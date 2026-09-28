(ns rule-fairy.markdown
  "Line-level Markdown structure shared by the engine: fenced code blocks, ATX
  headings with GitHub-style slugs, heading-bounded sections and paragraph-sized
  units. Nothing here knows about rules or instruction files."
  (:require [clojure.string :as str]))

(def ^:private heading-pattern
  #"^[ \t]{0,3}(#{1,6})[ \t]+(.+?)$")

(defn lines
  "Splits content on LF or CRLF line endings, keeping a trailing empty line."
  [content]
  (str/split content #"\r?\n" -1))

(defn opening-fence
  "The code fence a line opens, as {:character :length}, or nil."
  [line]
  (when-let [run (some-> (re-find #"^[ \t]{0,3}(`{3,}|~{3,})" line) second)]
    {:character (first run)
     :length (count run)}))

(defn closing-fence?
  "True when the line closes the open fence: the same character, at least as
  long, and nothing else on the line."
  [{:keys [character length]} line]
  (when-let [run (some-> (re-matches #"^[ \t]{0,3}(`+|~+)[ \t]*$" line) second)]
    (and (= character (first run))
         (>= (count run) length))))

(defn fenced-lines
  "One boolean per line: whether it lies inside a fenced code block, the fence
  lines themselves included."
  [lines]
  (loop [[line & more-lines] lines
         fence nil
         result []]
    (if line
      (if fence
        (recur more-lines
               (when-not (closing-fence? fence line) fence)
               (conj result true))
        (if-let [new-fence (opening-fence line)]
          (recur more-lines new-fence (conj result true))
          (recur more-lines nil (conj result false))))
      result)))

(defn heading
  "Parses an ATX heading line into {:level n :text string}, closing hashes
  removed, or nil when the line is not a heading."
  [line]
  (when-let [[_ hashes text] (re-matches heading-pattern line)]
    {:level (count hashes)
     :text (-> text
               (str/replace #"[ \t]+#+[ \t]*$" "")
               str/trim)}))

(def ^:private link-pattern
  "An inline link `[text](destination)`, its destination allowed one level
  of balanced parentheses as CommonMark does, or a reference link
  `[text][label]`, with the visible text as group 1."
  #"\[([^\]]*)\](?:\((?:[^()]|\([^()]*\))*\)|\[[^\]]*\])")

(def ^:private code-span-pattern
  "A code span: a run of backticks, literal content, the same run again."
  #"(`+)(.*?)\1")

(defn- links-to-text
  "text with every link outside a code span reduced to its visible text; a
  code span shows link syntax literally, so its content stays."
  [text]
  (let [outside (map #(str/replace % link-pattern "$1") (str/split text code-span-pattern -1))
        spans (map first (re-seq code-span-pattern text))]
    (apply str (interleave outside (concat spans [""])))))

(defn slug
  "GitHub-style anchor for heading text: links reduced to their visible
  text, then lower-cased, punctuation removed, whitespace replaced by
  hyphens."
  [text]
  (-> text
      links-to-text
      str/lower-case
      (str/replace #"[^\p{L}\p{N}\s_-]" "")
      (str/replace #"\s" "-")))

(defn- unique-slug
  "base when no earlier heading took it, else base-1, base-2 and so on up
  to the first one no earlier heading took, as GitHub does."
  [taken base]
  (if (taken base)
    (some (fn [n]
            (let [candidate (str base "-" n)]
              (when-not (taken candidate) candidate)))
          (iterate inc 1))
    base))

(defn headings
  "Headings outside fenced blocks as [{:level :text :line :slug}], where :line
  is the zero-based line index and a repeated slug is suffixed -1, -2 and so
  on, skipping a suffix another heading already produced, the way GitHub
  does. fenced? answers whether a line index is inside a fence."
  [lines fenced?]
  (:headings
   (reduce (fn [{:keys [taken] :as result} [index line]]
             (if (fenced? index)
               result
               (if-let [{:keys [text] :as parsed} (heading line)]
                 (let [heading-slug (unique-slug taken (slug text))]
                   (-> result
                       (update :headings conj (assoc parsed :line index :slug heading-slug))
                       (update :taken conj heading-slug)))
                 result)))
           {:headings [] :taken #{}}
           (map-indexed vector lines))))

(defn sections
  "Splits content at every heading outside fenced blocks. Returns
  [{:heading heading-or-nil :lines [...]}]: the first entry holds the preamble
  before the first heading and is left out when blank, and every other entry
  starts with its own heading line."
  [content]
  (let [all-lines (lines content)
        fenced (fenced-lines all-lines)
        heads (headings all-lines #(nth fenced %))
        starts (map :line heads)
        preamble (subvec all-lines 0 (or (first starts) (count all-lines)))
        heading-sections (map (fn [head next-start]
                                {:heading head
                                 :lines (subvec all-lines (:line head) next-start)})
                              heads
                              (concat (rest starts) [(count all-lines)]))]
    (vec (if (every? str/blank? preamble)
           heading-sections
           (cons {:heading nil :lines preamble} heading-sections)))))

(defn- finish-unit [units unit-lines fenced?]
  (if (seq unit-lines)
    (conj units {:content (str/join "\n" unit-lines)
                 :fenced? fenced?})
    units))

(defn units
  "Paragraph-sized units of content as [{:content :fenced?}]: text split at
  blank lines, headings and fence boundaries, with a fenced block kept whole."
  [content]
  (loop [[line & more-lines] (lines content)
         fence nil
         unit-lines []
         unit-fenced? false
         result []]
    (if-not line
      (finish-unit result unit-lines unit-fenced?)
      (let [new-fence (when-not fence (opening-fence line))
            boundary? (and (nil? fence)
                           (seq unit-lines)
                           (or unit-fenced?
                               (str/blank? line)
                               new-fence
                               (heading line)))]
        (cond
          (and (nil? fence) (empty? unit-lines) (str/blank? line))
          (recur more-lines nil [] false result)

          boundary?
          (recur (if (str/blank? line) more-lines (cons line more-lines))
                 nil
                 []
                 false
                 (finish-unit result unit-lines unit-fenced?))

          :else
          (let [next-fence (cond
                             (and fence (closing-fence? fence line)) nil
                             fence fence
                             :else new-fence)]
            (recur more-lines
                   next-fence
                   (conj unit-lines line)
                   (or unit-fenced? (some? fence) (some? new-fence))
                   result)))))))

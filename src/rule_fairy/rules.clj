(ns rule-fairy.rules
  "Rule discovery, matching and rendering shared by every harness adapter.

  A rule is a Markdown file with optional `---` frontmatter under a rules
  directory (Cursor's `.cursor/rules/**/*.mdc`). Frontmatter selects when the
  rule applies: `globs` by edited path, `alwaysApply` everywhere, and the hook
  extensions `promptAnyOf` and `promptRequires` by prompt text. Rule bodies may
  import documentation with standalone `@path` or `@path#heading` lines;
  imports resolve inside the project root, overlapping selections merge, and
  `<!-- agent-context: omit -->` blocks are removed."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.config :as config]
            [rule-fairy.markdown :as md]))

(def ^:private max-expanded-context-bytes (* 64 1024))

(def ^:private include-pattern
  #"^[ \t]*@([A-Za-z0-9._/-]+)(?:#([^ \t]+))?[ \t]*$")

(def ^:private omit-start "<!-- agent-context: omit -->")
(def ^:private omit-end "<!-- /agent-context -->")

;;; Rules source
;;
;; Where a project keeps its rules. The default is Cursor's layout, so a
;; repository that already has `.cursor/rules/**/*.mdc` needs no configuration.
;; A `rule-fairy.edn` at the project root can add or move directories and
;; extensions:
;;
;;   {:rules {:dirs [".cursor/rules" "docs/rules"] :extensions [".mdc" ".md"]}}

(def default-rules-config
  {:dirs [".cursor/rules"]
   :extensions [".mdc"]})

(defn- string-list? [value each-ok?]
  (and (sequential? value)
       (seq value)
       (every? #(and (string? %) (each-ok? %)) value)))

(defn project-rules-source
  "The rules source for the project at project-root:
  {:project-root path :dirs [path ...] :extensions [string ...]}. Directories
  are resolved against the root. Reads the optional `rule-fairy.edn` there,
  whose `:rules` map may set `:dirs` and `:extensions`."
  [project-root]
  (let [root (io/file project-root)
        rules-config (:rules (config/read-config root))
        _ (when (and (some? rules-config) (not (map? rules-config)))
            (throw (ex-info (str ":rules in " config/config-file " must be a map")
                            {:rules rules-config})))
        unknown-keys (remove #{:dirs :extensions} (keys rules-config))
        {:keys [dirs extensions]} (merge default-rules-config rules-config)]
    (when (seq unknown-keys)
      (throw (ex-info (str "Unknown :rules keys in " config/config-file ": "
                           (str/join ", " unknown-keys))
                      {:keys (vec unknown-keys)})))
    (when-not (string-list? dirs seq)
      (throw (ex-info (str ":rules :dirs in " config/config-file
                           " must be a non-empty vector of directory paths")
                      {:dirs dirs})))
    (when-not (string-list? extensions #(re-matches #"\.[A-Za-z0-9]+" %))
      (throw (ex-info (str ":rules :extensions in " config/config-file
                           " must be a non-empty vector of extensions with a leading dot, like [\".mdc\"]")
                      {:extensions extensions})))
    {:project-root (str root)
     :dirs (mapv #(str (.normalize (.resolve (.toPath root) %))) dirs)
     :extensions (vec extensions)}))

;;; Rule files and frontmatter

(def ^:private rule-pattern
  #"(?s)\A---[ \t]*\r?\n(?:(.*?)\r?\n)?---[ \t]*(?:\r?\n|\z)(.*)\z")

(def ^:private frontmatter-field-pattern
  #"([A-Za-z][A-Za-z0-9_-]*):[ \t]*(.*?)[ \t]*")

(defn- frontmatter-fields [frontmatter]
  (into {}
        (keep (fn [line]
                (when-let [[_ field value] (re-matches frontmatter-field-pattern line)]
                  [(keyword field) value])))
        (md/lines frontmatter)))

(defn parse-rule
  "Splits rule content into frontmatter fields and Markdown body.
  Returns {:frontmatter {field-keyword value} :body string}; :frontmatter is
  nil when the content has no frontmatter block. Values are trimmed strings."
  [content]
  (if-let [[_ frontmatter body] (re-matches rule-pattern content)]
    {:frontmatter (frontmatter-fields (or frontmatter ""))
     :body (str/trim body)}
    {:frontmatter nil
     :body (str/trim content)}))

(defn frontmatter-list
  "Returns a comma-separated frontmatter field as a vector of trimmed,
  non-empty strings; empty when the field is absent."
  [frontmatter field]
  (if-let [value (get frontmatter field)]
    (->> (str/split value #",")
         (map str/trim)
         (remove empty?)
         vec)
    []))

(defn rule-globs
  "The rule's `globs` patterns in declaration order."
  [frontmatter]
  (frontmatter-list frontmatter :globs))

(defn always-apply?
  "True when the rule sets `alwaysApply: true`, compared case-insensitively."
  [frontmatter]
  (= "true" (some-> (:alwaysApply frontmatter) str/lower-case)))

(defn prompt-trigger
  "The rule's prompt trigger as {:any-of terms :requires terms}, lower-cased,
  or nil when it declares no `promptAnyOf`. `promptRequires` alone never
  triggers: every required term must match in addition to one alternative."
  [frontmatter]
  (let [any-of (mapv str/lower-case (frontmatter-list frontmatter :promptAnyOf))]
    (when (seq any-of)
      {:any-of any-of
       :requires (mapv str/lower-case (frontmatter-list frontmatter :promptRequires))})))

(defn- rule-file? [{:keys [extensions]} file]
  (and (.isFile file)
       (some #(str/ends-with? (.getName file) %) extensions)))

(defn rule-files
  "Returns the rule files of a source with names relative to their own
  directory, sorted by name. A name found in more than one directory is an
  error: names identify rules in labels, glob caches and session state."
  [{:keys [dirs] :as source}]
  (let [files (vec (for [dir dirs
                         :let [rules-directory (io/file dir)
                               rules-path (.toPath rules-directory)]
                         file (file-seq rules-directory)
                         :when (rule-file? source file)]
                     {:file file
                      :rule-name (-> (str (.relativize rules-path (.toPath file)))
                                     (str/replace "\\" "/"))}))
        duplicates (->> (group-by :rule-name files)
                        (filter #(< 1 (count (val %))))
                        (into (sorted-map)))]
    (when (seq duplicates)
      (throw (ex-info (str "Rule names defined in more than one rules directory: "
                           (str/join ", " (keys duplicates)))
                      {:duplicates (into {} (map (fn [[rule-name entries]]
                                                   [rule-name (mapv #(str (:file %)) entries)]))
                                        duplicates)})))
    (vec (sort-by :rule-name files))))

(defn rule-file
  "The file of a named rule in the source, or nil when no directory has it."
  [{:keys [dirs]} rule-name]
  (some (fn [dir]
          (let [file (io/file dir rule-name)]
            (when (.isFile file) file)))
        dirs))

(defn rules-tree-mtime
  "Returns the newest relevant mtime across the source directories, or 0 when
  none exists. Directory mtimes are included so nested rule additions,
  removals, and renames invalidate caches as well as rule content changes."
  [{:keys [dirs] :as source}]
  (reduce max 0
          (for [dir dirs
                :let [rules-directory (io/file dir)]
                :when (.exists rules-directory)
                file (file-seq rules-directory)
                :when (or (.isDirectory file) (rule-file? source file))]
            (.lastModified file))))

(defn rule-index
  "Every rule of the source with its parsed selection frontmatter, sorted by
  name: [{:rule-name :globs :always-apply? :prompt-trigger}]."
  [source]
  (mapv (fn [{:keys [file rule-name]}]
          (let [{:keys [frontmatter]} (parse-rule (slurp file))]
            {:rule-name rule-name
             :globs (rule-globs frontmatter)
             :always-apply? (always-apply? frontmatter)
             :prompt-trigger (prompt-trigger frontmatter)}))
        (rule-files source)))

(defn always-apply-rule-names
  "Names of the indexed rules that apply regardless of path or prompt."
  [rule-index]
  (into [] (comp (filter :always-apply?) (map :rule-name)) rule-index))

;;; Prompt matching

(defn- contains-word? [prompt token]
  (boolean
   (re-find (re-pattern (str "(?<![a-z0-9])"
                             (java.util.regex.Pattern/quote token)
                             "(?![a-z0-9])"))
            prompt)))

(defn- contains-term? [prompt term]
  (if (re-matches #"[a-z0-9]+" term)
    (contains-word? prompt term)
    (str/includes? prompt term)))

(defn- matching-terms [prompt terms]
  (vec (filter #(contains-term? prompt %) terms)))

(defn- prompt-rule-match [prompt rule-name {:keys [requires any-of]}]
  (let [required-hits (matching-terms prompt requires)
        any-hits (matching-terms prompt any-of)]
    (when (and (= (count required-hits) (count requires))
               (seq any-hits))
      {:rule rule-name
       :keywords (vec (concat any-hits required-hits))})))

(defn rules-for-prompt
  "Returns the rules that apply to a prompt: every `alwaysApply` rule plus
  every rule whose prompt trigger matches, in rule-name order. Each match is
  {:rule name :always-apply? bool :keywords matched-terms}; a rule that is
  always-on and also keyword-matched reports both."
  [source prompt]
  (let [prompt-lower (str/lower-case prompt)]
    (->> (rule-index source)
         (keep (fn [{:keys [rule-name always-apply? prompt-trigger]}]
                 (let [trigger-match (when prompt-trigger
                                       (prompt-rule-match prompt-lower rule-name prompt-trigger))]
                   (when (or always-apply? trigger-match)
                     {:rule rule-name
                      :always-apply? always-apply?
                      :keywords (or (:keywords trigger-match) [])}))))
         vec)))

(defn prompt-match-reasons
  "Marker lines saying why prompt matches were selected: one naming the
  `alwaysApply` rules and one listing the matched keywords, each only when
  present. Adapters print them after the injected-rule markers."
  [matches]
  (let [always-on (filter :always-apply? matches)
        keywords (distinct (mapcat :keywords matches))]
    (cond-> []
      (seq always-on)
      (conj (str "[rule-fairy matched: alwaysApply " (str/join ", " (map :rule always-on)) "]"))

      (seq keywords)
      (conj (str "[rule-fairy matched: keyword \"" (str/join "\", \"" keywords) "\"]")))))

;;; Glob matching
;;
;; Rule globs follow Cursor's `globs` field: `*` and `?` match within one path
;; segment, `**` matches any number of segments, and a pattern without a `/`
;; matches its basename at any depth. Paths and patterns are compared with
;; forward slashes and without a leading `./` or `/`. Bracket expressions pass
;; through to the regular expression unchanged; braces are literal.

(def ^:private regex-special-characters
  #{\\ \. \+ \( \) \| \^ \$ \{ \}})

(defn normalize-path
  "Returns the path with forward slashes and without a leading `./` or `/`."
  [path]
  (-> (str path)
      (str/replace "\\" "/")
      (str/replace #"^\./" "")
      (str/replace #"^/" "")))

(defn- segment->regex [segment]
  (->> segment
       (map (fn [ch]
              (case ch
                \* "[^/]*"
                \? "[^/]"
                (str (when (regex-special-characters ch) "\\") ch))))
       (apply str)))

(defn- segments->regex [segments]
  (if-let [[segment & more-segments] (seq segments)]
    (if (= "**" segment)
      (if (seq more-segments)
        (str "(?:[^/]+/)*" (segments->regex more-segments))
        ".*")
      (str (segment->regex segment)
           (when (seq more-segments) "/")
           (segments->regex more-segments)))
    ""))

(defn glob-pattern
  "Compiles a rule glob into a regular expression over whole normalized paths."
  [glob]
  (let [glob (normalize-path glob)
        rooted? (str/includes? glob "/")]
    (try
      (re-pattern
       (str "^"
            (when-not rooted? "(?:.*/)?")
            (if rooted?
              (segments->regex (str/split glob #"/"))
              (segment->regex glob))
            "$"))
      (catch java.util.regex.PatternSyntaxException error
        (throw (ex-info (str "Invalid rule glob: " glob) {:glob glob} error))))))

(defn glob-matches?
  "True when the relative path matches the rule glob. Empty paths and globs
  match nothing."
  [path glob]
  (boolean
   (when (and (seq (str path)) (seq (str glob)))
     (re-matches (glob-pattern glob) (normalize-path path)))))

(defn rule-globs-index
  "Returns {rule-name globs} for the indexed rules that declare globs. This is
  the shape the hook adapters cache between events."
  [rule-index]
  (into {}
        (keep (fn [{:keys [rule-name globs]}]
                (when (seq globs)
                  [rule-name globs])))
        rule-index))

(defn rules-matching-path
  "Names in a globs index whose globs match the relative path, sorted."
  [globs-index relative-path]
  (->> globs-index
       (keep (fn [[rule-name globs]]
               (when (some #(glob-matches? relative-path %) globs)
                 rule-name)))
       sort
       vec))

;;; Markdown documents and imports

(defn- omission-ranges [source lines fenced?]
  (loop [index 0
         open-index nil
         ranges []]
    (if (= index (count lines))
      (if open-index
        (throw (ex-info (str "Unclosed agent-context omission in " source)
                        {:source source :line (inc open-index)}))
        ranges)
      (let [line (str/trim (nth lines index))]
        (cond
          (or (fenced? index)
              (and (not= line omit-start) (not= line omit-end)))
          (recur (inc index) open-index ranges)

          (= line omit-start)
          (if open-index
            (throw (ex-info (str "Nested agent-context omission in " source)
                            {:source source :line (inc index)}))
            (recur (inc index) index ranges))

          open-index
          (recur (inc index) nil (conj ranges [open-index (inc index)]))

          :else
          (throw (ex-info (str "Unexpected agent-context omission end in " source)
                          {:source source :line (inc index)})))))))

(defn- parse-document [source content]
  (let [lines (md/lines content)
        fenced-vector (md/fenced-lines lines)
        fenced? #(nth fenced-vector %)]
    {:lines lines
     :headings (md/headings lines fenced?)
     :omission-ranges (omission-ranges source lines fenced?)}))

(defn- parse-rule-body [content]
  (let [lines (md/lines (:body (parse-rule content)))
        fenced-vector (md/fenced-lines lines)]
    (reduce (fn [{:keys [body-lines] :as result} [index line]]
              (if (fenced-vector index)
                (update result :body-lines conj line)
                (if-let [[_ path fragment] (re-matches include-pattern line)]
                  (update result :includes conj {:path path :fragment fragment})
                  (assoc result :body-lines (conj body-lines line)))))
            {:body-lines [] :includes []}
            (map-indexed vector lines))))

(defn- project-root-path [project-root]
  (.toRealPath (.toPath (io/file project-root))
               (make-array java.nio.file.LinkOption 0)))

(defn- within-project-root? [root-path path]
  (let [root (str root-path)
        candidate (str path)]
    (or (= root candidate)
        (str/starts-with? candidate (str root java.io.File/separator)))))

(defn- include-path [root-path path]
  (let [candidate (.normalize (.resolve root-path path))]
    (when-not (within-project-root? root-path candidate)
      (throw (ex-info (str "Rule import escapes the project root: " path)
                      {:path path})))
    (when-not (.isFile (.toFile candidate))
      (throw (ex-info (str "Rule import does not exist: " path)
                      {:path path})))
    (let [real-path (.toRealPath candidate (make-array java.nio.file.LinkOption 0))]
      (when-not (within-project-root? root-path real-path)
        (throw (ex-info (str "Rule import resolves outside the project root: " path)
                        {:path path})))
      real-path)))

(defn- decoded-fragment [fragment]
  (some-> fragment
          (java.net.URLDecoder/decode "UTF-8")
          str/lower-case))

(defn- selection-range [path fragment {:keys [lines headings]}]
  (if-not fragment
    [0 (count lines)]
    (let [fragment (decoded-fragment fragment)
          heading (some #(when (= fragment (:slug %)) %) headings)]
      (when-not heading
        (throw (ex-info (str "Rule import heading does not exist: " path "#" fragment)
                        {:path path :fragment fragment})))
      [(:line heading)
       (or (some (fn [{:keys [line level]}]
                   (when (and (> line (:line heading))
                              (<= level (:level heading)))
                     line))
                 headings)
           (count lines))])))

(defn- resolve-selection [root-path document-cache {:keys [path fragment]}]
  (let [real-path (include-path root-path path)
        canonical-path (str real-path)
        document (or (get @document-cache canonical-path)
                     (let [parsed (parse-document path (slurp (.toFile real-path)))]
                       (swap! document-cache assoc canonical-path parsed)
                       parsed))
        [start end] (selection-range path fragment document)]
    {:canonical-path canonical-path
     :reference (str path (when fragment (str "#" fragment)))
     :document document
     :start start
     :end end}))

(defn- import-of
  "The import a selection came from: its reference and the importing rule."
  [selection]
  (select-keys selection [:reference :rule-name]))

(defn- merged-selection
  "A selection as the start of a merged range, carrying its own import."
  [selection]
  (assoc selection :imports [(import-of selection)]))

(defn- merge-ranges
  "Merges the selections of one document whose ranges overlap or touch into
  ranges carrying every import that reached them, in first-appearance
  order, so the rendered heading can still say which rule imports which
  reference."
  [selections]
  (reduce (fn [merged selection]
            (if-let [current (peek merged)]
              (if (<= (:start selection) (:end current))
                (conj (pop merged)
                      (-> current
                          (update :end max (:end selection))
                          (update :imports conj (import-of selection))))
                (conj merged (merged-selection selection)))
              [(merged-selection selection)]))
          []
          (sort-by (juxt :start :end) selections)))

(defn- importers-by-reference
  "The references of a merged range in first-appearance order, each with
  the rules importing it: [[reference [rule-name ...]] ...]."
  [imports]
  (for [reference (distinct (map :reference imports))]
    [reference (distinct (map :rule-name (filter #(= reference (:reference %)) imports)))]))

(defn- merged-selections [selections]
  (let [by-path (group-by :canonical-path selections)
        path-order (distinct (map :canonical-path selections))]
    (mapcat #(merge-ranges (get by-path %)) path-order)))

(defn- omitted-line? [omission-ranges line-index]
  (some (fn [[start end]]
          (<= start line-index (dec end)))
        omission-ranges))

(defn- selection-content [{:keys [document start end]}]
  (let [{:keys [lines omission-ranges]} document]
    (->> (range start end)
         (remove #(omitted-line? omission-ranges %))
         (map #(nth lines %))
         (str/join "\n")
         str/trim)))

;;; Rendering

(defn- render-block [{:keys [heading content]}]
  (str heading "\n\n" content))

(defn- reference-path
  "The root-relative file of an import reference, its heading fragment
  dropped."
  [reference]
  (normalize-path (first (str/split reference #"#" 2))))

(defn- context-data [{:keys [project-root] :as source} rule-names]
  (let [parsed-rules
        (mapv (fn [rule-name]
                (let [file (or (rule-file source rule-name)
                               (throw (ex-info (str "Rule does not exist: " rule-name)
                                               {:rule-name rule-name})))]
                  (assoc (parse-rule-body (slurp file)) :rule-name rule-name)))
              rule-names)
        root-path (project-root-path project-root)
        document-cache (atom {})
        selections (->> parsed-rules
                        (mapcat (fn [{:keys [rule-name includes]}]
                                  (map #(assoc % :rule-name rule-name) includes)))
                        distinct
                        (map (fn [{:keys [rule-name] :as include}]
                               (assoc (resolve-selection root-path document-cache include)
                                      :rule-name rule-name)))
                        merged-selections)
        rule-blocks (mapv (fn [{:keys [rule-name body-lines]}]
                           {:kind :rule
                            :label rule-name
                            :heading (str "# " rule-name)
                            :content (str/trim (str/join "\n" body-lines))})
                         parsed-rules)
        documentation-blocks
        (mapv (fn [{:keys [imports] :as selection}]
                (let [importers (importers-by-reference imports)
                      references (map first importers)]
                  {:kind :documentation
                   :label (str/join ", " references)
                   :paths (mapv reference-path references)
                   ;; Each reference names the rules importing it, so a reader
                   ;; of the bundle can tell which rule, and so which files, a
                   ;; section governs even when adjacent sections merged.
                   :heading (str "## Required context: "
                                 (str/join ", " (map (fn [[reference rule-names]]
                                                       (str "`" reference "` (" (str/join ", " rule-names) ")"))
                                                     importers)))
                   :content (selection-content selection)}))
              selections)
        expanded-content (->> documentation-blocks
                              (map render-block)
                              (str/join "\n\n---\n\n"))
        expanded-bytes (count (.getBytes expanded-content
                                         java.nio.charset.StandardCharsets/UTF_8))]
    (when (> expanded-bytes max-expanded-context-bytes)
      (throw (ex-info (str "Expanded rule documentation exceeds "
                           max-expanded-context-bytes " bytes")
                      {:expanded-bytes expanded-bytes
                       :max-bytes max-expanded-context-bytes})))
    {:rule-blocks rule-blocks
     :documentation-blocks documentation-blocks}))

(defn render-rule-blocks
  "Returns semantic rule and documentation blocks for bounded transports."
  [source rule-names]
  (let [{:keys [rule-blocks documentation-blocks]}
        (context-data source rule-names)]
    (into rule-blocks documentation-blocks)))

(defn- append-with-separator [content addition separator]
  (if (seq content)
    (str content separator addition)
    addition))

(defn- split-lines [content max-chars]
  (reduce (fn [parts line]
            (when (> (count line) max-chars)
              (throw (ex-info "A Markdown line exceeds the hook context frame limit"
                              {:line-chars (count line)
                               :max-chars max-chars})))
            (let [current (peek parts)
                  candidate (append-with-separator current line "\n")]
              (if (or (nil? current) (<= (count candidate) max-chars))
                (conj (pop parts) candidate)
                (conj parts line))))
          [nil]
          (md/lines content)))

(defn- bounded-markdown-units [content max-chars]
  (mapcat (fn [{:keys [content fenced?] :as unit}]
            (if (<= (count content) max-chars)
              [unit]
              (if fenced?
                (throw (ex-info "A fenced Markdown block exceeds the hook context frame limit"
                                {:block-chars (count content)
                                 :max-chars max-chars}))
                (map #(assoc unit :content %) (split-lines content max-chars)))))
          (md/units content)))

(defn- pack-markdown-units [units max-chars]
  (reduce (fn [parts {:keys [content]}]
            (let [current (peek parts)
                  candidate (append-with-separator current content "\n\n")]
              (if (or (nil? current) (<= (count candidate) max-chars))
                (conj (pop parts) candidate)
                (conj parts content))))
          [nil]
          units))

(defn split-block
  "Splits a semantic block at Markdown boundaries into self-contained parts no longer than max-chars."
  [{:keys [heading content]} max-chars]
  (let [heading-reserve (+ (count heading) 32)
        content-limit (- max-chars heading-reserve)]
    (when-not (pos? content-limit)
      (throw (ex-info "Rule block heading exceeds the hook context frame limit"
                      {:heading heading
                       :max-chars max-chars})))
    (let [part-contents (-> (bounded-markdown-units content content-limit)
                            (pack-markdown-units content-limit))
          part-count (count part-contents)]
      (mapv (fn [index part-content]
              (let [part-heading (if (= 1 part-count)
                                   heading
                                   (str heading " (part " (inc index) "/" part-count ")"))
                    part (str part-heading "\n\n" part-content)]
                (when (> (count part) max-chars)
                  (throw (ex-info "Rendered rule part exceeds the hook context frame limit"
                                  {:part-chars (count part)
                                   :max-chars max-chars})))
                part))
            (range part-count)
            part-contents))))

(defn render-rules
  "Renders rules and expands their standalone documentation imports."
  [source rule-names]
  (let [{:keys [rule-blocks documentation-blocks]}
        (context-data source rule-names)
        rendered-rules (->> rule-blocks
                           (map render-block)
                           (str/join "\n\n---\n\n"))
        rendered-selections (->> documentation-blocks
                                 (map render-block)
                                 (str/join "\n\n---\n\n"))]
    (str rendered-rules
         (when (seq rendered-selections)
           (str "\n\n---\n\n# Required documentation\n\n" rendered-selections)))))

(ns rule-fairy.dedup
  "Content-level deduplication of a review bundle. Instruction files are often
  symlinked, import each other, or carry chunks copied from one to the other
  when a harness lacks `@` imports, and rule text gets pasted into them. Given
  the bundle's blocks in source order, a section already emitted earlier is
  replaced by one line naming where the kept copy is, and so is a long
  paragraph or fenced block inside a surviving section. The first occurrence
  wins, so the canonical file belongs first in the bundle."
  (:require [clojure.string :as str]
            [rule-fairy.markdown :as md]))

(def min-unit-chars
  "Paragraphs and fenced blocks shorter than this are never treated as
  duplicates on their own: a repeated one-liner such as a test command is
  usually meant to appear in both places."
  200)

(defn- normalize-text [text]
  (-> (str/join "\n" (map str/trimr (md/lines text)))
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- normalize-heading [text]
  (-> text str/lower-case (str/replace #"\s+" " ") str/trim))

(defn omission-line
  "The line that replaces omitted content, naming the kept copy."
  [location]
  (str "*Duplicate of `" location "`, omitted.*"))

(defn- section-location [label heading]
  (if heading
    (str label "#" (:slug heading))
    label))

(defn- omission-part [location]
  {:text (omission-line location)
   :omission location
   :omissions [location]})

(defn- coalesce-omissions
  "Adjacent omission lines naming the same kept copy become one."
  [parts]
  (reduce (fn [result part]
            (if (and (:omission part) (= (:omission part) (:omission (peek result))))
              result
              (conj result part)))
          []
          parts))

(defn- dedup-units
  "Replaces long units of a section body already emitted elsewhere. Returns
  [state parts]; each part is {:text} or an omission part."
  [state location body]
  (reduce (fn [[state parts] {:keys [content]}]
            (let [normalized (normalize-text content)]
              (cond
                (< (count normalized) min-unit-chars)
                [state (conj parts {:text content})]

                (get-in state [:units normalized])
                [state (conj parts (omission-part (get-in state [:units normalized])))]

                :else
                [(assoc-in state [:units normalized] location)
                 (conj parts {:text content})])))
          [state []]
          (md/units body)))

(defn- dedup-section
  "Returns [state part] for one heading-bounded section of a block. A section
  whose heading text and body were emitted before becomes an omission part;
  otherwise its long units are checked one by one."
  [state label {:keys [heading lines]}]
  (let [heading-line (when heading (first lines))
        body (str/join "\n" (if heading (rest lines) lines))
        normalized-body (normalize-text body)
        section-key [(some-> heading :text normalize-heading) normalized-body]
        location (section-location label heading)]
    (cond
      (str/blank? normalized-body)
      [state {:text (str/trim (str/join "\n" lines))}]

      (get-in state [:sections section-key])
      [state (omission-part (get-in state [:sections section-key]))]

      :else
      (let [state (assoc-in state [:sections section-key] location)
            [state parts] (dedup-units state location body)
            parts (coalesce-omissions parts)
            omissions (vec (mapcat :omissions parts))]
        [state
         (if (seq omissions)
           {:text (str/join "\n\n" (cond->> (map :text parts)
                                     heading-line (cons heading-line)))
            :omissions omissions}
           {:text (str/trim (str/join "\n" lines))})]))))

(defn- dedup-block [state {:keys [label content] :as block}]
  (let [[state parts] (reduce (fn [[state parts] section]
                                (let [[state part] (dedup-section state label section)]
                                  [state (conj parts part)]))
                              [state []]
                              (md/sections content))
        parts (coalesce-omissions parts)
        omissions (vec (mapcat :omissions parts))]
    [state (assoc block
                  :content (if (seq omissions)
                             (str/join "\n\n" (map :text parts))
                             content)
                  :omissions omissions)]))

(defn dedup-blocks
  "blocks: [{:label string :content string ...}] in bundle order. Returns the
  same blocks with :content rewritten wherever a section or a long unit
  repeats earlier content, and :omissions listing the kept copy behind every
  omission line, as `label` or `label#heading-slug`. Content with nothing
  omitted is returned verbatim."
  [blocks]
  (second
   (reduce (fn [[state result] block]
             (let [[state block] (dedup-block state block)]
               [state (conj result block)]))
           [{:sections {} :units {}} []]
           blocks)))

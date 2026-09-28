(ns rule-fairy.bundle
  "The review bundle: one Markdown document holding everything a rules review
  has to read for a set of changed or planned paths. It contains the
  instruction files that apply to those paths, every rule whose globs match
  them plus every `alwaysApply` rule, and the documentation those rules
  import, deduplicated across the whole bundle with each omission replaced by
  a line naming the kept copy. Bundles live under the project's self-ignored
  `.rule-fairy/review/<mode>/`, one directory per review mode, so a diff
  review and a plan review in the same checkout never overwrite each other."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.config :as config]
            [rule-fairy.dedup :as dedup]
            [rule-fairy.git :as git]
            [rule-fairy.instructions :as instructions]
            [rule-fairy.rules :as rules]
            [rule-fairy.session-state :as session-state]))

;;; Inputs

(def ^:private named-escapes
  "The letter git writes after a backslash for each control character it
  quotes, plus quote and backslash, mapped to the byte it stands for."
  {\a 7 \b 8 \t 9 \n 10 \v 11 \f 12 \r 13 \" 34 \\ 92})

(defn- octal-digit? [b]
  (<= (int \0) b (int \7)))

(defn- unquote-git-path
  "Decodes the C-style quoting git applies to a path holding a quote, a
  backslash, a control character or, by default, a non-ASCII byte: the named
  escapes such as `\\\"`, `\\\\` and `\\n`, and octal `\\ooo` byte escapes.
  Decoding runs over the UTF-8 bytes: a backslash byte never occurs inside a
  multi-byte sequence, so a non-ASCII character git left literal passes
  through whole, surrogate pairs included."
  [quoted]
  (let [bytes (.getBytes ^String quoted "UTF-8")
        n (alength bytes)
        byte-at (fn [i] (bit-and (aget bytes i) 0xff))
        out (java.io.ByteArrayOutputStream.)]
    (loop [i 0]
      (when (< i n)
        (let [b (byte-at i)]
          (cond
            (or (not= (int \\) b) (= (inc i) n))
            (do (.write out (int b))
                (recur (inc i)))

            (octal-digit? (byte-at (inc i)))
            (let [digits (take-while octal-digit? (map byte-at (range (inc i) (min n (+ i 4)))))]
              (.write out (int (reduce (fn [value digit] (+ (* 8 value) (- digit (int \0)))) 0 digits)))
              (recur (+ i 1 (count digits))))

            :else
            (let [escaped (byte-at (inc i))]
              (.write out (int (get named-escapes (char escaped) escaped)))
              (recur (+ i 2)))))))
    (String. (.toByteArray out) "UTF-8")))

(def ^:private header-prefix "diff --git ")

(defn- symmetric-post-image
  "The path of an unquoted header whose two sides are the same path, read by
  halving the header body into `a/P b/P`. Nil for a rename, or when the
  halves disagree."
  [body]
  (let [n (quot (- (count body) 5) 2)]
    (when (and (pos? n)
               (odd? (count body))
               (str/starts-with? body "a/")
               (= " b/" (subs body (+ 2 n) (+ 5 n)))
               (= (subs body 2 (+ 2 n)) (subs body (+ 5 n))))
      (subs body (+ 5 n)))))

(defn- header-post-image
  "The post-image path of a `diff --git` header, whichever side git quoted.
  Git does not quote a path for spaces alone, so an unquoted path may itself
  contain ` b/`: the header is read as the same path on both sides first,
  which covers every header but a rename, and only then split at the last
  ` b/`. A rename is read from its `rename to` line before the header is
  consulted, so the split only ever sees headers git itself made ambiguous."
  [line]
  (let [body (subs line (count header-prefix))]
    (if (str/ends-with? body "\"")
      (when-let [[_ quoted] (re-find #"\"((?:[^\"\\]|\\.)*)\"$" body)]
        (let [path (unquote-git-path quoted)]
          (when (str/starts-with? path "b/")
            (subs path 2))))
      (or (symmetric-post-image body)
          (second (re-find #"^\"?a/.+ b/(.+)$" body))))))

(defn- unquote-if-quoted [path]
  (if (and (str/starts-with? path "\"") (str/ends-with? path "\""))
    (unquote-git-path (subs path 1 (dec (count path))))
    path))

(defn- destination-line
  "The path named by a `rename to` or `copy to` line in a file section's
  extended header, decoded when git quoted it; nil when the section has
  none. Only lines before the first hunk count."
  [section-lines]
  (some (fn [line]
          (when-let [[_ path] (re-find #"^(?:rename|copy) to (.+)$" line)]
            (unquote-if-quoted path)))
        (take-while #(not (or (str/starts-with? % "@@") (str/starts-with? % "--- ")))
                    section-lines)))

(defn- file-sections
  "The lines of a unified diff grouped per file, each group starting with
  its `diff --git` header. Text before the first header is dropped."
  [lines]
  (reduce (fn [sections line]
            (cond
              (str/starts-with? line header-prefix) (conj sections [line])
              (seq sections) (update sections (dec (count sections)) conj line)
              :else sections))
          []
          lines))

(defn- section-post-image [[header & extended]]
  (or (destination-line extended)
      (header-post-image header)))

(defn changed-paths-from-patch
  "The post-image paths of a unified diff, one per file section, in order of
  first appearance. A rename or copy takes its destination from the `rename
  to` or `copy to` line, which names it unambiguously; every other section
  takes it from its `diff --git` header. Paths git quoted, for a quote,
  backslash, control character or non-ASCII byte, are decoded."
  [patch-content]
  (->> (str/split-lines patch-content)
       file-sections
       (keep section-post-image)
       distinct
       vec))

(defn applicable-rule-names
  "Names of the indexed rules that apply to the changed paths: every rule with
  `alwaysApply` plus every rule whose globs match a changed path, sorted."
  [rule-index changed-paths]
  (let [globs-index (rules/rule-globs-index rule-index)]
    (->> changed-paths
         (mapcat #(rules/rules-matching-path globs-index %))
         (concat (rules/always-apply-rule-names rule-index))
         distinct
         sort
         vec)))

;;; Building

(defn- instruction-block [{:keys [path imported-by also-at content]}]
  {:kind :instructions
   :label path
   :heading (str "# " path
                 (when imported-by (str " (imported by " imported-by ")"))
                 (when (seq also-at) (str " (also " (str/join ", " also-at) ")")))
   :content content})

(defn- input-paths
  "The files the bundle was built from besides the rules, as written, for
  the revision check: the instruction files under every path they were
  displayed by and the file each was reached by, imported guidance outside
  the project included, the documentation the rules import, and the
  configuration file. rules-revision follows their links; the file as
  reached is what lets it, since a display path has `..` collapsed."
  [documents rule-blocks]
  (vec (distinct (concat (mapcat (fn [{:keys [path also-at file]}] (concat [path] also-at [file])) documents)
                         (mapcat :paths (filter #(= :documentation (:kind %)) rule-blocks))
                         [config/config-file]))))

(defn- listed [items]
  (if (seq items)
    (str/join ", " (map #(str "`" % "`") items))
    "none"))

(defn- header [{:keys [changed-paths instruction-paths rule-names rule-count omissions]}]
  (str "# Review bundle\n\n"
       "- Changed paths: " (count changed-paths) "\n"
       "- Instruction files: " (listed instruction-paths) "\n"
       "- Rules: " (listed rule-names) " (" (count rule-names) " of " rule-count " rule files)\n"
       "- Duplicates omitted: " (count omissions)
       (when (seq omissions) ", each replaced by a line naming the kept copy")))

(defn- rule-scope
  "One line under a rule's heading saying why it is in the bundle and which
  changed paths it covers, so a reviewer can tell which rules apply to which
  files when a change spans directories with different conventions."
  [{:keys [globs always-apply?]} changed-paths]
  (let [matched (filter (fn [path] (some #(rules/glob-matches? path %) globs)) changed-paths)]
    (str "*Scope: "
         (str/join "; " (cond-> []
                          always-apply? (conj "applies everywhere (alwaysApply)")
                          (seq globs) (conj (str "globs " (listed globs) "; matched " (listed matched)))))
         ".*")))

(defn- with-scopes [blocks rule-index changed-paths]
  (let [scopes (into {} (map (fn [{:keys [rule-name] :as entry}]
                               [rule-name (rule-scope entry changed-paths)]))
                     rule-index)]
    (mapv (fn [{:keys [kind label] :as block}]
            (if (= :rule kind)
              (assoc block :scope (scopes label))
              block))
          blocks)))

(defn- render-block [{:keys [heading scope content]}]
  (str heading "\n\n" (when scope (str scope "\n\n")) content))

(defn- render [summary blocks]
  (let [{instruction-blocks :instructions
         rule-blocks :rule
         documentation-blocks :documentation} (group-by :kind blocks)
        parts (concat [(header summary)]
                      (map render-block instruction-blocks)
                      (map render-block rule-blocks)
                      (when (seq documentation-blocks)
                        [(str "# Required documentation\n\n"
                              (str/join "\n\n---\n\n" (map render-block documentation-blocks)))]))]
    (str (str/join "\n\n---\n\n" parts) "\n")))

(defn build
  "Builds the bundle for changed-paths under project-root. Returns
  {:changed-paths :instruction-paths :input-paths :rule-names :rule-count
  :omissions :bundle}: :input-paths are the files besides the rules that
  went in (see input-paths), :rule-count is the number of rule files in the
  rules source and :omissions lists {:in label :kept location} for every
  omission line. Every rule block carries a scope line naming its globs and
  the changed paths they matched, or that it applies everywhere."
  [project-root changed-paths]
  (let [changed-paths (mapv rules/normalize-path changed-paths)
        rules-source (rules/project-rules-source project-root)
        documents (instructions/instruction-documents
                   (instructions/project-instructions-source project-root)
                   changed-paths)
        rule-index (rules/rule-index rules-source)
        rule-names (applicable-rule-names rule-index changed-paths)
        rule-blocks (rules/render-rule-blocks rules-source rule-names)
        blocks (dedup/dedup-blocks
                (into (mapv instruction-block documents)
                      (with-scopes rule-blocks rule-index changed-paths)))
        summary {:changed-paths changed-paths
                 :instruction-paths (mapv :path documents)
                 :input-paths (input-paths documents rule-blocks)
                 :rule-names rule-names
                 :rule-count (count rule-index)
                 :omissions (vec (for [{:keys [label omissions]} blocks
                                       kept omissions]
                                   {:in label :kept kept}))}]
    (assoc summary :bundle (render summary blocks))))

;;; Revisions

(defn rules-revision
  "Where the guidance a review reads comes from: the working tree's rules
  directories as configured, relative to the root when inside it, the HEAD
  they sit on, and whether the guidance carries uncommitted changes. The
  check covers the rules directories and every rule file in them,
  `rule-fairy.edn`, and input-paths, the bundle's other inputs (see build),
  each as written and where it resolves to (see git/locate), a tracked file
  or directory deleted locally included. Cleanliness is false when any of
  it is modified, untracked or ignored by git (see git/clean?), and nil,
  unknown, outside git and when any of that guidance lies outside the
  checkout, as a configured directory, a link's target or an imported file,
  where git cannot see it. The README's design note on `rules.clean` is the
  canonical statement. Keys are the names written to a review's meta.json."
  ([project-root] (rules-revision project-root []))
  ([project-root input-paths]
   (let [source (rules/project-rules-source project-root)
         dirs (mapv #(git/locate project-root %) (:dirs source))
         inputs (concat dirs
                        (map #(git/locate project-root (:file %)) (rules/rule-files source))
                        (map #(git/locate project-root %) (cons config/config-file input-paths)))]
     {:dirs (mapv :written dirs)
      :head_ref_oid (git/head-oid project-root)
      :clean (when (every? :real inputs)
               (git/clean? project-root (distinct (mapcat (juxt :written :real) inputs))))})))

;;; Artifacts

(def review-modes
  "Review modes, each with its own artifact directory: a diff review reads a
  patch, a plan review reads a plan."
  #{:diff :plan})

(defn- review-root [project-root]
  (io/file project-root ".rule-fairy" "review"))

(defn review-dir
  "The artifact directory of a review mode: `.rule-fairy/review/<mode>/`
  under the project root."
  [project-root mode]
  (when-not (review-modes mode)
    (throw (ex-info (str "Unknown review mode: " mode) {:mode mode})))
  (io/file (review-root project-root) (name mode)))

(defn bundle-file
  "Where the mode's bundle is written."
  [project-root mode]
  (io/file (review-dir project-root mode) "rules.md"))

(defn ensure-review-dir!
  "Creates the mode's artifact directory under the self-ignored review root
  and returns it."
  [project-root mode]
  (let [dir (review-dir project-root mode)]
    (session-state/ensure-dir! (review-root project-root))
    (.mkdirs dir)
    dir))

(defn clean!
  "Deletes the mode's artifact directory. Review artifacts belong to one run:
  the skills call this once the report is out, so a later review cannot pick
  up this one's inputs by mistake. A symlink inside the directory is removed,
  never followed. Returns the directory."
  [project-root mode]
  (let [dir (review-dir project-root mode)]
    (fs/delete-tree dir)
    dir))

(defn write!
  "Builds the bundle for changed-paths and writes it to the mode's bundle
  file, creating the self-ignored review directory. Nothing is written when
  building fails. Returns the build result with :file added."
  [project-root mode changed-paths]
  (let [result (build project-root changed-paths)
        file (bundle-file project-root mode)]
    (ensure-review-dir! project-root mode)
    (spit file (:bundle result))
    (assoc result :file file)))

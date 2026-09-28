(ns rule-fairy.instructions
  "Harness instruction files for a review bundle: `AGENTS.md`, `CLAUDE.md` and
  their relatives. Selects the files that apply to a set of project paths in
  the order the harnesses read them, the union of what either harness reads
  less an `AGENTS.md` that an override supersedes for both, follows `@path`
  imports the way Claude Code does, strips block-level HTML comments as
  Claude Code does before injection, and includes each real file once however
  it is reached, so a symlinked or imported file is not read twice."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.config :as config]
            [rule-fairy.markdown :as md]
            [rule-fairy.git :as git]
            [rule-fairy.rules :as rules]))

;;; Source

(def default-instruction-files
  "File names looked up in the project root and in every ancestor directory of
  a changed path, in bundle order. Codex reads the first AGENTS file present
  in each directory, the override before AGENTS.md; Claude Code reads the
  CLAUDE files when any exists at the root or above, and otherwise AGENTS.md
  in every directory and .claude/AGENTS.md at the root, never the override,
  with CLAUDE.local.md last in each directory."
  ["AGENTS.override.md" "AGENTS.md" ".claude/AGENTS.md"
   "CLAUDE.md" ".claude/CLAUDE.md" "CLAUDE.local.md"])

(def ^:private claude-file-names
  "The files whose presence makes Claude Code read CLAUDE files instead of
  AGENTS.md: at the root for the whole session, in a subdirectory for that
  directory's AGENTS.md."
  ["CLAUDE.md" ".claude/CLAUDE.md" "CLAUDE.local.md"])

(defn project-instructions-source
  "The instruction-file source for the project at project-root:
  {:project-root path :files [relative-name ...]}. Reads the optional
  `rule-fairy.edn` there, whose `:instructions` map may set `:files`. Without
  it every default file name is looked up; an explicit empty vector includes
  no instruction files."
  [project-root]
  (let [root (io/file project-root)
        instructions (:instructions (config/read-config root))
        _ (when (and (some? instructions) (not (map? instructions)))
            (throw (ex-info (str ":instructions in " config/config-file " must be a map")
                            {:instructions instructions})))
        unknown-keys (remove #{:files} (keys instructions))
        files (get instructions :files default-instruction-files)]
    (when (seq unknown-keys)
      (throw (ex-info (str "Unknown :instructions keys in " config/config-file ": "
                           (str/join ", " unknown-keys))
                      {:keys (vec unknown-keys)})))
    (when-not (and (sequential? files)
                   (every? #(and (string? %) (not (str/blank? %))) files))
      (throw (ex-info (str ":instructions :files in " config/config-file
                           " must be a vector of file names relative to a directory,"
                           " like [\"AGENTS.md\" \"CLAUDE.md\"]")
                      {:files files})))
    {:project-root (str root)
     :files (mapv rules/normalize-path files)}))

;;; Directories

(defn- ancestor-directories
  "Directories from the project root down to the path's own directory, as
  relative strings with \"\" for the root."
  [relative-path]
  (let [segments (butlast (str/split (rules/normalize-path relative-path) #"/"))]
    (map #(str/join "/" (take % segments))
         (range 0 (inc (count segments))))))

(defn- directory-depth [directory]
  (if (= "" directory)
    0
    (inc (count (filter #{\/} directory)))))

(defn instruction-directories
  "Every directory whose instruction files apply to the changed paths: the
  root first, then subdirectories ordered by depth and name. Harnesses read a
  subdirectory's instruction files when they work on files there."
  [changed-paths]
  (->> (cons "" (mapcat ancestor-directories changed-paths))
       distinct
       (sort-by (juxt directory-depth identity))
       vec))

(defn- in-directory [directory file-name]
  (if (= "" directory) file-name (str directory "/" file-name)))

(defn- claude-file-in? [project-root directory]
  (boolean (some #(.isFile (io/file project-root (in-directory directory %)))
                 claude-file-names)))

(defn- discovered-files
  "The instruction files present in the directories the changed paths reach,
  in bundle order, less the files no harness reads. An AGENTS.md beside an
  AGENTS.override.md is left out when Codex reads only the override and
  Claude Code, which reads AGENTS.md only while no CLAUDE file exists at the
  root or in that directory, does not read it either. A .claude/AGENTS.md,
  which Codex never reads, is kept only at the root and only while no CLAUDE
  file exists there. Each rule applies only when the source list includes
  the file whose presence it depends on, the override or CLAUDE.md, so a
  configured list naming AGENTS.md or .claude/AGENTS.md alone is taken as
  written."
  [{:keys [project-root files]} changed-paths]
  (let [override-included? (boolean (some #{"AGENTS.override.md"} files))
        claude-included? (boolean (some #{"CLAUDE.md"} files))
        claude-at-root? (claude-file-in? project-root "")]
    (for [directory (instruction-directories changed-paths)
          :let [override? (and override-included?
                               (.isFile (io/file project-root (in-directory directory "AGENTS.override.md"))))
                agents-superseded? (and override?
                                        (or claude-at-root? (claude-file-in? project-root directory)))
                dot-agents-inactive? (and claude-included?
                                          (or (not= "" directory) claude-at-root?))]
          file-name files
          :let [path (in-directory directory file-name)
                file (io/file project-root path)]
          :when (and (.isFile file)
                     (not (and agents-superseded? (= "AGENTS.md" file-name)))
                     (not (and dot-agents-inactive? (= ".claude/AGENTS.md" file-name))))]
      {:path path :file file})))

;;; Content

(defn- block-comment-end
  "Index just past the last line of the block-level HTML comment opening at
  start, or nil when the comment is not block-level: it shares its opening or
  closing line with other text, or never closes."
  [lines start]
  (loop [index start]
    (when (< index (count lines))
      (let [line (str/trim (nth lines index))
            remainder (if (= index start) (subs line (count "<!--")) line)]
        (if-let [close (str/index-of remainder "-->")]
          (when (str/blank? (subs remainder (+ close (count "-->"))))
            (inc index))
          (recur (inc index)))))))

(defn strip-block-comments
  "Removes HTML comments that occupy whole lines outside fenced code blocks,
  as Claude Code does before injecting an instruction file. Comments sharing a
  line with other text and comments inside code blocks stay."
  [content]
  (let [lines (md/lines content)
        fenced (md/fenced-lines lines)]
    (loop [index 0
           kept []]
      (if (= index (count lines))
        (str/join "\n" kept)
        (let [line (nth lines index)
              end (and (not (nth fenced index))
                       (str/starts-with? (str/triml line) "<!--")
                       (block-comment-end lines index))]
          (if end
            (recur end kept)
            (recur (inc index) (conj kept line))))))))

;;; Imports

(def max-import-depth
  "Hops of `@path` imports followed from an instruction file, Claude Code's
  limit."
  4)

(def ^:private code-span-pattern #"(`+).*?\1")

(def ^:private import-pattern #"(?<![\w@])@([~/\w.-][\w./-]*)")

(def ^:private trailing-punctuation-pattern #"[.,;:!?)]+$")

(defn import-references
  "The `@path` import tokens of an instruction file in order of appearance,
  skipping fenced code blocks and code spans. Trailing sentence punctuation is
  not part of a path."
  [content]
  (let [lines (md/lines content)
        fenced (md/fenced-lines lines)]
    (->> (map-indexed vector lines)
         (remove (fn [[index _]] (nth fenced index)))
         (mapcat (fn [[_ line]]
                   (map second (re-seq import-pattern (str/replace line code-span-pattern "")))))
         (map #(str/replace % trailing-punctuation-pattern ""))
         (remove str/blank?)
         distinct
         vec)))

(defn- import-file
  "The existing regular file an import reference names, resolved the way
  Claude Code resolves it: `~/` from the home directory, `/` absolute, anything
  else relative to the importing file. Nil when there is no such file."
  [importing-file reference]
  (let [file (cond
               (str/starts-with? reference "~/")
               (io/file (System/getProperty "user.home") (subs reference 2))

               (str/starts-with? reference "/")
               (io/file reference)

               :else
               (io/file (.getParentFile (.getAbsoluteFile importing-file)) reference))]
    (when (.isFile file) file)))

(defn- display-path
  "The path shown for a file: relative to the project root when inside it,
  absolute otherwise."
  [project-root file]
  (:written (git/locate project-root (.getAbsolutePath file))))

(defn- include-document
  "Adds the file and, depth first, the files it imports to state
  {:documents [...] :seen {real-path index}}, where :seen holds the position
  of every included file. A file reached again under another display path,
  through a symlink in another directory or an import, is not added twice:
  the path joins the kept document's :also-at, so a bundle can show every
  directory the content governs."
  [{:keys [seen] :as state} project-root display file imported-by depth]
  (let [identity (str (git/real-path (.toPath file)))]
    (if-let [index (seen identity)]
      (let [{:keys [path also-at]} (get-in state [:documents index])]
        (if (or (= display path) (some #{display} also-at))
          state
          (update-in state [:documents index :also-at] (fnil conj []) display)))
      (let [content (str/trim (strip-block-comments (slurp file)))
            state (-> state
                      (assoc-in [:seen identity] (count (:documents state)))
                      (update :documents conj {:path display
                                               :file (.getAbsolutePath file)
                                               :imported-by imported-by
                                               :content content}))]
        (if (< depth max-import-depth)
          (reduce (fn [state reference]
                    (if-let [imported (import-file file reference)]
                      (include-document state project-root (display-path project-root imported)
                                        imported display (inc depth))
                      state))
                  state
                  (import-references content))
          state)))))

(defn instruction-documents
  "The instruction files that apply to changed-paths under the source's
  project root, in bundle order, each as {:path display-path :file path
  :content text :imported-by path-or-nil}, plus :also-at [display-path ...]
  when the same file was reached again under other paths. A display path is
  relative to the root when the file lies inside it, absolute otherwise,
  with `.` and `..` collapsed; :file is the file as reached, absolute and
  uncollapsed, so a `..` after a link can still be followed where the file
  really lies (see rule-fairy.git/locate). Files found in
  a directory come in :files order, each followed by the files it imports,
  depth first, up to max-import-depth hops. Content has block-level HTML
  comments removed and LF line endings. A file reached a second time by any
  route is not repeated."
  [{:keys [project-root] :as source} changed-paths]
  (:documents
   (reduce (fn [state {:keys [path file]}]
             (include-document state project-root path file nil 0))
           {:documents [] :seen {}}
           (discovered-files source changed-paths))))

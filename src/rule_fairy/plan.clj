(ns rule-fairy.plan
  "Which files a plan intends to create or edit, so the plan can be reviewed
  against the rules that apply to those paths before any code exists. A plan
  is Markdown; paths are taken from its file-list sections when it has any,
  and otherwise from every path-like token in the text. A path naming an
  existing directory stands for the files under it. Glob matching later
  works on the path string, so a file the plan will create triggers its
  rules the same way an existing one does."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.git :as git]
            [rule-fairy.markdown :as md]
            [rule-fairy.rules :as rules]))

(def ^:private list-heading-pattern
  #"(?i)(?:files?(?: to (?:edit|change|touch|create|modify|add))?|(?:touched|changed|affected|modified) files|files? (?:touched|changed|affected|modified))")

(def ^:private list-item-pattern #"^\s*(?:[-*+]|\d+[.)])\s+")

(def ^:private code-span-pattern #"`([^`\n]+)`")

(def ^:private bare-path-pattern
  "A bare token with at least one inner slash, optionally rooted at `/`,
  `./` or `../`. It never starts right after a slash, so the parts of a URL
  after its scheme do not count."
  #"(?<![\w/.-])(?:/|\.{1,2}/)?[\w.-]+(?:/[\w.-]+)+/?")

(def ^:private extension-pattern #"\.[A-Za-z0-9]{1,10}$")

(defn- clean-token
  "Strips a `:line` or `#anchor` suffix, trailing punctuation and a
  directory's trailing slash, then normalises the path. An absolute path
  stays absolute; project-path places it under the root later."
  [token]
  (let [cleaned (-> (str/trim token)
                    (str/replace #"[:#][^/]*$" "")
                    (str/replace #"[.,;:)]+$" "")
                    (str/replace #"/+$" ""))]
    (cond->> (rules/normalize-path cleaned)
      (str/starts-with? cleaned "/") (str "/"))))

(defn- path-like?
  "A token counts as a path when it has no whitespace, glob characters, URL
  scheme or parentheses and either contains a slash (a `docs/` directory
  reference included) or, for a code span, ends in a file extension once
  cleaned."
  [raw cleaned code-span?]
  (and (not (str/blank? cleaned))
       (not (re-find #"[\s*?(]|://" raw))
       (or (str/includes? raw "/")
           (and code-span? (boolean (re-find extension-pattern cleaned))))))

(defn- line-candidates [line]
  (let [candidate (fn [code-span? raw]
                    (let [cleaned (clean-token raw)]
                      (when (path-like? raw cleaned code-span?)
                        cleaned)))]
    (concat (keep #(candidate true (second %)) (re-seq code-span-pattern line))
            (keep #(candidate false %)
                  (re-seq bare-path-pattern (str/replace line code-span-pattern " "))))))

(defn candidate-paths
  "Path-like tokens in plan text in order of first appearance: every code
  span holding a path or a file name with an extension, and every bare token
  containing a slash, absolute ones and Markdown link destinations
  included. Fenced blocks are included, since plans list files in trees and
  commands too."
  [content]
  (vec (distinct (mapcat line-candidates (md/lines content)))))

(defn listed-paths
  "Paths from every section headed like \"Files\", \"Files to change\" or
  \"Affected files\", in document order: the first path-like token of each
  list item. A plan often keeps several such sections, files to create and
  files to modify, and all of them count. Nil when the plan has no such
  section or they list nothing."
  [content]
  (let [paths (->> (md/sections content)
                   (filter (fn [{:keys [heading]}]
                             (some->> heading :text str/trim (re-matches list-heading-pattern))))
                   (mapcat #(rest (:lines %)))
                   (filter #(re-find list-item-pattern %))
                   (keep #(first (line-candidates %)))
                   distinct
                   vec)]
    (when (seq paths) paths)))

(defn project-path
  "path as the project's rule globs see it: a relative path normalised, an
  absolute path under the project root made relative to it with links
  followed (see git/locate), and nil for an absolute path outside the
  project, elsewhere in its checkout included, which none of its rules
  covers."
  [project-root path]
  (if (fs/absolute? path)
    (let [real (:real (git/locate project-root path))]
      (when (and real (not= ".." (first (str/split real #"/"))))
        real))
    (rules/normalize-path path)))

(defn- in-checkout?
  "True when the path exists or its parent directory does, so a file the
  plan will create in an existing directory still counts."
  [project-root path]
  (let [file (io/file project-root path)]
    (or (.exists file)
        (boolean (some-> (.getParentFile file) .isDirectory)))))

(defn- files-under
  "The regular files under an existing directory of the project, as
  root-relative paths in sorted order. A `.git` directory is skipped and
  symbolic links are not followed."
  [project-root directory]
  (let [root (fs/absolutize (io/file project-root))]
    (->> (fs/glob (fs/file root directory) "**" {:hidden true})
         (filter fs/regular-file?)
         (map #(rules/normalize-path (str (fs/relativize root %))))
         (remove #(some #{".git"} (str/split % #"/")))
         sort
         vec)))

(defn- expand-directories
  "Replaces each path naming an existing directory that holds files with
  those files, counting them per directory under :expanded. A directory
  without files, or one the plan will create, stays a path: globs rooted at
  its parent still match it."
  [project-root paths]
  (reduce (fn [result path]
            (let [files (when (.isDirectory (io/file project-root path))
                          (files-under project-root path))]
              (if (seq files)
                (-> result
                    (update :paths into files)
                    (assoc-in [:expanded path] (count files)))
                (update result :paths conj path))))
          {:paths [] :expanded {}}
          paths))

(defn plan-paths
  "The paths a plan intends to touch under project-root as
  {:paths [...] :dropped [...] :expanded {directory file-count}
  :source :list|:extracted}. File-list sections win and are taken as
  written; otherwise every path-like token is kept when it exists in the
  checkout or its parent directory does, and :dropped names the rest so a
  caller can add back what the heuristic missed. Either way an absolute
  path is placed under the root (see project-path), one outside the
  project is dropped, and a path naming an existing directory is replaced
  by the files under it."
  [project-root content]
  (let [listed (listed-paths content)
        {placed :placed outside :outside}
        (reduce (fn [result path]
                  (if-let [placed (project-path project-root path)]
                    (update result :placed conj placed)
                    (update result :outside conj path)))
                {:placed [] :outside []}
                (or listed (candidate-paths content)))
        {kept true dropped false} (when-not listed
                                    (group-by #(in-checkout? project-root %) placed))
        {:keys [paths expanded]} (expand-directories project-root (if listed placed kept))]
    {:paths (vec (distinct (if listed paths (sort paths))))
     :dropped (vec (sort (concat outside dropped)))
     :expanded expanded
     :source (if listed :list :extracted)}))

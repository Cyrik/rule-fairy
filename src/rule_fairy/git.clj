(ns rule-fairy.git
  "The git and shell access the review skills share: running a command with
  checked exit codes, the revision facts recorded next to review artifacts,
  and where a path lies relative to a root, for glob matching and for the
  cleanliness check."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.nio.file Files LinkOption Path]))

(defn checkout-root
  "The checkout containing dir: the nearest directory, dir itself included,
  with a `.git` entry (a directory, or the file a worktree or submodule
  carries), as a File; nil when there is none. Walks the filesystem, no git
  process, so it costs nothing on a hook's path."
  [dir]
  (some (fn [candidate] (when (.exists (io/file candidate ".git")) candidate))
        (take-while some? (iterate #(.getParentFile ^java.io.File %) (io/file dir)))))

(defn- checkout-root-path [^java.nio.file.Path directory]
  (some-> (checkout-root (.toFile directory)) .toPath))

(defn- without-prefix
  "path less its leading prefix, or path itself when the prefix is empty,
  not its start, or all of it."
  [prefix path]
  (if (and (seq (str prefix))
           (.startsWith path prefix)
           (< (.getNameCount prefix) (.getNameCount path)))
    (.subpath path (.getNameCount prefix) (.getNameCount path))
    path))

(defn rule-path
  "The path of file as the project's rule globs are written, as a Path:
  relative to the checkout enclosing the file, less the project's own
  position inside its checkout. A session started in a subdirectory of its
  repository thus matches `src/**` for an edit under it, and so does an edit
  in a linked worktree of the repository, where the file sits under that
  subdirectory again. Outside git the project directory is the root. Both
  arguments are absolute, normalised Paths; a file the root cannot reach
  comes back absolute."
  [project file]
  (let [project-prefix (some-> (checkout-root-path project) (.relativize project))
        root (or (some-> (.getParent file) checkout-root-path) project)]
    (try
      (without-prefix project-prefix (.relativize root file))
      (catch IllegalArgumentException _
        file))))

(def ^:private follow-links (make-array LinkOption 0))

(defn real-path
  "path with its links followed, as an absolute Path, read the way the
  filesystem reads it: a `..` after a link steps out of the link's target,
  not out of the link, so nothing is collapsed before the links are
  followed. A path that does not exist resolves through its nearest
  existing ancestor, so a file still to be created under a linked directory
  and a tracked file deleted locally both land where git would report
  them; only that tail, where no link can sit, is collapsed."
  [^Path path]
  (let [path (.toAbsolutePath path)
        existing (some #(when (Files/exists % follow-links) %)
                       (take-while some? (iterate #(.getParent ^Path %) path)))]
    (.normalize (.resolve (.toRealPath ^Path existing follow-links) (.relativize ^Path existing path)))))

(defn- slashed [path]
  (str/replace (str path) "\\" "/"))

(defn locate
  "Where a guidance path lies relative to project-root, as {:written :real}.
  :written is the path as given, normalised, and relative to the root when
  it is absolute and lies under it. :real is the file with links followed,
  relative to the root with its own links followed, through `..` when the
  file lies elsewhere in the checkout holding the root, as a subproject's
  rules linked to a shared directory do; nil when it lies outside the
  checkout, where git cannot see it. Outside git the root bounds it. Git
  reports an edit under a link's target, not the link, so a cleanliness
  check needs both forms, and it reads a `..` pathspec from the root. The
  written form collapses `.` and `..` lexically, as git reads a pathspec;
  the real form lets the filesystem do it (see real-path)."
  [project-root path]
  (let [root (.toAbsolutePath (.toPath (io/file project-root)))
        given (.toPath (io/file (str path)))
        absolute (if (.isAbsolute given) given (.resolve root given))
        real (real-path absolute)
        real-root (real-path root)
        checkout (.toPath (or (checkout-root (.toFile real-root)) (.toFile real-root)))
        lexical-root (.normalize root)
        lexical (.normalize absolute)]
    {:written (slashed (if (.startsWith lexical lexical-root) (.relativize lexical-root lexical) lexical))
     :real (when (.startsWith real checkout)
             (slashed (.relativize real-root real)))}))

(defn run
  "Runs command, a vector of program and arguments, in dir and returns its
  stdout. options: :ok-exits, the exit codes that count as success (default
  #{0}), :env, extra environment variables, and :in, a string fed to stdin.
  Any other exit fails with the command's stderr."
  ([dir command] (run dir command {}))
  ([dir command {:keys [ok-exits env in] :or {ok-exits #{0}}}]
   (let [{:keys [exit out err]} (apply p/shell (cond-> {:dir dir :out :string :err :string :continue true
                                                        :extra-env (or env {})}
                                                 in (assoc :in in))
                                       command)]
     (when-not (ok-exits exit)
       (throw (ex-info (str (str/join " " command) " failed (exit " exit "): " (str/trim err))
                       {:command command :exit exit})))
     out)))

(defn git
  "Runs git in root and returns its trimmed stdout; fails on a non-zero exit.
  Not for NUL-delimited listings, whose first entry may begin with the space
  of an unchanged index column: those go through run."
  [root & args]
  (str/trim (run root (into ["git"] args))))

(defn try-git
  "Like git, but nil when the command fails, such as outside a repository."
  [root & args]
  (let [{:keys [exit out]} (apply p/shell {:dir root :out :string :err :string :continue true} "git" args)]
    (when (zero? exit)
      (str/trim out))))

(defn head-oid
  "The commit HEAD points at, or nil outside a repository or before the
  first commit."
  [root]
  (try-git root "rev-parse" "HEAD"))

(defn empty-tree-oid
  "The hash of the empty tree, the baseline before the first commit."
  [root]
  (git root "hash-object" "-t" "tree" "/dev/null"))

(defn repo-slug
  "owner/repo from the origin remote when it points at GitHub, else nil."
  [root]
  (some-> (try-git root "remote" "get-url" "origin")
          (->> (re-find #"github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?/?$"))
          second))

(defn- ignored-among?
  "True when an ignored entry from git status names one of the paths, or
  is a directory holding one: status lists an ignored directory once, not
  its files. An ignored file inside a tracked directory is not one of the
  paths and does not count."
  [entries paths]
  (boolean (some (fn [entry]
                   (some (fn [path]
                           (or (= entry path)
                               (and (str/ends-with? entry "/") (str/starts-with? path entry))))
                         paths))
                 entries)))

(defn- checkout-relative
  "paths, given relative to root, in the form git status prints paths:
  relative to the checkout holding root, whatever the current directory.
  Unchanged when root is not in a checkout."
  [root paths]
  (let [project (real-path (.toPath (io/file root)))]
    (if-let [checkout (some-> (checkout-root (.toFile project)) .toPath)]
      (map #(slashed (.relativize checkout (.normalize (.resolve project %)))) paths)
      paths)))

(defn clean?
  "True when the paths are what is committed: they carry no uncommitted
  changes and git ignores none of them, since ignored guidance never
  reached a commit either, the same as an untracked file. False otherwise,
  nil outside a repository. One status call answers both; its entries are
  relative to the checkout, so the paths are compared in that form, and
  the untracked mode is pinned because status.showUntrackedFiles=no would
  hide the untracked and ignored entries alike. The README's design note
  on `rules.clean` is the canonical statement of the contract this
  serves."
  [root paths]
  (when-let [status (apply try-git root "status" "--porcelain" "-z" "--ignored"
                           "--untracked-files=normal" "--" paths)]
    (let [{ignored true changed false} (group-by #(str/starts-with? % "!! ")
                                                 (remove str/blank? (str/split status #"\u0000")))]
      (and (empty? changed)
           (not (ignored-among? (map #(subs % 3) ignored) (checkout-relative root paths)))))))

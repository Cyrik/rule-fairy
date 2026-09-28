(ns rule-fairy.changes
  "Files changed in a checkout since a moment in time. This is how the hooks
  find the files a shell command wrote, where no tool input names them:
  after the command, everything git reports as modified, added, renamed or
  untracked whose file changed at or after the session's last shell check
  is a change made through the shell, and so is everything the commits
  since that check's HEAD touched, which covers a file edited and committed
  in one command. The session state keeps the check (`:shell-check`,
  `:shell-head`) and the paths found but not yet delivered
  (`:shell-pending`), so a check whose rules never reached the agent is
  retried by the next one."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [rule-fairy.config :as config]
            [rule-fairy.git :as git]
            [rule-fairy.rules :as rules])
  (:import [java.nio.file Files LinkOption Path]
           [java.nio.file.attribute FileTime]))

(def ^:private nul-pattern (re-pattern (str (char 0))))

(defn status-entries
  "The entries of `git status --porcelain=v1 -z` output as [status path]
  pairs. The original path that follows a rename or copy entry is dropped;
  the entry keeps the new path."
  [output]
  (loop [tokens (remove str/blank? (str/split output nul-pattern))
         entries []]
    (if-let [token (first tokens)]
      (let [status (subs token 0 2)
            path (subs token 3)
            renamed? (some #{\R \C} status)]
        (recur (if renamed? (nnext tokens) (next tokens))
               (conj entries [status path])))
      entries)))

(defn- changed-at-or-after?
  "Whether the file's content or inode changed at or after marker-ms,
  compared at second granularity so a filesystem that keeps whole seconds
  never hides an edit made in the marker's second. A move keeps the
  modification time, so the inode change time counts as well; the symlink
  itself is examined, never its target."
  [^Path path marker-ms]
  (try
    (let [options (into-array LinkOption [LinkOption/NOFOLLOW_LINKS])
          mtime (.toMillis (Files/getLastModifiedTime path options))
          ctime (try
                  (.toMillis ^FileTime (Files/getAttribute path "unix:ctime" options))
                  (catch Exception _ mtime))]
      (>= (quot (max mtime ctime) 1000) (quot marker-ms 1000)))
    (catch java.io.IOException _ false)))

(defn- project-paths
  "Repository-relative paths mapped to project-relative ones with forward
  slashes, those outside project-root dropped."
  [project-root top paths]
  (let [top (fs/real-path top)
        root (fs/real-path project-root)]
    (->> paths
         (map #(fs/path top %))
         (filter #(fs/starts-with? % root))
         (map #(rules/normalize-path (str (fs/relativize root %))))
         sort
         vec)))

(defn- locate-checkout
  "The checkout holding project-root as {:top real-path :head oid}, the head
  nil before the first commit; nil outside git. One process answers both,
  since spawning one costs more than either lookup: before the first commit
  git echoes the unresolved `HEAD` and exits 128, outside git it prints
  nothing."
  [project-root]
  (let [[top head] (str/split-lines
                    (git/run project-root ["git" "rev-parse" "--show-toplevel" "HEAD"]
                             {:ok-exits #{0 128}}))]
    (when-not (str/blank? top)
      {:top (fs/real-path top)
       :head (when (and head (re-matches #"[0-9a-f]+" head)) head)})))

(defn- working-tree-changes
  "The changed-since listing for a checkout whose top is known."
  [project-root top marker-ms]
  ;; Untrimmed: the entry of an unstaged change begins with a space.
  (let [output (git/run project-root ["git" "status" "--porcelain=v1" "-z" "--untracked-files=all"])]
    (->> (status-entries output)
         (remove (fn [[status _]] (str/includes? status "D")))
         (map (fn [[_ path]] (fs/path top path)))
         (filter #(changed-at-or-after? % marker-ms))
         (map #(str (fs/relativize top %)))
         (project-paths project-root top))))

(defn changed-since
  "The files under project-root that changed at or after marker-ms, as
  paths relative to project-root with forward slashes: every file git
  reports as modified, added, renamed or untracked, ignored files and
  deletions left out, a rename counted for its new path. Nil outside a git
  checkout."
  [project-root marker-ms]
  (when-let [{:keys [top]} (locate-checkout project-root)]
    (working-tree-changes project-root top marker-ms)))

(defn- commit-changes
  "The committed-since listing for a located checkout; nil before its first
  commit."
  [project-root {:keys [top head]} from-head]
  (when head
    (if (= head from-head)
      []
      (let [base (or from-head (git/empty-tree-oid project-root))
            output (git/run project-root ["git" "diff" "--name-only" "-z" "--diff-filter=ACMR" base head])]
        (project-paths project-root top (remove str/blank? (str/split output nul-pattern)))))))

(defn committed-since
  "The files under project-root that the commits after from-head added,
  changed, renamed or copied, up to the current HEAD, as paths relative to
  project-root. A nil from-head counts everything ever committed. Empty
  when HEAD is still from-head; nil outside a git checkout or before the
  first commit."
  [project-root from-head]
  (commit-changes project-root (locate-checkout project-root) from-head))

;;; Session state

(defn- check-keys
  "The session-state keys of a check that started at started-ms with HEAD
  at head. started-ms must precede every git lookup of the check, so a
  write made while it runs is at or after the marker and the next check
  sees it."
  [started-ms head]
  {:shell-check started-ms
   :shell-head head})

(defn start-check
  "The session-state keys that begin shell checking now: the current time
  and HEAD."
  [project-root]
  (let [started (System/currentTimeMillis)]
    (check-keys started (:head (locate-checkout project-root)))))

(defn check
  "One shell check against a session state: {:state state' :paths paths},
  where state' carries the check advanced to now and every path found but
  not yet delivered, and paths lists all of those. A state without a check
  starts one and finds nothing. Files changed in the working tree count
  through their times, files committed since the check's HEAD through the
  commits; the HEAD is only consulted once a check has recorded one. Throws
  when git fails, leaving the state untouched so the next check covers the
  same window."
  [state project-root]
  (let [started (System/currentTimeMillis)
        checkout (locate-checkout project-root)
        {:keys [shell-check shell-head shell-pending]} state
        found (when (and shell-check checkout)
                (concat (working-tree-changes project-root (:top checkout) shell-check)
                        (when (contains? state :shell-head)
                          (commit-changes project-root checkout shell-head))))
        pending (vec (into (sorted-set) (concat shell-pending found)))]
    {:state (merge state (check-keys started (:head checkout)) {:shell-pending pending})
     :paths pending}))

(defn delivered
  "The state with paths removed from the pending shell changes, once their
  rules have been delivered or nothing needed delivering."
  [state paths]
  (update state :shell-pending #(vec (remove (set paths) %))))

;;; Configuration

(def shell-hook-defaults
  "Whether each harness's shell hook checks for changes unless
  `rule-fairy.edn` says otherwise: on for Claude Code, whose auto mode edits
  through the shell in one case of five, off for Codex, which makes far more
  shell calls than edits and rarely edits through them, so every call would
  pay for a rare catch."
  {:claude true :codex false})

(defn shell-hook-enabled?
  "Whether the shell hook of harness (:claude or :codex) checks for changes
  under project-root: the `:shell-hook` map of `rule-fairy.edn`, each key a
  harness and each value true or false, merged over shell-hook-defaults."
  [project-root harness]
  (let [setting (:shell-hook (config/read-config project-root))]
    (when (and (some? setting) (not (map? setting)))
      (throw (ex-info (str ":shell-hook in " config/config-file " must be a map")
                      {:shell-hook setting})))
    (let [unknown-keys (remove (set (keys shell-hook-defaults)) (keys setting))]
      (when (seq unknown-keys)
        (throw (ex-info (str "Unknown :shell-hook keys in " config/config-file ": "
                             (str/join ", " unknown-keys))
                        {:keys (vec unknown-keys)}))))
    (when-not (every? boolean? (vals setting))
      (throw (ex-info (str ":shell-hook values in " config/config-file " must be true or false")
                      {:shell-hook setting})))
    (get (merge shell-hook-defaults setting) harness)))

(ns rule-fairy.session-state
  "Per-session injection state shared by the harness adapters.

  Each session has one EDN file under a harness-specific state directory. It
  records, per rule, the transcript metrics at the moment the rule was last
  injected (`:sizes`), how far the transcript has been scanned for
  compaction records (`:transcript-scan`), and when the working tree was
  last checked for files written through the shell (`:shell-check`, epoch
  milliseconds; see `rule-fairy.changes`). A rule is injected again when the
  transcript has been compacted since, or has grown by more than the
  reinjection threshold, which also catches context cleanup that leaves no
  compaction record. Files untouched for the TTL are deleted on the next save.
  The directory ignores itself in git, so a consuming repository needs no
  ignore rule.

  Both harnesses run several hook processes of one session at the same time
  (Claude Code's lanes, Codex's concurrently launched hooks and subagents
  carrying the parent's session id), so every read of a session's state, the
  decision taken on it and the save that records it belong inside one
  `with-lock` call."
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [rule-fairy.transcript :as transcript])
  (:import [java.nio.channels FileChannel]
           [java.nio.file CopyOption Files OpenOption StandardCopyOption StandardOpenOption]))

(def state-ttl-ms (* 4 60 60 1000))
(def reinjection-threshold-bytes (* 2 1024 1024))

(def ^:private globs-cache-name "globs-cache.edn")

(defn ensure-dir!
  "Creates the state directory with a .gitignore that ignores its whole content."
  [state-dir]
  (let [ignore (io/file state-dir ".gitignore")]
    (when-not (.exists ignore)
      (io/make-parents ignore)
      (spit ignore "*\n"))))

;; A file lock is held per JVM, and taking it twice from one JVM throws, so
;; threads of one process queue on this monitor before the file.
(def ^:private jvm-lock (Object.))

(defn with-lock
  "Calls f while holding the session's exclusive lock, a file under
  `locks/` in the state directory, and returns its value. Serialises the hook
  processes of one session across both threads and processes. The lock is
  not reentrant: f must not call with-lock for the same session."
  [state-dir session-id f]
  (let [lock-file (io/file state-dir "locks" (str session-id ".lock"))]
    (ensure-dir! state-dir)
    (io/make-parents lock-file)
    (locking jvm-lock
      (with-open [channel (FileChannel/open
                           (.toPath lock-file)
                           (into-array OpenOption
                                       [StandardOpenOption/CREATE
                                        StandardOpenOption/WRITE]))]
        (let [_file-lock (.lock channel)]
          (f))))))

(defn replace-file!
  "Writes content to file by way of a temporary file beside it moved into
  place atomically, so a reader in another process sees the old content or
  the new, never a partial write, and a process killed mid-write leaves the
  file as it was. The file's directory must exist."
  [file content]
  (let [file (io/file file)
        temp (java.io.File/createTempFile (str (.getName file) ".") ".tmp"
                                          (.getParentFile (.getAbsoluteFile file)))]
    (spit temp content)
    (Files/move (.toPath temp) (.toPath file)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE
                                        StandardCopyOption/REPLACE_EXISTING]))))

(defn record-plugin-root!
  "Records root, the directory the installed plugin runs from, as one line
  in `plugin-root` in the state directory, so a skill of the consuming
  project can reach the plugin's review scripts and procedure without
  knowing where its harness keeps plugins. Written atomically and only when
  the content differs, since the hooks call this on every run that touches
  session state; a nil root, a hook script run outside the plugin's
  launcher, records nothing."
  [state-dir root]
  (when root
    (let [file (io/file state-dir "plugin-root")
          content (str root "\n")]
      (when-not (and (.isFile file) (= content (slurp file)))
        (ensure-dir! state-dir)
        (replace-file! file content)))))

(defn state-file [state-dir session-id]
  (io/file state-dir (str session-id ".edn")))

(defn load-state [state-dir session-id]
  (let [file (state-file state-dir session-id)]
    (if (.exists file)
      (edn/read-string (slurp file))
      {:sizes {}})))

(defn cleanup-old-files!
  "Deletes state files, including stale in-flight batches and the temporary
  files an interrupted write leaves behind, untouched for the TTL. The glob
  cache is not session state and is kept. Symlinks are not followed."
  [state-dir]
  (let [dir (io/file state-dir)
        now (System/currentTimeMillis)]
    (when (.isDirectory dir)
      (doseq [path (concat (fs/glob dir "**.edn") (fs/glob dir "**.tmp"))
              :let [file (fs/file path)]
              :when (and (.isFile file)
                         (not= (.getName file) globs-cache-name)
                         (>= (- now (.lastModified file)) state-ttl-ms))]
        (.delete file)))))

(defn save-state! [state-dir session-id state]
  (ensure-dir! state-dir)
  (replace-file! (state-file state-dir session-id)
                 (pr-str (assoc state :ts (System/currentTimeMillis))))
  (cleanup-old-files! state-dir))

(defn transcript-metrics!
  "Current metrics for the session's transcript file, scanning only what grew
  since the cursor stored in the session state and persisting the new cursor.
  Nil when transcript-file is nil or not a file."
  [state-dir session-id transcript-file compaction-pattern]
  (let [file (some-> transcript-file io/file)]
    (when (and file (.isFile file))
      (let [state (load-state state-dir session-id)
            {:keys [cursor metrics]} (transcript/scan (.toPath file)
                                                      compaction-pattern
                                                      (:transcript-scan state transcript/empty-cursor))]
        (save-state! state-dir session-id (assoc state :transcript-scan cursor))
        metrics))))

(defn- normalize-metrics
  "Older state stored the transcript size alone; treat it as pre-compaction metrics."
  [metrics]
  (if (map? metrics)
    metrics
    {:size metrics
     :compaction-count 0}))

(defn needs-reinjection? [state rule-name current-metrics]
  (let [injections (:sizes state {})]
    (if-not (contains? injections rule-name)
      true
      (let [{current-size :size
             current-compaction-count :compaction-count} (normalize-metrics current-metrics)
            {stored-size :size
             stored-compaction-count :compaction-count} (normalize-metrics (get injections rule-name))]
        (or (> (or current-compaction-count 0) (or stored-compaction-count 0))
            (and current-size stored-size
                 (>= (- current-size stored-size) reinjection-threshold-bytes)))))))

(defn mark-injected!
  "Records current-metrics as the injection point of every rule name and saves.
  Other state keys, such as the transcript cursor, are preserved."
  [state-dir session-id state rule-names current-metrics]
  (let [stored-metrics (or current-metrics {:size 0 :compaction-count 0})
        new-state (update state :sizes
                          (fn [injections]
                            (reduce #(assoc %1 %2 stored-metrics)
                                    (or injections {})
                                    rule-names)))]
    (save-state! state-dir session-id new-state)
    new-state))

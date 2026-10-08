#!/usr/bin/env bb

(ns rule-common
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.changes :as changes]
            [rule-fairy.git :as git]
            [rule-fairy.rules :as rules]
            [rule-fairy.session-state :as session-state]
            [rule-fairy.transcript :as transcript])
  (:import [java.math BigInteger]
           [java.security MessageDigest]))

;; Claude Code exports CLAUDE_PROJECT_DIR to hook processes and runs them in
;; the session's working directory, which follows the agent's shell. Nothing
;; here depends on that directory.
(def project-root
  (or (System/getenv "RULE_FAIRY_PROJECT_DIR")
      (System/getenv "CLAUDE_PROJECT_DIR")
      (System/getProperty "user.dir")))

(def rules-source (rules/project-rules-source project-root))
(def state-dir (str (io/file project-root ".rule-fairy/claude")))
;; Forced with the first session-state access of the process: the first
;; prompt of a session records where the plugin runs from, and a hook that
;; exits before touching state records nothing. hooks/run exports the root.
(def ^:private plugin-root-recorded
  (delay (session-state/record-plugin-root! state-dir (System/getenv "RULE_FAIRY_PLUGIN_ROOT"))))
;; Transcripts live under Claude Code's configuration directory, which
;; CLAUDE_CONFIG_DIR relocates as a whole.
(def claude-projects-dir
  (str (io/file (or (System/getenv "CLAUDE_CONFIG_DIR")
                    (str (System/getProperty "user.home") "/.claude"))
                "projects")))
(def reinjection-threshold-bytes session-state/reinjection-threshold-bytes)
(def completion-marker-ttl-ms (* 30 1000))
(def hook-lane-count 12)
(def max-hook-context-chars 9000)
(def ^:private frame-header-reserve 64)
(def ^:private frame-separator "\n\n---\n\n")
(def ^:private compaction-pattern #"\"isCompactSummary\"\s*:\s*true")

(defn main-thread?
  "Whether the hook fires in the session's main conversation. Claude Code
  runs the same hooks inside subagents and marks their input with agent_id;
  rules are not injected there: a subagent's context starts without the
  main thread's rules, and an injection recorded there would count against
  the session and suppress the rule for the main thread later."
  [input]
  (nil? (:agent_id input)))

(defn render-rules [rule-names]
  (rules/render-rules rules-source rule-names))

(defn render-rule-blocks [rule-names]
  (rules/render-rule-blocks rules-source rule-names))

(defn rules-for-prompt [prompt]
  (rules/rules-for-prompt rules-source prompt))

(def shell-prefix-path-budget
  "Characters the shell hook's matched line spends on naming changed paths:
  the leading paths that fit are named and the rest are counted. Rules match
  by glob, so the names inform the agent without deciding anything, and
  neither a rebase's hundreds of paths nor a few very long ones may push the
  prefix past the frame limit."
  1000)

(defn shell-prefix
  "The first-frame prefix of a shell-change delivery: the injected markers,
  the matched line naming the leading changed paths that fit the budget and
  counting the rest, and the post-edit contract."
  [rule-names paths]
  (let [named (loop [named [] remaining paths used 0]
                (if-let [path (first remaining)]
                  (let [used (+ used (count path) (if (seq named) 2 0))]
                    (if (<= used shell-prefix-path-budget)
                      (recur (conj named path) (rest remaining) used)
                      named))
                  named))
        rest-count (- (count paths) (count named))
        matched (cond
                  (empty? named) (str (count paths) " paths")
                  (pos? rest-count) (str (str/join ", " named) " and " rest-count " more")
                  :else (str/join ", " named))]
    (str (str/join "\n" (map #(str "[rule-fairy injected: " % "]") rule-names))
         "\n[rule-fairy matched: glob on " matched ", changed by a shell command]\n\n"
         "Apply these rules on the next pass over this change. "
         "If the completed change conflicts with them, revise it before moving on.")))

(defn transcript-file [session-id]
  (some (fn [project-directory]
          (let [file (io/file project-directory (str session-id ".jsonl"))]
            (when (.isFile file) file)))
        (.listFiles (io/file claude-projects-dir))))

(defn ensure-state-dir! []
  (session-state/ensure-dir! state-dir))

(defn load-session-state [session-id]
  (session-state/load-state state-dir session-id))

(defn save-session-state! [session-id state]
  (session-state/save-state! state-dir session-id state))

(defn transcript-metrics!
  "Current metrics for the session transcript, scanning only what grew since
  the last call. Nil when no transcript exists yet. Call under the session lock."
  [session-id]
  (session-state/transcript-metrics! state-dir session-id (transcript-file session-id) compaction-pattern))

(defn needs-reinjection? [state rule-name current-metrics]
  (session-state/needs-reinjection? state rule-name current-metrics))

(defn mark-injected! [session-id state rule-names current-metrics]
  (session-state/mark-injected! state-dir session-id state rule-names current-metrics))

(defn event-id [event-kind input]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes (str (name event-kind) "\n" input) "UTF-8")))]
    (format "%064x" (BigInteger. 1 (.digest digest)))))

(defn hook-lane-index []
  (let [configured-lane (System/getenv "RULE_FAIRY_LANE")
        lane-index (some-> configured-lane parse-long)]
    (when-not (and lane-index
                   (<= 0 lane-index)
                   (< lane-index hook-lane-count))
      (throw (ex-info (str "Set RULE_FAIRY_LANE to an integer from 0 through "
                           (dec hook-lane-count))
                      {:configured-lane configured-lane})))
    lane-index))

(defn- batch-file-for [session-id event-id]
  (str state-dir "/batches/" session-id "/" event-id ".edn"))

(defn- with-session-lock [session-id f]
  @plugin-root-recorded
  (session-state/with-lock state-dir session-id f))

;;; Files written through the shell

(defn shell-hook-enabled?
  "Whether this project runs the shell check on Claude Code; see
  rule-fairy.changes/shell-hook-enabled?."
  []
  (changes/shell-hook-enabled? project-root :claude))

(defn ensure-shell-marker!
  "Starts the session's shell check when it has none, so the first shell
  command after the first prompt is measured from here."
  [session-id]
  (with-session-lock
    session-id
    (fn []
      (let [state (load-session-state session-id)]
        (when-not (:shell-check state)
          (save-session-state! session-id
                               (merge state (changes/start-check project-root))))))))

(defn shell-changes!
  "The project-relative paths of files changed since the session's last shell
  check, plus those still pending from a check whose rules never reached the
  agent, advancing the check to now. Empty when the session had no check yet,
  which this call starts, and outside git. Call under the session lock."
  [session-id]
  (let [{:keys [state paths]} (changes/check (load-session-state session-id) project-root)]
    (save-session-state! session-id state)
    paths))

(defn shell-delivered!
  "Clears paths from the session's pending shell changes once every lane has
  delivered their rules, or nothing needed delivering. Call under the session
  lock."
  [session-id paths]
  (save-session-state! session-id (changes/delivered (load-session-state session-id) paths)))

;;; Injection batches

(defn- load-batch [session-id event-id]
  (let [file (io/file (batch-file-for session-id event-id))]
    (when (.isFile file)
      (edn/read-string (slurp file)))))

(defn- save-batch! [session-id event-id batch]
  (let [path (batch-file-for session-id event-id)]
    (io/make-parents path)
    (session-state/replace-file! path (pr-str batch))))

(defn- delete-batch! [session-id event-id]
  (.delete (io/file (batch-file-for session-id event-id))))

(defn- finished-batch []
  {:frames []
   :acknowledged #{}
   :commit? false
   :finished-at (System/currentTimeMillis)})

(defn- current-batch [session-id event-id]
  (when-let [batch (load-batch session-id event-id)]
    (if (and (:finished-at batch)
             (>= (- (System/currentTimeMillis) (:finished-at batch))
                 completion-marker-ttl-ms))
      (do
        (delete-batch! session-id event-id)
        nil)
      batch)))

(defn- fits-frame? [pieces addition max-chars]
  (<= (+ (reduce + (map count pieces))
         (* (count frame-separator) (count pieces))
         (count addition))
      max-chars))

(defn- add-to-first-fitting-frame [frames piece frame-body-limit-fn]
  (if-let [index (first (keep-indexed #(when (fits-frame? %2 piece
                                                           (frame-body-limit-fn %1))
                                         %1)
                                      frames))]
    (update frames index conj piece)
    (conj frames [piece])))

(defn- pack-frame-pieces [pieces frame-body-limit-fn]
  (reduce #(add-to-first-fitting-frame %1 %2 frame-body-limit-fn)
          []
          (sort-by count > pieces)))

(defn delivery-id
  "The identity a delivery's shard headers carry, so a reader of the
  transcript can tell one delivery's shards from its neighbours' whatever
  order the lanes finished in: the leading part of the event id the lanes
  share. Twelve hex digits tell the deliveries of one transcript apart; this
  is not a global identifier."
  [event-id]
  (subs event-id 0 (min 12 (count event-id))))

(defn injection-frames
  "The frames of one delivery: each opens with `[rule-fairy shard i/n <id>]`,
  the first carries the prefix, and the rule and documentation pieces are
  packed in order under the frame limit."
  [rule-names prefix delivery-id]
  (let [first-frame-body-limit (- max-hook-context-chars
                                  (count prefix)
                                  frame-header-reserve)
        later-frame-body-limit (- max-hook-context-chars frame-header-reserve)]
    (when-not (pos? first-frame-body-limit)
      (throw (ex-info "Hook context prefix exceeds the frame limit"
                      {:prefix-chars (count prefix)
                       :max-chars max-hook-context-chars})))
    (let [pieces (mapcat #(rules/split-block % first-frame-body-limit)
                         (render-rule-blocks rule-names))
          frame-body-limit-fn #(if (zero? %)
                                 first-frame-body-limit
                                 later-frame-body-limit)
          packed-frames (pack-frame-pieces pieces frame-body-limit-fn)
          frame-count (count packed-frames)
          frames (mapv (fn [index frame-pieces]
                         (str "[rule-fairy shard " (inc index) "/" frame-count " " delivery-id "]\n"
                              (when (zero? index) (str prefix "\n\n"))
                              (str/join frame-separator frame-pieces)))
                       (range frame-count)
                       packed-frames)]
      (doseq [frame frames]
        (when (> (count frame) max-hook-context-chars)
          (throw (ex-info "Hook context frame exceeds the configured limit"
                          {:frame-chars (count frame)
                           :max-chars max-hook-context-chars}))))
      frames)))

(defn- deferred-line [rule-names]
  (str "[rule-fairy deferred: " (str/join ", " rule-names)
       " (past what one delivery can carry; delivered with the next matching event)]"))

(defn- oversized-rule-frame [rule-name reason]
  (str "[rule-fairy error: " rule-name " alone " reason "]\n"
       "No matched rules were injected or marked as injected."))

(defn- documentation-over-budget?
  "True for the engine's documentation budget error, which carries the byte
  counts. Any other rendering error is a broken rule and stays an error."
  [error]
  (contains? (ex-data error) :expanded-bytes))

(defn- fitting-delivery
  "The longest prefix of rule-names, in match order, that fits one delivery:
  its frames within the lanes and its documentation within the engine's
  budget, as {:rule-names :frames :deferred}; the first frame's marker lines
  open with the deferred rest. {:oversized name :reason text} when the first
  rule alone fits neither. Every shorter attempt renders again, because the
  documentation blocks merge across the rules delivered together. The
  deferred line counts against the first frame's limit, which also bounds
  every piece, so a rule that alone fills all the lanes is reported
  oversized while others are deferred; that boundary is twelve frames of
  one rule, far above any real one."
  [rule-names prefix-fn delivery-id]
  (loop [delivered-count (count rule-names)]
    (let [delivered (subvec rule-names 0 delivered-count)
          deferred (subvec rule-names delivered-count)
          prefix (cond->> (prefix-fn delivered)
                   (seq deferred) (str (deferred-line deferred) "\n"))
          {:keys [frames over-budget]} (try
                                         {:frames (injection-frames delivered prefix delivery-id)}
                                         (catch clojure.lang.ExceptionInfo error
                                           (if (documentation-over-budget? error)
                                             {:over-budget (ex-data error)}
                                             (throw error))))]
      (cond
        (and frames (<= (count frames) hook-lane-count))
        {:rule-names delivered :frames frames :deferred deferred}

        (= 1 delivered-count)
        {:oversized (first delivered)
         :reason (if over-budget
                   (str "expands to " (:expanded-bytes over-budget)
                        " bytes of documentation, over the " (:max-bytes over-budget) "-byte budget")
                   (str "needs " (count frames) " output frames, but only "
                        hook-lane-count " lanes are configured"))}

        :else
        (recur (dec delivered-count))))))

(defn- inherit-injections!
  "The session state with the rules its transcript already shows as injected
  marked at current-metrics, done while the session has recorded no
  injection of its own: a forked session starts with its parent's context
  but a fresh state file, so the first batch would deliver again what the
  context already holds. Nil metrics mean no transcript yet, so nothing to
  inherit."
  [session-id state current-metrics]
  (if (or (seq (:sizes state)) (nil? current-metrics))
    state
    (if-let [rule-names (seq (some-> (transcript-file session-id)
                                     .toPath
                                     (transcript/inherited-injections compaction-pattern)))]
      (mark-injected! session-id state rule-names current-metrics)
      state)))

(defn- create-batch [session-id event-id matching-rules transcript-metrics-fn prefix-fn paths]
  (let [matching-rules (vec (distinct matching-rules))]
    (when (seq matching-rules)
      (let [current-metrics (transcript-metrics-fn)
            state (inherit-injections! session-id (load-session-state session-id) current-metrics)
            new-rules (filterv #(needs-reinjection? state % current-metrics)
                              matching-rules)]
        (when (seq new-rules)
          (let [{:keys [rule-names frames deferred oversized reason]}
                (fitting-delivery new-rules prefix-fn (delivery-id event-id))]
            (if oversized
              {:rule-names []
               :paths (vec paths)
               :current-metrics current-metrics
               :frames [(oversized-rule-frame oversized reason)]
               :acknowledged #{}
               :commit? false}
              ;; A delivery that defers rules keeps every path pending: the
              ;; next shell command matches the same rules, finds the
              ;; delivered ones recorded and delivers the rest.
              {:rule-names rule-names
               :paths (if (seq deferred) [] (vec paths))
               :current-metrics current-metrics
               :frames frames
               :acknowledged #{}
               :commit? true})))))))

(defn- build-batch!
  "Builds and saves the event's batch, clearing the selection's paths from
  the pending shell changes when there is nothing to deliver. A failed build
  saves a finished batch, so the other lanes stay quiet, and rethrows."
  [session-id event-id transcript-metrics-fn selection-fn]
  (try
    (let [{:keys [matching-rules prefix-fn paths]} (selection-fn)
          batch (or (create-batch session-id
                                  event-id
                                  matching-rules
                                  transcript-metrics-fn
                                  prefix-fn
                                  paths)
                    (finished-batch))]
      (save-batch! session-id event-id batch)
      (when (and (seq paths) (empty? (:frames batch)))
        (shell-delivered! session-id paths))
      batch)
    (catch Exception error
      (save-batch! session-id event-id (finished-batch))
      (throw error))))

(defn injection-frame-for-lane!
  "The frame this lane delivers for the event, or nil. The first lane to
  arrive builds the event's batch from selection-fn and saves it; the others
  read it. selection-fn returns :matching-rules, :prefix-fn and, after a
  shell command, :paths, the changed paths whose rules the event delivers:
  they leave the pending shell changes at once when nothing needs
  delivering and once every lane has acknowledged its frame otherwise. They
  stay pending while a delivery defers rules to the next event, so the next
  shell command delivers the rest, and while a single rule is too large for
  the lanes, which fails loudly on every command until the rule is fixed."
  [{:keys [session-id event-id lane-index transcript-metrics-fn selection-fn]}]
  (with-session-lock
    session-id
    (fn []
      (let [batch (or (current-batch session-id event-id)
                      (build-batch! session-id event-id transcript-metrics-fn selection-fn))]
        (when-not (contains? (:acknowledged batch) lane-index)
          (when-let [frame (get (:frames batch) lane-index)]
            {:event-id event-id
             :frame-index lane-index
             :frame frame}))))))

(defn complete-injection-frame! [session-id event-id frame-index]
  (with-session-lock
    session-id
    (fn []
      (when-let [batch (load-batch session-id event-id)]
        (when (get (:frames batch) frame-index)
          (let [new-batch (update batch :acknowledged conj frame-index)]
            (if (= (count (:frames new-batch))
                   (count (:acknowledged new-batch)))
              (do
                (when (:commit? new-batch)
                  (mark-injected! session-id
                                  (load-session-state session-id)
                                  (:rule-names new-batch)
                                  (:current-metrics new-batch))
                  (when (seq (:paths new-batch))
                    (shell-delivered! session-id (:paths new-batch))))
                (save-batch! session-id event-id (finished-batch)))
              (save-batch! session-id event-id new-batch))))))))

;;; Edited paths and the glob cache

(def cache-file (str (io/file state-dir "globs-cache.edn")))
(def cache-ttl-ms 30000)

(defn- as-path [path]
  (java.nio.file.Paths/get path (into-array String [])))

(defn- absolute-path
  "path resolved against the root path when relative, normalised."
  [root path]
  (.normalize (.resolve root (as-path path))))

(defn relative-file-path
  "The edited file's path as the project's rule globs are written; see
  rule-fairy.git/rule-path. Relative tool paths resolve against the project
  directory."
  [project-directory file-path]
  (let [project (.normalize (.toAbsolutePath (as-path project-directory)))]
    (str (git/rule-path project (absolute-path project file-path)))))

(defn get-relative-path [file-path]
  (relative-file-path project-root file-path))

(defn- cache-source []
  (select-keys rules-source [:dirs :extensions]))

(defn cache-valid? []
  (let [cache (io/file cache-file)]
    (and (.exists cache)
         (> (.lastModified cache)
            (rules/rules-tree-mtime rules-source)))))

(defn build-cache []
  (rules/rule-globs-index (rules/rule-index rules-source)))

(defn- read-cache
  "The cached globs index, or nil when the cache was written for another rules
  source (a changed rule-fairy.edn) or in an older format."
  []
  (let [cached (edn/read-string (slurp cache-file))]
    (when (and (map? cached) (= (:source cached) (cache-source)))
      (:globs cached))))

(defn- write-cache! []
  (let [globs (build-cache)]
    (ensure-state-dir!)
    (io/make-parents cache-file)
    (session-state/replace-file! cache-file (pr-str {:source (cache-source) :globs globs}))
    globs))

(defn load-or-build-cache
  "The globs index, from the cache when it is younger than the TTL or still
  newer than the rules tree. Only a passed mtime check refreshes the TTL, so
  the fast path cannot extend itself indefinitely."
  []
  (let [cache (io/file cache-file)
        now (System/currentTimeMillis)]
    (or (when (.exists cache)
          (cond
            (< (- now (.lastModified cache)) cache-ttl-ms)
            (read-cache)

            (cache-valid?)
            (when-let [globs (read-cache)]
              (.setLastModified cache now)
              globs)))
        (write-cache!))))

(defn rules-for-path [file-path]
  (filter #(rules/rule-file rules-source %)
          (rules/rules-matching-path (load-or-build-cache) file-path)))

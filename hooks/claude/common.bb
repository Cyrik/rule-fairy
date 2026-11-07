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
(def max-frame-chars
  "Characters one frame of a delivery, one context entry of the mod
  (hooks/claude/register.ts), may carry: Claude Code reads an entry whole
  up to 100,000 characters and hands the model a head and a file path past
  that."
  100000)
(def max-frames-per-delivery
  "Frames one delivery may carry: Claude Code reads one event's entries
  together up to 200,000 characters, two frames of max-frame-chars."
  2)
(def ^:private frame-header-reserve 64)
(def ^:private frame-separator "\n\n---\n\n")

(defn state-id
  "The key of the hook input's session state and lock: the session id in
  the main conversation; inside a subagent, which Claude Code marks with
  agent_id, the session id and the agent id joined by a dot, so the
  subagent's deliveries are recorded apart from the main thread's and from
  its sibling agents'. A spawn carries spawn_id, its tool call, because the
  agent's id is assigned once the task is set; nothing reads that record
  back, the subagent's first event finds the frames in its own prompt."
  [{:keys [session_id agent_id spawn_id]}]
  (cond
    agent_id (str session_id "." agent_id)
    spawn_id (str session_id ".spawn." spawn_id)
    :else session_id))

(defn render-rules [rule-names]
  (rules/render-rules rules-source rule-names))

(defn render-rule-blocks [rule-names]
  (rules/render-rule-blocks rules-source rule-names))

(defn rules-for-prompt [prompt]
  (rules/rules-for-prompt rules-source prompt))

(defn ensure-state-dir! []
  (session-state/ensure-dir! state-dir))

(defn load-session-state [session-id]
  (session-state/load-state state-dir session-id))

(defn save-session-state! [session-id state]
  (session-state/save-state! state-dir session-id state))

(defn event-id [event-kind input]
  (let [digest (doto (MessageDigest/getInstance "SHA-256")
                 (.update (.getBytes (str (name event-kind) "\n" input) "UTF-8")))]
    (format "%064x" (BigInteger. 1 (.digest digest)))))

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
                               (merge state (changes/start-check))))))))

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
  "Clears paths from the session's pending shell changes once their rules
  have been delivered, or nothing needed delivering. Call under the session
  lock."
  [session-id paths]
  (save-session-state! session-id (changes/delivered (load-session-state session-id) paths)))

;;; Deliveries

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
  conversation can tell one delivery's frames from its neighbours': the
  leading part of the event id. Twelve hex digits tell the deliveries of
  one session apart; this is not a global identifier."
  [event-id]
  (subs event-id 0 (min 12 (count event-id))))

(defn injection-frames
  "The frames of one delivery, each one context entry: each opens with
  `[rule-fairy shard i/n <id>]`, the first carries the prefix, and the rule
  and documentation pieces are packed in order under max-frame-chars."
  [rule-names prefix delivery-id]
  (let [first-frame-body-limit (- max-frame-chars (count prefix) frame-header-reserve)
        later-frame-body-limit (- max-frame-chars frame-header-reserve)]
    (when-not (pos? first-frame-body-limit)
      (throw (ex-info "Hook context prefix exceeds the frame limit"
                      {:prefix-chars (count prefix)
                       :max-chars max-frame-chars})))
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
        (when (> (count frame) max-frame-chars)
          (throw (ex-info "Hook context frame exceeds the configured limit"
                          {:frame-chars (count frame)
                           :max-chars max-frame-chars}))))
      frames)))

(defn- fitting-delivery
  "rules/fit-delivery for the mod's frames: a delivery fits when its frames
  are within max-frames-per-delivery of max-frame-chars each, the first
  frame's marker lines opening with the deferred rest. The deferred line
  counts against the first frame's limit, which also bounds every piece,
  so a rule that alone fills every frame is reported oversized while others
  are deferred; that boundary is far above any real rule."
  [rule-names prefix-fn delivery-id]
  (rules/fit-delivery
   rule-names
   (fn [delivered deferred]
     (let [prefix (cond->> (prefix-fn delivered)
                    (seq deferred) (str (rules/deferred-line deferred) "\n"))
           frames (injection-frames delivered prefix delivery-id)]
       (if (<= (count frames) max-frames-per-delivery)
         {:rendered frames}
         {:reason (str "needs " (count frames) " context entries, but one delivery carries "
                       max-frames-per-delivery)})))))

(def recent-delivery-ms
  "How long a delivery counts as held by the conversation before the
  conversation shows it. Tool calls that run side by side read the
  conversation before either result, with its context, is recorded, so a
  rule delivered within this window is not delivered again; the window only
  has to cover two tools finishing around the same time."
  60000)

(defn- recently-delivered? [state rule-name now-ms]
  (when-let [delivered-at (get-in state [:delivered rule-name])]
    (< (- now-ms delivered-at) recent-delivery-ms)))

(defn- record-delivery!
  "Saves now-ms as the delivery time of every rule name, under `:delivered`
  in the session state."
  [session-id rule-names now-ms]
  (save-session-state! session-id
                       (update (load-session-state session-id)
                               :delivered merge (zipmap rule-names (repeat now-ms)))))

(defn deliver!
  "One delivery for one hook event of the Claude Code mod
  (hooks/claude/register.ts): the selection's matched rules the model's
  context does not hold, rendered and packed into context entries, recorded
  as delivered, and the shell paths cleared unless the delivery defers
  rules. A rule is delivered when no whole delivery of it is among
  in-context-frames, the Rule Fairy frames the mod read back from the
  conversation at this event, each from its shard header, and none was
  recorded within recent-delivery-ms. A compaction empties the conversation
  of past deliveries, so the next matching event delivers again, and a
  forked session starts with what it inherited; no transcript file is read.
  selection-fn returns :matching-rules, :prefix-fn, from the delivered rule
  names to the first frame's marker lines and contract, and after a shell
  command :paths, the changed paths whose rules the event delivers: they
  leave the pending shell changes when nothing needs delivering and once
  their rules are delivered. They stay pending while a delivery defers
  rules to the next event, so the next shell command delivers the rest,
  and while a single rule is too large for one delivery, which is reported
  on every command until the rule is fixed. session-id is the state key,
  state-id of the hook input. now-ms is the clock, the current time unless
  given. {:context [entry ...]}, empty when nothing is due; an oversized
  rule's error is the one entry and records nothing."
  [{:keys [session-id event-id selection-fn in-context-frames now-ms]}]
  (with-session-lock
    session-id
    (fn []
      (let [{:keys [matching-rules prefix-fn paths]} (selection-fn)
            now-ms (or now-ms (System/currentTimeMillis))
            in-context (set (transcript/frames-injections in-context-frames))
            state (load-session-state session-id)
            rule-names (->> (distinct matching-rules)
                            (remove in-context)
                            (remove #(recently-delivered? state % now-ms))
                            vec)]
        (if (empty? rule-names)
          (do (when (seq paths)
                (shell-delivered! session-id paths))
              {:context []})
          (let [{delivered :rule-names frames :rendered :keys [deferred oversized reason]}
                (fitting-delivery rule-names prefix-fn (delivery-id event-id))]
            (if oversized
              {:context [(rules/oversized-rule-lines oversized reason)]}
              (do (record-delivery! session-id delivered now-ms)
                  ;; A delivery that defers rules keeps every path pending:
                  ;; the next shell command matches the same rules, finds
                  ;; the delivered ones recorded and delivers the rest.
                  (when (and (seq paths) (empty? deferred))
                    (shell-delivered! session-id paths))
                  {:context frames}))))))))

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

(defn build-cache
  "The selection index of every rule: {:globs {rule [glob ...]} :always-on
  [rule ...]}."
  []
  (let [index (rules/rule-index rules-source)]
    {:globs (rules/rule-globs-index index)
     :always-on (rules/always-apply-rule-names index)}))

(defn- read-cache
  "The cached selection index, or nil when the cache was written for another
  rules source (a changed rule-fairy.edn) or in an older format."
  []
  (let [cached (edn/read-string (slurp cache-file))]
    (when (and (map? cached)
               (= (:source cached) (cache-source))
               (contains? cached :always-on))
      (select-keys cached [:globs :always-on]))))

(defn- write-cache! []
  (let [index (build-cache)]
    (ensure-state-dir!)
    (io/make-parents cache-file)
    (session-state/replace-file! cache-file (pr-str (assoc index :source (cache-source))))
    index))

(defn load-or-build-cache
  "The selection index, from the cache when it is younger than the TTL or
  still newer than the rules tree. Only a passed mtime check refreshes the
  TTL, so the fast path cannot extend itself indefinitely."
  []
  (let [cache (io/file cache-file)
        now (System/currentTimeMillis)]
    (or (when (.exists cache)
          (cond
            (< (- now (.lastModified cache)) cache-ttl-ms)
            (read-cache)

            (cache-valid?)
            (when-let [index (read-cache)]
              (.setLastModified cache now)
              index)))
        (write-cache!))))

(defn rules-for-path [file-path]
  (filter #(rules/rule-file rules-source %)
          (rules/rules-matching-path (:globs (load-or-build-cache)) file-path)))

(defn always-on-rules
  "The `alwaysApply` rules, which every event delivers when the context
  lacks them."
  []
  (filter #(rules/rule-file rules-source %)
          (:always-on (load-or-build-cache))))

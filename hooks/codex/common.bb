#!/usr/bin/env bb

(ns rule-common
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.changes :as changes]
            [rule-fairy.config :as config]
            [rule-fairy.git :as git]
            [rule-fairy.rules :as rules]
            [rule-fairy.session-state :as session-state])
  (:import [java.nio.file Paths]))

(defn- project-marker?
  "Whether a directory is a Rule Fairy project: it holds a rule-fairy.edn or
  the default rules directory."
  [directory]
  (or (.isFile (io/file directory config/config-file))
      (some #(.isDirectory (io/file directory %)) (:dirs rules/default-rules-config))))

(defn- discover-project-root
  "The project a hook running in cwd belongs to. Codex exports no project
  directory to hooks and runs them in the session's working directory, so
  the project is the nearest directory from cwd up to the enclosing checkout
  root, inclusive, that carries a project marker; without one the checkout
  root, so a session started in a subdirectory of a repository finds that
  subdirectory's rules while a hook run deeper down still finds the project.
  Outside git cwd is the project."
  [cwd]
  (if-let [checkout (git/checkout-root cwd)]
    (let [directories (take-while some? (iterate #(.getParentFile ^java.io.File %) (io/file cwd)))
          up-to-checkout (concat (take-while #(not= % checkout) directories) [checkout])]
      (str (or (some #(when (project-marker? %) %) up-to-checkout) checkout)))
    (str cwd)))

(def project-root
  (or (System/getenv "RULE_FAIRY_PROJECT_DIR")
      (discover-project-root (System/getProperty "user.dir"))))

(def rules-source (rules/project-rules-source project-root))
(def state-dir (str (io/file project-root ".rule-fairy/codex")))
(def cache-file (str (io/file state-dir "globs-cache.edn")))
;; Forced with the first session-state access of the process: the first
;; prompt of a session records where the plugin runs from, and a hook that
;; exits before touching state records nothing. hooks/run exports the root.
(def ^:private plugin-root-recorded
  (delay (session-state/record-plugin-root! state-dir (System/getenv "RULE_FAIRY_PLUGIN_ROOT"))))
(def reinjection-threshold-bytes session-state/reinjection-threshold-bytes)
(def ^:private compaction-pattern #"\"type\":\"compacted\"")

(defn read-json-stdin []
  (let [input-str (slurp *in*)]
    (when-not (str/blank? input-str)
      (json/parse-string input-str true))))

;;; Session state

(defn ensure-state-dir! []
  (session-state/ensure-dir! state-dir))

(defn load-session-state [session-id]
  (session-state/load-state state-dir session-id))

(defn save-session-state! [session-id state]
  (session-state/save-state! state-dir session-id state))

(defn state-id
  "The key of the hook input's session state and lock: the session id in
  the main thread; inside a subagent, whose tool events Codex marks with
  agent_id under the parent's session id (CLI 0.163; the hooks doc tables
  omit the field), the session id and the agent id joined by a dot, so the
  subagent's deliveries and transcript metrics are recorded apart from
  the parent's and from its sibling agents'."
  [{:keys [session_id agent_id]}]
  (if agent_id (str session_id "." agent_id) session_id))

(defn with-session-lock
  "Calls f holding the lock of the state key (state-id) and returns its
  value. Codex launches every hook matching an event at once, so a hook
  reads the state, decides and saves inside one call; the helpers below
  expect to run under it."
  [session-id f]
  @plugin-root-recorded
  (session-state/with-lock state-dir session-id f))

(defn transcript-metrics!
  "Current metrics for the transcript named by the hook input, scanning only
  what grew since the last call. Nil when the input names no readable
  transcript. Call under the session lock."
  [session-id {:keys [transcript_path]}]
  (session-state/transcript-metrics! state-dir session-id transcript_path compaction-pattern))

(defn needs-reinjection? [state rule-name current-metrics]
  (session-state/needs-reinjection? state rule-name current-metrics))

(defn mark-injected! [session-id state rule-names current-metrics]
  (session-state/mark-injected! state-dir session-id state rule-names current-metrics))

;;; Glob cache

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

(defn load-or-build-cache
  "The selection index, from the cache when it is newer than the rules tree
  and was written for the current rules source in the current format."
  []
  (let [cached (when (cache-valid?) (edn/read-string (slurp cache-file)))]
    (if (and (map? cached)
             (= (:source cached) (cache-source))
             (contains? cached :always-on))
      (select-keys cached [:globs :always-on])
      (let [index (build-cache)]
        (ensure-state-dir!)
        (io/make-parents cache-file)
        (session-state/replace-file! cache-file (pr-str (assoc index :source (cache-source))))
        index))))

;;; Paths

(defn relative-path
  "The edited file's path as the project's rule globs are written (see
  rule-fairy.git/rule-path), with forward slashes. Relative tool paths
  resolve against the session cwd; links are followed on both sides, so a
  logical session cwd and the JVM's physical working directory relativise
  consistently."
  [cwd file-path]
  (let [path (Paths/get file-path (into-array String []))
        session-root (Paths/get cwd (into-array String []))
        project (git/real-path (Paths/get project-root (into-array String [])))
        absolute (git/real-path (if (.isAbsolute path)
                                 path
                                 (.resolve session-root path)))]
    (rules/normalize-path (str (git/rule-path project absolute)))))

(defn rules-for-path [relative-file-path]
  (filter #(rules/rule-file rules-source %)
          (rules/rules-matching-path (:globs (load-or-build-cache)) relative-file-path)))

(defn always-on-rules
  "The `alwaysApply` rules, which every event delivers when the context
  lacks them."
  []
  (filter #(rules/rule-file rules-source %)
          (:always-on (load-or-build-cache))))

;;; Files written through the shell

(defn shell-hook-enabled?
  "Whether this project runs the shell check on Codex, off unless
  rule-fairy.edn turns it on; see rule-fairy.changes/shell-hook-enabled?."
  []
  (changes/shell-hook-enabled? project-root :codex))

(defn ensure-shell-marker!
  "Starts the session's shell check when it has none, so the first shell
  command after the first prompt is measured from here. Call under the
  session lock."
  [session-id]
  (let [state (load-session-state session-id)]
    (when-not (:shell-check state)
      (save-session-state! session-id (merge state (changes/start-check))))))

(defn shell-changes!
  "The project-relative paths of files changed since the session's last shell
  check, plus those still pending from a check whose rules never got built,
  advancing the check to now. Empty when the session had no check yet, which
  this call starts, and outside git. Call under the session lock."
  [session-id]
  (let [{:keys [state paths]} (changes/check (load-session-state session-id) project-root)]
    (save-session-state! session-id state)
    paths))

(defn shell-delivered!
  "Clears paths from the session's pending shell changes once their rules
  have been built for delivery. Call under the session lock."
  [session-id paths]
  (save-session-state! session-id (changes/delivered (load-session-state session-id) paths)))

;;; Rendering and injection

(defn render-rules [rule-names]
  (rules/render-rules rules-source rule-names))

(defn rules-for-prompt [prompt]
  (rules/rules-for-prompt rules-source prompt))

(defn fitted-injection!
  "Renders the longest prefix of rule-names, in match order, whose
  documentation fits the engine's budget and records those rules as
  injected on state: {:delivered names :deferred names :context text},
  the context being the marker lines prefix-fn builds for the delivered
  rules, opened by the deferred line when rules are left for the next
  matching event, then the rendered rules. When the first rule alone is
  over the budget, {:oversized name :context text}, the context being the
  error lines, and nothing is recorded. A rule that fails to render for
  another reason throws, so the hook fails. Call under the session lock."
  [session-id state rule-names current-metrics prefix-fn]
  (let [{delivered :rule-names :keys [rendered deferred oversized reason]}
        (rules/fit-delivery rule-names
                            (fn [delivered _deferred] {:rendered (render-rules delivered)}))]
    (if oversized
      {:oversized oversized
       :context (rules/oversized-rule-lines oversized reason)}
      (do (mark-injected! session-id state delivered current-metrics)
          {:delivered delivered
           :deferred deferred
           :context (str (when (seq deferred) (str (rules/deferred-line deferred) "\n"))
                         (prefix-fn delivered)
                         "\n\n"
                         rendered)}))))

(defn json-output [m]
  (println (json/generate-string m)))

#!/usr/bin/env bb

;; PostToolUse and PostToolUseFailure hook for the Bash tool. A shell command
;; can write files that no tool input names (a heredoc, `sed -i`, `mv`, a
;; script), so once it has run the hook asks git which files changed since the
;; session's last shell check, matches them against the rules' globs and
;; injects the matches with the post-edit contract: apply the rules on the
;; next pass and revise the finished change where it conflicts. The check
;; runs after a failed command too, since it may have written before failing.
;; A file edited and committed in the same command counts through the
;; commits since the last check. Paths stay pending until every lane has
;; delivered their rules, so a rule that fails to build or a delivery cut
;; short is retried by the next shell command, and a rule set too large for
;; the lanes arrives across successive commands. The injected context names
;; the changed paths that fit a 1,000-character budget and counts the rest,
;; so the prefix never exceeds the frame limit on its own.
;; Switched off for Claude Code through `:shell-hook` in rule-fairy.edn, the
;; hook exits before any git call.
;;
;; Dedup and lanes work as for the edit hook: one batch per hook event, one
;; frame per lane, rules marked injected once every lane has delivered.
;;
;; Registered in hooks/hooks.json under both events, once per lane.

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file (str (io/file (.getParentFile (.getAbsoluteFile (io/file *file*)))
                         "common.bb")))

(let [input-str (slurp *in*)]
  (when-not (str/blank? input-str)
    (let [input (json/parse-string input-str true)
          {:keys [session_id hook_event_name]} input]
      (when (and session_id (rule-common/main-thread? input) (rule-common/shell-hook-enabled?))
        (let [event-id (rule-common/event-id :shell input-str)
              selection-fn
              (fn []
                (let [paths (rule-common/shell-changes! session_id)
                      path->rules (into {}
                                        (for [path paths
                                              :let [rules (doall (rule-common/rules-for-path path))]
                                              :when (seq rules)]
                                          [path rules]))]
                  {:matching-rules (distinct (mapcat val path->rules))
                   :paths paths
                   :prefix-fn
                   (fn [rule-names]
                     (let [injected (set rule-names)
                           paths (keep (fn [[path rules]] (when (some injected rules) path))
                                       path->rules)]
                       (rule-common/shell-prefix rule-names paths)))}))]
          (when-let [{:keys [frame frame-index]}
                     (rule-common/injection-frame-for-lane!
                      {:session-id session_id
                       :event-id event-id
                       :lane-index (rule-common/hook-lane-index)
                       :transcript-metrics-fn
                       #(rule-common/transcript-metrics! session_id)
                       :selection-fn selection-fn})]
            (println (json/generate-string
                      {:hookSpecificOutput
                       {:hookEventName (or hook_event_name "PostToolUse")
                        :additionalContext frame}}))
            (flush)
            (rule-common/complete-injection-frame!
             session_id event-id frame-index)))))))

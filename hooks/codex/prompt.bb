#!/usr/bin/env bb

;; UserPromptSubmit hook. Selects every rule with
;; alwaysApply: true plus every rule whose promptAnyOf/promptRequires
;; frontmatter matches the prompt, and returns their content as
;; additionalContext. Always-on rules therefore arrive with the first prompt of
;; a session rather than at session start. Also starts the session's
;; shell-check marker where the shell hook is on, so files a shell command
;; writes later are measured from the first prompt (see post_edit.bb).

(require '[clojure.java.io :as io])

(load-file (str (io/file (.getParentFile (.getAbsoluteFile (io/file *file*)))
                         "common.bb")))

(require '[rule-fairy.rules :as rules]
         '[clojure.string :as str])

(defn prompt-output
  "The hook output delivering the rules the prompt selected, as far as one
  delivery carries them, or nil when every one of them is already in
  context. Records the delivery in the session state, so it runs under the
  session lock."
  [session_id input matches]
  (let [current-metrics (rule-common/transcript-metrics! session_id input)
        state (rule-common/load-session-state session_id)
        new-matches (filter #(rule-common/needs-reinjection? state (:rule %) current-metrics)
                            matches)
        match-of (into {} (map (juxt :rule identity)) new-matches)]
    (when (seq new-matches)
      (let [{:keys [context]}
            (rule-common/fitted-injection!
             session_id state (map :rule new-matches) current-metrics
             (fn [delivered]
               (str/join "\n"
                         (concat (map #(str "[rule-fairy injected: " % "]") delivered)
                                 (rules/prompt-match-reasons (map match-of delivered))))))]
        {:hookSpecificOutput
         {:hookEventName "UserPromptSubmit"
          :additionalContext context}}))))

(let [{:keys [prompt session_id] :as input} (rule-common/read-json-stdin)]
  (when (and prompt session_id)
    (let [matches (rule-common/rules-for-prompt prompt)
          state-id (rule-common/state-id input)]
      (when-let [output
                 (rule-common/with-session-lock
                  state-id
                  (fn []
                    (when (rule-common/shell-hook-enabled?)
                      (rule-common/ensure-shell-marker! state-id))
                    (prompt-output state-id input matches)))]
        (rule-common/json-output output)))))

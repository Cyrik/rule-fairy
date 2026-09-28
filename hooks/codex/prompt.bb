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
  "The hook output delivering the rules the prompt selected, or nil when
  every one of them is already in context. Records the delivery in the
  session state, so it runs under the session lock."
  [session_id input matches]
  (let [current-metrics (rule-common/transcript-metrics! session_id input)
        state (rule-common/load-session-state session_id)
        new-matches (filter #(rule-common/needs-reinjection? state (:rule %) current-metrics)
                            matches)]
    (when (seq new-matches)
      (let [rule-names (map :rule new-matches)
            contents (rule-common/prepare-injection! session_id
                                                    state
                                                    rule-names
                                                    current-metrics)]
        (when (seq contents)
          {:hookSpecificOutput
           {:hookEventName "UserPromptSubmit"
            :additionalContext
            (str (str/join "\n"
                           (concat (map #(str "[rule-fairy injected: " % "]") rule-names)
                                   (rules/prompt-match-reasons new-matches)))
                 "\n\n"
                 contents)}})))))

(let [{:keys [prompt session_id] :as input} (rule-common/read-json-stdin)]
  (when (and prompt session_id)
    (let [matches (rule-common/rules-for-prompt prompt)]
      (when-let [output
                 (rule-common/with-session-lock
                  session_id
                  (fn []
                    (when (rule-common/shell-hook-enabled?)
                      (rule-common/ensure-shell-marker! session_id))
                    (prompt-output session_id input matches)))]
        (rule-common/json-output output)))))

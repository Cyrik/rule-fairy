#!/usr/bin/env bb

;; UserPromptSubmit hook — triggered on every user prompt submission.
;; Selects every rule with alwaysApply: true plus every
;; rule whose promptAnyOf/promptRequires frontmatter matches the prompt text,
;; and injects their content so the model sees the conventions before
;; responding. Always-on rules therefore arrive with the first prompt of a
;; session rather than at session start.
;;
;; Dedup: tracks (per session) which rule files have been injected and in which
;; context generation. Re-injects after compaction or ~2MB of transcript growth
;; as a fallback for silent context cleanup.
;;
;; Also starts the session's shell-check marker where the shell hook is on,
;; so files a shell command writes later are measured from the first prompt
;; (see shell.bb).
;;
;; Registered in hooks/hooks.json under UserPromptSubmit, once per lane.

(require '[rule-fairy.rules :as rules]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file (str (io/file (.getParentFile (.getAbsoluteFile (io/file *file*)))
                         "common.bb")))

(let [input-str (slurp *in*)]
  (when-not (str/blank? input-str)
    (let [input (json/parse-string input-str true)
          {:keys [prompt session_id]} input]
      (when (and prompt session_id)
        (when (rule-common/shell-hook-enabled?)
          (rule-common/ensure-shell-marker! session_id))
        (let [event-id (rule-common/event-id :prompt input-str)
              selection-fn
              (fn []
                (let [matches (rule-common/rules-for-prompt prompt)
                      matches-by-rule (into {} (map (juxt :rule identity)) matches)]
                  {:matching-rules (map :rule matches)
                   :prefix-fn
                   (fn [rule-names]
                     (str/join "\n"
                               (concat (map #(str "[rule-fairy injected: " % "]") rule-names)
                                       (rules/prompt-match-reasons
                                        (map matches-by-rule rule-names)))))}))]
          (when-let [{:keys [frame frame-index]}
                     (rule-common/injection-frame-for-lane!
                      {:session-id session_id
                       :event-id event-id
                       :lane-index (rule-common/hook-lane-index)
                       :transcript-metrics-fn
                       #(rule-common/transcript-metrics! session_id)
                       :selection-fn selection-fn})]
            (println frame)
            (flush)
            (rule-common/complete-injection-frame!
             session_id event-id frame-index)))))))

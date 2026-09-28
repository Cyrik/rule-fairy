#!/usr/bin/env bb

;; PreToolUse hook — triggered before file-editing tool calls.
;; Reads the target path from the tool input, then checks it against the glob
;; patterns declared in the project's rules. When a file matches, the rule
;; content is injected into additionalContext for the model turn after the
;; tool call.
;;
;; Dedup: tracks (per session) which rule files have been injected and in which
;; context generation. Re-injects after compaction or ~2MB of transcript growth
;; as a fallback for silent context cleanup.
;;
;; Registered in hooks/hooks.json under PreToolUse, once per lane. Files
;; written by shell commands take the shell.bb hook instead. The edited path
;; is file_path (Edit, Write), notebook_path (NotebookEdit) or path (the MCP
;; filesystem server's write_file and edit_file). Inside a subagent the hook
;; does nothing; see rule-common/main-thread?.

(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file (str (io/file (.getParentFile (.getAbsoluteFile (io/file *file*)))
                         "common.bb")))

(let [input-str (slurp *in*)]
  (when-not (str/blank? input-str)
    (let [input (json/parse-string input-str true)
          session-id (:session_id input)
          file-path (or (get-in input [:tool_input :file_path])
                        (get-in input [:tool_input :notebook_path])
                        (get-in input [:tool_input :path]))]
      (when (and session-id file-path (rule-common/main-thread? input))
        (let [rel-path (rule-common/get-relative-path file-path)
              event-id (rule-common/event-id :file input-str)
              selection-fn
              (fn []
                {:matching-rules (doall (rule-common/rules-for-path rel-path))
                 :prefix-fn
                 (fn [rule-names]
                   (str (str/join "\n" (map #(str "[rule-fairy injected: " % "]") rule-names))
                        "\n[rule-fairy matched: glob on " rel-path "]"))})]
          (when-let [{:keys [frame frame-index]}
                     (rule-common/injection-frame-for-lane!
                      {:session-id session-id
                       :event-id event-id
                       :lane-index (rule-common/hook-lane-index)
                       :transcript-metrics-fn
                       #(rule-common/transcript-metrics! session-id)
                       :selection-fn selection-fn})]
            (println (json/generate-string
                      {:hookSpecificOutput
                       {:hookEventName "PreToolUse"
                        :additionalContext frame}}))
            (flush)
            (rule-common/complete-injection-frame!
             session-id event-id frame-index)))))))

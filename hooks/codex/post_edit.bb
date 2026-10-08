#!/usr/bin/env bb

;; PostToolUse hook for edits. After an apply_patch or edit tool call the
;; edited paths come from the tool input; after a Bash command, which can
;; write files no input names, they are the files changed since the session's
;; last shell check (see rule-fairy.changes), plus any still pending from a
;; check whose rules could not be built. Either way the matching rules are
;; injected with the post-edit contract: apply them on the next pass and
;; revise the finished change where it conflicts. The shell path is off
;; unless `:shell-hook {:codex true}` is set in rule-fairy.edn; a Bash event
;; then ends here.

(require '[clojure.java.io :as io])

(load-file (str (io/file (.getParentFile (.getAbsoluteFile (io/file *file*)))
                         "common.bb")))

(require '[clojure.string :as str])

(defn paths-from-patch
  "Every path an apply_patch text names: the Add, Update and Delete file
  headers and the `Move to` destination of a moved file, so a move into a
  directory governed by other rules selects those rules too."
  [patch]
  (when patch
    (->> (re-seq #"(?m)^\*\*\* (?:(?:Add|Update|Delete) File|Move to): (.+)$" patch)
         (map second)
         (map str/trim)
         (remove empty?))))

(defn paths-from-input [tool-input]
  (let [direct-paths (keep tool-input [:file_path :path])
        command-paths (paths-from-patch (:command tool-input))
        patch-paths (paths-from-patch (:patch tool-input))]
    (distinct (concat direct-paths command-paths patch-paths))))

(defn edit-output
  "The hook output delivering the rules the edit selected, or nil when every
  one of them is already in context. Records the delivery in the session
  state, so it runs under the session lock."
  [session_id input shell? relative-paths]
  (let [path->rules (into {}
                          (for [relative-path relative-paths
                                :let [rules (doall (rule-common/rules-for-path relative-path))]
                                :when (seq rules)]
                            [relative-path rules]))
        current-metrics (rule-common/transcript-metrics! session_id input)
        state (rule-common/load-session-state session_id)
        new-rules (->> path->rules
                       vals
                       (apply concat)
                       distinct
                       (filter #(rule-common/needs-reinjection? state % current-metrics)))]
    (when (seq new-rules)
      (let [contents (rule-common/prepare-injection! session_id
                                                    state
                                                    new-rules
                                                    current-metrics)]
        (when (seq contents)
          {:continue false
           :stopReason "Matched rules after an edit"
           :hookSpecificOutput
           {:hookEventName "PostToolUse"
            :additionalContext
            (str (str/join "\n" (map #(str "[rule-fairy injected: " % "]") new-rules))
                 "\n[rule-fairy matched: glob on "
                 (str/join ", " (keys path->rules))
                 (when shell? ", changed by a shell command")
                 "]\n\n"
                 "Apply these rules on the next pass over this edit. If the completed edit conflicts with them, revise it before moving on.\n\n"
                 contents)}})))))

(let [{:keys [session_id cwd tool_name tool_input] :as input} (rule-common/read-json-stdin)
      shell? (= "Bash" tool_name)]
  (when (and session_id cwd tool_input (or (not shell?) (rule-common/shell-hook-enabled?)))
    (when-let [output
               (rule-common/with-session-lock
                session_id
                (fn []
                  (let [relative-paths (if shell?
                                         (rule-common/shell-changes! session_id)
                                         (map #(rule-common/relative-path cwd %) (paths-from-input tool_input)))
                        output (edit-output session_id input shell? relative-paths)]
                    ;; Reached only once the rules were built, so a failure
                    ;; above keeps the paths pending for the next shell command.
                    (when shell?
                      (rule-common/shell-delivered! session_id relative-paths))
                    output)))]
      (rule-common/json-output output))))

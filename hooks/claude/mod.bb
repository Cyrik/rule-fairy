#!/usr/bin/env bb

;; Entry point of the Claude Code mod, hooks/claude/register.ts: one process
;; per event. Reads {"kind": "prompt" | "edit" | "shell", "input": {...}}
;; from stdin, where input carries the fields of Claude Code's hook input
;; (session_id, prompt, tool_name, tool_input, agent_id) plus
;; in_context_frames, the heads of the Rule Fairy frames the mod found in
;; the conversation, and prints {"context": [...]}: the context entries the
;; mod attaches after the prompt or the tool result, none when nothing is
;; due. A prompt selects by keyword and alwaysApply, an edit by the edited
;; path's globs, a shell command by the globs of the paths it changed; the
;; delivery is rule-common/deliver!.

(require '[rule-fairy.rules :as rules]
         '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

(load-file (str (io/file (.getParentFile (.getAbsoluteFile (io/file *file*)))
                         "common.bb")))

(defn- injected-lines [rule-names]
  (str/join "\n" (map #(str "[rule-fairy injected: " % "]") rule-names)))

(defn- prompt-selection [prompt]
  (fn []
    (let [matches (rule-common/rules-for-prompt prompt)
          matches-by-rule (into {} (map (juxt :rule identity)) matches)]
      {:matching-rules (map :rule matches)
       :prefix-fn (fn [rule-names]
                    (str/join "\n"
                              (cons (injected-lines rule-names)
                                    (rules/prompt-match-reasons
                                     (map matches-by-rule rule-names)))))})))

(defn- edit-selection [rel-path]
  (fn []
    {:matching-rules (doall (rule-common/rules-for-path rel-path))
     :prefix-fn (fn [rule-names]
                  (str (injected-lines rule-names)
                       "\n[rule-fairy matched: glob on " rel-path "]"))}))

(defn- shell-selection [session-id]
  (fn []
    (let [paths (rule-common/shell-changes! session-id)
          path->rules (into {}
                            (for [path paths
                                  :let [rules (doall (rule-common/rules-for-path path))]
                                  :when (seq rules)]
                              [path rules]))]
      {:matching-rules (distinct (mapcat val path->rules))
       :paths paths
       :prefix-fn (fn [rule-names]
                    (let [injected (set rule-names)
                          paths (keep (fn [[path rules]] (when (some injected rules) path))
                                      path->rules)]
                      (rule-common/shell-prefix rule-names paths)))})))

(defn- selection
  "The event's selection, or nil when it delivers nothing: a prompt without
  text, an edit naming no file, a shell command while the shell check is
  off, and any event inside a subagent."
  [kind {:keys [session_id prompt tool_input] :as input}]
  (when (rule-common/main-thread? input)
    (case kind
      "prompt" (when prompt
                 (when (rule-common/shell-hook-enabled?)
                   (rule-common/ensure-shell-marker! session_id))
                 (prompt-selection prompt))
      "edit" (when-let [file-path (or (:file_path tool_input)
                                      (:notebook_path tool_input)
                                      (:path tool_input))]
               (edit-selection (rule-common/get-relative-path file-path)))
      "shell" (when (rule-common/shell-hook-enabled?)
                (shell-selection session_id)))))

(let [input-str (slurp *in*)]
  (when-not (str/blank? input-str)
    (let [{:keys [kind input]} (json/parse-string input-str true)
          session-id (:session_id input)
          selection-fn (when session-id (selection kind input))
          context (when selection-fn
                    (:context (rule-common/deliver!
                               {:session-id session-id
                                :event-id (rule-common/event-id (keyword kind) input-str)
                                :selection-fn selection-fn
                                :in-context-frames (vec (:in_context_frames input))})))]
      (println (json/generate-string {:context (vec context)})))))

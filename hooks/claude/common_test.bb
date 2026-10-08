#!/usr/bin/env bb

(ns rule-common-test
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [rule-fairy.rules :as rules]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def hooks-directory (.getParentFile (.getAbsoluteFile (io/file *file*))))
(def repository-directory (.getParentFile (.getParentFile hooks-directory)))

;; The lane test validates a hook registration file, by default the plugin's own.
(def settings-file
  (or (System/getenv "RULE_FAIRY_SETTINGS_FILE")
      (str (io/file repository-directory "hooks/hooks.json"))))

(load-file (str (io/file hooks-directory "common.bb")))
(binding [*in* (java.io.StringReader. "")]
  (load-file (str (io/file hooks-directory "edit.bb"))))

(def rule-name "testing.mdc")
(def current-metrics {:size 100 :compaction-count 0})

(defn test-selection []
  {:matching-rules [rule-name]
   :prefix-fn (constantly "prefix")})

(defn test-frame-request [lane-index]
  {:session-id "session"
   :event-id "event"
   :lane-index lane-index
   :transcript-metrics-fn (constantly current-metrics)
   :selection-fn test-selection})

(defn configured-hook-lane [command]
  (some-> (re-find #"RULE_FAIRY_LANE=(\d+)" command)
          second
          parse-long))

(defn with-temp-project [files f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "rule-fairy-claude-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path content] files
              :let [file (io/file root path)]]
        (io/make-parents file)
        (spit file content))
      (f root)
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn with-temp-rules
  "Runs f with the shared renderer pointed at a temporary project whose rules
  tree and referenced documents are given as path->content."
  [files f]
  (with-temp-project
    files
    (fn [root]
      (with-redefs [rule-common/rules-source (rules/project-rules-source root)]
        (f root)))))

(defn with-temp-state [f]
  (with-temp-project
    {}
    (fn [root]
      (with-redefs [rule-common/state-dir (.getAbsolutePath root)]
        (f)))))

(defn rule-paragraph [rule-name index]
  (str "Paragraph " index " of " rule-name ": "
       (str/join " " (repeat 12 "follow this convention"))))

(defn large-rule
  "An rule file of roughly paragraph-count * 300 characters ending in a fenced
  block, so frame packing meets real headings, paragraphs and fences."
  [rule-name paragraph-count]
  (str "---\n"
       "description: " rule-name "\n"
       "globs: apps/**/*.clj\n"
       "alwaysApply: false\n"
       "---\n"
       "# " rule-name "\n\n"
       (str/join "\n\n" (map #(rule-paragraph rule-name %) (range paragraph-count)))
       "\n\n```clojure\n(defn " rule-name "-example []\n  :" rule-name ")\n```\n"))

(defn large-rule-files [rule-names paragraph-count]
  (into {}
        (map (fn [rule-name]
               [(str ".cursor/rules/" rule-name ".mdc")
                (large-rule rule-name paragraph-count)])
             rule-names)))

(deftest glob-cache-invalidates-on-nested-rename-test
  (with-temp-project
    {".cursor/rules/tasks/old.mdc"
     "---\nglobs: apps/old/**\nalwaysApply: false\n---\n# Old task"
     ".rule-fairy/claude/globs-cache.edn"
     "{\"tasks/old.mdc\" [\"apps/old/**\"]}"}
    (fn [root]
      (let [rules-directory (io/file root ".cursor/rules")
            tasks-directory (io/file rules-directory "tasks")
            old-rule (io/file tasks-directory "old.mdc")
            new-rule (io/file tasks-directory "new.mdc")
            cache (io/file root ".rule-fairy/claude/globs-cache.edn")
            before-cache (- (System/currentTimeMillis) 4000)
            cache-time (+ before-cache 1000)
            after-cache (+ cache-time 1000)]
        (doseq [file [rules-directory tasks-directory old-rule]]
          (.setLastModified file before-cache))
        (.setLastModified cache cache-time)
        (with-redefs [rule-common/rules-source (rules/project-rules-source root)
                      rule-common/cache-file (.getAbsolutePath cache)]
          (is (rule-common/cache-valid?))
          (is (.renameTo old-rule new-rule))
          (.setLastModified new-rule before-cache)
          (.setLastModified rules-directory before-cache)
          (.setLastModified tasks-directory after-cache)
          (is (not (rule-common/cache-valid?))))))))

(deftest cross-checkout-file-path-test
  (with-temp-project
    {"session/.git/HEAD" "ref: refs/heads/session"
     "checkout/.git/HEAD" "ref: refs/heads/main"
     "checkout/apps/example/src/example/ui/example.clj" ""}
    (fn [root]
      (let [session-directory (io/file root "session")
            target-file (io/file root
                                 "checkout/apps/example/src/example/ui/example.clj")
            relative-path (rule-common/relative-file-path (.getAbsolutePath session-directory)
                                                          (.getAbsolutePath target-file))]
        (is (= "apps/example/src/example/ui/example.clj" relative-path))
        (is (rules/glob-matches? relative-path "apps/**/ui/**/*.clj"))))))

(deftest project-position-in-checkout-test
  (with-temp-project
    {"repo/.git/HEAD" "ref: refs/heads/main"
     "repo/backend/src/a.clj" ""}
    (fn [root]
      (testing "a session started in a subdirectory of its repository matches that project's globs"
        (is (= "src/a.clj"
               (rule-common/relative-file-path (.getAbsolutePath (io/file root "repo/backend"))
                                               (.getAbsolutePath (io/file root "repo/backend/src/a.clj")))))))))

(deftest reinjection-test
  (testing "when a rule has not been injected, then it needs injection"
    (is (rule-common/needs-reinjection? {} rule-name
                                       {:size 100 :compaction-count 0})))

  (testing "given a rule was injected in the current context generation"
    (let [state {:sizes {rule-name {:size 100 :compaction-count 1}}}]
      (testing "when little transcript growth has occurred, then it stays deduplicated"
        (is (not (rule-common/needs-reinjection? state rule-name
                                                {:size 101 :compaction-count 1}))))

      (testing "when the transcript grows by the threshold, then it needs reinjection"
        (is (rule-common/needs-reinjection?
             state rule-name
             {:size (+ 100 rule-common/reinjection-threshold-bytes)
              :compaction-count 1})))

      (testing "when the context is compacted, then it needs immediate reinjection"
        (is (rule-common/needs-reinjection? state rule-name
                                           {:size 101 :compaction-count 2})))))

  (testing "given legacy numeric state"
    (testing "when the transcript has compacted, then it needs reinjection"
      (is (rule-common/needs-reinjection? {:sizes {rule-name 100}}
                                         rule-name
                                         {:size 101 :compaction-count 1})))))

(deftest transcript-metrics-test
  (with-temp-project
    {"-Users-example-Workspace-project/session.jsonl"
     (str "{\"type\":\"user\",\"isCompactSummary\":false}\n"
          "{\"type\":\"user\",\"isCompactSummary\":true}\n")}
    (fn [root]
      (with-temp-state
        (fn []
          (with-redefs [rule-common/claude-projects-dir (.getAbsolutePath root)]
            (let [transcript (io/file root "-Users-example-Workspace-project/session.jsonl")]
              (testing "the transcript is found by session id and scanned once in full"
                (is (= {:size (.length transcript) :compaction-count 1}
                       (rule-common/transcript-metrics! "session")))
                (is (= {:scanned-bytes (.length transcript) :compaction-count 1}
                       (:transcript-scan (rule-common/load-session-state "session")))))
              (testing "a later call counts only the appended complete lines"
                (spit transcript
                      (str "{\"type\":\"user\",\"isCompactSummary\":true}\n"
                           "{\"type\":\"user\",\"isCompactSum")
                      :append true)
                (is (= {:size (.length transcript) :compaction-count 2}
                       (rule-common/transcript-metrics! "session")))
                (is (< (get-in (rule-common/load-session-state "session")
                               [:transcript-scan :scanned-bytes])
                       (.length transcript))))
              (testing "marking rules injected keeps the scan cursor"
                (rule-common/mark-injected! "session"
                                           (rule-common/load-session-state "session")
                                           [rule-name]
                                           current-metrics)
                (is (= 2 (get-in (rule-common/load-session-state "session")
                                 [:transcript-scan :compaction-count])))
                (is (= current-metrics
                       (get-in (rule-common/load-session-state "session") [:sizes rule-name]))))
              (testing "an unknown session has no transcript and no metrics"
                (is (nil? (rule-common/transcript-metrics! "missing-session")))))))))))

(deftest event-id-test
  (is (= (rule-common/event-id :prompt "input")
         (rule-common/event-id :prompt "input")))
  (is (not= (rule-common/event-id :prompt "input")
            (rule-common/event-id :file "input"))))

(deftest markdown-block-splitting-test
  (let [fenced-block "```clojure\n(def value 1)\n```"
        block {:heading "# test.mdc"
               :content (str "Introductory paragraph.\n\n"
                             fenced-block
                             "\n\n"
                             (str/join "\n" (repeat 20 "- another rule")))}
        parts (rules/split-block block 160)]
    (is (< 1 (count parts)))
    (is (every? #(<= (count %) 160) parts))
    (is (every? #(str/starts-with? % "# test.mdc") parts))
    (is (= 1 (count (filter #(str/includes? % fenced-block) parts)))))

  (testing "a paragraph after a closing fence is a separate splittable unit"
    (let [fenced-block "```text\ncode\n```"
          parts (rules/split-block
                 {:heading "# test.mdc"
                  :content (str fenced-block "\n"
                                (str/join "\n" (repeat 20 "plain text")))}
                 100)]
      (is (< 1 (count parts)))
      (is (every? #(<= (count %) 100) parts))
      (is (= 1 (count (filter #(str/includes? % fenced-block) parts))))))

  (testing "a fenced block that cannot fit fails instead of being split"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"fenced Markdown block exceeds"
         (rules/split-block
          {:heading "# test.mdc"
           :content (str "```text\n" (apply str (repeat 100 "x")) "\n```")}
          100)))))

(deftest multi-frame-packing-test
  (let [rule-stems ["alpha" "beta" "gamma" "delta"]
        rule-names (mapv #(str % ".mdc") rule-stems)]
    (with-temp-rules
      (assoc (large-rule-files rule-stems 30)
             ".cursor/rules/alpha.mdc"
             (str (large-rule "alpha" 30) "\n@doc/guide.md#shared\n")
             ".cursor/rules/beta.mdc"
             (str (large-rule "beta" 30) "\n@doc/guide.md#shared\n")
             "doc/guide.md"
             "# Guide\n\n## Shared\n\nshared guidance\n\n## Other\n\nnot imported\n")
      (fn [_]
        (let [frames (rule-common/injection-frames rule-names "[rule-fairy matched: test]" "delivery")
              joined (str/join "\n" frames)]
          (is (< 1 (count frames)))
          (is (<= (count frames) rule-common/hook-lane-count))
          (is (every? #(<= (count %) rule-common/max-hook-context-chars) frames))
          (is (every? #(str/starts-with? % "[rule-fairy shard") frames))
          (is (= 1 (count (filter #(str/includes? % "[rule-fairy matched: test]") frames))))
          (doseq [rule-name rule-stems]
            (testing rule-name
              (is (str/includes? joined (rule-paragraph rule-name 29)))
              (is (str/includes? joined (str "(defn " rule-name "-example []")))))
          (testing "a section imported by two rules is delivered once"
            (is (= 1 (count (re-seq #"shared guidance" joined))))
            (is (not (str/includes? joined "not imported")))))))))

(deftest large-matched-rule-set-fits-configured-lanes-test
  (let [rule-stems (mapv #(str "rule-" %) (range 8))
        rule-names (mapv #(str % ".mdc") rule-stems)]
    (with-temp-rules
      (large-rule-files rule-stems 30)
      (fn [_]
        (let [prefix (str (str/join "\n" (map #(str "[rule-fairy injected: " % "]") rule-names))
                          "\n[rule-fairy matched: glob on apps/example/src/example/ui/example.clj]")
              frames (rule-common/injection-frames rule-names prefix "delivery")]
          (is (<= (count frames) rule-common/hook-lane-count))
          (is (every? #(<= (count %) rule-common/max-hook-context-chars) frames))
          (is (= 1 (count (filter #(str/includes? % "[rule-fairy injected:") frames))))
          (is (every? #(str/starts-with? % "[rule-fairy shard") frames)))))))

(deftest lanes-deliver-rendered-rules-end-to-end-test
  (let [rule-stems ["alpha" "beta" "gamma"]
        rule-names (mapv #(str % ".mdc") rule-stems)]
    (with-temp-rules
      (large-rule-files rule-stems 30)
      (fn [_]
        (with-temp-state
          (fn []
            (let [request (fn [lane-index]
                            {:session-id "session"
                             :event-id "event"
                             :lane-index lane-index
                             :transcript-metrics-fn (constantly current-metrics)
                             :selection-fn
                             (fn []
                               {:matching-rules rule-names
                                :prefix-fn
                                (fn [names]
                                  (str/join "\n" (map #(str "[rule-fairy injected: " % "]")
                                                      names)))})})
                  deliveries (->> (range rule-common/hook-lane-count)
                                  (keep #(rule-common/injection-frame-for-lane!
                                          (request %)))
                                  vec)
                  joined (str/join "\n" (map :frame deliveries))]
              (is (< 1 (count deliveries)))
              (is (= (range (count deliveries)) (map :frame-index deliveries)))
              (doseq [rule-name rule-stems]
                (testing rule-name
                  (is (str/includes? joined (rule-paragraph rule-name 29)))))
              (is (= {:sizes {}} (rule-common/load-session-state "session")))
              (doseq [{:keys [frame-index]} deliveries]
                (rule-common/complete-injection-frame! "session" "event" frame-index))
              (is (= (zipmap rule-names (repeat current-metrics))
                     (:sizes (rule-common/load-session-state "session"))))
              (is (nil? (rule-common/injection-frame-for-lane! (request 0)))))))))))

(deftest partial-delivery-with-real-frames-reads-back-as-what-it-delivered-test
  (let [rule-stems ["alpha" "beta" "gamma"]
        rule-names (mapv #(str % ".mdc") rule-stems)
        hook-record (fn [output-key output]
                      (json/generate-string {:type "attachment"
                                             :attachment {:type "hook_success" output-key output}}))
        tool-output (fn [frame] (json/generate-string {:hookSpecificOutput {:additionalContext frame}}))]
    (with-temp-rules
      (large-rule-files rule-stems 40)
      (fn [_]
        (with-temp-state
          (fn []
            (with-redefs [rule-common/hook-lane-count 2]
              (let [request (fn [lane-index]
                              {:session-id "session"
                               :event-id "event"
                               :lane-index lane-index
                               :transcript-metrics-fn (constantly current-metrics)
                               :selection-fn
                               (fn []
                                 {:matching-rules rule-names
                                  :prefix-fn (fn [names]
                                               (str/join "\n" (map #(str "[rule-fairy injected: " % "]") names)))})})
                    deliveries (->> (range rule-common/hook-lane-count)
                                    (keep #(rule-common/injection-frame-for-lane! (request %)))
                                    vec)
                    frames (mapv :frame deliveries)
                    [header deferred-line injected-line] (str/split-lines (first frames))]
                (testing "three 12 KB rules in two lanes: the first rule in two frames, the other two deferred"
                  (is (= 2 (count frames)))
                  (is (= "[rule-fairy shard 1/2 event]" header)
                      "the header carries the delivery id, the leading part of the event id")
                  (is (= "[rule-fairy deferred: beta.mdc, gamma.mdc (past what one delivery can carry; delivered with the next matching event)]"
                         deferred-line))
                  (is (= "[rule-fairy injected: alpha.mdc]" injected-line))
                  (is (str/starts-with? (second frames) "[rule-fairy shard 2/2 event]\n")))
                (testing "the fork reader returns the delivered rule from the real frames, whichever shape the hook wrote"
                  (with-temp-project
                    {"whole.jsonl" (str (hook-record :content (first frames)) "\n"
                                        (hook-record :stdout (tool-output (second frames))) "\n")
                     "cut-short.jsonl" (str (hook-record :content (first frames)) "\n")}
                    (fn [root]
                      (let [inherited #(rule-fairy.transcript/inherited-injections
                                        (.toPath (io/file root %))
                                        @#'rule-common/compaction-pattern)]
                        (is (= ["alpha.mdc"] (inherited "whole.jsonl")))
                        (is (= [] (inherited "cut-short.jsonl"))
                            "a delivery missing its second shard is not inherited")))))
                (testing "only the delivered rule is marked once both lanes acknowledge"
                  (doseq [{:keys [frame-index]} deliveries]
                    (rule-common/complete-injection-frame! "session" "event" frame-index))
                  (is (= {"alpha.mdc" current-metrics}
                         (:sizes (rule-common/load-session-state "session")))))))))))))

(deftest documentation-budget-is-part-of-what-fits-test
  (let [paragraphs (fn [heading n]
                     (str/join "\n\n" (map #(str heading " paragraph " % ": " (apply str (repeat 960 "d"))) (range n))))
        section (fn [heading] (str "## " heading "\n\n" (paragraphs heading 20)))
        rule (fn [heading] (str "---\nglobs: apps/**/*.clj\nalwaysApply: false\n---\n# " heading "\n\n@doc/guide.md#" heading "\n"))
        request (fn [lane-index matching-rules]
                  {:session-id "session"
                   :event-id (str/join "-" matching-rules)
                   :lane-index lane-index
                   :transcript-metrics-fn (constantly current-metrics)
                   :selection-fn (fn []
                                   {:matching-rules matching-rules
                                    :prefix-fn (fn [names]
                                                 (str/join "\n" (map #(str "[rule-fairy injected: " % "]") names)))})})
        deliveries (fn [matching-rules]
                     (->> (range rule-common/hook-lane-count)
                          (keep #(rule-common/injection-frame-for-lane! (request % matching-rules)))
                          vec))]
    (with-temp-rules
      {"doc/guide.md" (str "# Guide\n\n" (str/join "\n\n" (map section ["one" "two" "three" "four"]))
                           "\n\n## big\n\n" (paragraphs "big" 70))
       ".cursor/rules/a.mdc" (rule "one")
       ".cursor/rules/b.mdc" (rule "two")
       ".cursor/rules/c.mdc" (rule "three")
       ".cursor/rules/d.mdc" (rule "four")
       ".cursor/rules/e.mdc" (rule "big")}
      (fn [_]
        (with-temp-state
          (fn []
            (testing "four rules whose documentation together passes 64 KiB: three delivered, the fourth deferred"
              (let [delivered (deliveries ["a.mdc" "b.mdc" "c.mdc" "d.mdc"])
                    first-frame (:frame (first delivered))]
                (is (<= 2 (count delivered) rule-common/hook-lane-count))
                (is (str/includes? first-frame "[rule-fairy deferred: d.mdc (past what one delivery can carry; delivered with the next matching event)]"))
                (is (str/includes? first-frame "[rule-fairy injected: c.mdc]"))
                (is (not (str/includes? first-frame "[rule-fairy injected: d.mdc]")))
                (doseq [{:keys [frame-index]} delivered]
                  (rule-common/complete-injection-frame! "session" "a.mdc-b.mdc-c.mdc-d.mdc" frame-index))
                (is (= {"a.mdc" current-metrics "b.mdc" current-metrics "c.mdc" current-metrics}
                       (:sizes (rule-common/load-session-state "session"))))))
            (testing "a single rule whose documentation alone passes the budget is reported, not marked"
              (let [{:keys [frame frame-index]} (first (deliveries ["e.mdc"]))]
                (is (str/includes? frame "[rule-fairy error: e.mdc alone expands to "))
                (is (str/includes? frame " bytes of documentation, over the 65536-byte budget]"))
                (is (str/includes? frame "No matched rules were injected"))
                (rule-common/complete-injection-frame! "session" "e.mdc" frame-index)
                (is (not (contains? (:sizes (rule-common/load-session-state "session")) "e.mdc")))))))))))

(defn registered-command
  "The command the plugin registers for an event and lane."
  [event lane]
  (let [settings (json/parse-string (slurp settings-file) true)]
    (:command (nth (get-in settings [:hooks event 0 :hooks]) lane))))

(defn run-registered-hook
  "Runs the registered command the way Claude Code does: through a shell with
  the plugin root and project directory exported, from the given working
  directory, hook JSON on stdin. env adds environment variables. Returns
  stdout; a non-zero exit fails the test."
  ([event lane project-root cwd input]
   (run-registered-hook event lane project-root cwd input {}))
  ([event lane project-root cwd input env]
   (:out (p/shell {:dir (str cwd)
                   :in (json/generate-string input)
                   :out :string
                   :extra-env (merge {"CLAUDE_PLUGIN_ROOT" (str repository-directory)
                                      "CLAUDE_PROJECT_DIR" (str project-root)}
                                     env)}
                  "sh" "-c" (registered-command event lane)))))

(defn run-prompt-hook
  "Lane 0 of the prompt hook, run from the project root."
  ([root session-id prompt]
   (run-prompt-hook root session-id prompt {}))
  ([root session-id prompt env]
   (run-registered-hook :UserPromptSubmit 0 root root
                        {:prompt prompt :session_id session-id}
                        env)))

(defn run-edit-hook
  "Lane 0 of the edit hook, run from an unrelated working directory to show
  the hook depends on CLAUDE_PROJECT_DIR and not on the cwd. A bare file
  path is a file_path under root; otherwise the tool input and any extra
  input fields are given as maps."
  ([root session-id file-path]
   (run-edit-hook root session-id {:file_path (str (io/file root file-path))} {}))
  ([root session-id tool-input extra-input]
   (run-registered-hook :PreToolUse 0 root (System/getProperty "java.io.tmpdir")
                        (merge {:session_id session-id :tool_input tool-input} extra-input))))

(deftest subagent-and-mcp-edit-test
  (with-temp-project
    {".cursor/rules/widget.mdc" "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (let [edit (fn [session-id tool-input extra-input]
                   (some-> (run-edit-hook root session-id tool-input extra-input)
                           not-empty
                           (json/parse-string true)
                           (get-in [:hookSpecificOutput :additionalContext])))
            file (str (io/file root "src/a.clj"))]
        (testing "inside a subagent the hook injects nothing and records nothing"
          (is (nil? (edit "session-sub" {:file_path file} {:agent_id "agent-1" :agent_type "Explore"})))
          (is (not (.exists (io/file root ".rule-fairy/claude/session-sub.edn")))))
        (testing "the main thread of the same session still gets the rule"
          (is (str/includes? (edit "session-sub" {:file_path file} {}) "# Widget rule")))
        (testing "an MCP edit naming its file as path selects rules too"
          (is (str/includes? (edit "session-mcp" {:path (str (io/file root "src/b.clj"))}
                                   {:tool_name "mcp__filesystem__write_file"})
                             "[rule-fairy matched: glob on src/b.clj]")))))))

(defn git! [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(defn shell-hook-result
  "Lane 0 of the shell hook for a PostToolUse or PostToolUseFailure event of
  the Bash tool, run from the project root: {:exit :out}, whatever the exit.
  Each call carries its own tool_use_id, as Claude Code's do, so each is its
  own hook event."
  [event root session-id]
  (p/shell {:dir (str root)
            :in (json/generate-string {:session_id session-id
                                       :hook_event_name (name event)
                                       :tool_name "Bash"
                                       :tool_use_id (str (random-uuid))
                                       :tool_input {:command "printf x > somewhere"}})
            :out :string
            :err :string
            :continue true
            :extra-env {"CLAUDE_PLUGIN_ROOT" (str repository-directory)
                        "CLAUDE_PROJECT_DIR" (str root)}}
           "sh" "-c" (registered-command event 0)))

(defn run-shell-hook
  "The shell hook's additionalContext for the event, or nil; a non-zero exit
  fails the test."
  [event root session-id]
  (let [{:keys [exit out]} (shell-hook-result event root session-id)]
    (is (zero? exit) (str "shell hook exited " exit))
    (when-not (str/blank? out)
      (let [{:keys [hookSpecificOutput]} (json/parse-string out true)]
        (is (= (name event) (:hookEventName hookSpecificOutput)) "the output names the event that ran")
        (:additionalContext hookSpecificOutput)))))

(defn shell-pending [root session-id]
  (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/claude"))]
    (:shell-pending (rule-common/load-session-state session-id))))

(deftest shell-hook-test
  (with-temp-project
    {".cursor/rules/widget.mdc"
     "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     ".cursor/rules/docs.mdc"
     "---\nglobs: docs/**\nalwaysApply: false\n---\n# Docs rule\n"
     "src/a.clj" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (run-prompt-hook root "session-shell" "hello")
      (testing "a tracked file a shell command edited, and nothing else, gets its rules after the command"
        (spit (io/file root "src/a.clj") "(ns a) :edited\n")
        (let [context (run-shell-hook :PostToolUse root "session-shell")]
          (is (str/includes? context "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on src/a.clj, changed by a shell command]"))
          (is (str/includes? context "Apply these rules on the next pass"))
          (is (str/includes? context "# Widget rule"))))
      (testing "the next shell command finds nothing new"
        (is (nil? (run-shell-hook :PostToolUse root "session-shell"))))
      (testing "a failed command is checked too, and an already injected rule is not repeated"
        (spit (io/file root "src/c.clj") "(ns c)\n")
        (io/make-parents (io/file root "docs/x.md"))
        (spit (io/file root "docs/x.md") "# x\n")
        (let [context (run-shell-hook :PostToolUseFailure root "session-shell")]
          (is (str/includes? context "[rule-fairy injected: docs.mdc]"))
          (is (not (str/includes? context "widget.mdc")))
          (is (str/includes? context "[rule-fairy matched: glob on docs/x.md, changed by a shell command]"))))
      (testing "the marker lives in the session state and nothing is left pending"
        (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/claude"))]
          (let [state (rule-common/load-session-state "session-shell")]
            (is (number? (:shell-check state)))
            (is (= [] (:shell-pending state))))))
      (testing "a commit that moves HEAD injects nothing: the check reads the working tree, not the commits"
        (run-prompt-hook root "session-commit" "hello")
        (git! root "add" "-A")
        (git! root "commit" "-q" "-m" "earlier shell writes")
        (is (nil? (run-shell-hook :PostToolUse root "session-commit")))))))

(deftest shell-hook-disabled-test
  (with-temp-project
    {"rule-fairy.edn" "{:shell-hook {:claude false}}"
     ".cursor/rules/widget.mdc"
     "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (run-prompt-hook root "session-off" "hello")
      (spit (io/file root "src/a.clj") "(ns a) :edited\n")
      (is (nil? (run-shell-hook :PostToolUse root "session-off"))
          "with the shell hook off in rule-fairy.edn a shell write injects nothing")
      (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/claude"))]
        (is (nil? (:shell-check (rule-common/load-session-state "session-off")))
            "and the prompt hook starts no check")))))

(deftest shell-hook-pending-test
  (with-temp-project
    {".cursor/rules/lib.mdc"
     "---\nglobs: lib/**\nalwaysApply: false\n---\n# Lib rule\n\n@doc/lib.md\n"}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (run-prompt-hook root "session-pending" "hello")
      (io/make-parents (io/file root "lib/x.clj"))
      (spit (io/file root "lib/x.clj") "(ns x)\n")
      (testing "a rule that cannot be built fails the hook and keeps the path pending"
        (let [{:keys [exit err]} (shell-hook-result :PostToolUse root "session-pending")]
          (is (pos? exit))
          (is (str/includes? err "Rule import does not exist"))
          (is (= ["lib/x.clj"] (shell-pending root "session-pending")))))
      (testing "the next shell command delivers the pending path once the rule builds"
        (io/make-parents (io/file root "doc/lib.md"))
        (spit (io/file root "doc/lib.md") "# Lib guide\n")
        (let [context (run-shell-hook :PostToolUse root "session-pending")]
          (is (str/includes? context "[rule-fairy injected: lib.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on lib/x.clj, changed by a shell command]"))
          (is (= [] (shell-pending root "session-pending"))))))))

(deftest installed-hooks-test
  (with-temp-project
    {".cursor/rules/base.mdc"
     "---\ndescription: Base\nalwaysApply: true\n---\n# Base rule\n\nAlways on.\n"
     ".cursor/rules/widget.mdc"
     "---\npromptAnyOf: widget\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""
     ;; A consuming repository's bb.edn must not reach the hook's classpath.
     "bb.edn" "{:paths [\"nonexistent\"] :deps {org.example/missing {:mvn/version \"1.0.0\"}}}"}
    (fn [root]
      (testing "the first prompt injects the always-on rule under its own label"
        (let [out (run-prompt-hook root "session" "hello there")]
          (is (str/includes? out "[rule-fairy injected: base.mdc]"))
          (is (str/includes? out "[rule-fairy matched: alwaysApply base.mdc]"))
          (is (not (str/includes? out "[rule-fairy matched: keyword")))
          (is (str/includes? out "# Base rule"))
          (is (not (str/includes? out "# Widget rule")))))
      (testing "a later keyword match injects only the new rule"
        (let [out (run-prompt-hook root "session" "add a widget")]
          (is (str/includes? out "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? out "[rule-fairy matched: keyword \"widget\"]"))
          (is (not (str/includes? out "base.mdc")))
          (is (not (str/includes? out "alwaysApply")))))
      (testing "a repeated prompt injects nothing"
        (is (str/blank? (run-prompt-hook root "session" "hello there"))))
      (testing "the edit hook finds the project from CLAUDE_PROJECT_DIR, not the cwd"
        (let [out (run-edit-hook root "session-edit" "src/a.clj")
              context (get-in (json/parse-string out true) [:hookSpecificOutput :additionalContext])]
          (is (str/includes? context "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on src/a.clj]"))))
      (testing "state and caches land in the project's own state directory"
        (is (.isFile (io/file root ".rule-fairy/claude/.gitignore")))
        (is (.isFile (io/file root ".rule-fairy/claude/globs-cache.edn")))
        (is (.isFile (io/file root ".rule-fairy/claude/session.edn")))))))

(deftest relocated-config-dir-test
  (with-temp-project
    {".cursor/rules/base.mdc"
     "---\ndescription: Base\nalwaysApply: true\n---\n# Base rule\n"
     "config/projects/-Users-example-project/session-relocated.jsonl"
     "{\"type\":\"user\",\"isCompactSummary\":true}\n"}
    (fn [root]
      (let [env {"CLAUDE_CONFIG_DIR" (str (io/file root "config"))}
            transcript (io/file root "config/projects/-Users-example-project/session-relocated.jsonl")]
        (testing "the transcript is found under CLAUDE_CONFIG_DIR and a compaction reinjects"
          (is (str/includes? (run-prompt-hook root "session-relocated" "hello" env)
                             "[rule-fairy injected: base.mdc]"))
          (is (str/blank? (run-prompt-hook root "session-relocated" "again" env))
              "nothing new to inject while the transcript is unchanged")
          (spit transcript "{\"type\":\"user\",\"isCompactSummary\":true}\n" :append true)
          (is (str/includes? (run-prompt-hook root "session-relocated" "after compaction" env)
                             "[rule-fairy injected: base.mdc]")
              "the compaction recorded in the relocated transcript triggers reinjection"))))))

(deftest state-directory-ignores-itself-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:sizes {}})
      (is (= "*\n" (slurp (io/file rule-common/state-dir ".gitignore")))))))

(deftest hook-lane-configuration-test
  (let [settings (json/parse-string (slurp settings-file) true)]
    (is (= "^Bash$" (get-in settings [:hooks :PostToolUse 0 :matcher])))
    (is (= "^Bash$" (get-in settings [:hooks :PostToolUseFailure 0 :matcher])))
    (doseq [[event script] [[:UserPromptSubmit "claude/prompt.bb"]
                            [:PreToolUse "claude/edit.bb"]
                            [:PostToolUse "claude/shell.bb"]
                            [:PostToolUseFailure "claude/shell.bb"]]]
      (let [handlers (get-in settings [:hooks event 0 :hooks])]
        (is (= rule-common/hook-lane-count (count handlers)))
        (is (= rule-common/hook-lane-count
               (count (distinct (map #(select-keys % [:command :args]) handlers)))))
        (is (= (set (range rule-common/hook-lane-count))
               (set (map #(configured-hook-lane (:command %)) handlers))))
        (is (every? #(str/ends-with? (:command %) script) handlers))))))

(deftest injection-batch-test
  (with-temp-state
    (fn []
      (let [session-id "session"
            event-id "event"
            frames (mapv #(str "frame-" %) (range rule-common/hook-lane-count))
            render-count (atom 0)
            transcript-metrics-count (atom 0)
            frame-request #(assoc (test-frame-request %)
                                  :transcript-metrics-fn
                                  (fn []
                                    (swap! transcript-metrics-count inc)
                                    current-metrics))]
        (with-redefs [rule-common/injection-frames
                      (fn [& _]
                        (swap! render-count inc)
                        frames)]
          (let [deliveries (->> (range rule-common/hook-lane-count)
                                (mapv (fn [lane-index]
                                        (future
                                          (rule-common/injection-frame-for-lane!
                                           (frame-request lane-index)))))
                                (mapv deref))]
            (is (= 1 @render-count))
            (is (= 1 @transcript-metrics-count))
            (is (= (mapv (fn [lane-index]
                           {:frame-index lane-index
                            :frame (str "frame-" lane-index)})
                         (range rule-common/hook-lane-count))
                   (mapv #(select-keys % [:frame-index :frame]) deliveries)))
            (is (= {:sizes {}}
                   (rule-common/load-session-state session-id)))

            (doseq [{:keys [frame-index]} (butlast deliveries)]
              (rule-common/complete-injection-frame!
               session-id event-id frame-index))
            (is (= {:sizes {}}
                   (rule-common/load-session-state session-id)))

            (rule-common/complete-injection-frame!
             session-id event-id (:frame-index (last deliveries)))
            (is (= current-metrics
                   (get-in (rule-common/load-session-state session-id)
                           [:sizes rule-name])))
            (is (nil? (rule-common/injection-frame-for-lane!
                       (frame-request 0))))))))))

(defn injecting-hook-record
  "A transcript record of a prompt hook's output injecting one rule, as a
  forked session's transcript replays it from the parent."
  [rule-name]
  (json/generate-string {:type "attachment"
                         :attachment {:type "hook_success"
                                      :hookName "UserPromptSubmit"
                                      :content (str "[rule-fairy shard 1/1 " rule-name "]\n[rule-fairy injected: " rule-name "]\n\n# body")}}))

(deftest forked-session-inherits-its-transcripts-injections-test
  (testing "a rule the inherited context holds is marked, not delivered again"
    (with-temp-project
      {"-Users-example-Workspace-project/session.jsonl"
       (str "{\"type\":\"user\",\"isCompactSummary\":true}\n"
            (injecting-hook-record rule-name) "\n")}
      (fn [root]
        (with-temp-state
          (fn []
            (with-redefs [rule-common/claude-projects-dir (.getAbsolutePath root)
                          rule-common/injection-frames (constantly ["frame"])]
              (is (nil? (rule-common/injection-frame-for-lane! (test-frame-request 0))))
              (is (= {rule-name current-metrics}
                     (:sizes (rule-common/load-session-state "session"))))))))))
  (testing "a rule injected before the last compaction is no longer in context and is delivered"
    (with-temp-project
      {"-Users-example-Workspace-project/session.jsonl"
       (str (injecting-hook-record rule-name) "\n"
            "{\"type\":\"user\",\"isCompactSummary\":true}\n"
            (injecting-hook-record "other.mdc") "\n")}
      (fn [root]
        (with-temp-state
          (fn []
            (with-redefs [rule-common/claude-projects-dir (.getAbsolutePath root)
                          rule-common/injection-frames (constantly ["frame"])]
              (let [{:keys [frame-index] :as delivery} (rule-common/injection-frame-for-lane! (test-frame-request 0))]
                (is (some? delivery))
                (is (= {"other.mdc" current-metrics}
                       (:sizes (rule-common/load-session-state "session")))
                    "the inherited rule is marked at once, the delivered one only when its lanes complete")
                (rule-common/complete-injection-frame! "session" "event" frame-index)
                (is (= {"other.mdc" current-metrics rule-name current-metrics}
                       (:sizes (rule-common/load-session-state "session"))))))))))))

(deftest deduplicated-event-scans-transcript-once-test
  (with-temp-state
    (fn []
      (rule-common/mark-injected! "session" {:sizes {}} [rule-name] current-metrics)
      (let [transcript-metrics-count (atom 0)
            frame-request #(assoc (test-frame-request %)
                                  :transcript-metrics-fn
                                  (fn []
                                    (swap! transcript-metrics-count inc)
                                    current-metrics))]
        (doseq [lane-index (range rule-common/hook-lane-count)]
          (is (nil? (rule-common/injection-frame-for-lane!
                     (frame-request lane-index)))))
        (is (= 1 @transcript-metrics-count))
        (is (= current-metrics
               (get-in (rule-common/load-session-state "session")
                       [:sizes rule-name])))))))

(deftest incomplete-batch-keeps-unacknowledged-lane-available-test
  (with-temp-state
    (fn []
      (with-redefs [rule-common/injection-frames (constantly ["one" "two"])]
        (let [delivery (rule-common/injection-frame-for-lane!
                        (test-frame-request 0))]
          (rule-common/complete-injection-frame!
           "session" "event" (:frame-index delivery))
          (is (= {:sizes {}}
                 (rule-common/load-session-state "session")))
          (is (= {:frame-index 1 :frame "two"}
                 (select-keys
                  (rule-common/injection-frame-for-lane!
                   (test-frame-request 1))
                  [:frame-index :frame])))
          (is (some? (rule-common/injection-frame-for-lane!
                      (test-frame-request 1))))
          (is (nil? (rule-common/injection-frame-for-lane!
                     (test-frame-request 2)))))))))

(defn one-frame-per-rule
  "An injection-frames stand-in needing one frame per rule, the first frame
  carrying the prefix and the delivered rule names."
  [rule-names prefix _delivery-id]
  (into [(str prefix "|" (str/join "," rule-names))]
        (repeat (dec (count rule-names)) "more")))

(deftest partial-delivery-marks-only-the-rules-that-fit-test
  (with-temp-state
    (fn []
      (with-redefs [rule-common/hook-lane-count 1
                    rule-common/injection-frames one-frame-per-rule]
        (let [request #(assoc (test-frame-request 0)
                              :event-id %
                              :selection-fn (constantly {:matching-rules ["a.mdc" "b.mdc"]
                                                         :prefix-fn (fn [names] (str "injected " (str/join "," names)))}))
              {:keys [frame frame-index]} (rule-common/injection-frame-for-lane! (request "first"))]
          (is (= (str "[rule-fairy deferred: b.mdc (past what one delivery can carry; delivered with the next matching event)]\n"
                      "injected a.mdc"
                      "|a.mdc")
                 frame)
              "the shard carries the rules that fit and opens by naming the deferred one")
          (rule-common/complete-injection-frame! "session" "first" frame-index)
          (is (= {"a.mdc" current-metrics} (:sizes (rule-common/load-session-state "session")))
              "only the delivered rule is marked")
          (let [{:keys [frame frame-index]} (rule-common/injection-frame-for-lane! (request "second"))]
            (is (= "injected b.mdc|b.mdc" frame)
                "the next matching event delivers the rest, with nothing left to defer")
            (rule-common/complete-injection-frame! "session" "second" frame-index))
          (is (= {"a.mdc" current-metrics "b.mdc" current-metrics}
                 (:sizes (rule-common/load-session-state "session")))))))))

(deftest oversized-rule-is-reported-and-not-marked-test
  (with-temp-state
    (fn []
      (with-redefs [rule-common/hook-lane-count 1
                    rule-common/injection-frames (constantly ["one" "two"])]
        (let [{:keys [event-id frame-index frame] :as delivery}
              (rule-common/injection-frame-for-lane!
               (test-frame-request 0))]
          (is (some? delivery))
          (is (str/includes? frame (str "[rule-fairy error: " rule-name " alone needs 2 output frames")))
          (is (str/includes? frame "No matched rules were injected"))
          (rule-common/complete-injection-frame! "session" event-id frame-index)
          (is (= {:sizes {}}
                 (rule-common/load-session-state "session")))
          (is (nil? (rule-common/injection-frame-for-lane!
                     (test-frame-request 0)))))))))

(defn shell-frame-request
  "A frame request whose selection delivers pending shell paths."
  [lane-index paths matching-rules]
  (assoc (test-frame-request lane-index)
         :selection-fn (fn []
                         {:matching-rules matching-rules
                          :prefix-fn (constantly "prefix")
                          :paths paths})))

(defn shell-pending-state []
  (:shell-pending (rule-common/load-session-state "session")))

(deftest shell-prefix-names-paths-within-a-character-budget-test
  (testing "paths that fit the budget are named in full"
    (let [paths (mapv #(str "src/file-" % ".clj") (range 25))
          prefix (rule-common/shell-prefix ["a.mdc" "b.mdc"] paths)]
      (is (str/starts-with? prefix "[rule-fairy injected: a.mdc]\n[rule-fairy injected: b.mdc]\n[rule-fairy matched: glob on src/file-0.clj, "))
      (is (str/includes? prefix "src/file-24.clj, changed by a shell command]"))
      (is (not (str/includes? prefix " more")))))
  (testing "the leading paths that fit are named, the rest counted: 60-character paths, 16 fit in 1,000"
    (let [paths (mapv #(str "src/" (apply str (repeat 48 "x")) "/f" (format "%02d" %) ".clj") (range 30))
          prefix (rule-common/shell-prefix ["a.mdc"] paths)]
      (is (every? #(= 60 (count %)) paths))
      (is (str/includes? prefix "/f15.clj and 14 more, changed by a shell command]"))
      (is (not (str/includes? prefix "/f16.clj")))))
  (testing "twenty 512-character paths: one named, the rest counted, prefix inside the frame"
    (let [segment (apply str (repeat 70 "a"))
          paths (mapv #(str "src/" (str/join "/" (repeat 7 segment)) "/file-" % ".clj") (range 20))
          prefix (rule-common/shell-prefix ["a.mdc"] paths)]
      (is (= 512 (apply max (map count paths))))
      (is (str/includes? prefix "/file-0.clj and 19 more, changed by a shell command]"))
      (is (< (count prefix) (quot rule-common/max-hook-context-chars 2)))))
  (testing "a single path longer than the budget is counted, not named"
    (let [prefix (rule-common/shell-prefix ["a.mdc"] [(apply str (repeat 1200 "p"))])]
      (is (str/includes? prefix "[rule-fairy matched: glob on 1 paths, changed by a shell command]"))))
  (testing "hundreds of paths, as after a rebase, leave the prefix far inside the frame"
    (let [many (mapv #(str "apps/home/src/scarlet/home/ui/some/deeply/nested/namespace/file_" % "_controller.clj")
                     (range 300))
          prefix (rule-common/shell-prefix (mapv #(str "rule-" % ".mdc") (range 14)) many)]
      (is (re-find #" and 2\d\d more, changed by a shell command\]" prefix))
      (is (< (count prefix) (quot rule-common/max-hook-context-chars 2))))))

(deftest shell-paths-stay-pending-until-every-lane-delivered-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:sizes {} :shell-pending ["src/x.clj" "src/y.clj"]})
      (with-redefs [rule-common/injection-frames (constantly ["one" "two"])]
        (let [request #(shell-frame-request % ["src/x.clj"] [rule-name])
              first-delivery (rule-common/injection-frame-for-lane! (request 0))]
          (is (= ["src/x.clj" "src/y.clj"] (shell-pending-state))
              "building the batch delivers nothing yet")
          (rule-common/complete-injection-frame! "session" "event" (:frame-index first-delivery))
          (is (= ["src/x.clj" "src/y.clj"] (shell-pending-state))
              "one lane of two is not delivery")
          (rule-common/complete-injection-frame!
           "session" "event" (:frame-index (rule-common/injection-frame-for-lane! (request 1))))
          (is (= ["src/y.clj"] (shell-pending-state))
              "the last lane's acknowledgement clears the batch's paths")
          (is (= current-metrics
                 (get-in (rule-common/load-session-state "session") [:sizes rule-name]))))))))

(deftest shell-paths-with-nothing-to-deliver-clear-at-once-test
  (with-temp-state
    (fn []
      (testing "no rule matches the paths"
        (rule-common/save-session-state! "session" {:sizes {} :shell-pending ["src/x.clj"]})
        (is (nil? (rule-common/injection-frame-for-lane! (shell-frame-request 0 ["src/x.clj"] []))))
        (is (= [] (shell-pending-state))))
      (testing "every matching rule is already injected"
        (rule-common/save-session-state! "session" {:sizes {rule-name current-metrics}
                                                    :shell-pending ["src/x.clj"]})
        (is (nil? (rule-common/injection-frame-for-lane!
                   (assoc (shell-frame-request 0 ["src/x.clj"] [rule-name]) :event-id "later"))))
        (is (= [] (shell-pending-state)))))))

(deftest shell-paths-stay-pending-across-a-partial-delivery-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:sizes {} :shell-pending ["src/x.clj"]})
      (with-redefs [rule-common/hook-lane-count 1
                    rule-common/injection-frames one-frame-per-rule]
        (let [request #(assoc (shell-frame-request 0 ["src/x.clj"] ["a.mdc" "b.mdc"]) :event-id %)
              first-delivery (rule-common/injection-frame-for-lane! (request "first"))]
          (rule-common/complete-injection-frame! "session" "first" (:frame-index first-delivery))
          (is (= ["src/x.clj"] (shell-pending-state))
              "a delivery that defers a rule keeps the path for the next shell command")
          (is (= {"a.mdc" current-metrics} (:sizes (rule-common/load-session-state "session"))))
          (let [second-delivery (rule-common/injection-frame-for-lane! (request "second"))]
            (rule-common/complete-injection-frame! "session" "second" (:frame-index second-delivery)))
          (is (= [] (shell-pending-state))
              "the path clears once the last deferred rule has been delivered")
          (is (= {"a.mdc" current-metrics "b.mdc" current-metrics}
                 (:sizes (rule-common/load-session-state "session")))))))))

(deftest shell-paths-stay-pending-for-an-oversized-rule-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:sizes {} :shell-pending ["src/x.clj"]})
      (with-redefs [rule-common/hook-lane-count 1
                    rule-common/injection-frames (constantly ["one" "two"])]
        (let [{:keys [event-id frame-index frame]}
              (rule-common/injection-frame-for-lane! (shell-frame-request 0 ["src/x.clj"] [rule-name]))]
          (is (str/includes? frame "No matched rules were injected"))
          (rule-common/complete-injection-frame! "session" event-id frame-index)
          (is (= ["src/x.clj"] (shell-pending-state))
              "a rule that cannot fit the lanes delivers nothing, so the path waits and the next command fails loudly again")
          (is (= {} (:sizes (rule-common/load-session-state "session")))))))))

(deftest failed-batch-render-is-not-persisted-test
  (with-temp-state
    (fn []
      (let [render-count (atom 0)]
        (with-redefs [rule-common/injection-frames
                      (fn [& _]
                        (swap! render-count inc)
                        (throw (ex-info "render failed" {})))]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"render failed"
               (rule-common/injection-frame-for-lane!
                (test-frame-request 0))))
          (is (= {:sizes {}}
                 (rule-common/load-session-state "session")))
          (is (nil? (rule-common/injection-frame-for-lane!
                     (test-frame-request 1))))
          (is (= 1 @render-count)))))))

(deftest shared-renderer-test
  (with-temp-rules
    {".cursor/rules/testing.mdc"
     (str "---\nglobs: **/test/**/*.clj\nalwaysApply: false\n---\n"
          "# Testing\n\n@doc/conventions/test_conventions.md#given-when-then\n")
     "doc/conventions/test_conventions.md"
     (str "# Test conventions\n\n"
          "## Given, when, then\n\nName each testing block by its phase.\n\n"
          "## Fixtures\n\nnot imported\n")}
    (fn [_]
      (let [rendered (rule-common/render-rules ["testing.mdc"])]
        (is (str/includes? rendered "# testing.mdc\n\n# Testing"))
        (is (str/includes? rendered "# Required documentation"))
        (is (str/includes? rendered
                           "## Required context: `doc/conventions/test_conventions.md#given-when-then`"))
        (is (str/includes? rendered "Name each testing block by its phase."))
        (is (not (str/includes? rendered "not imported")))))))

(deftest plugin-root-recorded-test
  (with-temp-project
    {".cursor/rules/base.mdc" "---\nalwaysApply: true\n---\n# Base\n"}
    (fn [root]
      (run-prompt-hook root "session" "hello")
      (is (= (str repository-directory "\n")
             (slurp (io/file root ".rule-fairy/claude/plugin-root")))
          "the first prompt records the plugin root the launcher resolved, on one line"))))

(let [{:keys [fail error]} (run-tests 'rule-common-test)]
  (System/exit (+ fail error)))

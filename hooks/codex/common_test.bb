#!/usr/bin/env bb

(ns rule-common-test
  (:require [babashka.process :as p]
            [rule-fairy.rules :as rules]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def hooks-directory (.getParentFile (.getAbsoluteFile (io/file *file*))))
(def repository-directory (.getParentFile (.getParentFile hooks-directory)))
(def registration-file (io/file repository-directory "hooks/codex-hooks.json"))

(load-file (str (io/file hooks-directory "common.bb")))

(def rule-name "testing.mdc")
(def representative-edited-paths
  ["apps/example/src/example/ui/example_controller.clj"
   "apps/example/test/example/ui/example_controller_test.clj"])

(defn matching-rule-names-for-paths [paths]
  (with-redefs [rule-common/load-or-build-cache rule-common/build-cache]
    (->> paths
         (mapcat rule-common/rules-for-path)
         distinct
         sort)))

(defn with-temp-project [files f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "rules-test"
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

(defn with-temp-state [f]
  (with-temp-project
    {}
    (fn [root]
      (with-redefs [rule-common/state-dir (.getAbsolutePath root)]
        (f)))))

(defn render-test-rule
  ([root]
   (render-test-rule root ["test.mdc"]))
  ([root rule-names]
   (rules/render-rules (rules/project-rules-source root) rule-names)))

(deftest rules-for-path-test
  (with-temp-project
    {".cursor/rules/ui.mdc" ""
     ".cursor/rules/testing.mdc" ""}
    (fn [root]
      (with-redefs [rule-common/rules-source (rules/project-rules-source root)
                    rule-common/load-or-build-cache
                    (constantly {"ui.mdc" ["apps/**/ui/**/*.clj"]
                                 "testing.mdc" ["**/test/**/*.clj"]})]
        (is (= #{"ui.mdc"}
               (set (rule-common/rules-for-path
                     "apps/example/src/example/ui/screen.clj"))))
        (is (= #{"testing.mdc" "ui.mdc"}
               (set (rule-common/rules-for-path
                     "apps/example/test/example/ui/screen_test.clj"))))))))

(deftest glob-cache-preserves-relative-paths-test
  (with-temp-project
    {".cursor/rules/ui.mdc"
     "---\nglobs: apps/top/**\nalwaysApply: false\n---\n# Top-level UI"
     ".cursor/rules/tasks/ui.mdc"
     "---\nglobs: apps/nested/**\nalwaysApply: false\n---\n# Nested UI"}
    (fn [root]
      (with-redefs [rule-common/rules-source (rules/project-rules-source root)]
        (is (= {"ui.mdc" ["apps/top/**"]
                "tasks/ui.mdc" ["apps/nested/**"]}
               (rule-common/build-cache)))))))

(deftest glob-cache-invalidates-on-nested-rename-test
  (with-temp-project
    {".cursor/rules/tasks/old.mdc"
     "---\nglobs: apps/old/**\nalwaysApply: false\n---\n# Old task"
     ".rule-fairy/codex/globs-cache.edn"
     "{\"tasks/old.mdc\" [\"apps/old/**\"]}"}
    (fn [root]
      (let [rules-directory (io/file root ".cursor/rules")
            tasks-directory (io/file rules-directory "tasks")
            old-rule (io/file tasks-directory "old.mdc")
            new-rule (io/file tasks-directory "new.mdc")
            cache (io/file root ".rule-fairy/codex/globs-cache.edn")
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

(deftest rules-for-prompt-test
  (with-temp-project
    {".cursor/rules/service.mdc"
     (str "---\n"
          "description: Service rules\n"
          "promptAnyOf: authorization, find-or-create\n"
          "promptRequires: service\n"
          "alwaysApply: false\n"
          "---\n\n"
          "# Service")
     ".cursor/rules/testing.mdc" "# Top-level Testing"
     ".cursor/rules/base.mdc"
     "---\ndescription: Base\nalwaysApply: true\n---\n# Base"
     ".cursor/rules/tasks/testing.mdc"
     (str "---\n"
          "description: Testing rules\n"
          "promptAnyOf: deftest, match?\n"
          "alwaysApply: false\n"
          "---\n\n"
          "# Nested Testing")}
    (fn [root]
      (with-redefs [rule-common/rules-source (rules/project-rules-source root)]
        (testing "always-on rules come first, then all required terms and one optional term match"
          (is (= [{:rule "base.mdc" :always-apply? true :keywords []}
                  {:rule "service.mdc"
                   :always-apply? false
                   :keywords ["authorization" "service"]}
                  {:rule "tasks/testing.mdc"
                   :always-apply? false
                   :keywords ["deftest"]}]
                 (rule-common/rules-for-prompt
                  "Update the SERVICE authorization deftest"))))

        (testing "the relative rule name renders the nested file when basenames collide"
          (let [matches (remove :always-apply?
                                (rule-common/rules-for-prompt "Update the deftest"))
                rendered (render-test-rule root (mapv :rule matches))]
            (is (= [{:rule "tasks/testing.mdc"
                     :always-apply? false
                     :keywords ["deftest"]}]
                   matches))
            (is (str/includes? rendered "# tasks/testing.mdc\n\n# Nested Testing"))
            (is (not (str/includes? rendered "Top-level Testing")))))

        (testing "missing required terms prevent a keyword match"
          (is (= ["base.mdc"]
                 (mapv :rule (rule-common/rules-for-prompt
                             "Update authorization")))))

        (testing "plain words use word boundaries"
          (is (= ["base.mdc"]
                 (mapv :rule (rule-common/rules-for-prompt
                             "Rename mydeftesthelper")))))))))

(defn registered-command
  "The command the plugin registers for a Codex event."
  [event]
  (let [registration (json/parse-string (slurp registration-file) true)]
    (:command (first (get-in registration [:hooks event 0 :hooks])))))

(defn run-registered-hook
  "Runs the registered command the way Codex does: through a shell with the
  plugin root exported, from the session cwd (the project root unless given),
  hook JSON on stdin. Returns the additionalContext or nil; a non-zero exit
  fails the test."
  ([event root input]
   (run-registered-hook event root root input))
  ([event root cwd input]
   (let [out (:out (p/shell {:dir (str cwd)
                             :in (json/generate-string (assoc input :cwd (str cwd)))
                             :out :string
                             :extra-env {"PLUGIN_ROOT" (str repository-directory)}}
                            "sh" "-c" (registered-command event)))]
     (when-not (str/blank? out)
       (get-in (json/parse-string out true) [:hookSpecificOutput :additionalContext])))))

(defn run-prompt-hook [root session-id prompt]
  (run-registered-hook :UserPromptSubmit root {:prompt prompt :session_id session-id}))

(defn run-post-tool-hook
  "The post-edit hook with a project-relative edited path. The temp root is
  passed as the logical path, which on macOS is a symlink into /private; the
  hook must relativise it against the physical working directory correctly."
  [root session-id file-path]
  (run-registered-hook :PostToolUse root {:session_id session-id
                                           :tool_input {:file_path file-path}}))

(deftest codex-registration-test
  (let [registration (json/parse-string (slurp registration-file) true)]
    (doseq [[event script] [[:UserPromptSubmit "codex/prompt.bb"]
                            [:PostToolUse "codex/post_edit.bb"]]]
      (let [handlers (get-in registration [:hooks event 0 :hooks])]
        (is (= 1 (count handlers)))
        (is (str/ends-with? (:command (first handlers)) script))
        (is (not (str/includes? (:command (first handlers)) "git"))
            "the hook finds the checkout root itself, no git process in the registration")
        (is (= 0 (:additionalContextLimit (first handlers))))
        (is (string? (:statusMessage (first handlers))))))
    (let [matcher (get-in registration [:hooks :PostToolUse 0 :matcher])]
      (is (str/includes? matcher "apply_patch"))
      (is (re-find #"\bBash\b" matcher) "shell commands reach the post-edit hook as Bash"))))

(defn git! [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(deftest hook-run-from-a-subdirectory-test
  (with-temp-project
    {".cursor/rules/always.mdc" "---\nalwaysApply: true\n---\n# Always rule\n"
     ".cursor/rules/widget.mdc" "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/nested/a.clj" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (let [cwd (io/file root "src/nested")]
        (testing "the prompt hook finds the project from the agent's cwd inside the checkout"
          (is (str/includes? (run-registered-hook :UserPromptSubmit root cwd
                                                  {:prompt "hello" :session_id "session-sub"})
                             "# Always rule")))
        (testing "an edit named relative to that cwd matches the project-relative glob"
          (is (str/includes? (run-registered-hook :PostToolUse root cwd
                                                  {:session_id "session-sub"
                                                   :tool_input {:file_path "a.clj"}})
                             "# Widget rule")))
        (testing "state lands under the project root, not the cwd"
          (is (.isDirectory (io/file root ".rule-fairy/codex")))
          (is (not (.exists (io/file cwd ".rule-fairy")))))))))

(deftest subproject-in-a-checkout-test
  (with-temp-project
    {"backend/.cursor/rules/widget.mdc"
     "---\nglobs: src/**\nalwaysApply: false\n---\n# Backend widget rule\n"
     "backend/src/nested/a.clj" ""
     "tools/rule-fairy.edn" "{:rules {:dirs [\"rules\"]}}"
     "tools/rules/tool.mdc" "---\nglobs: bin/**\nalwaysApply: false\n---\n# Tool rule\n"
     "tools/bin/run.sh" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (testing "a session started in a subdirectory with its own rules directory is that project"
        (is (str/includes? (run-registered-hook :PostToolUse root (io/file root "backend")
                                                {:session_id "session-backend"
                                                 :tool_input {:file_path "src/nested/a.clj"}})
                           "# Backend widget rule"))
        (is (.isDirectory (io/file root "backend/.rule-fairy/codex")))
        (is (not (.exists (io/file root ".rule-fairy")))))
      (testing "a hook run deeper down still finds the subproject and matches relative to it"
        (is (str/includes? (run-registered-hook :PostToolUse root (io/file root "backend/src")
                                                {:session_id "session-backend-deep"
                                                 :tool_input {:file_path "nested/a.clj"}})
                           "[rule-fairy matched: glob on src/nested/a.clj]")))
      (testing "a rule-fairy.edn alone marks a project"
        (is (str/includes? (run-registered-hook :PostToolUse root (io/file root "tools")
                                                {:session_id "session-tools"
                                                 :tool_input {:file_path "bin/run.sh"}})
                           "# Tool rule"))))))

(deftest edit-in-a-worktree-test
  (with-temp-project
    {".cursor/rules/widget.mdc" "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (git! root "worktree" "add" "-q" ".worktrees/review")
      (testing "an edit in a linked worktree, made from the main checkout's cwd, matches the project's globs"
        (let [context (run-registered-hook :PostToolUse root {:session_id "session-worktree"
                                                              :tool_input {:file_path ".worktrees/review/src/a.clj"}})]
          (is (str/includes? context "[rule-fairy matched: glob on src/a.clj]"))
          (is (str/includes? context "# Widget rule")))))))

(def shell-hook-input
  {:tool_name "Bash"
   :tool_input {:command "printf x > somewhere"}})

(defn run-shell-hook
  "The post-edit hook after a Bash command, run from the project root."
  [root session-id]
  (run-registered-hook :PostToolUse root (assoc shell-hook-input :session_id session-id)))

(defn shell-hook-result
  "The post-edit hook after a Bash command: {:exit :out :err}, whatever the exit."
  [root session-id]
  (p/shell {:dir (str root)
            :in (json/generate-string (assoc shell-hook-input :session_id session-id :cwd (str root)))
            :out :string
            :err :string
            :continue true
            :extra-env {"PLUGIN_ROOT" (str repository-directory)}}
           "sh" "-c" (registered-command :PostToolUse)))

(defn shell-pending [root session-id]
  (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/codex"))]
    (:shell-pending (rule-common/load-session-state session-id))))

(deftest shell-change-test
  (with-temp-project
    {"rule-fairy.edn" "{:shell-hook {:codex true}}"
     ".cursor/rules/widget.mdc"
     "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (run-prompt-hook root "session-shell" "hello")
      (testing "a tracked file a shell command edited, and nothing else, gets its rules after the command"
        (spit (io/file root "src/a.clj") "(ns a) :edited\n")
        (let [context (run-shell-hook root "session-shell")]
          (is (str/includes? context "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on src/a.clj, changed by a shell command]"))
          (is (str/includes? context "# Widget rule"))))
      (testing "the next shell command finds nothing new"
        (is (nil? (run-shell-hook root "session-shell"))))
      (testing "the marker lives in the session state and nothing is left pending"
        (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/codex"))]
          (let [state (rule-common/load-session-state "session-shell")]
            (is (number? (:shell-check state)))
            (is (string? (:shell-head state)))
            (is (= [] (:shell-pending state))))))
      (testing "a file edited and committed in one command is still seen"
        ;; A clean tree first, so the new session's check sees only the commit.
        (git! root "add" "-A")
        (git! root "commit" "-q" "-m" "earlier shell writes")
        (run-prompt-hook root "session-commit" "hello")
        (spit (io/file root "src/d.clj") "(ns d)\n")
        (git! root "add" "src/d.clj")
        (git! root "commit" "-q" "-m" "edited and committed in one command")
        (let [context (run-shell-hook root "session-commit")]
          (is (str/includes? context "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on src/d.clj, changed by a shell command]")))))))

(deftest shell-hook-off-by-default-test
  (with-temp-project
    {".cursor/rules/widget.mdc"
     "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (run-prompt-hook root "session-off" "hello")
      (spit (io/file root "src/a.clj") "(ns a) :edited\n")
      (is (nil? (run-shell-hook root "session-off"))
          "without :shell-hook {:codex true} in rule-fairy.edn a shell write injects nothing")
      (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/codex"))]
        (is (nil? (:shell-check (rule-common/load-session-state "session-off")))
            "and the prompt hook starts no check")))))

(deftest shell-change-pending-test
  (with-temp-project
    {"rule-fairy.edn" "{:shell-hook {:codex true}}"
     ".cursor/rules/lib.mdc"
     "---\nglobs: lib/**\nalwaysApply: false\n---\n# Lib rule\n\n@doc/lib.md\n"}
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (run-prompt-hook root "session-pending" "hello")
      (io/make-parents (io/file root "lib/x.clj"))
      (spit (io/file root "lib/x.clj") "(ns x)\n")
      (testing "a rule that cannot be built fails the hook and keeps the path pending"
        (let [{:keys [exit err]} (shell-hook-result root "session-pending")]
          (is (pos? exit))
          (is (str/includes? err "Rule import does not exist"))
          (is (= ["lib/x.clj"] (shell-pending root "session-pending")))))
      (testing "the next shell command delivers the pending path once the rule builds"
        (io/make-parents (io/file root "doc/lib.md"))
        (spit (io/file root "doc/lib.md") "# Lib guide\n")
        (let [context (run-shell-hook root "session-pending")]
          (is (str/includes? context "[rule-fairy injected: lib.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on lib/x.clj, changed by a shell command]"))
          (is (= [] (shell-pending root "session-pending"))))))))

(deftest prompt-and-edit-share-one-dedup-state-test
  (with-temp-project
    {".cursor/rules/both.mdc"
     "---\nalwaysApply: true\nglobs: src/**\n---\n# Both rule\n"
     "src/a.clj" ""
     "bb.edn" "{:paths [\"nonexistent\"] :deps {org.example/missing {:mvn/version \"1.0.0\"}}}"}
    (fn [root]
      (testing "a rule delivered after an edit is not delivered again by the next prompt"
        (is (str/includes? (run-post-tool-hook root "session-a" "src/a.clj")
                           "[rule-fairy injected: both.mdc]"))
        (is (nil? (run-prompt-hook root "session-a" "hello"))))
      (testing "a rule delivered by a prompt is not delivered again after an edit"
        (is (str/includes? (run-prompt-hook root "session-b" "hello")
                           "[rule-fairy injected: both.mdc]"))
        (is (nil? (run-post-tool-hook root "session-b" "src/a.clj")))))))

(deftest hooks-of-one-session-serialise-test
  (with-temp-project
    {".cursor/rules/widget.mdc"
     "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (with-redefs [rule-common/state-dir (str (io/file root ".rule-fairy/codex"))]
        (let [hook (rule-common/with-session-lock
                    "session-lock"
                    (fn []
                      (let [hook (future (run-post-tool-hook root "session-lock" "src/a.clj"))]
                        (testing "a hook started while another holds the session lock waits for it"
                          (is (= ::waiting (deref hook 500 ::waiting))))
                        ;; What a concurrent hook records while this one waits.
                        (rule-common/mark-injected! "session-lock"
                                                    (rule-common/load-session-state "session-lock")
                                                    ["widget.mdc"]
                                                    nil)
                        hook)))]
          (testing "once released, the hook decides from the state the lock holder left"
            (is (nil? (deref hook 10000 ::still-waiting)))))))))

(deftest moved-file-destination-test
  (with-temp-project
    {".cursor/rules/destination.mdc"
     "---\nglobs: new/**\nalwaysApply: false\n---\n# Destination rule\n"
     ".cursor/rules/origin.mdc"
     "---\nglobs: old/**\nalwaysApply: false\n---\n# Origin rule\n"
     ;; The moved file is gone; its directory remains, as in a real checkout.
     "old/other.clj" ""
     "new/a.clj" "(ns a)\n"}
    (fn [root]
      (testing "an apply_patch move selects the rules of both the source and the destination"
        (let [patch (str "*** Begin Patch\n"
                         "*** Update File: old/a.clj\n"
                         "*** Move to: new/a.clj\n"
                         "@@\n-(ns a)\n+(ns a)\n"
                         "*** End Patch\n")
              context (run-registered-hook :PostToolUse root {:session_id "session-move"
                                                              :tool_input {:command patch}})]
          (is (str/includes? context "[rule-fairy injected: destination.mdc]"))
          (is (str/includes? context "[rule-fairy injected: origin.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on old/a.clj, new/a.clj]"))
          (is (str/includes? context "# Destination rule")))))))

(deftest prompt-hook-always-apply-test
  (with-temp-project
    {".cursor/rules/base.mdc"
     "---\ndescription: Base\nalwaysApply: true\n---\n# Base rule\n\nAlways on.\n"
     ".cursor/rules/widget.mdc"
     "---\npromptAnyOf: widget\nalwaysApply: false\n---\n# Widget rule\n"}
    (fn [root]
      (testing "the first prompt injects the always-on rule under its own label"
        (let [context (run-prompt-hook root "session" "hello there")]
          (is (str/includes? context "[rule-fairy injected: base.mdc]"))
          (is (str/includes? context "[rule-fairy matched: alwaysApply base.mdc]"))
          (is (not (str/includes? context "[rule-fairy matched: keyword")))
          (is (str/includes? context "# Base rule"))
          (is (not (str/includes? context "# Widget rule")))))
      (testing "a later keyword match injects only the new rule"
        (let [context (run-prompt-hook root "session" "add a widget")]
          (is (str/includes? context "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? context "[rule-fairy matched: keyword \"widget\"]"))
          (is (not (str/includes? context "base.mdc")))))
      (testing "a repeated prompt injects nothing"
        (is (nil? (run-prompt-hook root "session" "hello there")))))))

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
  (with-temp-state
    (fn []
      (let [transcript (java.io.File/createTempFile "rule-fairy-codex-transcript" ".jsonl")
            input {:transcript_path (.getAbsolutePath transcript)}]
        (try
          (spit transcript
                (str "{\"type\":\"event_msg\",\"payload\":{}}\n"
                     "{\"type\":\"compacted\",\"payload\":{}}\n"))
          (testing "the first call scans the whole transcript and stores the cursor"
            (is (= {:size (.length transcript) :compaction-count 1}
                   (rule-common/transcript-metrics! "session" input)))
            (is (= {:scanned-bytes (.length transcript) :compaction-count 1}
                   (:transcript-scan (rule-common/load-session-state "session")))))
          (testing "a later call counts only the appended complete lines"
            (spit transcript
                  (str "{\"type\":\"compacted\",\"payload\":{}}\n"
                       "{\"type\":\"event_msg\"")
                  :append true)
            (is (= {:size (.length transcript) :compaction-count 2}
                   (rule-common/transcript-metrics! "session" input)))
            (is (< (get-in (rule-common/load-session-state "session")
                           [:transcript-scan :scanned-bytes])
                   (.length transcript))))
          (testing "marking rules injected keeps the scan cursor"
            (rule-common/mark-injected! "session"
                                       (rule-common/load-session-state "session")
                                       [rule-name]
                                       {:size 1 :compaction-count 2})
            (is (= 2 (get-in (rule-common/load-session-state "session")
                             [:transcript-scan :compaction-count]))))
          (testing "a missing or absent transcript yields no metrics"
            (is (nil? (rule-common/transcript-metrics! "session" {:transcript_path "/nonexistent/x.jsonl"})))
            (is (nil? (rule-common/transcript-metrics! "session" {}))))
          (finally
            (.delete transcript)))))))

(deftest state-directory-ignores-itself-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:sizes {}})
      (is (= "*\n" (slurp (io/file rule-common/state-dir ".gitignore")))))))

(deftest prepare-injection-test
  (testing "successful rendering is persisted after rendering"
    (let [events (atom [])]
      (with-redefs [rule-common/render-rules
                    (fn [_]
                      (swap! events conj :render)
                      "rendered")
                    rule-common/mark-injected!
                    (fn [& _]
                      (swap! events conj :mark))]
        (is (= "rendered"
               (rule-common/prepare-injection! "session" {} [rule-name] nil)))
        (is (= [:render :mark] @events)))))

  (testing "failed rendering is not persisted"
    (let [marked? (atom false)]
      (with-redefs [rule-common/render-rules
                    (fn [_]
                      (throw (ex-info "render failed" {})))
                    rule-common/mark-injected!
                    (fn [& _]
                      (reset! marked? true))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"render failed"
                              (rule-common/prepare-injection!
                               "session" {} [rule-name] nil)))
        (is (false? @marked?))))))

(deftest heading-import-test
  (with-temp-project
    {".cursor/rules/test.mdc" "# Rule\n\n@doc/guide.md#alpha"
     ".cursor/rules/other.mdc" "# Other rule\n\n@doc/guide.md#detail"
     "doc/guide.md" (str "# Guide\n\n"
                         "## Alpha\n\nalpha body\n\n"
                         "### Detail\n\ndetail body\n\n"
                         "## Beta\n\nbeta body\n")}
    (fn [root]
      (let [rendered (render-test-rule root ["test.mdc" "other.mdc"])]
        (testing "the selected heading includes its child headings"
          (is (str/includes? rendered "## Alpha"))
          (is (str/includes? rendered "### Detail")))
        (testing "the next sibling section is excluded"
          (is (not (str/includes? rendered "beta body"))))
        (testing "overlapping imports from active rules are deduplicated"
          (is (= 1 (count (re-seq #"detail body" rendered)))))))))

(deftest omission-test
  (with-temp-project
    {".cursor/rules/test.mdc" "# Rule\n\n@doc/guide.md"
     "doc/guide.md" (str "# Guide\n\nvisible\n\n"
                         "<!-- agent-context: omit -->\n"
                         "human-only detail\n"
                         "<!-- /agent-context -->\n\n"
                         "```markdown\n"
                         "<!-- agent-context: omit -->\n"
                         "example detail\n"
                         "<!-- /agent-context -->\n"
                         "```\n\nafter")}
    (fn [root]
      (let [rendered (render-test-rule root)]
        (is (str/includes? rendered "visible"))
        (is (not (str/includes? rendered "human-only detail")))
        (is (str/includes? rendered "example detail"))
        (is (str/includes? rendered "after"))))))

(deftest fenced-import-test
  (with-temp-project
    {".cursor/rules/test.mdc" "# Rule\n\n```markdown\n@doc/missing.md#missing\n```"}
    (fn [root]
      (is (str/includes? (render-test-rule root)
                         "@doc/missing.md#missing")))))

(deftest invalid-import-test
  (testing "a missing heading fails"
    (with-temp-project
      {".cursor/rules/test.mdc" "# Rule\n\n@doc/guide.md#missing"
       "doc/guide.md" "# Guide\n\n## Present\n"}
      (fn [root]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"heading does not exist"
                              (render-test-rule root))))))

  (testing "an unclosed omission fails"
    (with-temp-project
      {".cursor/rules/test.mdc" "# Rule\n\n@doc/guide.md"
       "doc/guide.md" "# Guide\n\n<!-- agent-context: omit -->\nhidden"}
      (fn [root]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unclosed agent-context omission"
                              (render-test-rule root))))))

  (testing "nested omissions fail"
    (with-temp-project
      {".cursor/rules/test.mdc" "# Rule\n\n@doc/guide.md"
       "doc/guide.md" (str "# Guide\n\n"
                           "<!-- agent-context: omit -->\n"
                           "<!-- agent-context: omit -->\n"
                           "<!-- /agent-context -->\n"
                           "<!-- /agent-context -->")}
      (fn [root]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Nested agent-context omission"
                              (render-test-rule root))))))

  (testing "an import cannot escape the project root"
    (with-temp-project
      {".cursor/rules/test.mdc" "# Rule\n\n@../outside.md"}
      (fn [root]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"escapes the project root"
                              (render-test-rule root)))))))

(deftest expanded-context-budget-test
  (with-temp-project
    {".cursor/rules/test.mdc" "# Rule\n\n@doc/guide.md"
     "doc/guide.md" (apply str (repeat (inc rules/max-expanded-context-bytes) "x"))}
    (fn [root]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Expanded rule documentation exceeds"
                            (render-test-rule root))))))

(deftest edited-paths-render-matching-rules-test
  (with-temp-project
    {".cursor/rules/ui.mdc"
     (str "---\nglobs: apps/**/ui/**/*.clj\nalwaysApply: false\n---\n"
          "# UI\n\n@doc/ui.md#components\n")
     ".cursor/rules/testing.mdc"
     "---\nglobs: **/test/**/*.clj\nalwaysApply: false\n---\n# Testing\n"
     ".cursor/rules/db.mdc"
     "---\nglobs: db/**\nalwaysApply: false\n---\n# Database\n"
     "doc/ui.md"
     "# UI guide\n\n## Components\n\nreuse components\n\n## History\n\nnot imported\n"}
    (fn [root]
      (with-redefs [rule-common/rules-source (rules/project-rules-source root)]
        (let [matched (matching-rule-names-for-paths representative-edited-paths)
              rendered (rule-common/render-rules matched)]
          (is (= ["testing.mdc" "ui.mdc"] matched))
          (is (str/includes? rendered "# ui.mdc\n\n# UI"))
          (is (str/includes? rendered "# testing.mdc\n\n# Testing"))
          (is (not (str/includes? rendered "# Database")))
          (is (str/includes? rendered "## Required context: `doc/ui.md#components`"))
          (is (str/includes? rendered "reuse components"))
          (is (not (str/includes? rendered "not imported"))))))))

(let [{:keys [fail error]} (run-tests 'rule-common-test)]
  (System/exit (+ fail error)))

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

;; The registration test validates a hooks manifest, by default the plugin's own.
(def settings-file
  (or (System/getenv "RULE_FAIRY_SETTINGS_FILE")
      (str (io/file repository-directory "hooks/hooks.json"))))

(load-file (str (io/file hooks-directory "common.bb")))

(def rule-name "testing.mdc")

(defn test-selection []
  {:matching-rules [rule-name]
   :prefix-fn (constantly "prefix")})

(defn delivery
  "deliver! for the session: the selection's rules, with in-context-frames
  the frames the conversation holds, none unless given, at now-ms, the
  current time unless given."
  ([event-id selection-fn]
   (delivery event-id selection-fn []))
  ([event-id selection-fn in-context-frames]
   (delivery event-id selection-fn in-context-frames nil))
  ([event-id selection-fn in-context-frames now-ms]
   (rule-common/deliver! (cond-> {:session-id "session"
                                  :event-id event-id
                                  :selection-fn selection-fn
                                  :in-context-frames in-context-frames}
                           now-ms (assoc :now-ms now-ms)))))

(defn delivered-rules
  "The rules the session state records as delivered."
  []
  (set (keys (:delivered (rule-common/load-session-state "session")))))

(defn in-context
  "Frames of whole single-shard deliveries of the named rules, as the mod
  reads them back from the conversation."
  [& rule-names]
  (mapv #(str "[rule-fairy shard 1/1 " % "]\n[rule-fairy injected: " % "]\n\n# body") rule-names))

(defn naming-selection
  "A selection of the named rules whose first frame opens by naming the
  delivered ones as injected."
  [rule-names]
  (fn []
    {:matching-rules rule-names
     :prefix-fn (fn [names]
                  (str/join "\n" (map #(str "[rule-fairy injected: " % "]") names)))}))

(defn shell-selection
  "A selection whose delivery clears the pending shell paths."
  [paths matching-rules]
  (fn []
    {:matching-rules matching-rules
     :prefix-fn (constantly "prefix")
     :paths paths}))

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
        ;; A frame limit small enough to split four 9 KB rules, so the
        ;; packer meets real headings, paragraphs and fences.
        (let [frames (with-redefs [rule-common/max-frame-chars 9000]
                       (rule-common/injection-frames rule-names "[rule-fairy matched: test]" "delivery"))
              joined (str/join "\n" frames)]
          (is (< 1 (count frames)))
          (is (every? #(<= (count %) 9000) frames))
          (is (every? #(str/starts-with? % "[rule-fairy shard") frames))
          (is (= 1 (count (filter #(str/includes? % "[rule-fairy matched: test]") frames))))
          (doseq [rule-name rule-stems]
            (testing rule-name
              (is (str/includes? joined (rule-paragraph rule-name 29)))
              (is (str/includes? joined (str "(defn " rule-name "-example []")))))
          (testing "a section imported by two rules is delivered once"
            (is (= 1 (count (re-seq #"shared guidance" joined))))
            (is (not (str/includes? joined "not imported")))))))))

(deftest partial-delivery-with-real-frames-reads-back-as-what-it-delivered-test
  (let [rule-stems ["alpha" "beta" "gamma"]
        rule-names (mapv #(str % ".mdc") rule-stems)]
    (with-temp-rules
      (large-rule-files rule-stems 40)
      (fn [_]
        (with-temp-state
          (fn []
            (with-redefs [rule-common/max-frame-chars 9000]
              (let [{frames :context} (delivery "event" (naming-selection rule-names))
                    [header deferred-line injected-line] (str/split-lines (first frames))]
                (testing "three 12 KB rules under a 9,000-character frame limit: the first rule in two frames, the other two deferred"
                  (is (= 2 (count frames)))
                  (is (= "[rule-fairy shard 1/2 event]" header)
                      "the header carries the delivery id, the leading part of the event id")
                  (is (= "[rule-fairy deferred: beta.mdc, gamma.mdc (past what one delivery can carry; delivered with the next matching event)]"
                         deferred-line))
                  (is (= "[rule-fairy injected: alpha.mdc]" injected-line))
                  (is (str/starts-with? (second frames) "[rule-fairy shard 2/2 event]\n")))
                (testing "only the delivered rule is recorded"
                  (is (= #{"alpha.mdc"} (delivered-rules))))
                (testing "the conversation reader returns the delivered rule from the real frames, and nothing from a delivery cut short"
                  (is (= ["alpha.mdc"] (rule-fairy.transcript/frames-injections frames)))
                  (is (= [] (rule-fairy.transcript/frames-injections [(first frames)]))
                      "a delivery missing its second frame is not counted"))))))))))

(deftest documentation-budget-is-part-of-what-fits-test
  (let [paragraphs (fn [heading n]
                     (str/join "\n\n" (map #(str heading " paragraph " % ": " (apply str (repeat 960 "d"))) (range n))))
        section (fn [heading] (str "## " heading "\n\n" (paragraphs heading 20)))
        rule (fn [heading] (str "---\nglobs: apps/**/*.clj\nalwaysApply: false\n---\n# " heading "\n\n@doc/guide.md#" heading "\n"))
        deliveries (fn [matching-rules]
                     (:context (delivery (str/join "-" matching-rules) (naming-selection matching-rules))))]
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
              (let [[first-frame :as delivered] (deliveries ["a.mdc" "b.mdc" "c.mdc" "d.mdc"])]
                (is (<= 1 (count delivered) rule-common/max-frames-per-delivery))
                (is (str/includes? first-frame "[rule-fairy deferred: d.mdc (past what one delivery can carry; delivered with the next matching event)]"))
                (is (str/includes? first-frame "[rule-fairy injected: c.mdc]"))
                (is (not (str/includes? first-frame "[rule-fairy injected: d.mdc]")))
                (is (= #{"a.mdc" "b.mdc" "c.mdc"} (delivered-rules)))))
            (testing "a single rule whose documentation alone passes the budget is reported, not marked"
              (let [[frame :as delivered] (deliveries ["e.mdc"])]
                (is (= 1 (count delivered)))
                (is (str/includes? frame "[rule-fairy error: e.mdc alone expands to "))
                (is (str/includes? frame " bytes of documentation, over the 65536-byte budget]"))
                (is (str/includes? frame "No matched rules were injected"))
                (is (not (contains? (delivered-rules) "e.mdc")))))))))))

(defn mod-result
  "Runs the mod's entry script the way hooks/claude/register.ts does: through
  the launcher with the plugin root and project directory exported, from the
  given working directory, {kind input} on stdin. {:exit :out :err}, whatever
  the exit; env adds environment variables."
  ([kind project-root cwd input]
   (mod-result kind project-root cwd input {}))
  ([kind project-root cwd input env]
   (p/shell {:dir (str cwd)
             :in (json/generate-string {:kind kind :input input})
             :out :string
             :err :string
             :continue true
             :extra-env (merge {"CLAUDE_PLUGIN_ROOT" (str repository-directory)
                                "CLAUDE_PROJECT_DIR" (str project-root)}
                               env)}
            (str (io/file repository-directory "hooks/run")) "claude/mod.bb")))

(defn mod-context
  "The context entries the mod's entry script prints for the event, joined
  into one string, or nil for none; a non-zero exit fails the test."
  [kind project-root cwd input env]
  (let [{:keys [exit out err]} (mod-result kind project-root cwd input env)]
    (is (zero? exit) (str "mod entry exited " exit ": " err))
    (let [{:keys [context]} (json/parse-string out true)]
      (when (seq context)
        (str/join "\n" context)))))

(defn run-prompt-hook
  "The prompt delivery, run from the project root: its context or nil."
  ([root session-id prompt]
   (run-prompt-hook root session-id prompt {}))
  ([root session-id prompt env]
   (mod-context "prompt" root root {:prompt prompt :session_id session-id} env)))

(defn run-edit-hook
  "The edit delivery, run from an unrelated working directory to show the
  mod depends on CLAUDE_PROJECT_DIR and not on the cwd: its context or nil.
  A bare file path is a file_path under root; otherwise the tool input and
  any extra input fields are given as maps."
  ([root session-id file-path]
   (run-edit-hook root session-id {:file_path (str (io/file root file-path))} {}))
  ([root session-id tool-input extra-input]
   (mod-context "edit" root (System/getProperty "java.io.tmpdir")
                (merge {:session_id session-id :tool_input tool-input} extra-input)
                {})))

(deftest subagent-and-mcp-edit-test
  (with-temp-project
    {".cursor/rules/widget.mdc" "---\nglobs: src/**\nalwaysApply: false\n---\n# Widget rule\n"
     "src/a.clj" ""}
    (fn [root]
      (let [edit #(run-edit-hook root %1 %2 %3)
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

(defn shell-input
  "The input of a shell delivery after a Bash call that succeeded
  (:PostToolUse) or failed (:PostToolUseFailure), each call with its own
  tool_use_id, as Claude Code's have, so each is its own event."
  [event session-id]
  {:session_id session-id
   :hook_event_name (name event)
   :tool_name "Bash"
   :tool_use_id (str (random-uuid))
   :tool_input {:command "printf x > somewhere"}})

(defn shell-hook-result
  "The shell delivery for the event, run from the project root: {:exit :out
  :err}, whatever the exit."
  [event root session-id]
  (mod-result "shell" root root (shell-input event session-id)))

(defn run-shell-hook
  "The shell delivery's context for the event, or nil; a non-zero exit fails
  the test."
  [event root session-id]
  (mod-context "shell" root root (shell-input event session-id) {}))

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
      (testing "the edit delivery finds the project from CLAUDE_PROJECT_DIR, not the cwd"
        (let [context (run-edit-hook root "session-edit" "src/a.clj")]
          (is (str/includes? context "[rule-fairy injected: widget.mdc]"))
          (is (str/includes? context "[rule-fairy matched: glob on src/a.clj]"))))
      (testing "state and caches land in the project's own state directory"
        (is (.isFile (io/file root ".rule-fairy/claude/.gitignore")))
        (is (.isFile (io/file root ".rule-fairy/claude/globs-cache.edn")))
        (is (.isFile (io/file root ".rule-fairy/claude/session.edn")))))))

(deftest state-directory-ignores-itself-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {})
      (is (= "*\n" (slurp (io/file rule-common/state-dir ".gitignore")))))))

(deftest mod-registration-test
  (let [manifest (json/parse-string (slurp settings-file) true)]
    (is (= ["./claude/register.ts"] (:modules manifest))
        "the manifest names the mod's hooks module")
    (is (nil? (:hooks manifest))
        "no settings hooks remain beside it: the mod delivers every Claude Code event")
    (is (.isFile (io/file repository-directory "hooks/claude/register.ts")))))

(defn one-frame-per-rule
  "An injection-frames stand-in needing one frame per rule, the first frame
  carrying the prefix and the delivered rule names."
  [rule-names prefix _delivery-id]
  (into [(str prefix "|" (str/join "," rule-names))]
        (repeat (dec (count rule-names)) "more")))

(deftest partial-delivery-marks-only-the-rules-that-fit-test
  (with-temp-state
    (fn []
      (with-redefs [rule-common/max-frames-per-delivery 1
                    rule-common/injection-frames one-frame-per-rule]
        (let [selection (constantly {:matching-rules ["a.mdc" "b.mdc"]
                                     :prefix-fn (fn [names] (str "injected " (str/join "," names)))})
              {[frame] :context} (delivery "first" selection)]
          (is (= (str "[rule-fairy deferred: b.mdc (past what one delivery can carry; delivered with the next matching event)]\n"
                      "injected a.mdc"
                      "|a.mdc")
                 frame)
              "the entry carries the rules that fit and opens by naming the deferred one")
          (is (= #{"a.mdc"} (delivered-rules))
              "only the delivered rule is recorded")
          (is (= {:context ["injected b.mdc|b.mdc"]} (delivery "second" selection))
              "the next matching event delivers the rest, with nothing left to defer")
          (is (= #{"a.mdc" "b.mdc"} (delivered-rules))))))))

(deftest oversized-rule-is-reported-and-not-marked-test
  (with-temp-state
    (fn []
      (with-redefs [rule-common/max-frames-per-delivery 1
                    rule-common/injection-frames (constantly ["one" "two"])]
        (let [{[frame :as context] :context} (delivery "event" test-selection)]
          (is (= 1 (count context)))
          (is (str/includes? frame (str "[rule-fairy error: " rule-name " alone needs 2 context entries, but one delivery carries 1]")))
          (is (str/includes? frame "No matched rules were injected"))
          (is (= #{} (delivered-rules)))
          (is (= [frame] (:context (delivery "next" test-selection)))
              "reported again on the next event, until the rule is fixed"))))))

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
  (testing "twenty 512-character paths: one named, the rest counted, prefix near its budget"
    (let [segment (apply str (repeat 70 "a"))
          paths (mapv #(str "src/" (str/join "/" (repeat 7 segment)) "/file-" % ".clj") (range 20))
          prefix (rule-common/shell-prefix ["a.mdc"] paths)]
      (is (= 512 (apply max (map count paths))))
      (is (str/includes? prefix "/file-0.clj and 19 more, changed by a shell command]"))
      (is (< (count prefix) 2000))))
  (testing "a single path longer than the budget is counted, not named"
    (let [prefix (rule-common/shell-prefix ["a.mdc"] [(apply str (repeat 1200 "p"))])]
      (is (str/includes? prefix "[rule-fairy matched: glob on 1 paths, changed by a shell command]"))))
  (testing "hundreds of paths, as after a rebase, keep the prefix near its budget"
    (let [many (mapv #(str "apps/home/src/scarlet/home/ui/some/deeply/nested/namespace/file_" % "_controller.clj")
                     (range 300))
          prefix (rule-common/shell-prefix (mapv #(str "rule-" % ".mdc") (range 14)) many)]
      (is (re-find #" and 2\d\d more, changed by a shell command\]" prefix))
      (is (< (count prefix) 2000)))))

(deftest shell-paths-clear-with-their-delivery-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:shell-pending ["src/x.clj" "src/y.clj"]})
      (with-redefs [rule-common/injection-frames (constantly ["one" "two"])]
        (is (= {:context ["one" "two"]} (delivery "event" (shell-selection ["src/x.clj"] [rule-name]))))
        (is (= ["src/y.clj"] (shell-pending-state))
            "the delivery clears the paths it delivered for, and no others")
        (is (= #{rule-name} (delivered-rules)))))))

(deftest shell-paths-with-nothing-to-deliver-clear-at-once-test
  (with-temp-state
    (fn []
      (testing "no rule matches the paths"
        (rule-common/save-session-state! "session" {:shell-pending ["src/x.clj"]})
        (is (= {:context []} (delivery "event" (shell-selection ["src/x.clj"] []))))
        (is (= [] (shell-pending-state))))
      (testing "every matching rule is in the conversation already"
        (rule-common/save-session-state! "session" {:shell-pending ["src/x.clj"]})
        (is (= {:context []}
               (delivery "later" (shell-selection ["src/x.clj"] [rule-name]) (in-context rule-name))))
        (is (= [] (shell-pending-state)))))))

(deftest shell-paths-stay-pending-across-a-partial-delivery-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:shell-pending ["src/x.clj"]})
      (with-redefs [rule-common/max-frames-per-delivery 1
                    rule-common/injection-frames one-frame-per-rule]
        (let [selection (shell-selection ["src/x.clj"] ["a.mdc" "b.mdc"])]
          (delivery "first" selection)
          (is (= ["src/x.clj"] (shell-pending-state))
              "a delivery that defers a rule keeps the path for the next shell command")
          (is (= #{"a.mdc"} (delivered-rules)))
          (delivery "second" selection)
          (is (= [] (shell-pending-state))
              "the path clears once the last deferred rule has been delivered")
          (is (= #{"a.mdc" "b.mdc"} (delivered-rules))))))))

(deftest shell-paths-stay-pending-for-an-oversized-rule-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:shell-pending ["src/x.clj"]})
      (with-redefs [rule-common/max-frames-per-delivery 1
                    rule-common/injection-frames (constantly ["one" "two"])]
        (let [{[frame] :context} (delivery "event" (shell-selection ["src/x.clj"] [rule-name]))]
          (is (str/includes? frame "No matched rules were injected"))
          (is (= ["src/x.clj"] (shell-pending-state))
              "a rule that cannot fit one delivery delivers nothing, so the path waits and the next command reports it again")
          (is (= #{} (delivered-rules))))))))

(deftest failed-render-marks-nothing-test
  (with-temp-state
    (fn []
      (rule-common/save-session-state! "session" {:shell-pending ["src/x.clj"]})
      (with-redefs [rule-common/injection-frames (fn [& _] (throw (ex-info "render failed" {})))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"render failed"
                              (delivery "event" (shell-selection ["src/x.clj"] [rule-name]))))
        (is (= #{} (delivered-rules))
            "nothing is recorded")
        (is (= ["src/x.clj"] (shell-pending-state))
            "the path stays pending for the next command")))))

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

(deftest delivery-reads-the-conversation-test
  (with-temp-rules
    {".cursor/rules/a.mdc" "---\nglobs: src/**\nalwaysApply: false\n---\n# A rule\n"
     ".cursor/rules/b.mdc" "---\nglobs: src/**\nalwaysApply: false\n---\n# B rule\n"}
    (fn [_]
      (with-temp-state
        (fn []
          (let [selection (naming-selection ["a.mdc" "b.mdc"])
                injected-lines (fn [{:keys [context]}]
                                 (filter #(str/starts-with? % "[rule-fairy injected: ")
                                         (mapcat str/split-lines context)))
                t0 1000000]
            (testing "a rule whose whole delivery the conversation holds, as after a fork, is not delivered; one it lacks is"
              (let [{[entry] :context :as delivered} (delivery "first" selection (in-context "a.mdc") t0)]
                (is (= ["[rule-fairy injected: b.mdc]"] (injected-lines delivered)))
                (is (str/includes? entry "# B rule"))
                (is (not (str/includes? entry "# A rule")))
                (is (= {"b.mdc" t0} (:delivered (rule-common/load-session-state "session"))))))
            (testing "within the window a delivered rule is not delivered again, even while the conversation does not show it yet"
              (is (= {:context []} (delivery "second" selection (in-context "a.mdc") (+ t0 59000)))))
            (testing "past the window, with the conversation emptied as after a compaction, both are delivered again"
              (is (= ["[rule-fairy injected: a.mdc]" "[rule-fairy injected: b.mdc]"]
                     (injected-lines (delivery "third" selection [] (+ t0 61000)))))
              (is (= {"a.mdc" (+ t0 61000) "b.mdc" (+ t0 61000)}
                     (:delivered (rule-common/load-session-state "session")))))))))))

(deftest large-rules-arrive-as-one-entry-test
  (with-temp-rules
    (large-rule-files ["x" "y" "z"] 40)
    (fn [_]
      (with-temp-state
        (fn []
          (testing "three 12 KB rules arrive whole as one entry, headed and named, and are recorded"
            (let [{:keys [context]} (delivery "large" (naming-selection ["x.mdc" "y.mdc" "z.mdc"]))
                  entry (first context)]
              (is (= 1 (count context)))
              (is (> (count entry) 30000))
              (is (str/starts-with? entry "[rule-fairy shard 1/1 large]\n[rule-fairy injected: x.mdc]\n[rule-fairy injected: y.mdc]\n[rule-fairy injected: z.mdc]\n\n"))
              (doseq [rule-stem ["x" "y" "z"]]
                (is (str/includes? entry (rule-paragraph rule-stem 39))))
              (is (= #{"x.mdc" "y.mdc" "z.mdc"} (delivered-rules))))))))))

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

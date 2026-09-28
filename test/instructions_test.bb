#!/usr/bin/env bb

(ns instructions-test
  (:require [rule-fairy.instructions :as instructions]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn with-temp-project [files f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "instructions-test"
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

(defn symlink! [root link target]
  (let [link-file (io/file root link)]
    (io/make-parents link-file)
    (java.nio.file.Files/createSymbolicLink (.toPath link-file)
                                            (.toPath (io/file root target))
                                            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn document-paths [documents]
  (mapv :path documents))

(deftest instruction-directories-test
  (is (= [""] (instructions/instruction-directories [])))
  (is (= ["" "apps" "apps/web" "apps/web/src"]
         (instructions/instruction-directories ["apps/web/src/x.clj" "README.md"])))
  (testing "directories are ordered by depth then name, and paths are normalised"
    (is (= ["" "a" "b" "b/c"]
           (instructions/instruction-directories ["b/c/x.clj" "./a/y.clj" "/b/z.clj"])))))

(deftest default-discovery-test
  (with-temp-project
    {"AGENTS.md" "# Agents\n\nShared."
     "CLAUDE.md" "# Claude\n\n@AGENTS.md\n"
     "CLAUDE.local.md" "local"
     ".claude/CLAUDE.md" "dot claude"
     "apps/CLAUDE.md" "apps"
     "apps/web/CLAUDE.md" "web"
     "apps/web/AGENTS.md" "web agents"
     "apps/api/CLAUDE.md" "api, untouched"}
    (fn [root]
      (let [source (instructions/project-instructions-source root)
            documents (instructions/instruction-documents source ["apps/web/src/x.clj" "README.md"])]
        (testing "without configuration every default name is looked up"
          (is (= {:project-root (str root) :files instructions/default-instruction-files}
                 source)))
        (testing "root files come first in default order, then touched directories by depth"
          (is (= ["AGENTS.md" "CLAUDE.md" ".claude/CLAUDE.md" "CLAUDE.local.md"
                  "apps/CLAUDE.md" "apps/web/AGENTS.md" "apps/web/CLAUDE.md"]
                 (document-paths documents))))
        (testing "an import of a file already included is not a second document"
          (is (every? nil? (map :imported-by documents)))
          (is (= "# Claude\n\n@AGENTS.md" (:content (second documents)))))
        (testing "with no changed paths only the root applies"
          (is (= ["AGENTS.md" "CLAUDE.md" ".claude/CLAUDE.md" "CLAUDE.local.md"]
                 (document-paths (instructions/instruction-documents source [])))))))))

(defn discovered-paths [root changed-paths]
  (document-paths (instructions/instruction-documents
                   (instructions/project-instructions-source root) changed-paths)))

(deftest override-precedence-test
  (testing "AGENTS.md beside an override is left out once a CLAUDE file at the root makes Claude Code read CLAUDE files"
    (with-temp-project
      {"AGENTS.override.md" "override" "AGENTS.md" "agents" "CLAUDE.md" "claude"
       "apps/AGENTS.override.md" "apps override" "apps/AGENTS.md" "apps agents"}
      (fn [root]
        (is (= ["AGENTS.override.md" "CLAUDE.md" "apps/AGENTS.override.md"]
               (discovered-paths root ["apps/x.clj"]))))))
  (testing "without a CLAUDE file at the root Claude Code reads AGENTS.md, so both stay; a subdirectory's own CLAUDE file drops its AGENTS.md"
    (with-temp-project
      {"AGENTS.override.md" "override" "AGENTS.md" "agents"
       "apps/AGENTS.override.md" "apps override" "apps/AGENTS.md" "apps agents" "apps/CLAUDE.md" "apps claude"}
      (fn [root]
        (is (= ["AGENTS.override.md" "AGENTS.md" "apps/AGENTS.override.md" "apps/CLAUDE.md"]
               (discovered-paths root ["apps/x.clj"]))))))
  (testing "an AGENTS.md without an override beside it always stays"
    (with-temp-project
      {"AGENTS.md" "agents" "CLAUDE.md" "claude"}
      (fn [root]
        (is (= ["AGENTS.md" "CLAUDE.md"] (discovered-paths root []))))))
  (testing "a configured list that leaves out the override takes AGENTS.md as written"
    (with-temp-project
      {"rule-fairy.edn" "{:instructions {:files [\"AGENTS.md\" \"CLAUDE.md\"]}}"
       "AGENTS.override.md" "override" "AGENTS.md" "agents" "CLAUDE.md" "claude"}
      (fn [root]
        (is (= ["AGENTS.md" "CLAUDE.md"] (discovered-paths root [])))))))

(deftest configured-files-test
  (testing "an explicit list restricts and orders the files"
    (with-temp-project
      {"rule-fairy.edn" "{:instructions {:files [\"docs/GUIDE.md\" \"CLAUDE.md\"]}}"
       "AGENTS.md" "agents"
       "CLAUDE.md" "claude"
       "docs/GUIDE.md" "guide"}
      (fn [root]
        (let [source (instructions/project-instructions-source root)]
          (is (= ["docs/GUIDE.md" "CLAUDE.md"] (:files source)))
          (is (= ["docs/GUIDE.md" "CLAUDE.md"]
                 (document-paths (instructions/instruction-documents source ["x.clj"]))))))))

  (testing "an empty list includes no instruction files"
    (with-temp-project
      {"rule-fairy.edn" "{:instructions {:files []}}"
       "AGENTS.md" "agents"}
      (fn [root]
        (is (= [] (instructions/instruction-documents
                   (instructions/project-instructions-source root) ["x.clj"]))))))

  (testing "invalid configuration fails with the file named"
    (doseq [[config message] [["{:instructions [:files]}" #":instructions in rule-fairy.edn must be a map"]
                              ["{:instructions {:file [\"x\"]}}" #"Unknown :instructions keys in rule-fairy.edn: :file"]
                              ["{:instructions {:files \"CLAUDE.md\"}}" #":instructions :files in rule-fairy.edn"]
                              ["{:instructions {:files [\"\"]}}" #":instructions :files in rule-fairy.edn"]
                              ["{:instruction {}}" #"Unknown keys in rule-fairy.edn: :instruction"]]]
      (with-temp-project
        {"rule-fairy.edn" config}
        (fn [root]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                                (instructions/project-instructions-source root))
              config))))))

(deftest identity-dedup-test
  (with-temp-project
    {"AGENTS.md" "# Agents\n\nShared."
     "CLAUDE.local.md" "Personal notes, then @AGENTS.md again."
     "api/x.clj" ""}
    (fn [root]
      (symlink! root "CLAUDE.md" "AGENTS.md")
      (symlink! root "api/AGENTS.md" "AGENTS.md")
      (let [documents (instructions/instruction-documents
                       (instructions/project-instructions-source root) ["api/x.clj"])]
        (testing "a symlinked file and an import of an included file appear once"
          (is (= ["AGENTS.md" "CLAUDE.local.md"] (document-paths documents))))
        (testing "the other paths the kept file was reached under are recorded on it"
          (is (= ["CLAUDE.md" "api/AGENTS.md"] (:also-at (first documents))))
          (is (nil? (:also-at (second documents)))))))))

(deftest parent-step-after-link-test
  (with-temp-project
    {"CLAUDE.md" "Root.\n\n@linked/../guide.md\n@guide.md\n"
     "docs/guide.md" "Nested guide."
     "docs/nested/x.md" ""
     "guide.md" "Root guide."}
    (fn [root]
      (symlink! root "linked" "docs/nested")
      (testing "a `..` after a link steps out of the link's target, as the filesystem does, so the two guides stay distinct"
        (is (= ["Root.\n\n@linked/../guide.md\n@guide.md" "Nested guide." "Root guide."]
               (mapv :content (instructions/instruction-documents
                               (instructions/project-instructions-source root) ["x.clj"]))))))))

(deftest dot-claude-agents-test
  (testing "with a CLAUDE file at the root, .claude/AGENTS.md is inactive for both harnesses"
    (with-temp-project
      {"CLAUDE.md" "claude" ".claude/AGENTS.md" "dot agents"}
      (fn [root]
        (is (= ["CLAUDE.md"] (discovered-paths root []))))))
  (testing "without one it is read at the root, never in a subdirectory"
    (with-temp-project
      {"AGENTS.md" "agents" ".claude/AGENTS.md" "dot agents"
       "apps/AGENTS.md" "apps agents" "apps/.claude/AGENTS.md" "apps dot agents"}
      (fn [root]
        (is (= ["AGENTS.md" ".claude/AGENTS.md" "apps/AGENTS.md"]
               (discovered-paths root ["apps/x.clj"]))))))
  (testing "a configured list naming it is taken as written"
    (with-temp-project
      {"rule-fairy.edn" "{:instructions {:files [\".claude/AGENTS.md\"]}}"
       "CLAUDE.md" "claude" ".claude/AGENTS.md" "dot agents"}
      (fn [root]
        (is (= [".claude/AGENTS.md"] (discovered-paths root [])))))))

(def claude-content
  (str "# Project\n"
       "\n"
       "See @docs/guide.md. Also @docs/missing.md and @./docs/style.md, but not\n"
       "`@docs/ignored.md` in a span, someone@example.com, or @claude.\n"
       "\n"
       "```text\n"
       "@docs/fenced.md\n"
       "```\n"))

(deftest import-references-test
  (is (= ["docs/guide.md" "docs/missing.md" "./docs/style.md" "claude"]
         (instructions/import-references claude-content)))
  (is (= ["~/.claude/shared.md" "/abs/file.md"]
         (instructions/import-references "Read @~/.claude/shared.md and @/abs/file.md!"))))

(deftest imports-test
  (with-temp-project
    {"CLAUDE.md" claude-content
     "docs/guide.md" "# Guide\n\nRefers to @deeper/a.md relative to this file."
     "docs/style.md" "style"
     "docs/ignored.md" "ignored"
     "docs/fenced.md" "fenced"
     "docs/deeper/a.md" "a @b.md"
     "docs/deeper/b.md" "b @c.md"
     "docs/deeper/c.md" "c @d.md"
     "docs/deeper/d.md" "d @e.md"
     "docs/deeper/e.md" "e"}
    (fn [root]
      (let [documents (instructions/instruction-documents
                       (instructions/project-instructions-source root) [])]
        (testing "imports follow the importing file depth first, up to four hops"
          (is (= ["CLAUDE.md" "docs/guide.md" "docs/deeper/a.md" "docs/deeper/b.md"
                  "docs/deeper/c.md" "docs/style.md"]
                 (document-paths documents))))
        (testing "each import records the file that pulled it in"
          (is (= [nil "CLAUDE.md" "docs/guide.md" "docs/deeper/a.md" "docs/deeper/b.md" "CLAUDE.md"]
                 (mapv :imported-by documents))))
        (testing "missing files, code spans and fenced blocks import nothing"
          (is (not-any? #{"docs/ignored.md" "docs/fenced.md" "docs/missing.md"}
                        (document-paths documents))))))))

(deftest home-import-test
  (let [home (System/getProperty "user.home")]
    (with-temp-project
      {"CLAUDE.md" "@~/.claude/shared.md"
       "fake-home/.claude/shared.md" "shared"}
      (fn [root]
        (try
          (System/setProperty "user.home" (str (io/file root "fake-home")))
          (let [documents (instructions/instruction-documents
                           (instructions/project-instructions-source root) [])]
            (testing "a home import resolves and is shown relative to the root when inside it"
              (is (= ["CLAUDE.md" "fake-home/.claude/shared.md"] (document-paths documents)))
              (is (= "shared" (:content (second documents))))))
          (finally
            (System/setProperty "user.home" home)))))))

(deftest outside-root-display-test
  (with-temp-project
    {"project/CLAUDE.md" "@../shared/team.md"
     "shared/team.md" "team"}
    (fn [root]
      (let [documents (instructions/instruction-documents
                       (instructions/project-instructions-source (io/file root "project")) [])]
        (testing "a file outside the project root is shown with its absolute path"
          (is (= 2 (count documents)))
          (is (= (str (.normalize (.toAbsolutePath (.toPath (io/file root "shared/team.md")))))
                 (:path (second documents)))))))))

(deftest strip-block-comments-test
  (is (= (str "# Title\n"
              "Text <!-- inline --> more.\n"
              "<!-- not block --> trailing\n"
              "```md\n"
              "<!-- kept in fence -->\n"
              "```\n"
              "<!-- unclosed")
         (instructions/strip-block-comments
          (str "# Title\n"
               "<!-- one line -->\n"
               "Text <!-- inline --> more.\n"
               "  <!--\n"
               "multi\n"
               "line\n"
               "-->\n"
               "<!-- not block --> trailing\n"
               "```md\n"
               "<!-- kept in fence -->\n"
               "```\n"
               "<!-- unclosed"))))
  (testing "line endings are normalised to LF"
    (is (= "a\nb" (instructions/strip-block-comments "a\r\nb")))))

(deftest document-content-test
  (with-temp-project
    {"AGENTS.md" "\r\n<!-- maintainers: keep short -->\r\n# Agents\r\n\r\nBody.\r\n"}
    (fn [root]
      (is (= "# Agents\n\nBody."
             (:content (first (instructions/instruction-documents
                               (instructions/project-instructions-source root) []))))))))

(let [{:keys [fail error]} (run-tests 'instructions-test)]
  (System/exit (+ fail error)))

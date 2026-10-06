#!/usr/bin/env bb

(ns rules-test
  (:require [rule-fairy.rules :as rules]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

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

(def rule-content
  (str "---\n"
       "description: UI rules: components and styling\n"
       "globs: apps/**/ui/**/*.clj, **/*.cljs\n"
       "alwaysApply: false\n"
       "promptAnyOf: Widget, HTMX\n"
       "promptRequires: UI\n"
       "custom: kept\n"
       "---\n"
       "\n# UI\n\nBody.\n"))

(deftest parse-rule-test
  (testing "frontmatter fields are keyed by name with trimmed string values"
    (let [{:keys [frontmatter body]} (rules/parse-rule rule-content)]
      (is (= {:description "UI rules: components and styling"
              :globs "apps/**/ui/**/*.clj, **/*.cljs"
              :alwaysApply "false"
              :promptAnyOf "Widget, HTMX"
              :promptRequires "UI"
              :custom "kept"}
             frontmatter))
      (is (= "# UI\n\nBody." body))))

  (testing "CRLF line endings parse to the same fields and body lines"
    (let [lf (rules/parse-rule rule-content)
          crlf (rules/parse-rule (str/replace rule-content "\n" "\r\n"))]
      (is (= (:frontmatter lf) (:frontmatter crlf)))
      (is (= (str/split-lines (:body lf)) (str/split-lines (:body crlf))))))

  (testing "content without a frontmatter block is all body"
    (is (= {:frontmatter nil :body "# Plain\n\nBody."}
           (rules/parse-rule "# Plain\n\nBody.\n")))
    (is (nil? (:frontmatter (rules/parse-rule "--- not a fence\n# Title")))))

  (testing "an empty frontmatter block and a closing fence at end of file"
    (is (= {:frontmatter {} :body "# Title"}
           (rules/parse-rule "---\n---\n# Title")))
    (is (= {:frontmatter {:globs "a/**"} :body ""}
           (rules/parse-rule "---\nglobs: a/**\n---")))))

(deftest frontmatter-accessors-test
  (let [{:keys [frontmatter]} (rules/parse-rule rule-content)]
    (testing "globs keep their case and declaration order"
      (is (= ["apps/**/ui/**/*.clj" "**/*.cljs"] (rules/rule-globs frontmatter)))
      (is (= [] (rules/rule-globs nil)))
      (is (= [] (rules/rule-globs {:globs " , "}))))

    (testing "alwaysApply is a case-insensitive boolean"
      (is (not (rules/always-apply? frontmatter)))
      (is (rules/always-apply? {:alwaysApply "true"}))
      (is (rules/always-apply? {:alwaysApply "True"}))
      (is (not (rules/always-apply? {:alwaysApply "yes"})))
      (is (not (rules/always-apply? nil))))

    (testing "prompt terms are lower-cased and require at least one alternative"
      (is (= {:any-of ["widget" "htmx"] :requires ["ui"]}
             (rules/prompt-trigger frontmatter)))
      (is (nil? (rules/prompt-trigger {:promptRequires "ui"})))
      (is (nil? (rules/prompt-trigger nil)))
      (is (= {:any-of ["a"] :requires []}
             (rules/prompt-trigger {:promptAnyOf "a, ,"}))))))

(deftest glob-matches-test
  (testing "a pattern without a slash matches its basename at any depth"
    (is (rules/glob-matches? "deep/dir/file.clj" "*.clj"))
    (is (rules/glob-matches? "file.clj" "*.clj"))
    (is (not (rules/glob-matches? "file.cljs" "*.clj"))))

  (testing "a rooted pattern anchors at the project root and ** spans zero or more segments"
    (is (rules/glob-matches? "apps/x/src/x/ui/a.clj" "apps/**/ui/**/*.clj"))
    (is (rules/glob-matches? "apps/ui/a.clj" "apps/**/ui/**/*.clj"))
    (is (not (rules/glob-matches? "other/apps/x/ui/a.clj" "apps/**/ui/**/*.clj")))
    (is (rules/glob-matches? "db/migrations/001.sql" "db/**"))
    (is (not (rules/glob-matches? "db" "db/**"))))

  (testing "* and ? stay within one segment"
    (is (not (rules/glob-matches? "src/a/b.clj" "src/*.clj")))
    (is (rules/glob-matches? "src/ab.clj" "src/a?.clj"))
    (is (not (rules/glob-matches? "src/a/.clj" "src/a?.clj"))))

  (testing "regular-expression characters in patterns are literal"
    (is (not (rules/glob-matches? "srcXmain.clj" "src.main.clj")))
    (is (rules/glob-matches? "a+b/(c).clj" "a+b/(c).clj")))

  (testing "paths and patterns are normalized before matching"
    (is (rules/glob-matches? "./src/a.clj" "src/*.clj"))
    (is (rules/glob-matches? "/src/a.clj" "src/*.clj"))
    (is (rules/glob-matches? "src\\a.clj" "src/*.clj"))
    (is (rules/glob-matches? "src/a.clj" "./src/*.clj")))

  (testing "empty inputs match nothing"
    (is (not (rules/glob-matches? "" "*.clj")))
    (is (not (rules/glob-matches? nil "*.clj")))
    (is (not (rules/glob-matches? "a.clj" "")))
    (is (not (rules/glob-matches? "a.clj" nil))))

  (testing "an invalid glob fails and names the glob"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid rule glob: src/\[a"
                          (rules/glob-matches? "src/a.clj" "src/[a")))))

(deftest rule-index-test
  (with-temp-project
    {".cursor/rules/ui.mdc"
     "---\nglobs: apps/**/ui/**/*.clj\nalwaysApply: false\n---\n# UI"
     ".cursor/rules/base.mdc"
     "---\ndescription: Base\nalwaysApply: true\n---\n# Base"
     ".cursor/rules/tasks/testing.mdc"
     "---\nglobs: **/test/**/*.clj\npromptAnyOf: deftest\n---\n# Testing"
     ".cursor/rules/notes.md" "not a rule"
     ".cursor/rules/plain.mdc" "# Plain"}
    (fn [root]
      (let [source (rules/project-rules-source root)
            index (rules/rule-index source)
            globs-index (rules/rule-globs-index index)]
        (testing "every .mdc file is indexed in name order with its selection fields"
          (is (= ["base.mdc" "plain.mdc" "tasks/testing.mdc" "ui.mdc"]
                 (mapv :rule-name index)))
          (is (= {:rule-name "tasks/testing.mdc"
                  :globs ["**/test/**/*.clj"]
                  :always-apply? false
                  :prompt-trigger {:any-of ["deftest"] :requires []}}
                 (nth index 2)))
          (is (= {:rule-name "plain.mdc"
                  :globs []
                  :always-apply? false
                  :prompt-trigger nil}
                 (nth index 1))))
        (testing "selection helpers derive from the index"
          (is (= ["base.mdc"] (rules/always-apply-rule-names index)))
          (is (= {"ui.mdc" ["apps/**/ui/**/*.clj"]
                  "tasks/testing.mdc" ["**/test/**/*.clj"]}
                 globs-index))
          (is (= ["tasks/testing.mdc" "ui.mdc"]
                 (rules/rules-matching-path globs-index
                                                  "apps/x/test/x/ui/a_test.clj")))
          (is (= [] (rules/rules-matching-path globs-index "README.md")))
          (is (= [{:rule "base.mdc" :always-apply? true :keywords []}
                  {:rule "tasks/testing.mdc" :always-apply? false :keywords ["deftest"]}]
                 (rules/rules-for-prompt source "fix the DEFTEST")))
          (is (= [{:rule "base.mdc" :always-apply? true :keywords []}]
                 (rules/rules-for-prompt source "hello"))))))))

(deftest prompt-match-reasons-test
  (is (= [] (rules/prompt-match-reasons [])))
  (is (= ["[rule-fairy matched: alwaysApply base.mdc, core.mdc]"]
         (rules/prompt-match-reasons
          [{:rule "base.mdc" :always-apply? true :keywords []}
           {:rule "core.mdc" :always-apply? true :keywords []}])))
  (is (= ["[rule-fairy matched: keyword \"service\", \"deftest\"]"]
         (rules/prompt-match-reasons
          [{:rule "a.mdc" :always-apply? false :keywords ["service"]}
           {:rule "b.mdc" :always-apply? false :keywords ["service" "deftest"]}])))
  (testing "a rule that is always-on and keyword-matched appears in both lines"
    (is (= ["[rule-fairy matched: alwaysApply both.mdc]"
            "[rule-fairy matched: keyword \"widget\"]"]
           (rules/prompt-match-reasons
            [{:rule "both.mdc" :always-apply? true :keywords ["widget"]}])))))

(deftest project-rules-source-test
  (testing "without a config file the source is Cursor's layout under the root"
    (with-temp-project
      {}
      (fn [root]
        (is (= {:project-root (str root)
                :dirs [(str (io/file root ".cursor/rules"))]
                :extensions [".mdc"]}
               (rules/project-rules-source root))))))

  (testing "a relative root normalises away the leading ./"
    (is (= {:project-root "." :dirs [".cursor/rules"] :extensions [".mdc"]}
           (rules/project-rules-source "."))))

  (testing "rule-fairy.edn adds directories and extensions"
    (with-temp-project
      {"rule-fairy.edn" "{:rules {:dirs [\"docs/rules\" \"/abs/elsewhere\"] :extensions [\".md\" \".mdc\"]}}"}
      (fn [root]
        (is (= {:project-root (str root)
                :dirs [(str (io/file root "docs/rules")) "/abs/elsewhere"]
                :extensions [".md" ".mdc"]}
               (rules/project-rules-source root))))))

  (testing "invalid configuration fails with the file named"
    (doseq [[config message] [["[:not :a :map]" #"must contain a map"]
                              ["{:rules [:dirs]}" #":rules in rule-fairy.edn must be a map"]
                              ["{:rules {:dirs []}}" #":dirs in rule-fairy.edn must be a non-empty vector"]
                              ["{:rules {:dirs \"docs\"}}" #":dirs in rule-fairy.edn"]
                              ["{:rules {:dirs [\"\"]}}" #":dirs in rule-fairy.edn"]
                              ["{:rules {:extensions [\"md\"]}}" #":extensions in rule-fairy.edn"]
                              ["{:rules {:extensions []}}" #":extensions in rule-fairy.edn"]
                              ["{:rules {:dir \"x\"}}" #"Unknown :rules keys in rule-fairy.edn: :dir"]
                              ["{:rule {:dirs [\"x\"]}}" #"Unknown keys in rule-fairy.edn: :rule"]]]
      (with-temp-project
        {"rule-fairy.edn" config}
        (fn [root]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                                (rules/project-rules-source root))
              config))))))

(deftest multiple-rules-directories-test
  (with-temp-project
    {"rule-fairy.edn" "{:rules {:dirs [\".cursor/rules\" \"docs/rules\"] :extensions [\".mdc\" \".md\"]}}"
     ".cursor/rules/a.mdc" "---\nglobs: src/**\n---\n# A\n"
     "docs/rules/b.md" "---\nalwaysApply: true\n---\n# B\n\n@doc/guide.md#shared\n"
     "docs/rules/nested/d.mdc" "# D"
     "docs/rules/c.txt" "# not a rule"
     "doc/guide.md" "# Guide\n\n## Shared\n\nshared text\n"}
    (fn [root]
      (let [source (rules/project-rules-source root)]
        (testing "files from every directory and extension are indexed by name"
          (is (= ["a.mdc" "b.md" "nested/d.mdc"]
                 (mapv :rule-name (rules/rule-index source))))
          (is (= (str (io/file root "docs/rules/b.md"))
                 (str (rules/rule-file source "b.md"))))
          (is (nil? (rules/rule-file source "missing.mdc"))))
        (testing "rules from different directories render together"
          (let [rendered (rules/render-rules source ["a.mdc" "b.md"])]
            (is (str/includes? rendered "# a.mdc\n\n# A"))
            (is (str/includes? rendered "# b.md\n\n# B"))
            (is (str/includes? rendered "shared text"))))
        (testing "the tree mtime spans every directory"
          (is (<= (.lastModified (io/file root "docs/rules/nested/d.mdc"))
                  (rules/rules-tree-mtime source))))
        (testing "a name present in two directories is an error"
          (spit (io/file root "docs/rules/a.mdc") "# Shadow")
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Rule names defined in more than one rules directory: a.mdc"
                                (rules/rule-index source))))))))

(deftest documentation-names-its-importers-test
  (with-temp-project
    {".cursor/rules/ui.mdc" "# UI\n\n@doc/guide.md#ui\n@doc/shared.md\n"
     ".cursor/rules/api.mdc" "# API\n\n@doc/guide.md#api\n@doc/shared.md\n"
     "doc/guide.md" (str "# Guide\n\n## UI\n\nui text\n\n"
                         "## Middle\n\nnot imported\n\n"
                         "## API\n\napi text\n")
     "doc/shared.md" "# Shared\n\nshared text\n"}
    (fn [root]
      (let [rendered (rules/render-rules (rules/project-rules-source root) ["ui.mdc" "api.mdc"])]
        (testing "each documentation block names the rules that import it"
          (is (str/includes? rendered "## Required context: `doc/guide.md#ui` (ui.mdc)\n\n## UI\n\nui text"))
          (is (str/includes? rendered "## Required context: `doc/guide.md#api` (api.mdc)\n\n## API\n\napi text"))
          (is (str/includes? rendered "## Required context: `doc/shared.md` (ui.mdc, api.mdc)")))))))

(deftest linked-heading-import-test
  (with-temp-project
    {".cursor/rules/t.mdc" "# T\n\n@doc/guide.md#testing\n"
     "doc/guide.md" "# Guide\n\n## [Testing](testing.md)\n\nrun the tests\n\n## Other\n\nnot imported\n"}
    (fn [root]
      (testing "an import names the anchor GitHub gives a heading that is a link"
        (let [rendered (rules/render-rules (rules/project-rules-source root) ["t.mdc"])]
          (is (str/includes? rendered "## [Testing](testing.md)\n\nrun the tests"))
          (is (not (str/includes? rendered "not imported"))))))))

(deftest merged-sections-keep-their-importers-test
  (with-temp-project
    {".cursor/rules/a.mdc" "---\nglobs: src/a/**\n---\n# A\n\n@doc/guide.md#one\n@doc/guide.md#two\n"
     ".cursor/rules/b.mdc" "---\nglobs: src/b/**\n---\n# B\n\n@doc/guide.md#two\n@doc/guide.md#three\n"
     ".cursor/rules/c.mdc" "# C\n\n@doc/link.md#one\n"
     "doc/guide.md" "# Guide\n\n## One\n\none\n\n## Two\n\ntwo\n\n## Three\n\nthree\n"}
    (fn [root]
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file root "doc/link.md"))
                                              (.toPath (io/file "guide.md"))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (let [source (rules/project-rules-source root)
            rendered (rules/render-rules source ["a.mdc" "b.mdc"])]
        (testing "adjacent sections merge into one block whose heading names the importers per reference"
          (is (str/includes? rendered (str "## Required context: `doc/guide.md#one` (a.mdc), "
                                           "`doc/guide.md#two` (a.mdc, b.mdc), `doc/guide.md#three` (b.mdc)")))
          (is (= 1 (count (re-seq #"## Two" rendered)))))
        (testing "a documentation block names the file as written; the revision check follows the link"
          (is (= ["doc/link.md"]
                 (:paths (last (rules/render-rule-blocks source ["c.mdc"]))))))))))

(deftest crlf-rule-renders-test
  (with-temp-project
    {".cursor/rules/win.mdc"
     (str "---\r\nglobs: src/**\r\nalwaysApply: false\r\n---\r\n"
          "# Windows rule\r\n\r\n@doc/guide.md#section\r\n")
     "doc/guide.md"
     "# Guide\r\n\r\n## Section\r\n\r\nline one\r\n\r\n## Other\r\n\r\nnot imported\r\n"}
    (fn [root]
      (let [rendered (rules/render-rules (rules/project-rules-source root) ["win.mdc"])]
        (is (str/starts-with? rendered "# win.mdc\n\n# Windows rule"))
        (is (not (str/includes? rendered "globs:")))
        (is (str/includes? rendered "## Required context: `doc/guide.md#section`"))
        (is (str/includes? rendered "line one"))
        (is (not (str/includes? rendered "not imported")))))))

(deftest import-only-heading-keeps-a-pointer-test
  (with-temp-project
    {".cursor/rules/ui.mdc" "# UI\n\n### Dialogs\n\n@doc/guide.md#dialogs\n\n### Next\n\nprose\n"
     "doc/guide.md" "# Guide\n\n## Dialogs\n\ndialog text\n"}
    (fn [root]
      (let [rendered (rules/render-rules (rules/project-rules-source root) ["ui.mdc"])]
        (testing "a heading whose only content was an import keeps a line naming it"
          (is (str/includes? rendered
                             (str "### Dialogs\n\n"
                                  "Imported: `doc/guide.md#dialogs` (delivered as a Required context block)\n\n"
                                  "### Next"))))
        (testing "the import line is gone and the section renders once, in its documentation block"
          (is (not (str/includes? rendered "@doc/guide.md")))
          (is (= 1 (count (re-seq #"dialog text" rendered))))
          (is (str/includes? rendered "## Required context: `doc/guide.md#dialogs` (ui.mdc)\n\n## Dialogs\n\ndialog text")))))))

(let [{:keys [fail error]} (run-tests 'rules-test)]
  (System/exit (+ fail error)))

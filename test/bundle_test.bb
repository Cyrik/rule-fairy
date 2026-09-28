#!/usr/bin/env bb

(ns bundle-test
  (:require [babashka.fs :as fs]
            [rule-fairy.bundle :as bundle]
            [rule-fairy.dedup :as dedup]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn with-temp-project [files f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "bundle-test"
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

(def matching-rule-content
  (str "---\n"
       "description: Review rule\n"
       "globs: apps/**/src/*.clj\n"
       "alwaysApply: false\n"
       "---\n"
       "# Review rule\n\n"
       "Apply this rule.\n\n"
       "@doc/guide.md#agent-checks\n"))

(def guide-content
  (str "# Guide\n\n"
       "Developer context.\n\n"
       "## Agent checks\n\n"
       "Check the imported constraint.\n\n"
       "<!-- agent-context: omit -->\n"
       "Developer-only explanation.\n"
       "<!-- /agent-context -->\n\n"
       "Keep the final constraint.\n\n"
       "## Other section\n\n"
       "Do not import this section.\n"))

(def long-paragraph
  (str/trim (apply str (repeat 6 "This sentence pads the paragraph well past the duplicate threshold. "))))

(deftest changed-paths-from-patch-test
  (is (= ["apps/a.clj" "docs/new name.md"]
         (bundle/changed-paths-from-patch
          (str "diff --git a/apps/a.clj b/apps/a.clj\n"
               "index 1..2 100644\n"
               "diff --git a/docs/old.md b/docs/new name.md\n"
               "diff --git a/apps/a.clj b/apps/a.clj\n"))))
  (is (= [] (bundle/changed-paths-from-patch "")))

  (testing "headers git quoted are decoded: octal UTF-8 bytes, quotes, backslashes, tabs"
    (is (= ["src/fäö.clj" "src/quo\"te.clj" "back\\slash.clj" "tab\there.clj" "plain.clj"]
           (bundle/changed-paths-from-patch
            (str "diff --git \"a/src/f\\303\\244\\303\\266.clj\" \"b/src/f\\303\\244\\303\\266.clj\"\n"
                 "diff --git \"a/src/quo\\\"te.clj\" \"b/src/quo\\\"te.clj\"\n"
                 "diff --git \"a/back\\\\slash.clj\" \"b/back\\\\slash.clj\"\n"
                 "diff --git \"a/tab\\there.clj\" \"b/tab\\there.clj\"\n"
                 "diff --git \"a/quo\\\"ted.clj\" b/plain.clj\n")))))

  (testing "a literal non-ASCII character in a quoted header survives whole, so both spellings agree"
    (is (= ["src/😀\"name.clj"]
           (bundle/changed-paths-from-patch
            (str "diff --git \"a/src/😀\\\"name.clj\" \"b/src/😀\\\"name.clj\"\n"
                 "diff --git \"a/src/\\360\\237\\230\\200\\\"name.clj\" \"b/src/\\360\\237\\230\\200\\\"name.clj\"\n")))))

  (testing "every named escape git writes is decoded"
    (is (= ["c\u0007\b\f\u000b.clj"]
           (bundle/changed-paths-from-patch
            "diff --git \"a/c\\a\\b\\f\\v.clj\" \"b/c\\a\\b\\f\\v.clj\"\n"))))

  (testing "an unquoted path containing ` b/` is read whole when both sides agree"
    (is (= ["src/a b/name.clj" "src/y.clj"]
           (bundle/changed-paths-from-patch
            (str "diff --git a/src/a b/name.clj b/src/a b/name.clj\n"
                 "diff --git a/src/a b/x.clj b/src/y.clj\n")))))

  (testing "a rename takes its destination from the rename to line, quoted or not, hunks ignored"
    (is (= ["src/a b/name.clj" "src/q\"x.clj" "src/moved.clj"]
           (bundle/changed-paths-from-patch
            (str "diff --git a/src/old.clj b/src/a b/name.clj\n"
                 "similarity index 100%\n"
                 "rename from src/old.clj\n"
                 "rename to src/a b/name.clj\n"
                 "diff --git a/src/plain.clj \"b/src/q\\\"x.clj\"\n"
                 "similarity index 50%\n"
                 "rename from src/plain.clj\n"
                 "rename to \"src/q\\\"x.clj\"\n"
                 "index 1..2 100644\n"
                 "--- a/src/plain.clj\n"
                 "+++ \"b/src/q\\\"x.clj\"\n"
                 "@@ -1 +1 @@\n"
                 "-rename to src/not-this.clj\n"
                 "+x\n"
                 "diff --git a/src/here.clj b/src/moved.clj\n"
                 "similarity index 80%\n"
                 "rename from src/here.clj\n"
                 "rename to src/moved.clj\n"
                 "--- a/src/here.clj\n"
                 "+++ b/src/moved.clj\n"))))))

(deftest build-test
  (with-temp-project
    {"CLAUDE.md" "Repository instructions."
     ".cursor/rules/review.mdc"
     "---\nglobs: apps/other/**\nalwaysApply: false\n---\n# Top-level collision\n"
     ".cursor/rules/tasks/review.mdc" matching-rule-content
     ".cursor/rules/unmatched.mdc"
     "---\nglobs: apps/other/**\nalwaysApply: false\n---\n# Unmatched\n"
     ".cursor/rules/base.mdc"
     "---\ndescription: Base\nalwaysApply: true\n---\n# Base rule\n"
     ".cursor/rules/win.mdc"
     "---\r\nglobs: apps/**/src/*.clj\r\nalwaysApply: false\r\n---\r\n# Windows rule\r\n"
     "doc/guide.md" guide-content}
    (fn [root]
      (let [{:keys [bundle rule-names rule-count instruction-paths omissions changed-paths]}
            (bundle/build root ["./apps/example/src/example.clj"])]
        (testing "the header names what went in"
          (is (= ["apps/example/src/example.clj"] changed-paths))
          (is (= ["CLAUDE.md"] instruction-paths))
          (is (= ["base.mdc" "tasks/review.mdc" "win.mdc"] rule-names))
          (is (= 5 rule-count))
          (is (= [] omissions))
          (is (str/starts-with? bundle
                                (str "# Review bundle\n\n"
                                     "- Changed paths: 1\n"
                                     "- Instruction files: `CLAUDE.md`\n"
                                     "- Rules: `base.mdc`, `tasks/review.mdc`, `win.mdc` (3 of 5 rule files)\n"
                                     "- Duplicates omitted: 0\n\n---\n\n"))))
        (testing "instruction files, rules and documentation use the shared renderer"
          (is (str/includes? bundle "# CLAUDE.md\n\nRepository instructions."))
          (is (str/includes? bundle (str "# tasks/review.mdc\n\n"
                                         "*Scope: globs `apps/**/src/*.clj`; matched `apps/example/src/example.clj`.*\n\n"
                                         "# Review rule")))
          (is (str/includes? bundle "# Required documentation\n\n## Required context: `doc/guide.md#agent-checks`"))
          (is (str/includes? bundle "Check the imported constraint."))
          (is (str/includes? bundle "Keep the final constraint."))
          (is (not (str/includes? bundle "@doc/guide.md")))
          (is (not (str/includes? bundle "Developer-only explanation.")))
          (is (not (str/includes? bundle "Do not import this section.")))
          (is (not (str/includes? bundle "# Top-level collision")))
          (is (not (str/includes? bundle "# Unmatched"))))
        (testing "alwaysApply rules are included without a glob match, and say so"
          (is (str/includes? bundle "# base.mdc\n\n*Scope: applies everywhere (alwaysApply).*\n\n# Base rule")))
        (testing "CRLF frontmatter selects and renders like LF"
          (is (str/includes? bundle (str "# win.mdc\n\n"
                                         "*Scope: globs `apps/**/src/*.clj`; matched `apps/example/src/example.clj`.*\n\n"
                                         "# Windows rule")))
          (is (not (str/includes? bundle "globs:"))))
        (testing "instruction files come before rules, documentation last"
          (is (< (str/index-of bundle "# CLAUDE.md")
                 (str/index-of bundle "# base.mdc")
                 (str/index-of bundle "# Required documentation"))))))))

(deftest explicit-paths-test
  (with-temp-project
    {".cursor/rules/ui.mdc" "---\nglobs: apps/**/ui/**\nalwaysApply: false\n---\n# UI rule\n"
     ".cursor/rules/db.mdc" "---\nglobs: db/**\nalwaysApply: false\n---\n# DB rule\n"}
    (fn [root]
      (let [{:keys [rule-names rule-count bundle instruction-paths]}
            (bundle/build root ["apps/x/ui/a.clj" "new/file.clj"])]
        (is (= ["ui.mdc"] rule-names))
        (is (= 2 rule-count))
        (is (= [] instruction-paths))
        (is (str/includes? bundle "- Instruction files: none\n"))
        (is (str/includes? bundle "# UI rule"))
        (is (not (str/includes? bundle "# DB rule")))))))

(deftest instruction-scope-and-imports-test
  (with-temp-project
    {"AGENTS.md" "# Agents\n\nRoot agents."
     "CLAUDE.md" "# Claude\n\nSee @docs/shared.md for more."
     "docs/shared.md" "# Shared\n\nShared guidance."
     "apps/web/CLAUDE.md" "# Web\n\nWeb only."
     "apps/api/CLAUDE.md" "# Api\n\nApi only."}
    (fn [root]
      (let [{:keys [bundle instruction-paths]} (bundle/build root ["apps/web/src/x.clj"])]
        (is (= ["AGENTS.md" "CLAUDE.md" "docs/shared.md" "apps/web/CLAUDE.md"] instruction-paths))
        (is (str/includes? bundle "# docs/shared.md (imported by CLAUDE.md)\n\n# Shared"))
        (is (not (str/includes? bundle "Api only.")))))))

(deftest aliased-instruction-file-test
  (with-temp-project
    {"AGENTS.md" "# Agents\n\nShared guidance."
     "api/x.clj" ""}
    (fn [root]
      (java.nio.file.Files/createSymbolicLink (.toPath (io/file root "api/AGENTS.md"))
                                              (.toPath (io/file root "AGENTS.md"))
                                              (make-array java.nio.file.attribute.FileAttribute 0))
      (let [{:keys [bundle instruction-paths input-paths]} (bundle/build root ["api/x.clj"])]
        (testing "a file reached under another path is rendered once, its other path named"
          (is (str/includes? bundle "# AGENTS.md (also api/AGENTS.md)\n\n# Agents"))
          (is (= 1 (count (re-seq #"Shared guidance" bundle))))
          (is (= ["AGENTS.md"] instruction-paths)))
        (testing "the inputs for the revision check are every path of the instruction files and the configuration file"
          (is (= ["AGENTS.md" "api/AGENTS.md" (str (io/file root "AGENTS.md")) "rule-fairy.edn"] input-paths)))))))

(deftest dedup-across-bundle-test
  (with-temp-project
    {"AGENTS.md" (str "# Agents\n\n## Testing\n\n" long-paragraph)
     "CLAUDE.md" (str "# Claude\n\n## Testing\n\n" long-paragraph)
     ".cursor/rules/testing.mdc" (str "---\nalwaysApply: true\n---\n# Testing rule\n\n" long-paragraph "\n\nRule-only line.\n")}
    (fn [root]
      (let [{:keys [bundle omissions]} (bundle/build root [])]
        (testing "a copied section and a pasted rule paragraph point at the kept copy"
          (is (= [{:in "CLAUDE.md" :kept "AGENTS.md#testing"}
                  {:in "testing.mdc" :kept "AGENTS.md#testing"}]
                 omissions))
          (is (str/includes? bundle (str "# CLAUDE.md\n\n# Claude\n\n" (dedup/omission-line "AGENTS.md#testing"))))
          (is (str/includes? bundle (str "# testing.mdc\n\n*Scope: applies everywhere (alwaysApply).*\n\n"
                                         "# Testing rule\n\n"
                                         (dedup/omission-line "AGENTS.md#testing")
                                         "\n\nRule-only line."))
              "the scope line sits outside the deduplicated content")
          (is (str/includes? bundle "- Duplicates omitted: 2, each replaced by a line naming the kept copy")))))))

(deftest scope-line-test
  (with-temp-project
    {".cursor/rules/both.mdc" "---\nglobs: db/**, apps/**/ui/**\nalwaysApply: true\n---\n# Both\n"
     ".cursor/rules/ui.mdc" "---\nglobs: apps/**/ui/**\n---\n# UI\n"}
    (fn [root]
      (let [{:keys [bundle]} (bundle/build root ["apps/x/ui/a.clj" "apps/x/ui/b.clj" "README.md"])]
        (testing "a rule with both settings reports both, listing only the paths its globs matched"
          (is (str/includes? bundle (str "# both.mdc\n\n*Scope: applies everywhere (alwaysApply); "
                                         "globs `db/**`, `apps/**/ui/**`; matched `apps/x/ui/a.clj`, `apps/x/ui/b.clj`.*"))))
        (testing "an always-on rule whose globs matched nothing says so"
          (let [{:keys [bundle]} (bundle/build root ["README.md"])]
            (is (str/includes? bundle "*Scope: applies everywhere (alwaysApply); globs `db/**`, `apps/**/ui/**`; matched none.*"))
            (is (not (str/includes? bundle "# ui.mdc")))))))))

(deftest artifacts-test
  (with-temp-project
    {".cursor/rules/ui.mdc" "---\nglobs: apps/**/ui/**\n---\n# UI rule\n"}
    (fn [root]
      (testing "modes have separate directories under the self-ignored review root"
        (is (= (io/file root ".rule-fairy/review/diff/rules.md") (bundle/bundle-file root :diff)))
        (is (= (io/file root ".rule-fairy/review/plan/rules.md") (bundle/bundle-file root :plan)))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown review mode: :pr"
                              (bundle/review-dir root :pr))))
      (testing "write! creates the directory, the ignore file and the bundle"
        (let [{:keys [file rule-names]} (bundle/write! root :plan ["apps/x/ui/a.clj"])]
          (is (= (bundle/bundle-file root :plan) file))
          (is (= ["ui.mdc"] rule-names))
          (is (str/includes? (slurp file) "# UI rule"))
          (is (= "*\n" (slurp (io/file root ".rule-fairy/review/.gitignore"))))
          (is (not (.exists (bundle/bundle-file root :diff))))))
      (testing "clean! removes the mode directory and leaves the review root"
        (let [outside (io/file root "outside")]
          (io/make-parents (io/file outside "keep.txt"))
          (spit (io/file outside "keep.txt") "keep")
          (fs/create-sym-link (io/file (bundle/review-dir root :plan) "link") outside)
          (is (= (bundle/review-dir root :plan) (bundle/clean! root :plan)))
          (is (not (.exists (bundle/review-dir root :plan))))
          (is (.exists (io/file outside "keep.txt"))
              "a symlink inside the artifacts is removed, its target untouched")
          (is (.exists (io/file root ".rule-fairy/review/.gitignore")))
          (is (not (.exists (bundle/clean! root :plan))) "cleaning again is fine")))
      (testing "a build failure writes nothing"
        (spit (io/file root ".cursor/rules/broken.mdc") "---\nalwaysApply: true\n---\n# Broken\n\n@doc/missing.md\n")
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Rule import does not exist"
                              (bundle/write! root :diff ["README.md"])))
        (is (not (.exists (bundle/bundle-file root :diff))))))))

(let [{:keys [fail error]} (run-tests 'bundle-test)]
  (System/exit (+ fail error)))

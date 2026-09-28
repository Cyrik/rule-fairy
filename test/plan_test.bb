#!/usr/bin/env bb

(ns plan-test
  (:require [rule-fairy.plan :as plan]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn with-temp-project [files f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "plan-test"
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

(deftest candidate-paths-test
  (testing "code spans holding paths or file names, with line, anchor and punctuation stripped"
    (is (= ["src/a.clj" "docs/guide.md" "README.md" "bb.edn"]
           (plan/candidate-paths
            (str "Edit `src/a.clj:42` and `docs/guide.md#testing`, then `README.md`.\n"
                 "Also touch `./bb.edn`, run `bb test`, and see `(defn foo [])`.\n")))))

  (testing "bare tokens need a slash; prose words and URLs do not count"
    (is (= ["apps/web/core.clj" "db/migrations" "either/or"]
           (plan/candidate-paths
            (str "Move apps/web/core.clj (e.g. the ns) into db/migrations/ later.\n"
                 "See https://example.com/x/y.md and version 1.2.3 and either/or?\n")))
        "either/or is a candidate here; the checkout filter in plan-paths drops it"))

  (testing "globs and spans with spaces are not paths"
    (is (= [] (plan/candidate-paths "Match `src/**/*.clj` and `apps/* /x`, plus `a b/c`."))))

  (testing "absolute paths stay absolute, in a code span, bare or as a link destination; a URL still does not count"
    (is (= ["/repo/src/a.clj"] (plan/candidate-paths "Edit `/repo/src/a.clj:3`.")))
    (is (= ["/repo/src/b.clj" "/repo/docs/g.md"]
           (plan/candidate-paths "See /repo/src/b.clj and [the guide](/repo/docs/g.md), not https://example.com/x/y.md."))))

  (testing "fenced blocks count and duplicates collapse"
    (is (= ["test/rules_test.bb" "src/x.clj"]
           (plan/candidate-paths
            (str "```sh\nbb test/rules_test.bb\n```\n\n- `src/x.clj`\n- `src/x.clj` again\n"))))))

(deftest listed-paths-test
  (testing "a file-list section yields the first path of each list item"
    (is (= ["db/migrations/001.sql" "apps/web/src/ui.clj" "docs/new.md"]
           (plan/listed-paths
            (str "# Plan\n\n## Files to change\n\n"
                 "- `db/migrations/001.sql`: new table, see `docs/other.md`\n"
                 "* apps/web/src/ui.clj\n"
                 "1. Create `docs/new.md`\n"
                 "Not a list item: `ignored/x.clj`\n\n"
                 "## Steps\n\n- `steps/are/not/files.clj`\n")))))

  (testing "a bare absolute path in a list item counts"
    (is (= ["/repo/src/a.clj"] (plan/listed-paths "## Files\n\n- /repo/src/a.clj: the widget\n"))))

  (testing "heading variants are recognised case-insensitively"
    (doseq [heading ["Files" "FILES" "File" "Affected files" "Changed Files" "Files touched" "Files to create"]]
      (is (= ["a/b.clj"] (plan/listed-paths (str "## " heading "\n\n- `a/b.clj`\n"))) heading)))

  (testing "every file-list section contributes, in document order"
    (is (= ["src/new.clj" "src/old.clj"]
           (plan/listed-paths (str "## Files to create\n\n- `src/new.clj`\n\n"
                                   "## Files to modify\n\n- `src/old.clj`\n\n"
                                   "## Steps\n\n- `steps/x.clj`\n")))))

  (testing "no section, or an empty one, means nil"
    (is (nil? (plan/listed-paths "# Plan\n\n- `a/b.clj`\n")))
    (is (nil? (plan/listed-paths "## Files\n\nNothing listed here.\n")))))

(deftest plan-paths-test
  (with-temp-project
    {"apps/web/src/existing.clj" "(ns existing)"
     "apps/.git/config" "not a source file"
     "docs/readme.md" "docs"}
    (fn [root]
      (testing "extracted paths are kept when they or their parent directory exist, a directory standing for its files"
        (is (= {:paths ["apps/web/src/existing.clj" "apps/web/src/new.clj" "docs/readme.md"]
                :dropped ["either/or" "elsewhere/thing.clj"]
                :expanded {"docs" 1}
                :source :extracted}
               (plan/plan-paths root (str "Edit `apps/web/src/existing.clj`, add `apps/web/src/new.clj`,\n"
                                          "touch the `docs/` folder and `elsewhere/thing.clj`, either/or.\n")))))

      (testing "a .git directory is skipped; an empty directory and one to be created stay paths"
        (.mkdirs (io/file root "empty"))
        (is (= {:paths ["apps/web/src/existing.clj" "docs/newdir" "empty"]
                :dropped []
                :expanded {"apps" 1}
                :source :extracted}
               (plan/plan-paths root "Rework `apps/`, `empty/` and `docs/newdir/`.\n"))))

      (testing "a file-list section is taken as written, directories expanded in place"
        (is (= {:paths ["elsewhere/thing.clj" "docs/readme.md" "apps/web/src/existing.clj"]
                :dropped []
                :expanded {"docs" 1}
                :source :list}
               (plan/plan-paths root (str "## Files\n\n- `elsewhere/thing.clj`\n- `docs/`\n- `apps/web/src/existing.clj`\n\n"
                                          "## Steps\n\nAlso `docs/readme.md`.\n")))))

      (testing "an absolute path under the project, as given or with links resolved, is placed under the root; one outside is dropped"
        (let [real-root (str (fs/real-path root))]
          (is (= {:paths ["apps/web/src/existing.clj" "docs/readme.md"]
                  :dropped ["/elsewhere/thing.clj"]
                  :expanded {}
                  :source :list}
                 (plan/plan-paths root (str "## Files\n\n- `" root "/apps/web/src/existing.clj`\n"
                                            "- `" real-root "/docs/readme.md`\n"
                                            "- `/elsewhere/thing.clj`\n"))))
          (is (= {:paths ["apps/web/src/existing.clj"]
                  :dropped ["/elsewhere/thing.clj"]
                  :expanded {}
                  :source :extracted}
                 (plan/plan-paths root (str "Edit `" root "/apps/web/src/existing.clj` and `/elsewhere/thing.clj`.\n"))))
          (is (= {:paths ["apps/web/src/existing.clj" "docs/readme.md"]
                  :dropped ["/elsewhere/thing.clj"]
                  :expanded {}
                  :source :extracted}
                 (plan/plan-paths root (str "Edit " root "/apps/web/src/existing.clj, see [docs](" root "/docs/readme.md) and /elsewhere/thing.clj.\n")))))))))

(let [{:keys [fail error]} (run-tests 'plan-test)]
  (System/exit (+ fail error)))

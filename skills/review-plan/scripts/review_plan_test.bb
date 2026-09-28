#!/usr/bin/env bb

(ns review-plan-script-test
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def skill-dir
  (.getParentFile (.getParentFile (.getAbsoluteFile (io/file *file*)))))

(def shim (io/file skill-dir "scripts/run"))

(defn write! [root path content]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file content)))

(defn with-temp-project [files f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "review-plan-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (doseq [[path content] files]
        (write! root path content))
      (f root)
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn run-review-plan [root & args]
  (apply p/shell {:dir root :out :string :err :string :continue true} (str shim) args))

(def plan-dir ".rule-fairy/review/plan")
(def bundle-path (str plan-dir "/rules.md"))
(def meta-path (str plan-dir "/meta.json"))

(def project-files
  {".cursor/rules/ui.mdc" "---\nglobs: apps/**/ui/**\n---\n# UI rule\n\nUI body.\n"
   ".cursor/rules/db.mdc" "---\nglobs: db/**\n---\n# DB rule\n\nDB body.\n"
   ".cursor/rules/base.mdc" "---\nalwaysApply: true\n---\n# Base rule\n"
   "AGENTS.md" "# Agents\n\nInstructions."
   "apps/web/src/web/ui/existing.clj" "(ns web.ui.existing)"
   "docs/plan.md" (str "# Plan\n\n"
                       "1. Edit `apps/web/src/web/ui/existing.clj:12` to add the widget.\n"
                       "2. Create `apps/web/src/web/ui/new_widget.clj` next to it.\n"
                       "3. Later, `elsewhere/nowhere/thing.clj` (directory does not exist yet).\n"
                       "4. Run `bb test`.\n")
   "docs/listed.md" (str "# Plan\n\n"
                         "## Files to change\n\n"
                         "- `db/migrations/001.sql`: new table\n"
                         "- apps/web/src/web/ui/existing.clj\n\n"
                         "## Steps\n\n"
                         "Mention of `docs/other.md` is ignored because the list wins.\n")
   "docs/dir.md" "# Plan\n\nRework everything under `apps/web/` and `docs/`.\n"})

(deftest paths-test
  (with-temp-project
    project-files
    (fn [root]
      (testing "extracted paths, with dropped ones listed"
        (let [{:keys [exit out]} (run-review-plan root "paths" "docs/plan.md")]
          (is (zero? exit) out)
          (is (str/includes? out "Plan: docs/plan.md"))
          (is (str/includes? out (str "Paths (extracted):\n"
                                      "  apps/web/src/web/ui/existing.clj\n"
                                      "  apps/web/src/web/ui/new_widget.clj\n")))
          (is (str/includes? out "Dropped (no such file or parent directory, or outside the project; add back with --paths):\n  elsewhere/nowhere/thing.clj"))
          (is (not (str/includes? out "bb test")))))

      (testing "--paths adds what the heuristic missed, an absolute path placed under the root"
        (let [{:keys [exit out]} (run-review-plan root "paths" "docs/plan.md" "--paths" "elsewhere/nowhere/thing.clj" "./db/x.sql" (str root "/db/y.sql"))]
          (is (zero? exit) out)
          (is (str/includes? out "Added:\n  elsewhere/nowhere/thing.clj\n  db/x.sql\n  db/y.sql"))))

      (testing "an absolute plan path is read as given"
        (let [plan (.getAbsolutePath (io/file root "docs/plan.md"))
              {:keys [exit out]} (run-review-plan root "paths" plan)]
          (is (zero? exit) out)
          (is (str/includes? out (str "Plan: " plan)))
          (is (str/includes? out "Paths (extracted):\n  apps/web/src/web/ui/existing.clj\n"))))

      (testing "a directory reference stands for the files under it"
        (let [{:keys [exit out]} (run-review-plan root "paths" "docs/dir.md")]
          (is (zero? exit) out)
          (is (str/includes? out (str "Paths (extracted):\n"
                                      "  apps/web/src/web/ui/existing.clj\n"
                                      "  docs/dir.md\n"
                                      "  docs/listed.md\n"
                                      "  docs/plan.md\n")))
          (is (str/includes? out (str "Directories expanded to the files under them:\n"
                                      "  apps/web/ (1 file)\n"
                                      "  docs/ (3 files)\n")))))

      (testing "a file-list section takes precedence and is taken as written"
        (let [{:keys [exit out]} (run-review-plan root "paths" "docs/listed.md")]
          (is (zero? exit) out)
          (is (str/includes? out "Paths (list):\n  db/migrations/001.sql\n  apps/web/src/web/ui/existing.clj\n"))
          (is (not (str/includes? out "docs/other.md")))
          (is (not (str/includes? out "Dropped"))))))))

(deftest bundle-and-clean-test
  (with-temp-project
    project-files
    (fn [root]
      (testing "the bundle and metadata are written for the plan's paths"
        (let [{:keys [exit out]} (run-review-plan root "bundle" "docs/plan.md")
              bundle (slurp (io/file root bundle-path))
              meta (json/parse-string (slurp (io/file root meta-path)) true)]
          (is (zero? exit) out)
          (is (str/includes? out "2 of 3 rules, 1 instruction files, 2 paths (extracted), 0 duplicates omitted"))
          (is (str/includes? bundle "# AGENTS.md\n\n# Agents"))
          (is (str/includes? bundle "# UI rule"))
          (is (str/includes? bundle "# Base rule"))
          (is (not (str/includes? bundle "# DB rule")))
          (is (= {:mode "plan"
                  :plan_file "docs/plan.md"
                  :path_source "extracted"
                  :changed_paths ["apps/web/src/web/ui/existing.clj" "apps/web/src/web/ui/new_widget.clj"]
                  :added_paths []
                  :dropped_paths ["elsewhere/nowhere/thing.clj"]
                  :expanded_directories {}
                  :rules {:dirs [".cursor/rules"] :head_ref_oid nil :clean nil}}
                 (dissoc meta :generated_at)))
          (is (string? (:generated_at meta)))
          (is (= "*\n" (slurp (io/file root ".rule-fairy/review/.gitignore"))))
          (is (not (.exists (io/file root ".rule-fairy/review/diff"))))))

      (testing "bundle again with added paths replaces the artifacts and records the additions"
        (write! root (str plan-dir "/leftover.txt") "from an earlier pass")
        (let [{:keys [exit out]} (run-review-plan root "bundle" "docs/plan.md" "--paths" "db/schema.sql")
              meta (json/parse-string (slurp (io/file root meta-path)) true)]
          (is (zero? exit) out)
          (is (str/includes? out "3 paths (extracted, 1 added)"))
          (is (= ["db/schema.sql"] (:added_paths meta)))
          (is (str/includes? (slurp (io/file root bundle-path)) "# DB rule"))
          (is (not (.exists (io/file root plan-dir "leftover.txt"))))))

      (testing "clean removes the plan artifacts"
        (let [{:keys [exit out]} (run-review-plan root "clean")]
          (is (zero? exit) out)
          (is (str/includes? out "Removed"))
          (is (not (.exists (io/file root plan-dir))))
          (is (.exists (io/file root ".rule-fairy/review/.gitignore"))))
        (is (zero? (:exit (run-review-plan root "clean"))))))))

(deftest arguments-test
  (with-temp-project
    project-files
    (fn [root]
      (doseq [[args message] [[[] "usage: review-plan paths"]
                              [["paths"] "A plan file is required"]
                              [["paths" "docs/missing.md"] "Plan not found"]
                              [["bundle" "docs/plan.md" "extra"] "Unexpected argument: extra"]
                              [["bundle" "docs/plan.md" "--bogus"] "Unknown argument: --bogus"]
                              [["paths" "docs/plan.md" "--paths" "/elsewhere/x.clj"] "Path outside the project: /elsewhere/x.clj"]
                              [["clean" "now"] "clean takes no arguments"]
                              [["frobnicate"] "Unknown command: frobnicate"]]]
        (let [{:keys [exit err]} (apply run-review-plan root args)]
          (is (= 1 exit) (pr-str args))
          (is (str/includes? err message) (pr-str args))))
      (is (not (.exists (io/file root ".rule-fairy")))))))

(let [{:keys [fail error]} (run-tests 'review-plan-script-test)]
  (System/exit (+ fail error)))

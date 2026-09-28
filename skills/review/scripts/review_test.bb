#!/usr/bin/env bb

(ns review-script-test
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def skill-dir
  (.getParentFile (.getParentFile (.getAbsoluteFile (io/file *file*)))))

(def shim (io/file skill-dir "scripts/run"))

(defn git! [root & args]
  (apply p/shell {:dir root :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(defn git-out [root & args]
  (str/trim (:out (apply git! root args))))

(defn write! [root path content]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file content)))

(defn temp-dir [prefix]
  (.toFile
   (java.nio.file.Files/createTempDirectory
    prefix
    (make-array java.nio.file.attribute.FileAttribute 0))))

(defn delete-tree! [root]
  (doseq [file (reverse (file-seq root))]
    (.delete file)))

(defn with-temp-repo
  "A repository on `main` with a committed source file, doc and rule, a
  `feature` branch with one more commit, an uncommitted modification, an
  untracked file, and a GitHub origin URL."
  [f]
  (let [root (temp-dir "review-script-test")]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (git! root "remote" "add" "origin" "git@github.com:owner/repo.git")
      (write! root "src/a.clj" "old\n")
      (write! root "docs/base.md" "a document committed on main, long enough for rename detection\n")
      (write! root ".cursor/rules/r.mdc" "---\nglobs: src/**\n---\n# R\n\nRule body.\n")
      (write! root "CLAUDE.md" "Repository instructions.")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (git! root "checkout" "-q" "-b" "feature")
      (write! root "docs/x.md" "docs\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "docs")
      (write! root "src/a.clj" "new\n")
      (write! root "src/new file.clj" "untracked\n")
      (f root)
      (finally
        (delete-tree! root)))))

(def fake-gh
  (str "#!/bin/sh\n"
       "case \"$1 $2\" in\n"
       ;; --patch answers with a per-commit series, as GitHub's patch format does.
       "  \"pr diff\") case \" $* \" in\n"
       "    *\" --patch \"*) printf 'From 1111 Mon Sep 17 00:00:00 2001\\nSubject: [PATCH 1/2] first\\n\\n"
       "diff --git a/src/a.clj b/src/a.clj\\n--- a/src/a.clj\\n+++ b/src/a.clj\\n@@ -1 +1 @@\\n-old\\n+intermediate\\n\\n"
       "From 2222 Mon Sep 17 00:00:00 2001\\nSubject: [PATCH 2/2] second\\n\\n"
       "diff --git a/src/a.clj b/src/a.clj\\n--- a/src/a.clj\\n+++ b/src/a.clj\\n@@ -1 +1 @@\\n-intermediate\\n+new\\n' ;;\n"
       "    *) printf 'diff --git a/src/a.clj b/src/a.clj\\n--- a/src/a.clj\\n+++ b/src/a.clj\\n@@ -1 +1 @@\\n-old\\n+new\\n' ;;\n"
       "  esac ;;\n"
       "  \"pr view\") printf '{\"number\":7,\"url\":\"https://github.com/owner/repo/pull/7\",\"title\":\"Title\","
       "\"state\":\"OPEN\",\"isDraft\":false,\"headRefOid\":\"abc123\",\"headRefName\":\"feature\",\"baseRefName\":\"main\"}\\n' ;;\n"
       "  \"pr checkout\") touch checked-out.marker ;;\n"
       "  \"repo view\") printf 'owner/repo\\n' ;;\n"
       "  *) echo \"unexpected gh $*\" >&2; exit 2 ;;\n"
       "esac\n"))

(defn install-fake-gh! [root]
  (let [gh (io/file root ".fake-bin/gh")]
    (write! root ".fake-bin/gh" fake-gh)
    (.setExecutable gh true)
    (str (.getParentFile gh))))

(defn run-review
  "Runs the skill's shim in root, the way the skill text tells an agent to.
  extra-env is merged into the environment. Returns {:exit :out :err}."
  [root extra-env & args]
  (apply p/shell {:dir root :out :string :err :string :continue true :extra-env extra-env}
         (str shim) args))

(def diff-dir ".rule-fairy/review/diff")
(def bundle-path (str diff-dir "/rules.md"))

(defn read-meta [root]
  (json/parse-string (slurp (io/file root diff-dir "meta.json")) true))

(defn read-patch [root]
  (slurp (io/file root diff-dir "patch.diff")))

(defn header-count [patch path]
  (count (re-seq (re-pattern (str "(?m)^diff --git a/" (java.util.regex.Pattern/quote path) " ")) patch)))

(deftest shim-test
  (is (.canExecute shim) "scripts/run must be executable")
  (with-temp-repo
    (fn [root]
      (testing "no command prints usage and fails"
        (let [{:keys [exit err]} (run-review root {})]
          (is (= 1 exit))
          (is (str/includes? err "usage: review fetch"))))
      (testing "an unknown command fails"
        (let [{:keys [exit err]} (run-review root {} "frobnicate")]
          (is (= 1 exit))
          (is (str/includes? err "Unknown command: frobnicate")))))))

(deftest fetch-local-test
  (with-temp-repo
    (fn [root]
      (write! root "src/fäö.clj" "umlauts\n")
      (write! root "src/😀\"q.clj" "quoted\n")
      (write! root ".git/info/exclude" "generated/\n")
      (write! root "generated/example.clj" "forced\n")
      (write! root "generated/other.clj" "never staged\n")
      (git! root "add" "-f" "generated/example.clj")
      (git! root "add" "src/a.clj")
      (write! root "src/a.clj" "new\nnewer\n")
      (testing "staged, unstaged and untracked changes against HEAD, each path once"
        (let [{:keys [exit out]} (run-review root {} "fetch" "--local")
              patch (read-patch root)
              meta (read-meta root)]
          (is (zero? exit) out)
          (is (str/includes? out "5 changed paths"))
          (is (= 1 (header-count patch "src/a.clj")) "a file both staged and unstaged appears once")
          (is (str/includes? patch "+newer"))
          (is (= 1 (header-count patch "src/new file.clj")))
          (is (str/includes? patch "+untracked"))
          (is (= 1 (header-count patch "generated/example.clj"))
              "a staged addition appears even when its path is ignored")
          (is (not (str/includes? patch "generated/other.clj"))
              "an ignored file that was never staged stays out")
          (is (= 1 (header-count patch "src/fäö.clj")) "non-ASCII paths stay readable")
          (is (str/includes? patch "diff --git \"a/src/😀\\\"q.clj\" \"b/src/😀\\\"q.clj\"")
              "a path with a quote is quoted by git, its non-ASCII character left literal")
          (is (not (str/includes? patch "docs/x.md")))
          (is (= "local" (:mode meta)))
          (is (= (git-out root "rev-parse" "HEAD") (:head_ref_oid meta)))
          (is (= "owner/repo" (:repo_slug meta)))
          (is (true? (:working_tree_included meta)))
          (is (= ["generated/example.clj" "src/a.clj" "src/fäö.clj" "src/new file.clj" "src/😀\"q.clj"]
                 (:changed_paths meta))
              "the quoted header decodes to the real path")
          (is (= {:dirs [".cursor/rules"] :head_ref_oid (:head_ref_oid meta) :clean true}
                 (:rules meta)))
          (is (string? (:generated_at meta)))
          (is (= "*\n" (slurp (io/file root ".rule-fairy/review/.gitignore"))))
          (is (= "MM src/a.clj" (git-out root "status" "--porcelain" "--" "src/a.clj"))
              "the real index is untouched: still staged and modified again")
          (is (= "A  generated/example.clj" (git-out root "status" "--porcelain" "--" "generated/example.clj"))
              "the real index is untouched: the forced addition is still staged")))

      (testing "a second fetch replaces the artifacts and drops a stale bundle"
        (write! root bundle-path "stale bundle")
        (spit (io/file root ".cursor/rules/r.mdc") "---\nglobs: src/**\n---\n# R changed\n")
        (let [{:keys [exit]} (run-review root {} "fetch" "--local")]
          (is (zero? exit))
          (is (not (.exists (io/file root bundle-path))))
          (is (false? (get-in (read-meta root) [:rules :clean]))
              "a modified rule makes the rules revision unclean"))))))

(deftest fetch-base-test
  (with-temp-repo
    (fn [root]
      (io/make-parents (io/file root "docs/a b/renamed.md"))
      (.renameTo (io/file root "docs/base.md") (io/file root "docs/a b/renamed.md"))
      (testing "everything since the merge base, working tree included, a rename made outside git detected"
        (let [{:keys [exit out]} (run-review root {} "fetch" "--base" "main")
              patch (read-patch root)
              meta (read-meta root)]
          (is (zero? exit) out)
          (is (str/includes? patch "diff --git a/docs/base.md b/docs/a b/renamed.md"))
          (is (str/includes? patch "rename from docs/base.md"))
          (is (str/includes? patch "rename to docs/a b/renamed.md"))
          (is (str/includes? patch "diff --git a/docs/x.md b/docs/x.md") "committed on the branch")
          (is (str/includes? patch "diff --git a/src/a.clj b/src/a.clj"))
          (is (str/includes? patch "diff --git a/src/new file.clj b/src/new file.clj"))
          (is (= "base" (:mode meta)))
          (is (= "main" (:base_ref meta)))
          (is (= (git-out root "rev-parse" "main") (:base_ref_oid meta)))
          (is (= ["docs/a b/renamed.md" "docs/x.md" "src/a.clj" "src/new file.clj"] (:changed_paths meta))
              "the rename destination comes from its rename to line, spaces and all"))))))

(deftest fetch-before-first-commit-test
  (let [root (temp-dir "review-script-unborn")]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (write! root "src/a.clj" "first\n")
      (write! root ".cursor/rules/r.mdc" "# R")
      (git! root "add" "src/a.clj")
      (write! root "src/b.clj" "second\n")
      (testing "local mode diffs against the empty tree and records no head"
        (let [{:keys [exit out]} (run-review root {} "fetch" "--local")
              meta (read-meta root)]
          (is (zero? exit) out)
          (is (= [".cursor/rules/r.mdc" "src/a.clj" "src/b.clj"] (:changed_paths meta)))
          (is (nil? (:head_ref_oid meta)))
          (is (= {:dirs [".cursor/rules"] :head_ref_oid nil :clean false} (:rules meta))
              "untracked rules are uncommitted rules")))
      (testing "base mode needs a commit and says so"
        (let [{:keys [exit err]} (run-review root {} "fetch" "--base" "main")]
          (is (= 1 exit))
          (is (str/includes? err "git merge-base main HEAD failed"))))
      (finally
        (delete-tree! root)))))

(deftest fetch-pr-test
  (with-temp-repo
    (fn [root]
      (let [env {"PATH" (str (install-fake-gh! root) ":" (System/getenv "PATH"))}]
        (testing "the PR patch and metadata come from gh"
          (let [{:keys [exit out]} (run-review root env "fetch" "--pr" "7")
                meta (read-meta root)]
            (is (zero? exit) out)
            (is (str/includes? (read-patch root) "+new"))
            (is (= 1 (header-count (read-patch root) "src/a.clj"))
                "the aggregate diff is requested, not the per-commit series")
            (is (not (str/includes? (read-patch root) "+intermediate")))
            (is (= {:mode "pr" :pr_number 7 :pr_url "https://github.com/owner/repo/pull/7"
                    :pr_title "Title" :pr_state "OPEN" :pr_draft false
                    :head_ref_oid "abc123" :head_ref_name "feature" :base_ref_name "main"
                    :repo_slug "owner/repo" :checked_out false :working_tree_included false
                    :changed_paths ["src/a.clj"]}
                   (dissoc meta :rules :generated_at)))
            (is (not (.exists (io/file root "checked-out.marker"))))))

        (testing "--checkout checks the PR out first and records it"
          (let [{:keys [exit]} (run-review root env "fetch" "--pr" "7" "--checkout")]
            (is (zero? exit))
            (is (.exists (io/file root "checked-out.marker")))
            (is (true? (:checked_out (read-meta root))))))))))

(deftest fetch-arguments-test
  (with-temp-repo
    (fn [root]
      (doseq [[args message] [[[] "One of --pr <number>, --local or --base <ref> is required"]
                              [["--local" "--pr" "1"] "Only one of --pr, --local and --base"]
                              [["--local" "--checkout"] "--checkout only applies to --pr"]
                              [["--base"] "--base needs a value"]
                              [["--base" "--checkout"] "--base needs a value"]
                              [["--bogus"] "Unknown argument: --bogus"]]]
        (let [{:keys [exit err]} (apply run-review root {} "fetch" args)]
          (is (= 1 exit) (pr-str args))
          (is (str/includes? err message) (pr-str args))
          (is (not (.exists (io/file root ".rule-fairy")))))))))

(deftest bundle-and-clean-test
  (with-temp-repo
    (fn [root]
      (testing "bundle needs a patch"
        (let [{:keys [exit err]} (run-review root {} "bundle")]
          (is (= 1 exit))
          (is (str/includes? err "Patch not found"))
          (is (str/includes? err "review fetch"))))

      (testing "fetch then bundle produces the rules bundle from the patch"
        (is (zero? (:exit (run-review root {} "fetch" "--local"))))
        (let [{:keys [exit out]} (run-review root {} "bundle")
              bundle (slurp (io/file root bundle-path))]
          (is (zero? exit) out)
          (is (str/includes? out "1 of 1 rules, 1 instruction files, 2 changed paths, 0 duplicates omitted"))
          (is (str/includes? bundle "# CLAUDE.md\n\nRepository instructions."))
          (is (str/includes? bundle (str "# r.mdc\n\n"
                                         "*Scope: globs `src/**`; matched `src/a.clj`, `src/new file.clj`.*\n\n"
                                         "# R\n\nRule body.")))))

      (testing "bundle re-checks the guidance's cleanliness over the files it used"
        (is (true? (get-in (read-meta root) [:rules :clean])))
        (write! root "CLAUDE.md" "Repository instructions, edited locally.")
        (is (zero? (:exit (run-review root {} "bundle"))))
        (is (false? (get-in (read-meta root) [:rules :clean]))
            "an edited instruction file the bundle used makes the guidance unclean")
        (git! root "checkout" "-q" "--" "CLAUDE.md"))

      (testing "bundle again, from another patch, replaces the bundle"
        (write! root "other.diff" "diff --git a/README.md b/README.md\n")
        (let [{:keys [exit out]} (run-review root {} "bundle" "--patch" "other.diff")]
          (is (zero? exit) out)
          (is (str/includes? out "0 of 1 rules"))
          (is (not (str/includes? (slurp (io/file root bundle-path)) "# R\n")))))

      (testing "clean removes the diff artifacts and nothing else"
        (let [{:keys [exit out]} (run-review root {} "clean")]
          (is (zero? exit) out)
          (is (str/includes? out "Removed"))
          (is (not (.exists (io/file root diff-dir))))
          (is (.exists (io/file root ".rule-fairy/review/.gitignore")))
          (is (.exists (io/file root "other.diff")))))

      (testing "clean is fine with nothing to remove and takes no arguments"
        (is (zero? (:exit (run-review root {} "clean"))))
        (let [{:keys [exit err]} (run-review root {} "clean" "now")]
          (is (= 1 exit))
          (is (str/includes? err "clean takes no arguments"))))

      (testing "a rendering error leaves no bundle behind"
        (is (zero? (:exit (run-review root {} "fetch" "--local"))))
        (write! root ".cursor/rules/broken.mdc" "---\nalwaysApply: true\n---\n# Broken\n\n@doc/missing.md\n")
        (let [{:keys [exit err]} (run-review root {} "bundle")]
          (is (= 1 exit))
          (is (str/includes? err "Rule import does not exist"))
          (is (not (.exists (io/file root bundle-path)))))))))

(let [{:keys [fail error]} (run-tests 'review-script-test)]
  (System/exit (+ fail error)))

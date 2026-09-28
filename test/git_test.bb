#!/usr/bin/env bb

(ns git-test
  (:require [rule-fairy.git :as git]
            [rule-fairy.bundle :as bundle]
            [rule-fairy.plan :as plan]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]))

(defn with-temp-dir [f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "git-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f root)
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(defn git! [root & args]
  (apply p/shell {:dir root :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(deftest outside-a-repository-test
  (with-temp-dir
    (fn [root]
      (is (nil? (git/head-oid root)))
      (is (nil? (git/repo-slug root)))
      (is (nil? (git/clean? root ["."])))
      (testing "the rules revision degrades to nil revision facts"
        (.mkdirs (io/file root ".cursor/rules"))
        (is (= {:dirs [".cursor/rules"] :head_ref_oid nil :clean nil}
               (bundle/rules-revision root)))))))

(deftest checkout-root-test
  (with-temp-dir
    (fn [root]
      (testing "no .git anywhere up the tree"
        (is (nil? (git/checkout-root (io/file root "a/b")))))
      (git! root "init" "-q" "--initial-branch=main")
      (.mkdirs (io/file root "a/b"))
      (testing "the root itself and a nested directory"
        (is (= root (git/checkout-root root)))
        (is (= root (git/checkout-root (io/file root "a/b"))))
        (is (= root (git/checkout-root (str root "/a/b")))))
      (testing "a .git file, as a worktree or submodule carries, counts"
        (.mkdirs (io/file root "a/sub/deep"))
        (spit (io/file root "a/sub/.git") "gitdir: elsewhere\n")
        (is (= (io/file root "a/sub") (git/checkout-root (io/file root "a/sub/deep"))))))))

(defn write! [root path content]
  (let [file (io/file root path)]
    (io/make-parents file)
    (spit file content)))

(defn touch! [root path]
  (write! root path ""))

(deftest rule-path-test
  (with-temp-dir
    (fn [root]
      (let [rule-path (fn [project file]
                        (str (git/rule-path (.toPath (io/file root project))
                                            (.toPath (io/file root file)))))]
        (touch! root "repo/.git/HEAD")
        (touch! root "repo/backend/src/a.clj")
        (touch! root "repo/frontend/x.js")
        ;; A linked worktree carries a .git file naming the repository's git directory.
        (write! root "repo/.claude/worktrees/w/.git" "gitdir: ../../../.git/worktrees/w")
        (touch! root "repo/.claude/worktrees/w/backend/src/b.clj")
        (touch! root "repo/.claude/worktrees/w/src/c.clj")
        (touch! root "other/.git/HEAD")
        (touch! root "other/src/d.clj")
        (touch! root "plain/src/e.clj")
        (testing "a project at its repository root"
          (is (= "backend/src/a.clj" (rule-path "repo" "repo/backend/src/a.clj"))))
        (testing "a project in a subdirectory of its repository drops that subdirectory"
          (is (= "src/a.clj" (rule-path "repo/backend" "repo/backend/src/a.clj"))))
        (testing "an edit in a linked worktree is relative to the worktree, less the same subdirectory"
          (is (= "src/c.clj" (rule-path "repo" "repo/.claude/worktrees/w/src/c.clj")))
          (is (= "src/b.clj" (rule-path "repo/backend" "repo/.claude/worktrees/w/backend/src/b.clj"))))
        (testing "a file elsewhere in the repository keeps its checkout-relative path"
          (is (= "frontend/x.js" (rule-path "repo/backend" "repo/frontend/x.js"))))
        (testing "a file in another checkout is relative to that checkout"
          (is (= "src/d.clj" (rule-path "repo" "other/src/d.clj"))))
        (testing "outside git the project directory is the root"
          (is (= "src/e.clj" (rule-path "plain" "plain/src/e.clj"))))))))

(defn symlink! [root link target]
  (io/make-parents (io/file root link))
  (java.nio.file.Files/createSymbolicLink (.toPath (io/file root link))
                                          (.toPath (io/file target))
                                          (make-array java.nio.file.attribute.FileAttribute 0)))

(deftest real-path-test
  (with-temp-dir
    (fn [root]
      (write! root "docs/guide.md" "nested")
      (touch! root "docs/nested/x.md")
      (write! root "guide.md" "root")
      (symlink! root "linked" "docs/nested")
      (let [real (fn [path] (str (git/real-path (.toPath (io/file root path)))))
            real-root (str (git/real-path (.toPath root)))]
        (testing "a `..` after a link steps out of the link's target, for an existing file and for one still to be created"
          (is (= (str real-root "/docs/guide.md") (real "linked/../guide.md")))
          (is (= (str real-root "/docs/new.md") (real "linked/../new.md"))))
        (testing "locate keeps the written form lexical and the real form as the filesystem reads it"
          (is (= {:written "guide.md" :real "docs/guide.md"} (git/locate root "linked/../guide.md")))
          (is (= {:written "guide.md" :real "guide.md"} (git/locate root "guide.md"))))))))

(defn relink! [root link target]
  (.delete (io/file root link))
  (symlink! root link target))

(deftest linked-guidance-test
  (with-temp-dir
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (touch! root "src/a.clj")
      (write! root "docs/guidance.md" "# Guidance")
      (write! root "docs/shared.md" "# Shared\n")
      (write! root "docs/rules/r.mdc" "---\nglobs: src/**\n---\n# R\n\n@docs/link.md\n")
      (write! root "docs/linked-rule.mdc" "# Linked\n")
      (write! root "settings/rules.edn" "{}")
      (symlink! root "CLAUDE.md" "docs/guidance.md")
      (symlink! root "docs/link.md" "shared.md")
      (symlink! root ".cursor/rules" "../docs/rules")
      (symlink! root "docs/rules/linked.mdc" "../linked-rule.mdc")
      (symlink! root "rule-fairy.edn" "settings/rules.edn")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (let [{:keys [input-paths]} (bundle/build root ["src/a.clj"])
            clean-after-editing (fn [path content inputs]
                                  (spit (io/file root path) content)
                                  (let [clean (:clean (bundle/rules-revision root inputs))]
                                    (git! root "checkout" "-q" "--" path)
                                    clean))]
        (testing "the bundle's inputs are the paths as displayed and as reached; the revision check follows their links"
          (is (= ["CLAUDE.md" (str (io/file root "CLAUDE.md")) "docs/link.md" "rule-fairy.edn"] input-paths))
          (is (true? (:clean (bundle/rules-revision root input-paths)))))
        (testing "an edit to a linked instruction file's target makes the guidance unclean"
          (is (false? (clean-after-editing "docs/guidance.md" "# Guidance, edited" input-paths))))
        (testing "so does an edit to a linked documentation import's target"
          (is (false? (clean-after-editing "docs/shared.md" "# Shared, edited\n" input-paths))))
        (testing "a linked rules directory is checked where it resolves to, without the bundle's inputs"
          (is (false? (clean-after-editing "docs/rules/r.mdc" "---\nglobs: src/**\n---\n# R, edited\n\n@docs/link.md\n" nil))))
        (testing "so is a linked rule file, whether or not it applies"
          (is (false? (clean-after-editing "docs/linked-rule.mdc" "# Linked, edited\n" nil))))
        (testing "and the linked configuration file"
          (is (false? (clean-after-editing "settings/rules.edn" "{:instructions {:files []}}" nil))))
        (with-temp-dir
          (fn [outside]
            (write! outside "guidance.md" "# Outside")
            (write! outside "rules/o.mdc" "# O\n")
            (testing "a rule file linked to outside the checkout leaves cleanliness unknown"
              (symlink! root "docs/rules/outside.mdc" (str (io/file outside "rules/o.mdc")))
              (is (nil? (:clean (bundle/rules-revision root))))
              (.delete (io/file root "docs/rules/outside.mdc"))
              (is (true? (:clean (bundle/rules-revision root)))))
            (testing "so does an instruction file linked to outside"
              (relink! root "CLAUDE.md" (str (io/file outside "guidance.md")))
              (is (nil? (:clean (bundle/rules-revision root (:input-paths (bundle/build root ["src/a.clj"]))))))
              (relink! root "CLAUDE.md" "docs/guidance.md"))
            (testing "and a committed instruction file importing guidance from outside"
              (.delete (io/file root "CLAUDE.md"))
              (write! root "CLAUDE.md" (str "# Guidance\n\n@" (io/file outside "guidance.md") "\n"))
              (git! root "add" "-A")
              (git! root "commit" "-q" "-m" "external import")
              (let [{:keys [instruction-paths input-paths]} (bundle/build root ["src/a.clj"])]
                (is (= ["CLAUDE.md" (str (io/file outside "guidance.md"))] instruction-paths))
                (is (nil? (:clean (bundle/rules-revision root input-paths)))))
              (git! root "reset" "-q" "--hard" "HEAD~1"))
            (testing "and a rules directory linked to outside"
              (relink! root ".cursor/rules" (str (io/file outside "rules")))
              (is (nil? (:clean (bundle/rules-revision root))))
              (relink! root ".cursor/rules" "../docs/rules")
              (is (true? (:clean (bundle/rules-revision root input-paths)))))))))))

(deftest parent-step-import-test
  (with-temp-dir
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (touch! root "src/a.clj")
      (write! root "CLAUDE.md" "# Root\n\n@linked/../guide.md\n@guide.md\n")
      (write! root "docs/guide.md" "# Nested guide\n")
      (touch! root "docs/nested/x.md")
      (write! root "guide.md" "# Root guide\n")
      (symlink! root "linked" "docs/nested")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (let [{:keys [input-paths]} (bundle/build root ["src/a.clj"])]
        (testing "an import with a `..` after a link is an input as reached, uncollapsed"
          (is (some #{(str (io/file root "linked/../guide.md"))} input-paths))
          (is (true? (:clean (bundle/rules-revision root input-paths)))))
        (testing "an edit to the file it really reaches makes the guidance unclean"
          (spit (io/file root "docs/guide.md") "# Nested guide, edited\n")
          (is (false? (:clean (bundle/rules-revision root input-paths)))))))))

(deftest shared-rules-in-a-checkout-test
  (with-temp-dir
    (fn [checkout]
      (git! checkout "init" "-q" "--initial-branch=main")
      (write! checkout "shared/rules/r.mdc" "---\nglobs: src/**\n---\n# Shared\n")
      (touch! checkout "app/src/a.clj")
      (symlink! checkout "app/.cursor/rules" "../../shared/rules")
      (git! checkout "add" "-A")
      (git! checkout "commit" "-q" "-m" "base")
      (let [app (io/file checkout "app")]
        (testing "a subproject's rules linked to elsewhere in its checkout are checked there, through the parent"
          (is (= {:written ".cursor/rules" :real "../shared/rules"} (git/locate app ".cursor/rules")))
          (is (true? (:clean (bundle/rules-revision app))))
          (spit (io/file checkout "shared/rules/r.mdc") "---\nglobs: src/**\n---\n# Shared, edited\n")
          (is (false? (:clean (bundle/rules-revision app))))
          (git! checkout "checkout" "-q" "--" "shared/rules/r.mdc"))
        (testing "an ignored rule in the shared directory is seen through the parent too"
          (write! checkout ".gitignore" "shared/rules/local.mdc\n")
          (git! checkout "add" "-A")
          (git! checkout "commit" "-q" "-m" "ignore")
          (is (true? (:clean (bundle/rules-revision app))))
          (write! checkout "shared/rules/local.mdc" "# Local\n")
          (is (false? (:clean (bundle/rules-revision app)))))
        (testing "a plan path elsewhere in the checkout is still outside the project"
          (is (nil? (plan/project-path app (str (io/file checkout "shared/rules/r.mdc")))))
          (is (= "src/a.clj" (plan/project-path app (str (io/file app "src/a.clj"))))))))))

(deftest ignored-guidance-test
  (with-temp-dir
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (touch! root "src/a.clj")
      (write! root ".gitignore" ".cursor/\nrule-fairy.edn\n.DS_Store\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (write! root ".cursor/rules/r.mdc" "---\nglobs: src/**\n---\n# R\n")
      (write! root "rule-fairy.edn" "{}")
      (let [{:keys [rule-names input-paths]} (bundle/build root ["src/a.clj"])]
        (testing "ignored rules and configuration shape the bundle but were never committed, so the guidance is unclean"
          (is (= ["r.mdc"] rule-names))
          (is (false? (:clean (bundle/rules-revision root input-paths))))
          (is (true? (git/clean? root ["src/a.clj"]))))
        (testing "a user setting that hides untracked files hides neither ignored nor untracked guidance from the check"
          (git! root "config" "status.showUntrackedFiles" "no")
          (is (false? (:clean (bundle/rules-revision root input-paths))))
          (touch! root "docs/new.md")
          (is (false? (git/clean? root ["docs/new.md"])))
          (.delete (io/file root "docs/new.md"))
          (git! root "config" "--unset" "status.showUntrackedFiles"))
        (testing "committed, they are clean, and ignored junk inside the rules directory does not count"
          (write! root ".gitignore" ".DS_Store\n")
          (git! root "add" "-A")
          (git! root "commit" "-q" "-m" "guidance")
          (touch! root ".cursor/rules/.DS_Store")
          (is (true? (:clean (bundle/rules-revision root input-paths)))))))))

(deftest configured-rules-directories-test
  (with-temp-dir
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (write! root ".cursor/rules/r.mdc" "# R")
      (write! root "rule-fairy.edn" (pr-str {:rules {:dirs [(str (io/file root ".cursor/rules"))]}}))
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (testing "an absolute directory inside the checkout is relativised, the root given as the scripts give it"
        (let [{:keys [dirs clean]} (bundle/rules-revision (str root "/."))]
          (is (= [".cursor/rules"] dirs))
          (is (true? clean))))
      (with-temp-dir
        (fn [outside]
          (write! outside "rules/o.mdc" "# O")
          (write! root "rule-fairy.edn" (pr-str {:rules {:dirs [".cursor/rules" (str (io/file outside "rules"))]}}))
          (git! root "add" "-A")
          (git! root "commit" "-q" "-m" "external rules")
          (testing "a directory outside the checkout is named as configured and leaves cleanliness unknown"
            (let [{:keys [dirs clean]} (bundle/rules-revision (str root "/."))]
              (is (= [".cursor/rules" (str (io/file outside "rules"))] dirs))
              (is (nil? clean)))))))))

(deftest repository-test
  (with-temp-dir
    (fn [root]
      (git! root "init" "-q" "--initial-branch=main")
      (spit (io/file root "a.txt") "a")
      (io/make-parents (io/file root ".cursor/rules/r.mdc"))
      (spit (io/file root ".cursor/rules/r.mdc") "# R")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "init")
      (testing "head, cleanliness and the rules revision"
        (let [head (git/head-oid root)]
          (is (re-matches #"[0-9a-f]{40}" head))
          (is (true? (git/clean? root [".cursor/rules"])))
          (is (= {:dirs [".cursor/rules"] :head_ref_oid head :clean true}
                 (bundle/rules-revision root)))
          (testing "the bundle's other inputs and the configuration file join the check"
            (is (true? (:clean (bundle/rules-revision root ["a.txt"]))))
            (spit (io/file root "AGENTS.md") "# Agents")
            (is (false? (:clean (bundle/rules-revision root ["AGENTS.md"]))))
            (is (true? (:clean (bundle/rules-revision root))))
            (.delete (io/file root "AGENTS.md"))
            (spit (io/file root "rule-fairy.edn") "{}")
            (is (false? (:clean (bundle/rules-revision root))))
            (.delete (io/file root "rule-fairy.edn")))
          (spit (io/file root ".cursor/rules/r.mdc") "# R changed")
          (is (false? (git/clean? root [".cursor/rules"])))
          (is (true? (git/clean? root ["a.txt"])))
          (testing "a tracked rules directory deleted locally is an uncommitted change"
            (doseq [file (reverse (file-seq (io/file root ".cursor/rules")))]
              (.delete file))
            (is (= {:dirs [".cursor/rules"] :head_ref_oid head :clean false}
                   (bundle/rules-revision root))))
          (testing "a path git never knew is not a change"
            (is (true? (git/clean? root ["nope/dir"]))))))
      (testing "the origin URL forms GitHub uses all yield owner/repo"
        (git! root "remote" "add" "origin" "https://example.com/placeholder.git")
        (doseq [url ["git@github.com:owner/repo.git"
                     "https://github.com/owner/repo.git"
                     "https://github.com/owner/repo"
                     "ssh://git@github.com/owner/repo.git"]]
          (git! root "remote" "set-url" "origin" url)
          (is (= "owner/repo" (git/repo-slug root)) url)))
      (testing "a non-GitHub origin has no slug"
        (git! root "remote" "set-url" "origin" "https://gitlab.com/owner/repo.git")
        (is (nil? (git/repo-slug root))))
      (testing "a failing command names itself"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"git rev-parse nope failed"
                              (git/git root "rev-parse" "nope")))))))

(let [{:keys [fail error]} (run-tests 'git-test)]
  (System/exit (+ fail error)))

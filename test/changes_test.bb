#!/usr/bin/env bb

(ns changes-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [rule-fairy.changes :as changes]
            [rule-fairy.git :as git]))

(defn git! [root & args]
  (apply p/shell {:dir (str root) :out :string :err :string}
         "git" "-c" "user.email=test@example.com" "-c" "user.name=Test" args))

(defn write! [root path content]
  (let [file (io/file (str root) path)]
    (io/make-parents file)
    (spit file content)))

(defn with-temp-repo
  "A repository with a committed file in the root and in a subdirectory."
  [f]
  (let [root (fs/create-temp-dir {:prefix "rule-fairy-changes-test"})]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (write! root ".gitignore" "ignored/\n")
      (write! root "a.txt" "one\n")
      (write! root "moved.txt" "moving\n")
      (write! root "gone.txt" "x\n")
      (write! root "sub/tracked.clj" "(ns tracked)\n")
      (write! root "sub/renamed.clj" "(ns renamed)\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (f root)
      (finally
        (fs/delete-tree root)))))

(deftest status-entries-test
  (let [nul (str (char 0))]
    (is (= [[" M" "a.txt"] ["??" "new file.clj"] ["R " "new.clj"] ["A " "added.clj"]]
           (changes/status-entries
            (str " M a.txt" nul "?? new file.clj" nul "R  new.clj" nul "old.clj" nul "A  added.clj" nul)))
        "a rename keeps its new path and drops the original that follows it")
    (is (= [] (changes/status-entries "")))))

(deftest changed-since-test
  (with-temp-repo
    (fn [root]
      (write! root "a.txt" "modified before the marker\n")
      (Thread/sleep 1100)
      (let [marker (System/currentTimeMillis)]
        (Thread/sleep 1100)
        (write! root "sub/tracked.clj" "(ns tracked) :edited\n")
        (write! root "sub/new file.clj" "(ns new)\n")
        (write! root "ignored/build.js" "generated")
        (fs/delete (fs/path root "gone.txt"))
        (fs/move (fs/path root "moved.txt") (fs/path root "sub/moved.txt"))
        (git! root "mv" "sub/renamed.clj" "sub/was-renamed.clj")
        (testing "modified, new, moved and renamed files after the marker; ignored, deleted and earlier edits left out"
          (is (= ["sub/moved.txt" "sub/new file.clj" "sub/tracked.clj" "sub/was-renamed.clj"]
                 (changes/changed-since (str root) marker))))
        (testing "a project root inside the repository sees its own files, relative to itself"
          (write! root "b.txt" "root-level, after the marker\n")
          (is (= ["moved.txt" "new file.clj" "tracked.clj" "was-renamed.clj"]
                 (changes/changed-since (str (fs/path root "sub")) marker))))
        (testing "a marker in the future reports nothing"
          (is (= [] (changes/changed-since (str root) (+ (System/currentTimeMillis) 60000)))))))))

(deftest leading-space-entries-test
  (let [root (fs/create-temp-dir {:prefix "rule-fairy-changes-tracked"})]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (write! root "tracked.clj" "(ns tracked)\n")
      (write! root " leading.clj" "(ns leading)\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "base")
      (write! root "tracked.clj" "(ns tracked) :edited\n")
      (is (= ["tracked.clj"] (changes/changed-since (str root) 0))
          "an unstaged edit to a tracked file, whose status entry begins with a space, is the only change")
      (write! root " leading.clj" "(ns leading) :edited\n")
      (is (= [" leading.clj" "tracked.clj"] (changes/changed-since (str root) 0))
          "a file name keeps its leading space")
      (finally
        (fs/delete-tree root)))))

(deftest check-and-delivered-test
  (with-temp-repo
    (fn [root]
      (let [started (changes/check {} (str root))]
        (is (= [] (:paths started)) "the first check starts the marker and finds nothing")
        (is (number? (get-in started [:state :shell-check])))
        (write! root "sub/new.clj" "(ns new)\n")
        (write! root "sub/other.clj" "(ns other)\n")
        (let [checked (changes/check (:state started) (str root))]
          (is (= ["sub/new.clj" "sub/other.clj"] (:paths checked)))
          (is (= (:paths checked) (get-in checked [:state :shell-pending])))
          (is (= (:paths checked) (:paths (changes/check (:state checked) (str root))))
              "undelivered paths stay pending across checks")
          (is (= ["sub/other.clj"]
                 (:shell-pending (changes/delivered (:state checked) ["sub/new.clj"])))))))))

(deftest head-moves-report-nothing-test
  (with-temp-repo
    (fn [root]
      (git! root "switch" "-q" "-c" "other")
      (write! root "sub/on-other.clj" "(ns on-other)\n")
      (write! root "a.txt" "rewritten on other\n")
      (git! root "add" "-A")
      (git! root "commit" "-q" "-m" "on other")
      (git! root "switch" "-q" "main")
      (write! root "sub/written-earlier.clj" "(ns written-earlier)\n")
      (let [started (changes/check {} (str root))]
        (testing "committing a file written before the check reports nothing: the check reads the working tree, not the commits"
          (git! root "add" "-A")
          (git! root "commit" "-q" "-m" "written before the check")
          (is (= [] (:paths (changes/check (:state started) (str root))))))
        (testing "a branch switch that rewrites tracked files reports nothing"
          (git! root "switch" "-q" "other")
          (is (= [] (:paths (changes/check (:state started) (str root))))))))))

(deftest write-during-check-test
  (with-temp-repo
    (fn [root]
      (let [started (changes/check {} (str root))
            run git/run
            slow-status (fn [dir command & options]
                          (let [output (apply run dir command options)]
                            (when (= "status" (second command))
                              (write! root "sub/during-scan.clj" "(ns during)\n")
                              (Thread/sleep 1100))
                            output))
            scanned (with-redefs [git/run slow-status]
                      (changes/check (:state started) (str root)))]
        (is (= [] (:paths scanned)) "the write landed after the listing")
        (is (= ["sub/during-scan.clj"]
               (:paths (changes/check (:state scanned) (str root))))
            "the marker precedes the scan, so the next check sees a write made during it")))))

(deftest unborn-repository-test
  (let [root (fs/create-temp-dir {:prefix "rule-fairy-changes-unborn"})]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (write! root "a.clj" "(ns a)\n")
      (testing "a repository without a commit yet"
        (is (= ["a.clj"] (changes/changed-since (str root) 0)))
        (let [started (changes/check {} (str root))]
          (is (= [] (:paths started)))
          (is (= ["a.clj"]
                 (:paths (changes/check (assoc (:state started) :shell-check 0) (str root)))))))
      (finally
        (fs/delete-tree root)))))

(deftest shell-hook-config-test
  (let [dir (fs/create-temp-dir {:prefix "rule-fairy-changes-config"})]
    (try
      (testing "defaults: on for Claude Code, off for Codex"
        (is (true? (changes/shell-hook-enabled? (str dir) :claude)))
        (is (false? (changes/shell-hook-enabled? (str dir) :codex))))
      (testing "rule-fairy.edn sets each harness on its own"
        (write! dir "rule-fairy.edn" "{:shell-hook {:claude false :codex true}}")
        (is (false? (changes/shell-hook-enabled? (str dir) :claude)))
        (is (true? (changes/shell-hook-enabled? (str dir) :codex)))
        (write! dir "rule-fairy.edn" "{:shell-hook {:codex true}}")
        (is (true? (changes/shell-hook-enabled? (str dir) :claude))
            "a harness the map leaves out keeps its default"))
      (testing "invalid settings fail with the file named"
        (doseq [[config message] [["{:shell-hook true}" #":shell-hook in rule-fairy.edn must be a map"]
                                  ["{:shell-hook {:cursor true}}" #"Unknown :shell-hook keys in rule-fairy.edn: :cursor"]
                                  ["{:shell-hook {:codex \"yes\"}}" #":shell-hook values in rule-fairy.edn must be true or false"]]]
          (write! dir "rule-fairy.edn" config)
          (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                                (changes/shell-hook-enabled? (str dir) :codex))
              config)))
      (finally
        (fs/delete-tree dir)))))

(deftest trailing-space-in-checkout-path-test
  (let [tmp (fs/create-temp-dir {:prefix "rule-fairy-changes-space"})
        root (fs/create-dirs (fs/path tmp "checkout "))]
    (try
      (git! root "init" "-q" "--initial-branch=main")
      (write! root "source.clj" "(ns source)\n")
      (is (= ["source.clj"] (changes/changed-since (str root) 0))
          "the checkout path keeps its trailing space; only git's line terminator is dropped")
      (finally
        (fs/delete-tree tmp)))))

(deftest outside-git-test
  (let [dir (fs/create-temp-dir {:prefix "rule-fairy-changes-plain"})]
    (try
      (write! dir "a.txt" "x")
      (is (nil? (changes/changed-since (str dir) 0)))
      (finally
        (fs/delete-tree dir)))))

(let [{:keys [fail error]} (run-tests 'changes-test)]
  (System/exit (+ fail error)))

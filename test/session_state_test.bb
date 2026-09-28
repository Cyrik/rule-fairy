#!/usr/bin/env bb

(ns session-state-test
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [rule-fairy.session-state :as session-state]))

(defn with-temp-dir [f]
  (let [root (.toFile
              (java.nio.file.Files/createTempDirectory
               "rule-fairy-session-state-test"
               (make-array java.nio.file.attribute.FileAttribute 0)))]
    (try
      (f (str root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete file))))))

(deftest state-roundtrip-test
  (with-temp-dir
    (fn [state-dir]
      (testing "a fresh session has an empty sizes map"
        (is (= {:sizes {}} (session-state/load-state state-dir "s"))))
      (testing "saving stamps a timestamp and creates a self-ignoring directory"
        (session-state/save-state! state-dir "s" {:sizes {"a.mdc" {:size 1 :compaction-count 0}}})
        (let [state (session-state/load-state state-dir "s")]
          (is (= {"a.mdc" {:size 1 :compaction-count 0}} (:sizes state)))
          (is (number? (:ts state))))
        (is (= "*\n" (slurp (io/file state-dir ".gitignore"))))))))

(deftest cleanup-test
  (with-temp-dir
    (fn [root]
      (let [state-dir (str (io/file root ".rule-fairy" "claude"))
            stale (- (System/currentTimeMillis) session-state/state-ttl-ms 1000)
            write! (fn [file content old?]
                     (io/make-parents file)
                     (spit file content)
                     (when old? (.setLastModified file stale))
                     file)
            in-state (fn [path] (io/file state-dir path))
            old-session (write! (in-state "old.edn") "{:sizes {}}" true)
            old-batch (write! (in-state "batches/old/event.edn") "{}" true)
            fresh-session (write! (in-state "fresh.edn") "{:sizes {}}" false)
            old-cache (write! (in-state "globs-cache.edn") "{}" true)
            old-lock (write! (in-state "locks/old.lock") "" true)
            old-temp (write! (in-state "old.edn.123.tmp") "{:sizes" true)
            old-outside (write! (io/file root "outside/old.edn") "{}" true)]
        (fs/create-sym-link (in-state "linked") (io/file root "outside"))
        (session-state/save-state! state-dir "new" {:sizes {}})
        (testing "stale session files, batches and interrupted writes are removed"
          (is (not (.exists old-session)))
          (is (not (.exists old-batch)))
          (is (not (.exists old-temp))))
        (testing "fresh files, the glob cache, and non-EDN files stay"
          (is (.exists fresh-session))
          (is (.exists old-cache))
          (is (.exists old-lock)))
        (testing "a symlink out of the state directory is not followed"
          (is (.exists old-outside)))))))

(deftest replace-file-test
  (with-temp-dir
    (fn [state-dir]
      (let [file (io/file state-dir "s.edn")]
        (session-state/replace-file! file "{:a 1}")
        (is (= "{:a 1}" (slurp file)))
        (session-state/replace-file! file "{:a 2}")
        (testing "the new content replaces the old and no temporary file stays behind"
          (is (= "{:a 2}" (slurp file)))
          (is (= ["s.edn"] (map #(.getName %) (.listFiles (io/file state-dir))))))))))

(deftest record-plugin-root-test
  (with-temp-dir
    (fn [state-dir]
      (let [file (io/file state-dir "plugin-root")
            whole-second (* 1000 (quot (- (System/currentTimeMillis) 60000) 1000))]
        (testing "a nil root, a script run outside the launcher, records nothing"
          (session-state/record-plugin-root! state-dir nil)
          (is (not (.exists file))))
        (testing "the root is one line in a self-ignoring directory"
          (session-state/record-plugin-root! state-dir "/plugins/rule-fairy/0.1.0")
          (is (= "/plugins/rule-fairy/0.1.0\n" (slurp file)))
          (is (= "*\n" (slurp (io/file state-dir ".gitignore")))))
        (testing "an unchanged root leaves the file untouched"
          (.setLastModified file whole-second)
          (session-state/record-plugin-root! state-dir "/plugins/rule-fairy/0.1.0")
          (is (= whole-second (.lastModified file))))
        (testing "a new root replaces the file, leaving no temporary file behind"
          (session-state/record-plugin-root! state-dir "/plugins/rule-fairy/0.2.0")
          (is (= "/plugins/rule-fairy/0.2.0\n" (slurp file)))
          (is (= [".gitignore" "plugin-root"]
                 (sort (map #(.getName %) (.listFiles (io/file state-dir)))))))))))

(deftest with-lock-test
  (with-temp-dir
    (fn [state-dir]
      (testing "f runs under a lock file in the state directory and its value comes back"
        (is (= :done (session-state/with-lock state-dir "s" (fn [] :done))))
        (is (.isFile (io/file state-dir "locks/s.lock")))
        (is (= "*\n" (slurp (io/file state-dir ".gitignore"))))))))

(deftest needs-reinjection-test
  (let [state {:sizes {"a.mdc" {:size 100 :compaction-count 1}}}]
    (is (session-state/needs-reinjection? state "new.mdc" {:size 100 :compaction-count 1}))
    (is (not (session-state/needs-reinjection? state "a.mdc" {:size 101 :compaction-count 1})))
    (is (session-state/needs-reinjection? state "a.mdc" {:size 101 :compaction-count 2}))
    (is (session-state/needs-reinjection?
         state "a.mdc" {:size (+ 100 session-state/reinjection-threshold-bytes) :compaction-count 1}))
    (testing "legacy numeric entries compare as pre-compaction metrics"
      (is (session-state/needs-reinjection? {:sizes {"a.mdc" 100}} "a.mdc" {:size 101 :compaction-count 1})))))

(deftest transcript-metrics-test
  (with-temp-dir
    (fn [state-dir]
      (let [transcript (java.io.File/createTempFile "rule-fairy-session-state" ".jsonl")
            pattern #"\"type\":\"compacted\""]
        (try
          (spit transcript "{\"type\":\"compacted\"}\n")
          (testing "nil and missing transcripts yield no metrics"
            (is (nil? (session-state/transcript-metrics! state-dir "s" nil pattern)))
            (is (nil? (session-state/transcript-metrics! state-dir "s" "/nonexistent.jsonl" pattern))))
          (testing "the cursor is persisted and survives marking rules injected"
            (is (= {:size (.length transcript) :compaction-count 1}
                   (session-state/transcript-metrics! state-dir "s" transcript pattern)))
            (session-state/mark-injected! state-dir "s" (session-state/load-state state-dir "s")
                                          ["a.mdc"] {:size 1 :compaction-count 1})
            (is (= {:scanned-bytes (.length transcript) :compaction-count 1}
                   (:transcript-scan (session-state/load-state state-dir "s"))))
            (is (= {:size 1 :compaction-count 1}
                   (get-in (session-state/load-state state-dir "s") [:sizes "a.mdc"]))))
          (finally
            (.delete transcript)))))))

(let [{:keys [fail error]} (run-tests 'session-state-test)]
  (System/exit (+ fail error)))

#!/usr/bin/env bb

(ns transcript-test
  (:require [rule-fairy.transcript :as transcript]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]))

(def claude-pattern #"\"isCompactSummary\"\s*:\s*true")
(def codex-pattern #"\"type\":\"compacted\"")

(defn with-transcript [f]
  (let [file (java.io.File/createTempFile "transcript-test" ".jsonl")]
    (try
      (f file (.toPath file))
      (finally
        (.delete file)))))

(deftest scan-test
  (with-transcript
    (fn [file path]
      (testing "an empty transcript yields an empty cursor"
        (is (= {:cursor transcript/empty-cursor
                :metrics {:size 0 :compaction-count 0}}
               (transcript/scan path claude-pattern transcript/empty-cursor))))

      (testing "complete lines are counted and the cursor reaches the end"
        (spit file (str "{\"type\":\"user\",\"isCompactSummary\":false}\n"
                        "{\"type\":\"user\",\"isCompactSummary\":true}\n"))
        (let [{:keys [cursor metrics]} (transcript/scan path claude-pattern transcript/empty-cursor)]
          (is (= {:scanned-bytes (.length file) :compaction-count 1} cursor))
          (is (= {:size (.length file) :compaction-count 1} metrics))

          (testing "a rescan from the same cursor counts nothing twice"
            (is (= cursor (:cursor (transcript/scan path claude-pattern cursor)))))

          (testing "a partial trailing line is left for the next scan"
            (let [scanned-before (.length file)]
              (spit file "{\"type\":\"user\",\"isCompactSummary\":tr" :append true)
              (let [{partial-cursor :cursor partial-metrics :metrics}
                    (transcript/scan path claude-pattern cursor)]
                (is (= scanned-before (:scanned-bytes partial-cursor)))
                (is (= 1 (:compaction-count partial-cursor)))
                (is (= (.length file) (:size partial-metrics)))

                (testing "completing the line counts it exactly once"
                  (spit file "ue}\n" :append true)
                  (let [{:keys [cursor metrics]} (transcript/scan path claude-pattern partial-cursor)]
                    (is (= {:scanned-bytes (.length file) :compaction-count 2} cursor))
                    (is (= 2 (:compaction-count metrics))))))))))

      (testing "a truncated transcript is rescanned from the start"
        (let [stale {:scanned-bytes 10000 :compaction-count 7}]
          (spit file "{\"type\":\"user\",\"isCompactSummary\":true}\n")
          (is (= {:scanned-bytes (.length file) :compaction-count 1}
                 (:cursor (transcript/scan path claude-pattern stale)))))))))

(deftest pattern-test
  (with-transcript
    (fn [file path]
      (spit file (str "{\"type\":\"event_msg\",\"payload\":{}}\n"
                      "{\"type\":\"compacted\",\"payload\":{}}\n"
                      "{\"type\":\"user\",\"isCompactSummary\" : true}\n"))
      (is (= 1 (get-in (transcript/scan path codex-pattern transcript/empty-cursor)
                       [:metrics :compaction-count])))
      (is (= 1 (get-in (transcript/scan path claude-pattern transcript/empty-cursor)
                       [:metrics :compaction-count]))))))

(deftest non-ascii-content-test
  (with-transcript
    (fn [file path]
      (testing "multi-byte characters do not shift the byte cursor"
        (spit file "{\"type\":\"user\",\"text\":\"größe ünïcode 日本語\"}\n")
        (let [{:keys [cursor]} (transcript/scan path claude-pattern transcript/empty-cursor)]
          (is (= (.length file) (:scanned-bytes cursor)))
          (spit file "{\"type\":\"user\",\"isCompactSummary\":true}\n" :append true)
          (is (= {:scanned-bytes (.length file) :compaction-count 1}
                 (:cursor (transcript/scan path claude-pattern cursor)))))))))

(let [{:keys [fail error]} (run-tests 'transcript-test)]
  (System/exit (+ fail error)))

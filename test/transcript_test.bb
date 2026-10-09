#!/usr/bin/env bb

(ns transcript-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]
            [rule-fairy.transcript :as transcript]))

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

(defn frame
  "One frame of a delivery as the Claude mod writes it: the shard header
  with the delivery id, then the marker lines, then a body."
  [delivery shard shards & marker-lines]
  (str "[rule-fairy shard " shard "/" shards " " delivery "]\n"
       (str/join "\n" marker-lines)
       "\n\n# body"))

(defn injected [rule-name]
  (str "[rule-fairy injected: " rule-name "]"))

(deftest frames-injections-test
  (testing "whole deliveries only, in first-appearance order, whatever arrived between their frames"
    (is (= ["a.mdc" "c.mdc"]
           (transcript/frames-injections
            [(frame "d1" 1 2 (injected "a.mdc"))
             "plain context without a header"
             (frame "d2" 1 2 (injected "b.mdc"))
             (frame "d1" 2 2)
             (frame "d3" 1 1 (injected "c.mdc"))]))))

  (testing "a marker quoted after other text is not a frame"
    (is (= [] (transcript/frames-injections
               [(str "Previous-session note: Rule Fairy logged this example:\n"
                     (frame "d1" 1 1 (injected "testing.mdc")))]))))

  (testing "a header without a delivery id, from before deliveries carried one, is not a frame"
    (is (= [] (transcript/frames-injections
               ["[rule-fairy shard 1/1]\n[rule-fairy injected: a.mdc]\n\n# body"]))))

  (testing "only the injected markers after the header name rules, in a real first frame's order"
    (is (= ["a.mdc" "b.mdc"]
           (transcript/frames-injections
            [(frame "d1" 1 1
                    "[rule-fairy deferred: c.mdc (past what one delivery can carry; delivered with the next matching event)]"
                    (injected "a.mdc")
                    (injected "b.mdc")
                    "[rule-fairy matched: alwaysApply a.mdc]")]))))

  (testing "a delivery missing a shard does not count"
    (is (= [] (transcript/frames-injections [(frame "d1" 1 3 (injected "a.mdc"))])))
    (is (= [] (transcript/frames-injections [(frame "d1" 1 3 (injected "a.mdc"))
                                             (frame "d1" 3 3)]))))

  (testing "shards in any order, with another delivery between them, make a whole delivery"
    (is (= ["a.mdc" "b.mdc" "c.mdc"]
           (transcript/frames-injections
            [(frame "d1" 3 3)
             (frame "d2" 1 1 (injected "c.mdc"))
             (frame "d1" 1 3 (injected "a.mdc") (injected "b.mdc"))
             (frame "d1" 2 3)]))))

  (testing "an interrupted delivery, then a whole one whose frames arrived in reverse: only the whole one counts"
    (is (= ["b.mdc"]
           (transcript/frames-injections
            [(frame "d1" 1 2 (injected "a.mdc"))
             (frame "d2" 2 2)
             (frame "d2" 1 2 (injected "b.mdc"))]))))

  (testing "a rule delivered twice is named once"
    (is (= ["a.mdc"]
           (transcript/frames-injections
            [(frame "d1" 1 1 (injected "a.mdc"))
             (frame "d2" 1 1 (injected "a.mdc"))])))))

(let [{:keys [fail error]} (run-tests 'transcript-test)]
  (System/exit (+ fail error)))

#!/usr/bin/env bb

(ns transcript-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
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

(defn shard
  "A Rule Fairy hook output injecting the named rules, one single-shard
  delivery identified by its first rule."
  [& rule-names]
  (str "[rule-fairy shard 1/1 " (first rule-names) "]\n"
       (str/join "\n" (map #(str "[rule-fairy injected: " % "]") rule-names))
       "\n\n# body"))

(defn hook-record
  "A transcript record of a hook's output, as Claude Code writes it: the
  prompt hook's text under :content, a tool hook's JSON under :stdout."
  [hook-name output-key output]
  (json/generate-string {:type "attachment"
                         :attachment {:type "hook_success" :hookName hook-name output-key output}}))

(deftest inherited-injections-test
  (with-transcript
    (fn [file path]
      (testing "a transcript without hook records inherits nothing"
        (spit file "{\"type\":\"user\",\"isCompactSummary\":true}\n")
        (is (= [] (transcript/inherited-injections path claude-pattern))))

      (testing "rules injected after the last compaction summary, from hook records only, each once"
        (spit file
              (str (str/join "\n"
                             [(hook-record "UserPromptSubmit" :content (shard "old.mdc"))
                              (json/generate-string {:type "user" :isCompactSummary true
                                                     :message (shard "summary.mdc")})
                              (hook-record "UserPromptSubmit" :content (shard "prompt.mdc" "shared.mdc"))
                              (hook-record "PreToolUse:Edit" :stdout
                                           (json/generate-string {:hookSpecificOutput {:additionalContext (shard "edit.mdc" "shared.mdc")}}))
                              (json/generate-string {:type "user"
                                                     :message {:content [{:type "tool_result"
                                                                          :content (str "{\"type\":\"attachment\"} " (shard "tool.mdc"))}]}})
                              (hook-record "PostToolUse:Bash" :content
                                           "[rule-fairy error: big.mdc alone needs 13 output frames]\nNo matched rules were injected or marked as injected.")
                              (hook-record "SessionStart" :content "unrelated hook output")])
                   "\n"
                   ;; A record still being written has no newline yet and is left out.
                   (subs (hook-record "UserPromptSubmit" :content (shard "partial.mdc")) 0 40)))
        (is (= ["prompt.mdc" "shared.mdc" "edit.mdc"]
               (transcript/inherited-injections path claude-pattern)))))))

(defn frame
  "One frame of a delivery as the Claude hooks write it: the shard header
  with the delivery id, then the marker lines, then a body."
  [delivery shard shards & marker-lines]
  (str "[rule-fairy shard " shard "/" shards " " delivery "]\n"
       (str/join "\n" marker-lines)
       "\n\n# body"))

(defn injected [rule-name]
  (str "[rule-fairy injected: " rule-name "]"))

(deftest inherited-injections-reads-whole-deliveries-only-test
  (with-transcript
    (fn [file path]
      (let [records (fn [& records] (spit file (str (str/join "\n" records) "\n")))
            inherited #(transcript/inherited-injections path claude-pattern)
            prompt-record #(hook-record "UserPromptSubmit" :content %)
            edit-record #(hook-record "PreToolUse:Edit" :stdout
                                      (json/generate-string {:hookSpecificOutput {:additionalContext %}}))]
        (testing "a marker quoted after other text in another hook's output is not a frame"
          (records (hook-record "SessionStart" :content
                                (str "Previous-session note: Rule Fairy logged this example:\n"
                                     (frame "d1" 1 1 (injected "testing.mdc")))))
          (is (= [] (inherited))))

        (testing "a header without a delivery id, from before deliveries carried one, is not inherited"
          (records (prompt-record "[rule-fairy shard 1/1]\n[rule-fairy injected: a.mdc]\n\n# body"))
          (is (= [] (inherited))))

        (testing "only the injected markers after the header name rules, in a real first frame's order"
          (records (prompt-record (frame "d1" 1 1
                                         "[rule-fairy deferred: c.mdc (past what one delivery can carry; delivered with the next matching event)]"
                                         (injected "a.mdc")
                                         (injected "b.mdc")
                                         "[rule-fairy matched: alwaysApply a.mdc]")))
          (is (= ["a.mdc" "b.mdc"] (inherited))))

        (testing "a delivery missing a shard is not inherited"
          (records (prompt-record (frame "d1" 1 3 (injected "a.mdc"))))
          (is (= [] (inherited)))
          (records (prompt-record (frame "d1" 1 3 (injected "a.mdc")))
                   (prompt-record (frame "d1" 3 3)))
          (is (= [] (inherited))))

        (testing "shards recorded in any order, with another delivery between them, make a whole delivery"
          (records (prompt-record (frame "d1" 3 3))
                   (edit-record (frame "d2" 1 1 (injected "c.mdc")))
                   (prompt-record (frame "d1" 1 3 (injected "a.mdc") (injected "b.mdc")))
                   (prompt-record (frame "d1" 2 3)))
          (is (= ["a.mdc" "b.mdc" "c.mdc"] (inherited))))

        (testing "an interrupted delivery, then a whole one whose lanes finished in reverse: only the whole one counts"
          (records (prompt-record (frame "d1" 1 2 (injected "a.mdc")))
                   (prompt-record (frame "d2" 2 2))
                   (prompt-record (frame "d2" 1 2 (injected "b.mdc"))))
          (is (= ["b.mdc"] (inherited))))

        (testing "another hook's stdout that is not JSON is skipped"
          (records (hook-record "PostToolUse:Bash" :stdout "{not json")
                   (hook-record "PostToolUse:Bash" :stdout "plain text")
                   (prompt-record (frame "d1" 1 1 (injected "a.mdc"))))
          (is (= ["a.mdc"] (inherited))))))))

(let [{:keys [fail error]} (run-tests 'transcript-test)]
  (System/exit (+ fail error)))

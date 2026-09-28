#!/usr/bin/env bb

(ns dedup-test
  (:require [rule-fairy.dedup :as dedup]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def long-paragraph
  (str/trim (apply str (repeat 6 "This sentence pads the paragraph well past the duplicate threshold. "))))

(def other-long-paragraph
  (str/trim (apply str (repeat 6 "A different sentence that is also long enough to count as a unit. "))))

(def long-fence
  (str "```clojure\n"
       (str/join "\n" (repeat 8 "(defn padded-function [argument] (str argument \"padding\"))"))
       "\n```"))

(defn block [label content]
  {:label label :content content})

(defn omission [location]
  (dedup/omission-line location))

(deftest identical-section-test
  (let [[agents claude] (dedup/dedup-blocks
                         [(block "AGENTS.md" "# Testing\n\nRun bb test.\n\n## Style\n\nShort.")
                          (block "CLAUDE.md" "# Testing\n\nRun bb test.\n\n## Other\n\nDifferent.")])]
    (testing "the first occurrence is returned verbatim"
      (is (= "# Testing\n\nRun bb test.\n\n## Style\n\nShort." (:content agents)))
      (is (= [] (:omissions agents))))
    (testing "a repeated section becomes one line naming the kept copy"
      (is (= (str (omission "AGENTS.md#testing") "\n\n## Other\n\nDifferent.") (:content claude)))
      (is (= ["AGENTS.md#testing"] (:omissions claude))))))

(deftest heading-level-and-whitespace-test
  (testing "heading level and case are ignored, trailing whitespace and blank runs are normalised"
    (let [[_ copy] (dedup/dedup-blocks
                    [(block "a" "## Testing\n\nline one  \n\n\n\nline two")
                     (block "b" "### TESTING\n\nline one\n\nline two\n")])]
      (is (= (omission "a#testing") (:content copy))))))

(deftest long-paragraph-test
  (let [[_ copy] (dedup/dedup-blocks
                  [(block "AGENTS.md" (str "# One\n\n" long-paragraph))
                   (block "CLAUDE.md" (str "# Two\n\n" long-paragraph "\n\nShort tail."))])]
    (testing "a long paragraph under a different heading is omitted, the rest of the section stays"
      (is (= (str "# Two\n\n" (omission "AGENTS.md#one") "\n\nShort tail.") (:content copy)))
      (is (= ["AGENTS.md#one"] (:omissions copy))))))

(deftest short-repeat-kept-test
  (let [[_ copy] (dedup/dedup-blocks
                  [(block "a" "# A\n\nRun `bb test` before pushing.")
                   (block "b" "# B\n\nRun `bb test` before pushing.")])]
    (is (= "# B\n\nRun `bb test` before pushing." (:content copy)))
    (is (= [] (:omissions copy)))))

(deftest fenced-block-test
  (testing "a long fenced block is compared whole and omitted whole"
    (let [[_ copy] (dedup/dedup-blocks
                    [(block "a" (str "# Code\n\n" long-fence))
                     (block "b" (str "# Copy\n\nIntro.\n\n" long-fence))])]
      (is (= (str "# Copy\n\nIntro.\n\n" (omission "a#code")) (:content copy)))))

  (testing "a short fenced block repeats freely"
    (let [[_ copy] (dedup/dedup-blocks
                    [(block "a" "# A\n\n```sh\nbb test\n```")
                     (block "b" "# B\n\n```sh\nbb test\n```")])]
      (is (= [] (:omissions copy))))))

(deftest preamble-test
  (testing "content without headings is located by label alone"
    (let [[_ copy] (dedup/dedup-blocks [(block "a" long-paragraph) (block "b" long-paragraph)])]
      (is (= (omission "a") (:content copy)))
      (is (= ["a"] (:omissions copy)))))

  (testing "a preamble paragraph repeated under a heading is caught by the paragraph layer"
    (let [[_ copy] (dedup/dedup-blocks
                    [(block "a" long-paragraph)
                     (block "b" (str "# Heading\n\n" long-paragraph))])]
      (is (= (str "# Heading\n\n" (omission "a")) (:content copy))))))

(deftest coalesce-test
  (testing "adjacent omissions from the same kept copy collapse into one line"
    (let [[_ copy] (dedup/dedup-blocks
                    [(block "a" (str "# One\n\n" long-paragraph "\n\n" other-long-paragraph))
                     (block "b" (str "# Two\n\n" long-paragraph "\n\n" other-long-paragraph "\n\nTail."))])]
      (is (= (str "# Two\n\n" (omission "a#one") "\n\nTail.") (:content copy)))
      (is (= ["a#one"] (:omissions copy))))))

(deftest heading-only-sections-test
  (let [[_ copy] (dedup/dedup-blocks [(block "a" "# T\n\n## S") (block "b" "# T\n\n## S")])]
    (is (= "# T\n\n## S" (:content copy)))
    (is (= [] (:omissions copy)))))

(deftest within-block-repeat-test
  (let [[only] (dedup/dedup-blocks
                [(block "a" (str "# One\n\n" long-paragraph "\n\n# Two\n\n" long-paragraph))])]
    (is (= (str "# One\n\n" long-paragraph "\n\n# Two\n\n" (omission "a#one")) (:content only)))))

(deftest unchanged-content-verbatim-test
  (let [odd "# A\n\n\n\nspaced   \n\n- list\n\n\n# B\n"
        [only] (dedup/dedup-blocks [(block "a" odd)])]
    (is (= odd (:content only)))
    (is (= [] (:omissions only)))))

(let [{:keys [fail error]} (run-tests 'dedup-test)]
  (System/exit (+ fail error)))

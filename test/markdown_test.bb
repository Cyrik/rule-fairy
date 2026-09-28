#!/usr/bin/env bb

(ns markdown-test
  (:require [rule-fairy.markdown :as md]
            [clojure.test :refer [deftest is run-tests testing]]))

(def document
  (str "Preamble line.\n"
       "\n"
       "# Title\n"
       "\n"
       "Intro paragraph.\n"
       "\n"
       "```md\n"
       "# not a heading\n"
       "```\n"
       "\n"
       "## Section ##\n"
       "\n"
       "Body one.\n"
       "Body two.\n"
       "\n"
       "## Section\n"
       "\n"
       "Repeated heading.\n"))

(deftest headings-test
  (testing "headings outside fences get GitHub-style unique slugs"
    (let [lines (md/lines document)
          fenced (md/fenced-lines lines)]
      (is (= [{:level 1 :text "Title" :line 2 :slug "title"}
              {:level 2 :text "Section" :line 10 :slug "section"}
              {:level 2 :text "Section" :line 15 :slug "section-1"}]
             (md/headings lines #(nth fenced %))))))

  (testing "a suffix another heading already produced is skipped, as GitHub does"
    (let [slugs (fn [text]
                  (let [lines (md/lines text)
                        fenced (md/fenced-lines lines)]
                    (mapv :slug (md/headings lines #(nth fenced %)))))]
      (is (= ["api" "api-1" "api-1-1"] (slugs "# API\n# API\n# API-1\n")))
      (is (= ["api" "api-1" "api-2"] (slugs "# API\n# API-1\n# API\n")))))

  (testing "closing hashes are dropped and a hash without a space is not a heading"
    (is (= {:level 3 :text "Do it: now!"} (md/heading "### Do it: now! ###")))
    (is (nil? (md/heading "#hashtag")))
    (is (= "do-it-now" (md/slug "Do it: now!"))))

  (testing "a heading that is a link slugs to its visible text, as GitHub does"
    (is (= "testing" (md/slug "[Testing](testing.md)")))
    (is (= "see-the-api" (md/slug "See [the API][api]")))
    (is (= "api" (md/slug "[API](guide_(v2).md)")))
    (is (= "testing" (-> (md/headings ["## [Testing](testing.md)"] (constantly false)) first :slug))))

  (testing "link syntax inside a code span is literal text"
    (is (= "apiapimd" (md/slug "`[API](api.md)`")))
    (is (= "see-code-and-docs" (md/slug "See `code` and [docs](d.md)")))
    (is (= "xapiapimd-y" (md/slug "``x`[API](api.md)`` [y](y.md)")))))

(deftest sections-test
  (testing "content splits at headings outside fences, preamble first"
    (let [sections (md/sections document)]
      (is (= [nil "Title" "Section" "Section"]
             (map #(some-> % :heading :text) sections)))
      (is (= ["Preamble line." ""] (:lines (first sections))))
      (is (= ["# Title" "" "Intro paragraph." "" "```md" "# not a heading" "```" ""]
             (:lines (second sections))))
      (is (= "section-1" (-> sections (nth 3) :heading :slug)))))

  (testing "a blank preamble is left out and headingless content is one section"
    (is (= ["Title"] (map #(-> % :heading :text) (md/sections "\n\n# Title\n\nBody\n"))))
    (is (= [{:heading nil :lines ["Just text." ""]}] (md/sections "Just text.\n")))
    (is (= [] (md/sections "")))))

(deftest units-test
  (testing "units split at blank lines, headings and fences, and a fence stays whole"
    (is (= [{:content "Preamble line." :fenced? false}
            {:content "# Title" :fenced? false}
            {:content "Intro paragraph." :fenced? false}
            {:content "```md\n# not a heading\n```" :fenced? true}
            {:content "## Section ##" :fenced? false}
            {:content "Body one.\nBody two." :fenced? false}
            {:content "## Section" :fenced? false}
            {:content "Repeated heading." :fenced? false}]
           (md/units document))))

  (testing "CRLF content splits like LF content"
    (is (= (md/units "a\n\nb\n") (md/units "a\r\n\r\nb\r\n")))))

(let [{:keys [fail error]} (run-tests 'markdown-test)]
  (System/exit (+ fail error)))

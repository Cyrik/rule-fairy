#!/usr/bin/env bb

(ns plugin-layout-test
  "Keeps the plugin's skill and agent files well-formed: both harnesses
  discover skills from skills/<name>/SKILL.md by their frontmatter, Claude
  Code loads agents/*.md the same way, and a skill's relative links and
  scripts must exist where the text says they are."
  (:require [cheshire.core :as json]
            [rule-fairy.rules :as rules]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

(def plugin-root
  (.getParentFile (.getParentFile (.getAbsoluteFile (io/file *file*)))))

(defn frontmatter [file]
  (:frontmatter (rules/parse-rule (slurp file))))

(defn relative-links
  "Targets of Markdown links that are relative paths, ignoring URLs and
  anchors."
  [content]
  (->> (re-seq #"\]\(([^)\s]+)\)" content)
       (map second)
       (remove #(or (str/includes? % "://") (str/starts-with? % "#")))))

(def skill-dirs
  (->> (.listFiles (io/file plugin-root "skills"))
       (filter #(.isDirectory %))
       (sort-by #(.getName %))))

(deftest skills-test
  (is (seq skill-dirs) "the plugin ships at least one skill")
  (doseq [dir skill-dirs
          :let [skill-file (io/file dir "SKILL.md")
                fields (frontmatter skill-file)
                content (slurp skill-file)]]
    (testing (str "skill " (.getName dir))
      (is (.isFile skill-file))
      (is (= (.getName dir) (:name fields)) "frontmatter name matches the directory")
      (is (not (str/blank? (:description fields))))
      (is (< (count (:description fields)) 1024) "description well under the listing cap")
      (testing "relative links resolve inside the skill directory"
        (doseq [link (relative-links content)]
          (is (.exists (io/file dir link)) link)))
      (testing "every script the text names exists and runs"
        (doseq [script (distinct (map second (re-seq #"<skill-dir>/(scripts/[\w./-]+)" content)))]
          (let [file (io/file dir script)]
            (is (.isFile file) script)
            (is (.canExecute file) (str script " is executable"))))))))

(deftest agents-test
  (let [agent-files (->> (.listFiles (io/file plugin-root "agents"))
                         (filter #(str/ends-with? (.getName %) ".md"))
                         (sort-by #(.getName %)))]
    (is (seq agent-files))
    (doseq [file agent-files
            :let [fields (frontmatter file)
                  stem (str/replace (.getName file) #"\.md$" "")]]
      (testing (str "agent " stem)
        (is (= stem (:name fields)) "frontmatter name matches the file")
        (is (not (str/blank? (:description fields))))
        (is (str/includes? (:disallowedTools fields "") "Edit") "the reviewer stays read-only")))))

(deftest manifests-test
  (let [manifest #(json/parse-string (slurp (io/file plugin-root %)) true)
        portable (manifest "plugin.json")
        claude (manifest ".claude-plugin/plugin.json")
        codex (manifest ".codex-plugin/plugin.json")
        marketplace (manifest ".claude-plugin/marketplace.json")]
    (testing "the three manifests and the marketplace entry agree on name and version"
      (is (= #{"rule-fairy"} (set (map :name [portable claude codex]))))
      (is (= 1 (count (distinct (map :version [portable claude codex])))))
      (is (= [(:version portable)] (mapv :version (:plugins marketplace))))
      (is (= "rule-fairy" (:name (first (:plugins marketplace))))))
    (testing "the root manifest is a portable Agent Plugins manifest, so Codex discovers skills/ itself"
      (is (= "https://agent-plugins.org/schemas/1.0.0/plugin.schema.json" (:$schema portable)))
      (is (nil? (:hooks portable)) "hooks belong to the Codex overlay")
      (is (nil? (:extensions portable)) "an extensions.com.openai block would replace the overlay"))
    (testing "the Codex overlay points at an existing hooks file"
      (is (.isFile (io/file plugin-root (:hooks codex))) (:hooks codex))))
  (testing "the procedure file both skills reference exists"
    (is (.isFile (io/file plugin-root "skills/review/references/procedure.md")))))

(let [{:keys [fail error]} (run-tests 'plugin-layout-test)]
  (System/exit (+ fail error)))

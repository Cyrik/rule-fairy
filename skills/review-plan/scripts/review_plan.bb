#!/usr/bin/env bb
;; Command-line side of the rule-fairy:review-plan skill. Run from the
;; reviewed project's root, normally through the `run` shim beside this file
;; so the plugin's own bb.edn is used.
;;
;;   review_plan.bb paths <plan-file> [--paths <path>...]
;;     Shows which files the plan intends to touch: a file-list section when
;;     the plan has one, otherwise every path-like token that exists in the
;;     checkout or whose parent directory does. Paths the heuristic dropped
;;     are listed so they can be added back with --paths.
;;
;;   review_plan.bb bundle <plan-file> [--paths <path>...]
;;     Writes .rule-fairy/review/plan/rules.md, the bundle of instruction
;;     files, rules and documentation that apply to those paths, and meta.json
;;     recording the paths, where they came from and the rules revision.
;;
;;   review_plan.bb clean
;;     Deletes the plan review's artifacts.
;;
;; Artifacts belong to one pass: bundle starts from an empty directory, and
;; the skill runs clean once the report is out.

(ns review-plan-script
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.bundle :as bundle]
            [rule-fairy.plan :as plan]))

(def usage
  (str "usage: review-plan paths <plan-file> [--paths <path>...]\n"
       "       review-plan bundle <plan-file> [--paths <path>...]\n"
       "       review-plan clean"))

(defn parse-args [args]
  (loop [[arg & more] args
         options {:added []}
         collecting? false]
    (cond
      (nil? arg)
      (if (:plan-file options)
        options
        (throw (ex-info (str "A plan file is required\n" usage) {})))

      (= "--paths" arg) (recur more options true)

      (str/starts-with? arg "--")
      (throw (ex-info (str "Unknown argument: " arg) {:arg arg}))

      collecting? (recur more (update options :added conj arg) true)
      (:plan-file options) (throw (ex-info (str "Unexpected argument: " arg) {:arg arg}))
      :else (recur more (assoc options :plan-file arg) false))))

(defn- read-plan
  "The plan's text: an absolute plan-file as given, a relative one from root."
  [root plan-file]
  (let [given (io/file plan-file)
        file (if (.isAbsolute given) given (io/file root plan-file))]
    (when-not (.isFile file)
      (throw (ex-info (str "Plan not found: " file) {:file (str file)})))
    (slurp file)))

(defn plan-selection
  "The paths the bundle is built for: the plan's own, plus the ones added on
  the command line, placed under the root like the plan's; an added path
  outside the project is an error. Returns the plan-paths result with :added
  and the merged :changed-paths."
  [root {:keys [plan-file added]}]
  (let [{:keys [paths] :as selection} (plan/plan-paths root (read-plan root plan-file))
        added (mapv (fn [path]
                      (or (plan/project-path root path)
                          (throw (ex-info (str "Path outside the project: " path) {:path path}))))
                    added)]
    (assoc selection
           :added (vec (remove (set paths) (distinct added)))
           :changed-paths (vec (distinct (concat paths added))))))

(defn- print-paths [label paths]
  (println (str label (when (empty? paths) " none")))
  (doseq [path paths]
    (println (str "  " path))))

(defn- file-count [n]
  (str n (if (= 1 n) " file" " files")))

(defn paths! [root {:keys [plan-file] :as options}]
  (let [{:keys [paths dropped added source expanded]} (plan-selection root options)]
    (println (str "Plan: " plan-file))
    (print-paths (str "Paths (" (name source) "):") paths)
    (when (seq expanded)
      (print-paths "Directories expanded to the files under them:"
                   (map (fn [[directory n]] (str directory "/ (" (file-count n) ")"))
                        (sort-by key expanded))))
    (when (seq dropped)
      (print-paths "Dropped (no such file or parent directory, or outside the project; add back with --paths):" dropped))
    (when (seq added)
      (print-paths "Added:" added))))

(defn bundle! [root {:keys [plan-file] :as options}]
  (let [{:keys [changed-paths source added dropped expanded]} (plan-selection root options)
        output-file (bundle/bundle-file root :plan)
        meta-file (io/file (bundle/review-dir root :plan) "meta.json")]
    (bundle/clean! root :plan)
    (let [{:keys [rule-names rule-count instruction-paths input-paths omissions]}
          (bundle/write! root :plan changed-paths)
          meta {:mode "plan"
                :plan_file plan-file
                :path_source (name source)
                :changed_paths changed-paths
                :added_paths added
                :dropped_paths dropped
                :expanded_directories expanded
                :rules (bundle/rules-revision root input-paths)
                :generated_at (str (java.time.Instant/now))}]
      (spit meta-file (json/generate-string meta {:pretty true}))
      (println (str "Wrote " output-file ": " (count rule-names) " of " rule-count " rules, "
                    (count instruction-paths) " instruction files, "
                    (count changed-paths) " paths (" (name source)
                    (when (seq added) (str ", " (count added) " added"))
                    "), " (count omissions) " duplicates omitted; and " meta-file))
      meta)))

(defn clean! [root args]
  (when (seq args)
    (throw (ex-info (str "clean takes no arguments\n" usage) {})))
  (println (str "Removed " (bundle/clean! root :plan))))

(defn -main [[command & args]]
  (case command
    "paths" (paths! "." (parse-args args))
    "bundle" (bundle! "." (parse-args args))
    "clean" (clean! "." args)
    (throw (ex-info (if command (str "Unknown command: " command "\n" usage) usage) {}))))

(when (= *file* (System/getProperty "babashka.file"))
  (try
    (-main *command-line-args*)
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println "Error:" (ex-message error)))
      (System/exit 1))))

#!/usr/bin/env bb
;; Command-line side of the rule-fairy:review skill. Run from the reviewed
;; project's root, normally through the `run` shim beside this file so the
;; plugin's own bb.edn is used.
;;
;;   review.bb fetch (--pr <number> [--checkout] | --local | --base <ref>)
;;     Writes .rule-fairy/review/diff/patch.diff and meta.json from one of
;;     three sources: the pull request's patch through gh (--checkout also
;;     checks it out detached, which needs the user's explicit authorisation),
;;     every change to the working tree against HEAD, or every change since
;;     the merge base with <ref>. Working-tree modes include staged, unstaged
;;     and untracked files and detect renames made outside git.
;;
;;   review.bb bundle [--patch <file>]
;;     Reads the changed files from the patch and writes
;;     .rule-fairy/review/diff/rules.md: the applicable instruction files,
;;     rules and imported documentation, deduplicated.
;;
;;   review.bb clean
;;     Deletes the diff review's artifacts.
;;
;; Artifacts belong to one run: fetch starts from an empty directory, and the
;; skill runs clean once the report is out.

(ns review-script
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [rule-fairy.bundle :as bundle]
            [rule-fairy.git :as git]))

(def usage
  (str "usage: review fetch (--pr <number> [--checkout] | --local | --base <ref>)\n"
       "       review bundle [--patch <file>]\n"
       "       review clean"))

(defn- gh [root & args]
  (git/run root (into ["gh"] args)))

;;; Fetch: arguments

(defn parse-fetch-args [args]
  (loop [[arg & more] args
         options {}]
    (cond
      (nil? arg)
      (do (when-not (:mode options)
            (throw (ex-info "One of --pr <number>, --local or --base <ref> is required" {})))
          (when (and (:checkout? options) (not= :pr (:mode options)))
            (throw (ex-info "--checkout only applies to --pr" {})))
          options)

      (= "--checkout" arg) (recur more (assoc options :checkout? true))

      (#{"--pr" "--local" "--base"} arg)
      (let [mode (keyword (subs arg 2))
            takes-value? (not= :local mode)
            value (when takes-value? (first more))]
        (when (:mode options)
          (throw (ex-info "Only one of --pr, --local and --base may be given" {})))
        (when (and takes-value? (or (nil? value) (str/starts-with? value "--")))
          (throw (ex-info (str arg " needs a value") {})))
        (recur (if takes-value? (rest more) more)
               (cond-> (assoc options :mode mode)
                 takes-value? (assoc mode value))))

      :else (throw (ex-info (str "Unknown argument: " arg) {:arg arg})))))

;;; Fetch: working-tree patches

(def ^:private diff-options
  ;; Fixed prefixes keep the `diff --git a/x b/x` headers the bundle reads
  ;; whatever diff.noprefix or diff.mnemonicPrefix the user has set; no
  ;; external diff tool and no colour for the same reason. Non-ASCII paths
  ;; stay readable instead of octal-escaped.
  ["-c" "core.quotepath=false" "diff" "--no-ext-diff" "--no-color"
   "--src-prefix=a/" "--dst-prefix=b/"])

(defn- working-tree-patch
  "One diff from the commit `against` to the whole working tree. The tree is
  staged into a throwaway index, so a single tree-to-tree diff covers
  committed, staged, unstaged and untracked changes without duplicates, and
  rename detection pairs a deleted path with its untracked replacement. The
  real index is never touched; the plugin's own artifact directory is left
  out.

  The scratch index starts from the real index's entries, so a staged
  addition stays tracked even when its path is ignored, but not from a copy
  of the index file: a copy carries the stat cache, and git then trusts an
  entry whose size and mtime match even when the file was edited within the
  same second as the last index write, which agents do all the time.
  `update-index --index-info` records no stats, so `git add` hashes every
  file once instead. Before the first commit the entries are empty and
  `git add` creates the index."
  [root against]
  (let [scratch (fs/create-temp-file {:prefix "rule-fairy-index"})
        env {"GIT_INDEX_FILE" (str scratch)}]
    (try
      (fs/delete scratch)
      (git/run root ["git" "update-index" "--index-info"]
               {:env env :in (git/run root ["git" "ls-files" "--stage"])})
      (git/run root ["git" "add" "--all" "--" ":(exclude).rule-fairy"] {:env env})
      (git/run root (into ["git"] (concat diff-options ["--cached" "--find-renames" against])) {:env env})
      (finally
        (fs/delete-if-exists scratch)))))

;;; Fetch: modes

(defn- pr-inputs [root {:keys [pr checkout?]}]
  (when checkout?
    (gh root "pr" "checkout" pr "--detach"))
  ;; The default output is the aggregate diff of the PR; --patch would give one
  ;; patch per commit, intermediate versions and all.
  (let [patch (gh root "pr" "diff" pr)
        view (json/parse-string
              (gh root "pr" "view" pr "--json"
                  "number,url,title,state,isDraft,headRefOid,headRefName,baseRefName")
              true)]
    {:patch patch
     :meta {:mode "pr"
            :pr_number (:number view)
            :pr_url (:url view)
            :pr_title (:title view)
            :pr_state (:state view)
            :pr_draft (:isDraft view)
            :head_ref_oid (:headRefOid view)
            :head_ref_name (:headRefName view)
            :base_ref_name (:baseRefName view)
            :repo_slug (str/trim (gh root "repo" "view" "--json" "nameWithOwner" "-q" ".nameWithOwner"))
            :checked_out (boolean checkout?)
            :working_tree_included false}}))

(defn- local-inputs [root]
  (let [head (git/head-oid root)]
    {:patch (working-tree-patch root (or head (git/empty-tree-oid root)))
     :meta {:mode "local"
            :head_ref_oid head
            :repo_slug (git/repo-slug root)
            :working_tree_included true}}))

(defn- base-inputs [root {:keys [base]}]
  (let [merge-base (git/git root "merge-base" base "HEAD")]
    {:patch (working-tree-patch root merge-base)
     :meta {:mode "base"
            :base_ref base
            :base_ref_oid merge-base
            :head_ref_oid (git/head-oid root)
            :repo_slug (git/repo-slug root)
            :working_tree_included true}}))

(defn- patch-file [root]
  (io/file (bundle/review-dir root :diff) "patch.diff"))

(defn- meta-file [root]
  (io/file (bundle/review-dir root :diff) "meta.json"))

(defn fetch!
  "Replaces the diff review's artifacts with patch.diff and meta.json for the
  requested source and returns the metadata."
  [root {:keys [mode] :as options}]
  (let [{:keys [patch meta]} (case mode
                               :pr (pr-inputs root options)
                               :local (local-inputs root)
                               :base (base-inputs root options))
        changed-paths (bundle/changed-paths-from-patch patch)
        meta (assoc meta
                    :changed_paths changed-paths
                    :rules (bundle/rules-revision root)
                    :generated_at (str (java.time.Instant/now)))
        patch-file (patch-file root)
        meta-file (meta-file root)]
    (bundle/clean! root :diff)
    (bundle/ensure-review-dir! root :diff)
    (spit patch-file patch)
    (spit meta-file (json/generate-string meta {:pretty true}))
    (println (str "Wrote " patch-file " (" (count (str/split-lines patch)) " lines, "
                  (count changed-paths) " changed paths) and " meta-file))
    meta))

;;; Bundle

(defn parse-bundle-args [args]
  (loop [[arg & more] args
         options {}]
    (cond
      (nil? arg) options
      (= "--patch" arg) (if-let [file (first more)]
                          (recur (rest more) (assoc options :patch file))
                          (throw (ex-info "--patch needs a file" {})))
      :else (throw (ex-info (str "Unknown argument: " arg) {:arg arg})))))

(defn bundle!
  "Writes the diff-review bundle for root and returns the build result.
  options: :patch overrides the patch file."
  [root {:keys [patch]}]
  (let [patch-file (if patch (io/file patch) (patch-file root))
        output-file (bundle/bundle-file root :diff)]
    (when-not (.isFile patch-file)
      (throw (ex-info (str "Patch not found: " patch-file
                           ". Run `review fetch` first, or pass --patch <file>.")
                      {:file (str patch-file)})))
    (let [{:keys [rule-names rule-count changed-paths instruction-paths input-paths omissions] :as result}
          (bundle/write! root :diff (bundle/changed-paths-from-patch (slurp patch-file)))
          meta-path (meta-file root)]
      ;; fetch recorded the rules revision before the bundle's inputs were
      ;; known; now the check can cover the instruction files and
      ;; documentation that went in.
      (when (.isFile meta-path)
        (let [meta (json/parse-string (slurp meta-path) true)]
          (spit meta-path (json/generate-string
                           (assoc meta :rules (bundle/rules-revision root input-paths))
                           {:pretty true}))))
      (println (str "Wrote " output-file ": " (count rule-names) " of " rule-count " rules, "
                    (count instruction-paths) " instruction files, "
                    (count changed-paths) " changed paths, "
                    (count omissions) " duplicates omitted"))
      result)))

;;; Clean

(defn clean! [root args]
  (when (seq args)
    (throw (ex-info (str "clean takes no arguments\n" usage) {})))
  (println (str "Removed " (bundle/clean! root :diff))))

;;; Entry point

(defn -main [[command & args]]
  (case command
    "fetch" (fetch! "." (parse-fetch-args args))
    "bundle" (bundle! "." (parse-bundle-args args))
    "clean" (clean! "." args)
    (throw (ex-info (if command (str "Unknown command: " command "\n" usage) usage) {}))))

(when (= *file* (System/getProperty "babashka.file"))
  (try
    (-main *command-line-args*)
    (catch clojure.lang.ExceptionInfo error
      (binding [*out* *err*]
        (println "Error:" (ex-message error)))
      (System/exit 1))))

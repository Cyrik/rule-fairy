(ns rule-fairy.config
  "The optional `rule-fairy.edn` at a project root. It holds one map; each
  namespace validates its own key (`:rules` for the rules source,
  `:instructions` for harness instruction files, `:shell-hook` for the
  per-harness shell check). This namespace reads the file and rejects keys
  nobody handles, so a misspelt key fails instead of silently changing
  nothing."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def config-file "rule-fairy.edn")

(def ^:private known-keys #{:rules :instructions :shell-hook})

(defn read-config
  "The parsed configuration map under project-root, or nil when the file is
  absent."
  [project-root]
  (let [file (io/file project-root config-file)]
    (when (.isFile file)
      (let [config (edn/read-string (slurp file))]
        (when-not (map? config)
          (throw (ex-info (str config-file " must contain a map") {:file (str file)})))
        (let [unknown-keys (remove known-keys (keys config))]
          (when (seq unknown-keys)
            (throw (ex-info (str "Unknown keys in " config-file ": " (str/join ", " unknown-keys))
                            {:file (str file) :keys (vec unknown-keys)}))))
        config))))

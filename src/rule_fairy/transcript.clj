(ns rule-fairy.transcript
  "Incremental compaction counting over a harness transcript.

  A transcript is a JSONL file that only grows while a session runs. The hooks
  need two numbers from it: its size, to detect large growth, and how many
  compaction records it holds, to detect context compaction. Rescanning the
  whole file on every hook event costs a full read per prompt, so a scan cursor
  remembers how far the file has been counted. Only complete lines are counted;
  a partial trailing line waits for the next scan, so a record is never split
  across two scans and miscounted.

  A transcript can also start with history: a forked session's transcript
  replays the parent's records, hook outputs included, under the new session
  id. `inherited-injections` reads the rules those records already injected."
  (:require [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.nio ByteBuffer]
           [java.nio.channels FileChannel]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files OpenOption Path StandardOpenOption]))

(def empty-cursor
  {:scanned-bytes 0
   :compaction-count 0})

(defn- read-range
  "Bytes [offset, offset + length) of the file at path, decoded as ISO-8859-1 so
  that character offsets equal byte offsets."
  [^Path path offset length]
  (with-open [channel (FileChannel/open path (into-array OpenOption [StandardOpenOption/READ]))]
    (let [buffer (ByteBuffer/allocate length)]
      (.position channel (long offset))
      (loop []
        (when (and (.hasRemaining buffer)
                   (not= -1 (.read channel buffer)))
          (recur)))
      (.flip buffer)
      (String. (.array buffer) 0 (.limit buffer) StandardCharsets/ISO_8859_1))))

(defn scan
  "Advances cursor over the transcript at path, counting complete lines that
  match compaction-pattern. Returns {:cursor cursor' :metrics {:size bytes
  :compaction-count n}}. A file shorter than the cursor has been replaced or
  truncated and is rescanned from the start."
  [^Path path compaction-pattern cursor]
  (let [size (Files/size path)
        {:keys [scanned-bytes compaction-count]} (if (< size (:scanned-bytes cursor))
                                                   empty-cursor
                                                   cursor)
        chunk (read-range path scanned-bytes (- size scanned-bytes))
        complete (if-let [last-newline (str/last-index-of chunk "\n")]
                   (subs chunk 0 (inc last-newline))
                   "")
        cursor' {:scanned-bytes (+ scanned-bytes (count complete))
                 :compaction-count (+ compaction-count
                                      (count (re-seq compaction-pattern complete)))}]
    {:cursor cursor'
     :metrics {:size size
               :compaction-count (:compaction-count cursor')}}))

(def ^:private shard-header-pattern #"\[rule-fairy shard (\d+)/(\d+) ([^\s\]]+)\]")
(def ^:private marker-pattern #"\[rule-fairy ([a-z]+): (.*)\]")

(defn- hook-outputs
  "The context texts a record delivered, when it is a hook_success
  attachment: the attachment's content, where a prompt hook's output lands,
  and the additionalContext inside its stdout JSON, where a tool hook's
  lands. Empty for every other record. The substring test keeps JSON parsing
  to the candidate lines, and another hook's stdout that is not JSON is
  skipped."
  [line]
  (if-not (str/includes? line "hook_success")
    []
    (let [{:keys [type attachment]} (json/parse-string line true)]
      (if-not (and (= "attachment" type)
                   (= "hook_success" (:type attachment)))
        []
        (let [{:keys [content stdout]} attachment
              additional-context (when (and (string? stdout)
                                            (str/starts-with? (str/triml stdout) "{"))
                                   (try
                                     (-> (json/parse-string stdout true)
                                         :hookSpecificOutput
                                         :additionalContext)
                                     (catch Exception _ nil)))]
          (filter string? [content additional-context]))))))

(defn- shard-record
  "The frame a hook output is, as {:delivery id :shard i :shards n :injected
  [name ...]}, when its first line is a Rule Fairy shard header carrying a
  delivery id; nil otherwise, so a marker quoted inside another hook's
  output never counts, and nor does a header written before deliveries
  carried an id. The marker lines directly after the header are the
  delivery's bookkeeping, and only the `injected` ones name rules; frames
  after the first carry none."
  [output]
  (let [[header & lines] (str/split-lines output)]
    (when-let [[_ shard shards delivery] (re-matches shard-header-pattern header)]
      {:delivery delivery
       :shard (parse-long shard)
       :shards (parse-long shards)
       :injected (->> lines
                      (map #(re-matches marker-pattern %))
                      (take-while some?)
                      (keep (fn [[_ kind value]] (when (= "injected" kind) value)))
                      vec)})))

(defn- whole-deliveries
  "The rule names of every delivery whose shards are all present, in order
  of each delivery's first record. Records are grouped by the id their
  headers carry, so the order the lanes finished in and other deliveries'
  records between them do not matter."
  [records]
  (let [by-delivery (group-by :delivery records)]
    (for [delivery (distinct (map :delivery records))
          :let [shards (get by-delivery delivery)]
          :when (= (:shards (first shards)) (count (distinct (map :shard shards))))
          rule-name (mapcat :injected shards)]
      rule-name)))

(defn inherited-injections
  "Rule names the complete records after the transcript's last compaction
  summary show as injected by Rule Fairy, in first-appearance order. A
  forked session's transcript replays the parent's hook outputs under the
  new session id and names the parent nowhere, so these records are the only
  trace of what the inherited context already holds. A frame counts only as
  a hook_success attachment whose output opens with a shard header naming
  its delivery, and a rule counts only when every shard of that delivery is
  present: a delivery the parent lost lanes on was never marked there and is
  not inherited here, so the fork delivers it whole. Reads the whole file
  once, which the caller does once per session."
  [^Path path compaction-pattern]
  (let [content (slurp (.toFile path))
        complete (if-let [last-newline (str/last-index-of content "\n")]
                   (subs content 0 last-newline)
                   "")]
    (->> (str/split-lines complete)
         (reduce (fn [records line]
                   (if (re-find compaction-pattern line)
                     []
                     (if-let [record (some shard-record (hook-outputs line))]
                       (conj records record)
                       records)))
                 [])
         whole-deliveries
         distinct
         vec)))

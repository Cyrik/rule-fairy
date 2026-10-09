(ns rule-fairy.transcript
  "Incremental compaction counting over a harness transcript.

  A transcript is a JSONL file that only grows while a session runs. The hooks
  need two numbers from it: its size, to detect large growth, and how many
  compaction records it holds, to detect context compaction. Rescanning the
  whole file on every hook event costs a full read per prompt, so a scan cursor
  remembers how far the file has been counted. Only complete lines are counted;
  a partial trailing line waits for the next scan, so a record is never split
  across two scans and miscounted.

  The hook outputs a conversation holds can also be read back:
  `frames-injections` names the rules the whole Rule Fairy deliveries among
  them injected."
  (:require [clojure.string :as str])
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
  headers carry, so the order the frames arrived in and other deliveries'
  records between them do not matter."
  [records]
  (let [by-delivery (group-by :delivery records)]
    (for [delivery (distinct (map :delivery records))
          :let [shards (get by-delivery delivery)]
          :when (= (:shards (first shards)) (count (distinct (map :shard shards))))
          rule-name (mapcat :injected shards)]
      rule-name)))

(defn frames-injections
  "Rule names the given hook outputs show as injected by Rule Fairy, in
  first-appearance order. An output counts as a frame only when it opens
  with a shard header naming its delivery, and a rule counts only when every
  shard of its delivery is present among the outputs: a delivery cut short
  was never marked where it ran and is not taken as delivered here. The mod
  reads the outputs back from the conversation itself, in the Messages API
  form `$.session.messages` answers, so what it finds is what the model's
  context holds now, after any compaction."
  [outputs]
  (->> outputs
       (keep shard-record)
       whole-deliveries
       distinct
       vec))

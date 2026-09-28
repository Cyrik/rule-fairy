(ns rule-fairy.transcript
  "Incremental compaction counting over a harness transcript.

  A transcript is a JSONL file that only grows while a session runs. The hooks
  need two numbers from it: its size, to detect large growth, and how many
  compaction records it holds, to detect context compaction. Rescanning the
  whole file on every hook event costs a full read per prompt, so a scan cursor
  remembers how far the file has been counted. Only complete lines are counted;
  a partial trailing line waits for the next scan, so a record is never split
  across two scans and miscounted."
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

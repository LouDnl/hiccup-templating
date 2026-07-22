(ns hiccup-templating.core.reader
  (:require
   #?(:clj  [clojure.edn :as edn]
      :cljs [cljs.reader :as edn])
   #?(:clj [clojure.java.io :as io])
   [clojure.string :as string])
  #?(:clj
     (:import
      [java.io File FilterReader PushbackReader Reader]
      [java.util.concurrent.atomic AtomicLong])))

#?(:clj (set! *warn-on-reflection* true))


;;; EDN reading

;; Templates contain user-defined tags (`#data/foo`, `#when`, `#or`, ...).
;; The reader maps every unknown tag to a `tagged-literal` so we can
;; inspect them later instead of needing pre-registered data readers.

(def ^:dynamic *max-depth*
  "When bound to a positive integer, `ref-meta-to-tagged-literal` throws
   as soon as the template's nesting depth exceeds this value. `nil`
   (the default) means no limit."
  nil)

(defn- edn-readers
  []
  (into {}
        (map (fn [[k _]] [k #(tagged-literal k %)]))
        (merge default-data-readers *data-readers*)))


(defn- edn-opts
  []
  {:eof     nil
   :readers (edn-readers)
   :default tagged-literal})


(defn- check-max-depth!
  [depth]
  (when (and *max-depth* (> depth *max-depth*))
    (throw (ex-info "template exceeds max nesting depth during read"
                    {:max-depth *max-depth*
                     :depth     depth}))))

(defn- walk-ref-meta
  "Explicit-recursion equivalent of the previous `clojure.walk/postwalk`
   pass. Promotes `^:ref {...}` metadata to `tagged-literal 'ref` and
   descends into every tagged literal's `:form`. Enforces `*max-depth*`
   at each recursion step.

   `:ref` promotion is limited to maps (the documented
   `^:ref {...}` attribute-map use case) so an attacker-supplied
   template cannot smuggle arbitrary values into a downstream
   consumer that trusts the `'ref` tag."
  [v depth]
  (check-max-depth! depth)
  (let [d      (inc depth)
        walked (cond
                 ;; `empty` preserves metadata on maps/vectors/sets so
                 ;; the :ref check below still sees the tag when the
                 ;; author wrote `^:ref {...}` in the template.
                 (map? v)    (into (empty v) (map (fn [[k vv]] [k (walk-ref-meta vv d)])) v)
                 (vector? v) (with-meta (mapv #(walk-ref-meta % d) v) (meta v))
                 (set? v)    (into (empty v) (map #(walk-ref-meta % d)) v)
                 (seq? v)    (doall (map #(walk-ref-meta % d) v))
                 :else       v)]
    (cond
      (tagged-literal? walked)
      (tagged-literal (:tag walked) (walk-ref-meta (:form walked) d))

      (and (map? walked) (contains? (meta walked) :ref))
      (tagged-literal 'ref walked)

      :else walked)))

(defn- ref-meta-to-tagged-literal
  "Hiccup attribute values can be tagged via `^:ref {...}` metadata.
   Promote those to plain `tagged-literal`s so `expand-tag` sees them."
  [template]
  (walk-ref-meta template 0))

#?(:clj
   (defn- limiting-reader
     "Returns a `java.io.FilterReader` that throws once more than
      `max-bytes` characters have been read from `rdr`. Character count is
      used as a byte proxy - safe for ASCII, conservative for multibyte
      UTF-8 (real byte count is >= char count)."
     ^Reader [^Reader rdr max-bytes]
     (let [counter (AtomicLong. 0)
           check!  (fn [^long n]
                     (when (> n (long max-bytes))
                       (throw (ex-info "template exceeds max bytes"
                                       {:max-bytes max-bytes
                                        :bytes     n}))))]
       (proxy [FilterReader] [rdr]
         (read
           ([]
            (let [c (.read rdr)]
              (when (>= c 0)
                (check! (.incrementAndGet counter)))
              c))
           ([cbuf ^long off ^long len]
            (let [n (.read rdr ^chars cbuf off len)]
              (when (pos? n)
                (check! (.addAndGet counter (long n))))
              n)))))))

#?(:clj
   (defn- traversal-segment?
     "True when `s` contains a `..` path segment (splits on `/` and the
      platform separator so both are covered on all OSes)."
     [s]
     (let [seps (re-pattern (str "[/" (java.util.regex.Pattern/quote File/separator) "]"))]
       (some #{".."} (string/split (str s) seps)))))

#?(:clj
   (defn template-from-edn-file
     "Read EDN template from `path` + `filename`.
 
      Options:
        `:validatepath true` - reject `..` segments in `path` or `filename`
                               and refuse any file whose canonical path
                               escapes the canonicalised `path`.
        `:max-bytes n`       - refuse to read the file if its size exceeds
                               `n` bytes, and abort mid-read once more
                               than `n` characters have streamed.
        `:max-depth n`       - abort with `ex-info` if the template's
                               nesting depth exceeds `n` during the
                               reader postwalk.
 
      All limits are opt-in - omit the option (or pass `nil`) to keep the
      caller responsible for the boundary."
     [path filename
      & {:keys [validatepath max-bytes max-depth]
         :or   {validatepath false}}]
     (let [path     (if (string/ends-with? path "/")
                      (string/replace path #"/$" "")
                      path)
           filename (if (string/ends-with? filename ".edn")
                      filename
                      (throw (ex-info (format "Incorrect extension for file `%s`, expected `.edn`" filename)
                                      {:path path :filename filename})))
           _        (when validatepath
                      (when (or (traversal-segment? path)
                                (traversal-segment? filename))
                        (throw (ex-info "`..` segments are not allowed in path or filename"
                                        {:path path :filename filename})))
                      (let [base           (.getCanonicalFile (io/file path))
                            canonical-file (.getCanonicalFile (io/file base filename))]
                        (when-not (.startsWith (.getPath canonical-file)
                                               (str (.getPath base) File/separator))
                          (throw (ex-info "Path escapes base directory"
                                          {:path path :filename filename})))))
           file     (io/file (format (if (seq path) "%s/%s" "%s%s") path filename))]
       (when (and max-bytes (.exists file) (> (.length file) (long max-bytes)))
         (throw (ex-info "template file exceeds max bytes"
                         {:max-bytes max-bytes
                          :bytes     (.length file)
                          :path      path
                          :filename  filename})))
       (if (.exists file)
         (let [base-rdr (io/reader file)
               rdr      (if max-bytes (limiting-reader base-rdr max-bytes) base-rdr)]
           (with-open [pb (PushbackReader. rdr)]
             (binding [*max-depth* max-depth]
               (ref-meta-to-tagged-literal (edn/read (edn-opts) pb)))))
         (throw (ex-info (format "File `%s` does not exist" (.toString file))
                         {:path     path
                          :filename filename}))))))

#?(:clj
   (defn template-from-edn-stream
     "Read EDN template from a `PushbackReader` (e.g. an uploaded file).
 
      Options:
        `:max-bytes n` - abort mid-read once more than `n` characters have
                         streamed through the reader.
        `:max-depth n` - abort with `ex-info` if the template's nesting
                         depth exceeds `n` during the reader postwalk.
 
      Both limits are opt-in - omit them to preserve the previous
      trust-caller default."
     [ednstream & {:keys [max-bytes max-depth]}]
     (let [rdr (if max-bytes
                 (PushbackReader. (limiting-reader ednstream max-bytes))
                 ednstream)]
       (binding [*max-depth* max-depth]
         (ref-meta-to-tagged-literal (edn/read (edn-opts) rdr))))))

(defn template-from-edn-string
  "Read EDN template from a string (e.g. a slurped file).

   Options:
     `:max-bytes n` - throw before parsing when the UTF-8 byte length
                      of `ednstring` exceeds `n`.
     `:max-depth n` - abort with `ex-info` if the template's nesting
                      depth exceeds `n` during the reader postwalk.

   Both limits are opt-in - omit them to preserve the previous
   trust-caller default."
  [ednstring & {:keys [max-bytes max-depth]}]
  (when max-bytes
    (let [n #?(:clj  (count (.getBytes ^String ednstring "UTF-8"))
               :cljs (.-length (.encode (js/TextEncoder.) ednstring)))]
      (when (> n #?(:clj (long max-bytes) :cljs max-bytes))
        (throw (ex-info "template string exceeds max bytes"
                        {:max-bytes max-bytes
                         :bytes     n})))))
  (binding [*max-depth* max-depth]
    (ref-meta-to-tagged-literal (edn/read-string (edn-opts) ednstring))))


(ns hiccup-templating.core.reader
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.walk :as walk])
  (:import

(set! *warn-on-reflection* true)


;;; EDN reading

;; Templates contain user-defined tags (`#data/foo`, `#when`, `#or`, ...).
;; The reader maps every unknown tag to a `tagged-literal` so we can
;; inspect them later instead of needing pre-registered data readers.

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


(defn- ref-meta-to-tagged-literal
  "Hiccup attribute values can be tagged via `^:ref {...}` metadata.
   Promote those to plain `tagged-literal`s so `expand-tag` sees them."
  [template]
  (walk/postwalk
   (fn [v]
     (cond
       (tagged-literal? v)
       (tagged-literal (:tag v) (ref-meta-to-tagged-literal (:form v)))

       (and (some? v) (contains? (meta v) :ref))
       (tagged-literal 'ref v)

       :else v))
   template))

(defn template-from-edn-file
  "Read EDN template from path + filename"
  [path filename]
  (let [path     (if (string/ends-with? path "/")
                   (string/replace path #"/$" "")
                   path)
        filename (if (string/ends-with? filename ".edn")
                   filename
                   (throw (ex-info (format "Incorrect extension for file `%s`, expected `.edn`" filename) {:path     path
                                                                                                           :filename filename})))
        file     (io/file (format "%s/%s" path filename))]
    (if (.exists file)
      (with-open [reader (PushbackReader. (io/reader file))]
        (ref-meta-to-tagged-literal (edn/read (edn-opts) reader)))
      (throw (ex-info (format "File `%s` does not exist" (.toString file))
                      {:path     path
                       :filename filename})))))

(defn template-from-edn-stream
  "Read EDN template from stream e.g. an uploaded file"
  [ednstream]
  (ref-meta-to-tagged-literal (edn/read (edn-opts) ednstream)))


(defn template-from-edn-string
  "Read EDN template from string e.g. a slurped file"
  [ednstring]
  (ref-meta-to-tagged-literal (edn/read-string (edn-opts) ednstring)))

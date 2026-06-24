(ns hiccup-templating.core.parser
  (:require
   [clojure.java.io :as io]
   [hiccup.page :as p]
   [hiccup2.core :as h])
  (:import
   (java.io ByteArrayOutputStream)))


;;; HTML / XHTML rendering

(defn- parse-hiccup
  "Renders a Hiccup tree as XHTML 1.0 strict, the dialect flying-saucer
   expects. `escape-strings` defaults to false so prefilled HTML
   fragments (`&nbsp;`, `<br/>`, etc.) survive."
  [hiccup-ds
   & {:keys [escape-strings] :or {escape-strings false}}]
  (h/html
   {:escape-strings? escape-strings}
   (p/xml-declaration "US-ASCII")
   (p/doctype :xhtml-strict)
   hiccup-ds))

(defn hiccup-xhtml-stream
  "Returns an xhtml stream from provided hiccup datastructure"
  [hiccup-ds]
  (with-open [baos (ByteArrayOutputStream.)
              w    (io/writer baos :encoding "UTF-8")]
    (.write w (.toString (parse-hiccup hiccup-ds)))
    baos))

(defn hiccup-xhtml-string
  "Returns an xhtml string from provided hiccup datastructure"
  [hiccup-ds]
  (.toString (parse-hiccup hiccup-ds {:escape-strings false})))

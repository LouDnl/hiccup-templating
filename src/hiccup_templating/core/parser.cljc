(ns hiccup-templating.core.parser
  #?(:clj 
     (:require
       [clojure.java.io :as io]
       [hiccup.page :as p]
       [hiccup2.core :as h])
     :cljs
     (:require
       [hiccups.core :as hiccups :include-macros true]))
  #?(:clj 
     (:import
      [hiccup.util RawString]
      [java.io ByteArrayOutputStream Writer])))

#?(:clj (set! *warn-on-reflection* true))


;;; HTML / XHTML rendering

#?(:clj
   (defn- parse-hiccup
     "Renders a Hiccup tree as XHTML 1.0 strict (the dialect flying-saucer
      expects) and returns the compiled result. `escape-strings` defaults
      to true - safe for HTML sinks. The public entry points flip it to
      false for the PDF pipeline where pre-built HTML fragments must
      survive."
     ^RawString
     [hiccup-ds
      & {:keys [escape-strings] :or {escape-strings true}}]
     (h/html
      {:escape-strings? escape-strings}
      (p/xml-declaration "US-ASCII")
      (p/doctype :xhtml-strict)
      hiccup-ds)))
 
#?(:clj
   (defn hiccup-xhtml-stream
     "Renders `hiccup-ds` as XHTML 1.0 strict and returns an OPEN
      `java.io.ByteArrayOutputStream` holding the UTF-8 encoded bytes.
      The internal writer is closed (flushing its buffer into `baos`),
      but `baos` itself is left open so callers can `.toString`,
      `.toByteArray`, or write additional bytes into it. Callers that
      need to release the buffer should call `.close` themselves.
 
      The 1-arity call passes `{:escape-strings false}` so pre-built HTML
      fragments in the tree survive the render - intended for the
      flying-saucer PDF pipeline with a trusted data map. Callers that
      need escaping (any HTML sink with untrusted data) should either
      supply `opts` explicitly or use the `-escaped` variants in
      `hiccup-templating.core`."
     ([hiccup-ds] (hiccup-xhtml-stream hiccup-ds {:escape-strings false}))
     ([hiccup-ds opts]
      (let [baos (ByteArrayOutputStream.)]
        (with-open [w ^Writer (io/writer baos :encoding "UTF-8")]
          (.write w (.toString (parse-hiccup hiccup-ds opts))))
        baos))))

(defn hiccup-xhtml-string
  "Renders `hiccup-ds` as XHTML 1.0 strict and returns the result as a
   string.

   The 1-arity call passes `{:escape-strings false}` so pre-built HTML
   fragments in the tree survive the render - intended for the
   flying-saucer PDF pipeline with a trusted data map. Callers that
   need escaping (any HTML sink with untrusted data) should either
   supply `opts` explicitly or use the `-escaped` variants in
   `hiccup-templating.core`."
  ([hiccup-ds] (hiccup-xhtml-string hiccup-ds {:escape-strings false}))
  ([hiccup-ds opts] 
   #?(:clj  (.toString (parse-hiccup hiccup-ds opts))
      :cljs (hiccups/html hiccup-ds))))

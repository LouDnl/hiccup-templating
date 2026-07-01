(ns hiccup-templating.unittests
  "Unit tests for the hiccup-templating library.

   Coverage is grouped by namespace under test:
     * `hiccup-templating.core.data`      - data-path parsing + lookup
     * `hiccup-templating.core.reader`    - EDN reader entry points
     * `hiccup-templating.core.walker`    - directive expansion / pruning
     * `hiccup-templating.core.parser`    - XHTML rendering

   A handful of end-to-end tests also exercise the public façade in
   `hiccup-templating.core` against the sample template + data files
   under `test/resources`.

   Some tests intentionally assert the *current* observable behaviour of
   the library rather than the behaviour documented in the README. Those
   are tagged with a `FIXME` comment and cross-referenced from
   SECURITY.md so the divergence stays visible."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [hiccup-templating.core
    :refer [from-file from-stream from-string
            as-xhtml-stream as-xhtml-string
            as-xhtml-stream-escaped as-xhtml-string-escaped
            template-to-hiccup]]
   [hiccup-templating.core.reader]
   [hiccup-templating.core.data :as data]
   [hiccup-templating.core.sentinels :as s]
   [hiccup-templating.core.walker :as walker])
  (:import
   (java.io ByteArrayInputStream InputStreamReader PushbackReader)))


;;; ---------------------------------------------------------------------
;;; Shared fixtures / helpers

(def test-path      "test/resources/")
(def test-template  "template_a.edn")
(def test-data-file "data_a.edn")

(def sample-data
  {:texts
   {:intro           "Hello from Clojure!"
    :elementtext     ", this some other text"
    :available       true
    :elementtext_two "I am text, therefore i must be read"
    :footer          "Goodbye now, come again!"}})

(defn- string->pushback-reader
  "Wraps `s` in a PushbackReader so `from-stream` can consume it."
  [s]
  (-> (.getBytes ^String s "UTF-8")
      (ByteArrayInputStream.)
      (InputStreamReader. "UTF-8")
      (PushbackReader.)))


;;; ---------------------------------------------------------------------
;;; hiccup-templating.core.data

(deftest data-path-splits-on-dots
  (is (= [:foo]           (data/data-path :foo)))
  (is (= [:foo :bar]      (data/data-path :foo.bar)))
  (is (= [:foo :bar :baz] (data/data-path :foo.bar.baz))))

(deftest lookup-data-returns-value-when-present
  (is (= "Hello from Clojure!"
         (data/lookup-data [:texts :intro] sample-data)))
  (is (true?
       (data/lookup-data [:texts :available] sample-data))))

(deftest lookup-data-returns-missing-sentinel-when-absent
  (is (= s/MISSING (data/lookup-data [:texts :nope] sample-data)))
  (is (= s/MISSING (data/lookup-data [:missing :chain] sample-data))))

(deftest lookup-data-treats-nil-as-missing
  ;; Documented contract: `nil` in the data map is indistinguishable
  ;; from the key being absent. Guards against surprising template
  ;; output when upstream code leaves optional fields as `nil`.
  (is (= s/MISSING (data/lookup-data [:texts :intro] {:texts {:intro nil}}))))


;;; ---------------------------------------------------------------------
;;; hiccup-templating.core.reader

(deftest from-string-parses-minimal-template
  (let [t (from-string "{:template [:div :data/x]}")]
    (is (map? t))
    (is (vector? (:template t)))))

(deftest from-string-preserves-tagged-literals
  (let [t (from-string "{:template [:div #when :data/x [:span :data/x]]}")]
    (is (some tagged-literal? (:template t)))))

(deftest ref-metadata-on-map-promotes-to-tagged-literal
  ;; Documented use case: `^:ref {...}` attribute maps become
  ;; `#ref {...}` so downstream extensions can resolve them.
  (let [t (from-string "{:template [:div ^:ref {:key \"v\"}]}")
        v (nth (:template t) 1)]
    (is (tagged-literal? v))
    (is (= 'ref (:tag v)))))

(deftest ref-metadata-on-non-map-is-ignored
  ;; SECURITY.md #LOW-2 fix: `:ref` metadata only promotes maps -
  ;; matches the documented `^:ref {...}` shape and prevents attackers
  ;; from smuggling arbitrary values as `#ref` tagged literals.
  (let [t (from-string "{:template [:div ^:ref [:span \"x\"]]}")
        v (nth (:template t) 1)]
    (is (vector? v))
    (is (not (tagged-literal? v)))))

(deftest from-stream-parses-from-pushback-reader
  (let [t (from-stream
           (string->pushback-reader "{:template [:p :data/msg]}"))]
    (is (= [:p :data/msg] (:template t)))))

(deftest from-file-reads-sample-template
  (let [t (from-file test-path test-template)]
    (is (map? t))
    (is (contains? t :template))))

(deftest from-file-tolerates-trailing-slash-in-path
  (is (= (from-file "test/resources"  test-template)
         (from-file "test/resources/" test-template))))

(deftest from-file-rejects-non-edn-extension
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"expected `\.edn`"
       (from-file test-path "not-a-template.txt"))))

(deftest from-file-throws-on-missing-file
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"does not exist"
       (from-file test-path "no_such_template.edn"))))

(deftest from-file-allows-traversal-without-validation
  ;; Documents the by-design library boundary described in SECURITY.md
  ;; #INFO-1. Without `:validatepath true` the caller is trusted with
  ;; the file-system boundary.
  (let [content (hiccup-templating.core.reader/template-from-edn-file
                 "test/resources" "../../deps.edn")]
    (is (map? content))
    (is (contains? content :paths))))

(deftest from-file-validatepath-allows-legit-file
  (let [t (hiccup-templating.core.reader/template-from-edn-file
           test-path test-template :validatepath true)]
    (is (map? t))))

(deftest from-file-validatepath-blocks-filename-traversal
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"`\.\.` segments are not allowed"
       (hiccup-templating.core.reader/template-from-edn-file
        "test/resources" "../../deps.edn" :validatepath true))))

(deftest from-file-validatepath-blocks-path-traversal
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"`\.\.` segments are not allowed"
       (hiccup-templating.core.reader/template-from-edn-file
        "test/resources/../../.." "deps.edn" :validatepath true))))

;;; ---- MEDIUM-1 max-bytes ----

(deftest from-string-rejects-oversize-input
  (let [payload (str "{:template [:p \"" (apply str (repeat 200 \x)) "\"]}")]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"exceeds max bytes"
         (from-string payload :max-bytes 50)))))

(deftest from-string-passes-when-under-max-bytes
  (let [t (from-string "{:template [:p \"hi\"]}" :max-bytes 1024)]
    (is (map? t))))

(deftest from-stream-rejects-oversize-input
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"exceeds max bytes"
       (from-stream
        (string->pushback-reader
         (str "{:template [:p \"" (apply str (repeat 200 \x)) "\"]}"))
        :max-bytes 50))))

(deftest from-file-rejects-oversize-file
  ;; template_a.edn is ~600 bytes; cap at 100 to force the rejection.
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"exceeds max bytes"
       (from-file test-path test-template :max-bytes 100))))

(deftest from-file-passes-when-under-max-bytes
  (let [t (from-file test-path test-template :max-bytes 10000)]
    (is (map? t))))


;;; ---- MEDIUM-2 max-depth ----

(defn- deep-string
  "Builds an EDN string of the shape `[[[[...\"x\"...]]]]` with `n`
   nested vectors under the `:template` key."
  [n]
  (str "{:template "
       (apply str (repeat n "["))
       "\"x\""
       (apply str (repeat n "]"))
       "}"))

(deftest from-string-rejects-oversize-nesting
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"exceeds max nesting depth"
       (from-string (deep-string 100) :max-depth 10))))

(deftest from-string-passes-when-under-max-depth
  (let [t (from-string (deep-string 5) :max-depth 100)]
    (is (map? t))))

(deftest template-to-hiccup-rejects-oversize-walker-depth
  (let [tpl (from-string (deep-string 200))]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"exceeds max walker depth"
         (template-to-hiccup tpl {} :max-depth 10)))))

(deftest template-to-hiccup-passes-when-under-max-depth
  (let [tpl (from-string (deep-string 5))]
    ;; expanded shape is [[[[["x"]]]]]; walker depth stays small.
    (is (some? (template-to-hiccup tpl {} :max-depth 100)))))


;;; ---------------------------------------------------------------------
;;; hiccup-templating.core.walker

(defn- render
  "Convenience: parse `edn-str` as a template and expand against `data`."
  [edn-str data]
  (template-to-hiccup (from-string edn-str) data))

(deftest data-keyword-resolves-to-lookup-value
  (is (= [:span "hi"]
         (render "{:template [:span :data/greeting]}" {:greeting "hi"}))))

(deftest data-keyword-that-misses-drops-the-slot
  (is (= [:span]
         (render "{:template [:span :data/nope]}" {}))))

(deftest data-shorthand-tag-behaves-like-when
  (testing "present -> body kept"
    (is (= [:div [:span "x"]]
           (render "{:template [:div #data/x [:span :data/x]]}"
                   {:x "x"}))))
  (testing "missing -> body dropped"
    (is (= [:div]
           (render "{:template [:div #data/x [:span :data/x]]}"
                   {})))))

(deftest when-directive-splices-body-when-test-resolves
  (is (= [:div [:h1 "hey"]]
         (render "{:template [:div #when :data/t [:h1 :data/t]]}"
                 {:t "hey"}))))

(deftest when-directive-drops-body-when-test-missing
  (is (= [:div]
         (render "{:template [:div #when :data/t [:h1 :data/t]]}"
                 {}))))

(deftest when-not-directive-inverts-when
  (testing "missing -> body kept"
    (is (= [:div [:em "fallback"]]
           (render "{:template [:div #when-not :data/t [:em \"fallback\"]]}"
                   {}))))
  (testing "present -> body dropped"
    (is (= [:div]
           (render "{:template [:div #when-not :data/t [:em \"fallback\"]]}"
                   {:t "value"})))))

(deftest when-and-requires-every-test
  (let [edn "{:template [:div #when-and [[:data/a :data/b] [:p \"ok\"]]]}"]
    (is (= [:div [:p "ok"]] (render edn {:a 1 :b 2})))
    (is (= [:div]           (render edn {:a 1})))
    (is (= [:div]           (render edn {})))))

(deftest when-or-requires-at-least-one-test
  (let [edn "{:template [:div #when-or [[:data/a :data/b] [:p \"ok\"]]]}"]
    (is (= [:div [:p "ok"]] (render edn {:a 1})))
    (is (= [:div [:p "ok"]] (render edn {:b 1})))
    (is (= [:div]           (render edn {})))))

(deftest or-directive-falls-back-when-expr-missing
  (is (= [:span "fallback"]
         (render "{:template [:span #or [:data/nope \"fallback\"]]}"
                 {})))
  (is (= [:span "primary"]
         (render "{:template [:span #or [:data/x \"fallback\"]]}"
                 {:x "primary"}))))

(deftest or-directive-fallback-can-reference-data
  (is (= [:span "backup"]
         (render "{:template [:span #or [:data/primary :data/secondary]]}"
                 {:secondary "backup"}))))

(deftest remove-element-prunes-enclosing-element
  ;; SECURITY.md #MEDIUM-3 fix: `:remove/element` drops the element that
  ;; holds the `#or`. The surrounding `:tr` stays but is emptied of its
  ;; pruned `:td`.
  (is (= [:tr]
         (render "{:template [:tr [:td #or [:data/missing :remove/element]]]}"
                 {}))))

(deftest remove-parent-prunes-parent-element
  ;; `:remove/parent` propagates one hop further than `:remove/element`
  ;; and drops the parent of the element that holds the `#or`.
  (is (nil?
       (render "{:template [:tr [:td #or [:data/missing :remove/parent]]]}"
               {})))
  (is (= [:table [:tr [:td "keep"]]]
         (render
          (str "{:template [:table"
               " [:tr [:td #or [:data/missing :remove/parent]]]"
               " [:tr [:td \"keep\"]]]}")
          {}))))

(deftest map-values-drop-missing-entries
  ;; Attribute maps prune entries whose value resolves to `::missing`.
  (is (= [:div {:class "x"}]
         (render "{:template [:div {:class \"x\" :id :data/missing}]}"
                 {}))))

(deftest unknown-tag-passes-form-through
  ;; Extensibility hook: unrecognised tags return their captured form
  ;; unmodified so callers can layer custom edn-readers.
  (is (= [:div "kept"]
         (render "{:template [:div #custom/thing \"kept\"]}" {}))))

(deftest template-to-hiccup-honors-alt-template-key
  (let [tpl (from-string "{:main [:p :data/x] :other [:h1 :data/x]}")]
    (is (= [:p "hi"]
           (template-to-hiccup tpl {:x "hi"} :template-key :main)))
    (is (= [:h1 "hi"]
           (template-to-hiccup tpl {:x "hi"} :template-key :other)))))

(deftest template-to-hiccup-returns-nil-when-entire-template-prunes
  (is (nil?
       (template-to-hiccup
        (from-string "{:template #when [:data/nope [:div \"x\"]]}")
        {}))))

(deftest walker-expand-is-directly-usable
  ;; Guards the public shape of the `expand` helper - internal callers
  ;; and tests both rely on it returning either an expanded form or one
  ;; of the sentinels.
  (is (= "v" (walker/expand :data/k {:k "v"})))
  (is (= s/MISSING (walker/expand :data/k {}))))


;;; ---------------------------------------------------------------------
;;; hiccup-templating.core.parser

(deftest as-xhtml-string-renders-doctype-and-body
  (let [html (as-xhtml-string [:html [:body [:p "hi"]]])]
    (is (string? html))
    (is (re-find #"<!DOCTYPE html" html))
    (is (re-find #"<p>hi</p>" html))))

(deftest as-xhtml-stream-writes-same-bytes-as-string
  (let [tree [:html [:body [:p "hi"]]]]
    (is (= (as-xhtml-string tree)
           (.toString (as-xhtml-stream tree))))))

(deftest as-xhtml-string-currently-does-not-escape-values
  ;; Documents SECURITY.md #HIGH-1. The default renderer is called with
  ;; `escape-strings? false`, so any string in the tree is emitted
  ;; verbatim. Callers who want XSS-safe output must use the `-escaped`
  ;; variant (see the tests below).
  (let [payload "<script>alert(1)</script>"
        html    (as-xhtml-string
                 (render "{:template [:div :data/x]}" {:x payload}))]
    (is (re-find (re-pattern (java.util.regex.Pattern/quote payload))
                 html))))

(deftest as-xhtml-string-escaped-escapes-values
  (let [payload "<script>alert(1)</script>"
        html    (as-xhtml-string-escaped
                 (render "{:template [:div :data/x]}" {:x payload}))]
    (is (not (re-find (re-pattern (java.util.regex.Pattern/quote payload))
                      html)))
    (is (re-find #"&lt;script&gt;" html))))

(deftest as-xhtml-stream-escaped-matches-string-escaped
  (let [tree (render "{:template [:div :data/x]}" {:x "<b>hi</b>"})]
    (is (= (as-xhtml-string-escaped tree)
           (.toString (as-xhtml-stream-escaped tree))))))

(deftest as-xhtml-stream-returns-writable-baos
  ;; SECURITY.md #LOW-1 fix: the returned ByteArrayOutputStream stays
  ;; open so callers can `.toString`, `.toByteArray`, or append bytes.
  (let [baos        ^java.io.ByteArrayOutputStream
                    (as-xhtml-stream [:html [:body [:p "hi"]]])
        before-size (.size baos)]
    (.write baos 65)
    (is (= (inc before-size) (.size baos)))
    (is (pos? (count (.toByteArray baos))))))


;;; ---------------------------------------------------------------------
;;; End-to-end via bundled sample resources

(deftest read-from-file
  (let [template (from-file test-path test-template)
        data     (edn/read-string (slurp (str test-path test-data-file)))
        hiccup   (template-to-hiccup template data)]
    (is (map? template))
    (is (map? data))
    (is (vector? hiccup))
    (is (= :html (first hiccup)))))

(deftest sample-template-produces-expected-tree
  ;; Locks in the README's worked example so future refactors of the
  ;; walker cannot silently change the observable output shape.
  (let [template (from-file test-path test-template)
        hiccup   (template-to-hiccup template sample-data)]
    (is (= [:html
            [:head]
            [:body
             [:header "Hello from Clojure!"]
             [:container
              [:div
               [:span "This is some text"]
               [:strong ", this some other text"]]]
             [:container [:p "I am text, therefore i must be read"]]]]
           hiccup))))

(deftest sample-template-sub-template-uses-or-fallback
  (let [template (from-file test-path test-template)
        hiccup   (template-to-hiccup template sample-data
                                     :template-key :sub-template)]
    (is (= [:footer [:div "Goodbye now, come again!"]] hiccup))))

(deftest resources-exist-on-disk
  ;; Regression guard: several tests above depend on these files being
  ;; present. Fail loudly if they get moved or renamed.
  (is (.exists (io/file test-path test-template)))
  (is (.exists (io/file test-path test-data-file))))

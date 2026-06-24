(ns hiccup-templating.unittests
(:require
  [clojure.edn :as edn]
  [clojure.test :refer [deftest is]]
  [hiccup-templating.core.reader]
  [hiccup-templating.core
   :refer [from-file from-stream from-string
           as-xhtml-stream as-xhtml-string
           template-to-hiccup]]))

(def test-path      "test/resources/")
(def test-template  "template_a.edn")
(def test-data-file "data_a.edn")
(def test-data-ds
  {:texts
   {:intro           "Hello from Clojure!"
    :elementtext     ", this some other text"
    :available       true
    :elementtext_two "I am text, therefore i must be read"
    :footer          "Goodbye now, come again!"}})

(deftest read-from-file
  (let [template (from-file test-path test-template)
        data     (edn/read-string (slurp (format "%s/%s" test-path test-data-file)))
        hiccup   (template-to-hiccup template data)]
    (is (map? template))
    (is (map? data))
    (is (vector? hiccup))))

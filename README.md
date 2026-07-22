[![Clojars Project](https://img.shields.io/clojars/v/nl.loudai/hiccup-templating.svg?include_prereleases)](https://clojars.org/nl.loudai/hiccup-templating)
  
# EDN Hiccup templating
This library provides utility functions for filling a [Hiccup](https://github.com/weavejester/hiccup) template stored in [EDN](https://github.com/edn-format/edn) format with data stored in a Clojure map. Works in both **Clojure (JVM)** and **ClojureScript (browser / Node.js)**.

[Clojure](https://www.clojure.org/) - Clojure is a robust, practical, and fast programming language with a set of useful features that together form a simple, coherent, and powerful tool.  
[Hiccup](https://github.com/weavejester/hiccup) - Hiccup is a library for representing HTML in Clojure. It uses vectors to represent elements, and maps to represent an element's attributes.  
[EDN](https://github.com/edn-format/edn) - Extensible Data Notation is a subset of the Clojure language used as a data transfer format, designed to be used in a similar way to JSON or XML.  
  

# Docs
[https://cljdoc.org/versions/nl.loudai/hiccup-templating](https://cljdoc.org/versions/nl.loudai/hiccup-templating)  
  

# Why
I needed a quick way to use different templates for pdf file generation
  

# Inspiration
[Aero](https://github.com/juxt/aero) - A small library for explicit, intentful configuration   
[hiccup-template](https://github.com/yogthos/hiccup-template) - A minimal Clojure/Script library for Hiccup style templating.
  

# Status
Still in development.


# Platform support

| Function | JVM | CLJS |
|---|---|---|
| `from-string` | ✓ | ✓ |
| `from-file` | ✓ | — no filesystem |
| `from-stream` | ✓ | — no streams |
| `template-to-hiccup` | ✓ | ✓ |
| `as-xhtml-string` | ✓ XHTML strict | ✓ plain HTML5 |
| `as-xhtml-string-escaped` | ✓ XHTML strict | ✓ plain HTML5 |
| `as-xhtml-stream` | ✓ | — |
| `as-xhtml-stream-escaped` | ✓ | — |

In CLJS, `template-to-hiccup` is the primary entry point. Most CLJS frameworks (Reagent, re-frame) consume hiccup vectors directly without needing to render to an HTML string.

  
# Key / value datamap
The datamap is where the data for the template comes from. 
```clojure
{:mykey      "My value"
 :anotherkey 1000
 :texts      {:intro "My intro text"}
 :validation {:header true}}
```
  

# Tag literals
`#when <test> <body>` Splice `<body>` if `<test>` resolves, drop otherwise. `<body>` can be a single form or a vector of forms.  
`#when-not <test> <body>` Inverse of `#when`.  
`#when-and [<tests>] <body>` Splice `<body>` if EVERY test in the `<tests>` vector resolves. Cheap way to gate a row on multiple data paths.  
`#when-or  [<tests>] <body>` Splice `<body>` if AT LEAST ONE test in `<tests>` resolves.  
`#or [<expr> <fallback>]` Evaluate `<expr>`; if it resolves to `::missing`, evaluate `<fallback>`.  
Two special fallbacks are recognised: 
- `:remove/element` drop the element that holds this `#or`.
- `:remove/parent` drop the parent of that element.
Any other fallback is spliced as a plain value.  
  

# Tag literal examples
All keys that represent data in the template must look like `:data/key.value` and can be sub map key/values aswell e.g. `:data/key.anotherkey.value`  
  
`#when` Inserts the element if the data path key/value exists  
```clojure
  [#when :data/texts.intro [:header :data/texts.intro]]
  [#when :data/validation.header [:header "The key/value exists"]]
```
`#when-not` Inserts the element if the data path key/value does _not_ exist  
```clojure
  [#when-not :data/texts.outro [:header :data/texts.intro]]
  [#when-not :data/validation.somekey [:header "The validation key does not exist"]]
```
`#when-and` Inserts the element if both the data path key/values exist  
```clojure
  [#when-and [[:data/mykey :data/validation.header] [:header "The key/value exists"]]]
```
`#when-or` Inserts the element if either of the data path key/values exists  
```clojure
  [#when-and [[:data/somekey :data/validation.header] [:header "The second key/value exists"]]]
```
`#or` Inserts either value
```clojure
  [:span #or [:data/nonexistent "I am the fallback text"]]
```
  

# Getting started

## Clojure (JVM)

### From string
```clojure
(require '[hiccup-templating.core :refer [from-string template-to-hiccup as-xhtml-string]])

(let [template (from-string
                "{:template
                  [:html
                    [:head]
                    [:body
                    #when :data/texts.intro [:header :data/texts.intro]
                    #when-or [[:data/texts.nonexistent :data/texts.elementtext]
                              [:container
                                [:div [:span \"This is some text\"] [:strong #when :data/texts.elementtext :data/texts.elementtext]]]]
                    #when-and [[:data/texts.available :data/texts.elementtext_two]
                                [:container [:p :data/texts.elementtext_two]]]]]}")
      data     {:texts
                {:intro           "Hello from Clojure!"
                 :elementtext     ", this some other text"
                 :available       true
                 :elementtext_two "I am text, therefore i must be read"}}
      hiccup   (template-to-hiccup template data)]
  (as-xhtml-string hiccup))
```

### From file
```clojure
(require
 '[clojure.edn :as edn]
 '[hiccup-templating.core :refer [from-file template-to-hiccup as-xhtml-string]])

(let [template (from-file "/path/to/" "template.edn")
      data     (edn/read-string (slurp "/path/to/data.edn"))
      hiccup   (template-to-hiccup template data)]
  (as-xhtml-string hiccup))
```

### Result output
```xml
<?xml version="1.0" encoding="US-ASCII"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN" "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html>
<head></head>
<body>
    <header>Hello from Clojure!</header>
    <container>
        <div><span>This is some text</span><strong>, this some other text</strong></div>
    </container>
    <container>
        <p>I am text, therefore i must be read</p>
    </container>
</body>
</html>
```


## ClojureScript

### Setup (shadow-cljs + deps.edn)
```clojure
;; deps.edn
{:deps {nl.loudai/hiccup-templating {:mvn/version "..."}
        org.clojure/clojurescript   {:mvn/version "1.12.145"}}
 :aliases
 {:cljs {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.11"}}}}}
```

### With Reagent (browser)
In Reagent, `template-to-hiccup` returns a hiccup vector that Reagent renders directly — no HTML string step needed.

```clojure
(ns my-app.core
  (:require [reagent.core :as r]
            [hiccup-templating.core :refer [from-string template-to-hiccup]]))

(def template
  (from-string "{:template [:div [:h1 :data/title] #when :data/subtitle [:p :data/subtitle]]}"))

(defn my-component [data]
  ;; template-to-hiccup returns a hiccup vector — pass directly to Reagent
  (template-to-hiccup template data))

;; Usage:
(r/render [my-component {:title "Hello" :subtitle "World"}]
          (.getElementById js/document "app"))
```

### As HTML string (Node.js / SSR)
```clojure
(ns my-app.render
  (:require [hiccup-templating.core :refer [from-string template-to-hiccup as-xhtml-string]]))

(def template
  (from-string "{:template [:html [:body [:h1 :data/title]]]}"))

(defn render [data]
  (-> (template-to-hiccup template data)
      (as-xhtml-string)))
```

> In CLJS, `as-xhtml-string` produces plain HTML5 (no XHTML doctype). If a doctype is required prepend it manually.

### Escaping user input in CLJS
```clojure
(as-xhtml-string-escaped hiccup-tree)
```
Both `as-xhtml-string-escaped` and `as-xhtml-string` are available cross-platform. Use the `-escaped` variant whenever the data map may contain user input.


# Examples with hiccup2 html parser (JVM)

## Running the below code
```clojure
(let [t (from-file "test/resources/" "template_a.edn")
      d (from-file "test/resources/" "data_a.edn")
      f (template-to-hiccup t d {:template-key :sub-template})
      h (template-to-hiccup t (assoc d :footer f))]
  (hiccup2.core/html h))
```

## Results in
  
### Template file contents
```clojure
{:template
 [:html
  [:head]
  [:body
   #when :data/texts.intro [:header :data/texts.intro]
   #when-or [[:data/texts.nonexistent :data/texts.elementtext]
             [:container
              [:div [:span "This is some text"] [:strong #when :data/texts.elementtext :data/texts.elementtext]]]]
   #when-and [[:data/texts.available :data/texts.elementtext_two]
              [:container [:p :data/texts.elementtext_two]]]
   #when :data/footer :data/footer]]
 :sub-template
 [:footer
  [:div #or [:data/texts.doesntexist :data/texts.footer]]]}
```
  
### Data map contents
```clojure
{:texts
 {:intro           "Hello from Clojure!"
  :elementtext     ", this some other text"
  :available       true
  :elementtext_two "I am text, therefore i must be read"
  :footer          "Goodbye now, come again!"}}
```
  
### Result after processing the template and filling it with the data
```clojure
; template
[:html
 [:head]
 [:body
  [:header "Hello from Clojure!"]
  [:container [:div [:span "This is some text"] [:strong ", this some other text"]]]
  [:container [:p "I am text, therefore i must be read"]]]]
; sub-template
[:footer [:div "Goodbye now, come again!"]]
```
  
### Result html without adding the footer
Generated with `(hiccup2.core/html input)`  
```html
<html>
<head></head>
<body>
    <header>Hello from Clojure!</header>
    <container>
        <div><span>This is some text</span><strong>, this some other text</strong></div>
    </container>
    <container>
        <p>I am text, therefor i must be read</p>
    </container>
</body>
</html>
```

### Result html with adding the footer
```html
<html>
<head></head>
<body>
    <header>Hello from Clojure!</header>
    <container>
        <div><span>This is some text</span><strong>, this some other text</strong></div>
    </container>
    <container>
        <p>I am text, therefore i must be read</p>
    </container>
    <footer>
        <div>Goodbye now, come again!</div>
    </footer>
</body>
</html>
```

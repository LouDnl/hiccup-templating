(ns hiccup-templating.core
  "EDN-driven Hiccup template engine
   Templates are filled at runtime with a Clojure data map.

   ## Tagged-literal grammar
   Tag                              Meaning
   -------------------------------- ----------------------------------------
   `:data/foo.bar`                  Look up `[:foo :bar]` in `data`.
                                    Resolves to the value, or to the
                                    `::missing` sentinel when absent / nil.

   `#data/foo.bar <form>`           Back-compat shorthand for
                                    `#when :data/foo.bar <form>` - used by
                                    the original template; kept so old
                                    templates still parse.

   `#when <test> <body>`            Splice `<body>` if `<test>` resolves;
                                    drop otherwise. `<body>` can be a
                                    single form or a vector of forms.

   `#when-not <test> <body>`        Inverse of `#when`.

   `#when-and [<tests>] <body>`     Splice `<body>` if EVERY test in the
                                    `<tests>` vector resolves. Cheap way
                                    to gate a row on multiple data
                                    paths.

   `#when-or  [<tests>] <body>`     Splice `<body>` if AT LEAST ONE
                                    test in `<tests>` resolves.

   `#or [<expr> <fallback>]`        Evaluate `<expr>`; if it resolves to
                                    `::missing`, evaluate `<fallback>`.
                                    Two special fallbacks are recognised:
                                      `:remove/element`  drop the element
                                                         that holds this
                                                         `#or`.
                                      `:remove/parent`   drop the parent
                                                         of that element.
                                    Any other fallback is spliced as a
                                    plain value.

   ## How element removal works

   `:remove/element` and `:remove/parent` are not values - they are
   sentinels that propagate up the walk:

     * a vector / list whose child returned `::drop-element` drops itself
       (returns `::missing` so it disappears from its own parent);
     * a vector / list whose child returned `::drop-parent` returns
       `::drop-element`, so the level above drops itself instead.

   That two-hop hand-off means `:remove/parent` deletes exactly the
   element one level above the `#or` form - typically a `:tr` row whose
   inner `:td` referenced a missing value.

   ## Public API

   `from-file` / `from-string` / `from-stream`
     Read an EDN template and wrap user-defined tags as `tagged-literal`s.

   `template-to-hiccup`
     Walk a template + data map, returning a Hiccup vector tree with
     missing branches pruned.

   `as-xhtml-string` / `as-xhtml-stream`
     Render a Hiccup tree to XHTML (with `<!DOCTYPE>`) for the
     flying-saucer PDF pipeline."
  (:require
   [hiccup-templating.core.parser]
   [hiccup-templating.core.reader]
   [hiccup-templating.core.walker]))

(set! *warn-on-reflection* true)

  from-file hiccup-templating.core.reader/template-from-edn-file)
(def ^{:arglists (quote ([ednstream])), :doc "Reads an EDN html template from provided stream"}
  from-stream hiccup-templating.core.reader/template-from-edn-stream)
(def ^{:arglists (quote ([ednstring])), :doc "Reads an EDN html template from provided string"}
  from-string hiccup-templating.core.reader/template-from-edn-string)
(def ^{:arglists (quote ([hiccup-ds])), :doc "Parses provided hiccup datastructure into xhtml with unescaped strings and returns it as stream"}
  as-xhtml-stream hiccup-templating.core.parser/hiccup-xhtml-stream)
(def ^{:arglists (quote ([hiccup-ds])), :doc "Parses provided hiccup datastructure into xhtml with unescaped strings and returns it as string"}
  as-xhtml-string hiccup-templating.core.parser/hiccup-xhtml-string)
(def ^{:arglists (quote ([template data
                          & {:keys [template-key]
                             :or   {template-key :template}}])), :doc "Parses and fills provided template under :template-key with provided data"}
  template-to-hiccup hiccup-templating.core.walker/template-to-hiccup)

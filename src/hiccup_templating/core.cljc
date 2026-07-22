(ns hiccup-templating.core
  "EDN-driven Hiccup template engine.
   Templates are stored as EDN and filled at runtime with a Clojure
   data map, producing a Hiccup vector tree that can then be rendered
   to XHTML for downstream sinks (flying-saucer PDF pipeline, HTTP
   responses, email bodies, ...).

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

   Reading templates:

   `from-file`    Load an EDN template from disk. Optional
                  `:validatepath true` rejects `..` segments in the
                  path or filename and refuses any file whose canonical
                  path escapes the supplied base directory. Without
                  the flag the caller is trusted with the file-system
                  boundary.
   `from-stream`  Read an EDN template from a `PushbackReader` (e.g.
                  an uploaded stream).
   `from-string`  Read an EDN template from a string (e.g. a slurped
                  file).

   Filling templates:

   `template-to-hiccup`
                  Walk a template + data map, returning a Hiccup vector
                  tree with missing branches pruned. The template slot
                  read is `:template` by default; override with
                  `:template-key`.

   Rendering to XHTML:

   `as-xhtml-string` / `as-xhtml-stream`
                  Render a Hiccup tree to XHTML (with `<!DOCTYPE>`).
                  String values are emitted **unescaped** so pre-built
                  HTML fragments (`&nbsp;`, `<br/>`, ...) survive - use
                  these when the sink is the flying-saucer PDF pipeline
                  and the data map is trusted.
   `as-xhtml-string-escaped` / `as-xhtml-stream-escaped`
                  Same as above but HTML-escapes every string value in
                  the tree. Use these when rendering to any HTML sink
                  where the data map may contain user input."
  (:require
   [hiccup-templating.core.parser]
   [hiccup-templating.core.reader]
   [hiccup-templating.core.walker]))

#?(:clj (set! *warn-on-reflection* true))


#?(:clj
   (def ^{:arglists (quote ([path filename & {:keys [validatepath max-bytes max-depth]}]))
          :doc      "Reads an EDN template file at `<path>/<filename>`. Trailing
      slashes on `path` are tolerated; `filename` must end in `.edn`.
 
      Options (all opt-in):
        `:validatepath true` - reject `..` segments in `path` or `filename`
                               and refuse any file whose canonical path
                               escapes the canonicalised `path`.
        `:max-bytes n`       - refuse to read the file if its size exceeds
                               `n` bytes, and abort mid-read once more
                               than `n` characters have streamed.
        `:max-depth n`       - throw `ex-info` if the template's nesting
                               depth exceeds `n` during the reader
                               postwalk.
 
      Throws `ex-info` when the extension check fails, when the file does
      not exist, when `:validatepath true` catches an escape attempt, or
      when a size / depth limit is exceeded."}
     from-file hiccup-templating.core.reader/template-from-edn-file))

#?(:clj
   (def ^{:arglists (quote ([ednstream & {:keys [max-bytes max-depth]}]))
          :doc      "Reads an EDN template from a `java.io.PushbackReader` (e.g. an
      uploaded stream). Unknown tagged literals are preserved as
      `tagged-literal` values so the walker can interpret them later.
 
      Options (all opt-in):
        `:max-bytes n` - abort mid-read once more than `n` characters have
                         streamed through the reader.
        `:max-depth n` - throw `ex-info` if the template's nesting depth
                         exceeds `n` during the reader postwalk."}
     from-stream hiccup-templating.core.reader/template-from-edn-stream))

(def ^{:arglists (quote ([ednstring & {:keys [max-bytes max-depth]}]))
       :doc      "Reads an EDN template from a string (e.g. a slurped file).
   Unknown tagged literals are preserved as `tagged-literal` values so
   the walker can interpret them later.

   Options (all opt-in):
     `:max-bytes n` - throw before parsing when the UTF-8 byte length
                      of `ednstring` exceeds `n`.
     `:max-depth n` - throw `ex-info` if the template's nesting depth
                      exceeds `n` during the reader postwalk."}
  from-string hiccup-templating.core.reader/template-from-edn-string)

#?(:clj
   (def ^{:arglists (quote ([hiccup-ds]))
          :doc      "Renders a Hiccup datastructure as XHTML 1.0 strict and returns
      it as a `java.io.ByteArrayOutputStream`. String values are emitted
      **unescaped** so pre-built HTML fragments survive - intended for the
      flying-saucer PDF pipeline with a trusted data map. Use
      `as-xhtml-stream-escaped` if the data map may contain user input."}
     as-xhtml-stream hiccup-templating.core.parser/hiccup-xhtml-stream))

(def ^{:arglists (quote ([hiccup-ds]))
       :doc      "Renders a Hiccup datastructure as XHTML 1.0 strict and returns
   it as a string. String values are emitted **unescaped** so pre-built
   HTML fragments survive - intended for the flying-saucer PDF pipeline
   with a trusted data map. Use `as-xhtml-string-escaped` if the data
   map may contain user input."}
  as-xhtml-string hiccup-templating.core.parser/hiccup-xhtml-string)

#?(:clj
   (def ^{:arglists (quote ([hiccup-ds]))
          :doc      "Renders a Hiccup datastructure as XHTML 1.0 strict and returns
      it as a `java.io.ByteArrayOutputStream`. Every string value in the
      tree is HTML-escaped, making this the safe choice when rendering to
      any HTML sink with a data map that may contain user input."}
     as-xhtml-stream-escaped #(hiccup-templating.core.parser/hiccup-xhtml-stream % {:escape-strings true})))

(def ^{:arglists (quote ([hiccup-ds]))
       :doc      "Renders a Hiccup datastructure as XHTML 1.0 strict and returns
   it as a string. Every string value in the tree is HTML-escaped,
   making this the safe choice when rendering to any HTML sink with a
   data map that may contain user input."}
  as-xhtml-string-escaped #(hiccup-templating.core.parser/hiccup-xhtml-string % {:escape-strings true}))

(def ^{:arglists (quote ([template data
                          & {:keys [template-key max-depth]
                             :or   {template-key :template}}]))
       :doc      "Walks the template slot under `:template-key` (default
   `:template`) of `template`, resolving `:data/...` lookups and
   tagged-literal directives against `data`. Returns the expanded
   Hiccup vector tree with missing branches pruned, or `nil` when the
   entire template resolves away.

   Options:
     `:template-key k` - override which template slot to expand.
                          Defaults to `:template`.
     `:max-depth n`    - throw `ex-info` when the walker recurses more
                          than `n` levels deep. Opt-in - `nil`
                          (default) means no limit."}
  template-to-hiccup hiccup-templating.core.walker/template-to-hiccup)

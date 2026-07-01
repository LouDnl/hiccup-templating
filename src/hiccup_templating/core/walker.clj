(ns hiccup-templating.core.walker
  (:require
   [hiccup-templating.core.sentinels
    :refer [MISSING DROP-ELEMENT DROP-PARENT]]
   [hiccup-templating.core.data
    :refer [data-path lookup-data]]))

(set! *warn-on-reflection* true)


;;; Walker
;;
;; One pass, no atoms. Returns either an expanded value or one of the
;; sentinels above. Vector / list nodes turn child sentinels into their
;; own sentinel (or simply drop the child for `::missing`).

(def ^:dynamic *max-depth*
  "When bound to a positive integer, `expand` throws once the recursion
   depth exceeds this value. `nil` (the default) means no limit."
  nil)

(def ^:dynamic ^:private *depth* 0)

(declare expand)

;; Tags that gate a body. EDN tagged-literals only consume ONE form,
;; so when a template author writes `#when :data/foo [:tr ...]`, the
;; `[:tr ...]` arrives as a SIBLING of the tag, not as part of it.
;; collect-children pairs a bodyless guard with the following sibling
;; so the body can still be gated by the test.

(def ^:private guard-names #{"when" "when-not" "when-and" "when-or"})

(defn- guard-tag?
  [node]
  (and (tagged-literal? node)
       (let [t (:tag node)]
         (and (nil? (namespace t))
              (contains? guard-names (name t))))))

(defn- guard-bodyless?
  "True when a guard tag's captured form is just the test (or just the
   tests-vector for #when-and / #when-or), with no body baked in."
  [tagged]
  (let [n (name (:tag tagged))
        f (:form tagged)]
    (cond
      ;; #when-and / #when-or: form should be `[<tests-vec> <body>...]`.
      ;; Only the tests-vec present -> body missing.
      (or (= n "when-and") (= n "when-or"))
      (or (not (vector? f))
          (and (vector? f)
               (= 1 (count f))
               (vector? (first f))))

      ;; #when / #when-not: form should be `[<test> <body>...]`. Bare
      ;; test (a keyword, tagged-literal, ...) means no body.
      :else
      (not (vector? f)))))

(defn- pair-guard-with-body
  "Wraps `next-form` into the guard's body slot."
  [guard next-form]
  (let [n (name (:tag guard))
        f (:form guard)]
    (cond
      (or (= n "when-and") (= n "when-or"))
      (tagged-literal (:tag guard)
                      (cond
                        (vector? f) (conj f next-form)
                        :else       [f next-form]))

      :else
      (tagged-literal (:tag guard) [f next-form]))))

(defn- collect-children
  "Expands every entry in `xs`. Returns either:
     `{:result <vec>}`     - kept children, missing ones dropped;
     `{:drop  DROP-ELEMENT}` - caller should drop itself;
     `{:drop  DROP-PARENT}`  - caller's parent should drop itself.

   When a child is a bodyless guard tag (e.g. `#when :data/foo`), the
   next sibling is consumed as the guard's body before walking - that
   is how `#when :data/foo [:tr ...]` ends up gating the `:tr`."
  [xs data]
  (loop [xs (seq xs), acc (transient [])]
    (if (nil? xs)
      {:result (persistent! acc)}
      (let [head (first xs)
            tail (next xs)
            [node tail']
            (if (and (guard-tag? head)
                     (guard-bodyless? head)
                     (seq tail))
              [(pair-guard-with-body head (first tail)) (next tail)]
              [head tail])
            v (expand node data)]
        (cond
          (= v DROP-ELEMENT) {:drop DROP-ELEMENT}
          (= v DROP-PARENT)  {:drop DROP-PARENT}
          (= v MISSING)      (recur tail' acc)
          :else              (recur tail' (conj! acc v)))))))

(defn- splice
  "Hands a body of forms back as either a single value (when there's
   exactly one) or a seq (so `seq?` branch in `expand` flattens them
   into the surrounding vector)."
  [forms]
  (case (count forms)
    0 MISSING
    1 (first forms)
    (seq forms)))

(defn- expand-tag
  "Resolves a tagged-literal node. The tag's name picks the form; the
   `data/...` namespace is reserved for the back-compat data-lookup
   shorthand."
  [tag form data]
  (let [t-ns   (namespace tag)
        t-name (name tag)]
    (cond
      ;; #data/foo.bar <form> - original syntax, equivalent to
      ;; #when :data/foo.bar <form>. Kept so existing templates parse.
      (= "data" t-ns)
      (let [v (lookup-data (data-path (symbol t-name)) data)]
        (if (= v MISSING) MISSING (expand form data)))

      (= "when" t-name)
      (let [[test & body] (if (vector? form) form [form])]
        (if (= (expand test data) MISSING)
          MISSING
          (let [{:keys [result drop]} (collect-children body data)]
            (condp = drop
              DROP-ELEMENT MISSING
              DROP-PARENT  DROP-ELEMENT
              (splice result)))))

      (= "when-not" t-name)
      (let [[test & body] (if (vector? form) form [form])]
        (if (= (expand test data) MISSING)
          (let [{:keys [result drop]} (collect-children body data)]
            (case drop
              DROP-ELEMENT MISSING
              DROP-PARENT  DROP-ELEMENT
              (splice result)))
          MISSING))

      ;; #when-and / #when-or - first slot is a vector of tests, the
      ;; rest of the form is the body (splice semantics shared with
      ;; #when). Distinguishing tests from body via a vector slot keeps
      ;; the EDN reader happy (one form per tagged literal) and avoids
      ;; sentinel keywords as delimiters.
      (or (= "when-and" t-name) (= "when-or" t-name))
      (let [[tests & body] form
            results        (map #(expand % data) tests)
            present?       (fn [r] (not (= r MISSING)))
            pass?          (if (= "when-and" t-name)
                             (every? present? results)
                             (some present? results))]
        (if-not pass?
          MISSING
          (let [{:keys [result drop]} (collect-children body data)]
            (condp = drop
              DROP-ELEMENT MISSING
              DROP-PARENT  DROP-ELEMENT
              (splice result)))))

      (= "or" t-name)
      (let [[expr fallback] form
            v               (expand expr data)]
        (if (= v MISSING) (expand fallback data) v))

      :else
      ;; Unknown tag - pass the form through untouched. Keeps the walker
      ;; permissive so callers can extend with custom edn-readers
      ;; without losing template content.
      form)))

(defn expand
  "Walks `node`, resolving `:data/...` lookups and tagged-literal
   directives against `data`. Returns either the expanded structure or
   one of the sentinels declared above. Callers normally hand the
   result to `template-to-hiccup`, which strips trailing sentinels.

   Honours `*max-depth*`: throws `ex-info` if the walker's recursion
   depth exceeds the bound value."
  [node data]
  (binding [*depth* (inc *depth*)]
    (when (and *max-depth* (> *depth* (long *max-depth*)))
      (throw (ex-info "template exceeds max walker depth"
                      {:max-depth *max-depth*
                       :depth     *depth*})))
    (cond
      (= node :remove/element) DROP-ELEMENT
      (= node :remove/parent)  DROP-PARENT

      (and (keyword? node) (= (namespace node) "data"))
      (lookup-data (data-path (symbol (name node))) data)

      (tagged-literal? node)
      (expand-tag (:tag node) (:form node) data)

      (map? node)
      (into {}
            (keep (fn [[k v]]
                    (let [vv (expand v data)]
                      (when-not (= vv MISSING) [k vv]))))
            node)

      (vector? node)
      (let [{:keys [drop result]} (collect-children node data)]
        (condp = drop
          DROP-ELEMENT MISSING
          DROP-PARENT  DROP-ELEMENT
          result))

      (seq? node)
      (let [{:keys [drop result]} (collect-children node data)]
        (condp = drop
          DROP-ELEMENT MISSING
          DROP-PARENT  DROP-ELEMENT
          (seq result)))

      :else node)))

(defn template-to-hiccup
  "Top-level entry point. Resolves the `template-key` slot of `template`
   against `data`. Returns nil when the entire template prunes away.

   Options:
     `:template-key k` - override which template slot to expand.
                          Defaults to `:template`.
     `:max-depth n`    - throw `ex-info` when the walker recurses more
                          than `n` levels deep. Opt-in - nil (default)
                          means no limit."
  [template data
   & {:keys [template-key max-depth]
      :or   {template-key :template}}]
  (binding [*max-depth* max-depth]
    (let [out (expand (get template template-key) data)]
      (if (#{MISSING DROP-ELEMENT DROP-PARENT} out) nil out))))

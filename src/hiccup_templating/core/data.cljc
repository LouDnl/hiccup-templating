(ns hiccup-templating.core.data
  (:require
   [clojure.string
    :refer [split]]
   [hiccup-templating.core.sentinels
    :refer [MISSING]]))

#?(:clj (set! *warn-on-reflection* true))


;;; Data lookup

(defn data-path
  [s]
  (mapv keyword (split (name s) #"\.")))


(defn lookup-data
  [path data]
  (let [v (get-in data path MISSING)]
    (if (or (= v MISSING) (nil? v)) MISSING v)))

(ns build
  (:require
   [aero.core :refer [read-config]]
   [clojure.java.io :refer [reader]]
   [clojure.pprint]
   [clojure.string]
   [clojure.tools.build.api :as b]))

(comment
  "https://clojure.org/guides/tools_build"
  "https://clojure.github.io/tools.build/clojure.tools.build.api.html"
  "https://kozieiev.com/blog/packaging-clojure-into-jar-uberjar-with-tools-build/")

(set! *warn-on-reflection* true)

(def ^:const config-filename "config.edn")
(def ^:const config-filelocation "./")

(defn config []
  (when-let [cfg (str config-filelocation config-filename)]
    (with-open [config (reader cfg)]
      (read-config config))))


;;; Basic settings

(def build-folder "target")
(def jar-content (str build-folder "/" "classes"))  ;; folder where we collect files to pack in a jar
(def basis (b/create-basis {:project :standard :user :standard}))  ;; basis structure
(def version (-> (config) :app :version))  ;; library version
(def pom-project (-> (config) :app :pom/project))
(def pom-license (-> (config) :app :pom/license))
(def pom-data (into pom-license pom-project))

;; JAR
(def lib-name (-> (config) :compile :jar :lib))  ;; library name
(def jar-file-name (format "%s/%s-%s.jar" build-folder (name lib-name) version))  ;; path for result jar file

;; UBERJAR
(def app-name (-> (config) :app :name))
(def uber-file-name (format "%s/%s-%s-standalone.jar" build-folder app-name version))  ;; path for result uber file


;;; Main build functions

(defn write-pom  ; clj -T:build write-pom
  [_]
  (println "Cleaning existing POM files")
  (b/delete {:path "target/classes/META-INF/maven/nl.loudai/hiccup-templating/pom.xml"})
  (b/delete {:path "./pom.xml"})
  (println "Writing POM")
  (b/write-pom {:class-dir jar-content
                :lib       lib-name
                :version   version
                :basis     basis
                :src-dirs  ["src"]
                :pom-data  pom-data})
  (b/copy-file {:src    "target/classes/META-INF/maven/nl.loudai/hiccup-templating/pom.xml"
                :target "./pom.xml"}))

(defn clean  ; clj -T:build clean
  [_]
  (println "Cleaning build folder")
  (b/delete {:path build-folder})
  (println (format "Build folder \"%s\" removed" build-folder)))

(defn cleancpcache  ; clj -T:build cleancpcache
  [_]
  (println "Cleaning cpcache cache")
  (b/delete {:path ".cpcache"})
  (println ".cpcache cleaned"))

(defn cleanall  ; clj -T:build cleanall
  [_]
  (clean nil)
  (println "Cleaning calva cache")
  (b/delete {:path ".calva/deps.clj.jar"})
  (b/delete {:path ".calva/output-window/output.calva-repl"})
  (cleancpcache nil)
  (println "Cleaning clj-kondo cache")
  (b/delete {:path ".clj-kondo/.cache"})
  (println "Cleaning lsp cache")
  (b/delete {:path ".lsp/.cache"})
  (println "Completed cleanall"))

(defn jar ; clj -T:build jar
  "Build a library jar for Clojars"
  [_]
  (clean nil) ; clean leftovers
  (println "Copying files") ; prepare jar content
  (b/copy-dir    {:src-dirs   ["src"
                               "resources"]
                  :ignores    ["^timedate.*"]
                  :target-dir jar-content})
  (write-pom nil) ; create pom.xml
  (println "Creating jar") ; create jar
  (b/jar         {:class-dir  jar-content
                  :jar-file   jar-file-name})
  (println (format "Jar file created: \"%s\"" jar-file-name)))

(defn print-env
  "Write environment files for debugging ~ use when needed"
  [envname]
  (spit (format "%s-getEnv.md" envname)
        (with-out-str (clojure.pprint/pprint (into {} (System/getenv)))))
  (spit (format "%s-getProperties-noclasspath.md" envname)
        (with-out-str (clojure.pprint/pprint (dissoc (into {} (System/getProperties)) "java.class.path"))))
  (spit (format "%s-getProperties-classpath.md" envname)
        (with-out-str (clojure.pprint/pprint {"java.class.path" (clojure.string/split (get (into {} (System/getProperties)) "java.class.path") #":")}))))

(defn uberjar ; clj -T:build uberjar
  "Build an uberjar for standalone running"
  [_]
  (clean nil) ; clean leftovers
  (println "Copying files") ; prepare uberjar content
  (b/copy-dir    {:src-dirs   ["src"]  ;; needed for repl development e.g. clojure.repl/source
                  :target-dir jar-content})
  ;; 20240119 ~ Updated complete copy of resources folder to specific folders and files only
  (b/copy-dir    {:src-dirs   ["resources/migrations"]
                  :target-dir (str jar-content "/migrations")})
  (b/copy-dir    {:src-dirs   ["resources/migrationsdss"]
                  :target-dir (str jar-content "/migrationsdss")})
  (b/copy-dir    {:src-dirs   ["resources/public"]
                  :target-dir (str jar-content "/public")})
  (b/copy-dir    {:src-dirs   ["resources/queries"]
                  :target-dir (str jar-content "/queries")})
  (b/copy-dir    {:src-dirs   ["resources/wizportaal"]
                  :target-dir (str jar-content "/wizportaal")})
  (b/copy-file   {:src    "resources/config.edn"
                  :target (str jar-content "/config.edn")})
  (b/copy-file   {:src    "resources/config-test.edn"
                  :target (str jar-content "/config-test.edn")})
  (write-pom nil) ; create pom.xml
  (println "Compiling Clojure") ; compile clojure code
  (b/compile-clj {:basis      basis
                  :src-dirs   ["src"]
                  :class-dir  jar-content})
  (println "Making uberjar") ; create uber file
  (b/uber        {:class-dir  jar-content
                  :uber-file  uber-file-name
                  :basis      basis
                  :main       (quote (-> (config) :compile :uberjar))})  ;; here we specify the entry point for uberjar
  (println (format "Uber file created: \"%s\"" uber-file-name)))

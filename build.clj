(ns build
  (:require
   [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file "target/wavelength-standalone.jar")

(defn clean [_]
  (b/delete {:path "target"})
  (b/delete {:path "resources/public/main.js"})
  (b/delete {:path "resources/public/cljs-out/"}))

(defn cljs [_]
  (let [basis    (b/create-basis {:aliases [:fig :min]})
        cmds     (b/java-command {:basis     basis
                                  :java-opts (:jvm-opts basis)
                                  :main      'clojure.main
                                  :main-args ["-m" "figwheel.main" "-O" "advanced" "-bo" "dev"]})
        {:keys [exit]} (b/process cmds)]
    (b/delete {:path "resources/public/cljs-out/"})
    (when-not (zero? exit)
      (throw (ex-info "Figwheel failed" {})))))

(defn uber [_]
  (clean nil)
  (cljs nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'wavelength.main}))

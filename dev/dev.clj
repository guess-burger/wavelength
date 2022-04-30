(ns dev
  (:require [org.httpkit.client :as http]
            [clojure.string :as str]
            [cheshire.core :as json]
            [wavelength.main :as wv]))

;; telewave has the wavelengths in a js file
(def ^:private wave-data "https://raw.githubusercontent.com/gjeuken/telewave/master/scripts/data.js")

(defn generate-waves
  []
  (let [{:keys [status headers body error] :as resp} @(http/get wave-data)
        body (str/replace body #"(var data = |;)" "")
        data (json/parse-string body)]
    (spit "resources/prompts.edn" (pr-str data))))


(defn start []
  (wv/-main))



(comment

  (generate-waves)

  )

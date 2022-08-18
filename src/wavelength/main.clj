(ns wavelength.main
  (:require
    [chord.http-kit :as chord]
    [clojure.core.async :as as]
    [clojure.core.async.impl.protocols :as p]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [hiccup.core :as hc]
    [hiccup.util :as hcu]
    [org.httpkit.server :as http]
    [ring.middleware.defaults :refer :all]
    [wavelength.game :as game])
  (:gen-class)
  (:import (java.io Writer)))

(defn index-page []
  (hc/html
    [:head
     [:title "Wavelength"]
     [:link {:rel "stylesheet" :href (hcu/to-uri "/main.css")}]]
    [:body
     [:div#container]
     [:script {:type "text/javascript", :src (hcu/to-uri "/js/main.js")}]]))

;; Bidi from Chord but into a record rather than reified
(defrecord Bidi [read-ch write-ch on-close]
  p/ReadPort
  (take! [_ handler]
    (p/take! read-ch handler))

  p/WritePort
  (put! [_ msg handler]
    (p/put! write-ch msg handler))

  p/Channel
  (close! [_]
    (p/close! read-ch)
    (p/close! write-ch)
    (when on-close
      (on-close))))
(defmethod print-method Bidi [bidi ^Writer writer]
  (print-method (str "<Bidi:" (.hashCode bidi) ">") writer))

(defn ^:private bidi-ch [read-ch write-ch & [{:keys [on-close]}]]
  (Bidi. read-ch write-ch on-close))

(defn ^:private strip-message-in-ch
  "What a lovely hack to just get around chord adding :message to everything"
  [ws-ch]
  ;; FIXME is this really the best way to do this?
  (let [xform (comp
               ;; remove chord wrapping :message
               (map :message)
               ;; filter out keep-alives from clients
               (filter #(not= :keep-alive %)))
        in-ch (as/pipe ws-ch (as/chan 1 xform))]
    (bidi-ch in-ch ws-ch)))

;; FIXME this is a bad name
(defn ws-handler [nickname room-code req]
  ;; unified API for WebSocket and HTTP long polling/streaming
  (chord/with-channel req ws-ch    ; get the channel
    (let [ch (strip-message-in-ch ws-ch)]
      (game/create-or-join-lobby ch nickname room-code))))

(defroutes main-routes
  (GET "/" [] (index-page))
  (GET "/lobby" [nickname room :as req] (ws-handler nickname room req))
  (route/resources "/")
  (route/not-found "Page not found"))

(defn -main
  [& [port]]
  (let [port (Integer. (or port 8080))]
    (println "Stating on port" port)
    (http/run-server (wrap-defaults #'main-routes site-defaults) {:port port})))

(comment

  (-main)

  ,)
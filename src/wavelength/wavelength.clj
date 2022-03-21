(ns wavelength.wavelength
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
    [wavelength.team-lobby :as lobby]
    [lib.stately.core :as st])
  (:gen-class))

(defn index-page []
  (hc/html
    [:head
     [:title "Hello test"]]
    [:body
     [:h1 "hello world"]
     [:div#container]
     [:script {:type "text/javascript", :src (hcu/to-uri "/js/main.js")}]]))

(defn ^:private bidi-ch [read-ch write-ch & [{:keys [on-close]}]]
  "Shamelessly taken from chord to allow us to maintain the same BiDi semantics "
  (reify
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
        (on-close)))))

(defn ^:private strip-message-in-ch
  "What a lovely hack to just get around chord adding :message to everything"
  [ws-ch]
  (let [in-ch (as/pipe ws-ch (as/chan 1 (map :message)))]
    (bidi-ch in-ch ws-ch)))

;; FIXME this is a bad name
(defn ws-handler [nickname room-code req]
  ;; unified API for WebSocket and HTTP long polling/streaming
  (chord/with-channel req ws-ch    ; get the channel
                      (let [ch (strip-message-in-ch ws-ch)
                            ;lobby-ch (and room-code (get @lobby/lobbies room-code))
                            ]
                        (lobby/create-or-join-lobby ch nickname room-code {})
                        #_(if lobby-ch
                          (lobby/join-lobby ch nickname lobby-ch)
                          (lobby/create-lobby ch nickname {})))))

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
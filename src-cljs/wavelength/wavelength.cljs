(ns wavelength.wavelength
  (:require
   [chord.client :refer [ws-ch]]
   [cljs.core.async :as async :refer [chan <! >! put! close! go go-loop]]
   [clojure.string :as str]
   [goog.uri.utils :as guri]
   [reagent.core :as r]
   [reagent.dom :as dom]
   ))

(defonce send-chan (chan))

(defonce state (r/atom {}))

(enable-console-print!)
(add-watch state :debug (fn [_key _atom old-st new-st] (println old-st "->"new-st)))

(defn send-msg-loop
  [svr-chan]
  (go-loop []
     (when-let [msg (<! send-chan)]
       ;; FIXME I think the whole go-loop from send-chan to
       ;; serv-chan is a bit strange to be honest
       ;; I _think_ the point is to hide the bi-directional channel
       ;; since we don't really want anything other than the receive-loop
       ;; actually taking the messages.. (so why does chord big up the fact it is bi-di then?)
       (println "send-msg-loop sending" msg)
       (when (>! svr-chan msg)
         (recur)))))

(defn receive-msg-loop
  [svr-chan nickname]
  (println nickname)
  (go-loop []
     (if-let [new-msg (:message (<! svr-chan))]
       (do
         (println "got: " new-msg)
         (case  (:type new-msg)
           :reset
           (swap! state assoc :game-state (dissoc new-msg :type))

           :merge
           (swap! state update :game-state merge (dissoc new-msg :type))

           :ignored)
         (recur))
       (do
         (js/console.log "Websocket closed")
         (reset! state {})))))


(defn- append-params
  [url params]
  (guri/appendParamsFromMap  url (clj->js params)))

(defn start-echo [url nickname room]
  (go
    (let [{:keys [ws-channel error]} (<! (ws-ch (append-params url {:nickname nickname :room room})))]
      (if-not error
        (do
          (send-msg-loop ws-channel)
          (receive-msg-loop ws-channel nickname)
          ;; FIXME this feels rather strange! <- WHY?
          ;; FIXME maybe put this into a loading or something and get the server to confirm mode and nickname
          ;; FIXME because what happens if a nickname is already taken?
          (swap! state assoc :game-state {:nickname nickname :mode :team-lobby}))
        (swap! state assoc :error error)))))

(defn create-room []
  (let [nickname (atom "")
        room     (atom "")
        url      (-> (.. js/document -location -origin)
                     (str/replace #"^http" "ws")
                     (str "/lobby"))]
    (fn []
      [:<>
       [:form {:on-submit (fn [x]
                            (.preventDefault x)
                            (start-echo url @nickname @room))}
        [:label {:for "nickname"} "Nickname:"]
        [:input#nickname {:type    "text"
                          :onInput (fn [x] (->> x .-target .-value (reset! nickname)))}]
        [:label {:for "room"} "Room Code:"]
        [:input#room {:type "text"
                      :onInput (fn [x] (->> x .-target .-value (reset! room)))}]
        [:input {:type  "submit"
                 :value "Submit"}]]])))

;; TODO remove this
(defn lobby []
  (let [{:keys [room-code members admin nickname]} (:game-state @state)]
    [:<>
     [:h2 (str "Room: " room-code)]
     [:h2 (str "Members: " members)]
     (when (= nickname admin)
       [:button {:type     "button"
                 :on-click #(put! send-chan {:type :start :message "hello"})} "Start"])
     [:button {:type     "button"
               :on-click (fn []
                           (put! send-chan {:type :leave})
                           (reset! state {}))} "Bye"]]))

(defn dump-state []
  [:<>
   (for [[k v] (:game-state @state)]
     [:p (str k " -> " v)])])

(defn- team-div
  [team-name team-key players]
  [:div
   [:p team-name]
   [:ul
    (for [player players]
      [:li player])]
   [:button {:on-click (fn [] (put! send-chan {:type :pick-team
                                               :team team-key}))}
    "Join"]])

(defn team-lobby []
  (let [{:keys [room-code left right spectators nickname ready msg]} (:game-state @state)]
    [:<>
     [:h2 "Team Lobby"]
     (when msg
       [:p msg])
     [:ul
      [:li (str "Room: " room-code)]
      [:li (str "nickname: " nickname)]]
     [team-div "Left" :left left]
     [team-div "Spectators" :spectators spectators]
     [team-div "Right" :right right]
     [:button {:disabled (not ready)
               :on-click (fn [] (put! send-chan {:type :start}))}
      "Start Game"]
     (for [[k v] (dissoc (:game-state @state) :room-code :left :right :spectators :nickname)]
       [:p (str k " -> " v)])]))

(def ^:private team-name
  {:left "Left Brain"
   :right "Right Brain"})

(defn pick-psychic []
  (let [{:keys [team-turn active]} (:game-state @state)]
    [:<>
     [:h2 "Pick Psychic"]
     (if active
       [:<>
        [:p "Choose a Psychic for the round"]
        [:button {:on-click #(put! send-chan {:type :pick-psychic})}
                 "Become The Psychic"]]
       [:p (str "Wait while " (team-name team-turn) " chooses their Psychic")])
     (for [[k v] (dissoc (:game-state @state) :team-turn :active)]
       [:p (str k " -> " v)])]))


(defn pick-wavelength []
  (let [{:keys [team-turn active wavelengths psychic]} (:game-state @state)
        [opt1 opt2] wavelengths]
    [:<>
     [:h2 "Pick Wavelength"]
     (if wavelengths
       [:<>
        ;;TODO allow picking but also allow getting new cards
        [:p "Pick a Wavelength for your team to guess for"]
        [:p (str (first opt1) " <--> " (second opt1))]
        [:button {:on-click #(put! send-chan {:type :pick
                                              :pick opt1})}
                 "Pick"]
        [:p (str (first opt2) " <--> " (second opt2))]
        [:button {:on-click #(put! send-chan {:type :pick
                                              :pick opt2})}
                 "Pick"]
        [:p ""]
        [:button {:on-click #(put! send-chan {:type :switch-cards})}
                 "Switch Cards"]]
       (let [msg (if active
                   (str psychic " is choosing a wavelength you to guess")
                   (str psychic " is choosing a wavelength for " (team-name team-turn) " to guess"))]
         [:<>
          [:p msg]]))
     [dump-state]]))

(defn app
  []
  (let [s (:game-state @state)]
    (case (:mode s)
      nil              [create-room]
      :team-lobby      [team-lobby]
      :pick-psychic    [pick-psychic]
      :pick-wavelength [pick-wavelength]
      [:div
       [:h2 "Eh?"]
       [dump-state]])))
;; eh?
(dom/render [app]
          (. js/document (getElementById "container")))
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
  (let [{:keys [team-turn active result]} (:game-state @state)]
    [:<>
     [:h2 "Pick Psychic"]
     (when result
       [:<>
        [:h3 "Result"]
        (for [[k v] result]
          [:p (str "Result " k " -> " v)])])
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
        [:button {:on-click #(put! send-chan {:type :pick-card
                                              :pick opt1})}
                 "Pick"]
        [:p (str (first opt2) " <--> " (second opt2))]
        [:button {:on-click #(put! send-chan {:type :pick-card
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

(defn pick-clue
  []
  (let [{:keys [target wavelength]} (:game-state @state)
        clue (atom "")]
    (fn []
      [:<>
       [:h2 "Pick clue"]
       [:p (str (first wavelength) " <--> " (second wavelength))]
       [:input {:type "range"
                :min  0 :max 100 :value target
                ;:disabled true
                }]
       [:p "Enter a clue"]
       [:form {:on-submit (fn [x]
                            (.preventDefault x)
                            (put! send-chan {:type :pick-clue
                                             :pick @clue}))}
        [:label {:for "clue"} "Clue:"]
        [:input#clue {:type    "text"
                      :onInput (fn [x] (->> x .-target .-value (reset! clue)))}]
        [:input {:type  "submit"
                 :value "Submit"}]]])))

(defn team-guess-guesser
  [guess]
  (let [slider    (r/atom guess)]
    (fn [guess]
      (println "GUESS!" guess)
      (reset! slider guess)
      [:<>
       [:p "Discuss the clue with your team and as a team decide where on the wavelength the clue sits"]
       [:input {:type      "range"
                :value     @slider
                :on-change (fn [x]
                             (let [x (js/parseInt (.. x -target -value))]
                               (put! send-chan {:type :move-guess :guess x})))}]
       [:button {:on-click #(put! send-chan {:type :pick-guess, :guess guess})}
                "Submit"]])))

(defn team-guess-listener
  [msg]
  (let [{:keys [guess]} (:game-state @state)]
    [:<>
     [:p msg]
     [:input {:type      "range"
              :value     guess}]]))

(defn team-guess
  []
  ;; TODO only the guessers should really be guessing
  ;; FIXME this isn't updating passed this way
  ;; probably because it's trying to have it's own state which doesn't
  (let [{:keys [active role team-turn guess clue]} (:game-state @state)
        ;; TODO trying to not get caught be old state
        safe-role (when active role)]
    [:div
     [:h2 "Team Guess"]
     [:p (str "Clue: " clue)]
     (case safe-role
       :psychic [team-guess-listener "Your team is guessing your clue"]
       :guesser [team-guess-guesser guess]
       nil [team-guess-listener (str (team-name team-turn)
                                     " is discussing their clue and picking where on the wavelength they think it fits")])
     [dump-state]]))

(defn left-right
  []
  (let [{:keys [wavelength role guess clue]} (:game-state @state)
        explanation (if (= :waiting role)
                      "Decide as a team if the target is Left or Right of the other teams guess to score points"
                      "<Other team> is deciding whether the target is left or right of <Guessing team>'s guess")]
    [:div
     [:h2 "Left-Right Phase"]
     [:p (str "Clue: " clue)]
     [:p explanation]
     ;; FIXME this is empty for spectators... did they never get sent the wavelength?!?!?
     ;; seems the same for the guessing members of the team!!
     [:p (str (first wavelength) " <--> " (second wavelength))]
     [:input {:type "range" :value guess}]
     (when (= :waiting role)
       [:<>
        [:button {:on-click #(put! send-chan {:type :pick-lr, :guess :left})}
                 "Left"]
        [:button {:on-click #(put! send-chan {:type :pick-lr, :guess :right})}
                 "Right"]
        [dump-state]])]))

(defn app
  []
  (let [s (:game-state @state)]
    (case (:mode s)
      nil              [create-room]
      :team-lobby      [team-lobby]
      :pick-psychic    [pick-psychic]
      :pick-wavelength [pick-wavelength]
      :pick-clue       [pick-clue]
      :team-guess      [team-guess]
      :left-right      [left-right]
      [:div
       [:h2 "Eh?"]
       [dump-state]])))
;; eh?
(dom/render [app]
          (. js/document (getElementById "container")))
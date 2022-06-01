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
      [:div {:style {:display "grid"
                     :grid-template-columns "1fr"
                     :justify-items "center"}}
       [:h1 #_{:style {:text-align "center"}} "Wavelength"]
       [:label {:for "nickname"} "Nickname"]
       [:input#nickname {:style {:margin-bottom "1em"}
                         :type    "text"
                         :onInput (fn [x] (->> x .-target .-value (reset! nickname)))}]
       [:label {:for "room"} "Room Code"]
       [:input#room {:style {:margin-bottom "1em"}
                     :type "text"
                     :onInput (fn [x] (->> x .-target .-value (reset! room)))}]
       [:button {:on-click #(start-echo url @nickname @room)}
        "Submit"]])))

(defn dump-state []
  [:<>
   (for [[k v] (:game-state @state)]
     [:p (str k " -> " v)])])

(defn- team-div
  [team-name team-key players pos]
  [:<>
   [:p {:style {:grid-row 1 :grid-column pos}} team-name]
   [:div {:style {:grid-row 2 :grid-column pos}}
    [:button
     {:on-click (fn [] (put! send-chan {:type :pick-team
                                        :team team-key}))}
     "Join"]]
   [:div {:style {:grid-row 3 :grid-column pos}}
    (for [player players]
      [:p player])]])

(defn team-lobby []
  (let [{:keys [room-code left right spectators nickname ready msg]} (:game-state @state)]
    [:<>
     [:h1 {:style {:text-align "center"}}
      "Wavelength"]
     (when msg
       [:p msg])
     [:ul
      [:li (str "Room: " room-code)]
      [:li (str "nickname: " nickname)]]
     [:div {:style {:display "grid"
                    :grid-template-columns "repeat(3, 1fr)"
                    :justify-items "center"}}
      [team-div "Left Brain" :left left 1]
      [team-div "Spectators" :spectators spectators 2]
      [team-div "Right Brain" :right right 3]
      [:div {:style    {:grid-column 2 :grid-row 4}}
       ;; using a div to stop the button expanding to fill grid
       ;; Probably a better way of doing that
       [:button {:disabled (not ready)
                 :on-click (fn [] (put! send-chan {:type :start}))}
        "Start Game"]]]
     #_(for [[k v] (dissoc (:game-state @state) :room-code :left :right :spectators :nickname)]
       [:p (str k " -> " v)])]))

(def ^:private team-name
  {:left "Left Brain"
   :right "Right Brain"})

(defn team-view
  [{:keys [score left right spectators]}]
  [:<>
   [:hr]
   [:div {:style {:display               "grid"
                  :grid-template-columns "repeat(3, 1fr)"
                  :justify-items         "center"}}
    [:p (str "Left Brain: " (:left score) "/9")]
    [:p (str (if (number? spectators) spectators (count spectators)) " Spectators")]
    [:p (str "Right Brain: " (:right score) "/9")]
    [:div
     (for [player left]
       [:p player])]
    [:div {:style {:grid-column 3}}
     (for [player right]
       [:p player])]]])

;; FIXME need a better name
(defn thinger
  "This is 'good enough' for now. It isn't perfect but that due to the way that browsers choose to render
   their sliders so the only way to really get around that will be to make a custom slider component.
   Or use on that works how I need it "
  [wavelength clue guess target on-change]
  [:div {:style {:display               "grid"
                 :grid-template-columns "repeat(3, 1fr)"
                 :justify-items         "center"}}
   [:p {:style {:grid-row 1 :grid-column 1}}
    (first wavelength)]
   [:p {:style {:grid-row 1 :grid-column 3}}
    (second wavelength)]
   [:div {:style {:grid-row              2
                  :width                 "100%"
                  :grid-column           "1 / 4"
                  :display               "grid"
                  :grid-template-columns "repeat(220, 1fr)"
                  :justify-items         "center"}}
    [:input {:style     {:width       "100%"
                         :grid-column " 2 / 220"
                         :grid-row    2
                         :z-index     5}
             :type      "range"
             :max       109
             :value     guess
             :on-change on-change
             }]
    (when target
      (let [centre (* 2 (+ 1 target))]
        [:<>
         [:div {:style {:background-color "SeaGreen"
                        :grid-row         "1 / 3"
                        :grid-column      (str (max 1 (- centre 5)) " / " (min 221 (+ centre 5)))
                        :width            "100%"
                        :z-index          4}}]
         [:div {:style {:background-color "Orange"
                        :grid-row         "1 / 3"
                        :grid-column      (str (max 1 (- centre 15)) " / " (min 221 (+ centre 15)))
                        :width            "100%"
                        :z-index          3}}]
         [:div {:style {:background-color "FireBrick"
                        :grid-row         "1 / 3"
                        :grid-column      (str (max 1 (- centre 25)) " / " (min 221 (+ centre 25)))
                        :width            "100%"
                        :z-index          2}}]]))]])

(defn psychics-clue
  [name clue]
  [:p {:style {:text-align "center"}}
   (str name "'s clue: ")
   [:strong clue]])

(def other-team
  {:left  :right
   :right :left})

(defn waiting-screen
  [{:keys [team-turn result wavelength] :as game-state} content]
  [:<>
   (when result
     (let [{:keys [guess target active-score waiting-score catch-up? psychic clue]} result
           active-name (team-name team-turn)
           waiting-name (-> team-turn other-team team-name) ]
       [:<>
        [psychics-clue psychic clue]
        [thinger wavelength nil guess target nil]
        #_[:h3 "Result"]
        [:p {:style {:text-align "center"}}
         (str active-name " scored " active-score " points")]
        (when (= 1 waiting-score)
          [:p {:style {:text-align "center"}}
           (str waiting-name " scored 1 point for their counter guess")])
        (when catch-up?
          [:p {:style {:text-align "center"}}
           (str "Catch rule in play, " active-name " goes again")])
        [:hr]]))
   [:div {:style {:display               "grid"
                  :grid-template-columns "1fr"
                  :justify-items         "center"}}
    content]
   [team-view game-state]])

(defn pick-psychic []
  (let [{:keys [team-turn active result wavelength] :as gs} (:game-state @state)]
    [:<>
     #_[:h2 "Pick Psychic"]
     [waiting-screen
      gs
      [:div {:style {:display               "grid"
                     :grid-template-columns "1fr"
                     :justify-items         "center"}}
       (if active
         [:<>
          [:p "Choose a Psychic for the round"]
          [:button {:on-click #(put! send-chan {:type :pick-psychic})}
           "Become The Psychic"]]
         [:p (str "Wait while " (team-name team-turn) " chooses their Psychic")])]]
     #_[dump-state]]))


(defn pick-wavelength []
  (let [{:keys [team-turn role wavelengths psychic] :as gs} (:game-state @state)
        [opt1 opt2] wavelengths]
    [:<>
     #_[:h2 "Pick Wavelength"]
     (if (= :psychic role)
       [:<>
        [:div {:style {:display               "grid"
                       :grid-template-columns "repeat(2, 1fr)"
                       :justify-items         "center"}}
         [:p {:style {:grid-column "1 / 3"}}
          "Pick a Wavelength for your team to guess on"]
         [:p (str (first opt1) " <--> " (second opt1))]
         [:button {:style    {:grid-row 3}
                   :on-click #(put! send-chan {:type :pick-card
                                               :pick opt1})}
          "Pick"]
         [:p (str (first opt2) " <--> " (second opt2))]
         [:button {:on-click #(put! send-chan {:type :pick-card
                                               :pick opt2})}
          "Pick"]
         [:button {:style    {:grid-column "1 / 3"}
                   :on-click #(put! send-chan {:type :switch-cards})}
          "Switch Cards"]]
        [team-view gs]]
       [waiting-screen gs
        (let [msg (if (= :guesser role)
                    (str psychic " is choosing a wavelength you to guess")
                    (str psychic " is choosing a wavelength for " (team-name team-turn) " to guess"))]
          [:<>
           [:p msg]])])
     #_[dump-state]]))

(defn pick-clue
  []
  (let [{:keys [target wavelength]} (:game-state @state)
        clue (atom "")]
    (fn []
      [:<>
       #_[:h2 "Pick clue"]
       [thinger wavelength "" target target nil]
       [:p {:style {:text-align "center"}}
        "Enter a clue that represents where on the wavelength the target sits"]
       [:p {:style {:text-align "center"}}
        "Remember you not are not allowed to communicate with your team after this point!"]
       [:div {:style {:display               "grid"
                      :grid-template-columns "1fr"
                      :justify-items "center"}}
        [:form {:on-submit (fn [x]
                             (.preventDefault x)
                             (put! send-chan {:type :pick-clue
                                              :pick @clue}))}
         #_[:label {:for "clue"} "Clue:"]
         [:input#clue {:type    "text"
                       :onInput (fn [x] (->> x .-target .-value (reset! clue)))}]
         [:input {:type  "submit"
                  :value "Submit"}]]]])))

(defn team-guess-guesser
  [guess]
  (let [{:keys [wavelength]} (:game-state @state)
        slider    (r/atom guess)]
    (fn [guess]
      (reset! slider guess)
      [:<>
       [thinger wavelength nil guess nil (fn [x]
                                           (let [x (js/parseInt (.. x -target -value))]
                                             (put! send-chan {:type :move-guess :guess x})))]
       [:div {:style {:display               "grid"
                      :grid-template-columns "repeat(3, 1fr)"
                      :justify-items         "center"}}
        [:p {:style {:grid-row    3
                     :grid-column "1 /4"}}
         "Discuss the clue with your team and as a team decide where on the wavelength the clue sits"]
        [:button {:style    {:grid-row    4
                             :grid-column "1 /4"}
                  :on-click #(put! send-chan {:type :pick-guess, :guess guess})}
         "Submit"]]])))

(defn team-guess-listener
  [msg]
  (let [{:keys [guess wavelength]} (:game-state @state)]
    [:<>
     [thinger wavelength nil guess nil]
     [:p {:style {:text-align "center"}} msg]]))

(defn team-guess
  []
  (let [{:keys [active role team-turn guess clue psychic] :as gs} (:game-state @state)
        safe-role (when active role)]
    [:<>
     #_[:h2 "Team Guess"]
     [psychics-clue psychic clue]
     (case safe-role
       :psychic [team-guess-listener "Your team is guessing your clue"]
       :guesser [team-guess-guesser guess]
       nil [team-guess-listener (str (team-name team-turn)
                                     " is discussing their clue and picking where on the wavelength they think it fits")])
     [team-view gs]
     #_[dump-state]]))

(defn left-right
  []
  (let [{:keys [wavelength role guess psychic clue team-turn]} (:game-state @state)
        explanation (if (= :waiting role)
                      "Decide as a team if the target is Left or Right of the other teams guess to score points"
                      (let [active-name (team-name team-turn)
                            waiting-name (-> team-turn other-team team-name)]
                        (str waiting-name
                             " is deciding whether the target is left or right of " active-name "'s guess")))]
    [:div
     #_[:h2 "Left-Right Phase"]
     [psychics-clue psychic clue]
     [:div {:style {:display               "grid"
                    :grid-template-columns "repeat(3, 1fr)"
                    :justify-items         "center"}}
      [:p {:style {:grid-row 1 :grid-column 1}}
       (first wavelength)]
      [:p {:style {:grid-row 1 :grid-column 3}}
       (second wavelength)]
      [:input {:style {:grid-row 2 :grid-column "1 / 4" :width "100%"}
               :type "range" :value guess :max 110}]
      (when (= :waiting role)
        [:<>
         [:button {:style {:grid-row 3 :grid-column 1}
                   :on-click #(put! send-chan {:type :pick-lr, :guess :left})}
          "Left"]
         [:button {:style {:grid-row 3 :grid-column 3}
                   :on-click #(put! send-chan {:type :pick-lr, :guess :right})}
          "Right"]])
      [:p {:style {:grid-row 4 :grid-column "1 / 4"}}
       explanation]
      #_[dump-state]]]))

(defn app
  []
  ;; FIXME probably want to look into some form of container for this
  ;; as the grid expands across the whole screen which might not be the best
  ;; thing to do
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
(ns wavelength.wavelength
  (:require
   [chord.client :refer [ws-ch]]
   [cljs.core.async :as async :refer [chan <! >! put! close!]]
   [clojure.string :as str]
   [goog.uri.utils :as guri]
   [reagent.core :as r]
   [reagent.dom :as dom])
  (:require-macros [cljs.core.async :refer [alt! go-loop go]]))

(defonce send-chan (chan))

(defonce state (r/atom {}))

(enable-console-print!)
(add-watch state :debug (fn [_key _atom old-st new-st] (println old-st "->"new-st)))

(defn send-msg-loop
  [svr-chan]
  (go-loop []
    ;; Heroku ws connections timeout when no message is sent/received in 55s
    (let [timeout-ch (async/timeout 45000)
          msg        (alt!
                      timeout-ch :keep-alive
                      send-chan ([msg] msg))]
      (when msg
        (println "send-msg-loop sending" msg)
        (when (>! svr-chan msg)
          (recur))))))

(defn receive-msg-loop
  [svr-chan nickname]
  (go-loop []
     (if-let [new-msg (:message (<! svr-chan))]
       (do
         (println nickname " got: " new-msg)
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

(defn connect-to-server [url nickname room]
  (go
    (let [{:keys [ws-channel error]} (<! (ws-ch (append-params url {:nickname nickname :room room})))]
      (if-not error
        (do
          (send-msg-loop ws-channel)
          (receive-msg-loop ws-channel nickname)
          ;; TODO can we remove this now that the server sends the nickname over now
          (swap! state assoc :game-state {:nickname nickname :mode :team-lobby}))
        (swap! state assoc :error error)))))

(defn footer
  []
  [:<>
   [:hr]
   [:div.center-grid.cols-1
    [:p {:style {:color "grey"}}
     "Based on the boardgame " [:a {:href "https://www.wavelength.zone/"} "Wavelength"]
     [:br]
     [:a {:href "https://www.ultraboardgames.com/wavelength/game-rules.php"} "How to Play"]
     [:br]
     "Source code on " [:a {:href " https://github.com/guess-burger/wavelength"} "Github"]]
    ]])

(defn create-room []
  (let [nickname (atom "")
        room     (atom "")
        url      (-> (.. js/document -location -origin)
                     (str/replace #"^http" "ws")
                     (str "/lobby"))]
    (fn []
      [:<>
             [:div.center-grid.cols-1
        [:h1 "Wavelength"]

        [:label {:for "nickname"} "Nickname"]
        [:input#nickname {:style   {:margin-bottom "1em"}
                          :type    "text"
                          :onInput (fn [x] (->> x .-target .-value (reset! nickname)))}]
        [:label {:for "room"} "Room Code"]
        [:input#room {:style   {:margin-bottom "1em"}
                      :type    "text"
                      :onInput (fn [x] (->> x .-target .-value (reset! room)))}]
        [:button {:on-click #(connect-to-server url @nickname @room)}
         "Submit"]]
       [footer]])))

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
     [:ul
      [:li (str "Room: " room-code)]
      [:li (str "nickname: " nickname)]]
     (when msg [:p.fw.txt-c msg])
     [:div.center-grid.cols-3
      [team-div "Left Brain" :left left 1]
      [team-div (str "Spectator" (when-not (= 1 (count spectators)) "s")) :spectators spectators 2]
      [team-div "Right Brain" :right right 3]
      [:div.gr4.gc2
       ;; using a div to stop the button expanding to fill grid
       ;; Probably a better way of doing that
       [:button {:disabled (not ready)
                 :on-click (fn [] (put! send-chan {:type :start}))}
        "Start Game"]]]
     [footer]]))

(def ^:private team-name
  {:left "Left Brain"
   :right "Right Brain"})

(defn team-view
  [{:keys [score left right spectators]}]
  [:<>
   [:hr]
   [:div.center-grid.cols-3 [:p (str "Left Brain: " (:left score) "/10")]
    [:p (str (if (number? spectators) spectators (count spectators)) " Spectators")]
    [:p (str "Right Brain: " (:right score) "/10")]
    [:div
     (for [player left]
       [:p player])]
    [:div.gc3
     (for [player right]
       [:p player])]]])

(defn target-slider
  [wavelength clue guess target on-change]
  (let [range-max  110
        slider-max (dec range-max)
        grid-size  (* 2 slider-max)
        grid-min   1
        grid-max   (inc grid-size)
        centre     (when target (+ 1 (* 2 target)))]
    [:div.center-grid.cols-3
     [:p.gr1.gc1 (first wavelength)]
     [:p.gr1.gc3 (second wavelength)]
     [:div.center-grid.gr2.gc1-4.fw

      [:div.fw.gr1.gc1 {:style {:z-index 5
                                :height  "25px"}}
       [:input.slider {:style     {:width "100%"}
                       :type      "range"
                       :max       slider-max
                       :value     guess
                       :on-change on-change}]]
      [:div.fw.gr1.gc1
       {:style {:padding-left #_"5px"  "6px"
                :padding-right #_"5px" "4px"
                ;; no idea why these are the numbers that look right

                ;; make padding shrink the content rather than expand it
                :box-sizing            "border-box"
                :height                "20px"}}
       [:div.center-grid
        {:style {:height                "100%"
                 :grid-template-columns (str "repeat(" grid-size ", 1fr)")}}
        (when target
          [:<>
           [:div {:style {:background-color "SeaGreen"
                          :grid-row         "1 / 3"
                          :grid-column      (str (max grid-min (- centre 5)) " / " (min grid-max (+ centre 5)))
                          :width            "100%"
                          :z-index          4}}]
           [:div {:style {:background-color "Orange"
                          :grid-row         "1 / 3"
                          :grid-column      (str (max grid-min (- centre 15)) " / " (min grid-max (+ centre 15)))
                          :width            "100%"
                          :z-index          3}}]
           (let [left-side (- centre 25)]
             [:div {:style {:background-color "FireBrick"
                            :grid-row         "1 / 3"
                            :grid-column      (str (max grid-min left-side) " / " centre)
                            :width            "100%"
                            :z-index          2
                            :border-radius    (when (> left-side grid-min) "5px")}}])
           (let [right-side (+ centre 25)]
             [:div {:style {:background-color "FireBrick"
                            :grid-row         "1 / 3"
                            :grid-column      (str centre " / " (min grid-max right-side))
                            :width            "100%"
                            :z-index          2
                            :border-radius    (when (< right-side grid-max) "5px")}}])])]]]]))

(defn psychics-clue
  [name clue]
  [:p.txt-c (str name "'s clue: ") [:strong clue]])

(def other-team
  {:left  :right
   :right :left})

(defn title
  [game-state]
  [:div.center-grid.cols-3.fw
   [:h1.gc2 "Wavelength"]
   (when-let [room-code (:room-code game-state)]
     [:p.gc3
      {:style {:text-align "right"
               :width      "100%"
               :color      "grey"}}
      (str "Room: " room-code)])])

(defn waiting-screen
  [{:keys [team-turn result wavelength] :as game-state} content]
  [:<>
   (when result
     (let [{:keys [guess target active-score waiting-score catch-up? psychic clue sudden-death]} result
           active-name (team-name team-turn)
           waiting-name (-> team-turn other-team team-name) ]
       [:<>
        [psychics-clue psychic clue]
        [target-slider wavelength nil guess target nil]
        #_[:h3 "Result"]
        [:p.txt-c
         (str active-name " scored " active-score " points")]
        (when (= 1 waiting-score)
          [:p.txt-c
           (str waiting-name " scored 1 point for their counter guess")])
        (when catch-up?
          [:p.txt-c
           (str "Catch rule in play, " active-name " goes again")])
        (when sudden-death
          [:p.txt-c "Sudden Death!"])
        [:hr]]))
   [:div.center-grid.cols-1 content]
   [team-view game-state]])

(defn pick-psychic []
  (let [{:keys [team-turn active result wavelength] :as gs} (:game-state @state)]
    [:<>
     [title gs]
     #_[:h2 "Pick Psychic"]
     [waiting-screen
      gs
      [:div.center-grid.cols-1
       (if active
         [:<>
          [:p "Choose a Psychic for the round"]
          [:button {:on-click #(put! send-chan {:type :pick-psychic})}
           "Become The Psychic"]]
         [:p (str "Wait while " (team-name team-turn) " chooses their Psychic")])]]
     [footer]
     #_[dump-state]]))


(defn pick-wavelength []
  (let [{:keys [team-turn role wavelengths psychic] :as gs} (:game-state @state)
        [opt1 opt2] wavelengths]
    [:<>
     [title gs]
     #_[:h2 "Pick Wavelength"]
     (if (= :psychic role)
       [:<>
        [:div.center-grid.cols-2
         [:p.gc1-3 "Pick a Wavelength for your team to guess on"]
         [:p (str (first opt1) " ⟷ " (second opt1))]
         [:button.gr3 {:on-click #(put! send-chan {:type :pick-card
                                                   :pick opt1})}
          "Pick"]
         [:p (str (first opt2) " ⟷ " (second opt2))]
         [:button {:on-click #(put! send-chan {:type :pick-card
                                               :pick opt2})}
          "Pick"]
         [:button.gc1-3 {:on-click #(put! send-chan {:type :switch-cards})}
          "Switch Cards"]]
        [team-view gs]]
       [waiting-screen gs
        (let [msg (if (= :guesser role)
                    (str psychic " is choosing a wavelength you to guess")
                    (str psychic " is choosing a wavelength for " (team-name team-turn) " to guess"))]
          [:<>
           [:p msg]])])
     [footer]
     #_[dump-state]]))

(defn pick-clue
  []
  (let [{:keys [target wavelength] :as gs} (:game-state @state)
        clue (atom "")]
    (fn []
      [:<>
       [title gs]
       #_[:h2 "Pick clue"]
       [target-slider wavelength "" target target nil]
       [:p.txt-c "Enter a clue that represents where on the wavelength the target sits"]
       [:p.txt-c "Remember you not are not allowed to communicate with your team after this point!"]
       [:div.center-grid.cols-1
        [:form {:on-submit (fn [x]
                             (.preventDefault x)
                             (put! send-chan {:type :pick-clue
                                              :pick @clue}))}
         #_[:label {:for "clue"} "Clue:"]
         [:input#clue {:type    "text"
                       :onInput (fn [x] (->> x .-target .-value (reset! clue)))}]
         [:input {:type  "submit"
                  :value "Submit"}]]]
       [team-view gs]
       [footer]])))

(defn team-guess-guesser
  [guess]
  (let [{:keys [wavelength]} (:game-state @state)
        slider    (r/atom guess)]
    (fn [guess]
      (reset! slider guess)
      [:<>
       [target-slider wavelength nil guess nil (fn [x]
                                           (let [x (js/parseInt (.. x -target -value))]
                                             (put! send-chan {:type :move-guess :guess x})))]
       [:div.center-grid.cols-3
        [:p.gr-3.gc1-4
         "Discuss the clue with your team and as a team decide where on the wavelength the clue sits"]
        [:button.gr-4.gc1-4 {:on-click #(put! send-chan {:type :pick-guess, :guess guess})}
         "Submit"]]])))

(defn team-guess-listener
  [msg]
  (let [{:keys [guess wavelength]} (:game-state @state)]
    [:<>
     [target-slider wavelength nil guess nil]
     [:p.txt-c msg]]))

(defn team-guess
  []
  (let [{:keys [active role team-turn guess clue psychic] :as gs} (:game-state @state)
        safe-role (when active role)]
    [:<>
     [title gs]
     #_[:h2 "Team Guess"]
     [psychics-clue psychic clue]
     (case safe-role
       :psychic [team-guess-listener "Your team is guessing your clue"]
       :guesser [team-guess-guesser guess]
       nil [team-guess-listener (str (team-name team-turn)
                                     " is discussing their clue and picking where on the wavelength they think it fits")])
     [team-view gs]
     [footer]
     #_[dump-state]]))

(defn left-right
  []
  (let [{:keys [wavelength role guess psychic clue team-turn] :as gs} (:game-state @state)
        explanation (if (= :waiting role)
                      "Decide as a team if the target is Left or Right of the other teams guess to score points"
                      (let [active-name (team-name team-turn)
                            waiting-name (-> team-turn other-team team-name)]
                        (str waiting-name
                             " is deciding whether the target is left or right of " active-name "'s guess")))]
    [:div
     [title gs]
     #_[:h2 "Left-Right Phase"]
     [psychics-clue psychic clue]
     [target-slider wavelength "" guess]
     [:div.center-grid.cols-3
      (when (= :waiting role)
        [:<>
         [:button.gr3.gc1
          {:on-click #(put! send-chan {:type :pick-lr, :guess :left})}
          "Left"]
         [:button.gr3.gc3
          {:on-click #(put! send-chan {:type :pick-lr, :guess :right})}
          "Right"]])
      [:p.gr4.gc1-4 explanation]]
     [team-view gs]
     [footer]
     #_[dump-state]]))

(defn reveal
  []
  (let [{:keys [winner role] :as gs} (:game-state @state)]
    [:<>
     [waiting-screen gs
      [:div.center-grid.cols-2.fw
       [:p.gc1-3 (str (team-name winner) " wins!")]
       [:button {:disabled (= :spectator role)
                 :on-click #(put! send-chan {:type :play-again})}
        "Play Again"]
       [:button {:disabled (= :spectator role)
                 :on-click #(put! send-chan {:type :change-teams})}
        "Change Teams"]]]
     [footer]
     #_[dump-state]]))

(defn app
  []
   (let [s (:game-state @state)]
     (case (:mode s)
       nil [create-room]
       :team-lobby [team-lobby]
       :pick-psychic [pick-psychic]
       :pick-wavelength [pick-wavelength]
       :pick-clue [pick-clue]
       :team-guess [team-guess]
       :left-right [left-right]
       :reveal [reveal]
       [:div
        [:h2 "Eh?"]
        [dump-state]])))

(dom/render [app]
          (. js/document (getElementById "container")))
(ns wavelength.team-lobby
  (:require
   [chord.http-kit :as chord]
   [clojure.core.async :as as]
   [lib.stately.core :as st]))


;; can this be a more generic lobby?
;; but focused on for "teams"


;; what does that mean?
;; - let people pick their team
;; - let people pick a random team
;; - let people randomise the teams
;; - have conditions for the teams?
;;   - what about asymmetric games?
;;   - how do you handle the different rules?
;;   - do you need to explain it? or just enable the start button?
;;   - how do we control the lobby view then?

;; Am i making this too complicated, too soon?
;; maybe i just want conditions, and the picker options


;; Guess I need 3 lists
;; left team, right team, unpicked (spectating? that might work quite well!)
;;
;; Then i need a predicate for enabling the start button
;;  - 2 players in all left and right team!

(defonce lobbies (atom {}))

(defn- all-players
  [{:keys [left spectators right] :as _context}]
  (concat (keys left) (keys spectators) (keys right)))

(defn- leave-lobby
  [who {:keys [left spectators right] :as context}]
  (let [left (dissoc left who)
        spectators (dissoc spectators who)
        right (dissoc right who)
        ;; FIXME this could use the remove thing from joining a team
        out-msg {:type       :merge
                 :left       (vals left)
                 :spectators (vals spectators)
                 :right      (vals right)}
        members (concat (keys left) (keys spectators) (keys right))]
    (if (empty? members)
      {::st/state ::st/end}
      {::st/state   ::st/recur
       ::st/context (assoc context
                      :left left
                      :spectators spectators
                      :right right)
       ::st/fx      {::st/send [{::st/msg out-msg
                                 ::st/to  members}]}})))


(defn- join
  [msg {:keys [left spectators right code] :as context}]
  (let [joiner       (:player msg)
        spectators'  (assoc spectators joiner (:nickname msg))
        joiner-msg {:type       :merge
                    :mode       :team-lobby
                    :room-code  code
                    :left       (sequence (vals left))
                    :spectators (vals spectators')
                    :right      (sequence (vals right))}
        existing     (concat (keys left) (keys spectators) (keys right))
        existing-msg {:type       :merge
                      :spectators (vals spectators')}]
    {::st/state   ::st/recur
     ::st/context (assoc context
                    :spectators spectators')
     ::st/fx      {::st/send [{::st/to  existing
                               ::st/msg existing-msg}
                              {::st/to  [joiner]
                               ::st/msg joiner-msg}]}}))

(defn ignore+recur
  [context]
  #_(println "ignoring")
  {::st/state   ::st/recur
   ::st/context context})

(defn- find+remove-player
  [player context team-k]
  (let [team (get context team-k)]
    (println {:k team-k :p player :t team})
    (if (contains? team player)
      (reduced [(get team player) team-k (assoc context team-k (dissoc team player))])
      context)))

(defn- pick-team
  [context {:keys [team] :as _msg} player]
  #_(println "pick-team" team)
  (if (#{:left :right :spectators} team)
    (let [[nickname old-team context'] (reduce (partial find+remove-player player) context [:left :right :spectators])]
      (if-not (= team old-team)
        (let [context' (update context' team assoc player nickname)
              out-msg (assoc {:type :merge
                              team  (map second (get context' team))}
                        old-team (map second (get context' old-team)))]
          {::st/state   ::st/recur
           ::st/context context'
           ::st/fx      {::st/send [{::st/to  (all-players context')
                                     ::st/msg out-msg}]}})
        (ignore+recur context)))
    (ignore+recur context)))

(defn wait-in-lobby-inputs
  [{:keys [lobby left spectators right]}]
  (concat (keys left) (keys spectators) (keys right) [lobby]))

(defn wait-in-lobby-entry-fx
  [{:keys [spectators code] :as _context}]
  (let [[who nickname] (first spectators)]
    {::st/send [{::st/to  [who]
                 ::st/msg {:type       :merge
                           :mode       :team-lobby
                           :room-code  code
                           :spectators [nickname]}}]}))

(defn wait-in-lobby-transitions
  [[msg from] {:keys [lobby] :as context}]

  #_(println "Got Msg" {:msg msg :from from})
  (cond

    ;; FIXME another leak about core async?
    (nil? msg) ;; disconnect
    (leave-lobby from context)

    (and (= from lobby) (= :join (:type msg)))
    (join msg context)

    (= :pick-team (:type msg))
    (pick-team context msg from)

    (= :leave (:type msg))
    (leave-lobby from context)

    ;;  - start game
    ;;    - ->


    :default
    (ignore+recur context)))


(def state-machine
  {::wait-in-lobby {::st/inputs         #'wait-in-lobby-inputs
                    ;; FIXME "first" entry might suggest the very first time you enter a state
                    ;; I kind of mean when you enter it from another state rather than
                    ;; recurring into it... maybe there is a better name for that
                    ::st/first-entry-fx #'wait-in-lobby-entry-fx
                    ::st/transition-fn  #'wait-in-lobby-transitions}})

;; --- --- ---

(defn ^:private rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(defn ^:private create-lobby
  "Registers a lobby room, then returns the initial lobby state and context"
  [ws-ch nickname full-state-machine]
  (let [lobby-code (rand-str 10)
        ;; FIXME more proof that this "abstraction" is a little rough!
        lobby-ch   (as/chan)]
    (swap! lobbies assoc lobby-code lobby-ch)
    (st/run (merge full-state-machine state-machine)
            ::wait-in-lobby
            {:left       {}
             :spectators {ws-ch nickname}
             :right      {}
             :code       lobby-code
             :lobby      lobby-ch})))

(defn ^:private join-lobby
  [ws-ch nickname lobby-ch]
  (st/send lobby-ch {:type     :join
                     :player   ws-ch
                     :nickname nickname}))

(defn create-or-join-lobby
  "If a lobby for room code exists join it. Otherwise, create a new
   lobby for a new random room-code that would being the given state machine"
  [ch nickname room-code game-state-machine]
  (if-let [lobby-ch (and room-code (get @lobbies room-code))]
    (join-lobby ch nickname lobby-ch)
    (create-lobby ch nickname game-state-machine)))
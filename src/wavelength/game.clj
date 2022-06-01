(ns wavelength.game
  (:require
   ;; TODO see if we can remove core.asycn from here
   [clojure.core.async :as as]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [lib.stately.core :as st]))

(def score-block-size 5)
(def board-range (* 22 score-block-size))

(def ^:private other-team
  {:left  :right
   :right :left})

(defn- all-players
  [{:keys [left spectators right] :as _context}]
  (concat (keys left) (keys spectators) (keys right)))

(defn active-waiting-chs
  [{:keys [active-team waiting-team spectators] :as context}]
  (let [active (keys (get context active-team))
        waiting (concat (keys (get context waiting-team)) (keys spectators))]
    [active waiting]))

(defn ignore+recur
  [context]
  #_(println "ignoring")
  {::st/state   ::st/recur
   ::st/context context})

(defn ^:private msg-to-everyone
  [{:keys [left right spectators]} msg]
  {::st/to  (mapcat keys [left right spectators])
   ::st/msg msg})

(defn everyone-inputs
  [{:keys [left right spectators] :as _context}]
  (mapcat keys [left right spectators]))

;; -- Lobby
;; A holding areas where players join as spectators before picking the team they wish to play in.
;; the match can only begin when there are at least 2 players in each team

(defn ^:private ready?
  [{:keys [left right] :as _context}]
  (and (<= 2 (count left))
       (<= 2 (count right))))

(defn- leave-lobby
  [who {:keys [left spectators right] :as context}]
  (let [left (dissoc left who)
        spectators (dissoc spectators who)
        right (dissoc right who)
        context (assoc context
                       :left left
                       :spectators spectators
                       :right right)
        ready (ready? context)
        ;; FIXME this could use the remove thing from joining a team
        out-msg {:type       :merge
                 :left       (vals left)
                 :spectators (vals spectators)
                 :right      (vals right)
                 :ready      ready}
        members (concat (keys left) (keys spectators) (keys right))]
    (if (empty? members)
      {::st/state ::st/end}
      {::st/state   ::st/recur
       ::st/context context
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

(defn- find+remove-player
  [player context team-k]
  (let [team (get context team-k)]
    (println {:k team-k :p player :t team})
    (if (contains? team player)
      (reduced [(get team player) team-k (assoc context team-k (dissoc team player))])
      context)))

;; TODO should put some tests on this of it's being reused now
(defn remove-player-from
  "Remove a player if present in the given teams, returning vec of
   player name, team-k and the new context if found otherwise just context"
  ([context player]
   (remove-player-from context player [:left :right :spectators]))
  ([context player team-ks]
   (reduce (partial find+remove-player player) context team-ks)))

(defn- pick-team
  [context {:keys [team] :as _msg} player]
  #_(println "pick-team" team)
  (if (#{:left :right :spectators} team)
    (let [[nickname old-team context'] (remove-player-from context player)]
      (if-not (= team old-team)
        (let [context' (update context' team assoc player nickname)
              out-msg (assoc {:type :merge
                              :ready (ready? context')
                              team   (map second (get context' team))}
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
  [{:keys [left right spectators code lobby-msg] :as context}]
  {::st/fx {::st/send [{::st/to  (mapcat keys [left right spectators])
                        ::st/msg {:type       :merge
                                  :mode       :team-lobby
                                  :room-code  code
                                  :msg        lobby-msg
                                  :ready      (ready? context)
                                  :left       (vals left)
                                  :spectators (vals spectators)
                                  :right      (vals right)}}]}})

(defn wait-in-lobby-transitions
  [[msg from] {:keys [lobby] :as context}]

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

    (and (= :start (:type msg)) (ready? context))
    {::st/state   ::pick-psychic
     ::st/context context}

    :default
    (ignore+recur context)))

;; --- Pick Psychic - Psychic Phase
;; During this phase any member of the active team can declare they will be the psychic this round.
;; If there isn't an active team yet then on is randomly selected.

(def ^:private wavelengths-cards (edn/read-string (slurp (io/resource "prompts.edn"))))

(defn ^:private wavelength-deck
  ([] (wavelength-deck wavelengths-cards))
  ([cards] (lazy-seq (concat (shuffle cards) (wavelength-deck cards)))))

(defn on-entry-pick-psychic
  [context]

  ;; Probably need to pick a team to go if there isn't one already
  ;; Do we need first entry then? Since we will want to "reenter" this state in another round
  ;; but we won't need to reset all the state...
  ;; but then what if we play another game? Even if we went "through" a lobby
  ;; we'd probably still want to be in the same "room"

  ;; lobby would have
  {:left       {}
   :right      {}
   :spectators {}
   ;; fixme maybe make lobby like this? maybe
   :lobby-ch {}
   :lobby-code {}
   #_:lobby #_{:code ""
               :join-ch ""}}

  ;; and make lobby look like this
  {:score     {:left 1
               :right 0}
   :active-team :right
   :waiting-team :left}

  ;; later we'll want something like
  {:score {:left 1
           :right 0}
   :active-team :right
   :waiting-team :left
   :psychic :foo-ch
   :rest-team [:bar-ch :foo-ch]}

  (let [round-context (if (nil? (:score context))
                        ;; starting a game from a lobby
                        (let [team (rand-nth [:left :right])
                              other (other-team team)
                              score (update {:left 0 :right 0} other inc)]
                          (assoc context :score score :active-team team :waiting-team other
                                         :deck (wavelength-deck)))
                        ;;starting a new round
                        (select-keys context [:score :deck
                                              :active-team :waiting-team
                                              :left :right :spectators
                                              :lobby :code]))
        active-msg    {:type :merge
                       :mode :pick-psychic
                       :active true
                       :team-turn (:active-team round-context)
                       :score (:score round-context)
                       ;;
                       }
        waiting-msg (assoc active-msg :active false)
        [to-active to-waiting] (active-waiting-chs round-context)]

    {::st/context round-context
     ::st/fx      {::st/send [{::st/to  to-active
                               ::st/msg active-msg}
                              {::st/to to-waiting
                               ::st/msg waiting-msg}]}}))

(defn ^:private remove-player
  [context player]
  ;; FIXME does this function really work for every phase?
  ;; some of the phases are using :rest-of-team which isn't being updated
  (let [[name team-k next-context] (remove-player-from context player)]
    (cond
      ;; too few players: go back to lobby
      (or (> 2 (-> next-context :left count))
          (> 2 (-> next-context :right count)))
      {::st/context (-> next-context
                        (assoc :lobby-msg (str "Too few players to continue after \"" name "\" left"))
                        (dissoc :score :team-turn))
       ::st/state   ::wait-in-lobby}

      (= player (:psychic next-context))
      {::st/context (dissoc next-context :psychic)
       ::st/state   ::pick-psychic}

      :else
      {::st/context next-context
       ::st/fx      {::st/send [(msg-to-everyone context {:type  :merge
                                                          team-k (map second (get next-context team-k))})]}
       ::st/state   ::st/recur})))

(defn psychic-active-waiting-spectators
  [{:keys [psychic spectators] :as context}]
  (let [[active waiting] (active-waiting-chs context)
        active (remove #{psychic} active)]
    [psychic active waiting spectators]))

(defn pick-psychic
  [from {:keys [active-team waiting-team deck] :as context}]
  (println (assoc context :deck "<infinite!>"))
  ;; FIXME might need some print-fn for the context to prevent stately printing infinite seqs

  ;; Something like this...
  #_{:left         {:foo-ch "foo"}
     :right        {:bar-ch "bar"}

     :score        {:left  1
                    :right 0}

     :active-team  :right
     :waiting-team :left

     :psychic      :foo-ch
     ;; having this here means we can use the team just for nicknames and stuff
     ;; but use the rest-team and psychic for checking listening
     :rest-team    #{:bar-ch :foo-ch}
     }

  (let [team (active-team context)]
    (if (contains? (active-team context) from)
      (let [rest-team (-> team (dissoc from) keys set)
            base-msg  {:type    :merge
                       :mode    :pick-wavelength
                       :psychic (get-in context [active-team from])}
            context (assoc context
                           :psychic from
                           :rest-team rest-team)
            [psychic active waiting spectators] (psychic-active-waiting-spectators context)]
        {::st/context context
         ;; TODO need to send msg? Or make the next state do that?
         ::st/state   ::pick-wavelength
         ::st/fx      {::st/send [#_{::st/to  [from]
                                   ::st/msg (assoc base-msg
                                              :wavelengths (take 2 deck))}
                                  #_{::st/to  (concat rest-team spectators (-> context waiting-team keys))
                                   ::st/msg (assoc base-msg
                                              :psychic (get-in context [active-team from]))}
                                  {::st/to  [psychic]
                                   ::st/msg (assoc base-msg
                                                   :wavelengths (take 2 deck)
                                                   :role :psychic
                                                   :result nil)}
                                  {::st/to  active
                                   ::st/msg (assoc base-msg :role :guesser)}
                                  {::st/to  waiting
                                   ::st/msg (assoc base-msg :role :waiting)}
                                  {::st/to  spectators
                                   ::st/msg (assoc base-msg :role :spectator)}]}})
      {::st/context context
       ::st/state   ::st/recur})))


(defn pick-psychic-transitions
  [[msg from] {:keys [] :as context}]

  (cond

    (nil? msg)
    (remove-player context from)

    ;; TODO: should pick just be in place here since this is only the pick-psychic transitions?
    (= :pick-psychic (:type msg))
    (pick-psychic from context)

    :default
    {::st/context context
     ::st/state   ::st/recur}))

;; -- Pick Card - Psychic Phase
;; Here the Psychic is presented with two cards they can choose between. To make things a little easier
;; we also allow them to swap their cards for another 2 from the deck

(defn ^:private switch-cards
  [{:keys [psychic deck] :as context}]
  (let [deck (drop 2 deck)]
    {::st/context (assoc context
                         :deck deck)
     ::st/state   ::st/recur
     ::st/fx      {::st/send [{::st/to  [psychic]
                               ::st/msg {:type :merge
                                         :wavelengths (take 2 deck)}}]}}))

(defn ^:private pick-card
  [context msg]
  (let [deck    (:deck context)
        options (take 2 deck)
        pick    (:pick msg)]
    (if (or (= pick (first options))
            (= pick (second options)))
      ;; valid option chosen
      {::st/context (assoc context
                      :deck (drop 2 deck)
                      :wavelength pick)
       ::st/state   ::pick-clue}
      ;; wasn't an option we know
      {::st/context context
       ::st/state   ::st/recur
       ::st/fx      {::st/send [{::st/to  [(:psychic context)]
                                 ::st/msg {:type        :merge
                                           :wavelengths options}}]}})))

(defn pick-wavelength-transitions
  [[msg from] {:keys [psychic] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= :switch-cards (:type msg))
         (= psychic from))
    (switch-cards context)

    (and (= :pick-card (:type msg))
         (= psychic from))
    (pick-card context msg)

    :default
    {::st/context context
     ::st/state   ::st/recur}))

;; -- Pick Clue - Psychic Phase
;; During this part of the psychic phase, the psychic needs to enter a clue for their team to use
;; to guess the target

(defn pick-clue-on-entry
  [{:keys [psychic wavelength] :as context}]
  (let [target (rand-int board-range)]
    {::st/context (assoc context
                    :target target)
     ::st/fx      {::st/send [{::st/to  [psychic]
                               ::st/msg {:type       :merge
                                         :mode       :pick-clue
                                         :target     target
                                         :wavelength wavelength}}
                              ;; TODO could send a message to others to update them on
                              ;; picking a clue
                              ]}}))

(defn pick-clue-transitions
  [[msg from] {:keys [psychic] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= :pick-clue (:type msg))
         (= psychic from))
    {::st/context (assoc context :clue (:pick msg))
     ::st/state   ::team-guess}

    :default
    {::st/context context
     ::st/state   ::st/recur}))

;; -- Team Guess Phase
;; During this phase the active team discuss the clue and move the guess marker to where
;; they believe the target to be. Anyone on that team (except the psychic) can submit their
;; guess but we check their submission matches the "guess" being discussed

(defn team-guess-on-entry
  [context]
  (let [base-msg {:type       :merge
                  :mode       :team-guess
                  :clue       (:clue context)
                  :wavelength (:wavelength context)
                  :guess      (/ board-range 2)
                  :result     nil}]
    {::st/context (assoc context :guess (/ board-range 2))
     ::st/fx      {::st/send [(msg-to-everyone context base-msg)]}}))

(defn team-guess-transitions
  [[msg from] context]
  (cond

    (nil? msg)
    (remove-player context from)

    (= :move-guess (:type msg))
    {::st/context (assoc context :guess (:guess msg))
     ::st/state ::st/recur
     ::st/fx    {::st/send [(msg-to-everyone context {:type :merge, :guess (:guess msg)})]}}

    (and (= :pick-guess (:type msg))
         (= (:guess context) (:guess msg)))
    {::st/context context
     ::st/state   ::left-right}

    :default
    {::st/context context
     ::st/state   ::st/recur}))

;; -- Left/Right Phase
;; In this phase the Waiting team can guess whether they believe the target is
;; left or right of the active teams guess

(defn left-right-on-entry
  [context]
  {::st/context context
   ::st/fx      {::st/send [(msg-to-everyone context {:type :merge, :mode :left-right})]}})

(defn left-right-transitions
  [[msg from] context]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= :pick-lr (:type msg))
         (#{:left :right} (:guess msg))
         (contains? (get context (:waiting-team context)) from))
    (let [;context (assoc context :lr-guess (:guess msg))
          {:keys [target guess active-team waiting-team score psychic]} context

          lr-guess    (:guess msg)
          guess-score (condp >= (abs (- target guess))
                        ;; these a fixed for 5 size sections right now
                        2  4
                        7  3
                        12 2
                        0)
          score       (update score active-team + guess-score)
          lr-score    (if (and (not= 4 guess-score)
                               (or (and (= lr-guess :left)
                                        (< target guess))
                                   (and (= lr-guess :right)
                                        (> target guess))))
                        1 0)
          score       (update score waiting-team + lr-score)
          catch-up?   (and (= 4 guess-score)
                           (< (get score active-team) (get score waiting-team)))
          ;; TODO probably need a tie breaker flag or something

          context     (cond-> (assoc context
                                     :score score)
                        (not catch-up?)
                        (assoc :active-team  waiting-team
                               :waiting-team active-team))
          ;; TODO decide if there is a winner
          state       ::pick-psychic]
      {::st/context context
       ::st/state   state
       ::st/fx      {::st/send [(msg-to-everyone context
                                                 {:type   :merge
                                                  :result {:active        active-team
                                                           :active-score  guess-score
                                                           :waiting-score lr-score
                                                           :catch-up?     catch-up?
                                                           :target        target
                                                           :guess         guess
                                                           :psychic       (get-in context [active-team psychic])
                                                           :clue          (:clue context)}})]}})

    :default
    {::st/context context
     ::st/state   ::st/recur}))

(def state-machine
  {::wait-in-lobby   {::st/inputs         #'wait-in-lobby-inputs
                      ::st/on-entry       #'wait-in-lobby-entry-fx
                      ::st/transition-fn  #'wait-in-lobby-transitions}
   ::pick-psychic    {::st/on-entry       #'on-entry-pick-psychic
                      ::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'pick-psychic-transitions}
   ::pick-wavelength {::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'pick-wavelength-transitions}
   ::pick-clue       {::st/on-entry       #'pick-clue-on-entry
                      ::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'pick-clue-transitions}
   ::team-guess      {::st/on-entry       #'team-guess-on-entry
                      ::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'team-guess-transitions}
   ::left-right      {::st/on-entry       #'left-right-on-entry
                      ::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'left-right-transitions}})

;; ---

(defonce lobbies (atom {}))

(defn ^:private rand-str [len]
  (apply str (take len (repeatedly #(char (+ (rand 26) 65))))))

(defn ^:private create-lobby
  "Registers a lobby room, then returns the initial lobby state and context"
  [ws-ch nickname]
  (let [lobby-code (rand-str 10)
        ;; FIXME more proof that this "abstraction" is a little rough!
        lobby-ch   (as/chan)]
    (swap! lobbies assoc lobby-code lobby-ch)
    (st/run state-machine
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
  [ch nickname room-code]
  (if-let [lobby-ch (and room-code (get @lobbies room-code))]
    (join-lobby ch nickname lobby-ch)
    (create-lobby ch nickname)))
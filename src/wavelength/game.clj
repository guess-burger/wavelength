(ns wavelength.game
  (:require
   ;; TODO see if we can remove core.asycn from here
   [clojure.core.async :as as]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.logging :as log]
   [lib.stately.core :as st]))

(defonce lobbies (atom {}))

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
  (log/info "ignoring")
  {::st/state   ::st/recur
   ::st/context context})

(defn ^:private msg-to-everyone
  [{:keys [left right spectators]} msg]
  {::st/to  (mapcat keys [left right spectators])
   ::st/msg msg})

(defn all-inputs
  [{:keys [lobby left spectators right]}]
  (concat (keys left) (keys spectators) (keys right) [lobby]))

(defn ^:private find+remove-player
  [player context team-k]
  (let [team (get context team-k)]
    (if (contains? team player)
      (reduced [(assoc context team-k (dissoc team player))
                team-k
                (get team player)])
      context)))

(defn remove-player-from
  "Remove a player if present in the given teams, returning vec of player name,
   team-k and the new context if found otherwise just vec containing context"
  ([context player]
   (remove-player-from context player [:left :right :spectators]))
  ([context player team-ks]
   (let [result (reduce (partial find+remove-player player) context team-ks)]
     ;; if not a vector then we failed to find the player so return context/vector
     ;; in a vector
     (if (vector? result)
       result
       [result]))))

;; -- Lobby
;; A holding area where players join as spectators before picking the team they wish to play in.
;; the match can only begin when there are at least 2 players in each team

(defn ^:private ready?
  [{:keys [left right] :as _context}]
  (and (<= 2 (count left))
       (<= 2 (count right))))

(defmethod st/apply-effect ::close-lobby [[_k code]]
  (swap! lobbies dissoc code))

(defn- leave-lobby
  [who {:keys [code] :as context}]
  (let [[context team-k _] (remove-player-from context who)
        out-msg {:type  :merge
                 team-k (vals (get context team-k))
                 :ready (ready? context)}
        members (all-players context)]
    (if (empty? members)
      (do
        {::st/state ::st/end
         ::st/fx    {::close-lobby code}})
      {::st/state   ::st/recur
       ::st/context context
       ::st/fx      {::st/send [{::st/msg out-msg
                                 ::st/to  members}]}})))

(defn nickname
  [wanted-nickname taken-nicknames]
  (if (contains? taken-nicknames wanted-nickname)
    (if-let [[_ base digit] (re-matches #"(.*) \((\d+)\)$" wanted-nickname)]
      (recur (str base " (" (-> digit Integer/parseInt inc) ")") taken-nicknames)
      (recur (str wanted-nickname " (2)") taken-nicknames))
    wanted-nickname))

(defn- join-from-lobby
  [out-msg-fn msg {:keys [left spectators right code] :as context}]
  (let [existing     (merge left spectators right)
        nickname     (nickname (:nickname msg) (set (vals existing)))
        joiner       (:player msg)
        spectators'  (assoc spectators joiner nickname)
        existing-msg {:type       :merge
                      :spectators (vals spectators')}
        context'     (assoc context :spectators spectators')
        joiner-msg   (merge {;; These seem like standard things
                             :type       :reset
                             :nickname   nickname
                             :room-code  code
                             :left       (sequence (vals left))
                             :spectators (vals spectators')
                             :right      (sequence (vals right))}
                            (out-msg-fn context'))]
    {::st/state   ::st/recur
     ::st/context context'
     ::st/fx      {::st/send [{::st/to  (keys existing)
                               ::st/msg existing-msg}
                              {::st/to  [joiner]
                               ::st/msg joiner-msg}]}}))

(defn- pick-team
  [context {:keys [team] :as _msg} player]
  (if (#{:left :right :spectators} team)
    (let [[context' old-team nickname] (remove-player-from context player)]
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

(defn- join-lobby-msg
  [_context]
  {:mode :team-lobby})

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
    (join-from-lobby join-lobby-msg msg context)

    (= :pick-team (:type msg))
    (pick-team context msg from)

    (= :leave (:type msg))
    (leave-lobby from context)

    (and (= :start (:type msg)) (ready? context))
    {::st/state   ::pick-psychic
     ::st/context (select-keys context [:left :right :spectators :lobby :code])}

    :default
    (ignore+recur context)))

;; --- Pick Psychic - Psychic Phase
;; During this phase any member of the active team can declare they will be the psychic this round.
;; If there isn't an active team yet then one is randomly selected.

(def ^:private wavelengths-cards (edn/read-string (slurp (io/resource "prompts.edn"))))

(defn ^:private wavelength-deck
  ([] (wavelength-deck wavelengths-cards))
  ([cards] (lazy-seq (concat (shuffle cards) (wavelength-deck cards)))))

(defn on-entry-pick-psychic
  [context]
  (let [coming-from-lobby? (nil? (:score context))
        round-context      (if coming-from-lobby?
                             (let [team  (rand-nth [:left :right])
                                   other (other-team team)
                                   score (update {:left 0 :right 0} other inc)]
                               (assoc context :score score :active-team team
                                      :waiting-team other :deck (wavelength-deck)))
                             ;;otherwise we're starting a new round
                             (select-keys context [:score :sudden-death-rounds :deck
                                                   :active-team :waiting-team
                                                   :left :right :spectators
                                                   :lobby :code]))
        active-msg         (cond-> {:type      :merge
                                    :mode      :pick-psychic
                                    :active    true
                                    :team-turn (:active-team round-context)
                                    :score     (:score round-context)}
                             ;; remove any previous round results if the players just switched teams in the lobby
                             coming-from-lobby? (assoc :result nil))
        waiting-msg        (assoc active-msg :active false)
        [to-active to-waiting] (active-waiting-chs round-context)]

    {::st/context round-context
     ::st/fx      {::st/send [{::st/to  to-active
                               ::st/msg active-msg}
                              {::st/to to-waiting
                               ::st/msg waiting-msg}]}}))

(defn ^:private remove-player
  [context player]
  (let [[next-context team-k name] (remove-player-from context player)]
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
    [psychic active waiting (keys spectators)]))

(defn pick-psychic
  [from {:keys [active-team deck] :as context}]
  (println (assoc context :deck "<infinite!>"))
  ;; FIXME might need some print-fn for the context to prevent stately printing infinite seqs

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
         ::st/fx      {::st/send [{::st/to  [psychic]
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


(defn pick-psychic-spectator-join
  [{:keys [active-team score] :as _context}]
  {:mode      :pick-psychic
   :active    false
   :team-turn active-team
   :score     score})

(defn pick-psychic-transitions
  [[msg from] {:keys [lobby] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= from lobby) (= :join (:type msg)))
    (join-from-lobby pick-psychic-spectator-join msg context)

    (= :pick-psychic (:type msg))
    (pick-psychic from context)

    :default
    {::st/context context
     ::st/state   ::st/recur}))

;; -- Pick Card - Psychic Phase
;; Here the Psychic is presented with two cards they can choose between. To make things a little easier
;; we also allow them to swap their cards for another 2 from the deck

(defn pick-wavelength-spectator-join
  [{:keys [active-team score psychic] :as context}]
  {:mode      :pick-wavelength
   :active    false
   :team-turn active-team
   :score     score
   :psychic   (get-in context [active-team psychic])
   :role      :spectator})

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
  [[msg from] {:keys [psychic lobby] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= from lobby) (= :join (:type msg)))
    (join-from-lobby pick-wavelength-spectator-join msg context)

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
                                         :wavelength wavelength}}]}}))

(defn pick-clue-transitions
  [[msg from] {:keys [psychic lobby] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= from lobby) (= :join (:type msg)))
    (join-from-lobby pick-wavelength-spectator-join msg context)

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

(defn team-guess-spectator-join
  [{:keys [active-team score psychic] :as context}]
  {:mode      :team-guess
   :clue       (:clue context)
   :wavelength (:wavelength context)
   :guess      (:guess context)
   :psychic   (get-in context [active-team psychic])
   :active    false
   :team-turn active-team
   :score     score})

(defn team-guess-transitions
  [[msg from] {:keys [lobby] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= from lobby) (= :join (:type msg)))
    (join-from-lobby team-guess-spectator-join msg context)

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

(defn left-right-spectator-join
  [context]
  (-> (team-guess-spectator-join context)
      (assoc :mode :left-right)))

(defn left-right-transitions
  [[msg from] {:keys [lobby] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= from lobby) (= :join (:type msg)))
    (join-from-lobby left-right-spectator-join msg context)

    (and (= :pick-lr (:type msg))
         (#{:left :right} (:guess msg))
         (contains? (get context (:waiting-team context)) from))
    (let [{:keys [target guess active-team waiting-team score psychic sudden-death-rounds]} context

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

          winning-zone (some #(<= 10 %) (vals score))
          [state sudden-death-rounds] (cond
                                        (and sudden-death-rounds (< 0 sudden-death-rounds))
                                        [::pick-psychic (dec sudden-death-rounds)]

                                        (and winning-zone (apply = (vals score)))
                                        ;; need to enter sudden death
                                        [::pick-psychic 1]

                                        winning-zone
                                        ;; if we're in the "winning zone" but score aren't tied then someone has won
                                        [::reveal sudden-death-rounds]

                                        :default
                                        [::pick-psychic nil])

          context     (cond-> (assoc context
                                     :score score)

                        (not catch-up?)
                        (assoc :active-team  waiting-team
                               :waiting-team active-team)

                        sudden-death-rounds
                        (assoc :sudden-death-rounds sudden-death-rounds))

          result (cond-> {:active        active-team
                          :active-score  guess-score
                          :waiting-score lr-score
                          :catch-up?     catch-up?
                          :target        target
                          :guess         guess
                          :psychic       (get-in context [active-team psychic])
                          :clue          (:clue context)}
                   sudden-death-rounds
                   (assoc :sudden-death true))]
      {::st/context context
       ::st/state   state
       ::st/fx      {::st/send [(msg-to-everyone context
                                                 {:type   :merge
                                                  :result result})]}})

    :default
    {::st/context context
     ::st/state   ::st/recur}))


;; --- Reveal Phase

;; Reveal might be better named as :finished as at the end of the game we kind of want people to
;; either play again or change teams

(defn reveal-on-entry
  [{:keys [score] :as context}]
  (let [winner (max-key second (first score) (second score))]
    {::st/context context
     ::st/fx      {::st/send [(msg-to-everyone context {:type   :merge
                                                        :mode   :reveal
                                                        :score score
                                                        :winner (first winner)})]}}))

(defn- reveal-spectator-join
  [{:keys [score] :as _context}]
  {:mode :reveal
   :score score
   :winner (first (max-key second (first score) (second score)))})

(defn reveal-transitions
  [[msg from] {:keys [lobby spectators] :as context}]
  (cond

    (nil? msg)
    (remove-player context from)

    (and (= from lobby) (= :join (:type msg)))
    (join-from-lobby reveal-spectator-join msg context)

    ;; Change teams (i.e. go back to team-lobby/team select)
    (and (= :change-teams (:type msg))
         (not (contains? spectators from)))
    {::st/state   ::wait-in-lobby
     ::st/context (dissoc context :score)
     ::st/fx      {::st/send [(msg-to-everyone context
                                               {:type :merge
                                                :score  nil
                                                ;; TODO this one feels a little different now since that
                                                ;; after doing the team lobby we started making the on-entry
                                                ;; update the user state
                                                :mode :team-lobby})]}}

    ;; Play again (i.e. start another game with the same teams)
    (and (= :play-again (:type msg))
         (not (contains? spectators from)))
    {::st/state   ::pick-psychic
     ::st/context (dissoc context :score)
     ::st/fx      {::st/send [(msg-to-everyone context
                                               {:type   :merge
                                                :result nil
                                                :score  nil})]}}

    :default
    {::st/context context
     ::st/state   ::st/recur}))

;; ---

(def state-machine
  {::wait-in-lobby   {::st/on-entry       #'wait-in-lobby-entry-fx
                      ::st/inputs         #'all-inputs
                      ::st/transition-fn  #'wait-in-lobby-transitions}
   ::pick-psychic    {::st/on-entry       #'on-entry-pick-psychic
                      ::st/inputs         #'all-inputs
                      ::st/transition-fn  #'pick-psychic-transitions}
   ::pick-wavelength {::st/inputs         #'all-inputs
                      ::st/transition-fn  #'pick-wavelength-transitions}
   ::pick-clue       {::st/on-entry       #'pick-clue-on-entry
                      ::st/inputs         #'all-inputs
                      ::st/transition-fn  #'pick-clue-transitions}
   ::team-guess      {::st/on-entry       #'team-guess-on-entry
                      ::st/inputs         #'all-inputs
                      ::st/transition-fn  #'team-guess-transitions}
   ::left-right      {::st/on-entry       #'left-right-on-entry
                      ::st/inputs         #'all-inputs
                      ::st/transition-fn  #'left-right-transitions}
   ::reveal          {::st/on-entry       #'reveal-on-entry
                      ::st/inputs         #'all-inputs
                      ::st/transition-fn  #'reveal-transitions}})

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
             :lobby      lobby-ch}
            ;; Need to not attempt to print deck as it's an infinite sequence
            {:context-fmt #(dissoc % :deck )})))

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

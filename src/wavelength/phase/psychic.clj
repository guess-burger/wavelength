(ns wavelength.phase.psychic
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [lib.stately.core :as st]
   [wavelength.team-lobby :as tlobby]))

;; -- start psychic phase
;; pick the psychic (seem elects or random)
;; offer them a choice of "wavelength" question
;; await choice
;; show others the question
;; choose random "target"
;; show psychic the "target"
;; wait for their "clue"

(def ^:private other-team
  {:left  :right
   :right :left})

(def ^:private wavelengths-cards (edn/read-string (slurp (io/resource "prompts.edn"))))

(defn ^:private wavelength-deck
  ([] (wavelength-deck wavelengths-cards))
  ([cards] (lazy-seq (concat (shuffle cards) (wavelength-deck cards)))))

(comment

  (take 2 (wavelength-deck))
  )

(defn active-waiting-chs
  [{:keys [active-team waiting-team spectators] :as context}]
  (let [active (keys (get context active-team))
        waiting (concat (keys (get context waiting-team)) (keys spectators))]
    [active waiting]))

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
                              score (update {:left 0 :right 0} team inc)]
                          (assoc context :score score :active-team team :waiting-team other
                                         :deck (wavelength-deck)))
                        ;;starting a new round
                        (select-keys context [:score :active-team :waiting-team :left :right :spectators :ready? :cards]))
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

(defn ^:private msg-to-everyone
  [{:keys [left right spectators]} msg]
  {::st/to  (mapcat keys [left right spectators])
   ::st/msg msg}
  )

(defn remove-player
  [context player]
  (let [[name team-k next-context] (tlobby/remove-player-from context player)]
    (cond
      ;; too few players: go back to lobby
      (or (> 2 (-> next-context :left count))
           (> 2 (-> next-context :right count)))
      {::st/context (-> next-context
                        (assoc :lobby-msg (str "Too few players to continue after \"" name "\" left"))
                        (dissoc :score :team-turn))
       ::st/state   ::tlobby/wait-in-lobby}

      ;;TODO player was the psychic: re-pick the psychic
      (= player (:psychic next-context))
      {::st/context (dissoc next-context :psychic)
       ::st/state   ::pick-psychic}

      :else
      {::st/context next-context
       ::st/fx      {::st/send [(msg-to-everyone context {:type  :merge
                                                          team-k (map second (get next-context team-k))})]}
       ::st/state   ::st/recur})))

(defn pick-psychic
  [from {:keys [active-team spectators waiting-team deck] :as context}]
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
            base-msg  {:type :merge
                       :mode :pick-wavelength}]
        {::st/context (assoc context
                        :psychic from
                        :rest-team rest-team)
         ;; TODO need to send msg? Or make the next state do that?
         ::st/state   ::pick-wavelength
         ::st/fx      {::st/send [{::st/to  [from]
                                   ::st/msg (assoc base-msg
                                              :wavelengths (take 2 deck))}
                                  {::st/to  (concat rest-team spectators (-> context waiting-team keys))
                                   ::st/msg (assoc base-msg
                                              :psychic (get-in context [active-team from]))}]}})
      {::st/context context
       ::st/state   ::st/recur})))

(defn everyone-inputs
  [{:keys [left right spectators] :as _context}]
  (mapcat keys [left right spectators]))

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
  (let [options (->> context :deck (take 2))
        pick    (:pick msg)]
    (if (or (= pick (first options))
            (= pick (second options)))
      ;; valid option chosen
      {::st/context (assoc context
                      ;; TODO better name?
                      :pick pick)
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

(defn pick-clue-on-entry
  [context]
  ;; TODO
  #_(let [target (rand)])
  ;; FIXME it seems like xforming the context is optional
  ;; so should be able to not set the context here
  {::st/context context
   })

(defn pick-clue-transitions
  [[msg from] {:keys [psychic] :as context}]
  {::st/context context
   ::st/state   ::st/recur})

(def initial-state ::pick-psychic)
(def state-machine
  {::pick-psychic    {::st/on-entry       #'on-entry-pick-psychic
                      ::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'pick-psychic-transitions}
   ::pick-wavelength {::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'pick-wavelength-transitions}
   ;; TODO decide if the on-entry functions are better for setting up state
   ;; or if the previous state should set it up?
   ::pick-clue       {::st/on-entry       #'pick-clue-on-entry
                      ::st/inputs         #'everyone-inputs
                      ::st/transition-fn  #'pick-clue-transitions}})


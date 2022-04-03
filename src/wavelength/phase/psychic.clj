(ns wavelength.phase.psychic
  (:require
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
                          (assoc context :score score :active-team team :waiting-team other))
                        ;;starting a new round
                        (select-keys context [:score :active-team :waiting-team :left :right :spectators :ready?]))
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

      :else
      {::st/context next-context
       ::st/fx      {::st/send [(msg-to-everyone context {:type  :merge
                                                          team-k (map second (get next-context team-k))})]}
       ::st/state   ::st/recur})))

(defn pick
  [from {:keys [active-team] :as context}]
  (println (dissoc context :left :right :spectators))
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


  ;; TODO uncomment this !!!
  #_(let [team (active-team context)]
    (if (contains? (active-team context) from)
      (do
        (println (get team from) "is gonna be the the psychic")
        {::st/context (assoc context
                        :psychic from
                        ;; FIXME this seem to be broken
                        :rest-team (-> team (dissoc from) key set))
         ;; TODO need to send msg? Or make the next state do that?
         ::st/state   ::pick-clue})
      {::st/context context
       ::st/state   ::st/recur}))

  ;; TODO use the above stuff
  {::st/context context
   ::st/state   ::st/recur})

(defn pick-psychic-inputs
  [{:keys [left right spectators] :as _context}]
  (mapcat keys [left right spectators]))

(defn pick-psychic-transitions
  [[msg from] {:keys [] :as context}]

  ;; TODO: see note above about the same old same old
  (cond

    (nil? msg)
    (remove-player context from)

    (= :pick-psychic (:type msg))
    (pick from context)

    :default
    {::st/context context
     ::st/state   ::st/recur}))


(def initial-state ::pick-psychic)
(def state-machine
  ;; TODO using the on-entry-pick-psychic here might have been a really good idea
  ;; because if we need to loop back to pick-psychic after someone has left
  ;; then it will take care of the message for us...
  ;; TODO might just need to add an extra message
  {::pick-psychic {::st/on-entry       #'on-entry-pick-psychic
                   ::st/inputs         #'pick-psychic-inputs
                   ::st/transition-fn  #'pick-psychic-transitions}})


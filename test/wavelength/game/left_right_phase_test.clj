(ns wavelength.game.left-right-phase-test
  (:require
   [clojure.test :refer :all]
   [lib.stately.core :as st]
   [wavelength.game :as sut]))

;; Wavelength can fit 22 scoring "zone" on it's board
;; and there are 5 scoring zones

(defn create-context
  ([target guess left-score right-score active waiting]
   (create-context target guess left-score right-score active waiting active))
  ([target guess left-score right-score active waiting prev-team]
   {:target       target
    :clue         "clue"
    :guess        guess
    :wavelength   ["left side" "right side"]
    :score        {:left left-score :right right-score}
    :deck         [:card-1 :card-2]

    :lobby        :lobby-channel
    :code         "lobby-code"

    :active-team  active
    :waiting-team waiting

    :psychic      (if (= :left prev-team) :left-one :right-one)
    :rest-team    (if (= :left prev-team) #{:left-two} #{:right-two})

    :left         {:left-one "left one"
                   :left-two "left two"}
    :spectators   {:spectator-one "spectator one"}
    :right        {:right-one "right one"
                   :right-two "right two"}}))

(defn- output
  ([context result]
   (output context result ::sut/pick-psychic))
  ([context result state]
   #::st{:context context
         :state   state
         :fx      {::st/send [{::st/to  [:left-one :left-two
                                         :right-one :right-two
                                         :spectator-one]
                               ::st/msg {:type   :merge
                                         :result result}}]}}))

(deftest scoring-test
  (testing "Guessing team got it exact"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :left-one]
                                       (create-context 20 20 1 0 :right :left))
           (output (create-context 20 20 1 4 :left :right :right)
                   {:active :right
                    :active-score 4 :waiting-score 0
                    :catch-up? false
                    :clue "clue"
                    :psychic "right one"
                    :target 20 :guess 20}))))

  (testing "Guessing team got it just in 4 point barrier"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :left-one]
                                       (create-context 60 58 1 0 :right :left))
           (output (create-context 60 58 1 4 :left :right :right)
                   {:active :right
                    :active-score 4 :waiting-score 0
                    :catch-up? false
                    :clue "clue"
                    :psychic "right one"
                    :target 60 :guess 58})))
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :left-one]
                                       (create-context 60 62 1 0 :right :left))
           (output (create-context 60 62 1 4 :left :right :right)
                   {:active :right
                    :active-score 4 :waiting-score 0
                    :catch-up? false
                    :clue "clue"
                    :psychic "right one"
                    :target 60 :guess 62}))))

  (testing "Catch-up rule"
    ;; Catch-up rule applies if you get 4 points and are still behind
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :right-one]
                                       (create-context 60 62 0 5 :left :right))
           (output (create-context 60 62 4 5 :left :right)
                   {:active :left
                    :active-score 4 :waiting-score 0
                    :catch-up? true
                    :clue "clue"
                    :psychic "left one"
                    :target 60 :guess 62})))
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :right-one]
                                       (create-context 60 62 0 4 :left :right))
           (output (create-context 60 62 4 4 :right :left :left)
                   {:active :left
                    :active-score 4 :waiting-score 0
                    :catch-up? false
                    :clue "clue"
                    :psychic "left one"
                    :target 60 :guess 62})))))

(deftest winner-test
  (testing "Not quite"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :right-one]
                                       (create-context 62 62 5 0 :left :right))
           (output (create-context 62 62 9 0 :right :left :left)
                   {:active :left
                    :active-score 4 :waiting-score 0
                    :catch-up? false
                    :clue "clue"
                    :psychic "left one"
                    :target 62 :guess 62}))))

  (testing "Clear winner"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :right-one]
                                       (create-context 88 88 6 0 :left :right))
           (output (create-context 88 88 10 0 :right :left :left)
                   {:active :left
                    :active-score 4 :waiting-score 0
                    :catch-up? false
                    :clue "clue"
                    :psychic "left one"
                    ;; FIXME mark that someone won!
                    :target 88 :guess 88}
                   ::sut/reveal))))

  (testing "Both passed 10"
    ;; team with the highest
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :right} :right-one]
                                       (create-context 88 83 8 9 :left :right))
           (output (create-context 88 83 11 10 :right :left :left)
                   {:active :left
                    :active-score 3 :waiting-score 1
                    :catch-up? false
                    :clue "clue"
                    :psychic "left one"
                    :target 88 :guess 83}
                   ::sut/reveal)))))

(deftest tie-breaker
  ;; If there's a tie, each team takes a final sudden death turn.
  ;; The team that scores the most points that round wins (including the LEFT/RIGHT guess).
  ;; If there's still a tie, repeat until a team has won.

  (testing "entering sudden death"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :right} :right-one]
                                       (create-context 88 83 7 9 :left :right))
           (output (assoc (create-context 88 83 10 10 :right :left :left)
                          :sudden-death-rounds 1)
                   {:active       :left
                    :active-score 3 :waiting-score 1
                    :catch-up?    false
                    :clue         "clue"
                    :psychic      "left one"
                    :sudden-death true
                    :target       88 :guess 83}))))

  (testing "first round of sudden death"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :right} :left-one]
                                       (assoc (create-context 88 83 10 10 :right :left)
                                              :sudden-death-rounds 1))
           (output (assoc (create-context 88 83 11 13 :left :right :right)
                          :sudden-death-rounds 0)
                   {:active       :right
                    :active-score 3 :waiting-score 1
                    :catch-up?    false
                    :clue         "clue"
                    :psychic      "right one"
                    :sudden-death true
                    :target       88 :guess 83}))))

  (testing "second round of sudden death"
    (testing "ending with a winner"
      (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :right} :right-one]
                                         (-> (create-context 88 89 11 13 :left :right)
                                             (assoc :sudden-death-rounds 0)))
             (output (-> (create-context 88 89 15 13 :right :left :left)
                         (assoc :sudden-death-rounds 0))
                     {:active       :left
                      :active-score 4 :waiting-score 0
                      :catch-up?    false
                      :clue         "clue"
                      :psychic      "left one"
                      :sudden-death true
                      :target       88 :guess 89}
                     ::sut/reveal))))
    (testing "ending with another draw"
      (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :right} :right-one]
                                         (-> (create-context 88 83 11 13 :left :right)
                                             (assoc :sudden-death-rounds 0)))
             (output (-> (create-context 88 83 14 14 :right :left :left)
                         (assoc :sudden-death-rounds 1))
                     {:active       :left
                      :active-score 3 :waiting-score 1
                      :catch-up?    false
                      :clue         "clue"
                      :psychic      "left one"
                      :sudden-death true
                      :target       88 :guess 83}))))))
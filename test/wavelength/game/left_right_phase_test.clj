(ns wavelength.game.left-right-phase-test
  (:require
   [clojure.test :refer :all]
   [lib.stately.core :as st]
   [wavelength.game :as sut]))

;; Wavelength can fit 22 scoring "zone" on it's board
;; and there are 5 scoring zones

(defn create-context
  [target guess left-score right-score active waiting]
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
   ;; these are junk fake values that matter here
   :psychic      :some-one
   :rest-team    #{:some-two}

   :left         {:left-one "left one"
                  :left-two "left two"}
   :spectators   {:spectator-one "spectator one"}
   :right        {:right-one "right one"
                  :right-two "right two"}})

(defn- output
  [context result]
  #::st{:context context
        :state   ::sut/pick-psychic
        :fx      {::st/send [{::st/to  [:left-one :left-two
                                        :right-one :right-two
                                        :spectator-one]
                              ::st/msg {:type  :merge
                                        :result result}}]}})

(deftest scoring-test
  (testing "Guessing team got it exact"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :left-one]
                                       (create-context 20 20 1 0 :right :left))
           (output (create-context 20 20 1 4 :left :right)
                   {:active :right
                   :active-score 4 :waiting-score 0
                   :catch-up? false
                   :target 20 :guess 20}
                   #_{:left  0 :right 4 :catch-up? false #_:winner #_nil}))))

  (testing "Guessing team got it just in 4 point barrier"
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :left-one]
                                       (create-context 60 58 1 0 :right :left))
           (output (create-context 60 58 1 4 :left :right)
                   {:active :right
                   :active-score 4 :waiting-score 0
                   :catch-up? false
                   :target 60 :guess 58}
                   #_{:left  0 :right 4 :catch-up? false #_:winner #_nil})))
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :left-one]
                                       (create-context 60 62 1 0 :right :left))
           (output (create-context 60 62 1 4 :left :right)
                   {:active :right
                   :active-score 4 :waiting-score 0
                   :catch-up? false
                   :target 60 :guess 62}
                   #_{:left  0 :right 4 :catch-up? false #_:winner #_nil}))))

  (testing "Catch-up rule"
    ;; Catch-up rule applies if you get 4 points and are still behind
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :right-one]
                                       (create-context 60 62 0 5 :left :right))
           (output (create-context 60 62 4 5 :left :right)
                   {:active :left
                   :active-score 4 :waiting-score 0
                   :catch-up? true
                   :target 60 :guess 62}
                   #_{:left  4 :right 0 :catch-up? true #_:winner #_nil})))
    (is (= (sut/left-right-transitions [{:type :pick-lr, :guess :left} :right-one]
                                       (create-context 60 62 0 4 :left :right))
           (output (create-context 60 62 4 4 :right :left)
                   {:active :left
                   :active-score 4 :waiting-score 0
                   :catch-up? false
                   :target 60 :guess 62}
                   #_{:left  4 :right 0 :catch-up? false #_:winner #_nil})))))
(ns wavelength.team-lobby-test
  (:require [clojure.test :refer :all]
            [lib.stately.core :as st]
            [wavelength.team-lobby :as sut]))



(deftest join-test
  (testing "joining a 'fresh' lobby"
    (let [context {:left       {}
                   :spectators {:foo "foo"}
                   :right      {}
                   :code       "lobby-code"
                   :lobby      :the-lobby}
          msg {:type     :join
               :player   :bar
               :nickname "bar"}]
      (= #::st{:state   :lib.stately.core/recur,
               :context {:left       {},
                         :spectators {:foo "foo", :bar "bar"},
                         :right      {},
                         :code       "lobby-code",
                         :lobby      :the-lobby},
               :fx      #::st{:send [#::st{:to  [:foo],
                                           :msg {:type       :merge,
                                                 :spectators {:foo "foo", :bar "bar"}}}
                                     #::st{:to  :bar,
                                           :msg {:type       :merge,
                                                 :mode       :team-lobby,
                                                 :room-code  "lobby-code",
                                                 :left       {},
                                                 :spectators {:foo "foo", :bar "bar"},
                                                 :right      {}}}]}}

         (sut/wait-in-lobby-transitions
           [msg :the-lobby] context))))

  (testing "joining an already joined lobby"
    (let [context {:left       {},
                   :spectators {:foo "foo", :bar "bar"},
                   :right      {},
                   :code       "lobby-code",
                   :lobby      :the-lobby}
          msg {:type     :join
               :player   :baz
               :nickname "baz"}]
      (is (= #::st{:context {:code       "lobby-code"
                             :left       {}
                             :lobby      :the-lobby
                             :right      {}
                             :spectators {:bar "bar"
                                          :baz "baz"
                                          :foo "foo"}}
                   :fx      #::st{:send [#::st{:msg {:spectators ["foo" "bar" "baz"]
                                                     :type       :merge}
                                               :to  [:foo :bar]}
                                         #::st{:msg {:left       []
                                                     :mode       :team-lobby
                                                     :right      []
                                                     :room-code  "lobby-code"
                                                     :spectators ["foo" "bar" "baz"]
                                                     :type       :reset}
                                               :to  [:baz]}]}
                                :state   ::st/recur}
             (sut/wait-in-lobby-transitions
               [msg :the-lobby] context))))))

(deftest leave-lobby
  (let [context {:left       {},
                 :spectators {:foo "foo", :bar "bar"},
                 :right      {},
                 :code       "lobby-code",
                 :lobby      :the-lobby}]
    (is (= #::st{:state   ::st/recur,
                 :context {:left       {},
                           :spectators {:foo "foo"},
                           :right      {},
                           :code       "lobby-code",
                           :lobby      :the-lobby},
                 :fx      #::st{:send [#::st{:msg {:type       :merge,
                                                   :left       [],
                                                   :spectators ["foo"],
                                                   :right      []},
                                             :to  [:foo]}]}}
           (sut/wait-in-lobby-transitions [nil :bar] context))))

  (let [context {:left       {}
                 :spectators {:foo "foo"}
                 :right      {}
                 :code       "lobby-code"
                 :lobby      :the-lobby}]
    (is (= #::st{:state ::st/end}
           (sut/wait-in-lobby-transitions [nil :foo] context)))))


(deftest pick-team
  (let [msg {:type :pick-team :team :left}
        context {:left       {},
                 :spectators {:foo "foo", :bar "bar"},
                 :right      {},
                 :code       "lobby-code",
                 :lobby      :the-lobby}]
    (is (= #::st{:state   ::st/recur,
                 :context {:left       {:bar "bar"},
                           :spectators {:foo "foo"},
                           :right      {},
                           :code       "lobby-code",
                           :lobby      :the-lobby},
                 :fx      #::st{:send [#::st{:msg {:type       :merge,
                                                   :left       ["bar"],
                                                   :spectators ["foo"],},
                                             :to  [:bar :foo]}]}}
           (sut/wait-in-lobby-transitions [msg :bar] context))))
  (let [msg {:type :pick-team, :team :right}
        context {:left       {},
                 :spectators {:foo "foo", :bar "bar"},
                 :right      {},
                 :code       "lobby-code",
                 :lobby      :the-lobby}]
    (is (= #::st{:state   ::st/recur,
                 :context {:left       {},
                           :spectators {:foo "foo"},
                           :right      {:bar "bar"},
                           :code       "lobby-code",
                           :lobby      :the-lobby},
                 :fx      #::st{:send [#::st{:msg {:type       :merge,
                                                   :right      ["bar"],
                                                   :spectators ["foo"],},
                                             :to  [:foo :bar]}]}}
           (sut/wait-in-lobby-transitions [msg :bar] context))))
  (testing "joining the same team"
    (let [msg {:type :pick-team, :team :right}
          context {:left       {},
                   :spectators {:foo "foo"},
                   :right      {:bar "bar" :baz "baz"},
                   :code       "lobby-code",
                   :lobby      :the-lobby}]
      (is (= #::st{:state   ::st/recur,
                   :context {:left       {}
                             :spectators {:foo "foo"}
                             :right      {:bar "bar"
                                          :baz "baz"}
                             :code       "lobby-code"
                             :lobby      :the-lobby}}
             (sut/wait-in-lobby-transitions [msg :bar] context))))))
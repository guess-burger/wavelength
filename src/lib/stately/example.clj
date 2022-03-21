(ns lib.stately.example
  (:require [clojure.core.async :as as]
            [lib.stately.core :as st]))

(def plan
  {:foo {::st/inputs
         (fn [context] [(:in context)])

         ::st/transition-fn
         (fn [[msg _sender] context]
           {::st/state   :bar
            ::st/context (update context :count + msg)})}
   :bar {::st/inputs
         (fn [context] [(:in context)])

         ::st/transition-fn
         (fn [[msg _sender] context]
           {::st/state   :baz
            ::st/context (update context :count * msg)})}
   :baz {::st/inputs
         (fn [state] [(:in state)])

         ::st/transition-fn
         (fn [[msg _ch] context]
           (let [count (- (:count context) msg)]
             {::st/state ::st/end
              ;; is there any point with this context?
              ;::st/context state
              ::st/fx    {::st/send [{::st/to  [(:out context)]
                                      ::st/msg count}]}}))}})

(comment
  (def in (as/chan))
  (def out (as/chan))

  (st/run plan :foo {:in in :count 0 :out out})

  (as/put! in 4)
  (as/put! in 5)
  (as/put! in 6)
  (as/take! out (fn [msg] (println "I took out" msg)))

  )
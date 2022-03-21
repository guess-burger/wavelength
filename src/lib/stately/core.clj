(ns lib.stately.core
  "This is an experiment that tries to smooth out some rough edges I felt attempting
   to make this game using core.async. As such, there are a lot of assumptions on
   how to use core.async and that core.async makes sense for this problem.

   It attempts to allow you to consider the game 'process' as a state machine
   which "
  (:require [clojure.core.async :as as :refer [go-loop alts! chan]]))

(defn send [to msg]
  "Util for interacting with a running state"
  ;; TODO this should handle more types than just core.async for testing
  (as/put! to msg))

(defmulti apply-effect first)

(defmethod apply-effect ::send [params]
  (doseq [{::keys [to msg]} (second params)
          port              to]
    (println "sending" port msg)
    (send port msg)))

(defmethod apply-effect ::close [params]
  (doseq [ch (second params)]
    (as/close! ch)))

(defn run
  [plan initial-state initial-context]
  (go-loop [state    initial-state
            context  initial-context
            reentry? false]
           (println "Entered:" state context reentry?)
           ;; FIXME: WTF is this formatting!
           (let [{::keys [inputs transition-fn first-entry-fx]} (get plan state)
                 _ (when (and first-entry-fx (not reentry?))
                     (doseq [fx (first-entry-fx context)]
                       ;(println "Apply entry fx:" fx)
                       (apply-effect fx)))
                 i          (inputs context)
                 ;_ (println "inputs" i)
                 msg+ch     (alts! i)
                 ;_ (println "got" msg+ch)
                 {next-state ::state
                  next-context ::context
                  fx ::fx}  (transition-fn msg+ch context)
                 next-state (if (= ::recur next-state) state next-state)
                 ;_ (println next-state)
                 ]
             (doseq [x fx]
               ;(println "fx:" x)
               (apply-effect x))
             ;(println "Entering:" next-state next-context)
             (if (= ::end next-state)
               ;; does this do anything? I don't think so
               context
               (recur next-state next-context (= state next-state))))))
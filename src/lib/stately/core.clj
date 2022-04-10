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
    (println "Entered:" state #_context reentry?)
    ;; FIXME: WTF is this formatting!
    ;; TODO: Probably need to handle these fns being nil better... or at all
    (let [{::keys [inputs transition-fn on-entry]} (get plan state)
          ;; Apply FXs + Xform context if entering for first time
          context (if (and on-entry (not reentry?))
                    (let [result (on-entry context)]
                      ;(println result)
                      (doseq [x (::fx result)] (apply-effect x))
                      (or (::context result) context))
                    context)
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
      ;(println "Entering:" next-state #_next-context)
      (if (= ::end next-state)
        ;; does this do anything? I don't think so
        context
        (recur next-state next-context (= state next-state))))))
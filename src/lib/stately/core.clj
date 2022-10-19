(ns lib.stately.core
  "This is an experiment that tries to smooth out some rough edges I felt attempting
   to make this game using core.async. As such, there are a lot of assumptions on
   how to use core.async and that core.async makes sense for this problem.

   It attempts to allow you to consider the game 'process' as a state machine,
   moving between states as different inputs are received."
  (:require [clojure.core.async :as as :refer [go-loop alts!]]
            [clojure.tools.logging :as log]))

;; TODO should these kind of things be expanded to handle more types than just core.async to allow for easier testing...
;;  or should it not pretend it isn't async?
(defn send
  "Util for interacting with a running state"
  [to msg]
  (as/put! to msg))

(defmulti apply-effect first)

(defmethod apply-effect ::send [params]
  (doseq [{::keys [to msg]} (second params)
          port to]
    (log/info "Sending" {:to port :msg msg} )
    (send port msg)))

(defmethod apply-effect ::close [params]
  (doseq [ch (second params)]
    (log/debug "Closing channel" ch)
    (as/close! ch)))

(defn run
  ([plan initial-state initial-context]
   (run plan initial-state initial-context {}))
  ([plan initial-state initial-context {:keys [context-fmt]
                                        :or   {context-fmt identity}
                                        :as   _opts}]
   (go-loop [state initial-state
             context initial-context
             reentry? false]
     (log/info "Entered State" {:state state :reentry? reentry? :context (context-fmt context)})
     (let [{::keys [inputs transition-fn on-entry]} (get plan state)
           ;; Apply FXs + transform context if entering for first time
           context    (if (and on-entry (not reentry?))
                        (let [result     (on-entry context)
                              fx         (::fx result)
                              oe-context (::context result)]
                          (log/debug "on-entry result" {:state state :fx fx :context (context-fmt oe-context)})
                          (doseq [x fx] (apply-effect x))
                          (or (::context result) context))
                        context)
           i          (inputs context)
           _          (log/debug "inputs" i)
           msg+ch     (alts! i)
           _          (log/debug "got" msg+ch)
           {next-state   ::state
            next-context ::context
            fx           ::fx
            :as          _result} (transition-fn msg+ch context)
           _          (log/debug "Transition returned" {:state next-state :fx fx})
           next-state (if (= ::recur next-state) state next-state)]
       (doseq [x fx]
         (log/debug "apply effect" x)
         (apply-effect x))
       (if (= ::end next-state)
         (do
           (log/info "State-machine terminating")
           context)
         (recur next-state next-context (= state next-state)))))))

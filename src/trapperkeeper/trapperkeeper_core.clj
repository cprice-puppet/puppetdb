(ns trapperkeeper.trapperkeeper-core
  (:require [plumbing.graph :as graph]))

;; TODO: I'm expecting that this function will usually not be used by
;; external programs.  Instead, there will be some 'bootstrap' function
;; that reads a bootstrap config file and calls this.
(defn build-app
  [service-graphs]
  (apply merge service-graphs))

(defn run
  [trapperkeeper-app]
  ;; TODO: still have some lifecycle questions around this
  ;; part.  Here, the act of compiling the graph accomplishes
  ;; the work of starting the application; however, I think it
  ;; might be more flexible to have some kind of explicit
  ;; 'start' hook/function that each service can register;
  ;; then, compiling the graph wouldn't start the app; we'd
  ;; compile, and then call the lifecycle functions on the
  ;; services in order.
  ((graph/eager-compile trapperkeeper-app) {}))
;; TODO docs
(ns com.puppetlabs.puppetdb.http.experimental.node-run-summary
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.node-run-summary :as query]
            [cheshire.core :as json])
  (:use     [net.cgrand.moustache :only [app]]
            [com.puppetlabs.middleware :only [verify-param-exists
                                              verify-accepts-json]]
            [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  ;; TODO docstring
  [node num-runs db]
  (try
    (with-transacted-connection db
      (-> (query/query->sql node num-runs)
        (query/query-node-run-summaries)
        (pl-http/json-response)))
    (catch com.fasterxml.jackson.core.JsonParseException e
      (pl-http/error-response e))
    (catch IllegalArgumentException e
      (pl-http/error-response e))
    (catch IllegalStateException e
      (pl-http/error-response e pl-http/status-internal-error))))

(def routes
  (app
    [""]
    {:get (fn [{:keys [params globals]}]
            (produce-body
              (params "node")
              (Integer/parseInt (params "num-runs"))
              (:scf-db globals)))}))

(def node-run-summary-app
  "Ring app for querying for summary information about puppet runs on nodes."
  (-> routes
    verify-accepts-json
    (verify-param-exists "node")
    (verify-param-exists "num-runs")))
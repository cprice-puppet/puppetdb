;; TODO docs
(ns com.puppetlabs.puppetdb.http.experimental.event-counts
  (:require [com.puppetlabs.http :as pl-http]
            [com.puppetlabs.puppetdb.query.event-counts :as query]
            [cheshire.core :as json])
  (:use     [net.cgrand.moustache :only [app]]
            [com.puppetlabs.middleware :only [verify-param-exists
                                              verify-accepts-json]]
            [com.puppetlabs.jdbc :only (with-transacted-connection)]))

(defn produce-body
  ;; TODO docstring
  [query group-by db]
  (try
    (with-transacted-connection db
      (-> query
        (json/parse-string true)
        (query/query->sql group-by)
        (query/query-resource-event-counts)
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
            (produce-body (params "query") (params "summarize-by") (:scf-db globals)))}))

(def event-counts-app
  "Ring app for querying for summary information about resource events."
  (-> routes
    verify-accepts-json
    (verify-param-exists "query")
    (verify-param-exists "summarize-by")))
;; TODO: docs

(ns com.puppetlabs.puppetdb.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.event :as event])
  (:use [com.puppetlabs.jdbc :only [query-to-vec
                                    valid-jdbc-query?]]))

(defn get-group-by
  ;; TODO docs
  [summarize-by]
  (condp = summarize-by
    "certname" "certname"
    "resource-class" "resource_class"
    "resource" "resource_type, resource_title"
    (throw (IllegalArgumentException.
             (format "Unsupported-value for 'summarize-by': '%s'" summarize-by)))))

(defn query->sql
  ;; TODO docs
  [query summarize-by]
  {:pre  [(vector? query)]
   :post [(valid-jdbc-query? %)]}

  (let [group-by (get-group-by summarize-by)
        [event-sql & event-params] (event/query->sql query)
        sql (format
              (str "SELECT %s,
                      SUM(CASE WHEN status = 'failure' THEN 1 ELSE 0 END) AS failures,
                      SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END) AS successes,
                      SUM(CASE WHEN status = 'noop' THEN 1 ELSE 0 END) AS noops,
                      SUM(CASE WHEN status = 'skipped' THEN 1 ELSE 0 END) AS skips

                    FROM (%s) events
                    GROUP BY %s")
                ;; TODO: support order by and limit (probably globally, not here)
                ;   ORDER BY failures DESC
                ;   LIMIT 10
              group-by
              event-sql
              group-by)
        result (apply vector sql event-params)]
    result))

(defn query-resource-event-counts
  ;; TODO docs
  [[sql & params]]
  {:pre [(string? sql)]}
  (query-to-vec (apply vector sql params)))
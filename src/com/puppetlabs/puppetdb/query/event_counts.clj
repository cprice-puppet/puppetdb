;; TODO: docs

(ns com.puppetlabs.puppetdb.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.event :as event])
  (:use [com.puppetlabs.jdbc :only [query-to-vec
                                    valid-jdbc-query?]]))

(defn query->sql
  ;; TODO docs
  [query group-by]
  {:pre  [(vector? query)]
   :post [(valid-jdbc-query? %)]}
  ;; TODO sanitize / validate group-by, convert dashes to underscores, etc.
  (let [[event-sql & event-params] (event/query->sql query)
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
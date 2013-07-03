;; TODO: docs

(ns com.puppetlabs.puppetdb.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.event :as event])
  (:use [com.puppetlabs.jdbc :only [sorted-query-to-vec
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

(defn- event-count-sql
  ;; TODO docs / preconds
  [query group-by]
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
          group-by)]
    (apply vector sql event-params)))

(defn- aggregate-sql
  ;; TODO docs/preconds
  [event-count-sql]
  (let [[count-sql & params] event-count-sql
        sql (format
              (str "SELECT SUM(CASE WHEN successes > 0 THEN 1 ELSE 0 END) as successes,
	                    SUM(CASE WHEN failures > 0 THEN 1 ELSE 0 END) as failures,
	                    SUM(CASE WHEN noops > 0 THEN 1 ELSE 0 END) as noops,
	                    SUM(CASE WHEN skips > 0 THEN 1 ELSE 0 END) as skips

	                  FROM (%s) event_counts")
              count-sql)]
    (apply vector sql params)))

(defn query->sql
  ;; TODO docs
  [query summarize-by aggregate]
  {:pre  [(vector? query)
          (string? summarize-by)
          ((some-fn true? false?) aggregate)]
   :post [(valid-jdbc-query? %)]}

  (let [group-by (get-group-by summarize-by)
        sql (event-count-sql query group-by)]
    (if aggregate
      (aggregate-sql sql)
      sql)))


(defn query-resource-event-counts
  ;; TODO docs
  [[sql & params :as sql-and-params] query-params]
  {:pre [(string? sql)]}
  (sorted-query-to-vec sql-and-params query-params))
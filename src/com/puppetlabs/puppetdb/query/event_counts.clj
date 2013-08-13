;; TODO: docs

(ns com.puppetlabs.puppetdb.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.event :as event])
  (:use [com.puppetlabs.jdbc :only [paged-sorted-query-to-vec
                                    valid-jdbc-query?]]))

(defn get-group-by
  ;; TODO docs
  [summarize-by]
  (condp = summarize-by
    "certname" "certname"
    "containing-class" "containing_class"
    "resource" "resource_type, resource_title"
    (throw (IllegalArgumentException.
             (format "Unsupported-value for 'summarize-by': '%s'" summarize-by)))))


(defn- get-count-by-sql
  ;; TODO docs / preconds
  [sql count-by group-by]
  (condp = count-by
    "resource"  sql
    "node"      (format "SELECT DISTINCT certname, status, %s FROM (%s) distinct_events"
                  group-by sql)
    (throw (IllegalArgumentException.
             (format "Unsupported value for 'count-by': '%s'" count-by)))))


(defn- get-event-count-sql
  ;; TODO docs / preconds
  [event-sql group-by]
  (let [sql (format
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
;    (println "SQL:" sql)
;    (println "PARAMS:" event-params)
;    (apply vector sql event-params)))
    sql))

(defn- get-aggregate-sql
  ;; TODO docs/preconds
  [count-sql aggregate]
  (if aggregate
    (format
              (str "SELECT SUM(CASE WHEN successes > 0 THEN 1 ELSE 0 END) as successes,
	                    SUM(CASE WHEN failures > 0 THEN 1 ELSE 0 END) as failures,
	                    SUM(CASE WHEN noops > 0 THEN 1 ELSE 0 END) as noops,
	                    SUM(CASE WHEN skips > 0 THEN 1 ELSE 0 END) as skips
	                  FROM (%s) event_counts")
              count-sql)
    count-sql))

(defn query->sql
  ;; TODO docs
  [query last-run-only? summarize-by count-by aggregate?]
  {:pre  [(vector? query)
          ((some-fn true? false?) last-run-only?)
          (string? summarize-by)
          (string? count-by)
          ((some-fn true? false?) aggregate?)]
   :post [(valid-jdbc-query? %)]}

  (let [group-by                    (get-group-by summarize-by)
        [event-sql & event-params]  (event/query->sql query last-run-only?)
        count-by-sql                (get-count-by-sql event-sql count-by group-by)
        event-count-sql             (get-event-count-sql count-by-sql group-by)
        aggregate-sql               (get-aggregate-sql event-count-sql aggregate?)]
    (apply vector aggregate-sql event-params)))

(defn query-resource-event-counts
  ;; TODO docs
  [[sql & params :as sql-and-params] query-params]
  {:pre [(string? sql)]}
  (paged-sorted-query-to-vec sql-and-params query-params))

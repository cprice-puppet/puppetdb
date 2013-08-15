;; TODO: docs

(ns com.puppetlabs.puppetdb.query.event-counts
  (:require [com.puppetlabs.puppetdb.query.event :as event]
            [clojure.string :as string])
  (:use [com.puppetlabs.jdbc :only [paged-sorted-query-to-vec
                                    valid-jdbc-query?
                                    underscores->dashes
                                    dashes->underscores]]
        [clojure.core.match :only [match]]
        [com.puppetlabs.puppetdb.query :only [compile-term]]))



(defn compile-event-count-inequality
  ;; TODO: docs
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
    [(field :when #{"successes" "failures" "noops" "skips"})]
    {:where (format "%s %s ?" field op)
     :params [value]}

    :else (throw (IllegalArgumentException.
                   (str op "  operator does not support object '" path "' for event counts")))))

(defn compile-event-count-equality
  ;; TODO: docs
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (let [db-field (dashes->underscores path)]
    (match [db-field]
      [(field :when #{"successes" "failures" "noops" "skips"})]
      {:where (format "%s = ?" field)
       :params [value] }

      :else (throw (IllegalArgumentException.
                     (str path " is not a queryable object for event counts"))))))

(defn event-count-ops
  "Maps resource event query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= op "=") compile-event-count-equality
      (#{">" "<" ">=" "<="} op) (partial compile-event-count-inequality op))))


(defn- get-group-by
  ;; TODO docs
  [summarize-by]
  (condp = summarize-by
    "certname" "certname"
    "containing-class" "containing_class"
    "resource" "resource_type, resource_title"
    (throw (IllegalArgumentException.
             (format "Unsupported-value for 'summarize-by': '%s'" summarize-by)))))

(defn- get-event-counts-where-clause
  ;; TODO docs
  [event-count-query]
  (if event-count-query
    (compile-term event-count-ops event-count-query)
    {:where nil :params [] }))

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

(defn- get-filtered-sql
  [sql where]
  (if where
    (format "SELECT * FROM (%s) count_results WHERE %s"
      sql where)
    sql))


(defn query->sql
  ;; TODO docs
  [query event-count-query last-run-only? summarize-by count-by aggregate?]
  {:pre  [(vector? query)
          ((some-fn nil? vector?) event-count-query)
          ((some-fn true? false?) last-run-only?)
          (string? summarize-by)
          (string? count-by)
          ((some-fn true? false?) aggregate?)]
   :post [(valid-jdbc-query? %)]}

  (let [group-by                      (get-group-by summarize-by)
        {event-counts-where  :where
         event-counts-params :params} (get-event-counts-where-clause event-count-query)

        [event-sql & event-params]    (event/query->sql query last-run-only?)
        count-by-sql                  (get-count-by-sql event-sql count-by group-by)
        event-count-sql               (get-event-count-sql count-by-sql group-by)
        aggregate-sql                 (get-aggregate-sql event-count-sql aggregate?)
        filtered-sql                  (get-filtered-sql aggregate-sql event-counts-where)]
    ;(println "FINAL SQL:" filtered-sql)
    (apply vector filtered-sql (concat event-params event-counts-params))))

(defn query-resource-event-counts
  ;; TODO docs
  [[sql & params :as sql-and-params] query-params]
  {:pre [(string? sql)]}
  (paged-sorted-query-to-vec sql-and-params query-params))

;; ## SQL/query-related functions for events

(ns com.puppetlabs.puppetdb.query.event
  (:require [com.puppetlabs.utils :as utils]
            [clojure.string :as string]
            [cheshire.core :as json])
  (:use [com.puppetlabs.jdbc :only [paged-sorted-query-to-vec
                                    underscores->dashes
                                    dashes->underscores
                                    valid-jdbc-query?
                                    add-limit-clause]]
        [com.puppetlabs.jdbc.internal :only [limit-result-set!]]
        [com.puppetlabs.puppetdb.scf.storage :only [db-serialize sql-regexp-match]]
        [com.puppetlabs.puppetdb.query :only [compile-term compile-and compile-or compile-not-v2]]
        [clojure.core.match :only [match]]
        [clj-time.coerce :only [to-timestamp]]))

(defn compile-resource-event-inequality
  "Compile a timestamp inequality for a resource event query (> < >= <=).
  The `value` for comparison must be coercible to a timestamp via
  `clj-time.coerce/to-timestamp` (e.g., an ISO-8601 compatible date-time string)."
  [& [op path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 3)
    (throw (IllegalArgumentException. (format "%s requires exactly two arguments, but %d were supplied" op (dec (count args))))))
  (match [path]
    ["timestamp"]
    (if-let [timestamp (to-timestamp value)]
      {:where (format "resource_events.timestamp %s ?" op)
       :params [timestamp]}
      (throw (IllegalArgumentException. (format "'%s' is not a valid timestamp value" value))))

    :else (throw (IllegalArgumentException.
                   (str op " operator does not support object '" path "' for resource events")))))

(defn compile-resource-event-equality
  "Compile an = predicate for resource event query. `path` represents the field to
  query against, and `value` is the value."
  [& [path value :as args]]
  {:post [(map? %)
          (string? (:where %))]}
  (when-not (= (count args) 2)
    (throw (IllegalArgumentException. (format "= requires exactly two arguments, but %d were supplied" (count args)))))
  (let [db-field (dashes->underscores path)]
    (match [db-field]
      ["certname"]
      {:where (format "reports.certname = ?")
       :params [value]}

      [(field :when #{"report" "resource_type" "resource_title" "status"})]
      {:where (format "resource_events.%s = ?" field)
       :params [value] }

      ;; these fields allow NULL, which causes a change in semantics when
      ;; wrapped in a NOT(...) clause, so we have to be very explicit
      ;; about the NULL case.
      [(field :when #{"containing_class" "property" "message"})]
      {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
       :params [value] }

      ;; these fields require special treatment for NULL (as described above),
      ;; plus a serialization step since the values can be complex data types
      [(field :when #{"old_value" "new_value"})]
      {:where (format "resource_events.%s = ? AND resource_events.%s IS NOT NULL" field field)
       :params [(db-serialize value)] }

      ["timestamp"]
      (if-let [timestamp (to-timestamp value)]
        {:where "resource_events.timestamp = ?"
         :params [timestamp]}
        (throw (IllegalArgumentException. (format "'%s' is not a valid timestamp value" value))))


      :else (throw (IllegalArgumentException.
                     (str path " is not a queryable object for resource events"))))))

(defn compile-resource-event-regexp
  "Compile an ~ predicate for resource event query. `path` represents the field
   to query against, and `pattern` is the regular expression to match."
    [& [path pattern :as args]]
    {:post [(map? %)
            (string? (:where %))]}
    (when-not (= (count args) 2)
      (throw (IllegalArgumentException. (format "~ requires exactly two arguments, but %d were supplied" (count args)))))
    (let [path (dashes->underscores path)]
      (match [path]
        ["certname"]
        {:where (sql-regexp-match "reports.certname")
         :params [pattern]}

        [(field :when #{"report" "resource_type" "resource_title" "status"})]
        {:where  (sql-regexp-match (format "resource_events.%s" field))
         :params [pattern] }

        ;; these fields allow NULL, which causes a change in semantics when
        ;; wrapped in a NOT(...) clause, so we have to be very explicit
        ;; about the NULL case.
        [(field :when #{"property" "message"})]
        {:where (format "%s AND resource_events.%s IS NOT NULL"
                    (sql-regexp-match (format "resource_events.%s" field))
                    field)
         :params [pattern] }

        :else (throw (IllegalArgumentException.
                       (str path " is not a queryable object for resource events"))))))



(defn resource-event-ops
  "Maps resource event query operators to the functions implementing them. Returns nil
  if the operator isn't known."
  [op]
  (let [op (string/lower-case op)]
    (cond
      (= op "=") compile-resource-event-equality
      (= op "and") (partial compile-and resource-event-ops)
      (= op "or") (partial compile-or resource-event-ops)
      (= op "not") (partial compile-not-v2 resource-event-ops)
      (#{">" "<" ">=" "<="} op) (partial compile-resource-event-inequality op)
      (= op "~") compile-resource-event-regexp)))

(defn query->sql
  "Compile a resource event `query` into an SQL expression."
  ([query]
    (query->sql query false))
  ([query last-run-only?]
    {:pre  [(vector? query)]
     :post [(valid-jdbc-query? %)]}
    (let [{:keys [where params]}  (compile-term resource-event-ops query)
          last-run-only-join      (if last-run-only?
                                    "INNER JOIN latest_reports
                                        ON reports.certname = latest_reports.node
                                        AND reports.hash = latest_reports.report"
                                    "")
          sql (format (str "SELECT reports.certname,
                                    resource_events.report,
                                    resource_events.status,
                                    resource_events.timestamp,
                                    resource_events.resource_type,
                                    resource_events.resource_title,
                                    resource_events.containment_path,
                                    resource_events.containing_class,
                                    resource_events.property,
                                    resource_events.new_value,
                                    resource_events.old_value,
                                    resource_events.message,
                                    resource_events.file,
                                    resource_events.line
                                    FROM resource_events
                                    JOIN reports ON resource_events.report = reports.hash
                                    %s
                                    WHERE %s")
                last-run-only-join
                where)]
      (apply vector sql params))))

(defn limited-query-resource-events
  "Take a limit, a query, and its parameters, and return a vector of resource
   events which match.  Throws an exception if the query would
   return more than `limit` results.  (A value of `0` for `limit` means
   that the query should not be limited.)"
  ([limit sql-and-params]
    (limited-query-resource-events limit {} sql-and-params))
  ([limit query-params [sql & sql-params]]
    {:pre  [(and (integer? limit) (>= limit 0))]
     :post [(or (zero? limit) (<= (count %) limit))]}
    (let [limited-sql (add-limit-clause limit sql)
  ;        results       (limited-query-to-vec limit (apply vector limited-query
  ; params))]
         ;; TODO: it probably doesn't make sense to be doing both `add-limit-clause`
         ;;  *and* `paged-sorted-query`; should reconcile, but just doing this in
         ;;  the interest of time for the prototype.
         { total-count  :total-count
           results      :results } (paged-sorted-query-to-vec (apply vector limited-sql sql-params) query-params)
          ;; TODO: this clause lives in `limited-query-to-vec`; adding it here
          ;;  for now to get tests passing.
          limited-results (limit-result-set! limit results)]
;      (println "IN LQRE, total count:" total-count)
      {:total-count total-count
       :results 
         (map
           #(-> (utils/mapkeys underscores->dashes %)
              (update-in [:old-value] json/parse-string)
              (update-in [:new-value] json/parse-string))
           results)})))

(defn query-resource-events
  "Take a query and its parameters, and return a vector of matching resource
  events."
  ([sql-and-params]
    (query-resource-events {} sql-and-params))
  ([query-params [sql & params]]
    {:pre [(string? sql)]}
    (limited-query-resource-events 0 query-params (apply vector sql params))))

(defn events-for-report-hash
  "Given a particular report hash, this function returns all events for that
   given hash."
  [report-hash]
  {:pre [(string? report-hash)]
   :post [(vector? %)]}
  (let [query ["=" "report" report-hash]]
    (vec
      (-> query
        (query->sql)
        (query-resource-events)
        (:results)))))

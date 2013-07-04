;; TODO: docs

(ns com.puppetlabs.puppetdb.query.node-run-summary
  (:use [com.puppetlabs.jdbc :only [query-to-vec
                                    valid-jdbc-query?]]))

(defn query->sql
  ;; TODO docs
  [node num-runs]
  {:pre  [(string? node)
          (pos? num-runs)]
   :post [(valid-jdbc-query? %)]}

  ;; TODO: use sql params to help prevent injection attacks
  [(format
    (str "SELECT reports.start_time, resource_events.report,
            SUM(CASE WHEN resource_events.status = 'failure' THEN 1 ELSE 0 END) AS failures,
            SUM(CASE WHEN resource_events.status = 'success' THEN 1 ELSE 0 END) AS successes,
            SUM(CASE WHEN resource_events.status = 'noop' THEN 1 ELSE 0 END) AS noops,
            SUM(CASE WHEN resource_events.status = 'skipped' THEN 1 ELSE 0 END) AS skips
          FROM resource_events
	        INNER JOIN reports ON reports.hash = resource_events.report
          WHERE resource_events.report IN
            (SELECT hash FROM reports
	            WHERE certname = '%s'
	            ORDER BY start_time DESC LIMIT %s)
          GROUP BY reports.start_time, resource_events.report
          ORDER BY start_time DESC")
    node
    num-runs)])

(defn query-node-run-summaries
  ;; TODO docs
  [[sql & params :as sql-and-params]]
  {:pre [(string? sql)]}
  (query-to-vec sql-and-params))
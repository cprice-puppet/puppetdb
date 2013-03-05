(ns com.puppetlabs.test.concurrent
  (:use [com.puppetlabs.concurrent]
        [com.puppetlabs.testutils.logging]
        [clojure.test]
        [com.puppetlabs.utils :only [swap-and-return-old-val!]])
  (:require [clojure.tools.logging :as log])
  (:import [java.util.concurrent Semaphore]))

(defn- create-futures
  "Helper function to create a sequence of `n` futures that simply apply function
  `f` to `args`."
  [n f & args]
  (doall (for [i (range n)] (future (apply f args)))))

(defn- increment-count
  "Test helper function: increments the `:current` counter, and if the new value
  exceeds the `:max` counter, update the `:max` counter to the new max value."
  [counts]
  {:pre [(map? counts)
         (contains? counts :current)
         (contains? counts :max)]}
  (let [updated (update-in counts [:current] inc)]
    (assoc updated :max (max (:max updated) (:current updated)))))

(defn- decrement-count
  "Test helper function: decrements the `:current` counter."
  [counts]
  {:pre [(map? counts)
         (contains? counts :current)
         (contains? counts :max)]}
  (update-in counts [:current] dec))

(defn- update-counts
  "Test helper function: calls `swap!` on the `counts` atom to increment
  the counters, sleeps for a very short period to make sure other threads have
  a change to run, and then calls `swap!` again to decrement the counters."
  [counts]
  {:pre [(instance? clojure.lang.Atom counts)]}
  (swap! counts increment-count)
  (Thread/sleep 5)
  (swap! counts decrement-count))

(defn- update-counts-and-inc
  "Test helper function: calls `update-counts` to exercise the counter/semaphore
  code, then simply calls `inc` on `item` (to allow testing results of the `map`
  operation."
  [counts item]
  {:pre [(instance? clojure.lang.Atom counts)
         (number? item)]}
  (update-counts counts)
  (inc item))


(deftest test-bound-via-semaphore
  (doseq [bound [1 2 5]]
    (testing (format "Testing bound-via-semaphore with semaphore size %s" bound)
      (let [sem     (Semaphore. bound)
            counts  (atom {:current 0 :max 0})
            futures (create-futures 10
                      (bound-via-semaphore sem update-counts) counts)]
      (doseq [fut futures]
        ;; deref all of the futures to make sure we've waited for them to complete
        @fut)
      (is (= {:current 0 :max bound} @counts))))))

(deftest test-bounded-pmap
  (doseq [bound [1 2 5]]
    (testing (format "Testing bounded-pmap with bound %s" bound)
      (let [counts  (atom {:current 0 :max 0})
            results (doall (bounded-pmap bound (partial update-counts-and-inc counts) (range 20)))]
        (is (= {:current 0 :max bound} @counts))
        (is (= (set (map inc (range 20))) (set results)))))))

(defn simple-work-stack
  ;; TODO docs
  [size]
  {:pre  [(pos? size)]}
  (let [original-work   (range size)
        remaining-work  (atom (range size))
        counter         (atom 0)
        iterator-fn     (fn []
      (swap! counter inc)
      (let [old-work  (swap-and-return-old-val! remaining-work next)
            next-item (first old-work)]
        next-item))]
    {:original-work   original-work
     :remaining-work  remaining-work
     :counter         counter
     :iterator-fn     iterator-fn}))

(deftest test-work-queue->seq
  (let [queue (work-queue)]
    (doseq [i (range 5)]
      (.put queue i))
    (.put queue work-queue-sentinel-complete)
    (let [queue-seq  (work-queue->seq queue)]
      (is (= (range 5) queue-seq)))))

(deftest test-producer
  (doseq [num-workers [1 2 5]
          max-work    [0 2 10]]
    (testing (format "with %d worker(s) and %d max work items" num-workers max-work)
      (let [num-work-items                5
            {:keys [counter iterator-fn
                    original-work]}       (simple-work-stack num-work-items)
            p                             (producer iterator-fn num-workers max-work)
            {:keys [workers work-queue]}  p
            queued-work                   (work-queue->seq work-queue)]
        (testing "number of workers matches what we requested"
          (is (= num-workers (count workers))))
        (testing "work queue contains the correct work items"
          (is (= (set original-work) (set queued-work))))
        (testing "workers completed the correct number of work items"
          (let [work-completed (apply + (map deref workers))]
            (is (= num-work-items work-completed))))))))

(deftest test-consumer
  (doseq [num-workers [1 2 5]
          max-work    [0 2 10]]
    (testing (format "with %d worker(s) and %d max work items" num-workers max-work)
      (let [num-work-items                    5
            {:keys [counter iterator-fn
                    original-work]}           (simple-work-stack num-work-items)
            p                                 (producer iterator-fn num-workers max-work)
            {:keys [workers result-queue]}    (consumer p inc num-workers max-work)
            queued-results                    (work-queue->seq result-queue)]
        (testing "number of workers matches what we requested"
          (is (= num-workers (count workers))))
        (testing "result queue contains the correct results"
          (is (= (set (map inc original-work)) (set queued-results))))
        (testing "workers completed the correct number of work items"
          (let [work-completed (apply + (map deref workers))]
            (is (= num-work-items work-completed))))))))

(deftest test-fail-cases
  (testing "it should fail if consumer work-fn returns nil"
    (with-log-output logs
      (let [producer-fn (constantly 1)
            work-fn     (fn [work] nil)
            p           (producer producer-fn 5)
            c           (consumer p work-fn 5)
            results     (work-queue->seq (:result-queue c))]
        (is (thrown? IllegalStateException (doseq [result results] nil)))
        (is (= 1 (count (logs-matching #"Something horrible happened!" @logs)))))))
  (testing "it should fail if consumer work-fn throws an exception"
    (with-log-output logs
      (let [producer-fn (constantly 1)
            work-fn     (fn [work] (throw (IllegalArgumentException. "consumer exception!")))
            p           (producer producer-fn 5)
            c           (consumer p work-fn 5)
            results     (work-queue->seq (:result-queue c))]
        (is (thrown? IllegalArgumentException (doseq [result results] nil)))
        (is (= 1 (count (logs-matching #"Something horrible happened!" @logs)))))))
  (testing "it should fail if producer work-fn throws an exception"
    (with-log-output logs
      (let [producer-fn (fn [] (throw (IllegalArgumentException. "producer exception!")))
            work-fn     (fn [work] 1)
            p           (producer producer-fn 5)
            c           (consumer p work-fn 5)
            results     (work-queue->seq (:result-queue c))]
        (is (thrown? IllegalArgumentException (doseq [result results] nil)))
        (is (= 2 (count (logs-matching #"Something horrible happened!" @logs))))))))

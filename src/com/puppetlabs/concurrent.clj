;; ## Concurrency-related Utility Functions
;;
;; This namespace contains some utility functions for multi-thread operations.
;; In most cases these will simply be thin wrappers around clojure concurrency
;; functions and/or structures from `java.util.concurrent`.

(ns com.puppetlabs.concurrent
  (:import  [java.util.concurrent Semaphore LinkedBlockingQueue
              ArrayBlockingQueue ExecutionException])
  (:use     [com.puppetlabs.utils :only [iterator-fn->lazy-seq]])
  (:require [clojure.tools.logging :as log]))

(defn bound-via-semaphore
  "Given a semaphore `sem` function `f`, return a new function which simply
  acquires the semaphore, executes `f`, and then releases the semaphore.  This
  is mostly intended to be a helper function for use by `bounded-pmap`."
  [sem f]
  {:pre [(instance? Semaphore sem)
         (ifn? f)]
   :post [(ifn? %)]}
  (fn [& args]
    (.acquire sem)
    (try
      (apply f args)
      (finally (.release sem)))))

(defn bounded-pmap
  "Similar to clojure's built-in `pmap`, but prevents concurrent evaluation of
  more than `max-threads` number of items in the resulting sequence."
  [max-threads f coll]
  {:pre [(integer? max-threads)
         (ifn? f)
         (coll? coll)]}
  (let [sem         (Semaphore. max-threads)
        bounded-fn  (bound-via-semaphore sem f)]
    (pmap bounded-fn coll)))

;; TODO docs
(def work-queue-sentinel-complete (Object.))

(defn- build-worker
  ;; TODO docs
  [work-seq-fn enqueue-fn]
  (future
    (let [work-count (atom 0)]
      (doseq [work (work-seq-fn)]
        (enqueue-fn work)
        (swap! work-count inc))
      @work-count)))

(gen-interface
  :name    com.puppetlabs.concurrent.MinimalBlockingQueue
  :methods [[take []       Object]
            [put  [Object] void]])

(deftype WorkQueue [queue]
  com.puppetlabs.concurrent.MinimalBlockingQueue
  (put [this item]
    (if (nil? item)
      (throw (IllegalArgumentException. "Error!  Queues do not support `nil`.")))
    (.put queue item))
  (take [this]
    (.take queue)))

(defrecord Producer     [workers work-queue])
(defrecord Consumer     [workers result-queue])

(defn work-queue
  ;; TODO docs
  ([] (work-queue 0))
  ([size]
    {:pre  [(>= size 0)]
     :post [(instance? WorkQueue %)]}
    (if (= 0 size)
      (WorkQueue. (LinkedBlockingQueue.))
      (WorkQueue. (ArrayBlockingQueue. size)))))


(defn work-queue->seq
  ;; TODO docs
  [queue]
  {:pre  [(instance? WorkQueue queue)]
   :post [(seq? %)]}
  (iterator-fn->lazy-seq
    (fn []
      (let [next-item (.take queue)]
        ;; TODO: docs/cleanup
        (if (instance? Throwable next-item)
          (throw next-item))
        (if (= work-queue-sentinel-complete next-item)
          (do
            ;; TODO: doc
            (.put queue work-queue-sentinel-complete)
            nil)
          next-item)))))

(defn producer
  ;; TODO: docs preconds
  ([work-fn num-workers]
    (producer work-fn num-workers 0))
  ([work-fn num-workers max-work]
    {:pre  [(fn? work-fn)
            (pos? num-workers)
            (>= max-work 0)]
     :post [(instance? Producer %)
            (instance? WorkQueue (:work-queue %))
            (every? future? (:workers %))]}
    (let [queue       (work-queue max-work)
          workers     (doall (for [_ (range num-workers)]
                               (build-worker
                                 #(iterator-fn->lazy-seq work-fn)
                                 #(.put queue %))))
          supervisor  (future
                        ;; TODO: doc
                        (try
                          (doseq [worker workers] @worker)
                          (catch Throwable th
                            (let [ex (if (instance? ExecutionException th)
                                        (.getCause th)
                                        th)]
                              (log/error ex "Something horrible happened!"
                                "Shutting down producers.")
                              (mapv future-cancel workers)
                              (.put queue ex))))
                        (.put queue work-queue-sentinel-complete))]
      (Producer. workers queue))))

(defn- validate-consumer-work
  [result]
  (if (nil? result)
    (throw (IllegalStateException. "Consumer work function must not return `nil`.")))
  result)

(defn consumer
  ;; TODO: docs preconds
  ([producer work-fn num-workers] (consumer producer work-fn num-workers 0))
  ([{producer-queue :work-queue :as producer} work-fn num-workers max-results]
    {:pre  [(instance? Producer producer)
            (instance? WorkQueue producer-queue)]
     :post [(instance? Consumer %)
            (instance? WorkQueue (:result-queue %))
            (every? future? (:workers %))]}
    (let [result-queue   (work-queue max-results)
          workers        (doall (for [_ (range num-workers)]
                                  (build-worker
                                    #(work-queue->seq producer-queue)
                                    #(.put result-queue (validate-consumer-work (work-fn %))))))
          supervisor     (future
                            ;; TODO: doc
                            (try
                              (doseq [worker workers] @worker)
                              (catch Throwable th
                                (let [ex (if (instance? ExecutionException th)
                                            (.getCause th)
                                            th)]
                                  (log/error ex "Something horrible happened!"
                                    "Shutting down consumers.")
                                  (mapv future-cancel workers)
                                  (.put result-queue ex))))
                            (.put result-queue work-queue-sentinel-complete))]
      (Consumer. workers result-queue))))

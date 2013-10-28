(ns trapperkeeper.config.config-service
  (:require [trapperkeeper.config.config-core :as core])
  (:use [plumbing.core :only [fnk]]))

(defn config-service
  [path initial-config]
  {:pre [(string? path)
         (map? initial-config)]}
  {:config-service
   (fnk ^{:output-schema
          {:config  true}}
    []
    (let [config (merge
                    initial-config
                    (core/load-config! path))]
        {:config (fn
                   ([] config)
                   ([k] (config k)))}))})
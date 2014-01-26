(ns com.puppetlabs.puppetdb.repl-reload-bug)

(defn show-bug
  []
  (println "Doing initial require.")
  (require 'com.puppetlabs.time)
  (println "com.puppetlabs.time publics: " (ns-publics 'com.puppetlabs.time))
  (require 'clojure.tools.namespace.reload)
  (println "removing lib")
  (clojure.tools.namespace.reload/remove-lib 'com.puppetlabs.time)
  (try (ns-publics 'com.puppetlabs.time)
       (catch Exception e (println "Caught expected exception trying ns-publics")))
  (println "Reloading")
  (require 'com.puppetlabs.time :reload :verbose)
  (println "Reloaded.")
  (println "com.puppetlabs.time publics: " (ns-publics 'com.puppetlabs.time)))

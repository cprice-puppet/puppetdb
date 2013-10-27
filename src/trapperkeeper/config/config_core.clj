(ns trapperkeeper.config.config-core
  (:import [org.ini4j Ini])
  (:require [clojure.string :as string]
            [fs.core :as fs])
  (:use [clojure.java.io :only (reader file)]
        [com.puppetlabs.utils :only (parse-int)]))

(defn- ini-to-map
  "Takes a .ini filename and returns a nested map of
  fully-interpolated values. Strings that look like integers are
  returned as integers, and all section names and keys are returned as
  symbols."
  [filename]
  {:pre  [(or (string? filename)
            (instance? java.io.File filename))]
   :post [(map? %)
          (every? keyword? (keys %))
          (every? map? (vals %))]}
  (let [ini        (Ini. (reader filename))
        m          (atom {})
        keywordize #(keyword (string/lower-case %))]

    (doseq [[name section] ini
            [key _] section
            :let [val (.fetch section key)
                  val (or (parse-int val) val)]]
      (swap! m assoc-in [(keywordize name) (keywordize key)] val))
    @m))

(defn- inis-to-map
  "Takes a path and converts the pointed-at .ini files into a nested
  map (see `ini-to-map` for details). If `path` is a file, the
  behavior is exactly the same as `ini-to-map`. If `path` is a
  directory, we return a merged version of parsing all the .ini files
  in the directory (we do not do a recursive find of .ini files)."
  ([path]
    (inis-to-map path "*.ini"))
  ([path glob-pattern]
    {:pre  [(or (string? path)
              (instance? java.io.File path))]
     :post [(map? %)]}
    (let [files (if-not (fs/directory? path)
                  [path]
                  (fs/glob (fs/file path glob-pattern)))]
      (->> files
        (sort)
        (map fs/absolute-path)
        (map ini-to-map)
        (apply merge)
        (merge {})))))

(defn load-config!
  "Parses the given config file/directory and configures its various
  subcomponents."
  [path]
  {:pre [(string? path)]}
  (let [file (file path)]
    (when-not (.canRead file)
      (throw (IllegalArgumentException.
               (format "Configuration path '%s' must exist and must be readable." path)))))

  (inis-to-map path))

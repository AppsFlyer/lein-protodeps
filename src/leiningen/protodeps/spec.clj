(ns leiningen.protodeps.spec
  "Specs for the :lein-protodeps configuration."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]))

(s/def ::non-blank-string (s/and string? (complement str/blank?)))

(defn- path-segment?
  "True when s names a single directory entry, so it cannot escape the install directory
   or be read as an option by tar."
  [s]
  (not (or (= "." s)
           (str/starts-with? s "-")
           (str/includes? s "/")
           (str/includes? s "\\")
           (str/includes? s ".."))))

(s/def ::path-segment (s/and ::non-blank-string path-segment?))
(s/def :leiningen.protodeps.plugin/name ::path-segment)
(s/def :leiningen.protodeps.plugin/version ::path-segment)
(s/def :leiningen.protodeps.plugin/url-template ::non-blank-string)
(s/def :leiningen.protodeps.plugin/output-directive (s/and string? #(re-matches #".+_out" %)))
(s/def :leiningen.protodeps.plugin/options ::non-blank-string)
(s/def ::plugin (s/keys :req-un [:leiningen.protodeps.plugin/name
                                 :leiningen.protodeps.plugin/version
                                 :leiningen.protodeps.plugin/url-template
                                 :leiningen.protodeps.plugin/output-directive]
                        :opt-un [:leiningen.protodeps.plugin/options]))

(defn- distinct-output-directives? [plugins]
  (= (count plugins)
     (count (distinct (map :output-directive plugins)))))

(s/def ::plugins (s/and (s/coll-of ::plugin :kind sequential?)
                        distinct-output-directives?))

(defn validate-plugins!
  "Throw a descriptive error when the :plugins config does not conform to ::plugins.
   Returns the plugins otherwise."
  [plugins]
  (when-not (s/valid? ::plugins plugins)
    (throw (ex-info (str "Invalid :plugins configuration:\n" (s/explain-str ::plugins plugins))
                    {:plugins plugins})))
  plugins)

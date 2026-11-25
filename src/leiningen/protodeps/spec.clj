(ns leiningen.protodeps.spec
  "Specs for the :lein-protodeps configuration."
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as strings]))

(s/def ::non-blank-string (s/and string? (complement strings/blank?)))
(s/def ::option-key (some-fn keyword? string? symbol?))
(s/def :leiningen.protodeps.plugin/name ::non-blank-string)
(s/def :leiningen.protodeps.plugin/version ::non-blank-string)
(s/def :leiningen.protodeps.plugin/url-template ::non-blank-string)
(s/def :leiningen.protodeps.plugin/output-directive (s/and string? #(re-matches #".+_out" %)))
(s/def :leiningen.protodeps.plugin/options (s/nilable (s/or :map (s/map-of ::option-key any?)
                                                            :string ::non-blank-string)))
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

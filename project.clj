(defproject lein-protodeps "0.1.8"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Apache License, Version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :eval-in-leiningen true
  :deploy-repositories [["releases"  {:url           "***REMOVED***/" :username :***REMOVED***
                                      :password      :***REMOVED***
                                      :sign-releases false}]
                        ["snapshots"  {:url           "***REMOVED***" :username :***REMOVED***
                                       :password      :***REMOVED***
                                       :sign-releases false}]]
  :repositories [["jitpack.io" "https://jitpack.io"]]
  :dependencies [[clj-jgit "1.0.0-beta3"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]]}})

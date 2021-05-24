(defproject lein-protodeps "1.0.1"
  :description "FIXME: write description"
  :url "***REMOVED***"
  :license {:name "Apache License, Version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :eval-in-leiningen true
  :deploy-repositories [["releases"  {:url           "***REMOVED***/"
                                      :username      :***REMOVED***
                                      :password      :***REMOVED***
                                      :sign-releases false}]
                        ["snapshots"  {:url           "***REMOVED***"
                                       :username      :***REMOVED***
                                       :password      :***REMOVED***
                                       :sign-releases false}]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]]}})

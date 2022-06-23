(defproject com.appsflyer/lein-protodeps "1.0.4"
  :description "Leiningen plugin for consuming and compiling protobuf schemas"
  :url "https://github.com/AppsFlyer/lein-protodeps"
  :license {:name "Apache License, Version 2.0"
            :url  "http://www.apache.org/licenses/LICENSE-2.0.html"}
  :eval-in-leiningen true
  :deploy-repositories [["releases" {:url "https://repo.clojars.org"
                                     :sign-releases false
                                     :username :env/clojars_username
                                     :password :env/clojars_password}]
                        ["snapshots" {:url "https://repo.clojars.org"
                                      :username :env/clojars_username
                                      :password :env/clojars_password}]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]]}})

(ns leiningen.protodeps.spec-test
  (:require [clojure.test :refer [are deftest is testing]]
            [leiningen.protodeps.spec :as sut])
  (:import (clojure.lang ExceptionInfo)))

(deftest validate-plugins-test
  (let [valid {:name             "protoc-gen-validate"
               :version          "1.3.0"
               :url-template     "https://example.com/${:version}"
               :output-directive "validate_out"
               :options          {:lang "java"}}]
    (testing "Complete plugin configs are accepted"
      (is (= [valid] (sut/validate-plugins! [valid])))
      (is (= [] (sut/validate-plugins! []))))
    (testing "Incomplete or malformed plugin configs are rejected"
      (are [plugins] (thrown-with-msg? ExceptionInfo #"Invalid :plugins configuration"
                                       (sut/validate-plugins! plugins))
                     [(dissoc valid :url-template)]
                     [(dissoc valid :output-directive)]
                     [(dissoc valid :version)]
                     [(dissoc valid :name)]
                     [(assoc valid :options "lang=java")]
                     [(assoc valid :output-directive "validate")]
                     [valid (assoc valid :name "protoc-gen-validate-copy")]
                     valid))))

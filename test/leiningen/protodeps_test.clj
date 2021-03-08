(ns leiningen.protodeps-test
  (:require [leiningen.protodeps :as sut]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Path]
           [java.io File]))

(deftest integration-test
  (let [^Path tmp-dir (sut/create-temp-dir!)]
    (try
      (let [config {:output-path (str tmp-dir)
                    :proto-version "3.11.3"
                    :repos '{:test {:repo-type :filesystem
                                    :config {:path "resources/proto_repo"}
                                    :proto-paths ["protos"]
                                    :dependencies [[protos/dir1]]}}}]
        (sut/generate-files! {} config)
        (is (= #{"dir1/v1/File1.java" "dir2/v1/File2.java"} 
               (->> (.toFile tmp-dir)
                    file-seq
                    (filter #(not (.isDirectory ^File %)))
                    (map #(.relativize tmp-dir (.toPath ^File %)))
                    (map str)
                    set))))
      (finally
        (sut/cleanup-dir! tmp-dir)))))


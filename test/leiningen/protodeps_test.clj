(ns leiningen.protodeps-test
  (:require [leiningen.protodeps :as sut]
            [clojure.test :refer [deftest is]])
  (:import [java.nio.file Path]
           [java.io File]))

(defn- run-test! [test]
  (let [^Path tmp-dir (sut/create-temp-dir!)]
    (try
      (test tmp-dir)
      (finally
        (sut/cleanup-dir! tmp-dir)))))

(deftest integration-test
  (run-test!
   (fn [tmp-dir]
     (let [config {:output-path (str tmp-dir)
                   :proto-version "3.11.3"
                   :repos '{:repo1 {:repo-type :filesystem
                                    :config {:path "./resources/test/proto_repo"}
                                    :proto-paths ["protos"]
                                    :dependencies [protos]}
                            ;; external dependency repo, no direct schemas to compile
                            :repo2 {:repo-type :filesystem
                                    :config {:path "./resources/test/proto_repo2"}
                                    :proto-paths ["protos"]}}}]
       (sut/generate-files! {} config)
       (is (= #{"dir1/v1/File1.java" "dir2/v1/File2.java" "dir3/v1/File3.java" "dir4/v1/File4.java"}
              (->> (.toFile tmp-dir)
                   file-seq
                   (filter #(not (.isDirectory ^File %)))
                   (map #(.relativize tmp-dir (.toPath ^File %)))
                   (map str)
                   set))))))
  (run-test!
   (fn [tmp-dir]
     (let [config {:output-path (str tmp-dir)
                   :proto-version "3.11.3"
                   :repos '{:repo1 {:repo-type :filesystem
                                    :config {:path "./resources/test/proto_repo"}
                                    :proto-paths ["protos"]
                                    :dependencies [protos/dir1]}
                            ;; external dependency repo, no direct schemas to compile
                            :repo2 {:repo-type :filesystem
                                    :config {:path "./resources/test/proto_repo2"}
                                    :proto-paths ["protos"]}}}]
       (sut/generate-files! {} config)
       (is (= #{"dir1/v1/File1.java" "dir2/v1/File2.java" "dir3/v1/File3.java"}
              (->> (.toFile tmp-dir)
                   file-seq
                   (filter #(not (.isDirectory ^File %)))
                   (map #(.relativize tmp-dir (.toPath ^File %)))
                   (map str)
                   set))))))
  (run-test!
   (fn [tmp-dir]
     (let [config {:output-path (str tmp-dir)
                   :proto-version "3.11.3"
                   :repos '{:repo1 {:repo-type :filesystem
                                    :config {:path "./resources/test/proto_repo"}
                                    :proto-paths ["protos"]}
                            :repo2 {:repo-type :filesystem
                                    :config {:path "./resources/test/proto_repo2"}
                                    :proto-paths ["protos"]
                                    :dependencies [protos/dir3]}}}]
       (sut/generate-files! {} config)
       (is (= #{"dir3/v1/File3.java"}
              (->> (.toFile tmp-dir)
                   file-seq
                   (filter #(not (.isDirectory ^File %)))
                   (map #(.relativize tmp-dir (.toPath ^File %)))
                   (map str)
                   set)))))))

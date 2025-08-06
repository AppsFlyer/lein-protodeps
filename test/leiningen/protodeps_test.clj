(ns leiningen.protodeps-test
  (:require [leiningen.protodeps :as sut]
            [clojure.test :refer [deftest is testing]])
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

(deftest aarch64-architecture-mapping-test
  (testing "Test that aarch64 architecture is correctly mapped to aarch_64 for protoc download URLs"
    (is (= "aarch_64" (get sut/os-arch->arch "aarch64"))
        "aarch64 should map to aarch_64 for correct protoc binary download URL")))

(deftest aarch64-url-generation-test
  (testing "Test that protoc download URL is correctly generated for aarch64 architecture"
    (let [platform {:os-name "linux" :os-arch "aarch_64" :semver "24.3"}
          url-template "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip"
          expected-url "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch_64.zip"]
      (is (= expected-url (@#'sut/interpolate platform url-template))
          "URL should be correctly generated with aarch_64 architecture name"))))

(deftest aarch64-issue-8-fix-test
  (testing "Test that the fix for GitHub issue #8 works correctly"
    (let [platform {:os-name "linux" :os-arch "aarch_64" :semver "24.3"}
          url-template "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip"
          generated-url (@#'sut/interpolate platform url-template)
          ;; The issue mentioned the correct URL should have aarch_64 (with underscore)
          correct-url "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch_64.zip"
          ;; The issue mentioned the incorrect URL was aarch64 (without underscore)
          incorrect-url "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch64.zip"]
      (is (= correct-url generated-url)
          "Generated URL should match the correct format with aarch_64")
      (is (not= incorrect-url generated-url)
          "Generated URL should NOT match the incorrect format with aarch64"))))

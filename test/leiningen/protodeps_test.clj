(ns leiningen.protodeps-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [leiningen.protodeps :as sut])
  (:import (clojure.lang ExceptionInfo)
           (java.io File)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(defn- run-test! [test]
  (let [tmp-dir (sut/create-temp-dir!)]
    (try
      (test tmp-dir)
      (finally
        (sut/cleanup-dir! tmp-dir)))))

(deftest integration-test
  (run-test!
    (fn [^Path tmp-dir]
      (let [config {:output-path   (str tmp-dir)
                    :proto-version "3.11.3"
                    :repos         '{:repo1 {:repo-type    :filesystem
                                             :config       {:path "./resources/test/proto_repo"}
                                             :proto-paths  ["protos"]
                                             :dependencies [protos]}
                                     ;; external dependency repo, no direct schemas to compile
                                     :repo2 {:repo-type   :filesystem
                                             :config      {:path "./resources/test/proto_repo2"}
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
    (fn [^Path tmp-dir]
      (let [config {:output-path   (str tmp-dir)
                    :proto-version "3.11.3"
                    :repos         '{:repo1 {:repo-type    :filesystem
                                             :config       {:path "./resources/test/proto_repo"}
                                             :proto-paths  ["protos"]
                                             :dependencies [protos/dir1]}
                                     ;; external dependency repo, no direct schemas to compile
                                     :repo2 {:repo-type   :filesystem
                                             :config      {:path "./resources/test/proto_repo2"}
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
    (fn [^Path tmp-dir]
      (let [config {:output-path   (str tmp-dir)
                    :proto-version "3.11.3"
                    :repos         '{:repo1 {:repo-type   :filesystem
                                             :config      {:path "./resources/test/proto_repo"}
                                             :proto-paths ["protos"]}
                                     :repo2 {:repo-type    :filesystem
                                             :config       {:path "./resources/test/proto_repo2"}
                                             :proto-paths  ["protos"]
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
    (let [platform     {:os-name "linux"
                        :os-arch "aarch_64"
                        :semver  "24.3"}
          url-template "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip"
          expected-url "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch_64.zip"]
      (is (= expected-url (@#'sut/interpolate platform url-template))
          "URL should be correctly generated with aarch_64 architecture name"))))

(deftest aarch64-issue-8-fix-test
  (testing "Test that the fix for GitHub issue #8 works correctly"
    (let [platform      {:os-name "linux"
                         :os-arch "aarch_64"
                         :semver  "24.3"}
          url-template  "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip"
          generated-url (@#'sut/interpolate platform url-template)
          ;; The issue mentioned the correct URL should have aarch_64 (with underscore)
          correct-url   "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch_64.zip"
          ;; The issue mentioned the incorrect URL was aarch64 (without underscore)
          incorrect-url "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch64.zip"]
      (is (= correct-url generated-url)
          "Generated URL should match the correct format with aarch_64")
      (is (not= incorrect-url generated-url)
          "Generated URL should NOT match the incorrect format with aarch64"))))

(deftest cleanup-dir-test
  (testing "cleanup-dir! deletes a symlink in the directory, not its target"
    (let [outside (sut/create-temp-dir!)
          dir     (sut/create-temp-dir!)
          kept    (io/file (str outside) "kept")]
      (try
        (spit kept "kept")
        (Files/createSymbolicLink (.resolve dir "link") outside (make-array FileAttribute 0))
        (sut/cleanup-dir! dir)
        (is (not (.exists (.toFile dir))))
        (is (.exists kept))
        (finally
          (sut/cleanup-dir! outside))))))

(deftest cleanup-dir-best-effort-test
  (testing "cleanup-dir! warns instead of throwing when part of the directory cannot be deleted"
    (let [dir       (sut/create-temp-dir!)
          read-only (io/file (str dir) "read-only")
          siblings  (mapv #(io/file (str dir) (str "sibling-" %)) (range 5))]
      (try
        (.mkdir read-only)
        (spit (io/file read-only "file") "file")
        (.setWritable read-only false)
        (run! #(spit % "sibling") siblings)
        (let [err (with-out-str
                    (binding [*err* *out*]
                      (sut/cleanup-dir! dir)))]
          (is (str/includes? err "WARNING: Failed to delete"))
          (is (.exists read-only))
          (is (not-any? #(.exists ^File %) siblings)))
        (finally
          (.setWritable read-only true)
          (sut/cleanup-dir! dir))))))

(deftest generate-files-cleanup-on-setup-failure-test
  (testing "generate-files! deletes its temp dir when setup throws"
    (let [created                   (atom nil)
          original-create-temp-dir! sut/create-temp-dir!]
      (with-redefs [sut/create-temp-dir! (fn [& args]
                                           (reset! created (apply original-create-temp-dir! args)))
                    sut/get-platform     (fn [_]
                                           (throw (ex-info "unsupported platform" {})))]
        (is (thrown? ExceptionInfo (sut/generate-files! {} {}))))
      (is (some? @created))
      (is (not (.exists (.toFile ^Path @created)))))))

(deftest protoc-url-template-version-test
  (testing "Protoc URL templates can use ${:version}"
    (let [proto-version (merge {:os-name "linux"
                                :os-arch "x86_64"}
                               (@#'sut/parse-semver "3.25.5"))]
      (is (= "https://mirror.example.com/3.25.5/protoc-3.25.5-linux-x86_64.zip"
             (@#'sut/interpolate proto-version
               "https://mirror.example.com/${:version}/protoc-${:version}-${:os-name}-${:os-arch}.zip"))))))

(deftest grpc-url-template-version-test
  (let [grpc-version (merge {:os-name "linux"
                             :os-arch "x86_64"}
                            (@#'sut/parse-semver "1.68.1"))]
    (testing "The default gRPC URL template fills in ${:version}"
      (is (= "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/1.68.1/protoc-gen-grpc-java-1.68.1-linux-x86_64.exe"
             (@#'sut/interpolate grpc-version sut/grpc-release-tpl))))
    (testing "gRPC URL templates can use ${:version}"
      (is (= "https://mirror.example.com/1.68.1/protoc-gen-grpc-java-1.68.1-linux-x86_64.exe"
             (@#'sut/interpolate grpc-version
               "https://mirror.example.com/${:version}/protoc-gen-grpc-java-${:version}-${:os-name}-${:os-arch}.exe"))))))

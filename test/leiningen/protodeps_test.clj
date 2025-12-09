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
  (testing "Test that aarch64 architecture is correctly mapped with multiple variants"
    (let [variants (get sut/os-arch->arch "aarch64")]
      (is (= "aarch_64" (first variants))
          "aarch64 should map to aarch_64 as first variant (protoc style)")
      (is (some #(= "arm64" %) variants)
          "aarch64 should include arm64 as a variant (Go style)"))))

(deftest platform-variants-test
  (testing "Platform provides naming variants and stores all alternatives"
    (let [env (doto (java.util.Properties.)
                (.setProperty "os.name" "Mac OS X")
                (.setProperty "os.arch" "aarch64"))
          platform (@#'sut/get-platform env)]
      (is (= "osx" (:os-name platform)) "Default os-name uses first variant (protoc style)")
      (is (= "aarch_64" (:os-arch platform)) "Default os-arch uses first variant (protoc style)")
      (is (= ["osx" "darwin"] (:os-name-variants platform)) "All OS name variants stored")
      (is (= ["aarch_64" "arm64"] (:os-arch-variants platform)) "All arch variants stored"))))

(deftest aarch64-url-generation-test
  (testing "Test that protoc download URL is correctly generated for aarch64 architecture"
    (let [platform      {:os-name "linux"
                         :os-arch "aarch_64"
                         :semver  "24.3"}
          url-template  "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip"
          expected-url  "https://github.com/protocolbuffers/protobuf/releases/download/v24.3/protoc-24.3-linux-aarch_64.zip"]
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

(deftest plugin-options-formatting-test
  (testing "Format plugin options as key=value pairs"
    (is (= "lang=java" (@#'sut/format-plugin-options {:lang "java"})))
    (is (= "lang=java,paths=source_relative" 
           (@#'sut/format-plugin-options {:lang "java" :paths "source_relative"})))
    (is (nil? (@#'sut/format-plugin-options {})))
    (is (nil? (@#'sut/format-plugin-options nil)))))

(deftest plugin-url-interpolation-test
  (testing "Plugin URL interpolation with platform and version variables"
    (let [platform {:os-name "linux" :os-arch "x86_64" :version "1.0.2"}
          template "https://example.com/releases/v${:version}/plugin-${:version}-${:os-name}-${:os-arch}"]
      (is (= "https://example.com/releases/v1.0.2/plugin-1.0.2-linux-x86_64"
             (@#'sut/interpolate platform template))))))

(deftest merge-legacy-grpc-config-test
  (testing "Merge legacy gRPC config with new plugins"
    (let [grpc-version {:semver "1.30.2" :os-name "linux" :os-arch "x86_64"}]
      (testing "When compile-grpc? is true, add gRPC as a plugin"
        (let [config {:compile-grpc? true :grpc-version "1.30.2"}
              result (@#'sut/merge-legacy-grpc-config config grpc-version)]
          (is (= 1 (count result)))
          (is (= "protoc-gen-grpc-java" (:name (first result))))
          (is (= "1.30.2" (:version (first result))))
          (is (= "grpc-java_out" (:output-directive (first result))))))
      
      (testing "When compile-grpc? is false, return only configured plugins"
        (let [config {:compile-grpc? false
                      :plugins [{:name "protoc-gen-validate"
                                :version "1.0.2"}]}
              result (@#'sut/merge-legacy-grpc-config config grpc-version)]
          (is (= 1 (count result)))
          (is (= "protoc-gen-validate" (:name (first result))))))
      
      (testing "Merge gRPC with additional plugins"
        (let [config {:compile-grpc? true
                      :grpc-version "1.30.2"
                      :plugins [{:name "protoc-gen-validate"
                                :version "1.0.2"
                                :url-template "https://example.com/validate"
                                :output-directive "validate_out"}]}
              result (@#'sut/merge-legacy-grpc-config config grpc-version)]
          (is (= 2 (count result)))
          (is (= "protoc-gen-grpc-java" (:name (first result))))
          (is (= "protoc-gen-validate" (:name (second result))))))
      
      (testing "No plugins when compile-grpc? is false and no plugins configured"
        (let [config {:compile-grpc? false}
              result (@#'sut/merge-legacy-grpc-config config grpc-version)]
          (is (empty? result)))))))

(deftest protoc-opts-with-plugins-test
  (testing "Build protoc command with multiple plugins"
    (let [proto-paths ["/path/to/protos"]
          output-path "/output"
          plugins [{:plugin-path "/plugins/protoc-gen-grpc-java"
                   :output-directive "grpc-java_out"
                   :options nil}
                  {:plugin-path "/plugins/protoc-gen-validate"
                   :output-directive "validate_out"
                   :options {:lang "java"}}]
          proto-file (java.io.File. "/protos/test.proto")
          result (@#'sut/protoc-opts proto-paths output-path plugins proto-file)]
      (is (some #(= "--proto_path=/path/to/protos" %) result))
      (is (some #(= "--java_out=/output" %) result))
      (is (some #(= "--plugin=/plugins/protoc-gen-grpc-java" %) result))
      (is (some #(= "--grpc-java_out=/output" %) result))
      (is (some #(= "--plugin=/plugins/protoc-gen-validate" %) result))
      (is (some #(= "--validate_out=lang=java:/output" %) result))
      (is (some #(= "/protos/test.proto" %) result))))
  
  (testing "Build protoc command without plugins"
    (let [proto-paths ["/path/to/protos"]
          output-path "/output"
          plugins []
          proto-file (java.io.File. "/protos/test.proto")
          result (@#'sut/protoc-opts proto-paths output-path plugins proto-file)]
      (is (some #(= "--proto_path=/path/to/protos" %) result))
      (is (some #(= "--java_out=/output" %) result))
      (is (some #(= "/protos/test.proto" %) result))
      (is (not-any? #(.startsWith ^String % "--plugin=") result))))
  
  (testing "Build protoc command with additional flags"
    (let [proto-paths ["/path/to/protos"]
          output-path "/output"
          plugins [{:plugin-path "/plugins/protoc-gen-doc"
                   :output-directive "doc_out"
                   :options nil
                   :additional-flags {:doc_opt "markdown,docs.md"}}]
          proto-file (java.io.File. "/protos/test.proto")
          result (@#'sut/protoc-opts proto-paths output-path plugins proto-file)]
      (is (some #(= "--plugin=/plugins/protoc-gen-doc" %) result))
      (is (some #(= "--doc_out=/output" %) result))
      (is (some #(= "--doc_opt=markdown,docs.md" %) result)))))

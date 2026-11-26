(ns leiningen.protodeps-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [leiningen.protodeps :as sut]
            [leiningen.protodeps.test-util :as util])
  (:import (clojure.lang ExceptionInfo)
           (java.io File)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Arrays Properties)))

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
  (testing "Test that aarch64 architecture is correctly mapped with multiple variants"
    (let [variants (get sut/os-arch->arch "aarch64")]
      (is (= "aarch_64" (first variants))
          "aarch64 should map to aarch_64 as first variant (protoc style)")
      (is (some #(= "arm64" %) variants)
          "aarch64 should include arm64 as a variant (Go style)"))))

(deftest platform-variants-test
  (testing "Platform provides naming variants and stores all alternatives"
    (let [env      (doto (Properties.)
                     (.setProperty "os.name" "Mac OS X")
                     (.setProperty "os.arch" "aarch64"))
          platform (@#'sut/get-platform env)]
      (is (= "osx" (:os-name platform)) "Default os-name uses first variant (protoc style)")
      (is (= "aarch_64" (:os-arch platform)) "Default os-arch uses first variant (protoc style)")
      (is (= ["osx" "darwin"] (:os-name-variants platform)) "All OS name variants stored")
      (is (= ["aarch_64" "arm64"] (:os-arch-variants platform)) "All arch variants stored"))))

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

(deftest plugin-url-interpolation-test
  (testing "Plugin URL interpolation with platform and version variables"
    (let [platform {:os-name "linux"
                    :os-arch "x86_64"
                    :version "1.0.2"}
          template "https://example.com/releases/v${:version}/plugin-${:version}-${:os-name}-${:os-arch}"]
      (is (= "https://example.com/releases/v1.0.2/plugin-1.0.2-linux-x86_64"
             (@#'sut/interpolate platform template))))))

(deftest merge-legacy-grpc-config-test
  (testing "Merge legacy gRPC config with new plugins"
    (let [grpc-version {:semver  "1.30.2"
                        :os-name "linux"
                        :os-arch "x86_64"}]
      (testing "When compile-grpc? is true, add gRPC as a plugin"
        (let [config {:compile-grpc? true
                      :grpc-version  "1.30.2"}
              result (@#'sut/merge-legacy-grpc-config [] config grpc-version)]
          (is (= 1 (count result)))
          (is (= "protoc-gen-grpc-java" (:name (first result))))
          (is (= "1.30.2" (:version (first result))))
          (is (= "grpc-java_out" (:output-directive (first result))))))

      (testing "When compile-grpc? is false, return only configured plugins"
        (let [config {:compile-grpc? false
                      :plugins       [{:name    "protoc-gen-validate"
                                       :version "1.0.2"}]}
              result (@#'sut/merge-legacy-grpc-config (:plugins config) config grpc-version)]
          (is (= 1 (count result)))
          (is (= "protoc-gen-validate" (:name (first result))))))

      (testing "Merge gRPC with additional plugins"
        (let [config {:compile-grpc? true
                      :grpc-version  "1.30.2"
                      :plugins       [{:name             "protoc-gen-validate"
                                       :version          "1.0.2"
                                       :url-template     "https://example.com/validate"
                                       :output-directive "validate_out"}]}
              result (@#'sut/merge-legacy-grpc-config (:plugins config) config grpc-version)]
          (is (= 2 (count result)))
          (is (= "protoc-gen-grpc-java" (:name (first result))))
          (is (= "protoc-gen-validate" (:name (second result))))))

      (testing "No plugins when compile-grpc? is false and no plugins configured"
        (let [config {:compile-grpc? false}
              result (@#'sut/merge-legacy-grpc-config [] config grpc-version)]
          (is (empty? result))))

      (testing "compile-grpc? without a gRPC version fails"
        (is (thrown-with-msg? ExceptionInfo #":grpc-version is not set"
                              (@#'sut/merge-legacy-grpc-config [] {:compile-grpc? true} nil)))))))

(deftest protoc-opts-with-plugins-test
  (let [proto-paths    ["/path/to/protos"]
        output-path    "/output"
        proto-file     (File. "/protos/test.proto")
        contains-flag? (fn [result flag] (some #(= flag %) result))]

    (testing "Build protoc command with multiple plugins"
      (let [plugins [{:plugin-path      "/plugins/protoc-gen-grpc-java"
                      :output-directive "grpc-java_out"
                      :options          nil}
                     {:plugin-path      "/plugins/protoc-gen-validate"
                      :output-directive "validate_out"
                      :options          "lang=java"}]
            result  (@#'sut/protoc-opts proto-paths output-path plugins proto-file)]
        (are [flag] (contains-flag? result flag)
                    "--proto_path=/path/to/protos"
                    "--java_out=/output"
                    "--plugin=protoc-gen-grpc-java=/plugins/protoc-gen-grpc-java"
                    "--grpc-java_out=/output"
                    "--plugin=protoc-gen-validate=/plugins/protoc-gen-validate"
                    "--validate_out=/output"
                    "--validate_opt=lang=java"
                    "/protos/test.proto")
        (is (not-any? #(.startsWith ^String % "--grpc-java_opt") result))))

    (testing "Build protoc command without plugins"
      (let [plugins []
            result  (@#'sut/protoc-opts proto-paths output-path plugins proto-file)]
        (are [flag] (contains-flag? result flag)
                    "--proto_path=/path/to/protos"
                    "--java_out=/output"
                    "/protos/test.proto")
        (is (not-any? #(.startsWith ^String % "--plugin=") result))))

    (testing "Build protoc command with string options"
      (let [plugins [{:plugin-path      "/plugins/protoc-gen-doc"
                      :output-directive "doc_out"
                      :options          "markdown,docs.md"}]
            result  (@#'sut/protoc-opts proto-paths output-path plugins proto-file)]
        (are [flag] (contains-flag? result flag)
                    "--plugin=protoc-gen-doc=/plugins/protoc-gen-doc"
                    "--doc_out=/output"
                    "--doc_opt=markdown,docs.md")))))

(deftest merge-legacy-grpc-config-precedence-test
  (testing "A grpc-java_out entry in :plugins takes precedence over :compile-grpc?"
    (let [grpc-version {:semver  "1.68.1"
                        :os-name "linux"
                        :os-arch "x86_64"}
          explicit     {:name             "protoc-gen-grpc-java"
                        :version          "1.68.1"
                        :url-template     "https://mirror.example.com/protoc-gen-grpc-java.exe"
                        :output-directive "grpc-java_out"}
          config       {:compile-grpc? true
                        :grpc-version  "1.68.1"
                        :plugins       [explicit]}]
      (is (= [explicit] (@#'sut/merge-legacy-grpc-config (:plugins config) config grpc-version))))))

(deftest generate-plugin-urls-test
  (let [platform {:os-name          "osx"
                  :os-arch          "aarch_64"
                  :os-name-variants ["osx" "darwin"]
                  :os-arch-variants ["aarch_64"]}]
    (testing "Templates can use ${:major}, ${:minor} and ${:patch} for x.y.z versions"
      (is (= ["https://mirror.example.com/1.68.1/grpc-1.68-osx-aarch_64.exe"
              "https://mirror.example.com/1.68.1/grpc-1.68-darwin-aarch_64.exe"]
             (@#'sut/generate-plugin-urls
               "https://mirror.example.com/${:major}.${:minor}.${:patch}/grpc-${:major}.${:minor}-${:os-name}-${:os-arch}.exe"
               platform
               "1.68.1"))))
    (testing "Other version formats only fill ${:version}"
      (is (= ["https://example.com/v2024.1-rc1/osx"
              "https://example.com/v2024.1-rc1/darwin"]
             (@#'sut/generate-plugin-urls "https://example.com/v${:version}/${:os-name}" platform "2024.1-rc1"))))
    (testing "A template without platform variables yields a single URL"
      (is (= ["https://example.com/1.68.1/grpc.exe"]
             (@#'sut/generate-plugin-urls "https://example.com/${:version}/grpc.exe" platform "1.68.1"))))))

(deftest get-plugin-test
  (run-test!
    (fn [^Path tmp-dir]
      (let [home        (str (.resolve tmp-dir "home"))
            releases    (doto (.toFile (.resolve tmp-dir "releases")) (.mkdirs))
            content     (.getBytes "#!/bin/sh\necho plugin\n")
            ; 64KB that does not compress well, so cutting the .gz in half truncates the data stream
            big-content (byte-array (map #(unchecked-byte (* % 7919)) (range 65536)))
            platform    {:os-name          "missing"
                         :os-arch          "x86_64"
                         :os-name-variants ["missing" "linux"]
                         :os-arch-variants ["x86_64"]}
            url-for     #(str "file://" (.getAbsolutePath releases) "/" %)
            config      (fn [plugin-name url-template]
                          {:name             plugin-name
                           :version          "1.0.0"
                           :url-template     url-template
                           :output-directive "test_out"})
            plugin-file #(io/file home "plugins-installations" % "1.0.0" %)
            get-plugin! #(@#'sut/get-plugin! home platform %)]
        (io/copy content (io/file releases "linux.bin"))
        (io/copy (util/gzip-bytes content) (io/file releases "linux.gz"))
        (io/copy (util/zip-bytes "protoc-gen-zip" content) (io/file releases "linux.zip"))
        (let [gz-bytes (util/gzip-bytes big-content)]
          (io/copy (Arrays/copyOf gz-bytes (quot (alength gz-bytes) 2))
                   (io/file releases "linux-truncated.gz")))
        (let [tar-src (doto (.toFile (.resolve tmp-dir "tar-src")) (.mkdirs))]
          (io/copy content (io/file tar-src "protoc-gen-tar"))
          (is (zero? (:exit (sh/sh "tar" "-czf" (str (io/file releases "linux.tar.gz"))
                                   "-C" (str tar-src) "protoc-gen-tar")))))

        (testing "Downloads plain, .gz and .tar.gz plugins, falling back across URL variants"
          (are [plugin-name url-suffix]
            (let [path (get-plugin! (config plugin-name (url-for url-suffix)))]
              (and (= (str (plugin-file plugin-name)) path)
                   (Arrays/equals content (Files/readAllBytes (.toPath (io/file path))))
                   (.canExecute (io/file path))
                   (= [plugin-name] (vec (.list (.getParentFile (io/file path)))))))
            "protoc-gen-plain" "${:os-name}.bin"
            "protoc-gen-gz" "${:os-name}.gz"
            "protoc-gen-tar" "${:os-name}.tar.gz"
            "protoc-gen-zip" "${:os-name}.zip"))

        (testing "A truncated download is not cached"
          (let [plugin-name         "protoc-gen-truncated"
                ^ExceptionInfo error (try
                                       (get-plugin! (config plugin-name (url-for "${:os-name}-truncated.gz")))
                                       nil
                                       (catch ExceptionInfo e
                                         e))]
            (is (= "Failed to download plugin from any URL" (some-> error .getMessage)))
            (is (some? (ex-cause error)))
            (is (not (.exists ^File (plugin-file plugin-name))))
            (is (empty? (.list (.getParentFile ^File (plugin-file plugin-name)))))
            (testing "and the next run downloads again"
              (is (= (str (plugin-file plugin-name))
                     (get-plugin! (config plugin-name (url-for "${:os-name}.gz"))))))))))))

(def ^:private validate-test-repos
  '{:user-protos {:repo-type    :filesystem
                  :config       {:path "./resources/test/proto_repo3"}
                  :proto-paths  ["protos"]
                  :dependencies [protos/validation]}
    :validate    {:repo-type   :git
                  :config      {:clone-url "https://github.com/bufbuild/protoc-gen-validate.git"
                                :rev       "v1.3.0"}
                  :proto-paths ["."]}})

(defn- generated-file-names [^Path dir]
  (->> (.toFile dir)
       file-seq
       (remove #(.isDirectory ^File %))
       (map #(.getName ^File %))
       set))

(deftest protoc-gen-validate-plugin-test
  (testing "Without the plugin, no validator files are generated"
    (run-test!
      (fn [tmp-dir]
        (sut/generate-files! {} {:output-path   (str tmp-dir)
                                 :proto-version "3.25.5"
                                 :repos         validate-test-repos})
        (let [files (generated-file-names tmp-dir)]
          (is (contains? files "User.java"))
          (is (not-any? #(.contains ^String % "Validator") files))))))

  (testing "With the plugin, validator files are generated"
    (run-test!
      (fn [tmp-dir]
        (sut/generate-files! {} {:output-path   (str tmp-dir)
                                 :proto-version "3.25.5"
                                 :plugins       [{:name             "protoc-gen-validate"
                                                  :version          "1.3.0"
                                                  :url-template     "https://github.com/bufbuild/protoc-gen-validate/releases/download/v${:version}/protoc-gen-validate_${:version}_${:os-name}_${:os-arch}.tar.gz"
                                                  :output-directive "validate_out"
                                                  :options          "lang=java"}]
                                 :repos         validate-test-repos})
        (let [files (generated-file-names tmp-dir)]
          (is (contains? files "User.java"))
          (is (contains? files "UserValidator.java")))))))

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

(ns leiningen.protodeps.archive-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.test :refer [are deftest is testing]]
            [leiningen.protodeps.archive :as sut]
            [leiningen.protodeps.fs :as fs]
            [leiningen.protodeps.test-util :as util])
  (:import (clojure.lang ExceptionInfo)
           (java.io ByteArrayInputStream File)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util Arrays)))

(deftest download-format-test
  (testing "The download format comes from the URL path, ignoring the query, fragment and case"
    (are [url expected] (= expected (sut/download-format url))
      "https://example.com/plugin.tar.gz" :tar-gz
      "https://example.com/plugin.tgz" :tar-gz
      "https://example.com/plugin.TAR.GZ" :tar-gz
      "https://example.com/plugin.tar.gz?download=1" :tar-gz
      "https://example.com/plugin.zip" :zip
      "https://example.com/plugin.ZIP" :zip
      "https://example.com/plugin.zip#sha256" :zip
      "https://example.com/plugin.gz" :gz
      "https://example.com/plugin.exe" :plain
      "https://example.com/plugin?name=x.zip" :plain)))

(defn- tar-gz-bytes
  "A tar.gz of member under src-dir. Archiving \".\" stores members as ./name."
  ^bytes [^File src-dir member]
  (let [{:keys [exit out err]} (sh/sh "tar" "-czf" "-" "-C" (str src-dir) member :out-enc :bytes)]
    (when-not (zero? exit)
      (throw (ex-info "tar failed" {:err err})))
    out))

(deftest extract-test
  (let [^Path tmp-dir (Files/createTempDirectory "archive-test" (make-array FileAttribute 0))
        content       (.getBytes "#!/bin/sh\necho plugin\n")
        target        (io/file (.toFile tmp-dir) "out" "protoc-gen-x")
        extract!      (fn [fmt ^bytes archive-bytes]
                        (fs/delete-path! (.getParentFile target))
                        (.mkdirs (.getParentFile target))
                        (sut/extract! fmt (ByteArrayInputStream. archive-bytes) "protoc-gen-x" target))
        ; Writes content at dir-name/path... and returns the dir-name directory
        write-src!    (fn [dir-name & path]
                        (let [src-dir (io/file (.toFile tmp-dir) dir-name)
                              file    (apply io/file src-dir path)]
                          (.mkdirs (.getParentFile file))
                          (io/copy content file)
                          src-dir))]
    (try
      (let [file-dir  (write-src! "file" "protoc-gen-x")
            dir-dir   (write-src! "dir" "protoc-gen-x" "protoc-gen-x")
            other-dir (write-src! "other" "protoc-gen-other")
            file-tar  (tar-gz-bytes file-dir "protoc-gen-x")]
        (testing "Each format writes the named binary to the target file"
          (are [fmt archive-bytes]
            (do (extract! fmt archive-bytes)
                (Arrays/equals content (Files/readAllBytes (.toPath target))))
            :plain content
            :gz (util/gzip-bytes content)
            :zip (util/zip-bytes "protoc-gen-x" content)
            :tar-gz file-tar
            :tar-gz (tar-gz-bytes file-dir ".")))

        (testing "A missing entry fails"
          (is (thrown-with-msg? ExceptionInfo #"Plugin not found in zip"
                                (extract! :zip (util/zip-bytes "protoc-gen-other" content))))
          (is (thrown-with-msg? ExceptionInfo #"Plugin not found in tar.gz"
                                (extract! :tar-gz (tar-gz-bytes other-dir "protoc-gen-other")))))

        (testing "A directory with the plugin's name fails and is removed"
          (is (thrown-with-msg? ExceptionInfo #"protoc-gen-x is not a regular file"
                                (extract! :tar-gz (tar-gz-bytes dir-dir "protoc-gen-x"))))
          (is (not (fs/exists? target))))

        (testing "A body that is not a tar.gz fails and leaves nothing behind"
          (is (thrown-with-msg? ExceptionInfo #"Failed to extract tar.gz"
                                (extract! :tar-gz (.getBytes "<html>Not Found</html>"))))
          (is (empty? (.list (.getParentFile target))))))
      (finally
        (fs/delete-path! tmp-dir)))))

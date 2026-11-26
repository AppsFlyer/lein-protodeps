(ns leiningen.protodeps.archive-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.test :refer [are deftest is testing]]
            [leiningen.protodeps.archive :as sut]
            [leiningen.protodeps.fs :as fs]
            [leiningen.protodeps.test-util :as util])
  (:import (java.io ByteArrayInputStream File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util Arrays)
           (java.util.zip ZipInputStream)))

(set! *warn-on-reflection* true)

(deftest download-format-test
  (testing "The download format comes from the URL path, ignoring the query, fragment and case"
    (are [url expected]
      (= expected (sut/download-format url))
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
  (let [tmp-dir        (Files/createTempDirectory "archive-test" (make-array FileAttribute 0))
        content        (.getBytes "#!/bin/sh\necho plugin\n")
        target         (io/file (.toFile tmp-dir) "out" "protoc-gen-x")
        target-dir     (.getParentFile target)
        ; Extracts archive-bytes to target, in an out dir that holds only existing-content at target when given
        extract-bytes! (fn extract-bytes!
                         ([fmt archive-bytes]
                          (extract-bytes! fmt archive-bytes nil))
                         ([fmt ^bytes archive-bytes existing-content]
                          (fs/delete-path! target-dir)
                          (.mkdirs target-dir)
                          (when existing-content
                            (spit target existing-content))
                          (sut/extract! fmt (ByteArrayInputStream. archive-bytes) "protoc-gen-x" target)))
        ; Extracts over an existing target, expecting a failure. Returns the error message when target is
        ; unchanged and nothing else was left behind
        failure        (fn [fmt archive-bytes]
                         (try
                           (extract-bytes! fmt archive-bytes "existing")
                           nil
                           (catch Exception e
                             (when (and (= "existing" (slurp target))
                                        (= ["protoc-gen-x"] (vec (.list target-dir))))
                               (ex-message e)))))
        ; Writes content at dir-name/path... and returns the dir-name directory
        write-src!     (fn [dir-name & path]
                         (let [src-dir    (io/file (.toFile tmp-dir) dir-name)
                               ^File file (apply io/file src-dir path)]
                           (.mkdirs (.getParentFile file))
                           (io/copy content file)
                           src-dir))
        extracted?     #(Arrays/equals content (Files/readAllBytes (.toPath target)))]
    (try
      (let [file-dir   (write-src! "file" "protoc-gen-x")
            nested-dir (write-src! "nested" "protoc-gen-x" "protoc-gen-x")
            other-dir  (write-src! "other" "protoc-gen-other")
            file-tar   (tar-gz-bytes file-dir "protoc-gen-x")
            html       (.getBytes "<html>Not Found</html>")
            large-html (.getBytes (str/join (repeat 100000 "<html>Not Found</html>")))]
        (testing "Each format writes the named binary to the target file"
          (are [fmt archive-bytes]
            (do (extract-bytes! fmt archive-bytes)
                (extracted?))
            :plain content
            :gz (util/gzip-bytes content)
            :zip (util/zip-bytes "protoc-gen-x" content)
            :tar-gz file-tar
            :tar-gz (tar-gz-bytes file-dir ".")))

        (testing "Each format replaces an existing target file"
          (are [fmt archive-bytes]
            (do (extract-bytes! fmt archive-bytes "existing")
                (extracted?))
            :plain content
            :gz (util/gzip-bytes content)
            :zip (util/zip-bytes "protoc-gen-x" content)
            :tar-gz file-tar))

        (testing "A failed extraction leaves an existing target unchanged and nothing else behind"
          (are [fmt archive-bytes message]
            (re-find message (str (failure fmt archive-bytes)))
            :gz html #"Not in GZIP format"
            :zip html #"Plugin not found in zip"
            :zip (util/zip-bytes "protoc-gen-other" content) #"Plugin not found in zip"
            :tar-gz html #"Failed to extract tar.gz: .+"
            :tar-gz large-html #"Failed to extract tar.gz: .+"
            :tar-gz (tar-gz-bytes other-dir "protoc-gen-other") #"Plugin not found in tar.gz"
            :tar-gz (tar-gz-bytes nested-dir "protoc-gen-x") #"protoc-gen-x is not a regular file"))

        (testing "A symlink at the target is replaced, and the file it points to is unchanged"
          (let [pointee (io/file (.toFile tmp-dir) "pointee")]
            (spit pointee "pointee")
            (fs/delete-path! target-dir)
            (.mkdirs target-dir)
            (Files/createSymbolicLink (.toPath target) (.toPath pointee) (make-array FileAttribute 0))
            (sut/extract! :plain (ByteArrayInputStream. content) "protoc-gen-x" target)
            (is (fs/regular-file? target))
            (is (extracted?))
            (is (= "pointee" (slurp pointee)))))

        (testing "A tarball with a read-only directory still extracts, and the failed cleanup only warns"
          (let [ro-src (write-src! "read-only" "protoc-gen-x")
                ro-dir (io/file ro-src "sub")
                _      (write-src! "read-only" "sub" "file")
                _      (.setWritable ro-dir false)
                ro-tar (tar-gz-bytes ro-src ".")
                err    (with-out-str
                         (binding [*err* *out*]
                           (extract-bytes! :tar-gz ro-tar)))]
            (is (extracted?))
            (is (str/includes? err "WARNING: Failed to delete"))
            (run! #(.setWritable ^File % true) (concat [ro-dir] (file-seq target-dir))))))
      (finally
        (fs/delete-path! tmp-dir)))))

(deftest unzip-test
  (testing "unzip! writes every file in the zip under the destination"
    (let [tmp-dir (Files/createTempDirectory "unzip-test" (make-array FileAttribute 0))]
      (try
        (with-open [zinp (ZipInputStream. (ByteArrayInputStream. (util/zip-bytes "bin/protoc" (.getBytes "protoc"))))]
          (with-out-str
            (sut/unzip! zinp (str tmp-dir))))
        (is (= "readme" (slurp (io/file (.toFile tmp-dir) "README.md"))))
        (is (= "protoc" (slurp (io/file (.toFile tmp-dir) "bin" "protoc"))))
        (finally
          (fs/delete-path! tmp-dir))))))

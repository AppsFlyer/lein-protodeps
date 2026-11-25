(ns leiningen.protodeps.archive
  "Extract a single plugin binary from a downloaded archive or compressed file."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [leiningen.protodeps.fs :as fs])
  (:import (java.io InputStream)
           (java.nio.file CopyOption Files Path)
           (java.nio.file.attribute FileAttribute)
           (java.util List)
           (java.util.zip GZIPInputStream ZipInputStream)))

(defn download-format
  "The download format of a URL, judged by its path without the query or fragment, ignoring case."
  [url]
  (let [url-path (str/lower-case (str/replace url #"[?#].*" ""))]
    (cond
      (or (str/ends-with? url-path ".tar.gz")
          (str/ends-with? url-path ".tgz")) :tar-gz
      (str/ends-with? url-path ".zip") :zip
      (str/ends-with? url-path ".gz") :gz
      :else :plain)))

(defn- untar!
  "Extract the whole tar.gz from input into dir. Throws when tar fails."
  [^InputStream input ^Path dir]
  (let [^List cmd ["tar" "-xzf" "-" "-C" (str dir)]
        process   (-> (ProcessBuilder. cmd)
                      (.redirectErrorStream true)
                      (.start))]
    (try
      (with-open [output (.getOutputStream process)]
        (io/copy input output))
      (let [exit (.waitFor process)]
        (when-not (zero? exit)
          (let [err (slurp (.getInputStream process))]
            (throw (ex-info (str "Failed to extract tar.gz: " err)
                            {:exit  exit
                             :error err})))))
      (finally
        (.destroy process)))))

(defn- extract-tar-entry!
  "Move the tar.gz entry named entry-name to target-file.
   The whole archive is extracted to a scratch dir next to target-file, so an entry stored
   as either `name` or `./name` is found with any tar implementation.
   Throws when tar fails or the archive has no such entry."
  [^InputStream input ^String entry-name target-file]
  (let [target      (io/file target-file)
        scratch-dir (Files/createTempDirectory (.toPath (.getParentFile target)) "untar" (make-array FileAttribute 0))]
    (try
      (untar! input scratch-dir)
      (let [entry (.resolve scratch-dir entry-name)]
        (when-not (fs/exists? entry)
          (throw (ex-info "Plugin not found in tar.gz"
                          {:entry entry-name})))
        (Files/move entry (.toPath target) (make-array CopyOption 0)))
      (finally
        (fs/delete-path! scratch-dir)))))

(defn- extract-zip-entry!
  "Copy the zip entry named entry-name to target-file. Throws when the zip has no such entry."
  [^InputStream input entry-name target-file]
  (with-open [zinp (ZipInputStream. input)]
    (loop []
      (if-let [entry (.getNextEntry zinp)]
        (if (= entry-name (.getName entry))
          (with-open [output (io/output-stream (io/file target-file))]
            (io/copy zinp output))
          (recur))
        (throw (ex-info "Plugin not found in zip"
                        {:entry entry-name}))))))

(defn extract!
  "Write the binary named entry-name from input to target-file.
   fmt is a download-format result. Archives must have the binary at entry-name.
   Throws when the result is not a regular file, for example a directory or a symlink in a tarball."
  [fmt ^InputStream input entry-name target-file]
  (try
    (case fmt
      :tar-gz (extract-tar-entry! input entry-name target-file)
      :zip (extract-zip-entry! input entry-name target-file)
      :gz (with-open [gzip-input (GZIPInputStream. input)]
            (io/copy gzip-input (io/file target-file)))
      :plain (io/copy input (io/file target-file)))
    (when-not (fs/regular-file? target-file)
      (throw (ex-info (str entry-name " is not a regular file")
                      {:entry entry-name})))
    (catch Exception e
      ; Remove anything a failed extraction left behind, so the next URL starts clean
      (fs/delete-path! target-file)
      (throw e))))

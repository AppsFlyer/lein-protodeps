(ns leiningen.protodeps.archive
  "Extract downloaded archives and compressed files."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [leiningen.protodeps.fs :as fs])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)
           (java.util.zip GZIPInputStream ZipEntry ZipInputStream)))

(set! *warn-on-reflection* true)

(defn- zip-entries
  "The entries of zinp, read lazily and in order. Read an entry's data from zinp before realizing the next entry."
  [^ZipInputStream zinp]
  (take-while some? (repeatedly #(.getNextEntry zinp))))

(defn- write-zip-entry! [^ZipInputStream zinp ^ZipEntry entry dst]
  (let [file (io/file dst (.getName entry))]
    (when-not (.isDirectory entry)
      (io/make-parents file)
      (println "unzipping" (str file))
      (with-open [output (io/output-stream file)]
        (io/copy zinp output)))))

(defn unzip!
  "Extract every file in zinp under dst."
  [zinp dst]
  (doseq [entry (zip-entries zinp)]
    (write-zip-entry! zinp entry dst)))

(defn- untar!
  "Extract the whole tar.gz from input into dir. Throws when tar fails."
  [input dir]
  (let [{:keys [exit err]} (sh/sh "tar" "-xzf" "-" "-C" (str dir) :in input)]
    (when-not (zero? exit)
      (throw (ex-info (str "Failed to extract tar.gz: " (str/trim err))
                      {:exit  exit
                       :error err})))))

(defn- extract-tar-entry!
  "Extract the whole tar.gz into the dir of staged-file, so an entry stored as either `name` or `./name`
   is found with any tar implementation. Throws when tar fails or the archive has no such entry."
  [input entry-name ^File staged-file]
  (untar! input (.getParentFile staged-file))
  (when-not (fs/exists? staged-file)
    (throw (ex-info "Plugin not found in tar.gz"
                    {:entry entry-name}))))

(defn- extract-zip-entry!
  "Copy the zip entry named entry-name to staged-file. Throws when the zip has no such entry."
  [input entry-name staged-file]
  (with-open [zinp (ZipInputStream. input)]
    (if (some #(= entry-name (.getName ^ZipEntry %)) (zip-entries zinp))
      (io/copy zinp (io/file staged-file))
      (throw (ex-info "Plugin not found in zip"
                      {:entry entry-name})))))

(defn- extract-gz! [input _ staged-file]
  (with-open [gzip-input (GZIPInputStream. input)]
    (io/copy gzip-input (io/file staged-file))))

(defn- copy-plain! [input _ staged-file]
  (io/copy input (io/file staged-file)))

(def ^:private formats
  "Download formats in the order their URL suffixes are checked, so .tar.gz is matched before .gz.
   :plain matches no suffix and is the fallback."
  [{:format :tar-gz :suffixes [".tar.gz" ".tgz"] :extract! extract-tar-entry!}
   {:format :zip :suffixes [".zip"] :extract! extract-zip-entry!}
   {:format :gz :suffixes [".gz"] :extract! extract-gz!}
   {:format :plain :suffixes [] :extract! copy-plain!}])

(defn download-format
  "The download format of a URL, judged by its path without the query or fragment, ignoring case."
  [url]
  (let [url-path (str/lower-case (str/replace url #"[?#].*" ""))]
    (or (some (fn [{fmt :format suffixes :suffixes}]
                (when (some #(str/ends-with? url-path %) suffixes)
                  fmt))
              formats)
        :plain)))

(defn extract!
  "Write the binary named entry-name from input to target-file.
   fmt is a download-format result. Archives must have the binary at entry-name.
   The binary is extracted into a staging dir next to target-file and moved into place only when it is a
   regular file, so a failed extraction never changes target-file. Staging cleanup is best-effort.
   Throws when extraction fails or the result is not a regular file, for example a directory or a symlink
   in a tarball."
  [fmt input entry-name target-file]
  (let [extract-fn  (or (some #(when (= fmt (:format %)) (:extract! %)) formats)
                        (throw (ex-info (str "Unknown download format " fmt)
                                        {:format fmt})))
        target      (.getAbsoluteFile (io/file target-file))
        staging-dir (Files/createTempDirectory (.toPath (.getParentFile target)) "extract" (make-array FileAttribute 0))
        staged-file (io/file (str staging-dir) entry-name)]
    (try
      (extract-fn input entry-name staged-file)
      (when-not (fs/regular-file? staged-file)
        (throw (ex-info (str entry-name " is not a regular file")
                        {:entry entry-name})))
      (fs/move-into-place! staged-file target)
      (finally
        (fs/try-delete-path! staging-dir)))))

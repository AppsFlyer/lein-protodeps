(ns leiningen.protodeps.fs
  "File system helpers that never follow symlinks."
  (:require [clojure.java.io :as io])
  (:import (java.nio.file Files LinkOption Path)))

(defn- no-follow-links []
  (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))

(defn- ->path ^Path [path]
  (if (instance? Path path)
    path
    (.toPath (io/file path))))

(defn regular-file?
  "True when path is a regular file. A symlink is not followed, so it is never a regular file."
  [path]
  (Files/isRegularFile (->path path) (no-follow-links)))

(defn exists?
  "True when anything is at path, including a dangling symlink."
  [path]
  (Files/exists (->path path) (no-follow-links)))

(defn directory?
  "True when path is a directory. A symlink to a directory is not a directory."
  [path]
  (Files/isDirectory (->path path) (no-follow-links)))

(defn delete-path!
  "Delete path and, for a directory, everything under it. Symlinks are deleted, never followed."
  [path]
  (let [p (->path path)]
    (when (directory? p)
      (with-open [children (Files/list p)]
        (run! delete-path! (iterator-seq (.iterator children)))))
    (Files/deleteIfExists p)))

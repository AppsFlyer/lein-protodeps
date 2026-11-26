(ns leiningen.protodeps.fs
  "File system helpers that never follow symlinks."
  (:require [clojure.java.io :as io]
            [leiningen.protodeps.log :as log])
  (:import (java.nio.file CopyOption Files LinkOption Path StandardCopyOption)))

(set! *warn-on-reflection* true)

(defn- no-follow-links ^"[Ljava.nio.file.LinkOption;" []
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

(defn- children
  "The entries of dir, read eagerly so the directory stream is closed before they are used."
  [^Path dir]
  (with-open [stream (Files/list dir)]
    (vec (iterator-seq (.iterator stream)))))

(defn delete-path!
  "Delete path and, for a directory, everything under it. Symlinks are deleted, never followed."
  [path]
  (let [p (->path path)]
    (when (directory? p)
      (run! delete-path! (children p)))
    (Files/deleteIfExists p)))

(defn try-delete-path!
  "Best-effort delete-path! for cleanup. Deletes everything it can and prints a warning for each path
   it cannot delete, instead of throwing, so a failed cleanup never replaces the caller's result or error.
   A directory whose children were not all deleted is kept without a warning of its own.
   Returns true when path was fully deleted."
  [path]
  (let [p (->path path)]
    (try
      (if (and (directory? p)
               (not (every? true? (mapv try-delete-path! (children p)))))
        false
        (do
          (Files/deleteIfExists p)
          true))
      (catch Exception e
        (log/print-warning "Failed to delete" (str p) ":" (str e))
        false))))

(defn move-into-place!
  "Atomically move source to target, replacing a file or symlink at target.
   A directory at target is deleted first, since an atomic move cannot replace one."
  [source target]
  (let [target-path (->path target)]
    (when (directory? target-path)
      (delete-path! target-path))
    (Files/move (->path source) target-path (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE]))))

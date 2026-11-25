(ns leiningen.protodeps.test-util
  (:import (java.io ByteArrayOutputStream)
           (java.util.zip GZIPOutputStream ZipEntry ZipOutputStream)))

(defn gzip-bytes
  "Gzip-compress content."
  ^bytes [^bytes content]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gz (GZIPOutputStream. out)]
      (.write gz content))
    (.toByteArray out)))

(defn zip-bytes
  "A zip with a README.md entry followed by entry-name holding content."
  ^bytes [^String entry-name ^bytes content]
  (let [out (ByteArrayOutputStream.)]
    (with-open [zip (ZipOutputStream. out)]
      (.putNextEntry zip (ZipEntry. "README.md"))
      (.write zip (.getBytes "readme"))
      (.putNextEntry zip (ZipEntry. entry-name))
      (.write zip content))
    (.toByteArray out)))

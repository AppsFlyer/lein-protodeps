(ns leiningen.protodeps
  (:require [clojure.java.io :as io]
            [clojure.string :as strings]
            [clojure.java.shell :as sh]
            [clojure.set :as sets]
            [leiningen.core.main :as lein]
            [clojure.tools.cli :as cli])
  (:import [java.io File]
           [java.nio.file Files]
           [java.nio.file.attribute FileAttribute]
           [java.nio.file Path]))

(def ^:dynamic *verbose?* false)

(defn- verbose-prn [msg & args]
  (when *verbose?*
    (println "protodeps:" (apply format msg args))))


(defn print-err [& s]
  (binding [*out* *err*]
    (apply println s)))

(defn print-warning [& s]
  (apply print-err "protodeps: WARNING:" s))

(defn append-dir [parent & children]
  (strings/join File/separator (concat [parent] children)))


(defn create-temp-dir!
  ([] (create-temp-dir! nil))
  ([^Path base-path]
   (let [file-attrs (make-array FileAttribute 0)]
     (if base-path
       (Files/createTempDirectory base-path nil file-attrs)
       (Files/createTempDirectory nil file-attrs)))))


(defn- parse-semver [semver]
  (when semver
    (let [[major minor patch :as parts] (map #(Long/parseLong %)
                                             (strings/split semver #"\."))]
      (when-not (= (count parts) 3)
        (throw (ex-info "invalid semver, expected major.minor.patch" {:version semver})))
      {:major  major
       :minor  minor
       :patch  patch
       :semver semver})))


(defn- interpolate [m s]
  (reduce
    (fn [s [k v]]
      (strings/replace s (format "${%s}" (str k)) (str v)))
    s
    m))


(defn mkdir! [dir-path]
  (let [dir (io/file dir-path)]
    (when-not (or (.exists dir)
                  (.mkdirs dir))
      (throw (ex-info "failed to create dir" {:dir dir-path})))
    dir))


(defn run-sh!
  ([cmd opts] (run-sh! cmd opts nil))
  ([cmd opts m]
   (verbose-prn (str "running " cmd " with opts: %s") opts)
   (let [{:keys [exit] :as r} (apply sh/sh cmd (map (partial interpolate m) opts))]
     (if (= 0 exit)
       r
       (throw (ex-info (str cmd " failed") r))))))


(defn- sha? [s]
  (re-matches #"^[A-Za-z0-9]{40}$" s))


(defn- git-clone! [repo-url dir rev]
  (let [dir  (str dir)
        conf (into {}
                   (map
                     (fn [[k v]]
                       [(keyword "env" k) v]))
                   (System/getenv))]
    (if (sha? rev)
      (do
        (run-sh! "git" ["clone" repo-url dir] conf)
        (run-sh! "git" ["-C" dir "checkout" rev]))
      (run-sh! "git" ["clone" repo-url "--branch" rev "--single-branch" "--depth" "1" dir] conf))))


(defn clone! [repo-name base-path repo-config]
  (let [git-config (:config repo-config)
        path       (str (create-temp-dir! base-path))
        repo-url   (:clone-url git-config)
        rev        (:rev git-config)]
    (println "cloning" repo-name "at rev" rev "...")
    (when-not rev
      (throw  (ex-info (str ":rev is not set for " repo-name ", set a git tag/branch name/commit hash") {})))
    (git-clone! repo-url path rev)
    path))


(defmulti resolve-repo (fn [_ctx repo-config] (:repo-type repo-config)))

(defmethod resolve-repo :git [ctx repo-config]
  (clone! (:repo-name ctx) (:base-path ctx) repo-config))

(defmethod resolve-repo :filesystem [_ repo-config]
  (some-> repo-config :config :path io/file .getAbsolutePath))

(defn write-zip-entry! [^java.util.zip.ZipInputStream zinp
                        ^java.util.zip.ZipEntry entry
                        base-path]
  (let [file-name  (append-dir base-path (.getName entry))
        ^File file (io/file file-name)
        size       (.getCompressedSize entry)]
    (when (pos? size)
      (let [^bytes buf (byte-array 1024) ^File parent-file (.getParentFile file)]
        (mkdir! (.getAbsolutePath parent-file))
        (with-open [outp (io/output-stream file-name)]
          (println "unzipping" file-name)
          (loop []
            (let [bytes-read (.read zinp buf)]
              (when (pos? bytes-read)
                (.write outp buf 0 bytes-read)
                (recur)))))))))

(defn unzip! [^java.util.zip.ZipInputStream zinp dst]
  (loop []
    (when-let [^java.util.zip.ZipEntry entry (.getNextEntry zinp)]
      (write-zip-entry! zinp entry dst)
      (.closeEntry zinp)
      (recur))))

(def os-name->os {"Linux" "linux" "Mac OS X" "osx"})
(def os-arch->arch {"amd64" "x86_64" "x86_64" "x86_64" "aarch64" "aarch64"})


(defn get-prop [env prop-name]
  (if-let [v (get env prop-name)]
    v
    (throw (ex-info "unknown prop" {:prop-name prop-name}))))


;; osx/aarch64 release is unavailable yet, therefore we fall back to x86_64
(def ^:private platform-alternatives {{:os-name "osx" :os-arch "aarch64"}
                                      {:os-name "osx" :os-arch "x86_64"}})


(defn- get-platform [env]
  (let [raw-os-name (get env "os.name")
        raw-os-arch (get env "os.arch")
        os-name     (get os-name->os raw-os-name)
        os-arch     (get os-arch->arch raw-os-arch)
        platform    {:os-name os-name :os-arch os-arch}]
    (when (or (nil? os-arch) (nil? os-name))
      (throw (ex-info
               "\nPlatform is not currently supported.\n
Please open an issue at https://github.com/AppsFlyer/lein-protodeps/issues
and include this full error message to add support for your platform."
                      {:os.name raw-os-name :os.arch raw-os-arch})))
    (get platform-alternatives platform platform)))

(defn- get-protoc-release [{:keys [semver os-name os-arch]}]
  (strings/join "-" ["protoc" semver os-name os-arch]))

(def ^:private grpc-plugin-executable-name "protoc-gen-grpc-java")


(defn set-protoc-permissions! [protoc-path]
  (let [permissions (java.util.HashSet.)]
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_EXECUTE)
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_READ)
    (.add permissions java.nio.file.attribute.PosixFilePermission/OWNER_WRITE)
    (java.nio.file.Files/setPosixFilePermissions (.toPath (io/file protoc-path))
                                                 permissions)))


(defn download-protoc! [url dst]
  (println "protodeps: Downloading protoc from" url "...")
  (with-open [inp (java.util.zip.ZipInputStream. (io/input-stream url))]
    (unzip! inp dst)))

(defn- download-grpc-plugin! [url grpc-plugin-file]
  (println "protodeps: Downloading grpc java plugin from" url "...")
  (with-open [input-stream  (io/input-stream url)
              output-stream (io/output-stream (io/file grpc-plugin-file))]
    (io/copy input-stream output-stream)))


(defn run-protoc-and-report! [protoc-path opts]
  (let [{:keys [out err]} (run-sh! protoc-path opts)]
    (when-not (strings/blank? err)
      (print-err err))
    (when-not (strings/blank? out)
      (println out))))


(def protoc-release-tpl "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip")


(def new-protoc-release-tpl "https://github.com/protocolbuffers/protobuf/releases/download/v${:minor}.${:patch}/protoc-${:minor}.${:patch}-${:os-name}-${:os-arch}.zip")


(def grpc-release-tpl "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${:semver}/protoc-gen-grpc-java-${:semver}-${:os-name}-${:os-arch}.exe")


(defn- protoc-release-template [{:keys [protoc-zip-url-template]}
                                {:keys [major minor]}]
  (or
    protoc-zip-url-template
    (if (and (>= major 3) (>= minor 21))
      ;; 3.21 introduced a breaking change into release naming conventions,
      ;; see here: https://developers.google.com/protocol-buffers/docs/news/2022-05-06
      new-protoc-release-tpl
      protoc-release-tpl)))


(def ^:private protoc-install-dir "protoc-installations")
(def ^:private grpc-install-dir "grpc-installations")

(defn init-rc-dir! []
  (let [home (append-dir (get-prop (System/getProperties) "user.home") ".lein-protodeps")]
    (mkdir! home)
    (mkdir! (append-dir home protoc-install-dir))
    (mkdir! (append-dir home grpc-install-dir))
    home))

(defn discover-files [git-repo-path dep-path]
  (filterv
   (fn [^File file]
     (and (not (.isDirectory file))
          (strings/ends-with? (.getName file) ".proto")))
   (file-seq (io/file (append-dir git-repo-path dep-path)))))

(defn long-opt [k v]
  (str "--" k "=" v))

(defn with-proto-paths [protoc-args proto-paths]
  (into protoc-args
        (map (partial long-opt "proto_path")
             proto-paths)))

(defn get-file-dependencies [protoc-path proto-paths ^File proto-file]
  (map io/file
       (re-seq #"[^\s]*\.proto"
               (:out
                (run-sh!
                 protoc-path
                 (with-proto-paths
                   [(long-opt "dependency_out" "/dev/stdout")
                    "-o/dev/null"
                    (.getAbsolutePath proto-file)]
                   proto-paths))))))


(defn parallelize [{:keys [level min-chunk-size]} c combine-f f]
  (if (or (= 1 level) (< (count c) min-chunk-size))
    (f c)
    (let [chunks (partition-all (int (/ (count c) level)) c)
          ;; parallelism is capped by number of cores
          results (pmap f chunks)]
      (transduce (map identity) combine-f results))))

(defn expand-dependencies [parallelism protoc-path proto-paths proto-files]
  (parallelize
   parallelism
   proto-files
   sets/union
   (fn [proto-files]
     (loop [seen-files (set proto-files)
            [f & r]    proto-files]
       (if-not f
         seen-files
         (let [deps (get-file-dependencies protoc-path proto-paths f)
               deps (filterv
                     (fn [^File afile]
                       (not (some #(Files/isSameFile (.toPath ^File %) (.toPath afile)) seen-files)))
                     deps)]
           (recur
            (conj seen-files f)
            ;; For very large repos, we might end up concatenating an empty `deps` seq
            ;; many times over since most of the depenendencies will already be seen in prev iterations.
            ;; This could lead to the build up of a huge lazy-seq, since `concat` will still
            ;; cons to the seq. To circumvent this we concat only when non-empty.
            (if-not (seq deps)
              r
              (concat
               r
               deps)))))))))

(defn strip-suffix [suffix s]
  (if (strings/ends-with? s suffix)
    (subs s 0 (- (count s) (count suffix)))
    s))

(def strip-trailing-slash (partial strip-suffix "/"))

(defn validate-output-path [output-path project]
  (when-not output-path
    (throw (ex-info "output path not defined" {})))
  (when-not (some (fn [sp]
                    (strings/ends-with? (strip-trailing-slash sp)
                                        (strip-trailing-slash output-path)))
                  (:java-source-paths project))
    (print-warning "output-path" output-path "not found in :java-source-paths")))

(defn cleanup-dir! [^Path path]
  (doseq [file (reverse (file-seq (.toFile path)))]
    (.delete ^File file)))


(defn protoc-opts [proto-paths output-path compile-grpc? grpc-plugin ^File proto-file]
  (let [protoc-opts (with-proto-paths [(long-opt "java_out" output-path)] proto-paths)]
    (cond-> protoc-opts
      compile-grpc? (conj (long-opt "grpc-java_out" output-path))
      compile-grpc? (conj (long-opt "plugin" grpc-plugin))
      true          (conj (.getAbsolutePath proto-file)))))

(def cli-spec
  [["-h" "--help"]
   [nil "--keep-tmp-dir" "Don't delete temporary dir after process exits" :default false]
   ["-v" "--verbose" "Verbosity level" :default false]
   ["-p" "--parallelism PAR" "Parallelism level"
    :default (.availableProcessors (Runtime/getRuntime))
    :validate [pos?]
    :parse-fn #(Integer/parseInt %)]])

(defn- get-protoc! [home-dir config proto-version]
  (let [protoc-installs (append-dir home-dir protoc-install-dir)
        protoc-release  (get-protoc-release proto-version)
        protoc (append-dir protoc-installs protoc-release "bin" "protoc")]
    (when-not (.exists ^File (io/file protoc))
      (let [protoc-zip-url (interpolate proto-version (protoc-release-template config proto-version))]
        (download-protoc! protoc-zip-url (append-dir protoc-installs protoc-release)))
      (set-protoc-permissions! protoc))
    protoc))


(defn- get-grpc-plugin! [home-dir config grpc-version]
  (let [grpc-semver     (:semver grpc-version)
        grpc-installs   (append-dir home-dir grpc-install-dir)
        grpc-plugin-dir (append-dir grpc-installs grpc-semver)
        grpc-plugin     (append-dir grpc-plugin-dir grpc-plugin-executable-name)]
    (when (:compile-grpc? config)
      (when (not (.exists ^File (io/file grpc-plugin)))
        (mkdir! grpc-plugin-dir)
        (let [grpc-exe-url (interpolate grpc-version (or (:grpc-exe-url-template config)
                                                         grpc-release-tpl))]
          (download-grpc-plugin! grpc-exe-url grpc-plugin))
        (set-protoc-permissions! grpc-plugin))
      grpc-plugin)))

(defn generate-files! [opts config]
  (let [home-dir           (init-rc-dir!)
        parallelism        {:level (:parallelism opts)
                            :min-chunk-size 128}
        repos-config       (:repos config)
        output-path        (:output-path config)
        base-temp-path     (create-temp-dir!)
        ctx                {:base-path base-temp-path}
        keep-tmp?          (true? (:keep-tmp-dir opts))
        env                (System/getProperties)
        platform           (get-platform env)
        proto-version      (merge platform (parse-semver (:proto-version config)))
        grpc-version       (merge platform (parse-semver (:grpc-version config)))
        protoc             (get-protoc! home-dir config proto-version)
        grpc-plugin        (get-grpc-plugin! home-dir config grpc-version)
        repo-id->repo-path (into {}
                                 (map
                                   (fn [[k v]]
                                     (let [ctx (assoc ctx :repo-name k)]
                                       [k (resolve-repo ctx v)])))
                                 repos-config)
        proto-paths        (mapcat (fn [[repo-id repo-conf]]
                                     (map #(append-dir (get repo-id->repo-path repo-id) %)
                                          (:proto-paths repo-conf)))
                                   repos-config)]
    (try
      (mkdir! output-path)
      (verbose-prn "config: %s" config)
      (verbose-prn "paths: %s" {:protoc      protoc
                                :grpc-plugin grpc-plugin})
      (verbose-prn "output-path: %s" output-path)
      (doseq [[repo-id repo] repos-config]
        (let [repo-path   (get repo-id->repo-path repo-id)
              proto-files (transduce
                           (map
                            ;; For backward compatibility, we allow either [[my_dir]] or [my_dir]
                            ;; as part of the `:dependencies` vector.
                            (fn [proto-dir-or-vec]
                              (let [proto-dir (if (vector? proto-dir-or-vec)
                                                (first proto-dir-or-vec)
                                                proto-dir-or-vec)]
                                (println "analyzing" proto-dir "... This may take a while for large repos")
                                (expand-dependencies
                                 parallelism
                                 protoc proto-paths
                                 (discover-files repo-path (str proto-dir))))))
                           sets/union
                           (:dependencies repo))]
          (verbose-prn "files: %s" (mapv #(.getName ^File %) proto-files))
          (when (empty? proto-files)
            (print-warning "could not find any .proto files under" repo-id))
          (parallelize
           parallelism
           proto-files
           (constantly nil)
           (fn [proto-files]
             (doseq [proto-file proto-files]
               (let [protoc-opts (protoc-opts
                                  proto-paths
                                  output-path
                                  (:compile-grpc? config)
                                  grpc-plugin
                                  proto-file)]
                 (println "compiling" (.getName proto-file))
                 (run-protoc-and-report! protoc protoc-opts)))))))

      (finally
        (if keep-tmp?
          (println "generated" base-temp-path)
          (cleanup-dir! base-temp-path))))))

(defn generate-files*!
  "Generate protoc & gRPC stubs according to the `:lein-protodeps` configuration in `project.clj`"
  [opts project]
  (let [config          (:lein-protodeps project)
        output-path     (:output-path config)]
    (if (nil? config)
      (print-warning "No :lein-protodeps configuration found in project.clj")
      (binding [*verbose?* (-> opts :verbose)]
        (verbose-prn "config: %s" config)
        (validate-output-path output-path project)
        (generate-files! opts config)))))

(defn protodeps
  {:subtasks [#'generate-files*!]}
  [project & args]
  (let [{:keys [options summary errors arguments]} (cli/parse-opts args cli-spec)]
    (cond
      (:help options)
      (println summary)
      errors
      (doseq [err errors]
        (print-warning err))
      :else
      (let [mode (first arguments)]
        (case mode
          "generate" (generate-files*! options project)
          (lein/warn "Unknown task" mode))))))

(comment
  (def config '{:output-path   "src/java/generated"
                :proto-version "3.12.4"
                :grpc-version  "1.30.2"
                :compile-grpc? true
                :repos         {:af-proto
                                {:repo-type    :git
                                 :proto-paths  ["products"]
                                 :config       {:clone-url   "git@localhost:test/repo.git"
                                                :rev         "mybranch"}
                                 :dependencies [products/events]}}}))

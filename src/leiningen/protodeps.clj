(ns leiningen.protodeps
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [leiningen.core.main :as lein]
            [leiningen.protodeps.archive :as archive]
            [leiningen.protodeps.fs :as fs]
            [leiningen.protodeps.log :as log]
            [leiningen.protodeps.spec :as spec])
  (:import (java.io File FileNotFoundException InputStream)
           (java.nio.file Files Path)
           (java.nio.file.attribute FileAttribute PosixFilePermission)
           (java.util HashSet)
           (java.util.zip ZipInputStream)))

(set! *warn-on-reflection* true)

(def ^:dynamic *verbose?* false)

(defn- verbose-prn [msg & args]
  (when *verbose?*
    (println "protodeps:" (apply format msg args))))


(defn append-dir [parent & children]
  (str/join File/separator (concat [parent] children)))


(defn create-temp-dir!
  (^Path [] (create-temp-dir! nil))
  (^Path [^Path base-path]
   (let [^"[Ljava.nio.file.attribute.FileAttribute;" file-attrs (make-array FileAttribute 0)]
     (if base-path
       (Files/createTempDirectory base-path nil file-attrs)
       (Files/createTempDirectory nil file-attrs)))))


(defn- parse-semver [semver]
  (when semver
    (let [[major minor patch :as parts] (map #(Long/parseLong %)
                                             (str/split semver #"\."))]
      (when-not (= (count parts) 3)
        (throw (ex-info "invalid semver, expected major.minor.patch" {:version semver})))
      {:major   major
       :minor   minor
       :patch   patch
       :semver  semver
       :version semver})))


(defn- version-parts
  "Split a plugin version into :major, :minor and :patch when it has the form x.y.z.
   Returns nil for other version formats, so their URL templates only get ${:version}."
  [version]
  (when-let [[_ major minor patch] (re-matches #"(\d+)\.(\d+)\.(\d+)" version)]
    {:major major
     :minor minor
     :patch patch}))


(defn- interpolate [m s]
  (reduce
    (fn [s [k v]]
      (str/replace s (format "${%s}" (str k)) (str v)))
    s
    m))


(defn mkdir! ^File [dir-path]
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
      (throw (ex-info (str ":rev is not set for " repo-name ", set a git tag/branch name/commit hash") {})))
    (git-clone! repo-url path rev)
    path))


(defmulti resolve-repo (fn [_ctx repo-config] (:repo-type repo-config)))

(defmethod resolve-repo :git [ctx repo-config]
  (clone! (:repo-name ctx) (:base-path ctx) repo-config))

(defmethod resolve-repo :filesystem [_ repo-config]
  (some-> repo-config :config :path io/file .getAbsolutePath))

(def os-name->os {"Linux"    ["linux"]
                  "Mac OS X" ["osx" "darwin"]})

(def os-arch->arch {"amd64"   ["x86_64" "amd64"]
                    "x86_64"  ["x86_64" "amd64"]
                    "aarch64" ["aarch_64" "arm64"]})


(defn get-prop [env prop-name]
  (if-let [v (get env prop-name)]
    v
    (throw (ex-info "unknown prop" {:prop-name prop-name}))))


(defn- get-platform [env]
  (let [raw-os-name   (get env "os.name")
        raw-os-arch   (get env "os.arch")
        os-variants   (get os-name->os raw-os-name)
        arch-variants (get os-arch->arch raw-os-arch)]
    (when (or (nil? os-variants) (nil? arch-variants))
      (throw (ex-info
               "\nPlatform is not currently supported.\n
Please open an issue at https://github.com/AppsFlyer/lein-protodeps/issues
and include this full error message to add support for your platform."
               {:os.name raw-os-name
                :os.arch raw-os-arch})))
    ; The first variant matches protoc's release naming. Plugin downloads try every variant.
    {:os-name          (first os-variants)
     :os-arch          (first arch-variants)
     :os-name-variants os-variants
     :os-arch-variants arch-variants}))

(defn- get-protoc-release [{:keys [semver os-name os-arch]}]
  (str/join "-" ["protoc" semver os-name os-arch]))

(def ^:private grpc-plugin-executable-name "protoc-gen-grpc-java")
(def ^:private grpc-output-directive "grpc-java_out")


(defn set-executable-permissions!
  "Make path readable, writable and executable by its owner only."
  [path]
  (let [permissions (HashSet.)]
    (.add permissions PosixFilePermission/OWNER_EXECUTE)
    (.add permissions PosixFilePermission/OWNER_READ)
    (.add permissions PosixFilePermission/OWNER_WRITE)
    (Files/setPosixFilePermissions (.toPath (io/file path))
                                   permissions)))


(defn download-protoc! [url dst]
  (println "protodeps: Downloading protoc from" url "...")
  (with-open [inp (ZipInputStream. (io/input-stream url))]
    (archive/unzip! inp dst)))

(defn- open-url
  "Open url for reading. Throws an ex-info naming the url when nothing is found there."
  ^InputStream [url]
  (try
    (io/input-stream url)
    (catch FileNotFoundException e
      (throw (ex-info (str "URL not found: " url)
                      {:url url}
                      e)))))

(defn- try-download-plugin!
  "Download a plugin from a single URL to plugin-file. Returns nil on success, or the error of a failed attempt."
  [plugin-name url plugin-file]
  (try
    (println "protodeps: Trying to download" plugin-name "from" url "...")
    (with-open [input (open-url url)]
      (archive/extract! (archive/download-format url) input plugin-name plugin-file))
    (println "protodeps: Downloaded" plugin-name "from" url)
    nil
    (catch Exception e
      (log/print-warning "Failed to download/extract from" url ":" (ex-message e))
      e)))

(defn- download-plugin!
  "Try downloading plugin from multiple URL variants until one succeeds.
   Throws an exception if all URLs fail."
  [plugin-name urls plugin-file]
  (loop [[url & remaining] urls
         last-error nil]
    (if-not url
      (throw (ex-info "Failed to download plugin from any URL"
                      {:plugin plugin-name
                       :urls   urls}
                      last-error))
      (when-let [error (try-download-plugin! plugin-name url plugin-file)]
        (recur remaining error)))))


(defn run-protoc-and-report! [protoc-path opts]
  (let [{:keys [out err]} (run-sh! protoc-path opts)]
    (when-not (str/blank? err)
      (log/print-err err))
    (when-not (str/blank? out)
      (println out))))


(def protoc-release-tpl "https://github.com/protocolbuffers/protobuf/releases/download/v${:semver}/protoc-${:semver}-${:os-name}-${:os-arch}.zip")


(def new-protoc-release-tpl "https://github.com/protocolbuffers/protobuf/releases/download/v${:minor}.${:patch}/protoc-${:minor}.${:patch}-${:os-name}-${:os-arch}.zip")


(def grpc-release-tpl "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${:version}/protoc-gen-grpc-java-${:version}-${:os-name}-${:os-arch}.exe")


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
(def ^:private plugins-install-dir "plugins-installations")

(defn init-rc-dir! []
  (let [home (append-dir (get-prop (System/getProperties) "user.home") ".lein-protodeps")]
    (mkdir! home)
    (mkdir! (append-dir home protoc-install-dir))
    (mkdir! (append-dir home plugins-install-dir))
    home))

(defn discover-files [git-repo-path dep-path]
  (filterv
    (fn [^File file]
      (and (not (.isDirectory file))
           (str/ends-with? (.getName file) ".proto")))
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
    (let [chunks  (partition-all (int (/ (count c) level)) c)
          ;; parallelism is capped by number of cores
          results (pmap f chunks)]
      (transduce (map identity) combine-f results))))

(defn expand-dependencies [parallelism protoc-path proto-paths proto-files]
  (parallelize
    parallelism
    proto-files
    set/union
    (fn [proto-files]
      (loop [seen-files (set proto-files)
             [f & r] proto-files]
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
  (if (str/ends-with? s suffix)
    (subs s 0 (- (count s) (count suffix)))
    s))

(def strip-trailing-slash (partial strip-suffix "/"))

(defn validate-output-path [output-path project]
  (when-not output-path
    (throw (ex-info "output path not defined" {})))
  (when-not (some (fn [sp]
                    (str/ends-with? (strip-trailing-slash sp)
                                    (strip-trailing-slash output-path)))
                  (:java-source-paths project))
    (log/print-warning "output-path" output-path "not found in :java-source-paths")))

(defn cleanup-dir! [^Path path]
  (fs/try-delete-path! path))


(defn- add-plugin-opts
  "Add protoc options for a single plugin.
   protoc pairs `--X_out` with the plugin named `protoc-gen-X`, so the plugin is passed under that name.
   Options are passed as `--X_opt`, which protoc adds to the plugin's parameters."
  [protoc-opts output-path {:keys [plugin-path output-directive options]}]
  (let [plugin-id (strip-suffix "_out" output-directive)]
    (cond-> (-> protoc-opts
                (conj (long-opt "plugin" (str "protoc-gen-" plugin-id "=" plugin-path)))
                (conj (long-opt output-directive output-path)))
            options (conj (long-opt (str plugin-id "_opt") options)))))

(defn protoc-opts
  "Build protoc command options with support for multiple plugins.
   plugins should be a vector of maps with :plugin-path, :output-directive, and optional :options."
  [proto-paths output-path plugins ^File proto-file]
  (let [base-opts (with-proto-paths [(long-opt "java_out" output-path)] proto-paths)]
    (-> (reduce (fn [opts plugin]
                  (add-plugin-opts opts output-path plugin))
                base-opts
                plugins)
        (conj (.getAbsolutePath proto-file)))))

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
        protoc          (append-dir protoc-installs protoc-release "bin" "protoc")]
    (when-not (.exists ^File (io/file protoc))
      (let [protoc-zip-url (interpolate proto-version (protoc-release-template config proto-version))]
        (download-protoc! protoc-zip-url (append-dir protoc-installs protoc-release)))
      (set-executable-permissions! protoc))
    protoc))


(defn- generate-plugin-urls
  "Generate all URL variants by combining os-name and os-arch variants."
  [url-template platform plugin-version]
  (let [os-variants   (:os-name-variants platform)
        arch-variants (:os-arch-variants platform)]
    (distinct
      (for [os-name os-variants
            os-arch arch-variants]
        (interpolate (merge platform
                            (version-parts plugin-version)
                            {:version plugin-version
                             :semver  plugin-version          ; For :grpc-exe-url-template values that use ${:semver}
                             :os-name os-name
                             :os-arch os-arch})
                     url-template)))))

(defn- get-plugin!
  "Download and install a protoc plugin if not already present.
   plugin-config is a validated :plugins entry. Uses its :name, :version and :url-template."
  [home-dir platform plugin-config]
  (let [plugin-name      (:name plugin-config)
        plugin-version   (:version plugin-config)
        install-base-dir (append-dir home-dir plugins-install-dir plugin-name)
        plugin-dir       (append-dir install-base-dir plugin-version)
        plugin-path      (append-dir plugin-dir plugin-name)]
    (when-not (fs/regular-file? plugin-path)
      (mkdir! plugin-dir)
      ; archive/extract! moves a download into place only on success,
      ; so a failed or partial download never ends up at plugin-path.
      (download-plugin! plugin-name
                        (generate-plugin-urls (:url-template plugin-config) platform plugin-version)
                        plugin-path))
    ; Only chmod when needed, so a cached plugin in a read-only home dir still works
    (when-not (.canExecute (io/file plugin-path))
      (set-executable-permissions! plugin-path))
    plugin-path))

(defn- merge-legacy-grpc-config
  "Add a gRPC plugin entry to configured-plugins when the legacy :compile-grpc? is true.
   A gRPC entry in :plugins takes precedence over :compile-grpc?."
  [configured-plugins config grpc-version]
  (cond
    (not (:compile-grpc? config))
    configured-plugins

    (some #(= grpc-output-directive (:output-directive %)) configured-plugins)
    (do
      (log/print-warning ":compile-grpc? is true but :plugins already has a grpc-java_out plugin. Using the :plugins entry.")
      configured-plugins)

    (nil? grpc-version)
    (throw (ex-info ":compile-grpc? is true but :grpc-version is not set"
                    {:compile-grpc? true}))

    :else
    (cons {:name             grpc-plugin-executable-name
           :version          (:semver grpc-version)
           :url-template     (or (:grpc-exe-url-template config)
                                 grpc-release-tpl)
           :output-directive grpc-output-directive}
          configured-plugins)))

(defn generate-files! [opts config]
  (let [base-temp-path (create-temp-dir!)
        keep-tmp?      (true? (:keep-tmp-dir opts))]
    (try
      (let [home-dir           (init-rc-dir!)
            parallelism        {:level          (:parallelism opts)
                                :min-chunk-size 128}
            repos-config       (:repos config)
            output-path        (:output-path config)
            ctx                {:base-path base-temp-path}
            env                (System/getProperties)
            platform           (get-platform env)
            proto-version      (merge platform (parse-semver (:proto-version config)))
            grpc-version       (when (:grpc-version config)
                                 (merge platform (parse-semver (:grpc-version config))))
            ; Validate :plugins, then the merged list, which also covers the gRPC entry from :compile-grpc?
            plugin-configs     (-> (spec/validate-plugins! (or (:plugins config) []))
                                   (merge-legacy-grpc-config config grpc-version)
                                   (spec/validate-plugins!))
            protoc             (get-protoc! home-dir config proto-version)
            plugins            (mapv (fn [plugin-config]
                                       (let [plugin-path (get-plugin! home-dir platform plugin-config)]
                                         {:plugin-path      plugin-path
                                          :output-directive (:output-directive plugin-config)
                                          :options          (:options plugin-config)}))
                                     plugin-configs)
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
        (mkdir! output-path)
        (verbose-prn "config: %s" config)
        (verbose-prn "paths: %s" {:protoc  protoc
                                  :plugins (mapv :plugin-path plugins)})
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
                              set/union
                              (:dependencies repo))]
            (verbose-prn "files: %s" (mapv #(.getName ^File %) proto-files))
            (when (empty? proto-files)
              (log/print-warning "could not find any .proto files under" repo-id))
            (parallelize
              parallelism
              proto-files
              (constantly nil)
              (fn [proto-files]
                (doseq [^File proto-file proto-files]
                  (let [protoc-opts-args (protoc-opts
                                           proto-paths
                                           output-path
                                           plugins
                                           proto-file)]
                    (println "compiling" (.getName proto-file))
                    (run-protoc-and-report! protoc protoc-opts-args))))))))

      (finally
        (if keep-tmp?
          (println "generated" base-temp-path)
          (cleanup-dir! base-temp-path))))))

(defn generate-files*!
  "Generate protoc & gRPC stubs according to the `:lein-protodeps` configuration in `project.clj`"
  [opts project]
  (let [config      (:lein-protodeps project)
        output-path (:output-path config)]
    (if (nil? config)
      (log/print-warning "No :lein-protodeps configuration found in project.clj")
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
        (log/print-warning err))
      :else
      (let [mode (first arguments)]
        (case mode
          "generate" (generate-files*! options project)
          (lein/warn "Unknown task" mode))))))

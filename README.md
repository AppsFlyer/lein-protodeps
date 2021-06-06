# lein-protodeps

A Leiningen plugin to automate compilation of Protobuf and gRPC stubs.

This plugin allows to define your project's protobuf dependencies, by stating
their source location. These locations can currently point to a git repository, or a local filesystem.

When run, the plugin will resolve these locations and compile the desired `.proto` files (and their dependencies)
using the correct protoc compiler and gRPC plugin, according to the versions specified
in your project's configuration. The plugin will automatically download the correct versions if they
are not already installed.

## Usage

Put `[com.appsflyer/lein-protodeps "1.0.1"]` into the `:plugins` vector of your project.clj.

Once installed, run `lein protodeps generate` to run the plugin.

The plugin requires some configuration in your `project.clj` in order to run.

An example configuration:

```clj
(def proto-version "3.12.4") ;; protobuf version -- should be used when declaring protobuf dependencies
(def grpc-version "1.30.2")  ;; gRPC version -- should be used when declaring gRPC dependencies

(defproject my-cool-project "0.1.0"
  ...
  ...
  ;; plugin configuration
  :lein-protodeps {:output-path   "src/java/generated" ;; where to place the generated files? Should reside within your `java-source-paths`
                   :proto-version ~proto-version
                   :grpc-version  ~grpc-version
                   :compile-grpc? true ;; whether to activate the gRPC plugin during the stub generation process
                   ;; Repositories configuration. Each entry in this map is an entry mapping a logical repository name
                   ;; to its configuration.
                   :repos         {:af-schemas {:repo-type :git ;; a git repo
                                                :config   {:clone-url   "git@localhost:test/repo.git" ;; url to clone from
                                                           ;; rev - can point to a commit hash, tag name or branch name. The repo will be cloned
                                                           ;; to this version of itself. If unspecified, will point to origin's HEAD (i.e, master).
                                                           :rev         "mybranch"}
                                                ;; a vector of proto-paths relative to the directory root. May use an empty string if the root
                                                ;; level is a proto path in itself.
                                                :proto-paths ["products"]
                                                ;; a vector of dependencies which control what stubs to compile. Each dependency vector
                                                ;; contains a directory under one of the proto paths. All files in this directory and their
                                                ;; dependencies will be compiled.
                                                :dependencies [products/events
                                                               products/adrevenue]}

                                   :some-other-schemas {:repo-type    :filesystem ;; read files directly from filesystem instead of git.
                                                        :config       {:path "../schemas"} ;; path, either relative or absolute
                                                        :proto-paths  ["products"]
                                                        :dependencies [products/foo
                                                                       products/bar]}}}
```

## Cross-repository compilation

`lein-protodeps` also supports cross-repo compilation, for example when a `.proto` file dependency in one repo imports a file
residing in a different repo.

To enable cross-repo compilation, simply add both repos to the `:repos` config map.

## Git HTTP authentication

To use HTTP authentication using username and password, provide them in the clone url: `"https://<myuser>:<mypass>@github.com/whatever/cool_repo.git"`

It is recommended to use environment variables rather than hardcoding their values in plaintext. Environment variables are accessible via the `${:env/<var_name>}` interpolation syntax, which allows us to write the former as: `"https://${:env/GIT_USERNAME}:${:env/GIT_PASSWORD}@github.com/whatever/cool_repo.git"`.

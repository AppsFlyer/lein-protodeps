# lein-protodeps

[![Clojars Project](https://img.shields.io/clojars/v/com.appsflyer/lein-protodeps.svg)](https://clojars.org/com.appsflyer/lein-protodeps)

A Leiningen plugin to automate compilation of Protobuf and gRPC stubs.

This plugin allows to define your project's protobuf dependencies, by stating
their source location. These locations can currently point to a git repository, or a local filesystem.

When run, the plugin will resolve these locations and compile the desired `.proto` files (and their dependencies)
using the correct protoc compiler and gRPC plugin, according to the versions specified
in your project's configuration. The plugin will automatically download the correct versions if they
are not already installed.

<!-- markdown-toc start - Don't edit this section. Run M-x markdown-toc-refresh-toc -->
**Table of Contents**

- [Usage](#usage)
- [Cross-repository compilation](#cross-repository-compilation)
- [Git HTTP authentication](#git-http-authentication)
- [protoc and gRPC binaries retrieval](#protoc-and-grpc-binaries-retrieval)
- [Configuration Reference](#configuration-reference)
    - [Plugin options](#plugin-configuration-options)
    - [Repo Options](#repo-options)
    - [Proto options](#proto-options)

<!-- markdown-toc end -->



## Usage

Put `[com.appsflyer/lein-protodeps "1.0.3"]` into the `:plugins` vector of your project.clj.

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

## protoc and gRPC binaries retrieval

The plugin will download the protoc and gRPC plugin binaries according to the versions set in `:proto-version` and `:grpc-version`, respectively, and install them under 
`~/.lein-protodeps/`. 

By default, the plugin will use `https://github.com/protocolbuffers/protobuf/releases/download/` for protoc and `https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/` for gRPC.
However, it is possible to override these to other endpoints by setting `:protoc-zip-url-template` and `:grpc-exe-url-template` options in the plugin configuration.

The values of these options are URL templates that will be interpolated at runtime with the following variables to produce download URLs:

* `:os-name` host OS (i.e, `linux`, `osx`)
* `:os-arch` host architecture (i.e, `x86_64`, `aarch64`)
* `:semver` version string as defined in `:protoc-version` or `:grpc-version`
* `:major` major part of `:semver`
* `:minor` minor part of `:semver`
* `:patch` patch part of `:semver`

For example, to override the gRPC URL you may set `:grpc-exe-url-template` to `https://some-other-place.com/artifacts/grpc/${:semver}/protoc-gen-grpc-java-${:semver}-${:os-name}-${:os-arch}`. Note that
currently this feature does not support any method of authentication, in case your endpoints require it.


## Configuration Reference

#### Plugin Configuration Options
| Key                        | Type    | Req?     | Notes                                                                                                                                                    |
|----------------------------|---------|----------|----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:output-path`             | string  | required | Path to which to compile stubs. This usually needs to be under `:java-source-paths`                                                                      |
| `:proto-version`           | string  | required | Version of protoc to use                                                                                                                                 |
| `:grpc-version`            | string  | optional | Version of gRPC plugin to use. Required if `:compile-grpc?` is `true`                                                                                    |
| `:compile-grpc?`           | boolean | optional | Whether to compile gRPC stubs. Defaults to `false`                                                                                                       |
| `:protoc-zip-url-template` | string  | optional | URL template from which to retrieve protoc's zip release (if needed). See also [protoc and gRPC binaries retrieval](#protoc-and-grpc-binaries-retrieval) |
| `:grpc-exe-url-template`  | string  | optional | URL template from which to retrieve gRPC's executable release (if needed). See also [protoc and gRPC binaries retrieval](#protoc-and-grpc-binaries-retrieval)   |


#### Repo Options
| Key                              | Type                 | Req?                            | Notes                                                                                                                                     |
|----------------------------------|----------------------|---------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `<:repo-name>.:repo-type`        | `:git` `:filesystem` | required                        |                                                                                                                                           |
| `<:repo-name>.:config.:clone-url` | string               | required (for git repos)        | Either SSH or HTTP endpoints are supported, see also [Git HTTP authentication](#git-http-authentication)                                  |
| `<:repo-name>.:config.:rev`      | string               | optional (for git repos)        | commit hash/tag name/branch name. Not specifying a rev will default to cloning the main branch (it is generally encouraged to use a fixed version) |
| `<:repo-name>.:config.:path`     | string               | required (for filesystem repos) | path to directory containing files (absolute or relative to project directory)                                                            |

#### Proto options
| Key                          | Type              | Req?     | Notes                                                                                                                                                                                                            |
|------------------------------|-------------------|----------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `<:repo-name>.:proto-paths`  | vector of strings | required | Relative proto paths to the directory root (where `protoc` will search for imports, see `protoc --help` for more information)                                                                                    |
| `<:repo-name>.:dependencies` | vector of symbols | optional | List of paths which contain files to compile to stubs. Each of these paths needs to be prefixed with one of the proto paths defined under `:proto-paths`, see [example](#usage) above. If unspecified or empty, nothing in this repo will be compiled, but it may still be used for finding imports required by other repos. See also [cross-repository compilation](#cross-repository-compilation)                |




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
- [Protoc Plugins](#protoc-plugins)
- [protoc and gRPC binaries retrieval](#protoc-and-grpc-binaries-retrieval)
- [Configuration Reference](#configuration-reference)
    - [Plugin options](#plugin-configuration-options)
    - [Repo Options](#repo-options)
    - [Proto options](#proto-options)

<!-- markdown-toc end -->



## Usage

Put `[com.appsflyer/lein-protodeps "1.1.0"]` into the `:plugins` vector of your project.clj. Requires Leiningen 2.9.0 or newer.

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
                   ; Optional: protoc plugins for validation, documentation, etc.
                   :plugins [{:name "protoc-gen-validate"
                             :version "1.3.0"
                             :url-template "https://github.com/bufbuild/protoc-gen-validate/releases/download/v${:version}/protoc-gen-validate_${:version}_${:os-name}_${:os-arch}.tar.gz"
                             :output-directive "validate_out"
                             :options "lang=java"}]
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

## Protoc Plugins

`lein-protodeps` supports protoc plugins (e.g., `protoc-gen-validate`, `protoc-gen-doc`) in addition to gRPC.

### Using Protoc Plugins

Add a `:plugins` vector to your configuration:

```clj
:lein-protodeps {:output-path   "src/java/generated"
                 :proto-version "3.25.5"
                 :grpc-version  "1.68.1"
                 :compile-grpc? true  ; gRPC via legacy config
                 :plugins [{:name "protoc-gen-validate"
                           :version "1.3.0"
                           :url-template "https://github.com/bufbuild/protoc-gen-validate/releases/download/v${:version}/protoc-gen-validate_${:version}_${:os-name}_${:os-arch}.tar.gz"
                           :output-directive "validate_out"
                           :options "lang=java"}]
                 :repos {...}}
```

### Plugin Configuration Keys

- `:name` (required): the plugin binary's file name (e.g., `"protoc-gen-validate"`). It names the install directory, and for `.tar.gz`, `.tgz` and `.zip` downloads it must match the binary's path at the root of the archive (`name`, or `./name` in a tarball). It cannot be `.`, start with `-`, or contain `/`, `\` or `..`.
- `:version` (required): the plugin version to download. It cannot be `.`, start with `-`, or contain `/`, `\` or `..`.
- `:url-template` (required): URL template for downloading the plugin binary. Interpolation variables:
  - `${:version}`: plugin version (`${:semver}` also works for backward compatibility)
  - `${:major}`, `${:minor}`, `${:patch}`: version components, when the version has the form `x.y.z`
  - `${:os-name}`: host OS name (tries `osx`/`darwin` for macOS, `linux` for Linux)
  - `${:os-arch}`: host architecture (tries `x86_64`/`amd64` for x64, `aarch_64`/`arm64` for ARM64)
- `:output-directive` (required): the protoc output flag name without `--`, ending in `_out` (e.g., `"validate_out"` becomes `--validate_out`). protoc pairs `--X_out` with a plugin named `protoc-gen-X`, so the downloaded binary is passed to protoc as `--plugin=protoc-gen-X=<path>`.
- `:options` (optional): plugin parameters as a string, passed as is to `--X_opt=...` for an `X_out` directive (e.g., `"lang=java"` becomes `--validate_opt=lang=java`, and `"markdown,docs.md"` becomes `--doc_opt=markdown,docs.md`). Requires protoc 3.2.0 or newer.

The `:plugins` vector is validated before anything is downloaded. A missing required key, a `:name` or `:version` that breaks the rule above, an `:output-directive` that does not end in `_out`, `:options` that is present but blank or not a string, or two plugins with the same `:output-directive` fail with a message that names the problem.

The library tries multiple naming conventions automatically. On macOS ARM64, it tries every combination of `osx`/`darwin` and `aarch_64`/`arm64` until one succeeds. Each distinct URL is tried once.

### Plugin Installation

Plugins are automatically downloaded and installed to `~/.lein-protodeps/plugins-installations/<plugin-name>/<version>/` when first used.

**Supported formats**, chosen by the URL path without its query string or fragment, ignoring case:
- `.tar.gz` and `.tgz` archives (extracts the plugin binary from the tarball, using the `tar` command, which must be on the `PATH`)
- `.zip` archives (extracts the plugin binary from the zip)
- `.gz` compressed files (decompresses to binary)
- Plain executables (downloads directly)

The result must be a regular file. A directory or symlink with the plugin's name in an archive fails the download.

Execute permission is set on a plugin binary whenever it is missing, including on a previously downloaded one.

### Example: Using protoc-gen-validate

```clj
:plugins [{:name "protoc-gen-validate"
          :version "1.3.0"
          :url-template "https://github.com/bufbuild/protoc-gen-validate/releases/download/v${:version}/protoc-gen-validate_${:version}_${:os-name}_${:os-arch}.tar.gz"
          :output-directive "validate_out"
          :options "lang=java"}]
```

On macOS ARM64, the library tries these URLs in order:
1. `protoc-gen-validate_1.3.0_osx_aarch_64.tar.gz` (protoc style, not published)
2. `protoc-gen-validate_1.3.0_osx_arm64.tar.gz` (not published)
3. `protoc-gen-validate_1.3.0_darwin_aarch_64.tar.gz` (not published)
4. `protoc-gen-validate_1.3.0_darwin_arm64.tar.gz` (Go style, downloaded)

A download is written to a staging directory and moved into place only when it completes, so a failed or partial download is retried on the next run.

Generated protoc command:
```bash
protoc --proto_path=... \
       --java_out=output/ \
       --plugin=protoc-gen-validate=~/.lein-protodeps/plugins-installations/protoc-gen-validate/1.3.0/protoc-gen-validate \
       --validate_out=output/ \
       --validate_opt=lang=java \
       file.proto
```

### Using Multiple Plugins

You can configure multiple plugins together. For example, using gRPC, validation, and documentation:

```clj
:lein-protodeps {:output-path   "src/java/generated"
                 :proto-version "3.25.5"
                 :grpc-version  "1.68.1"
                 :compile-grpc? true  ; Adds gRPC plugin
                 :plugins [{:name "protoc-gen-validate"
                           :version "1.3.0"
                           :url-template "https://github.com/bufbuild/protoc-gen-validate/releases/download/v${:version}/protoc-gen-validate_${:version}_${:os-name}_${:os-arch}.tar.gz"
                           :output-directive "validate_out"
                           :options "lang=java"}
                          {:name "protoc-gen-doc"
                           :version "1.5.1"
                           :url-template "https://github.com/pseudomuto/protoc-gen-doc/releases/download/v${:version}/protoc-gen-doc_${:version}_${:os-name}_${:os-arch}.tar.gz"
                           :output-directive "doc_out"
                           :options "markdown,docs.md"}]
                 :repos {...}}
```

**Note:** protoc runs once per proto file. A plugin that writes one combined file, like protoc-gen-doc with `docs.md`, is overwritten on each run and ends up with the last file's output only.

### Backward Compatibility

The `:compile-grpc?` and `:grpc-version` options are syntactic sugar for adding gRPC to the `:plugins` vector. These two configurations are equivalent:

```clj
; Using legacy config (syntactic sugar)
:compile-grpc? true
:grpc-version "1.68.1"

; Equivalent plugin config
:plugins [{:name "protoc-gen-grpc-java"
          :version "1.68.1"
          :url-template "https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/${:version}/protoc-gen-grpc-java-${:version}-${:os-name}-${:os-arch}.exe"
          :output-directive "grpc-java_out"}]
```

You can use both forms together. When `:compile-grpc?` is true, gRPC is added to the plugins list. If `:plugins` already has a `grpc-java_out` entry, that entry is used and `:compile-grpc?` is ignored with a warning.

## protoc and gRPC binaries retrieval

The plugin downloads protoc and gRPC binaries based on `:proto-version` and `:grpc-version`, installing them to `~/.lein-protodeps/`.

Default download locations:
- protoc: `https://github.com/protocolbuffers/protobuf/releases/download/`
- gRPC: `https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/`

Override these by setting `:protoc-zip-url-template` or `:grpc-exe-url-template`.

URL templates support these interpolation variables:

* `${:version}` or `${:semver}`: version string from `:proto-version` or `:grpc-version`
* `${:major}`, `${:minor}`, `${:patch}`: version components
* `${:os-name}`: host OS (`linux`, `osx`). gRPC downloads also try `darwin` on macOS.
* `${:os-arch}`: host architecture (`x86_64`, `aarch_64`). gRPC downloads also try `amd64` and `arm64`.

Example override:
```clj
:grpc-exe-url-template "https://my-mirror.com/grpc/${:version}/protoc-gen-grpc-java-${:version}-${:os-name}-${:os-arch}.exe"
```

**Note:** Authentication is not supported for custom URLs.


## Configuration Reference

#### Plugin Configuration Options
| Key                        | Type           | Req?     | Notes                                                                                                                                                         |
|----------------------------|----------------|----------|---------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `:output-path`             | string         | required | Path to which to compile stubs. This usually needs to be under `:java-source-paths`                                                                           |
| `:proto-version`           | string         | required | Version of protoc to use                                                                                                                                      |
| `:grpc-version`            | string         | optional | Version of gRPC plugin to use. Required if `:compile-grpc?` is `true`                                                                                         |
| `:compile-grpc?`           | boolean        | optional | Whether to compile gRPC stubs. Defaults to `false`                                                                                                            |
| `:plugins`                 | vector of maps | optional | Generic protoc plugins configuration. See [Protoc Plugins](#protoc-plugins) section for details                                                               |
| `:protoc-zip-url-template` | string         | optional | URL template from which to retrieve protoc's zip release (if needed). See also [protoc and gRPC binaries retrieval](#protoc-and-grpc-binaries-retrieval)      |
| `:grpc-exe-url-template`   | string         | optional | URL template from which to retrieve gRPC's executable release (if needed). See also [protoc and gRPC binaries retrieval](#protoc-and-grpc-binaries-retrieval) |


#### Repo Options
| Key                               | Type                 | Req?                            | Notes                                                                                                                                              |
|-----------------------------------|----------------------|---------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| `<:repo-name>.:repo-type`         | `:git` `:filesystem` | required                        |                                                                                                                                                    |
| `<:repo-name>.:config.:clone-url` | string               | required (for git repos)        | Either SSH or HTTP endpoints are supported, see also [Git HTTP authentication](#git-http-authentication)                                           |
| `<:repo-name>.:config.:rev`       | string               | optional (for git repos)        | commit hash/tag name/branch name. Not specifying a rev will default to cloning the main branch (it is generally encouraged to use a fixed version) |
| `<:repo-name>.:config.:path`      | string               | required (for filesystem repos) | path to directory containing files (absolute or relative to project directory)                                                                     |

#### Proto options
| Key                          | Type              | Req?     | Notes                                                                                                                                                                                                                                                                                                                                                                                               |
|------------------------------|-------------------|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `<:repo-name>.:proto-paths`  | vector of strings | required | Relative proto paths to the directory root (where `protoc` will search for imports, see `protoc --help` for more information)                                                                                                                                                                                                                                                                       |
| `<:repo-name>.:dependencies` | vector of symbols | optional | List of paths which contain files to compile to stubs. Each of these paths needs to be prefixed with one of the proto paths defined under `:proto-paths`, see [example](#usage) above. If unspecified or empty, nothing in this repo will be compiled, but it may still be used for finding imports required by other repos. See also [cross-repository compilation](#cross-repository-compilation) |

# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.2.0] - 2026-11-26

### Added
* Generic protoc plugin support via the `:plugins` configuration option
* Plugins are downloaded from configurable URL templates
* Plugin `:options` string, passed to the plugin as `--X_opt` (requires protoc 3.2.0 or newer)
* Multiple plugins can run in the same build
* Plugin downloads can be `.tar.gz`, `.tgz`, `.zip`, `.gz` or a plain executable, detected from the URL path. The extracted plugin must be a regular file.
* `:plugins` is validated with `clojure.spec` before anything is downloaded, and the error names the missing or malformed key. `:name` and `:version` cannot be `.`, start with `-`, or contain `/`, `\` or `..`. Two plugins cannot share an `:output-directive`. The gRPC entry from `:compile-grpc?` is validated too.
* `${:major}`, `${:minor}` and `${:patch}` in plugin URL templates for `x.y.z` versions
* When every plugin URL fails, the error's cause is the error of the last attempt

### Changed
* Legacy gRPC configuration (`:compile-grpc?`, `:grpc-version`, `:grpc-exe-url-template`) is converted to a plugin entry internally. A `grpc-java_out` entry in `:plugins` takes precedence over `:compile-grpc?`. Otherwise `:compile-grpc?` without `:grpc-version` fails with a clear error, instead of a failed download from a URL with an empty version.
* A custom `:grpc-exe-url-template` ending in `.tar.gz`, `.tgz`, `.zip` or `.gz` is now extracted like any plugin download, so the archive must contain `protoc-gen-grpc-java`.
* All plugins, including gRPC, install to `~/.lein-protodeps/plugins-installations/<plugin-name>/<version>/`. `~/.lein-protodeps/grpc-installations/` is no longer read, so gRPC is downloaded again once after upgrading.
* Plugin downloads try multiple platform naming conventions (`osx`/`darwin`, `x86_64`/`amd64`, `aarch_64`/`arm64`) until one succeeds
* Plugins are passed to protoc as `--plugin=protoc-gen-X=<path>`, where `X_out` is the plugin's `:output-directive`
* `protoc-opts` takes a vector of plugins instead of the `compile-grpc?` flag and gRPC plugin path. Code that calls it directly needs to be updated.
* `os-name->os` and `os-arch->arch` map to vectors of naming variants instead of a single string
* `set-protoc-permissions!` is renamed to `set-executable-permissions!`, since it also applies to plugins
* Requires Leiningen 2.9.0 or newer, whose bundled Clojure includes `clojure.spec`

### Fixed
* Plugin binaries, including gRPC, get execute permission whenever it is missing, not only right after download
* A failed or partial plugin download, including gRPC, is no longer cached as the plugin binary. The next run downloads it again.

### Removed
* The macOS `aarch64` to `x86_64` protoc fallback. It no longer matched any platform once `aarch64` was mapped to `aarch_64`.

## [1.1.1] - 2026-11-26

### Added
* `leiningen.protodeps.archive` namespace that picks a download format from a URL (`.tar.gz`, `.tgz`, `.zip`, `.gz` or plain, ignoring the query string, fragment and case) and extracts a single regular file from it. The file is extracted into a staging dir next to the target and moved into place only on success, so a failed extraction never changes an existing target. Tarballs can store the file as `name` or `./name`, and are extracted with the system `tar`. Staging cleanup only warns on failure.
* `leiningen.protodeps.fs/move-into-place!` atomically moves a file to a target, replacing a file, symlink or directory there.

### Changed
* `unzip!` moved to `leiningen.protodeps.archive`. It skips directory entries by their type, so a zip whose entries record their size after the data is extracted in full.

## [1.1.0] - 2026-11-26

### Added
* `${:version}` can be used in `:protoc-zip-url-template` and `:grpc-exe-url-template`, as an alias of `${:semver}`.

### Changed
* The default gRPC URL template uses `${:version}`.

### Fixed
* The README names the `:proto-version` key correctly in the URL template variables.

## [1.0.14] - 2026-11-26

### Fixed
* Temp directory cleanup no longer follows symlinks, so a symlink in a cloned repo cannot cause files outside the temp directory to be deleted.
* Temp directory cleanup is best-effort. It deletes everything it can and reports each path it cannot delete as a warning. Cleanup never fails the build or hides the error that ended it.
* The temp directory is also deleted when setup fails, for example when a clone or a download throws.

### Changed
* `print-err` and `print-warning` moved to a new `leiningen.protodeps.log` namespace.

## [1.0.13] - 2026-11-26

### Changed
* `leiningen.protodeps` and its tests enable `*warn-on-reflection*`, and the reflective calls it reported are type hinted.
* `create-temp-dir!` and `mkdir!` have return type hints, and the file attributes array in `create-temp-dir!` is hinted.
* The redundant `ZipEntry` hint in `unzip!` is removed.

## [1.0.12] - 2026-11-26

### Changed
* Source and tests use the `str` and `set` aliases and imported class names instead of fully-qualified ones, and are re-indented. A committed `.clj-kondo/config.edn` enforces consistent aliases.
* Removed the `(comment ...)` example config from `src/leiningen/protodeps.clj`.
* README configuration tables are realigned.

## [1.0.11] - 2026-11-26

### Changed
* CI lints and runs the unit tests in a single `test` job instead of a separate `build` job before it.
* CI workflows default to read-only repository access, and only the Release `deploy` job can write.

## [1.0.10] - 2026-11-26

### Changed
* CI installs clj-kondo `2026.10.26` with `DeLaGuardo/setup-clojure@13` instead of `DeLaGuardo/setup-clj-kondo@master`, which runs on the deprecated Node.js 20.
* The Release workflow creates a GitHub release with generated notes for the version it tags.

## [1.0.9] - 2026-11-26

### Changed
* Renamed the `:af-proto` repo key to `:my-protos` and the `products/events` dependency to `"my_products/protos"` with `:proto-paths` `["my_products"]` in the `(comment ...)` example in `src/leiningen/protodeps.clj`.
* Clojars deploys from PRs and releases run one at a time.
* CI uses `actions/checkout@v7`, `actions/setup-java@v6` and `actions/cache@v6`.
* CI installs the latest Leiningen with `DeLaGuardo/setup-clojure@13` instead of downloading the `lein` script.

## [1.0.7] - 2025-08-07

### Changed
* No functional changes. Re-release of 1.0.6.

## [1.0.6] - 2025-08-07

### Fixed
* Fix aarch64 architecture mapping for protoc binary downloads. The `os-arch->arch` map now correctly maps `"aarch64"` to `"aarch_64"` to match the actual protoc binary naming convention.

### Added
* Unit tests for aarch64 architecture fix to ensure correct URL generation for protoc downloads.
* GitHub Actions workflows for CI/CD:
  * `ci_pr.yml` - Runs tests and deploys SNAPSHOT versions on pull requests
  * `ci_master.yml` - Runs tests and handles release deployments on master branch merges
* Modern CI configuration using latest Ubuntu runners and updated action versions.

### Changed
* Updated GitHub Actions workflows to use:
  * `ubuntu-latest` runners
  * `actions/checkout@v4`
  * `actions/setup-java@v4` 
  * `actions/cache@v4`
  * `clj-kondo` version `2025.07.28`
* Optimized Leiningen installation in CI to only run where needed.
* Added `.java-version` to `.gitignore`.

[Unreleased]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.2.0...HEAD
[1.2.0]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.14...v1.1.0
[1.0.14]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.13...v1.0.14
[1.0.13]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.12...v1.0.13
[1.0.12]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.11...v1.0.12
[1.0.11]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.10...v1.0.11
[1.0.10]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.9...v1.0.10
[1.0.9]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.7...v1.0.9
[1.0.7]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.5...v1.0.6

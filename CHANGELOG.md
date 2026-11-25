# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2026-11-25

### Added
* Generic protoc plugin support via the `:plugins` configuration option
* Plugins are downloaded from configurable URL templates
* Plugin `:options`, as a `key=value` map or a string, passed to the plugin as `--X_opt` (requires protoc 3.2.0 or newer)
* Multiple plugins can run in the same build
* `:plugins` is validated with `clojure.spec` before anything is downloaded, and the error names the missing or malformed key
* `${:version}` can be used in `:protoc-zip-url-template`
* Tests for plugin download, URL variant fallback, validation and protoc option building

### Changed
* Legacy gRPC configuration (`:compile-grpc?`, `:grpc-version`, `:grpc-exe-url-template`) is converted to a plugin entry internally. A `grpc-java_out` entry in `:plugins` takes precedence over `:compile-grpc?`.
* All plugins, including gRPC, install to `~/.lein-protodeps/plugins-installations/<plugin-name>/<version>/`. `~/.lein-protodeps/grpc-installations/` is no longer read, so gRPC is downloaded again once after upgrading.
* Plugin downloads try multiple platform naming conventions (`osx`/`darwin`, `aarch_64`/`arm64`) until one succeeds
* Plugins are passed to protoc as `--plugin=protoc-gen-X=<path>`, where `X_out` is the plugin's `:output-directive`
* `protoc-opts` takes a vector of plugins instead of the `compile-grpc?` flag and gRPC plugin path. Code that calls it directly needs to be updated.
* `os-name->os` and `os-arch->arch` map to vectors of naming variants instead of a single string
* Requires Leiningen 2.9.0 or newer

### Fixed
* Plugin binaries always have execute permissions set, even when previously downloaded
* Plugin downloads handle `.tar.gz`, `.gz`, and plain executable formats
* A failed or partial plugin download is no longer cached as the plugin binary. The next run downloads it again.

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

[Unreleased]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.7...v1.1.0
[1.0.7]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.5...v1.0.6

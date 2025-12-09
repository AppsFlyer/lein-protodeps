# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.1.0] - 2025-12-09

### Added
* Generic protoc plugin support via `:plugins` configuration option
* Auto-download plugins from configurable URL templates
* Plugin-specific options support (e.g., `lang=java` for protoc-gen-validate)
* Multiple plugins can run simultaneously
* Test coverage for plugin functionality
* Plugin `:additional-flags` configuration option for plugins requiring extra protoc flags (e.g., `protoc-gen-doc`'s `--doc_opt`)

### Changed
* `protoc-opts` function now accepts a vector of plugins
* Legacy gRPC configuration converted to plugin format internally
* Unified plugin installation directory to `~/.lein-protodeps/plugins-installations/<plugin-name>/<version>/` for all plugins including gRPC
* Plugin downloads try multiple platform naming conventions automatically (`osx`/`darwin`, `aarch_64`/`arm64`) until one succeeds

### Removed
* Removed `get-grpc-plugin!` and `download-grpc-plugin!` functions

### Fixed
* Plugin binaries always have execute permissions set, even when previously downloaded
* Plugin downloads handle `.tar.gz`, `.gz`, and plain executable formats
* Plugin downloads try all platform name variants before failing

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

[1.0.6]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.5...v1.0.6

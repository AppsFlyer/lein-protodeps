# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.11] - 2026-11-26

### Changed
* CI lints and runs the unit tests in a single `test` job instead of a separate `build` job before it.
* CI workflows default to read-only repository access, and only the Release `deploy` job can write.
* Updated the plugin version in the README usage snippet to `1.0.11`.

## [1.0.10] - 2026-11-26

### Changed
* CI installs clj-kondo `2026.10.26` with `DeLaGuardo/setup-clojure@13` instead of `DeLaGuardo/setup-clj-kondo@master`, which runs on the deprecated Node.js 20.
* The Release workflow creates a GitHub release with generated notes for the version it tags.
* Updated the plugin version in the README usage snippet to `1.0.10`.

## [1.0.9] - 2026-11-26

### Changed
* Renamed the `:af-proto` repo key to `:my-protos` and the `products/events` dependency to `"my_products/protos"` with `:proto-paths` `["my_products"]` in the `(comment ...)` example in `src/leiningen/protodeps.clj`.
* Updated the plugin version in the README usage snippet from `1.0.6` to `1.0.7`.
* Clojars deploys from PRs and releases run one at a time.
* CI uses `actions/checkout@v7`, `actions/setup-java@v6` and `actions/cache@v6`.
* CI installs the latest Leiningen with `DeLaGuardo/setup-clojure@13` instead of downloading the `lein` script.
* Updated the plugin version in the README usage snippet to `1.0.9`.

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

[Unreleased]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.11...HEAD
[1.0.11]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.10...v1.0.11
[1.0.10]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.9...v1.0.10
[1.0.9]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.7...v1.0.9
[1.0.7]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/AppsFlyer/lein-protodeps/compare/v1.0.5...v1.0.6

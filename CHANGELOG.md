# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[1.0.6]: https://github.com/AppsFlyer/lein-protodeps/compare/1.0.5...1.0.6

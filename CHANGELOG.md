# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.6] - 2026-03-10

### Changed
- Clean up existing installation on reinstall

## [1.0.5] - 2026-03-06

### Added
- Standalone Android APK with WebView UI, native terminal, and extra keys bar
- Multi-session terminal tab bar with swipe navigation
- Boot auto-start via BootReceiver
- Chromium browser automation support (`scripts/install-chromium.sh`)
- `oa --install` command for installing optional tools independently

### Fixed
- `update-core.sh` syntax error (extra `fi` on line 237)
- sharp image processing with WASM fallback for glibc/bionic boundary

### Changed
- Switch terminal input mode to `TYPE_NULL` for strict terminal behavior

## [1.0.4] - 2025-12-15

### Changed
- Upgrade Node.js to v22.22.0 for FTS5 support (`node:sqlite` static bundle)
- Show version in all update skip and completion messages

### Removed
- oh-my-opencode support (OpenCode uses internal Bun, PATH-based plugins not detected)

### Fixed
- Update version glob picks oldest instead of latest
- Native module build failures during update

## [1.0.3] - 2025-11-20

### Added
- `.gitattributes` for LF line ending enforcement

### Changed
- Bump version to v1.0.3

## [1.0.2] - 2025-10-15

### Added
- Platform-plugin architecture (`platforms/<name>/` structure)
- Shared script library (`scripts/lib.sh`)
- Verification system (`tests/verify-install.sh`)

### Changed
- Refactor install flow into modular scripts
- Separate platform-specific code from infrastructure

## [1.0.1] - 2025-09-01

### Fixed
- Initial bug fixes and stability improvements

## [1.0.0] - 2025-08-15

### Added
- Initial release
- glibc-runner based execution (no proot-distro required)
- One-command installer (`curl | bash`)
- Node.js glibc wrapper for standard Linux binaries on Android
- Path conversion for Termux compatibility
- Optional tools: tmux, code-server, OpenCode, AI CLIs
- Post-install verification

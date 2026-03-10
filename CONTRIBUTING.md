# Contributing to OpenClaw on Android

Thanks for your interest in contributing! This guide will help you get started.

## First-Time Contributors

Welcome — contributions of all sizes are valued. If this is your first contribution:

1. **Find an issue.** Look for issues labeled [`good first issue`](https://github.com/AidanPark/openclaw-android/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) — these are scoped for newcomers.

2. **Pick a scope.** Good first contributions include:
   - Typo and documentation fixes
   - Shell script improvements
   - Bug fixes with clear reproduction steps

3. **Follow the fork → PR workflow** described below.

## Development Setup

### Shell Scripts (installer, updater, patches)

```bash
# Clone the repo
git clone https://github.com/AidanPark/openclaw-android.git
cd openclaw-android

# Validate shell scripts
bash -n install.sh
bash -n update-core.sh
bash -n oa.sh
```

Shell scripts follow POSIX-compatible style with 4-space indentation. See `scripts/lib.sh` for shared conventions.

### Android App

```bash
cd android

# Build APK
./gradlew assembleDebug

# Run lint checks
./gradlew ktlintCheck
./gradlew detekt

# Format code
./gradlew ktlintFormat
```

**Prerequisites**: JDK 21, Android SDK (API 28+), NDK 28+, Node.js 22+ (for WebView UI).

### WebView UI

```bash
cd android/www
npm install
npm run build
```

### Enable Git Hooks

```bash
git config core.hooksPath .githooks
```

This enables the pre-commit hook that runs ktlint and detekt before every commit.

## How to Contribute

### 1. Fork and Clone

```bash
git clone https://github.com/<your-username>/openclaw-android.git
cd openclaw-android
```

### 2. Make Your Changes

All work happens on `main` — we use a single-branch workflow with no prefixes.

### 3. Test Your Changes

- **Shell scripts**: Run `bash -n <script>` to validate syntax
- **Android app**: Run `./gradlew assembleDebug` to verify build
- **Kotlin code**: Run `./gradlew ktlintCheck && ./gradlew detekt`

### 4. Commit

Commit messages use English, imperative style, with no prefix:

```
Fix update-core.sh syntax error
Add multi-session terminal tab bar
Upgrade Node.js to v22.22.0 for FTS5 support
```

- Start with a capital letter, no period at the end
- Keep the subject line under 50 characters
- Use imperative present tense ("Fix", not "Fixed" or "Fixes")

### 5. Open a Pull Request

Open a PR against `main`. Describe:
- What the change does
- Why it's needed
- How to test it

## Project Structure

The project has two main parts:

- **Shell scripts** (root) — Installer, updater, patches, CLI. These run in Termux on Android.
- **Android app** (`android/`) — Kotlin/Android APK with WebView UI and native terminal.

See the [README](README.md) for the full project structure and architecture details.

## Code Style

| Language | Style | Indentation |
|----------|-------|-------------|
| Shell (bash) | POSIX compatible, `scripts/lib.sh` conventions | 4 spaces |
| Kotlin | [Official coding conventions](https://kotlinlang.org/docs/coding-conventions.html) | 4 spaces |
| XML | Standard Android conventions | 2 spaces |
| TypeScript/React | ESLint config in `android/www/` | 2 spaces |

## Key Considerations

When contributing to this project, keep in mind:

- **Termux compatibility** — Scripts must work in Termux's environment (`$PREFIX` paths, no root)
- **glibc boundary** — Node.js runs under glibc-runner while system tools use Bionic libc
- **Path handling** — Standard Linux paths (`/tmp`, `/bin/sh`) must be converted to Termux equivalents
- **Android version range** — The app targets `minSdk 24` (Android 7.0) to `targetSdk 28`
- **Idempotency** — Install and update scripts should be safe to run multiple times

## Reporting Issues

- **Bugs**: Include Android version, device model, Termux version, steps to reproduce
- **Features**: Describe the use case and proposed approach
- **Security**: See [SECURITY.md](SECURITY.md) for responsible disclosure

## License

By contributing, you agree that your contributions will be licensed under the MIT License.

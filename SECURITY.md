# Security Policy

## Supported Versions

| Version | Supported          |
| ------- | ------------------ |
| 1.0.x   | :white_check_mark: |

## Reporting a Vulnerability

**Please do NOT open a public GitHub issue for security vulnerabilities.**

Instead, please report them responsibly:

1. **GitHub**: Use [GitHub Security Advisories](https://github.com/AidanPark/openclaw-android/security/advisories/new)

### What to Include

- Description of the vulnerability
- Steps to reproduce
- Impact assessment
- Suggested fix (if any)

### Response Timeline

- **Acknowledgment**: Within 48 hours
- **Assessment**: Within 1 week
- **Fix**: Within 2 weeks for critical issues

## Security Architecture

OpenClaw on Android runs standard Linux binaries on Android without proot-distro. The security model is shaped by this unique execution environment.

### Execution Isolation

```
┌─────────────────────────────────────────────┐
│ Android Kernel (SELinux enforced)            │
│ ┌─────────────────────────────────────────┐ │
│ │ Termux sandbox (/data/data/com.termux)  │ │
│ │ ┌─────────────────────────────────────┐ │ │
│ │ │ glibc-runner (ld.so userspace only) │ │ │
│ │ │ Node.js → OpenClaw                  │ │ │
│ │ └─────────────────────────────────────┘ │ │
│ └─────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

### Isolation Layers

1. **Android app sandbox** — Termux runs in its own Linux user namespace; no access to other app data
2. **SELinux** — Android's mandatory access control applies to all Termux processes
3. **No root required** — The entire stack runs as a regular unprivileged user
4. **No proot** — No filesystem translation layer; glibc-runner provides only the dynamic linker
5. **Path conversion** — Standard Linux paths (`/tmp`, `/bin/sh`) are mapped to Termux equivalents at install time, not at runtime via syscall interception

### What We Protect Against

- Unauthorized access to Android system or other app data (enforced by Android sandbox)
- Arbitrary code execution outside Termux (prevented by SELinux + app sandbox)
- Path traversal from Termux into Android system paths (Termux prefix isolation)

### What Is Out of Scope

- Vulnerabilities in OpenClaw core (report to [OpenClaw upstream](https://github.com/openclaw/openclaw))
- Vulnerabilities in Termux (report to [Termux](https://github.com/termux/termux-app))
- Vulnerabilities in glibc-runner (report to [termux-pacman](https://github.com/AidanPark/openclaw-android))
- Device-level security (rooted devices, unlocked bootloaders)

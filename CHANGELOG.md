# Changelog

All notable changes follow Semantic Versioning.

## 0.3.0 — Unreleased

- Added typed JDWP, abstract, reserved and filesystem sockets, and device/raw-device forward destinations.
- Added role validation before connecting, typed forward/reverse listings and endpoint-based removal.
- Preserved TCP methods and models; legacy listings now return only TCP-to-TCP mappings.
- Consume the complete ADB forward/reverse operation status, including failures and truncated responses.
- Added real JDWP handshake, terminated-process rejection and abstract socket forward/reverse echo integration tests.

## 0.2.0 — 2026-09-23

- Added Shell v2 with separated output streams and exit code.
- Added device tracking as a cold Flow.
- Added ADB SYNC stat, list, push and pull.
- Added package install/uninstall and TCP forward/reverse management.
- Added validated Maven publication artifacts, PGP release enforcement and a reproducible Central bundle.
- Added tag-driven Maven Central and GitHub Release automation.
- Added Kotlin ABI validation, standalone consumer tests, Dokka Pages deployment and JVM/Android samples.
- Added streaming SYNC pull/push, local `Path` sources and destinations, progress, configurable limits and metadata preservation.
- Added binary-safe screenshot capture in memory and directly to local paths.
- Added cancellable, backpressured logcat streaming with structured entries, validated filters and bounded lines.
- Added an explicit-serial physical/emulator integration suite and a minimal repository-owned Android fixture.
- Validated non-destructive operations with a physical Android device.

## 0.1.0 — 2026-09-07

- Added direct ADB Server TCP transport, host protocol, device discovery,
  transport selection, legacy shell and getprop.

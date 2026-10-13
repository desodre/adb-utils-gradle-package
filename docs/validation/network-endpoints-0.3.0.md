# Endpoint implementation evidence — 2026-10-13

Scope: issues [#10](https://github.com/desodre/adb-utils-gradle-package/issues/10) and [#32](https://github.com/desodre/adb-utils-gradle-package/issues/32). Development version: **0.3.0 (unreleased)**. The published 0.2.0 artifact and tag were not changed.

## Local results

| Check | Result |
| --- | --- |
| Clean JVM build and unit/loopback tests | PASS — 86 tests, zero failures/errors |
| `checkKotlinAbi` | PASS — additive API; existing TCP method/model signatures retained |
| `consumerTest` and `validatePublication` | PASS — local unsigned Maven artifact, not a Central release |
| Android sample `assembleDebug` using local 0.3.0 artifact | PASS |
| Previously compiled TCP consumer | PASS — compiled against published 0.2.0, executed unchanged with 0.3.0 |
| Full emulator integration, API 37 | PASS — all existing smoke steps and new endpoint steps |
| API 21 emulator integration | NOT RUN — emulator exited with code 139 before becoming an ADB target |

JVM/Android builds used Temurin JDK 17.0.19 and the existing SDK. A first fixture build under the terminal's JDK 26 failed during Android's JDK image transformation; switching to JDK 17 resolved it. The local SDK path also needed correcting for the invocation; no SDK path was committed.

The binary compatibility probe used only 0.2.0 API symbols: default-argument TCP `forward`/`reverse`, both removals, legacy listings, `TcpPort`, and `PortForward`/`ReverseForward` construction, copy and destructuring. Compilation used the cached published 0.2.0 JAR; runtime classpath replaced it with the newly built 0.3.0 JAR. It completed without recompilation or linkage errors. This complements, rather than replaces, the committed ABI baseline and consumer test.

## API 37 environment and observed steps

- Target: local emulator, `sdk_gphone16k_x86_64`, API 37, x86_64.
- Image: `system-images;android-37.0;google_apis_ps16k;x86_64`.
- Emulator: 37.1.11, cold boot, `-gpu swangle`.
- ADB client: 1.0.41 / 35.0.2-android-tools.
- Explicit emulator target; no physical-device test or physical serial included.
- JUnit: one full smoke scenario, zero failures/errors, 3.784 seconds.

Observed successful steps:

```text
legacy-shell
finite-shell-v2
health
interactive-shell passed
sync-and-forward passed
fixture-install
jdwp-handshake passed
abstract-forward-echo passed
abstract-reverse-echo passed
screenshot-memory
screenshot-path
jdwp-terminated-target passed
fixture-screenshot-logcat passed
```

The fixture announces its actual process ID after starting its unique abstract socket. The test sends and receives the exact 14-byte `JDWP-Handshake` through a TCP-to-JDWP mapping. After force-stop, another mapping to that PID must fail to attach (EOF/reset; a timeout is not treated as success). Abstract forward/reverse tests exchange a unique framed payload, not just a successful registration.

The fixture and mappings are cleaned in `finally`; post-run forward/reverse lists for the emulator were empty. The emulator started for this test was stopped afterward.

## Remaining validation boundaries

API 21 boot was attempted with emulator 37.1.11 (`swiftshader_indirect`, then Vulkan disabled) and 36.5.11 (`swiftshader_indirect`). All exited with code 139 before the target appeared. These runs provide no library regression result. Re-run the existing CI API 21 gate or correct the local emulator environment; do not label these attempts as passed. The existing suite intentionally does not install the Shell-v2-dependent fixture on API 21.

`localreserved`, `localfilesystem`, `dev` and `dev-raw` have serialization, parsing and role-validation tests, but no claim of successful privileged Android I/O. Such access depends on OS namespace support, permissions and SELinux. No arbitrary device files were accessed.

Existing cloud API 37 investigations [#30](https://github.com/desodre/adb-utils-gradle-package/issues/30) and [#31](https://github.com/desodre/adb-utils-gradle-package/issues/31) remain separate. This successful local API 37 result neither closes them nor carries the 0.2.0 release waiver forward to a future release. A new 0.3.0 release still needs its release validation.

## Reproduction

Use a JDK supported by the Android Gradle Plugin (17 used here) and a valid installed SDK:

```sh
./gradlew clean build checkKotlinAbi consumerTest validatePublication
./gradlew -p samples/android assembleDebug \
  -PadbUtilsRepository="$PWD/build/publication-check-repository" \
  -PadbUtilsVersion=0.3.0
./gradlew adbTest -PadbTest=true -PadbSerial=emulator-5554 -PadbTargetKind=emulator
```

The last command is opt-in and installs/removes only the repository fixture. See [device validation](../device-validation.md) and [endpoint contracts](../forwarding.md) for prerequisites and limits.

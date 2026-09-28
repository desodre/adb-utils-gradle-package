# Device validation gate

Run this gate after all release-candidate changes have merged and the normal CI is green. The suite talks directly to the already-running ADB Server; it never starts `adb` and never chooses a target implicitly.

## Safety and prerequisites

- Connect and authorize exactly the intended target.
- Pass its serial through `-PadbSerial`; the suite fails when the property is absent or the serial is unavailable.
- Use `-PadbTargetKind=physical` or `emulator` so the report identifies the environment.
- The suite installs only `io.github.desodre.adbutils.fixture` and uses the `adb-utils-` prefix for temporary remote files.
- The fixture package, temporary files and forward rules are removed in `finally` blocks.
- The physical target serial is supplied privately at execution time and must never be committed to the repository or attached to public reports.

## Matrix

| Target | Required | Purpose |
| --- | --- | --- |
| Physical device with a privately supplied serial | Yes | OEM behavior, USB transport and real device services |
| Emulator at the minimum supported API | Yes | Lower compatibility boundary |
| Emulator at the latest stable API used by CI | Yes | Current platform behavior |
| Emulator with a non-host ABI | When available | Architecture-independent behavior |

Record the actual API level, ABI, model, ADB Server version, library version and commit for every run. Do not infer these values from the target name.

## Execution

Use a JDK supported by the Android Gradle Plugin (CI uses JDK 17):

```sh
./gradlew adbTest \
  -PadbTest=true \
  -PadbSerial=DEVICE_SERIAL_EXAMPLE \
  -PadbTargetKind=physical

./gradlew adbTest \
  -PadbTest=true \
  -PadbSerial=emulator-5554 \
  -PadbTargetKind=emulator
```

The task builds the minimal `samples/android/fixture` APK before executing the tests. Unit tests remain independent from Android SDK and ADB.

## Covered behavior

- Explicit discovery and serial selection.
- Legacy Shell, finite Shell v2 and bidirectional interactive Shell v2.
- Health snapshot with partial-section semantics.
- SYNC stat/push/pull and remote cleanup.
- Forward creation, listing and cleanup.
- Fixture installation, launch, log marker and uninstall.
- Binary screenshot in memory and to a local path.
- Filtered logcat streaming and cancellation after the marker is observed.

## Release evidence

Attach or link the JUnit result and a summary with this shape to the release-validation issue:

```text
Commit:
Library version:
ADB Server version:
Target kind:
Serial: <redacted for physical devices; emulator serial only when applicable>
Model:
API level:
ABI:
Result:
Known limitation / linked issue:
```

The gate fails while a confirmed P0/P1 regression is open. Environment failures must be distinguished from library failures and retried after the environment is corrected.

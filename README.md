# adb-utils

[English](README.md) · [Português (Brasil)](README.pt-BR.md)

An early Kotlin/JVM SDK that communicates directly with the local Android Debug Bridge server over its TCP smart-socket protocol, normally at `127.0.0.1:5037`. It does not spawn the `adb` executable or use `ProcessBuilder`.

This is the Kotlin/JVM implementation in the adb-utils family. The independently versioned Dart implementation is available in [desodre/adb_utils](https://github.com/desodre/adb_utils) and on [pub.dev](https://pub.dev/packages/adb_utils). The implementations share protocol goals, but not source code or release numbers.

## Requirements

- Java 17 or newer.
- An already-running ADB Server.
- An authorized Android device for device services.

## Installation

Version 0.2.0 is available on [Maven Central](https://central.sonatype.com/artifact/io.github.desodre/adb-utils/0.2.0):

```kotlin
dependencies {
    implementation("io.github.desodre:adb-utils:0.2.0")
}
```

## Quick start

```kotlin
import io.github.desodre.adbutils.client.AdbClient
import java.nio.file.Path

suspend fun main() {
    val adb = AdbClient()
    println(adb.version())
    adb.devices().forEach { println("${it.serial}: ${it.model}") }
    val device = adb.device()
    val shell = device.shellV2("getprop ro.product.model")
    println("exit=${shell.exitCode} stdout=${shell.stdout} stderr=${shell.stderr}")
}
```

Finite operations are suspending and own a fresh connection. `AdbDevice` is a serial-bound handle, not a persistent connection.

## Interactive shell

```kotlin
val session = device.openInteractiveShell("sh")
try {
    session.writeStdin("echo hello\nexit\n".encodeToByteArray())
    session.closeStdin()
    session.output.collect { chunk ->
        println("${chunk.stream}: ${chunk.data.decodeToString()}")
    }
    println(session.awaitTermination())
} finally {
    session.cancel()
}
```

Interactive sessions use binary-safe Shell v2 frames and keep stdout and stderr distinct. Output is a single-consumer `Flow` backed by a bounded buffer, so a slow collector applies backpressure. `closeStdin()` gracefully half-closes input; `cancel()` force-closes the transport. Shell v2 requires Android API 24 or newer and unsupported devices raise `ShellV2UnsupportedException`.

## Tracking

```kotlin
adb.trackDevices().collect { devices ->
    devices.forEach { println("${it.serial}: ${it.state}") }
}

val ready = adb.waitForDevice(
    serial = DeviceSerial("R58M..."),
    state = DeviceState.DEVICE,
    timeoutMillis = 30_000,
)
```

The cold `Flow` owns one connection per collector. Cancellation closes it. Snapshots are emitted as received; reconnection is not automatic. `waitForDevice()` consumes this stream until the requested serial and state appear, which is useful after reboot; timeout raises `AdbTimeoutException` and always closes the tracking connection.

## Files, packages and forwarding

```kotlin
device.push("hello".encodeToByteArray(), "/data/local/tmp/hello.txt")
val contents = device.pull("/data/local/tmp/hello.txt")
device.pullTo("/sdcard/large.bin", Path.of("large.bin"))
device.push(Path.of("upload.bin"), "/data/local/tmp/upload.bin")
val stat = device.stat("/data/local/tmp/hello.txt")
val entries = device.list("/data/local/tmp")

val install = device.install(apkBytes)
device.uninstall("com.example.app")

device.forward(TcpPort(7000), TcpPort(8000))
device.removeForward(TcpPort(7000))
```

SYNC paths are limited to 1,024 UTF-8 bytes. `pull()` retains its convenient 64 MiB in-memory default. `pullChunks()` exposes a cold `Flow<ByteArray>`, while `pullTo()` writes through a temporary sibling file and atomically replaces the destination when supported. `pushChunks()` emits cumulative `SyncTransferProgress`; the `Path` overload streams local files and derives their POSIX mode and modification time. All streaming limits are configurable, cancellation closes the ADB session, and protocol frames remain capped at 64 KiB. Package installation uses SYNC, Package Manager and a temporary file under `/data/local/tmp`.

On Android, the overloads based on `java.nio.file.Path` require API 26 or newer; the Flow-based overloads remain available independently of local-file helpers.

Version 0.2.0 supports fixed TCP forwarding endpoints. Callers must remove mappings they create.

## Device health

```kotlin
val health = device.healthSnapshot(
    DeviceHealthOptions(sectionTimeoutMillis = 2_000),
)

when (val battery = health.battery) {
    is HealthSection.Available -> println("battery=${battery.value.levelPercent}%")
    is HealthSection.Unavailable -> println("battery unavailable: ${battery.failure.kind}")
}
```

The snapshot collects battery, `/data` storage, memory, uptime, Android version and hardware sections concurrently. Every section has its own timeout and failure, so one unsupported or malformed source does not discard successful data. Sizes are bytes, uptime is milliseconds and battery temperature is tenths of a Celsius degree. Hardware serial properties are excluded unless `includeIdentifiers = true` is explicitly requested.

## Screenshots and logcat

```kotlin
val png = device.screenshot()
device.screenshotTo(Path.of("screen.png"))

device.logcat(
    LogcatOptions(
        buffers = setOf(LogcatBuffer.MAIN, LogcatBuffer.SYSTEM),
        filters = listOf(LogcatFilter("ActivityManager", LogcatPriority.INFO)),
    ),
).collect { entry ->
    println("${entry.timestamp} ${entry.priority}/${entry.tag}: ${entry.message}")
}
```

Screenshots use ADB's binary-safe raw `exec:screencap -p` service (the protocol behind CLI `exec-out`) and never create a remote file. The in-memory and `Path` APIs validate the PNG signature and enforce configurable limits; file output uses a temporary sibling and only replaces the destination after successful validation.

`logcat()` is a cold, backpressured `Flow`. It supports validated buffer/filter selection plus structured `EPOCH` and message-only `RAW` formats. Lines split across ADB frames are reconstructed with a configurable bound. Cancelling collection closes the transport and stops the remote command.

## Current scope

- Host version, long device listing and typed device selection.
- Legacy shell, finite and interactive Shell v2, getprop, device tracking with `Flow` and state waiting with timeout.
- ADB SYNC v1 stat/list, in-memory transfers, streaming flows and local file sources/sinks.
- Package install/uninstall and TCP forward/reverse.
- Structured partial device-health snapshots with per-section timeouts.
- Binary-safe screenshots and cancellable, bounded logcat streaming.
- Text shell APIs reject malformed UTF-8; binary Shell v2 output is not exposed.
- The SDK does not start the ADB Server.
- The API is pre-1.0 and may change between minor versions.

Errors derive from `AdbException` and distinguish server availability, connection, timeout, malformed protocol, ADB `FAIL`, device state, output limits and package failures.

## Build

```shell
./gradlew clean build
./gradlew checkKotlinAbi consumerTest
./gradlew validatePublication
```

Unit and loopback TCP tests require no device. Hardware tests are opt-in and require an explicit serial; they build and install the repository-owned fixture APK, then clean packages, files and forwards even after known failures:

```shell
./gradlew adbTest -PadbTest=true -PadbSerial=DEVICE_SERIAL_EXAMPLE -PadbTargetKind=physical
./gradlew adbTest -PadbTest=true -PadbSerial=emulator-5554 -PadbTargetKind=emulator
```

See [device validation](docs/device-validation.md) for the required physical/emulator matrix and release evidence.

`validatePublication` builds an unsigned Maven repository under `build/publication-check-repository` and verifies artifacts, checksums and required POM metadata. `consumerTest` resolves the standalone JVM sample exclusively through that repository. Kotlin explicit API mode and `checkKotlinAbi` protect the checked-in ABI baseline; run `./gradlew updateKotlinAbi` only after reviewing an intentional public API change.

The `releaseBundle` task is reserved for signed releases and fails unless the protected `signingKey` and `signingPassword` Gradle properties are present. A semantic tag matching `VERSION` (for example `v0.2.0`) runs the release workflow, uploads the signed bundle through the Central Portal Publisher API, waits for `PUBLISHED`, and then creates the GitHub Release. See [RELEASING.md](RELEASING.md).

Standalone examples live in [samples/kotlin-jvm](samples/kotlin-jvm) and [samples/android](samples/android). Dokka documentation is deployed to [GitHub Pages](https://desodre.github.io/adb-utils-gradle-package/) after changes reach `main`.

## Related implementation

- [adb_utils for Dart](https://github.com/desodre/adb_utils), distributed through [pub.dev](https://pub.dev/packages/adb_utils).

Feature coverage and versions evolve independently in each ecosystem.

## Roadmap

See the [public Kanban](https://github.com/users/desodre/projects/8) for planned device/package tooling, CLI, Compose Desktop and Kotlin Multiplatform research.

See [CHANGELOG.md](CHANGELOG.md). Licensed under the [MIT License](LICENSE).

# adb-utils

[English](README.md) · [Português (Brasil)](README.pt-BR.md)

An early Kotlin/JVM SDK that communicates directly with the local Android Debug Bridge server over its TCP smart-socket protocol, normally at `127.0.0.1:5037`. It does not spawn the `adb` executable or use `ProcessBuilder`.

## Requirements

- Java 17 or newer.
- An already-running ADB Server.
- An authorized Android device for device services.

## Installation

The first Maven Central release is being prepared under these coordinates:

```kotlin
dependencies {
    implementation("io.github.desodre:adb-utils:0.2.0")
}
```

Until published, use `./gradlew publishToMavenLocal`.

## Quick start

```kotlin
import io.github.desodre.adbutils.client.AdbClient

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

## Tracking

```kotlin
adb.trackDevices().collect { devices ->
    devices.forEach { println("${it.serial}: ${it.state}") }
}
```

The cold `Flow` owns one connection per collector. Cancellation closes it. Snapshots are emitted as received; reconnection is not automatic.

## Files, packages and forwarding

```kotlin
device.push("hello".encodeToByteArray(), "/data/local/tmp/hello.txt")
val contents = device.pull("/data/local/tmp/hello.txt")
val stat = device.stat("/data/local/tmp/hello.txt")
val entries = device.list("/data/local/tmp")

val install = device.install(apkBytes)
device.uninstall("com.example.app")

device.forward(TcpPort(7000), TcpPort(8000))
device.removeForward(TcpPort(7000))
```

SYNC paths are limited to 1,024 UTF-8 bytes. `pull()` buffers data in memory with a 64 MiB default limit; `push()` accepts a `ByteArray` and sends 64 KiB chunks. Package installation uses SYNC, Package Manager and a temporary file under `/data/local/tmp`.

Version 0.2.0 supports fixed TCP forwarding endpoints. Callers must remove mappings they create.

## Current scope

- Host version, long device listing and typed device selection.
- Legacy shell, Shell v2, getprop and device tracking with `Flow`.
- ADB SYNC v1 stat/list/push/pull in memory.
- Package install/uninstall and TCP forward/reverse.
- Text shell APIs reject malformed UTF-8; binary Shell v2 output is not exposed.
- The SDK does not start the ADB Server.
- The API is pre-1.0 and may change between minor versions.

Errors derive from `AdbException` and distinguish server availability, connection, timeout, malformed protocol, ADB `FAIL`, device state, output limits and package failures.

## Build

```shell
./gradlew clean build
```

Unit and loopback TCP tests require no device. Real-device smoke tests must be explicitly enabled. Version 0.2.0 was exercised against a physical Android 16 device for tracking, shells, SYNC and forwarding.

## Roadmap

- Streaming SYNC and local file sources/sinks.
- Logcat as `Flow`, screenshots and diagnostics.
- CLI, Compose Desktop, Kotlin Multiplatform/Native and Dart bindings.

See [CHANGELOG.md](CHANGELOG.md). Licensed under the [MIT License](LICENSE).

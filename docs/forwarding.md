# Typed forwarding and JDWP

Available in the **unreleased 0.3.0 development version**, not in the published 0.2.0 artifact. The library speaks directly to the ADB server: no `adb` subprocess or debugger is launched.

## Endpoint roles

Forward listens on the ADB server host and connects on Android. Reverse listens on Android and connects on the ADB server host (which may be a different machine from your JVM).

| Kotlin endpoint | Wire representation | Forward local | Forward remote | Reverse remote/local |
| --- | --- | --- | --- | --- |
| `TcpPort(8700)` | `tcp:8700` | Yes | Yes | Yes |
| `LocalAbstract("app")` | `localabstract:app` | Yes | Yes | Yes |
| `LocalReserved("app")` | `localreserved:app` | Yes | Yes | Yes |
| `LocalFilesystem("/tmp/app.sock")` | `localfilesystem:/tmp/app.sock` | Yes | Yes | Yes |
| `JdwpPid(1234)` | `jdwp:1234` | No | Yes | No |
| `DeviceFile("/dev/example")` | `dev:/dev/example` | No | Yes | No |
| `RawDeviceFile("/dev/example")` | `dev-raw:/dev/example` | No | Yes | No |
| `UnknownEndpoint` (listing only) | Preserved server value | No | No | No |

“Yes” means the protocol role is accepted, not that the namespace exists on every OS. Abstract sockets require platform support; reserved sockets normally live under Android's `/dev/socket`. Filesystem paths refer to the machine at that end of the mapping. Permissions, SELinux, path-length limits and daemon capabilities still apply. `dev` and `dev-raw` do not grant root access; raw mode is intended for terminal device files. Do not use arbitrary device files for smoke testing.

Ports must be 1–65535 (automatic `tcp:0` allocation is not included). PIDs must be positive `Int` values. Names/paths must be nonempty, valid UTF-8, without whitespace, control characters or `;`. Paths must be absolute and not `/`; reserved names cannot contain `/`, `.` or `..` as the whole name. The complete request must fit ADB's 65535-byte frame limit. These checks run before opening a command connection. OS-specific socket path limits are enforced by the server, not inferred from the JVM's OS.

Unsupported endpoint roles throw `UnsupportedEndpointException`; invalid constructor values throw `IllegalArgumentException`. Server `FAIL` messages retain their reason in `AdbFailException`. Invalid/truncated status or malformed known endpoints in listings throw `AdbProtocolException`.

## Debugger attachment lifecycle

```kotlin
import io.github.desodre.adbutils.client.AdbClient
import io.github.desodre.adbutils.model.*

suspend fun withDebugTarget(pid: Int, attachDebugger: suspend (String, Int) -> Unit) {
    val device = AdbClient().device(DeviceSerial("DEVICE_SERIAL_EXAMPLE"))
    val local = TcpPort(8700)
    device.forward(local, JdwpPid(pid), noRebind = true)
    try {
        // The ADB server is local in this example. Close the debugger connection before returning.
        attachDebugger("127.0.0.1", local.value)
    } finally {
        device.removeForward(local)
    }
}
```

Supply the current PID of your own debuggable app. Starting a process or finding its PID is separate from registering the mapping. `forward()` succeeding only confirms listener registration: a nonexistent, terminated or non-debuggable target may fail when the debugger connects, through EOF/reset rather than an `AdbFailException` at registration. After an app restart, obtain its new PID and recreate the mapping. Another attached debugger can also prevent attachment.

`noRebind = true` prevents replacing someone else's existing listener. A registration failure does not authorize removing the existing mapping. Close the debugger socket before removing your mapping; removal prevents new connections but is not a debugger detach protocol. If using coroutines, arrange cleanup in a non-cancelled context when cancellation must also remove mappings.

This is a JDWP transport facility, not DAP, a VS Code extension, breakpoint management, or Java debugger implementation. Exposing the ADB server or forwarded debug ports to untrusted networks exposes powerful access; keep them restricted.

## Generic mappings and backward compatibility

```kotlin
val socket = LocalAbstract("my-app-debug")
device.forward(TcpPort(8800), socket, noRebind = true)
try {
    val mappings: List<ForwardMapping> = device.listForwardMappings()
    // Connect to port 8800 on the ADB server host using the app's socket protocol.
} finally {
    device.removeForward(TcpPort(8800))
}

val remote = LocalAbstract("my-app-reverse")
device.reverse(remote, TcpPort(9000), noRebind = true)
try {
    val mappings: List<ReverseMapping> = device.listReverseMappings()
    // Android clients connect to the abstract name; a host service must listen on port 9000.
} finally {
    device.removeReverse(remote)
}
```

Existing TCP overloads, `TcpPort`, `PortForward` and `ReverseForward` retain their signatures. `listForwards()` and `listReverses()` are compatibility views of **TCP-to-TCP only**. Use `listForwardMappings()` / `listReverseMappings()` to see other mappings. Forward listings are filtered by the explicitly selected serial. Reverse listings accept both current two-column and older transport-prefixed three-column formats.

Unknown namespaces are returned as `UnknownEndpoint` rather than silently discarded. They cannot be submitted back to commands; they need future typed support. Known but malformed endpoint values fail explicitly instead of being treated as unknown.

## Validation

Unit tests cover every supported role combination, invalid roles before connection, UTF-8 serialization, mixed listings, malformed data and both operation statuses. The opt-in Android suite installs the repository's debuggable fixture, verifies the 14-byte JDWP handshake, tests a terminated process, and transfers framed data through abstract forward and reverse mappings. See [device validation](device-validation.md). Reserved/filesystem/device-file permissions and host namespace availability remain environment-dependent; unit tests do not claim privileged on-device coverage.

Protocol references: [ADB manual](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md), [forward operation statuses](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/adb.cpp), [JDWP daemon service](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/daemon/jdwp_service.cpp).

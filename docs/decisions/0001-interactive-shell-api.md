# ADR 0001: Interactive shell API

## Status

Accepted.

## Context

Finite shell calls own a connection only for the duration of one suspending function. An interactive
shell must keep that connection alive while accepting stdin, streaming binary-safe stdout/stderr and
reporting remote termination. Exposing the underlying socket would couple the public API to the JVM
transport and bypass protocol framing and cleanup guarantees.

## Decision

- `AdbDevice.openInteractiveShell()` returns an owned `InteractiveShellSession`.
- The first release requires Shell v2. It does not silently fall back to legacy shell because legacy
  sessions cannot preserve separate stderr, exit codes or the close-stdin frame.
- `output` is a single-consumer `Flow<ShellOutputChunk>` backed by a bounded channel. The bounded
  buffer propagates backpressure to the ADB socket instead of growing memory without limit.
- `writeStdin()` serializes concurrent writers and splits large input into bounded frames.
- `closeStdin()` sends the Shell v2 half-close frame and is idempotent. `cancel()` force-closes the
  transport and waits for the reader coroutine to finish.
- `awaitTermination()` makes remote exit, caller cancellation and failures observable.
- The session owns an internal connection object; neither sockets nor transports enter the public API.

## Consequences

Consumers must collect output and eventually observe termination or cancel the session. Devices before
Android API 24 receive `ShellV2UnsupportedException`. Supporting a legacy interactive mode later would
require a separate capability contract rather than weakening this API's guarantees.

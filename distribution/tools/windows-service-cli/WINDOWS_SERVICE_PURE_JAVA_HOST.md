# Pure Java Windows Service

## Motivation

Elasticsearch on Windows has historically relied on Apache Commons Daemon (procrun)
to run as a Windows service. Procrun acts as a native wrapper that loads the JVM,
invokes configured Java methods for start/stop, and mediates between the Windows
Service Control Manager (SCM) and the Java application.

In order to streamline the implementation and reduce our depenedency on external libraries, we decided to eliminate procrun entirely and replace it with a pure Java
implementation that interacts with the SCM directly via Panama FFI
(`java.lang.foreign`).

## Architecture

The work is split across two branches / logical phases:

### Phase 1: FFI-based service control (`spacetime/windows-service-control-ffi`)

Replaces procrun-based `start`, `stop`, and `remove` commands with direct SCM
calls via Panama FFI. The `install` and `manager` commands remain procrun-based
in this phase.

**Key components:**

- `libs/windows-service-control` -- a new Multi-Release JAR (MRJAR) library
  containing Panama FFI bindings for `advapi32.dll`. The MRJAR structure handles
  the API differences between JDK 21 (where `java.lang.foreign` is a preview API)
  and JDK 22+ (where it is final). The compatibility shims live in `PanamaUtil`
  with a `main22` override.

- `Advapi32` -- low-level FFI bindings for SCM functions: `OpenSCManagerW`,
  `OpenServiceW`, `StartServiceW`, `ControlService`, `DeleteService`,
  `QueryServiceStatusEx`, `CloseServiceHandle`. Uses `GetLastError` capture via
  `Linker.Option.captureCallState`.

- `WindowsServiceControl` -- a high-level interface abstracting service
  operations (`startService`, `stopService`, `deleteService`, `queryStatus`).
  The production implementation (`NativeWindowsServiceControl`) uses `Advapi32`;
  tests use `MockWindowsServiceControl` via constructor injection.

- `ServiceStatus` -- a record wrapping the SCM's `SERVICE_STATUS_PROCESS` fields
  (`state`, `win32ExitCode`, `checkPoint`, `waitHint`). Includes an explicit
  `UNKNOWN` state (value `-1`) returned when `queryStatus` fails, so callers
  never deal with `null`.

- `ScmCommand` -- replaces `ProcrunCommand` as the base class for service CLI
  commands. Subclasses (`WindowsServiceStartCommand`, `WindowsServiceStopCommand`,
  `WindowsServiceRemoveCommand`) implement `executeServiceCommand` using the
  `WindowsServiceControl` interface.

- `WindowsServiceStopCommand` -- includes a polling loop that waits for the
  service to reach `STOPPED`, using `dwWaitHint` from the service status for
  intelligent backoff. Handles `UNKNOWN` states gracefully with user-facing
  warnings. Poll intervals follow
  [Microsoft's recommendation](https://learn.microsoft.com/en-us/windows/win32/services/stopping-a-service)
  of 1-10 seconds.

### Phase 2: Pure Java service hosting (`spacetime/pure-java-windows-service`)

Eliminates procrun for *running* the service itself. The JVM registers directly
with the SCM, receives start/stop callbacks, and manages the Elasticsearch
`ServerProcess` lifecycle.

**Key components:**

- `WindowsServiceHost` -- the core SCM lifecycle manager in
  `libs/windows-service-control`. Handles:
  - Calling `StartServiceCtrlDispatcherW` to register with the SCM (blocks the
    main thread until the service stops).
  - Providing a `ServiceMain` upcall (via Panama FFI) that the SCM invokes on a
    dedicated thread.
  - Registering a `HandlerEx` control handler (via Panama FFI upcall) to receive
    stop and interrogate commands.
  - Reporting status transitions (`START_PENDING` -> `RUNNING` -> `STOP_PENDING`
    -> `STOPPED`) to the SCM via `SetServiceStatus`.
  - Invoking a user-supplied `ServiceCallback` (`onStart`/`onStop`) for the
    actual application lifecycle.
  - Using a Java `CountDownLatch` for stop signaling between the handler thread
    and the service main thread (no native Win32 events needed for intra-process
    synchronization).

- `WindowsServiceDaemon` -- the Elasticsearch-specific entry point. A standalone
  class with a `main` method (not part of the `CliToolLauncher`/`Command`
  hierarchy). Its `main`:
  1. Configures basic logging.
  2. Creates a `WindowsServiceHost` with start/stop callbacks.
  3. Calls `host.run(serviceName)`, which blocks until the service is stopped.

  The `onStart` callback bootstraps an Elasticsearch `Environment` via
  `EnvironmentBuilder`, loads the keystore, parses JVM options, and starts a
  `ServerProcess`. The `onStop` callback calls `ServerProcess.stop()`.

- `WindowsServiceInstallCommand` -- rewritten to register `java.exe` directly
  with the SCM (no procrun). At install time, it constructs a
  command line containing:
  - The path to `java.exe` (resolved from `java.home`).
  - Minimal JVM options (`-Xms4m -Xmx64m -XX:+UseSerialGC`) for the daemon process itself (the actual Elasticsearch server JVM options are resolved at
    startup by the daemon).
  - Essential system properties (`es.path.home`, `es.path.conf`,
    `es.distribution.type`) that cannot be resolved at runtime.
  - A classpath covering `lib/*`, `lib/tools/server-cli/*`, and
    `lib/tools/windows-service-cli/*`.
  - The main class `org.elasticsearch.windows.service.WindowsServiceDaemon`.

  Service metadata (display name, description, start type, service account) is
  configured via `CreateServiceW` and `ChangeServiceConfig2W`.

- `EnvironmentBuilder` -- extracted from `EnvironmentAwareCommand` into a
  standalone utility in `server/src/main/java/.../common/cli/`. Encapsulates the
  logic for creating an Elasticsearch `Environment` from `ProcessInfo` (Docker
  env var translation, system property merging, config path resolution).
  `EnvironmentAwareCommand` now delegates to it, and `WindowsServiceDaemon` calls
  it directly without inheriting from `Command`.

- `WindowsServiceDaemonProvider` -- deleted. The daemon is no longer discovered
  via SPI; it has its own `main` method.

## Design decisions

**Why not keep `CliToolLauncher`/`Command` for the daemon?**
The Windows service lifecycle is fundamentally different from a CLI tool. The SCM
calls `ServiceMain` on a dedicated thread after `StartServiceCtrlDispatcherW`
blocks the main thread. There is no argument parsing, no terminal interaction,
and no clean mapping to the `Command.execute()` model. Forcing the daemon into
the CLI hierarchy would have little to no benefit.

**Why `java.exe` and not a batch file as the service binary?**
The SCM requires a proper executable as the service binary path. Batch files
(`.bat`) are not directly executable by the SCM; they would need `cmd.exe /c`
wrapping, which introduces fragility and an extra process.

**Why `CountDownLatch` instead of Win32 events for stop signaling?**
The stop signal is purely intra-process (the `HandlerEx` callback signals the
`ServiceMain` thread). Java's `CountDownLatch` is simpler, avoids an additional
native dependency (`kernel32.dll` event APIs), and is well-understood.

**Why duplicate `PanamaUtil` instead of reusing `libs/native`?**
The `libs/native` module has similar utilities (`LinkerHelper`, `MemorySegmentUtil`)
but they are package-private and not exported. Exposing them would require changes
to `libs/native`'s module boundaries for a Windows-specific feature. The
duplicated methods are trivial one-liner wrappers around `Linker` methods, and
`PanamaUtil` additionally provides MRJAR-based compatibility shims that
`libs/native` does not offer in a directly consumable form.

**Why resolve most configuration at service startup, not install time?**
Baking too much into the SCM's `lpBinaryPathName` makes the service brittle --
any change to JVM options, classpath, or paths would require reinstalling the
service. Only values that genuinely require the install-time environment (Java
home, ES home, config path, distribution type) are captured at install time.
Everything else (server JVM options, full classpath for the server child process,
heap size, etc.) is resolved by `WindowsServiceDaemon` at startup.

## Branches

| Branch | Base | Scope |
|--------|------|-------|
| `spacetime/windows-service-control-ffi` | `main` | FFI-based `start`/`stop`/`remove` commands |
| `spacetime/pure-java-windows-service` | `spacetime/windows-service-control-ffi` | Pure Java service hosting + FFI-based `install` |
| `spacetime/environment-builder` | `main` | `EnvironmentBuilder` extraction (can be merged independently) |

## Testing

- All service CLI commands use the `WindowsServiceControl` interface, which is
  mocked in tests via `MockWindowsServiceControl` (in `ScmCommandTestCase`).
  Install-specific assertions use `MockInstallServiceControl`, which extends the
  base mock.
- `WindowsServiceHost` and `WindowsServiceDaemon` require a real Windows
  environment with SCM access for integration testing.
- The `EnvironmentBuilder` extraction is covered by existing
  `EnvironmentAwareCommand` tests, since `EnvironmentAwareCommand` now delegates
  to it.

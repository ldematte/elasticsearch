# Refactoring Plan 3: Auto-Provision GraalVM via Gradle Toolchains

## Background

The Elasticsearch build already auto-downloads and manages JDKs via Gradle's Java Toolchain system. Custom `JavaToolchainResolver` implementations (in `build-tools-internal/src/main/java/.../toolchain/`) know how to construct download URLs for Oracle OpenJDK, Adoptium, etc. These resolvers are registered in `settings.gradle` and `JavaToolChainResolverPlugin.java`. When any task requests a toolchain via `javaToolchains.launcherFor { ... }`, Gradle checks local installations first, then auto-downloads using the resolvers.

The `nativeCompile` task in `server-launcher/build.gradle` currently expects `native-image` to already exist in `$JAVA_HOME/bin/` or on `PATH` -- requiring the developer to have pre-installed a GraalVM JDK. This plan adds a GraalVM toolchain resolver that plugs into the same auto-provisioning system, so Gradle downloads GraalVM on demand.

## Approach

Since GraalVM JDK distributions include `native-image` in their `bin/` directory (since GraalVM 22+), we can use Gradle's standard `javaToolchains.launcherFor` API to resolve a GraalVM JDK, then derive the `native-image` path from the installation.

**Key design decision**: The GraalVM version is derived from the existing `bundled_jdk` version in `version.properties` rather than maintained as a separate property. Oracle GraalVM releases track JDK version numbers exactly -- Oracle GraalVM for JDK 25.0.2 *is* version 25.0.2. This means:
- When the bundled JDK is updated (e.g., from 25.0.2 to 25.0.3), GraalVM automatically tracks it
- No separate version to keep in sync
- The resolver parses the base version (e.g., `25.0.2`) from the bundled JDK version string (`25.0.2+10@hash`), stripping the build number and hash

This requires:
1. A new `JavaToolchainResolver` that constructs Oracle GraalVM download URLs from the bundled JDK version
2. Registering the resolver in the toolchain management configuration
3. Updating the `nativeCompile` task to use the toolchain API instead of searching `PATH`/`JAVA_HOME`

No changes to `version.properties` or `VersionProperties.java` are needed.

## Oracle GraalVM Download URL Pattern

Oracle GraalVM archives are available at:
```
https://download.oracle.com/graalvm/{major}/archive/graalvm-jdk-{version}_{os}-{arch}_bin.{ext}
```
- `{major}` -- JDK major version (e.g., 25)
- `{version}` -- base version string (e.g., 25.0.2) -- parsed from the bundled JDK version
- `{os}` -- `linux`, `macos`, `windows`
- `{arch}` -- `x64`, `aarch64`
- `{ext}` -- `tar.gz` (linux/macos), `zip` (windows)

This is simpler than the Oracle OpenJDK pattern (no build number or hash required). For example, with `bundled_jdk = 25.0.2+10@b1e0dfa218384cb9959bdcb897162d4e`, the resolver would produce:
```
https://download.oracle.com/graalvm/25/archive/graalvm-jdk-25.0.2_linux-x64_bin.tar.gz
```

## Detailed Steps

### Step 1: Create `OracleGraalVmToolchainResolver.java`

New file: `build-tools-internal/src/main/java/org/elasticsearch/gradle/internal/toolchain/OracleGraalVmToolchainResolver.java`

Follow the same structure as `OracleOpenJdkToolchainResolver` / `ArchivedOracleJdkToolchainResolver`:
- Extend `AbstractCustomJavaToolchainResolver`
- Parse the bundled JDK version from `VersionProperties.getBundledJdkVersion()` using the same `VERSION_PATTERN` regex as `OracleOpenJdkToolchainResolver` to extract the base version (e.g., `25.0.2`) and major version (e.g., `25`)
- In `resolve()`:
  - Only match requests where vendor is `JvmVendorSpec.GRAAL_VM` (do NOT match "any" -- GraalVM should only be used when explicitly requested, to avoid accidentally resolving GraalVM for regular Java compilation tasks)
  - Only match requests where the language version matches the bundled JDK major version
  - Reject unsupported platform combos (aarch64 + windows)
  - Construct the Oracle GraalVM archive URL using the pattern above
  - Return `Optional.of(() -> URI.create(url))`

### Step 2: Register the resolver

**File: `build-tools-internal/src/main/java/org/elasticsearch/gradle/internal/toolchain/JavaToolChainResolverPlugin.java`**

Add `registry.register(OracleGraalVmToolchainResolver.class)` in the `apply()` method.

**File: `settings.gradle`**

Add import for `OracleGraalVmToolchainResolver` and a new `repository` entry in the `toolchainManagement.jvm.javaRepositories` block:
```groovy
repository('oracleGraalVm') {
  resolverClass = OracleGraalVmToolchainResolver
}
```

### Step 3: Update `server-launcher/build.gradle`

File: `distribution/tools/server-launcher/build.gradle`

Replace the current manual `native-image` lookup with toolchain-based resolution:

```groovy
def graalVmLauncher = javaToolchains.launcherFor {
    languageVersion = JavaLanguageVersion.of(VersionProperties.getBundledJdkMajorVersion())
    vendor = JvmVendorSpec.GRAAL_VM
}

tasks.register('nativeCompile', Exec) {
    // ... existing setup ...

    def nativeImageTool = graalVmLauncher.map {
        def suffix = System.getProperty('os.name').toLowerCase().contains('windows') ? '.cmd' : ''
        it.metadata.installationPath.file("bin/native-image${suffix}").asFile.absolutePath
    }

    executable nativeImageTool.get()
    // ... rest of args unchanged ...
}
```

This removes the entire `nativeImageTool` provider that searches `$JAVA_HOME/bin` and `PATH`, replacing it with a single `javaToolchains.launcherFor` call. Gradle handles downloading, caching, and extracting GraalVM automatically.

### Step 4: Add a test for the new resolver

New file: `build-tools-internal/src/test/groovy/org/elasticsearch/gradle/internal/toolchain/OracleGraalVmToolchainResolverSpec.groovy`

Follow the pattern from `OracleOpenJdkToolchainResolverSpec.groovy`:
- Extend `AbstractToolchainResolverSpec`
- Test supported requests (GRAAL_VM vendor, correct language version, all OS/arch combos)
- Test unsupported requests (wrong vendor, wrong language version, aarch64+windows)
- Verify the generated URLs match the expected Oracle GraalVM archive pattern

## What Changes

- `build-tools-internal/src/main/java/.../toolchain/OracleGraalVmToolchainResolver.java` -- new file
- `build-tools-internal/src/main/java/.../toolchain/JavaToolChainResolverPlugin.java` -- register resolver
- `settings.gradle` -- import + register resolver in `toolchainManagement`
- `distribution/tools/server-launcher/build.gradle` -- use toolchain API
- `build-tools-internal/src/test/groovy/.../OracleGraalVmToolchainResolverSpec.groovy` -- new test

## What Does NOT Change

- `version.properties` and `VersionProperties.java` -- no changes needed; we reuse `bundledJdkVersion`
- The `nativeCompile` task remains opt-in (not part of the default build)
- The native binary output location and format are unchanged
- The `server-launcher-common` library is unaffected
- All other toolchain resolvers and their behavior are unchanged
- The startup scripts are unaffected

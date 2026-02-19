/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.windows.service;

import org.elasticsearch.Build;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.ProcessInfo;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.service.windows.Advapi32Constants;
import org.elasticsearch.service.windows.WindowsServiceControl;
import org.elasticsearch.service.windows.WindowsServiceException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Installs Elasticsearch as a Windows service by registering it directly with the SCM.
 *
 * <p>The service binary path is a {@code java.exe} command line that launches
 * {@link WindowsServiceDaemon} with the minimal set of properties needed to bootstrap.
 * Only values that require the install-time environment (Java home, ES home, config path,
 * distribution type) are baked into the binary path; everything else is resolved at
 * service startup by the daemon itself.
 */
class WindowsServiceInstallCommand extends ScmCommand {

    WindowsServiceInstallCommand() {
        this(WindowsServiceControl.create());
    }

    WindowsServiceInstallCommand(WindowsServiceControl serviceControl) {
        super("Install Elasticsearch as a Windows Service", serviceControl);
    }

    @Override
    protected void executeServiceCommand(Terminal terminal, ProcessInfo processInfo, String serviceId) throws WindowsServiceException {
        Path javaHome = getJavaHome(processInfo.sysprops());
        terminal.println(String.format(Locale.ROOT, "Installing service : %s", serviceId));
        terminal.println(String.format(Locale.ROOT, "Using ES_JAVA_HOME : %s", javaHome));

        var serviceInfo = extractServiceInfo(processInfo, serviceId);

        serviceControl.createService(
            serviceId,
            serviceInfo.displayName,
            serviceInfo.binaryPath,
            serviceInfo.startType,
            serviceInfo.serviceUser,
            serviceInfo.servicePassword
        );

        try {
            serviceControl.setServiceDescription(serviceId, serviceInfo.description);
        } catch (WindowsServiceException e) {
            terminal.errorPrintln("WARNING: Service created but failed to set description: " + e.getMessage());
        }
    }

    private record ServiceInfo(
        String displayName,
        String description,
        String binaryPath,
        int startType,
        String serviceUser,
        String servicePassword
    ) {}

    @Override
    protected void validateCommand(ProcessInfo processInfo) throws UserException {
        Path javaHome = getJavaHome(processInfo.sysprops());
        Path javaExe = javaHome.resolve("bin").resolve("java.exe");
        if (Files.exists(javaExe) == false) {
            throw new UserException(
                ExitCodes.CONFIG,
                "Invalid java installation (no java.exe found in %s\\bin\\). Exiting...".formatted(javaHome)
            );
        }

        boolean hasUsername = processInfo.envVars().containsKey("SERVICE_USERNAME");
        if (processInfo.envVars().containsKey("SERVICE_PASSWORD") != hasUsername) {
            throw new UserException(
                ExitCodes.CONFIG,
                "Both service username and password must be set, only got " + (hasUsername ? "SERVICE_USERNAME" : "SERVICE_PASSWORD")
            );
        }
    }

    private ServiceInfo extractServiceInfo(ProcessInfo pinfo, String serviceId) {
        String displayName = pinfo.envVars()
            .getOrDefault("SERVICE_DISPLAY_NAME", "Elasticsearch %s (%s)".formatted(Build.current().version(), serviceId));
        String description = pinfo.envVars()
            .getOrDefault(
                "SERVICE_DESCRIPTION",
                String.format(Locale.ROOT, "Elasticsearch %s Windows Service - https://elastic.co", Build.current().version())
            );

        String startTypeStr = pinfo.envVars().getOrDefault("ES_START_TYPE", "manual");
        int startType = "auto".equalsIgnoreCase(startTypeStr)
            ? Advapi32Constants.SERVICE_AUTO_START
            : Advapi32Constants.SERVICE_DEMAND_START;

        String binaryPath = buildBinaryPath(pinfo);

        String serviceUser = pinfo.envVars().getOrDefault("SERVICE_USERNAME", "LocalSystem");
        String servicePassword = pinfo.envVars().get("SERVICE_PASSWORD");

        return new ServiceInfo(displayName, description, binaryPath, startType, serviceUser, servicePassword);
    }

    /**
     * Builds the service binary path: a {@code java.exe} command line with the minimal
     * properties needed to bootstrap the daemon. The daemon resolves everything else
     * (server JVM options, full classpath for the server process, etc.) at startup.
     */
    static String buildBinaryPath(ProcessInfo pinfo) {
        Map<String, String> sysprops = pinfo.sysprops();
        Path javaExe = getJavaHome(sysprops).resolve("bin").resolve("java.exe");
        String esHome = sysprops.get("es.path.home");

        StringJoiner cmd = new StringJoiner(" ");
        cmd.add(quote(javaExe.toString()));
        cmd.add("-Xms4m");
        cmd.add("-Xmx64m");
        cmd.add("-XX:+UseSerialGC");
        cmd.add(sysProp("es.path.home", esHome));
        cmd.add(sysProp("es.path.conf", sysprops.get("es.path.conf")));
        cmd.add(sysProp("es.distribution.type", sysprops.get("es.distribution.type")));

        String classpath = esHome
            + "\\lib\\*"
            + ";"
            + esHome
            + "\\lib\\tools\\server-cli\\*"
            + ";"
            + esHome
            + "\\lib\\tools\\windows-service-cli\\*";
        cmd.add("-cp");
        cmd.add(quote(classpath));
        cmd.add("org.elasticsearch.windows.service.WindowsServiceDaemon");

        return cmd.toString();
    }

    private static String sysProp(String key, String value) {
        if (value != null && value.contains(" ")) {
            return "-D%s=%s".formatted(key, quote(value));
        }
        return "-D%s=%s".formatted(key, value);
    }

    private static String quote(String s) {
        return '"' + s + '"';
    }

    @SuppressForbidden(reason = "get java home path to pass through")
    private static Path getJavaHome(Map<String, String> sysprops) {
        return Paths.get(sysprops.get("java.home"));
    }

    @Override
    protected String getSuccessMessage(String serviceId) {
        return String.format(Locale.ROOT, "The service '%s' has been installed", serviceId);
    }

    @Override
    protected String getFailureMessage(String serviceId) {
        return String.format(Locale.ROOT, "Failed installing '%s' service", serviceId);
    }
}

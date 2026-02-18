/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.windows.service;

import org.apache.logging.log4j.Level;
import org.elasticsearch.Build;
import org.elasticsearch.bootstrap.ServerArgs;
import org.elasticsearch.cli.ProcessInfo;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.common.cli.EnvironmentBuilder;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.common.settings.KeyStoreWrapper;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.Environment;
import org.elasticsearch.server.cli.JvmOptionsParser;
import org.elasticsearch.server.cli.MachineDependentHeap;
import org.elasticsearch.server.cli.ServerProcess;
import org.elasticsearch.server.cli.ServerProcessBuilder;
import org.elasticsearch.server.cli.ServerProcessUtils;
import org.elasticsearch.service.windows.WindowsServiceHost;

import java.io.IOException;
import java.util.Map;

/**
 * Pure Java Windows service entry point for Elasticsearch.
 *
 * <p>This class registers directly with the Windows Service Control Manager via
 * {@link WindowsServiceHost} and manages the Elasticsearch {@link ServerProcess} lifecycle
 * through the SCM's start/stop callbacks.
 *
 * <p>The JVM is launched by the SCM directly, which loads and start executing this class {@link #main} method.
 * The {@link #main} method calls {@code StartServiceCtrlDispatcherW}, which blocks until the service is stopped.
 */
public class WindowsServiceDaemon {

    private volatile ServerProcess server;

    public static void main(String[] args) throws Exception {
        ProcessInfo processInfo = ProcessInfo.fromSystem();
        configureLogging(processInfo.sysprops());

        WindowsServiceDaemon daemon = new WindowsServiceDaemon();
        String serviceName = processInfo.envVars().getOrDefault("SERVICE_ID", "elasticsearch-service-x64");

        WindowsServiceHost host = new WindowsServiceHost(new WindowsServiceHost.ServiceCallback() {
            @Override
            public void onStart() throws Exception {
                daemon.start(processInfo);
            }

            @Override
            public void onStop() throws Exception {
                daemon.stop();
            }
        });
        host.run(serviceName);
    }

    void start(ProcessInfo processInfo) throws Exception {
        Environment env = EnvironmentBuilder.createEnvironment(Map.of(), processInfo, Build.current().type());

        try (var loadedSecrets = KeyStoreWrapper.bootstrap(env.configDir(), () -> new SecureString(new char[0]))) {
            var serverArgs = new ServerArgs(false, true, null, loadedSecrets, env.settings(), env.configDir(), env.logsDir());
            var tempDir = ServerProcessUtils.setupTempDir(processInfo);
            var jvmOptions = JvmOptionsParser.determineJvmOptions(serverArgs, processInfo, tempDir, new MachineDependentHeap());
            var serverProcessBuilder = new ServerProcessBuilder().withTerminal(Terminal.DEFAULT)
                .withProcessInfo(processInfo)
                .withServerArgs(serverArgs)
                .withTempDir(tempDir)
                .withJvmOptions(jvmOptions);
            this.server = serverProcessBuilder.start();
        }
    }

    void stop() throws IOException {
        if (server != null) {
            server.stop();
        }
    }

    private static void configureLogging(Map<String, String> sysprops) {
        final String loggerLevel = sysprops.getOrDefault("es.logger.level", Level.INFO.name());
        final Settings settings = Settings.builder().put("logger.level", loggerLevel).build();
        LogConfigurator.configureWithoutConfig(settings);
        LogConfigurator.configureESLogging();
    }
}

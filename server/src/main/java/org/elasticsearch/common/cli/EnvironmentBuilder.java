/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.common.cli;

import org.elasticsearch.Build;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.ProcessInfo;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.core.SuppressForbidden;
import org.elasticsearch.env.Environment;
import org.elasticsearch.node.InternalSettingsPreparer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Utility for creating an {@link Environment} from process info and settings.
 *
 * <p>This decouples environment creation from the {@link EnvironmentAwareCommand} hierarchy so that
 * non-CLI entry points (e.g. a Windows service host) can bootstrap an {@link Environment} without
 * inheriting from {@link org.elasticsearch.cli.Command}.
 */
public class EnvironmentBuilder {

    private static final String DOCKER_UPPERCASE_SETTING_PREFIX = "ES_SETTING_";
    private static final Pattern DOCKER_LOWERCASE_SETTING_REGEX = Pattern.compile("[-a-z0-9_]+(\\.[-a-z0-9_]+)+");

    /**
     * Creates an {@link Environment} from the given settings and process info.
     *
     * @param settings additional settings (e.g. from CLI {@code -E} options); may be empty
     * @param processInfo the current process info (system properties, environment variables, working dir)
     * @param buildType the build type, used to determine whether Docker env var translation applies
     * @return the constructed environment
     * @throws UserException if required configuration is missing or conflicting
     */
    public static Environment createEnvironment(Map<String, String> settings, ProcessInfo processInfo, Build.Type buildType)
        throws UserException {
        var mergedSettings = new HashMap<>(settings);

        if (buildType == Build.Type.DOCKER) {
            putDockerEnvSettings(mergedSettings, processInfo.envVars());
        }

        putSystemPropertyIfSettingIsMissing(processInfo.sysprops(), mergedSettings, "path.data", "es.path.data");
        putSystemPropertyIfSettingIsMissing(processInfo.sysprops(), mergedSettings, "path.home", "es.path.home");
        putSystemPropertyIfSettingIsMissing(processInfo.sysprops(), mergedSettings, "path.logs", "es.path.logs");

        final String esPathConf = processInfo.sysprops().get("es.path.conf");
        if (esPathConf == null) {
            throw new UserException(ExitCodes.CONFIG, "the system property [es.path.conf] must be set");
        }
        return InternalSettingsPreparer.prepareEnvironment(
            Settings.EMPTY,
            mergedSettings,
            getConfigPath(esPathConf),
            // HOSTNAME is set by elasticsearch-env and elasticsearch-env.bat so it is always available
            () -> processInfo.envVars().get("HOSTNAME")
        );
    }

    static void putDockerEnvSettings(Map<String, String> settings, Map<String, String> envVars) {
        for (var envVar : envVars.entrySet()) {
            String key = envVar.getKey();
            if (DOCKER_LOWERCASE_SETTING_REGEX.matcher(key).matches()) {
                settings.put(key, envVar.getValue());
            } else if (key.startsWith(DOCKER_UPPERCASE_SETTING_PREFIX)) {
                key = key.substring(DOCKER_UPPERCASE_SETTING_PREFIX.length());
                key = key.replace('_', '.');
                key = key.replace("..", "_");
                key = key.toLowerCase(Locale.ROOT);
                settings.put(key, envVar.getValue());
            }
        }
    }

    static void putSystemPropertyIfSettingIsMissing(
        final Map<String, String> sysprops,
        final Map<String, String> settings,
        final String setting,
        final String key
    ) throws UserException {
        final String value = sysprops.get(key);
        if (value != null) {
            if (settings.containsKey(setting)) {
                final String message = String.format(
                    Locale.ROOT,
                    "setting [%s] found via command-line -E and system property [%s]",
                    setting,
                    key
                );
                throw new UserException(ExitCodes.USAGE, message);
            } else {
                settings.put(setting, value);
            }
        }
    }

    @SuppressForbidden(reason = "need path to construct environment")
    private static Path getConfigPath(final String pathConf) {
        return Paths.get(pathConf);
    }

    private EnvironmentBuilder() {}
}

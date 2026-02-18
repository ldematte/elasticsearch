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
import org.elasticsearch.cli.Command;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.service.windows.Advapi32Constants;
import org.elasticsearch.service.windows.WindowsServiceException;
import org.junit.Before;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class WindowsServiceInstallCommandTests extends ScmCommandTestCase {

    Path javaHome;
    MockInstallServiceControl installServiceControl;

    /**
     * Extends the base mock to capture install-specific fields (binary path, display name,
     * start type, service user/password, description).
     */
    static class MockInstallServiceControl extends MockWindowsServiceControl {
        String createdDisplayName;
        String createdBinaryPath;
        int createdStartType;
        String createdServiceUser;
        String createdServicePassword;
        String descriptionText;
        WindowsServiceException createException;

        @Override
        public void createService(
            String serviceId,
            String displayName,
            String binaryPath,
            int startType,
            String serviceUser,
            String servicePassword
        ) throws WindowsServiceException {
            super.createService(serviceId, displayName, binaryPath, startType, serviceUser, servicePassword);
            if (createException != null) {
                throw createException;
            }
            this.createdDisplayName = displayName;
            this.createdBinaryPath = binaryPath;
            this.createdStartType = startType;
            this.createdServiceUser = serviceUser;
            this.createdServicePassword = servicePassword;
        }

        @Override
        public void setServiceDescription(String serviceId, String description) {
            super.setServiceDescription(serviceId, description);
            this.descriptionText = description;
        }
    }

    public WindowsServiceInstallCommandTests(boolean spaceInPath) {
        super(spaceInPath);
    }

    @Before
    public void setupInstall() throws Exception {
        javaHome = createTempDir();
        Path javaExe = javaHome.resolve("bin").resolve("java.exe");
        Files.createDirectories(javaExe.getParent());
        Files.createFile(javaExe);
        sysprops.put("java.home", javaHome.toString());
        sysprops.put("es.distribution.type", "zip");
        envVars.put("COMPUTERNAME", "mycomputer");
        installServiceControl = new MockInstallServiceControl();
        mockServiceControl = installServiceControl;
    }

    @Override
    protected Command newCommand() {
        return new WindowsServiceInstallCommand(installServiceControl);
    }

    @Override
    protected String getExpectedOperation() {
        return "create";
    }

    @Override
    protected String getDefaultSuccessMessage() {
        return "The service 'elasticsearch-service-x64' has been installed";
    }

    @Override
    protected String getDefaultFailureMessage() {
        return "Failed installing 'elasticsearch-service-x64' service";
    }

    public void testPreExecuteOutput() throws Exception {
        envVars.put("SERVICE_ID", "myservice");
        assertOkWithOutput(
            allOf(containsString("Installing service : myservice"), containsString("Using ES_JAVA_HOME : " + javaHome)),
            emptyString()
        );
    }

    public void testJavaMissing() throws Exception {
        Files.delete(javaHome.resolve("bin").resolve("java.exe"));
        assertThat(executeMain(), equalTo(ExitCodes.CONFIG));
        assertThat(terminal.getErrorOutput(), containsString("Invalid java installation (no java.exe"));
    }

    public void testBinaryPathContainsJavaExe() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdBinaryPath, containsString("java.exe"));
    }

    public void testBinaryPathContainsMainClass() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdBinaryPath, containsString("WindowsServiceDaemon"));
    }

    public void testBinaryPathContainsEsHome() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdBinaryPath, containsString("-Des.path.home="));
        assertThat(installServiceControl.createdBinaryPath, containsString(esHomeDir.toString()));
    }

    public void testBinaryPathContainsClasspath() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdBinaryPath, containsString("lib\\tools\\server-cli"));
        assertThat(installServiceControl.createdBinaryPath, containsString("lib\\tools\\windows-service-cli"));
    }

    public void testBinaryPathContainsJvmOptions() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdBinaryPath, containsString("-XX:+UseSerialGC"));
        assertThat(installServiceControl.createdBinaryPath, containsString("-Xms4m"));
        assertThat(installServiceControl.createdBinaryPath, containsString("-Xmx64m"));
    }

    public void testBinaryPathDoesNotContainServerSpecificOptions() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdBinaryPath, not(containsString("cli.name")));
        assertThat(installServiceControl.createdBinaryPath, not(containsString("cli.libs")));
        assertThat(installServiceControl.createdBinaryPath, not(containsString("CliToolLauncher")));
    }

    public void testStartupType() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdStartType, equalTo(Advapi32Constants.SERVICE_DEMAND_START));

        installServiceControl = new MockInstallServiceControl();
        mockServiceControl = installServiceControl;
        terminal.reset();
        envVars.put("ES_START_TYPE", "auto");
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdStartType, equalTo(Advapi32Constants.SERVICE_AUTO_START));
    }

    public void testDisplayName() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        String expectedDefault = "Elasticsearch %s (elasticsearch-service-x64)".formatted(Build.current().version());
        assertThat(installServiceControl.createdDisplayName, equalTo(expectedDefault));

        installServiceControl = new MockInstallServiceControl();
        mockServiceControl = installServiceControl;
        terminal.reset();
        envVars.put("SERVICE_DISPLAY_NAME", "my service name");
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdDisplayName, equalTo("my service name"));
    }

    public void testDescription() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        String expectedDefault = String.format(
            Locale.ROOT,
            "Elasticsearch %s Windows Service - https://elastic.co",
            Build.current().version()
        );
        assertThat(installServiceControl.descriptionText, equalTo(expectedDefault));

        installServiceControl = new MockInstallServiceControl();
        mockServiceControl = installServiceControl;
        terminal.reset();
        envVars.put("SERVICE_DESCRIPTION", "my description");
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.descriptionText, equalTo("my description"));
    }

    public void testUsernamePassword() throws Exception {
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdServiceUser, equalTo("LocalSystem"));

        terminal.reset();
        envVars.put("SERVICE_USERNAME", "myuser");
        assertThat(executeMain(), equalTo(ExitCodes.CONFIG));
        assertThat(terminal.getErrorOutput(), containsString("Both service username and password must be set"));

        terminal.reset();
        envVars.remove("SERVICE_USERNAME");
        envVars.put("SERVICE_PASSWORD", "mypassword");
        assertThat(executeMain(), equalTo(ExitCodes.CONFIG));
        assertThat(terminal.getErrorOutput(), containsString("Both service username and password must be set"));

        installServiceControl = new MockInstallServiceControl();
        mockServiceControl = installServiceControl;
        terminal.reset();
        envVars.put("SERVICE_USERNAME", "myuser");
        envVars.put("SERVICE_PASSWORD", "mypassword");
        assertOkWithOutput(containsString("has been installed"), emptyString());
        assertThat(installServiceControl.createdServiceUser, equalTo("myuser"));
        assertThat(installServiceControl.createdServicePassword, equalTo("mypassword"));
    }

    public void testCreateServiceFailure() throws Exception {
        installServiceControl.createException = new WindowsServiceException("access denied", 5);
        assertThat(executeMain(), equalTo(ExitCodes.CODE_ERROR));
        assertThat(terminal.getErrorOutput(), containsString("Failed installing"));
        assertThat(terminal.getErrorOutput(), containsString("access denied"));
    }
}

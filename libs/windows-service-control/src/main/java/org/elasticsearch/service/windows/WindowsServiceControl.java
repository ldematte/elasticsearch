/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.service.windows;

/**
 * Interface for controlling Windows services via the Service Control Manager.
 *
 * <p>The default implementation ({@link NativeWindowsServiceControl}) uses Panama FFI to call
 * the Windows SCM APIs directly. Tests can provide mock implementations.
 */
public interface WindowsServiceControl {

    /**
     * Starts the specified service.
     * @param serviceId the service name
     * @throws WindowsServiceException if the service could not be started
     */
    void startService(String serviceId) throws WindowsServiceException;

    /**
     * Sends a stop control code to the specified service.
     * This returns after the stop has been initiated; the service may still be in STOP_PENDING state.
     * @param serviceId the service name
     * @throws WindowsServiceException if the stop command could not be sent
     */
    void stopService(String serviceId) throws WindowsServiceException;

    /**
     * Marks the specified service for deletion from the SCM database.
     * @param serviceId the service name
     * @throws WindowsServiceException if the service could not be deleted
     */
    void deleteService(String serviceId) throws WindowsServiceException;

    /**
     * Queries the current status of the specified service.
     * @param serviceId the service name
     * @return the current service status
     * @throws WindowsServiceException if the status could not be queried
     */
    ServiceStatus queryStatus(String serviceId) throws WindowsServiceException;

    /**
     * Creates a new Windows service in the SCM database.
     *
     * @param serviceId the service name
     * @param displayName the display name shown in the Services console
     * @param binaryPath the full command line for the service binary (e.g. path to java.exe with arguments)
     * @param startType one of {@code SERVICE_AUTO_START} or {@code SERVICE_DEMAND_START}
     * @param serviceUser the account under which the service runs (e.g. "LocalSystem"), or null for LocalSystem
     * @param servicePassword the password for the service account, or null
     * @throws WindowsServiceException if the service could not be created
     */
    void createService(String serviceId, String displayName, String binaryPath, int startType, String serviceUser, String servicePassword)
        throws WindowsServiceException;

    /**
     * Sets the description of an existing service.
     *
     * @param serviceId the service name
     * @param description the description text
     * @throws WindowsServiceException if the description could not be set
     */
    void setServiceDescription(String serviceId, String description) throws WindowsServiceException;

    /**
     * Creates the default native implementation backed by Panama FFI calls to advapi32.dll.
     */
    static WindowsServiceControl create() {
        return new NativeWindowsServiceControl();
    }
}

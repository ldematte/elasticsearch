/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.service.windows;

import org.elasticsearch.logging.LogManager;
import org.elasticsearch.logging.Logger;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.CountDownLatch;

/**
 * Hosts a Java application as a Windows service by interacting with the Service Control Manager.
 *
 * <p>This class handles the full SCM lifecycle:
 * <ol>
 *   <li>Registers with the SCM via {@code StartServiceCtrlDispatcherW}</li>
 *   <li>In the {@code ServiceMain} callback, registers a control handler and reports status</li>
 *   <li>Invokes the user-supplied {@link ServiceCallback} to start the application</li>
 *   <li>Blocks until a stop control code is received from the SCM</li>
 *   <li>Invokes the callback to stop the application and reports STOPPED</li>
 * </ol>
 *
 * <p>The caller's {@code main} method should simply call {@link #run(String)}.
 * This method blocks until the service is stopped.
 *
 * @see <a href="https://learn.microsoft.com/en-us/windows/win32/services/writing-a-servicemain-function">
 *      Writing a ServiceMain Function (Microsoft Learn)</a>
 */
public final class WindowsServiceHost {

    /**
     * Callback interface for the service application. Implementations handle the actual
     * work of starting and stopping the application.
     */
    public interface ServiceCallback {
        /**
         * Called when the SCM requests the service to start.
         * This method should start the application and return promptly; it must not block
         * for the lifetime of the service. The service is reported as RUNNING after this returns.
         *
         * @throws Exception if the service fails to start
         */
        void onStart() throws Exception;

        /**
         * Called when the SCM requests the service to stop.
         * This method should initiate a graceful shutdown. It may block while shutdown completes.
         * The service is reported as STOPPED after this returns.
         *
         * @throws Exception if the service fails to stop cleanly
         */
        void onStop() throws Exception;
    }

    private static final Logger logger = LogManager.getLogger(WindowsServiceHost.class);

    private static final int NO_ERROR = 0;
    private static final int STOP_PENDING_WAIT_HINT_MS = 30_000;
    private static final int START_PENDING_WAIT_HINT_MS = 30_000;

    private final Arena runtimeArena = Arena.ofShared();
    private final Advapi32 advapi32 = new Advapi32();
    private final CountDownLatch stopLatch = new CountDownLatch(1);
    private final ServiceCallback callback;

    private volatile MemorySegment serviceStatusHandle = MemorySegment.NULL;
    private volatile MemorySegment serviceStatusMem = MemorySegment.NULL;

    private volatile int exitCode = NO_ERROR;

    /**
     * Constructs a WindowsServiceHost with the provided callback.
     *
     * @param callback the application lifecycle callback
     */
    public WindowsServiceHost(ServiceCallback callback) {
        this.callback = callback;
    }

    /**
     * Registers this process with the SCM and blocks until the service is stopped.
     *
     * <p>This must be called from the process's main thread. The SCM will call back into
     * {@code serviceMain} on a separate thread, which in turn invokes the callback.
     *
     * @param serviceName the service name as registered in the SCM
     * @throws WindowsServiceException if SCM registration fails
     */
    public void run(String serviceName) throws WindowsServiceException {
        final MethodHandle serviceMainMH;
        try {
            serviceMainMH = MethodHandles.lookup()
                .findVirtual(WindowsServiceHost.class, "serviceMain", MethodType.methodType(void.class, int.class, MemorySegment.class))
                .bindTo(this);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }

        MemorySegment serviceMainPtr = PanamaUtil.upcallStub(serviceMainMH, Advapi32.SERVICE_MAIN_DESCRIPTOR, runtimeArena);
        MemorySegment table = Advapi32.buildServiceTable(runtimeArena, serviceName, serviceMainPtr);

        if (advapi32.startServiceCtrlDispatcher(table) == false) {
            throw new WindowsServiceException(
                "StartServiceCtrlDispatcherW failed (is this process started by the SCM?)",
                advapi32.getLastError()
            );
        }
    }

    /**
     * ServiceMain callback invoked by the SCM on a dedicated thread.
     * Must not be called directly.
     */
    @SuppressWarnings("unused") // called via upcall from native code
    void serviceMain(int argc, MemorySegment argv) {
        serviceStatusMem = runtimeArena.allocate(Advapi32.SERVICE_STATUS_LAYOUT);

        final MethodHandle handlerMH;
        try {
            handlerMH = MethodHandles.lookup()
                .findVirtual(
                    WindowsServiceHost.class,
                    "handlerEx",
                    MethodType.methodType(int.class, int.class, int.class, MemorySegment.class, MemorySegment.class)
                )
                .bindTo(this);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }

        MemorySegment handlerPtr = PanamaUtil.upcallStub(handlerMH, Advapi32.HANDLER_EX_DESCRIPTOR, runtimeArena);
        MemorySegment serviceNameW = PanamaUtil.allocateWideString(runtimeArena, "");
        serviceStatusHandle = advapi32.registerServiceCtrlHandlerEx(serviceNameW, handlerPtr, MemorySegment.NULL);

        if (serviceStatusHandle.equals(MemorySegment.NULL) || serviceStatusHandle.address() == 0) {
            // Cannot report status to the SCM without a valid handle; the SCM will
            // eventually time out and mark the service as failed.
            logger.error("RegisterServiceCtrlHandlerExW failed (Win32 error [{}]); service cannot report status", advapi32.getLastError());
            exitCode = 1;
            return;
        }

        reportStatus(Advapi32.SERVICE_START_PENDING, 0, 1, START_PENDING_WAIT_HINT_MS, NO_ERROR);

        try {
            callback.onStart();
        } catch (Exception e) {
            logger.error("service failed to start", e);
            reportStatus(Advapi32.SERVICE_STOPPED, 0, 0, 0, 1);
            exitCode = 1;
            return;
        }

        reportStatus(Advapi32.SERVICE_RUNNING, Advapi32.SERVICE_ACCEPT_STOP, 0, 0, NO_ERROR);

        try {
            stopLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        reportStatus(Advapi32.SERVICE_STOP_PENDING, 0, 1, STOP_PENDING_WAIT_HINT_MS, NO_ERROR);

        try {
            callback.onStop();
        } catch (Exception e) {
            logger.error("service failed to stop cleanly", e);
            exitCode = 1;
        }

        reportStatus(Advapi32.SERVICE_STOPPED, 0, 0, 0, exitCode);
    }

    /**
     * HandlerEx callback invoked by the SCM when a control code arrives.
     * Must not be called directly.
     */
    @SuppressWarnings("unused") // called via upcall from native code
    int handlerEx(int control, int eventType, MemorySegment eventData, MemorySegment context) {
        if (control == Advapi32.SERVICE_CONTROL_STOP) {
            reportStatus(Advapi32.SERVICE_STOP_PENDING, 0, 1, STOP_PENDING_WAIT_HINT_MS, NO_ERROR);
            stopLatch.countDown();
            return NO_ERROR;
        }

        if (control == Advapi32.SERVICE_CONTROL_INTERROGATE) {
            return NO_ERROR;
        }

        return NO_ERROR;
    }

    private void reportStatus(int currentState, int controlsAccepted, int checkPoint, int waitHintMs, int win32ExitCode) {
        MemorySegment h = serviceStatusHandle;
        if (h.equals(MemorySegment.NULL) == false && h.address() != 0) {
            Advapi32.fillServiceStatus(
                serviceStatusMem,
                Advapi32.SERVICE_WIN32_OWN_PROCESS,
                currentState,
                controlsAccepted,
                win32ExitCode,
                checkPoint,
                waitHintMs
            );
            advapi32.setServiceStatus(h, serviceStatusMem);
        }
    }
}

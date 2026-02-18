/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.service.windows;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Panama FFI bindings for the Windows Service Control Manager APIs in {@code advapi32.dll}.
 *
 * @see <a href="https://learn.microsoft.com/en-us/windows/win32/services/service-functions">Service Functions</a>
 */
class Advapi32 {

    // --- Access rights ---

    /** Required to connect to the SCM and enumerate/open services. */
    static final int SC_MANAGER_ALL_ACCESS = 0xF003F;

    /** Full access to a service object. */
    static final int SERVICE_ALL_ACCESS = 0xF01FF;

    // --- Service start types ---

    static final int SERVICE_AUTO_START = 0x00000002;
    static final int SERVICE_DEMAND_START = 0x00000003;

    // --- Error control ---

    static final int SERVICE_ERROR_NORMAL = 0x00000001;

    // --- Service config info levels ---

    static final int SERVICE_CONFIG_DESCRIPTION = 1;

    // --- Service control codes ---

    static final int SERVICE_CONTROL_STOP = 0x00000001;

    // --- Service types ---

    static final int SERVICE_WIN32_OWN_PROCESS = 0x00000010;

    // --- Service states ---

    static final int SERVICE_STOPPED = 0x00000001;
    static final int SERVICE_START_PENDING = 0x00000002;
    static final int SERVICE_STOP_PENDING = 0x00000003;
    static final int SERVICE_RUNNING = 0x00000004;

    // --- Controls accepted ---

    static final int SERVICE_ACCEPT_STOP = 0x00000001;

    // --- Service control codes (additional) ---

    static final int SERVICE_CONTROL_INTERROGATE = 0x00000004;

    // --- QueryServiceStatusEx info level ---

    static final int SC_STATUS_PROCESS_INFO = 0;

    // --- SERVICE_STATUS_PROCESS layout (36 bytes on 32-bit aligned fields) ---

    static final StructLayout SERVICE_STATUS_PROCESS_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("dwServiceType"),
        JAVA_INT.withName("dwCurrentState"),
        JAVA_INT.withName("dwControlsAccepted"),
        JAVA_INT.withName("dwWin32ExitCode"),
        JAVA_INT.withName("dwServiceSpecificExitCode"),
        JAVA_INT.withName("dwCheckPoint"),
        JAVA_INT.withName("dwWaitHint"),
        JAVA_INT.withName("dwProcessId"),
        JAVA_INT.withName("dwServiceFlags")
    );

    static final VarHandle dwCurrentState$vh = PanamaUtil.varHandleWithoutOffset(
        SERVICE_STATUS_PROCESS_LAYOUT,
        groupElement("dwCurrentState")
    );
    static final VarHandle dwWin32ExitCode$vh = PanamaUtil.varHandleWithoutOffset(
        SERVICE_STATUS_PROCESS_LAYOUT,
        groupElement("dwWin32ExitCode")
    );
    static final VarHandle dwCheckPoint$vh = PanamaUtil.varHandleWithoutOffset(SERVICE_STATUS_PROCESS_LAYOUT, groupElement("dwCheckPoint"));
    static final VarHandle dwWaitHint$vh = PanamaUtil.varHandleWithoutOffset(SERVICE_STATUS_PROCESS_LAYOUT, groupElement("dwWaitHint"));

    // --- SERVICE_STATUS layout (used by ControlService, 28 bytes) ---

    static final StructLayout SERVICE_STATUS_LAYOUT = MemoryLayout.structLayout(
        JAVA_INT.withName("dwServiceType"),
        JAVA_INT.withName("dwCurrentState"),
        JAVA_INT.withName("dwControlsAccepted"),
        JAVA_INT.withName("dwWin32ExitCode"),
        JAVA_INT.withName("dwServiceSpecificExitCode"),
        JAVA_INT.withName("dwCheckPoint"),
        JAVA_INT.withName("dwWaitHint")
    );

    // --- GetLastError capture ---

    private static final StructLayout CAPTURE_GETLASTERROR_LAYOUT = Linker.Option.captureStateLayout();
    private static final Linker.Option CAPTURE_GETLASTERROR_OPTION = Linker.Option.captureCallState("GetLastError");
    private static final VarHandle GetLastError$vh = PanamaUtil.varHandleWithoutOffset(
        CAPTURE_GETLASTERROR_LAYOUT,
        groupElement("GetLastError")
    );

    // --- Function handles ---

    private static final SymbolLookup LOOKUP;

    static {
        System.loadLibrary("advapi32");
        SymbolLookup loaderLookup = SymbolLookup.loaderLookup();
        LOOKUP = name -> loaderLookup.find(name).or(() -> Linker.nativeLinker().defaultLookup().find(name));
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return PanamaUtil.downcallHandle(PanamaUtil.findFunction(LOOKUP, name), descriptor, CAPTURE_GETLASTERROR_OPTION);
    }

    // SC_HANDLE OpenSCManagerW(LPCWSTR lpMachineName, LPCWSTR lpDatabaseName, DWORD dwDesiredAccess)
    private static final MethodHandle OpenSCManagerW$mh = downcall(
        "OpenSCManagerW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
    );

    // SC_HANDLE OpenServiceW(SC_HANDLE hSCManager, LPCWSTR lpServiceName, DWORD dwDesiredAccess)
    private static final MethodHandle OpenServiceW$mh = downcall(
        "OpenServiceW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT)
    );

    // BOOL StartServiceW(SC_HANDLE hService, DWORD dwNumServiceArgs, LPCWSTR *lpServiceArgVectors)
    private static final MethodHandle StartServiceW$mh = downcall(
        "StartServiceW",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    );

    // BOOL ControlService(SC_HANDLE hService, DWORD dwControl, LPSERVICE_STATUS lpServiceStatus)
    private static final MethodHandle ControlService$mh = downcall(
        "ControlService",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    );

    // BOOL DeleteService(SC_HANDLE hService)
    private static final MethodHandle DeleteService$mh = downcall("DeleteService", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // BOOL QueryServiceStatusEx(SC_HANDLE hService, SC_STATUS_TYPE InfoLevel, LPBYTE lpBuffer, DWORD cbBufSize, LPDWORD pcbBytesNeeded)
    private static final MethodHandle QueryServiceStatusEx$mh = downcall(
        "QueryServiceStatusEx",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    );

    // BOOL CloseServiceHandle(SC_HANDLE hSCObject)
    private static final MethodHandle CloseServiceHandle$mh = downcall("CloseServiceHandle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // SC_HANDLE CreateServiceW(SC_HANDLE hSCManager, LPCWSTR lpServiceName, LPCWSTR lpDisplayName,
    // DWORD dwDesiredAccess, DWORD dwServiceType, DWORD dwStartType, DWORD dwErrorControl,
    // LPCWSTR lpBinaryPathName, LPCWSTR lpLoadOrderGroup, LPDWORD lpdwTagId,
    // LPCWSTR lpDependencies, LPCWSTR lpServiceStartName, LPCWSTR lpPassword)
    private static final MethodHandle CreateServiceW$mh = downcall(
        "CreateServiceW",
        FunctionDescriptor.of(
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            JAVA_INT,
            JAVA_INT,
            JAVA_INT,
            JAVA_INT,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS,
            ADDRESS
        )
    );

    // BOOL ChangeServiceConfig2W(SC_HANDLE hService, DWORD dwInfoLevel, LPVOID lpInfo)
    private static final MethodHandle ChangeServiceConfig2W$mh = downcall(
        "ChangeServiceConfig2W",
        FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS)
    );

    // --- Service hosting function descriptors ---

    // SERVICE_TABLE_ENTRYW: { LPCWSTR lpServiceName, LPSERVICE_MAIN_FUNCTIONW lpServiceProc }
    static final GroupLayout SERVICE_TABLE_ENTRYW_LAYOUT = MemoryLayout.structLayout(
        ADDRESS.withName("lpServiceName"),
        ADDRESS.withName("lpServiceProc")
    );

    // void WINAPI ServiceMain(DWORD dwArgc, LPWSTR *lpszArgv)
    static final FunctionDescriptor SERVICE_MAIN_DESCRIPTOR = FunctionDescriptor.ofVoid(JAVA_INT, ADDRESS);

    // DWORD WINAPI HandlerEx(DWORD dwControl, DWORD dwEventType, LPVOID lpEventData, LPVOID lpContext)
    static final FunctionDescriptor HANDLER_EX_DESCRIPTOR = FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);

    // BOOL StartServiceCtrlDispatcherW(const SERVICE_TABLE_ENTRYW *lpServiceStartTable)
    private static final MethodHandle StartServiceCtrlDispatcherW$mh = downcall(
        "StartServiceCtrlDispatcherW",
        FunctionDescriptor.of(JAVA_INT, ADDRESS)
    );

    // SERVICE_STATUS_HANDLE RegisterServiceCtrlHandlerExW(LPCWSTR, LPHANDLER_FUNCTION_EX, LPVOID)
    private static final MethodHandle RegisterServiceCtrlHandlerExW$mh = downcall(
        "RegisterServiceCtrlHandlerExW",
        FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS)
    );

    // BOOL SetServiceStatus(SERVICE_STATUS_HANDLE, LPSERVICE_STATUS)
    private static final MethodHandle SetServiceStatus$mh = downcall("SetServiceStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    // --- Instance state for GetLastError ---

    private final MemorySegment lastErrorState;

    Advapi32() {
        this.lastErrorState = Arena.ofAuto().allocate(CAPTURE_GETLASTERROR_LAYOUT);
    }

    int getLastError() {
        return (int) GetLastError$vh.get(lastErrorState);
    }

    MemorySegment openSCManager(int desiredAccess) {
        try {
            return (MemorySegment) OpenSCManagerW$mh.invokeExact(lastErrorState, MemorySegment.NULL, MemorySegment.NULL, desiredAccess);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    MemorySegment openService(MemorySegment scManager, MemorySegment serviceName, int desiredAccess) {
        try {
            return (MemorySegment) OpenServiceW$mh.invokeExact(lastErrorState, scManager, serviceName, desiredAccess);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean startService(MemorySegment service) {
        try {
            return ((int) StartServiceW$mh.invokeExact(lastErrorState, service, 0, MemorySegment.NULL)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean controlService(MemorySegment service, int control, MemorySegment serviceStatus) {
        try {
            return ((int) ControlService$mh.invokeExact(lastErrorState, service, control, serviceStatus)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean deleteService(MemorySegment service) {
        try {
            return ((int) DeleteService$mh.invokeExact(lastErrorState, service)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean queryServiceStatusEx(MemorySegment service, MemorySegment buffer, int bufferSize, MemorySegment bytesNeeded) {
        try {
            return ((int) QueryServiceStatusEx$mh.invokeExact(
                lastErrorState,
                service,
                SC_STATUS_PROCESS_INFO,
                buffer,
                bufferSize,
                bytesNeeded
            )) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean closeServiceHandle(MemorySegment handle) {
        try {
            return ((int) CloseServiceHandle$mh.invokeExact(lastErrorState, handle)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    MemorySegment createService(
        MemorySegment scManager,
        MemorySegment serviceName,
        MemorySegment displayName,
        int desiredAccess,
        int serviceType,
        int startType,
        int errorControl,
        MemorySegment binaryPathName,
        MemorySegment serviceStartName,
        MemorySegment password
    ) {
        try {
            return (MemorySegment) CreateServiceW$mh.invokeExact(
                lastErrorState,
                scManager,
                serviceName,
                displayName,
                desiredAccess,
                serviceType,
                startType,
                errorControl,
                binaryPathName,
                MemorySegment.NULL, // lpLoadOrderGroup
                MemorySegment.NULL, // lpdwTagId
                MemorySegment.NULL, // lpDependencies
                serviceStartName,
                password
            );
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean changeServiceConfig2(MemorySegment service, int infoLevel, MemorySegment info) {
        try {
            return ((int) ChangeServiceConfig2W$mh.invokeExact(lastErrorState, service, infoLevel, info)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    // --- Service hosting methods ---

    boolean startServiceCtrlDispatcher(MemorySegment serviceTable) {
        try {
            return ((int) StartServiceCtrlDispatcherW$mh.invokeExact(lastErrorState, serviceTable)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    MemorySegment registerServiceCtrlHandlerEx(MemorySegment serviceName, MemorySegment handlerProc, MemorySegment context) {
        try {
            return (MemorySegment) RegisterServiceCtrlHandlerExW$mh.invokeExact(lastErrorState, serviceName, handlerProc, context);
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    boolean setServiceStatus(MemorySegment statusHandle, MemorySegment serviceStatus) {
        try {
            return ((int) SetServiceStatus$mh.invokeExact(lastErrorState, statusHandle, serviceStatus)) != 0;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    /**
     * Builds a SERVICE_TABLE_ENTRYW array with one entry and a null terminator.
     */
    static MemorySegment buildServiceTable(Arena arena, String serviceName, MemorySegment serviceMainPtr) {
        long entrySize = SERVICE_TABLE_ENTRYW_LAYOUT.byteSize();
        MemorySegment table = arena.allocate(entrySize * 2, SERVICE_TABLE_ENTRYW_LAYOUT.byteAlignment());

        MemorySegment nameW = PanamaUtil.allocateWideString(arena, serviceName);

        long offName = SERVICE_TABLE_ENTRYW_LAYOUT.byteOffset(groupElement("lpServiceName"));
        long offProc = SERVICE_TABLE_ENTRYW_LAYOUT.byteOffset(groupElement("lpServiceProc"));

        // First entry
        table.set(ADDRESS, offName, nameW);
        table.set(ADDRESS, offProc, serviceMainPtr);

        // Null terminator entry
        MemorySegment terminator = table.asSlice(entrySize, entrySize);
        terminator.set(ADDRESS, offName, MemorySegment.NULL);
        terminator.set(ADDRESS, offProc, MemorySegment.NULL);

        return table;
    }

    /**
     * Fills a SERVICE_STATUS struct for use with SetServiceStatus.
     */
    static void fillServiceStatus(
        MemorySegment ss,
        int serviceType,
        int currentState,
        int controlsAccepted,
        int win32ExitCode,
        int checkPoint,
        int waitHint
    ) {
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwServiceType")), serviceType);
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwCurrentState")), currentState);
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwControlsAccepted")), controlsAccepted);
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwWin32ExitCode")), win32ExitCode);
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwServiceSpecificExitCode")), 0);
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwCheckPoint")), checkPoint);
        ss.set(JAVA_INT, SERVICE_STATUS_LAYOUT.byteOffset(groupElement("dwWaitHint")), waitHint);
    }
}

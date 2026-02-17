/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.server.launcher;

import org.elasticsearch.server.launcher.common.LaunchDescriptor;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal launcher for the Elasticsearch server process.
 *
 * <p> This program reads a {@link LaunchDescriptor} from a binary file, spawns the server JVM process,
 * pipes the serialized ServerArgs bytes to the server's stdin, pumps stderr for the ready marker,
 * and waits for the server to exit.
 *
 * <p> This program has zero Elasticsearch dependencies beyond the shared launcher-common library.
 */
public class ServerLauncher {

    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private static volatile ServerProcess server;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: server-launcher [--dump] <descriptor-path>");
            System.exit(1);
        }

        boolean dump = false;
        String descriptorPath = null;

        for (String arg : args) {
            if ("--dump".equals(arg)) {
                dump = true;
            } else {
                descriptorPath = arg;
            }
        }

        if (descriptorPath == null) {
            System.err.println("Error: descriptor path is required");
            System.exit(1);
        }

        Path path = Path.of(descriptorPath);
        if (Files.exists(path) == false) {
            System.err.println("Error: descriptor file not found: " + descriptorPath);
            System.exit(1);
        }

        LaunchDescriptor descriptor = LaunchDescriptor.readFrom(path);

        if (dump) {
            System.out.println(descriptor.toHumanReadable());
            return;
        }

        // Delete the descriptor file now that we've read it
        Files.deleteIfExists(path);

        // Register shutdown hook before starting the server
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (shuttingDown) {
                shuttingDown.set(true);
                if (server != null) {
                    try {
                        server.stop();
                    } catch (IOException e) {
                        System.err.println("Error stopping server: " + e.getMessage());
                    }
                }
            }
        }, "server-launcher-shutdown"));

        server = startServer(descriptor);

        if (descriptor.daemonize()) {
            server.detach();
            return;
        }

        int exitCode = server.waitFor();
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static ServerProcess startServer(LaunchDescriptor descriptor) throws Exception {
        ensureWorkingDirExists(descriptor.workingDir());

        List<String> command = new ArrayList<>();
        command.add(descriptor.command());
        command.addAll(descriptor.jvmOptions());
        command.addAll(descriptor.jvmArgs());

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().clear();
        pb.environment().putAll(descriptor.environment());
        pb.directory(new File(descriptor.workingDir()));
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);

        Process jvmProcess = null;
        ErrorPumpThread errorPump;
        boolean success = false;

        try {
            jvmProcess = pb.start();
            errorPump = new ErrorPumpThread(jvmProcess.getErrorStream(), System.err);
            errorPump.start();
            sendServerArgs(descriptor.serverArgsBytes(), jvmProcess.getOutputStream());

            boolean serverOk = errorPump.waitUntilReady();
            if (serverOk == false) {
                int exitCode = jvmProcess.waitFor();
                System.err.println("Elasticsearch died while starting up, exit code: " + exitCode);
                System.exit(exitCode != 0 ? exitCode : 1);
            }
            success = true;
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            if (success == false && jvmProcess != null && jvmProcess.isAlive()) {
                jvmProcess.destroyForcibly();
            }
        }

        return new ServerProcess(jvmProcess, errorPump);
    }

    private static void ensureWorkingDirExists(String workingDir) throws Exception {
        Path path = Path.of(workingDir);
        if (Files.exists(path) && Files.isDirectory(path) == false) {
            System.err.println("Error: working directory exists but is not a directory: " + workingDir);
            System.exit(1);
        }
        Files.createDirectories(path);
    }

    private static void sendServerArgs(byte[] serverArgsBytes, OutputStream processStdin) {
        try {
            processStdin.write(serverArgsBytes);
            processStdin.flush();
        } catch (IOException ignore) {
            // A failure to write here means the process has problems, and it will die anyway.
            // The error pump thread will report the actual error.
        }
    }
}

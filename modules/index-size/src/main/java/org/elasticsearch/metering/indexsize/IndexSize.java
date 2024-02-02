/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.metering.indexsize;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.Scheduler;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Map;
import java.util.function.Supplier;

public class IndexSize extends AllocatedPersistentTask {

    public static final String TASK_NAME = "index-size";
    public static final NodeFeature INDEX_SIZE_SUPPORTED = new NodeFeature("index_size.supported");

    IndexSize(
        long id,
        String type,
        String action,
        String description,
        TaskId parentTask,
        Map<String, String> headers,
        ThreadPool threadPool, // Alternative B
        Supplier<TimeValue> pollIntervalSupplier // Alternative B
    ) {
        super(id, type, action, description, parentTask, headers);
        this.threadPool = threadPool;
        this.pollIntervalSupplier = pollIntervalSupplier;
    }

    @Override
    protected void onCancelled() {
        // Alternative B
        if (scheduled != null) {
            scheduled.cancel();
        }
        markAsCompleted();
    }

    @Nullable
    public static PersistentTasksCustomMetadata.PersistentTask<?> findTask(ClusterState clusterState) {
        PersistentTasksCustomMetadata taskMetadata = clusterState.getMetadata().custom(PersistentTasksCustomMetadata.TYPE);
        return taskMetadata == null ? null : taskMetadata.getTask(TASK_NAME);
    }

    // "HealthNode" style (Alternative A)
    // Just a "token", processing is done externally (HealthInfoPeriodicLogger)
    @Nullable
    public static DiscoveryNode findIndexSizeNode(ClusterState clusterState) {
        PersistentTasksCustomMetadata.PersistentTask<?> task = findTask(clusterState);
        if (task == null || task.isAssigned() == false) {
            return null;
        }
        return clusterState.nodes().get(task.getAssignment().getExecutorNode());
    }

    // "GeoIpDownloader" style (Alternative B)
    private static final Logger logger = LogManager.getLogger(IndexSize.class);
    private final ThreadPool threadPool;
    private volatile Scheduler.ScheduledCancellable scheduled;
    private final Supplier<TimeValue> pollIntervalSupplier;

    void run() {
        if (isCancelled() || isCompleted()) {
            return;
        }
        try {
            doSomething();
        } catch (Exception e) {
            logger.error("exception during IX action", e);
        }
        scheduleNextRun(pollIntervalSupplier.get());
    }

    private void doSomething() {
        // TODO: call IndexSizeService
    }

    private void scheduleNextRun(TimeValue time) {
        if (threadPool.scheduler().isShutdown() == false) {
            scheduled = threadPool.schedule(this::run, time, threadPool.generic());
        }
    }

    /**
     * This method requests the task to be rescheduled and run immediately, presumably because a dynamic property supplied by
     * pollIntervalSupplier has changed. This method does nothing if this task is cancelled, completed, or has not yet been
     * scheduled to run for the first time. It cancels any existing scheduled run.
     */
    void requestReschedule() {
        if (isCancelled() || isCompleted()) {
            return;
        }
        if (scheduled != null && scheduled.cancel()) {
            scheduleNextRun(TimeValue.ZERO);
        }
    }

    // Alternative B
    // IndexSizeTaskState implements PersistentTaskState
    // void updateTaskState() {
    // PlainActionFuture<PersistentTasksCustomMetadata.PersistentTask<?>> future = new PlainActionFuture<>();
    // updatePersistentTaskState(state, future);
    // state = ((IndexSizeTaskState) future.actionGet().getState());
    // }
}

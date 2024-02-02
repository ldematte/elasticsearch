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
import org.elasticsearch.ResourceAlreadyExistsException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.ClusterSettings;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.features.FeatureService;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTaskState;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.persistent.PersistentTasksService;
import org.elasticsearch.tasks.TaskId;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.RemoteTransportException;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.elasticsearch.health.node.selection.HealthNode.TASK_NAME;

/**
 * Persistent task executor that is managing the {@link IndexSize}.
 */
public final class IndexSizeTaskExecutor extends PersistentTasksExecutor<IndexSizeTaskParams> {

    private static final Logger logger = LogManager.getLogger(IndexSizeTaskExecutor.class);

    public static final Setting<Boolean> ENABLED_SETTING = Setting.boolSetting(
        "index-size.task.enabled",
        false,
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    public static final Setting<TimeValue> POLL_INTERVAL_SETTING = Setting.timeSetting(
        "index-size.task.poll.interval",
        TimeValue.timeValueMinutes(5),
        TimeValue.timeValueSeconds(30),
        Setting.Property.Dynamic,
        Setting.Property.NodeScope
    );

    private final ClusterService clusterService;
    private final FeatureService featureService;
    private final ThreadPool threadPool;
    private final AtomicReference<IndexSize> currentTask = new AtomicReference<>();
    private final ClusterStateListener taskStarter;
    private final ClusterStateListener shutdownListener;
    private final PersistentTasksService persistentTasksService;
    private volatile boolean enabled;
    private volatile TimeValue pollInterval;

    IndexSizeTaskExecutor(Client client, ClusterService clusterService, FeatureService featureService, ThreadPool threadPool) {
        super(TASK_NAME, ThreadPool.Names.MANAGEMENT);

        this.clusterService = clusterService;
        this.featureService = featureService;
        this.threadPool = threadPool;
        this.taskStarter = this::startTask;
        this.shutdownListener = this::shuttingDown;
        // Should we pass it via Binder/via PersistentTaskPlugin#getPersistentTasksExecutor?
        this.persistentTasksService = new PersistentTasksService(clusterService, threadPool, client);
        this.enabled = ENABLED_SETTING.get(clusterService.getSettings());
        this.pollInterval = POLL_INTERVAL_SETTING.get(clusterService.getSettings());
    }

    @Override
    protected void nodeOperation(AllocatedPersistentTask task, IndexSizeTaskParams params, PersistentTaskState state) {
        IndexSize indexSizeTask = (IndexSize) task;
        currentTask.set(indexSizeTask);
        DiscoveryNode node = clusterService.localNode();
        logger.info("Node [{{}}{{}}] is selected as the current IX node.", node.getName(), node.getId());

        // Alternative B (if we follow the "GeoIp" model)
        if (this.enabled) {
            indexSizeTask.run();
        }
    }

    @Override
    protected IndexSize createTask(
        long id,
        String type,
        String action,
        TaskId parentTaskId,
        PersistentTasksCustomMetadata.PersistentTask<IndexSizeTaskParams> taskInProgress,
        Map<String, String> headers
    ) {
        return new IndexSize(id, type, action, getDescription(taskInProgress), parentTaskId, headers, threadPool, () -> pollInterval);

    }

    void registerListeners(ClusterSettings clusterSettings) {
        if (this.enabled) {
            clusterService.addListener(taskStarter);
            clusterService.addListener(shutdownListener);
        }
        clusterSettings.addSettingsUpdateConsumer(ENABLED_SETTING, this::enable);
        clusterSettings.addSettingsUpdateConsumer(POLL_INTERVAL_SETTING, this::updatePollInterval);
    }

    private void enable(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            clusterService.addListener(taskStarter);
            clusterService.addListener(shutdownListener);
        } else {
            clusterService.removeListener(taskStarter);
            clusterService.removeListener(shutdownListener);
            abortTaskIfApplicable("disabling index size via '" + ENABLED_SETTING.getKey() + "'");
        }
    }

    private void updatePollInterval(TimeValue pollInterval) {
        if (Objects.equals(this.pollInterval, pollInterval) == false) {
            this.pollInterval = pollInterval;
            var task = currentTask.get();
            if (task != null) {
                task.requestReschedule();
            }
        }
    }

    // Do we need this? See PersistentTasksNodeService#doStartTask
    void startTask(ClusterChangedEvent event) {
        // Wait until cluster has recovered. Plus, start the task only when every node in the cluster supports IX
        if (event.state().clusterRecovered() && featureService.clusterHasFeature(event.state(), IndexSize.INDEX_SIZE_SUPPORTED)) {
            boolean indexSizeTaskExists = IndexSize.findTask(event.state()) != null;
            boolean isElectedMaster = event.localNodeMaster();
            if (isElectedMaster || indexSizeTaskExists) {
                clusterService.removeListener(taskStarter);
            }
            if (isElectedMaster && indexSizeTaskExists == false) {
                persistentTasksService.sendStartRequest(
                    TASK_NAME,
                    TASK_NAME,
                    new IndexSizeTaskParams(),
                    ActionListener.wrap(r -> logger.debug("Created the IX task"), e -> {
                        Throwable t = e instanceof RemoteTransportException ? e.getCause() : e;
                        if (t instanceof ResourceAlreadyExistsException == false) {
                            logger.error("Failed to create the IX task", e);
                            if (enabled) {
                                clusterService.addListener(taskStarter);
                            }
                        }
                    })
                );
            }
        }
    }

    // visible for testing
    void shuttingDown(ClusterChangedEvent event) {
        DiscoveryNode node = clusterService.localNode();
        if (isNodeShuttingDown(event, node.getId())) {
            abortTaskIfApplicable("node [{" + node.getName() + "}{" + node.getId() + "}] shutting down");
        }
    }

    // visible for testing
    void abortTaskIfApplicable(String reason) {
        IndexSize task = currentTask.get();
        if (task != null && task.isCancelled() == false) {
            logger.info("Aborting health node task due to {}.", reason);
            task.markAsLocallyAborted(reason);
            currentTask.set(null);
        }
    }

    private static boolean isNodeShuttingDown(ClusterChangedEvent event, String nodeId) {
        return event.previousState().metadata().nodeShutdowns().contains(nodeId) == false
            && event.state().metadata().nodeShutdowns().contains(nodeId);
    }
}

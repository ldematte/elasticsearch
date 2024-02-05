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
import org.elasticsearch.ResourceNotFoundException;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterState;
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
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<IndexSize> currentTask = new AtomicReference<>();
    private final PersistentTasksService persistentTasksService;
    private volatile boolean enabled;

    IndexSizeTaskExecutor(Client client, ClusterService clusterService, FeatureService featureService, ThreadPool threadPool) {
        super(IndexSize.TASK_NAME, ThreadPool.Names.MANAGEMENT);

        this.clusterService = clusterService;
        this.featureService = featureService;
        // Should we pass it via Binder/via PersistentTaskPlugin#getPersistentTasksExecutor?
        this.persistentTasksService = new PersistentTasksService(clusterService, threadPool, client);
        this.enabled = ENABLED_SETTING.get(clusterService.getSettings());
    }

    @Override
    protected void nodeOperation(AllocatedPersistentTask task, IndexSizeTaskParams params, PersistentTaskState state) {
        IndexSize indexSizeTask = (IndexSize) task;
        currentTask.set(indexSizeTask);
        DiscoveryNode node = clusterService.localNode();
        logger.info("Node [{{}}{{}}] is selected as the current IX node.", node.getName(), node.getId());
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
        return new IndexSize(id, type, action, getDescription(taskInProgress), parentTaskId, headers);
    }

    void registerListeners(ClusterSettings clusterSettings) {
        clusterService.addListener(this::startStopTask);
        clusterService.addListener(this::shuttingDown);
        clusterSettings.addSettingsUpdateConsumer(ENABLED_SETTING, this::setEnabled);
    }

    private void startStopTask(ClusterChangedEvent event) {
        // Wait until cluster has recovered. Plus, start the task only when every node in the cluster supports IX
        if (event.state().clusterRecovered() == false ||
            featureService.clusterHasFeature(event.state(), IndexSize.INDEX_SIZE_SUPPORTED) == false) {
            return;
        }

        DiscoveryNode masterNode = event.state().nodes().getMasterNode();
        if (masterNode == null) {
            // no master yet
            return;
        }

        doStartStopTask(event.state());
    }

    private void doStartStopTask(ClusterState clusterState) {
        boolean indexSizeTaskExists = IndexSize.findTask(clusterState) != null;

        boolean isElectedMaster = clusterService.state().nodes().isLocalNodeElectedMaster();
        // we should only start/stop task from single node, master is the best as it will go through it anyway
        if (isElectedMaster) {
            if (indexSizeTaskExists == false && enabled) {
                startTask();
            }
            if (indexSizeTaskExists && enabled == false) {
                stopTask();
            }
        }
    }

    private void startTask() {
        persistentTasksService.sendStartRequest(
            IndexSize.TASK_NAME,
            IndexSize.TASK_NAME,
            IndexSizeTaskParams.INSTANCE,
            ActionListener.wrap(r -> logger.debug("Created the IX task"), e -> {
                Throwable t = e instanceof RemoteTransportException ? e.getCause() : e;
                if (t instanceof ResourceAlreadyExistsException == false) {
                    logger.error("Failed to create the IX task", e);
                }
            })
        );
    }

    private void stopTask() {
        persistentTasksService.sendRemoveRequest(IndexSize.TASK_NAME, ActionListener.wrap(
            r -> {
                logger.debug("Stopped IX task");
                currentTask.set(null);
            },
            e -> {
                Throwable t = e instanceof RemoteTransportException ? e.getCause() : e;
                if (t instanceof ResourceNotFoundException == false) {
                    logger.error("failed to remove IX task", e);
                }
            })
        );
    }

    private void setEnabled(boolean enabled) {
        this.enabled = enabled;
        doStartStopTask(clusterService.state());
    }

    private void shuttingDown(ClusterChangedEvent event) {
        DiscoveryNode node = clusterService.localNode();
        if (isNodeShuttingDown(event, node.getId())) {
            stopTask();
        }
    }

    private static boolean isNodeShuttingDown(ClusterChangedEvent event, String nodeId) {
        return event.previousState().metadata().nodeShutdowns().contains(nodeId) == false
            && event.state().metadata().nodeShutdowns().contains(nodeId);
    }
}

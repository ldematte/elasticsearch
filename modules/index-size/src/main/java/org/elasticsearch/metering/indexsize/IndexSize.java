/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.metering.indexsize;

import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.features.NodeFeature;
import org.elasticsearch.persistent.AllocatedPersistentTask;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.tasks.TaskId;

import java.util.Map;

public class IndexSize extends AllocatedPersistentTask {

    public static final String TASK_NAME = "index-size";
    public static final NodeFeature INDEX_SIZE_SUPPORTED = new NodeFeature("index_size.supported");

    IndexSize(
        long id,
        String type,
        String action,
        String description,
        TaskId parentTask,
        Map<String, String> headers
    ) {
        super(id, type, action, description, parentTask, headers);
    }

    @Override
    protected void onCancelled() {
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
}

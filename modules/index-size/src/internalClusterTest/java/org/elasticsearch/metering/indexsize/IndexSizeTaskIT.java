/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.metering.indexsize;

import org.elasticsearch.action.admin.cluster.node.tasks.list.ListTasksRequest;
import org.elasticsearch.action.admin.cluster.node.tasks.list.ListTasksResponse;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.persistent.PersistentTasksCustomMetadata;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;
import org.junit.After;

import java.util.Collection;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class IndexSizeTaskIT  extends ESIntegTestCase {

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Stream.concat(super.nodePlugins().stream(), Stream.of(IndexSizePlugin.class)).collect(Collectors.toSet());
    }

    @After
    public void cleanUp() {
        updateClusterSettings(
            Settings.builder()
                .putNull(IndexSizeTaskExecutor.ENABLED_SETTING.getKey())
                .putNull(IndexSizeTaskExecutor.POLL_INTERVAL_SETTING.getKey())
        );
    }

    public void testTaskRemovedAfterCancellation() throws Exception {
        updateClusterSettings(Settings.builder().put(IndexSizeTaskExecutor.ENABLED_SETTING.getKey(), true));
        assertBusy(() -> {
            var task = IndexSize.findTask(clusterService().state());
            assertNotNull(task);
            assertTrue(task.isAssigned());
        });
        assertBusy(() -> {
            ListTasksResponse tasks = clusterAdmin().listTasks(new ListTasksRequest().setActions("index-size[c]")).actionGet();
            assertEquals(1, tasks.getTasks().size());
        });
        updateClusterSettings(Settings.builder().put(IndexSizeTaskExecutor.ENABLED_SETTING.getKey(), false));
        assertBusy(() -> {
            ListTasksResponse tasks2 = clusterAdmin().listTasks(new ListTasksRequest().setActions("index-size[c]")).actionGet();
            assertEquals(0, tasks2.getTasks().size());
        });
    }

    private PersistentTasksCustomMetadata.PersistentTask<PersistentTaskParams> getTask() {
        return PersistentTasksCustomMetadata.getTaskWithId(clusterService().state(), IndexSize.TASK_NAME);
    }
}

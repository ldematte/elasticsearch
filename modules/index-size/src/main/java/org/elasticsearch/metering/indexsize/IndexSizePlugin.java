/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.metering.indexsize;

import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.plugins.PersistentTaskPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;

import java.util.Collection;
import java.util.List;

public class IndexSizePlugin extends Plugin implements PersistentTaskPlugin {

    private IndexSizeTaskExecutor indexSizeTaskExecutor;

    @Override
    public Collection<?> createComponents(Plugin.PluginServices services) {
        indexSizeTaskExecutor = new IndexSizeTaskExecutor(
            services.client(),
            services.clusterService(),
            services.featureService(),
            services.threadPool()
        );
        indexSizeTaskExecutor.registerListeners(services.clusterService().getClusterSettings());

        // Alternative A
        var indexSizePeriodicConsumer = IndexSizePeriodicConsumer.create(
            services.clusterService().getSettings(),
            services.clusterService(),
            services.client()
        );
        return List.of(indexSizeTaskExecutor, indexSizePeriodicConsumer);
    }

    @Override
    public List<PersistentTasksExecutor<?>> getPersistentTasksExecutor(
        ClusterService clusterService,
        ThreadPool threadPool,
        Client client,
        SettingsModule settingsModule,
        IndexNameExpressionResolver expressionResolver
    ) {
        return List.of(indexSizeTaskExecutor);
    }
}

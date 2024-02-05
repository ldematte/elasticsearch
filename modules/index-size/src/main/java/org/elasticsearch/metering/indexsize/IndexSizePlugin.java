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
import org.elasticsearch.common.io.stream.NamedWriteableRegistry;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.SettingsModule;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.persistent.PersistentTasksExecutor;
import org.elasticsearch.plugins.PersistentTaskPlugin;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.xcontent.NamedXContentRegistry;
import org.elasticsearch.xcontent.ParseField;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

public class IndexSizePlugin extends Plugin implements PersistentTaskPlugin {

    private IndexSizeTaskExecutor indexSizeTaskExecutor;
    private IndexSizePeriodicConsumer indexSizePeriodicConsumer;

    @Override
    public List<Setting<?>> getSettings() {
        return List.of(
            IndexSizeTaskExecutor.ENABLED_SETTING,
            IndexSizeTaskExecutor.POLL_INTERVAL_SETTING
        );
    }

    @Override
    public Collection<?> createComponents(Plugin.PluginServices services) {
        indexSizeTaskExecutor = new IndexSizeTaskExecutor(
            services.client(),
            services.clusterService(),
            services.featureService(),
            services.threadPool()
        );
        indexSizeTaskExecutor.registerListeners(services.clusterService().getClusterSettings());

        indexSizePeriodicConsumer = IndexSizePeriodicConsumer.create(
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

    @Override
    public List<NamedXContentRegistry.Entry> getNamedXContent() {
        return List.of(
            new NamedXContentRegistry.Entry(
                PersistentTaskParams.class,
                new ParseField(IndexSize.TASK_NAME),
                IndexSizeTaskParams::fromXContent
            )
        );
    }

    @Override
    public List<NamedWriteableRegistry.Entry> getNamedWriteables() {
        return List.of(
            new NamedWriteableRegistry.Entry(PersistentTaskParams.class, IndexSize.TASK_NAME, reader -> IndexSizeTaskParams.INSTANCE)
        );
    }

    @Override
    public void close() throws IOException {
        super.close();
        if (indexSizePeriodicConsumer != null) {
            indexSizePeriodicConsumer.close();
            indexSizePeriodicConsumer = null;
        }
    }
}

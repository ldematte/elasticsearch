/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.support;

import org.elasticsearch.action.search.SearchTransportService;
import org.elasticsearch.client.internal.node.NodeClient;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.env.Environment;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.node.NodeService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;
import org.elasticsearch.watcher.ResourceWatcherService;

public interface ActionServices {
    ClusterService clusterService();

    ThreadPool threadPool();

    TransportService transportService();

    NodeService nodeService();

    SearchTransportService searchTransportService();

    NodeClient client();

    IndexNameExpressionResolver indexNameExpressionResolver();

    IndicesService indicesService();

    ScriptService scriptService();

    Environment environment();

    ResourceWatcherService resourceWatcherService();
}

/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.action.support;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.transport.TransportRequestHandler;

import java.util.concurrent.Executor;

public interface ActionRegistrar {

    <Request extends ActionRequest> void registerTransport(
        TransportAction<Request, ? extends ActionResponse> transportAction,
        Executor executor,
        Writeable.Reader<Request> requestReader,
        TransportRequestHandler<Request> handler);

    <Response extends ActionResponse> void registerClient(
        ActionType<Response> actionType,
        TransportAction<? extends ActionRequest, Response> transportAction,
        Executor executor);
}

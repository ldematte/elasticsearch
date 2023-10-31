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
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.transport.TransportRequestHandler;

import java.util.concurrent.Executor;

public abstract class RegistrableTransportAction<Request extends ActionRequest, Response extends ActionResponse> extends TransportAction<
    Request,
    Response> {
    protected RegistrableTransportAction(String actionName, ActionFilters actionFilters, TaskManager taskManager) {
        super(actionName, actionFilters, taskManager);
    }

    public interface TransportActionRegister {
        <Request extends ActionRequest> void register(
            String actionName,
            Executor executor,
            Writeable.Reader<Request> requestReader,
            TransportRequestHandler<Request> handler
        );
    }

    public interface ClientActionRegister {
        void register(ActionType<? extends ActionResponse> actionType, Executor executor);
    }

    // TODO: these can be 2 separate interfaces
    public abstract void registerWithTransport(TransportActionRegister register);

    public abstract void registerWithNodeClient(ClientActionRegister register);
}

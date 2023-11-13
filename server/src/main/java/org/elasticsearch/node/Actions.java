/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.node;

import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.ActionResponse;
import org.elasticsearch.action.ActionType;
import org.elasticsearch.action.admin.cluster.node.info.TransportNodesInfoAction;
import org.elasticsearch.action.admin.cluster.node.stats.TransportNodesStatsAction;
import org.elasticsearch.action.admin.cluster.remote.RemoteClusterNodesAction;
import org.elasticsearch.action.admin.cluster.remote.TransportRemoteInfoAction;
import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsAction;
import org.elasticsearch.action.admin.indices.mapping.get.GetFieldMappingsRequest;
import org.elasticsearch.action.admin.indices.mapping.get.TransportGetFieldMappingsAction;
import org.elasticsearch.action.fieldcaps.TransportFieldCapabilitiesAction;
import org.elasticsearch.action.ingest.SimulatePipelineTransportAction;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.ActionServices;
import org.elasticsearch.action.support.ChannelActionListener;
import org.elasticsearch.action.support.RegistrableTransportAction;
import org.elasticsearch.action.support.TransportAction;
import org.elasticsearch.common.io.stream.Writeable;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.plugins.ActionPlugin;
import org.elasticsearch.transport.TransportRequestHandler;
import org.elasticsearch.transport.TransportService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static java.util.Collections.unmodifiableMap;

class Actions {

    static Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> setupActions(
        List<ActionPlugin> actionPlugins,
        ActionFilters actionFilters,
        ActionServices services
    ) {
        // STEP 1: get rid of injection for NodeClient creation.
        // Instead of using the injection mechanism as in ActionModule, we create the instances directly and push them into the
        // Action map needed by NodeClient
        var actions = new HashMap<
            ActionType<? extends ActionResponse>,
            TransportAction<? extends ActionRequest, ? extends ActionResponse>>();

        actions.put(
            TransportNodesInfoAction.TYPE,
            new TransportNodesInfoAction(
                services.threadPool(),
                services.clusterService(),
                services.transportService(),
                services.nodeService(),
                actionFilters
            )
        );
        actions.put(
            TransportRemoteInfoAction.TYPE,
            new TransportRemoteInfoAction(services.transportService(), actionFilters, services.searchTransportService())
        );
        actions.put(
            RemoteClusterNodesAction.TYPE,
            new RemoteClusterNodesAction.TransportAction(services.transportService(), actionFilters, services.client())
        );
        actions.put(
            TransportNodesStatsAction.TYPE,
            new TransportNodesStatsAction(
                services.threadPool(),
                services.clusterService(),
                services.transportService(),
                services.nodeService(),
                actionFilters
            )
        );
        // ... and so on

        // STEP 2: put NodeClient and TransportService action registration together, in the same place

        // SOLUTION 1: registration code inside this class.
        // Code in HandledTransportAction will be moved here - HandledTransportAction will be removed and each derived class will be
        // simplified.
        // One advantage wrt today: multiple TransportService registrations are supported (today there is a bit of unbalance for "secondary"
        // actions.
        registerAction(
            actions,
            GetFieldMappingsAction.INSTANCE,
            new TransportGetFieldMappingsAction(
                services.transportService().getTaskManager(),
                services.clusterService(),
                actionFilters,
                services.indexNameExpressionResolver(),
                services.client()
            )
        ).withTransportService(services.transportService(), EsExecutors.DIRECT_EXECUTOR_SERVICE, true, GetFieldMappingsRequest::new)
            .withNodeClient();

        // actions.register(FieldCapabilitiesAction.INSTANCE, TransportFieldCapabilitiesAction.class);
        // SOLUTION 2: registration code (mostly) inside the TransportAction implementation class
        // SOLUTION 2A:
        var action = new TransportFieldCapabilitiesAction(
            services.transportService(),
            services.clusterService(),
            services.threadPool(),
            actionFilters,
            services.indicesService(),
            services.indexNameExpressionResolver()
        );
        action.registerWithNodeClient((actionType, executor) -> actions.put(actionType, action));
        action.registerWithTransport(new RegistrableTransportAction.TransportActionRegister() {
            @Override
            public <Request extends ActionRequest> void register(
                String actionName,
                Executor executor,
                Writeable.Reader<Request> requestReader,
                TransportRequestHandler<Request> handler
            ) {
                // TODO
            }
        });

        // SOLUTION 2B: same, but with some little helper classes to make it nicer
        // a) one registry for the whole method. Will call the registration code inside each TransportAction instance
        // and populate the map need for NodeClient (or create it - no need to create it outside and pass it?)
        Solution2BActionRegistry solution2BActionRegistry = new Solution2BActionRegistry(actions, services);

        // b) for each TransportAction: create and instance and register it (with TransportService, NodeClient, or both)
        solution2BActionRegistry.registerAction(
            Actions.ActionRegistrar::registerWithTransportServiceAndNodeClient,
            new TransportFieldCapabilitiesAction(
                services.transportService(),
                services.clusterService(),
                services.threadPool(),
                actionFilters,
                services.indicesService(),
                services.indexNameExpressionResolver()
            )
        );

        // SOLUTION 2C: same, but with some little helper classes to make it nicer
        // a) one registry for the whole method. Registration methods on the registry will be called by the registration code inside each
        // TransportAction createAndRegister method.
        // The registry will forward registration to TransportService and populate the map need for NodeClient (or create it - no need to
        // create it outside and pass it?)
        // If we want to, we can filter out/block registration to one or other with a filter class:
        // SimulatePipelineTransportAction.createAndRegister(... new TransportOnlyRegistrar(solution2CActionRegistry));
        Solution2CActionRegistry solution2CActionRegistry = new Solution2CActionRegistry(actions, services);

        SimulatePipelineTransportAction.createAndRegister(
            services.threadPool(),
            services.transportService(),
            actionFilters,
            services.nodeService().getIngestService(),
            solution2CActionRegistry);

        return unmodifiableMap(actions);
    }

    private static class Solution2CActionRegistry implements org.elasticsearch.action.support.ActionRegistrar {

        private final Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions;
        private final ActionServices services;

        Solution2CActionRegistry(
            Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions,
            ActionServices services) {

            this.actions = actions;
            this.services = services;
        }


        @Override
        public <Request extends ActionRequest> void registerTransport(
            TransportAction<Request, ? extends ActionResponse> transportAction,
            Executor executor,
            Writeable.Reader<Request> requestReader,
            TransportRequestHandler<Request> handler) {
            services.transportService().registerRequestHandler(
                transportAction.actionName,
                executor,
                false,
                true,
                requestReader,
                handler
            );
        }

        @Override
        public <Response extends ActionResponse> void registerClient(
            ActionType<Response> actionType,
            TransportAction<? extends ActionRequest, Response> transportAction,
            Executor executor) {
            actions.put(actionType, transportAction);
        }
    }

    private static class TransportActionRegistration<Request extends ActionRequest, Response extends ActionResponse> {

        private final ActionType<Response> actionType;
        private final TransportAction<Request, Response> transportAction;
        private final Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions;

        TransportActionRegistration(
            ActionType<Response> actionType,
            TransportAction<Request, Response> transportAction,
            Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions
        ) {
            this.actionType = actionType;
            this.transportAction = transportAction;
            this.actions = actions;
        }

        TransportActionRegistration<Request, Response> withTransportService(
            TransportService transportService,
            Executor executor,
            boolean canTripCircuitBreaker,
            Writeable.Reader<Request> requestReader
        ) {

            transportService.registerRequestHandler(
                transportAction.actionName,
                executor,
                false,
                canTripCircuitBreaker,
                requestReader,
                (request, channel, task) -> transportAction.execute(task, request, new ChannelActionListener<>(channel))
            );
            return this;
        }

        void withNodeClient() {
            actions.put(actionType, transportAction);
        }
    }

    private static class ActionRegistrar {
        private final Runnable registerTransportAction;
        private final Runnable registerClientAction;

        ActionRegistrar(Runnable registerTransportAction, Runnable registerClientAction) {
            this.registerTransportAction = registerTransportAction;
            this.registerClientAction = registerClientAction;
        }

        public void registerWithTransportService() {
            registerTransportAction.run();
        }

        public void registerWithNodeClient() {
            registerClientAction.run();
        }

        public void registerWithTransportServiceAndNodeClient() {
            registerTransportAction.run();
            registerClientAction.run();
        }
    }

    private static class Solution2BActionRegistry {
        private final Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions;
        private final ActionServices services;

        Solution2BActionRegistry(
            Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions,
            ActionServices services
        ) {
            this.actions = actions;
            this.services = services;
        }

        private <Request extends ActionRequest> void registerTransportAction(
            String actionName,
            Executor executor,
            Writeable.Reader<Request> requestReader,
            TransportRequestHandler<Request> handler
        ) {
            services.transportService().registerRequestHandler(actionName, executor, false, true, requestReader, handler);
        }

        void registerAction(
            Consumer<Actions.ActionRegistrar> registrationConsumer,
            RegistrableTransportAction<? extends ActionRequest, ? extends ActionResponse> transportAction
        ) {
            // TODO: here we can accept a TransporAction as second parameter and check (dynamically) for interface implementation
            // e.g. var registerWithNodeClient = (transportAction instanceof RegistrableNodeClientAction) ? /*register code*/ : () -> {}
            var registrar = new Actions.ActionRegistrar(
                () -> transportAction.registerWithTransport(this::registerTransportAction),
                () -> transportAction.registerWithNodeClient((actionType, executor) -> actions.put(actionType, transportAction))
            );

            registrationConsumer.accept(registrar);
        }
    }

    private static <
        Request extends ActionRequest,
        Response extends ActionResponse> TransportActionRegistration<Request, Response> registerAction(
            Map<ActionType<? extends ActionResponse>, TransportAction<? extends ActionRequest, ? extends ActionResponse>> actions,
            ActionType<Response> actionType,
            TransportAction<Request, Response> transportAction
        ) {
        return new TransportActionRegistration<>(actionType, transportAction, actions);
    }
}

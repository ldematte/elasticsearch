/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.services.custom;

import org.elasticsearch.ElasticsearchStatusException;
import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.common.ValidationException;
import org.elasticsearch.common.util.LazyInitializable;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.core.Strings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.inference.ChunkedInference;
import org.elasticsearch.inference.ChunkingSettings;
import org.elasticsearch.inference.InferenceServiceConfiguration;
import org.elasticsearch.inference.InferenceServiceResults;
import org.elasticsearch.inference.InputType;
import org.elasticsearch.inference.Model;
import org.elasticsearch.inference.ModelConfigurations;
import org.elasticsearch.inference.ModelSecrets;
import org.elasticsearch.inference.SettingsConfiguration;
import org.elasticsearch.inference.SimilarityMeasure;
import org.elasticsearch.inference.TaskType;
import org.elasticsearch.rest.RestStatus;
import org.elasticsearch.xpack.inference.chunking.ChunkingSettingsBuilder;
import org.elasticsearch.xpack.inference.chunking.EmbeddingRequestChunker;
import org.elasticsearch.xpack.inference.external.action.SenderExecutableAction;
import org.elasticsearch.xpack.inference.external.http.sender.EmbeddingsInput;
import org.elasticsearch.xpack.inference.external.http.sender.HttpRequestSender;
import org.elasticsearch.xpack.inference.external.http.sender.InferenceInputs;
import org.elasticsearch.xpack.inference.external.http.sender.UnifiedChatInput;
import org.elasticsearch.xpack.inference.services.ConfigurationParseContext;
import org.elasticsearch.xpack.inference.services.SenderService;
import org.elasticsearch.xpack.inference.services.ServiceComponents;
import org.elasticsearch.xpack.inference.services.ServiceUtils;
import org.elasticsearch.xpack.inference.services.custom.request.CustomRequest;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.elasticsearch.inference.TaskType.unsupportedTaskTypeErrorMsg;
import static org.elasticsearch.xpack.inference.external.action.ActionUtils.constructFailedToSendRequestMessage;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.createInvalidModelException;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMap;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMapOrDefaultEmpty;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.removeFromMapOrThrowIfNull;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.throwIfNotEmptyMap;
import static org.elasticsearch.xpack.inference.services.ServiceUtils.throwUnsupportedUnifiedCompletionOperation;

public class CustomService extends SenderService {

    public static final String NAME = "custom";
    private static final String SERVICE_NAME = "Custom";

    private static final EnumSet<TaskType> supportedTaskTypes = EnumSet.of(
        TaskType.TEXT_EMBEDDING,
        TaskType.SPARSE_EMBEDDING,
        TaskType.RERANK,
        TaskType.COMPLETION
    );

    public CustomService(HttpRequestSender.Factory factory, ServiceComponents serviceComponents) {
        super(factory, serviceComponents);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void parseRequestConfig(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        ActionListener<Model> parsedModelListener
    ) {
        try {
            Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
            Map<String, Object> taskSettingsMap = removeFromMapOrDefaultEmpty(config, ModelConfigurations.TASK_SETTINGS);

            var chunkingSettings = extractChunkingSettings(config, taskType);

            CustomModel model = createModel(
                inferenceEntityId,
                taskType,
                serviceSettingsMap,
                taskSettingsMap,
                serviceSettingsMap,
                chunkingSettings,
                ConfigurationParseContext.REQUEST
            );

            throwIfNotEmptyMap(config, NAME);
            throwIfNotEmptyMap(serviceSettingsMap, NAME);
            throwIfNotEmptyMap(taskSettingsMap, NAME);

            validateConfiguration(model);

            parsedModelListener.onResponse(model);
        } catch (Exception e) {
            parsedModelListener.onFailure(e);
        }
    }

    /**
     * This does some initial validation with mock inputs to determine if any templates are missing a field to fill them.
     */
    private static void validateConfiguration(CustomModel model) {
        String query = null;
        if (model.getTaskType() == TaskType.RERANK) {
            query = "test query";
        }

        try {
            new CustomRequest(query, List.of("test input"), model).createHttpRequest();
        } catch (IllegalStateException e) {
            var validationException = new ValidationException();
            validationException.addValidationError(Strings.format("Failed to validate model configuration: %s", e.getMessage()));
            throw validationException;
        }
    }

    private static ChunkingSettings extractChunkingSettings(Map<String, Object> config, TaskType taskType) {
        if (TaskType.TEXT_EMBEDDING.equals(taskType)) {
            return ChunkingSettingsBuilder.fromMap(removeFromMap(config, ModelConfigurations.CHUNKING_SETTINGS));
        }

        return null;
    }

    @Override
    public InferenceServiceConfiguration getConfiguration() {
        return Configuration.get();
    }

    @Override
    public EnumSet<TaskType> supportedTaskTypes() {
        return supportedTaskTypes;
    }

    @Override
    protected void doUnifiedCompletionInfer(
        Model model,
        UnifiedChatInput inputs,
        TimeValue timeout,
        ActionListener<InferenceServiceResults> listener
    ) {
        throwUnsupportedUnifiedCompletionOperation(NAME);
    }

    private static CustomModel createModelWithoutLoggingDeprecations(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        @Nullable Map<String, Object> secretSettings,
        @Nullable ChunkingSettings chunkingSettings
    ) {
        return createModel(
            inferenceEntityId,
            taskType,
            serviceSettings,
            taskSettings,
            secretSettings,
            chunkingSettings,
            ConfigurationParseContext.PERSISTENT
        );
    }

    private static CustomModel createModel(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> serviceSettings,
        Map<String, Object> taskSettings,
        @Nullable Map<String, Object> secretSettings,
        @Nullable ChunkingSettings chunkingSettings,
        ConfigurationParseContext context
    ) {
        if (supportedTaskTypes.contains(taskType) == false) {
            throw new ElasticsearchStatusException(unsupportedTaskTypeErrorMsg(taskType, NAME), RestStatus.BAD_REQUEST);
        }
        return new CustomModel(inferenceEntityId, taskType, NAME, serviceSettings, taskSettings, secretSettings, chunkingSettings, context);
    }

    @Override
    public CustomModel parsePersistedConfigWithSecrets(
        String inferenceEntityId,
        TaskType taskType,
        Map<String, Object> config,
        Map<String, Object> secrets
    ) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.TASK_SETTINGS);
        Map<String, Object> secretSettingsMap = removeFromMapOrThrowIfNull(secrets, ModelSecrets.SECRET_SETTINGS);

        var chunkingSettings = extractChunkingSettings(config, taskType);

        return createModelWithoutLoggingDeprecations(
            inferenceEntityId,
            taskType,
            serviceSettingsMap,
            taskSettingsMap,
            secretSettingsMap,
            chunkingSettings
        );
    }

    @Override
    public CustomModel parsePersistedConfig(String inferenceEntityId, TaskType taskType, Map<String, Object> config) {
        Map<String, Object> serviceSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.SERVICE_SETTINGS);
        Map<String, Object> taskSettingsMap = removeFromMapOrThrowIfNull(config, ModelConfigurations.TASK_SETTINGS);

        var chunkingSettings = extractChunkingSettings(config, taskType);

        return createModelWithoutLoggingDeprecations(
            inferenceEntityId,
            taskType,
            serviceSettingsMap,
            taskSettingsMap,
            null,
            chunkingSettings
        );
    }

    @Override
    public void doInfer(
        Model model,
        InferenceInputs inputs,
        Map<String, Object> taskSettings,
        TimeValue timeout,
        ActionListener<InferenceServiceResults> listener
    ) {
        if (model instanceof CustomModel == false) {
            listener.onFailure(createInvalidModelException(model));
            return;
        }

        CustomModel customModel = (CustomModel) model;

        var overriddenModel = CustomModel.of(customModel, taskSettings);

        var failedToSendRequestErrorMessage = constructFailedToSendRequestMessage(SERVICE_NAME);
        var manager = CustomRequestManager.of(overriddenModel, getServiceComponents().threadPool());
        var action = new SenderExecutableAction(getSender(), manager, failedToSendRequestErrorMessage);

        action.execute(inputs, timeout, listener);
    }

    @Override
    protected void validateInputType(InputType inputType, Model model, ValidationException validationException) {
        ServiceUtils.validateInputTypeIsUnspecifiedOrInternal(inputType, validationException);
    }

    @Override
    protected void doChunkedInfer(
        Model model,
        EmbeddingsInput inputs,
        Map<String, Object> taskSettings,
        InputType inputType,
        TimeValue timeout,
        ActionListener<List<ChunkedInference>> listener
    ) {
        if (model instanceof CustomModel == false) {
            listener.onFailure(createInvalidModelException(model));
            return;
        }

        var customModel = (CustomModel) model;
        var overriddenModel = CustomModel.of(customModel, taskSettings);

        var failedToSendRequestErrorMessage = constructFailedToSendRequestMessage(SERVICE_NAME);
        var manager = CustomRequestManager.of(overriddenModel, getServiceComponents().threadPool());

        List<EmbeddingRequestChunker.BatchRequestAndListener> batchedRequests = new EmbeddingRequestChunker<>(
            inputs.getInputs(),
            customModel.getServiceSettings().getBatchSize(),
            customModel.getConfigurations().getChunkingSettings()
        ).batchRequestsWithListeners(listener);

        for (var request : batchedRequests) {
            var action = new SenderExecutableAction(getSender(), manager, failedToSendRequestErrorMessage);
            action.execute(EmbeddingsInput.fromStrings(request.batch().inputs().get(), inputType), timeout, request.listener());
        }
    }

    @Override
    public Model updateModelWithEmbeddingDetails(Model model, int embeddingSize) {
        if (model instanceof CustomModel customModel && customModel.getTaskType() == TaskType.TEXT_EMBEDDING) {
            var newServiceSettings = getCustomServiceSettings(customModel, embeddingSize);

            return new CustomModel(customModel, newServiceSettings);
        } else {
            throw new ElasticsearchStatusException(
                Strings.format(
                    "Can't update embedding details for model of type: [%s], task type: [%s]",
                    model.getClass().getSimpleName(),
                    model.getTaskType()
                ),
                RestStatus.BAD_REQUEST
            );
        }
    }

    private static CustomServiceSettings getCustomServiceSettings(CustomModel customModel, int embeddingSize) {
        var serviceSettings = customModel.getServiceSettings();
        var similarityFromModel = serviceSettings.similarity();
        var similarityToUse = similarityFromModel == null ? SimilarityMeasure.DOT_PRODUCT : similarityFromModel;

        return new CustomServiceSettings(
            new CustomServiceSettings.TextEmbeddingSettings(
                similarityToUse,
                embeddingSize,
                serviceSettings.getMaxInputTokens(),
                serviceSettings.elementType()
            ),
            serviceSettings.getUrl(),
            serviceSettings.getHeaders(),
            serviceSettings.getQueryParameters(),
            serviceSettings.getRequestContentString(),
            serviceSettings.getResponseJsonParser(),
            serviceSettings.rateLimitSettings()
        );
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.INFERENCE_CUSTOM_SERVICE_ADDED;
    }

    public static class Configuration {
        public static InferenceServiceConfiguration get() {
            return configuration.getOrCompute();
        }

        private static final LazyInitializable<InferenceServiceConfiguration, RuntimeException> configuration = new LazyInitializable<>(
            () -> {
                var configurationMap = new HashMap<String, SettingsConfiguration>();
                // TODO revisit this
                return new InferenceServiceConfiguration.Builder().setService(NAME)
                    .setName(SERVICE_NAME)
                    .setTaskTypes(supportedTaskTypes)
                    .setConfigurations(configurationMap)
                    .build();
            }
        );
    }
}

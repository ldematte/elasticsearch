/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.metering.indexsize;

import org.elasticsearch.TransportVersion;
import org.elasticsearch.TransportVersions;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.persistent.PersistentTaskParams;
import org.elasticsearch.xcontent.ObjectParser;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentParser;

import java.io.IOException;

/**
 * Encapsulates the parameters needed to start the IX task. Currently, no parameters are required.
 */
public class IndexSizeTaskParams implements PersistentTaskParams {

    public static final IndexSizeTaskParams INSTANCE = new IndexSizeTaskParams();

    public static final ObjectParser<IndexSizeTaskParams, Void> PARSER = new ObjectParser<>(IndexSize.TASK_NAME, true, () -> INSTANCE);

    IndexSizeTaskParams() {
    }

    IndexSizeTaskParams(StreamInput ignored) {
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject();
        builder.endObject();
        return builder;
    }

    @Override
    public String getWriteableName() {
        return IndexSize.TASK_NAME;
    }

    @Override
    public TransportVersion getMinimalSupportedVersion() {
        return TransportVersions.INDEX_SIZE_SERVICE_ADDED;
    }

    @Override
    public void writeTo(StreamOutput out) {
    }

    public static IndexSizeTaskParams fromXContent(XContentParser parser) {
        return PARSER.apply(parser, null);
    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof IndexSizeTaskParams;
    }
}

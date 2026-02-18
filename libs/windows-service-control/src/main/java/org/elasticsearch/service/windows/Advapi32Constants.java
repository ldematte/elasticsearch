/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.service.windows;

/**
 * Public constants for Windows service configuration.
 */
public final class Advapi32Constants {

    /** Service is started automatically by the SCM during system startup. */
    public static final int SERVICE_AUTO_START = Advapi32.SERVICE_AUTO_START;

    /** Service must be started manually (e.g. via {@code sc start} or the Services console). */
    public static final int SERVICE_DEMAND_START = Advapi32.SERVICE_DEMAND_START;

    private Advapi32Constants() {}
}

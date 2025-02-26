/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.entitlement.qa.test;

import org.elasticsearch.core.CheckedConsumer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;

import static org.elasticsearch.entitlement.qa.test.EntitlementTest.ExpectedAccess.PLUGINS;

class URLConnectionNetworkActions {

    private static void withHttpConnection(CheckedConsumer<URLConnection, Exception> connectionConsumer) {
        try {
            var conn = URI.create("http://127.0.0.1:12345/").toURL().openConnection();
            // Be sure we got the connection implementation we want
            assert HttpURLConnection.class.isAssignableFrom(conn.getClass());

            try {
                connectionConsumer.accept(conn);
            } catch (Exception e) {
                // It's OK, it means we passed entitlement checks and we tried to connect
                assert e instanceof java.net.ConnectException;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @EntitlementTest(expectedAccess = PLUGINS)
    static void urlConnectionGetContentLength() {
        withHttpConnection(URLConnection::getContentLength);
    }

    @EntitlementTest(expectedAccess = PLUGINS)
    static void urlConnectionGetContent() {
        withHttpConnection(URLConnection::getContent);
    }

    @EntitlementTest(expectedAccess = PLUGINS)
    static void urlConnectionGetInputStream() {
        withHttpConnection(URLConnection::getInputStream);
    }
}

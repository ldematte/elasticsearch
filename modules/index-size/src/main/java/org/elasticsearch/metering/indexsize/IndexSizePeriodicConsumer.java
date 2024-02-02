/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.metering.indexsize;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.util.SetOnce;
import org.elasticsearch.client.internal.Client;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.component.Lifecycle;
import org.elasticsearch.common.scheduler.SchedulerEngine;
import org.elasticsearch.common.scheduler.TimeValueSchedule;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.RunOnce;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.gateway.GatewayService;

import java.io.Closeable;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.elasticsearch.metering.indexsize.IndexSizeTaskExecutor.ENABLED_SETTING;
import static org.elasticsearch.metering.indexsize.IndexSizeTaskExecutor.POLL_INTERVAL_SETTING;

public class IndexSizePeriodicConsumer implements ClusterStateListener, Closeable, SchedulerEngine.Listener {

    /**
     * Name constant for the job IndexSizeService schedules
     */
    protected static final String INDEX_SIZE_PERIODIC_CONSUMER_JOB_NAME = "index_size_periodic_consumer";

    private final Settings settings;

    private final ClusterService clusterService;
    private final Client client;

    private final Clock clock;

    // default visibility for testing purposes
    volatile boolean isIXNode = false;

    private final AtomicBoolean currentlyRunning = new AtomicBoolean(false);

    private final SetOnce<SchedulerEngine> scheduler = new SetOnce<>();
    private volatile TimeValue pollInterval;
    private volatile boolean enabled;

    private static final Logger logger = LogManager.getLogger(IndexSizePeriodicConsumer.class);

    /**
     * Creates a new IndexSizePeriodicConsumer.
     * This creates a scheduled job using the SchedulerEngine framework and runs it on the current IX node.
     *
     * @param settings the cluster settings, used to get the interval setting.
     * @param clusterService the cluster service, used to know when the IX node changes.
     * @param client the client used to call the IndexSizeService.
     */
    public static IndexSizePeriodicConsumer create(Settings settings, ClusterService clusterService, Client client) {
        var periodicConsumer = new IndexSizePeriodicConsumer(settings, clusterService, client);
        periodicConsumer.registerListeners();
        return periodicConsumer;
    }

    private IndexSizePeriodicConsumer(Settings settings, ClusterService clusterService, Client client) {
        this.settings = settings;
        this.clusterService = clusterService;
        this.client = client;
        this.clock = Clock.systemUTC();
        this.pollInterval = POLL_INTERVAL_SETTING.get(settings);
        this.enabled = ENABLED_SETTING.get(settings);
    }

    private void registerListeners() {
        if (enabled) {
            clusterService.addListener(this);
        }
        clusterService.getClusterSettings().addSettingsUpdateConsumer(ENABLED_SETTING, this::enable);
        clusterService.getClusterSettings().addSettingsUpdateConsumer(POLL_INTERVAL_SETTING, this::updatePollInterval);
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        // wait for the cluster state to be recovered
        if (event.state().blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) {
            return;
        }

        DiscoveryNode indexSizeNode = IndexSize.findIndexSizeNode(event.state());
        if (indexSizeNode == null) {
            this.isIXNode = false;
            this.maybeCancelJob();
            return;
        }
        final boolean isCurrentlyIXNode = indexSizeNode.getId().equals(this.clusterService.localNode().getId());
        if (this.isIXNode != isCurrentlyIXNode) {
            this.isIXNode = isCurrentlyIXNode;
            if (this.isIXNode) {
                // we weren't the IX node, and now we are
                maybeScheduleJob();
            } else {
                // we were the IX node, and now we aren't
                maybeCancelJob();
            }
        }
    }

    @Override
    public void close() {
        SchedulerEngine engine = scheduler.get();
        if (engine != null) {
            engine.stop();
        }
    }

    @Override
    public void triggered(SchedulerEngine.Event event) {
        if (event.getJobName().equals(INDEX_SIZE_PERIODIC_CONSUMER_JOB_NAME) && this.enabled) {
            this.doSomething();
        }
    }

    // default visibility for testing purposes
    void doSomething() {
        if (this.currentlyRunning.compareAndExchange(false, true) == false) {
            RunOnce release = new RunOnce(() -> currentlyRunning.set(false));
            try {

                // TODO: call IndexService

            } catch (Exception e) {
                logger.warn(() -> "The index size periodic consumer encountered an error.", e);
                // In case of an exception before the listener was wired, we can release the flag here, and we feel safe
                // that it will not release it again because this can only be run once.
                release.run();
            }
        }
    }

    /**
     * Create the SchedulerEngine.Job if this node is the IX node
     */
    private void maybeScheduleJob() {
        if (this.isIXNode == false) {
            return;
        }

        if (this.enabled == false) {
            return;
        }

        // don't schedule the job if the node is shutting down
        if (isClusterServiceStoppedOrClosed()) {
            logger.trace(
                "Skipping scheduling a index size periodic consumer job due to the cluster lifecycle state being: [{}] ",
                clusterService.lifecycleState()
            );
            return;
        }

        if (scheduler.get() == null) {
            scheduler.set(new SchedulerEngine(settings, clock));
            scheduler.get().register(this);
        }

        assert scheduler.get() != null : "scheduler should be available";
        final SchedulerEngine.Job scheduledJob = new SchedulerEngine.Job(
            INDEX_SIZE_PERIODIC_CONSUMER_JOB_NAME,
            new TimeValueSchedule(pollInterval)
        );
        scheduler.get().add(scheduledJob);
    }

    private void maybeCancelJob() {
        if (scheduler.get() != null) {
            scheduler.get().remove(INDEX_SIZE_PERIODIC_CONSUMER_JOB_NAME);
        }
    }

    private void enable(boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            clusterService.addListener(this);
            maybeScheduleJob();
        } else {
            clusterService.removeListener(this);
            maybeCancelJob();
        }
    }

    private void updatePollInterval(TimeValue newInterval) {
        this.pollInterval = newInterval;
        maybeScheduleJob();
    }

    private boolean isClusterServiceStoppedOrClosed() {
        final Lifecycle.State state = clusterService.lifecycleState();
        return state == Lifecycle.State.STOPPED || state == Lifecycle.State.CLOSED;
    }
}

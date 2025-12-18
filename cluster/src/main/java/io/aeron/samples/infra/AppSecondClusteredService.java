/*
 * Copyright 2023 Adaptive Financial Consulting
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.aeron.samples.infra;

import io.aeron.ExclusivePublication;
import io.aeron.Image;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.service.ClientSession;
import io.aeron.cluster.service.Cluster;
import io.aeron.cluster.service.ClusteredService;
import io.aeron.logbuffer.Header;
import io.aeron.samples.domain.auctions.Auctions;
import io.aeron.samples.domain.participants.Participants;
import org.agrona.DirectBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The clustered service for the auction application.
 */
public class AppSecondClusteredService implements ClusteredService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(AppSecondClusteredService.class);
    private static final String SERVICE_NAME = "AppSecondClusteredService (Secondary)";
    private final ClientSessions clientSessions = new ClientSessions();
    private final SessionMessageContextImpl context = new SessionMessageContextImpl(clientSessions);
    private final ClusterClientResponder clusterClientResponder = new ClusterClientResponderImpl(context);
    private final TimerManager timerManager = new TimerManager(context);
    private final Participants participants = new Participants(clusterClientResponder);
    private final Auctions auctions = new Auctions(context, participants, clusterClientResponder,
        timerManager);
    private final SnapshotManager snapshotManager = new SnapshotManager(auctions, participants, context);
    private final SbeDemuxer sbeDemuxer = new SbeDemuxer(participants, auctions, clusterClientResponder);
    private Cluster.Role currentRole = Cluster.Role.FOLLOWER;

    @Override
    public void onStart(final Cluster cluster, final Image snapshotImage)
    {
        LOGGER.info("[{}] Starting service - memberId={}, role={}",
            SERVICE_NAME, cluster.memberId(), cluster.role());

        snapshotManager.setIdleStrategy(cluster.idleStrategy());
        context.setIdleStrategy(cluster.idleStrategy());
        timerManager.setCluster(cluster);
        sbeDemuxer.setCluster(cluster);

        if (snapshotImage != null)
        {
            LOGGER.info("[{}] Loading snapshot from position={}", SERVICE_NAME, snapshotImage.position());
            snapshotManager.loadSnapshot(snapshotImage);
        }
        else
        {
            LOGGER.info("[{}] No snapshot to load, starting fresh", SERVICE_NAME);
        }

        currentRole = cluster.role();
        LOGGER.info("[{}] Service started successfully as {}", SERVICE_NAME, currentRole);
    }

    @Override
    public void onSessionOpen(final ClientSession session, final long timestamp)
    {
        LOGGER.info("[{}] Client session opened - sessionId={}, role={}",
            SERVICE_NAME, session.id(), currentRole);
        context.setClusterTime(timestamp);
        clientSessions.addSession(session);
    }

    @Override
    public void onSessionClose(final ClientSession session, final long timestamp, final CloseReason closeReason)
    {
        LOGGER.info("[{}] Client session closed - sessionId={}, reason={}, role={}",
            SERVICE_NAME, session.id(), closeReason, currentRole);
        context.setClusterTime(timestamp);
        clientSessions.removeSession(session);
    }

    @Override
    public void onSessionMessage(
        final ClientSession session,
        final long timestamp,
        final DirectBuffer buffer,
        final int offset,
        final int length,
        final Header header)
    {
        context.setSessionContext(session, timestamp);
        sbeDemuxer.dispatch(buffer, offset, length);
    }

    @Override
    public void onTimerEvent(final long correlationId, final long timestamp)
    {
        context.setClusterTime(timestamp);
        timerManager.onTimerEvent(correlationId, timestamp);
    }

    @Override
    public void onTakeSnapshot(final ExclusivePublication snapshotPublication)
    {
        LOGGER.info("[{}] Taking snapshot - role={}", SERVICE_NAME, currentRole);
        snapshotManager.takeSnapshot(snapshotPublication);
        LOGGER.info("[{}] Snapshot completed", SERVICE_NAME);
    }

    @Override
    public void onRoleChange(final Cluster.Role newRole)
    {
        final Cluster.Role previousRole = currentRole;
        currentRole = newRole;

        if (newRole == Cluster.Role.LEADER)
        {
            LOGGER.warn("[{}] *** ROLE CHANGE: {} -> LEADER *** Now processing client requests",
                SERVICE_NAME, previousRole);
        }
        else if (previousRole == Cluster.Role.LEADER && newRole == Cluster.Role.FOLLOWER)
        {
            LOGGER.warn("[{}] *** ROLE CHANGE: LEADER -> FOLLOWER *** Stepped down from leadership",
                SERVICE_NAME);
        }
        else if (newRole == Cluster.Role.CANDIDATE)
        {
            LOGGER.warn("[{}] *** ROLE CHANGE: {} -> CANDIDATE *** Election in progress",
                SERVICE_NAME, previousRole);
        }
        else
        {
            LOGGER.info("[{}] Role change: {} -> {}", SERVICE_NAME, previousRole, newRole);
        }
    }

    @Override
    public void onTerminate(final Cluster cluster)
    {
        LOGGER.warn("[{}] *** TERMINATING *** Final role was: {}, memberId={}",
            SERVICE_NAME, currentRole, cluster.memberId());
    }
}

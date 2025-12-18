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

import io.aeron.cluster.service.Cluster;
import io.aeron.samples.cluster.protocol.AddAuctionBidCommandDecoder;
import io.aeron.samples.cluster.protocol.AddParticipantCommandDecoder;
import io.aeron.samples.cluster.protocol.AuctionCreatedNotificationDecoder;
import io.aeron.samples.cluster.protocol.AuctionCreatedNotificationEncoder;
import io.aeron.samples.cluster.protocol.CreateAuctionCommandDecoder;
import io.aeron.samples.cluster.protocol.ListAuctionsCommandDecoder;
import io.aeron.samples.cluster.protocol.ListParticipantsCommandDecoder;
import io.aeron.samples.cluster.protocol.MessageHeaderDecoder;
import io.aeron.samples.cluster.protocol.MessageHeaderEncoder;
import io.aeron.samples.domain.auctions.Auction;
import io.aeron.samples.domain.auctions.Auctions;
import io.aeron.samples.domain.participants.Participant;
import io.aeron.samples.domain.participants.Participants;
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Demultiplexes messages from the ingress stream to the appropriate domain handler.
 */
public class SbeDemuxer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SbeDemuxer.class);
    private final Participants participants;
    private final Auctions auctions;
    private final ClusterClientResponder responder;
    private Cluster cluster;

    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();
    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();

    private final AddParticipantCommandDecoder addParticipantDecoder = new AddParticipantCommandDecoder();
    private final AddAuctionBidCommandDecoder addAuctionBidDecoder = new AddAuctionBidCommandDecoder();
    private final CreateAuctionCommandDecoder createAuctionDecoder = new CreateAuctionCommandDecoder();
    private final ListAuctionsCommandDecoder listAuctionsDecoder = new ListAuctionsCommandDecoder();
    private final ListParticipantsCommandDecoder listParticipantsDecoder = new ListParticipantsCommandDecoder();
    private final AuctionCreatedNotificationDecoder auctionCreatedNotificationDecoder =
        new AuctionCreatedNotificationDecoder();
    private final AuctionCreatedNotificationEncoder auctionCreatedNotificationEncoder =
        new AuctionCreatedNotificationEncoder();

    private final ExpandableDirectByteBuffer encodeBuffer = new ExpandableDirectByteBuffer(1024);


    /**
     * Dispatches ingress messages to domain logic.
     *
     * @param participants          the participants domain model to which commands are dispatched
     * @param auctions              the auction domain model to which commands are dispatched
     * @param responder             the responder to which responses are sent
     */
    public SbeDemuxer(
        final Participants participants,
        final Auctions auctions,
        final ClusterClientResponder responder)
    {
        this.participants = participants;
        this.auctions = auctions;
        this.responder = responder;
    }

    /**
     * Sets the cluster object used for offering messages
     * @param cluster the cluster object
     */
    public void setCluster(final Cluster cluster)
    {
        this.cluster = cluster;
    }

    /**
     * Dispatch a message to the appropriate domain handler.
     *
     * @param buffer the buffer containing the inbound message, including a header
     * @param offset the offset to apply
     * @param length the length of the message
     */
    public void dispatch(final DirectBuffer buffer, final int offset, final int length)
    {
        if (length < MessageHeaderDecoder.ENCODED_LENGTH)
        {
            LOGGER.error("Message too short, ignored.");
            return;
        }
        headerDecoder.wrap(buffer, offset);

        switch (headerDecoder.templateId())
        {
            case AddParticipantCommandDecoder.TEMPLATE_ID ->
            {
                addParticipantDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                participants.addParticipant(addParticipantDecoder.participantId(),
                    addParticipantDecoder.correlationId(), addParticipantDecoder.name());
            }
            case CreateAuctionCommandDecoder.TEMPLATE_ID ->
            {
                createAuctionDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);

                LOGGER.info("Received CreateAuctionCommand - encoding and submitting " +
                    "AuctionCreatedNotification via cluster.offer");

                // Encode dummy notification
                auctionCreatedNotificationEncoder.wrapAndApplyHeader(encodeBuffer, 0, headerEncoder)
                    .auctionId(-1L)  // dummy auction ID
                    .createdByParticipantId(createAuctionDecoder.createdByParticipantId())
                    .timestamp(System.currentTimeMillis())
                    .message("CreateAuctionCommand received");

                final int encodedLength = MessageHeaderEncoder.ENCODED_LENGTH +
                    auctionCreatedNotificationEncoder.encodedLength();

                // Submit to cluster via cluster.offer()
                if (cluster != null)
                {
                    cluster.idleStrategy().reset();
                    while (cluster.offer(encodeBuffer, 0, encodedLength) < 0)
                    {
                        cluster.idleStrategy().idle();
                    }
                    LOGGER.info("Successfully submitted AuctionCreatedNotification via cluster.offer");
                }
                else
                {
                    LOGGER.warn("Cluster not set, cannot offer AuctionCreatedNotification");
                }

                auctions.addAuction(createAuctionDecoder.createdByParticipantId(),
                    createAuctionDecoder.startTime(),
                    createAuctionDecoder.endTime(),
                    createAuctionDecoder.correlationId(),
                    createAuctionDecoder.name(),
                    createAuctionDecoder.description());
            }
            case AuctionCreatedNotificationDecoder.TEMPLATE_ID ->
            {
                auctionCreatedNotificationDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                LOGGER.info("Handling AuctionCreatedNotification: auctionId={}, " +
                    "participantId={}, timestamp={}, message={}",
                    auctionCreatedNotificationDecoder.auctionId(),
                    auctionCreatedNotificationDecoder.createdByParticipantId(),
                    auctionCreatedNotificationDecoder.timestamp(),
                    auctionCreatedNotificationDecoder.message());
            }
            case AddAuctionBidCommandDecoder.TEMPLATE_ID ->
            {
                addAuctionBidDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                auctions.addBid(addAuctionBidDecoder.auctionId(),
                    addAuctionBidDecoder.addedByParticipantId(),
                    addAuctionBidDecoder.price(),
                    addAuctionBidDecoder.correlationId());
            }
            case ListAuctionsCommandDecoder.TEMPLATE_ID ->
            {
                listAuctionsDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final List<Auction> auctionList = auctions.getAuctionList();
                responder.returnAuctionList(auctionList, listAuctionsDecoder.correlationId());
            }
            case ListParticipantsCommandDecoder.TEMPLATE_ID ->
            {
                listParticipantsDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                final List<Participant> participantList = participants.getParticipantList();
                responder.returnParticipantList(participantList, listParticipantsDecoder.correlationId());
            }
            default -> LOGGER.error("Unknown message template {}, ignored.", headerDecoder.templateId());
        }
    }
}

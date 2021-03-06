/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.stream.pubsub;

import com.google.api.core.ApiFutures;
import com.google.api.gax.core.CredentialsProvider;
import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.GrpcTransportChannel;
import com.google.api.gax.rpc.FixedTransportChannelProvider;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.pubsub.v1.stub.SubscriberStubSettings;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.PullRequest;
import com.google.pubsub.v1.PullResponse;
import com.google.pubsub.v1.ReceivedMessage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Logger;

import org.jetbrains.annotations.NotNull;
import org.mockito.Mockito;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Mock Pub/Sub Server
 */
class MockPubSubServer {

    /** Test topic. */
    public static final String TOPIC_NAME = "pagevisits";

    private static final Logger LOGGER = Logger.getLogger(MockPubSubServer.class.getName());
    public static final String PROJECT = "test-project";
    private static final String LOCALHOST = "localhost";
    private static final int PORT = 8080;
    public static final int MESSAGES_PER_REQUEST = 10;

    private final Map<String, Publisher> publishers = new HashMap<>();
    private final List<PubsubMessage> topicMessages = new ArrayList<>();
    private final Queue<PubsubMessage> blockingQueue = new LinkedBlockingDeque<>();

    public SubscriberStubSettings createSubscriberStub() throws IOException {
        CredentialsProvider credentialsProvider = NoCredentialsProvider.create();

        ManagedChannel managedChannel = managedChannel();

        FixedTransportChannelProvider transportChannel = FixedTransportChannelProvider.create(GrpcTransportChannel.create(managedChannel));
        SubscriberStubSettings subscriberStubSettings = SubscriberStubSettings.newBuilder()
                                                                              .setTransportChannelProvider(transportChannel)
                                                                              .setCredentialsProvider(credentialsProvider)
                                                                              .build();
        return subscriberStubSettings;
    }

    @NotNull
    private ManagedChannel managedChannel() {
        ManagedChannel managedChannel = Mockito.mock(ManagedChannel.class);
        when(managedChannel.newCall(any(MethodDescriptor.class),any(CallOptions.class))).thenAnswer((la) -> clientCall());
        return managedChannel;
    }

    private ClientCall<PullRequest, PullResponse> clientCall() {
        ClientCall<PullRequest, PullResponse> clientCall = Mockito.mock(ClientCall.class);

        doAnswer(
            iom ->{
                Object[] arguments = iom.getArguments();
                ClientCall.Listener<PullResponse> listener = (ClientCall.Listener<PullResponse>) arguments[0];
                Metadata metadata = (Metadata) arguments[1];
                pullMessages(listener, metadata);
                return null;
            }
        ).when(clientCall).start(any(ClientCall.Listener.class),any(Metadata.class));
        return clientCall;
    }

    private void pullMessages(ClientCall.Listener<PullResponse> listener, Metadata metadata) {
        PullResponse.Builder pullResponse = PullResponse.newBuilder();

        for(int i = 0; i < MESSAGES_PER_REQUEST; i++) {
            pullResponse.addReceivedMessages(ReceivedMessage.newBuilder().mergeMessage(blockingQueue.remove()).build());
        }

        listener.onMessage(pullResponse.build());
        listener.onClose(Status.OK, metadata);
    }

    public Publisher getPublisher(String topicName) throws IOException {
        publishers.putIfAbsent(topicName, createPublisher(topicName));
        return publishers.get(topicName);
    }

    private Publisher createPublisher(String topic) {
        Publisher publisher = mock(Publisher.class);

        when(publisher.publish(any(PubsubMessage.class))).thenAnswer(
                (iom) -> {
                    PubsubMessage pubsubMessage = (PubsubMessage) iom.getArguments()[0];
                    blockingQueue.add(pubsubMessage);
                    return ApiFutures.immediateFuture(UUID.randomUUID().toString());
                }
        );
        return publisher;
    }

    /**
     * Obtains Pub/Sub address.
     *
     * @return Pub/Sub address.
     */
    private String getPubSubAddress() {
        return LOCALHOST+ ":" + PORT;
    }

}

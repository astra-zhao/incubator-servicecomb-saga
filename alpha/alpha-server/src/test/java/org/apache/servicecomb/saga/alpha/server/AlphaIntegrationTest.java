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

package org.apache.servicecomb.saga.alpha.server;

import static com.seanyinx.github.unit.scaffolding.Randomness.uniquify;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.servicecomb.saga.alpha.core.EventType.TxAbortedEvent;
import static org.apache.servicecomb.saga.alpha.core.EventType.TxEndedEvent;
import static org.apache.servicecomb.saga.alpha.core.EventType.TxStartedEvent;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.servicecomb.saga.alpha.core.EventType;
import org.apache.servicecomb.saga.pack.contract.grpc.GrpcCompensateCommand;
import org.apache.servicecomb.saga.pack.contract.grpc.GrpcTxEvent;
import org.apache.servicecomb.saga.pack.contract.grpc.TxEventServiceGrpc;
import org.apache.servicecomb.saga.pack.contract.grpc.TxEventServiceGrpc.TxEventServiceStub;
import org.hamcrest.core.Is;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import com.google.protobuf.ByteString;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;

@RunWith(SpringRunner.class)
@SpringBootTest(classes = {AlphaApplication.class, AlphaConfig.class}, properties = "alpha.server.port=8090")
public class AlphaIntegrationTest {
  private static final int port = 8090;

  private static ManagedChannel clientChannel = ManagedChannelBuilder
      .forAddress("localhost", port).usePlaintext(true).build();

  private TxEventServiceStub stub = TxEventServiceGrpc.newStub(clientChannel);

  private static final String payload = "hello world";

  private final String globalTxId = UUID.randomUUID().toString();
  private final String localTxId = UUID.randomUUID().toString();
  private final String parentTxId = UUID.randomUUID().toString();
  private final String compensationMethod = getClass().getCanonicalName();
  private final String serviceName = uniquify("serviceName");
  private final String instanceId = uniquify("instanceId");

  @Autowired
  private TxEventEnvelopeRepository eventRepo;

  private static final List<GrpcCompensateCommand> receivedCommands = new CopyOnWriteArrayList<>();
  private final StreamObserver<GrpcCompensateCommand> compensateResponseObserver = new CompensateStreamObserver();

  @AfterClass
  public static void tearDown() throws Exception {
    clientChannel.shutdown();
  }

  @Before
  public void before() {
    eventRepo.deleteAll();
    receivedCommands.clear();
  }

  @Test
  public void persistsEvent() {
    StreamObserver<GrpcTxEvent> requestObserver = stub.callbackCommand(compensateResponseObserver);
    requestObserver.onNext(someGrpcEvent(TxStartedEvent));
    // use the asynchronous stub need to wait for some time
    await().atMost(1, SECONDS).until(() -> eventRepo.findByEventGlobalTxId(globalTxId) != null);

    assertThat(receivedCommands.isEmpty(), is(true));

    TxEventEnvelope envelope = eventRepo.findByEventGlobalTxId(globalTxId);

    assertThat(envelope.serviceName(), is(serviceName));
    assertThat(envelope.instanceId(), is(instanceId));
    assertThat(envelope.globalTxId(), is(globalTxId));
    assertThat(envelope.localTxId(), is(localTxId));
    assertThat(envelope.parentTxId(), is(parentTxId));
    assertThat(envelope.type(), Is.is(TxStartedEvent.name()));
    assertThat(envelope.compensationMethod(), is(compensationMethod));
    assertThat(envelope.payloads(), is(payload.getBytes()));
  }

  @Test
  public void doNotCompensateDuplicateTxOnFailure() {
    // duplicate events with same content but different timestamp
    StreamObserver<GrpcTxEvent> requestObserver = stub.callbackCommand(compensateResponseObserver);
    requestObserver.onNext(eventOf(TxStartedEvent, localTxId, parentTxId, "service a".getBytes(), "method a"));
    requestObserver.onNext(eventOf(TxStartedEvent, localTxId, parentTxId, "service a".getBytes(), "method a"));
    requestObserver.onNext(eventOf(TxEndedEvent, new byte[0], "method a"));

    String localTxId1 = UUID.randomUUID().toString();
    String parentTxId1 = UUID.randomUUID().toString();
    requestObserver.onNext(eventOf(TxStartedEvent, localTxId1, parentTxId1, "service b".getBytes(), "method b"));
    requestObserver.onNext(eventOf(TxEndedEvent, new byte[0], "method b"));

    requestObserver.onNext(someGrpcEvent(TxAbortedEvent));
    await().atMost(1, SECONDS).until(() -> receivedCommands.size() > 1);

    assertThat(receivedCommands, containsInAnyOrder(
        GrpcCompensateCommand.newBuilder().setGlobalTxId(globalTxId).setLocalTxId(localTxId).setParentTxId(parentTxId)
            .setCompensateMethod("method a").setPayloads(ByteString.copyFrom("service a".getBytes())).build(),
        GrpcCompensateCommand.newBuilder().setGlobalTxId(globalTxId).setLocalTxId(localTxId1).setParentTxId(parentTxId1)
            .setCompensateMethod("method b").setPayloads(ByteString.copyFrom("service b".getBytes())).build()
    ));
  }

  @Test
  public void getCompensateCommandOnFailure() {
    StreamObserver<GrpcTxEvent> requestObserver = stub.callbackCommand(compensateResponseObserver);
    requestObserver.onNext(someGrpcEvent(TxStartedEvent));
    await().atMost(1, SECONDS).until(() -> eventRepo.findByEventGlobalTxId(globalTxId) != null);

    requestObserver.onNext(someGrpcEvent(TxAbortedEvent));
    await().atMost(1, SECONDS).until(() -> !receivedCommands.isEmpty());

    assertThat(receivedCommands.get(0).getGlobalTxId(), is(globalTxId));
    assertThat(receivedCommands.get(0).getLocalTxId(), is(localTxId));
    assertThat(receivedCommands.get(0).getParentTxId(), is(parentTxId));
    assertThat(receivedCommands.get(0).getCompensateMethod(), is(compensationMethod));
    assertThat(receivedCommands.get(0).getPayloads().toByteArray(), is(payload.getBytes()));
  }

  private GrpcTxEvent someGrpcEvent(EventType type) {
    return eventOf(type, localTxId, parentTxId, payload.getBytes(), getClass().getCanonicalName());
  }

  private GrpcTxEvent eventOf(EventType eventType, byte[] payloads, String compensationMethod) {
    return eventOf(eventType, UUID.randomUUID().toString(), UUID.randomUUID().toString(), payloads, compensationMethod);
  }

  private GrpcTxEvent eventOf(EventType eventType, String localTxId, String parentTxId, byte[] payloads, String compensationMethod) {
    return GrpcTxEvent.newBuilder()
        .setServiceName(serviceName)
        .setInstanceId(instanceId)
        .setTimestamp(System.currentTimeMillis())
        .setGlobalTxId(globalTxId)
        .setLocalTxId(localTxId)
        .setParentTxId(parentTxId == null ? "" : parentTxId)
        .setType(eventType.name())
        .setCompensationMethod(compensationMethod)
        .setPayloads(ByteString.copyFrom(payloads))
        .build();
  }

  private static class CompensateStreamObserver implements StreamObserver<GrpcCompensateCommand> {
    @Override
    public void onNext(GrpcCompensateCommand command) {
      // intercept received command
      receivedCommands.add(command);
    }

    @Override
    public void onError(Throwable t) {
    }

    @Override
    public void onCompleted() {
    }
  }
}

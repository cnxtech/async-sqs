package com.bandwidth.sqs.consumer;

import static com.bandwidth.sqs.queue.MutableSqsQueueAttributesTest.ATTRIBUTES;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.amazonaws.handlers.AsyncHandler;
import com.bandwidth.sqs.consumer.SqsConsumer.LoadBalanceRequestUpdater;
import com.bandwidth.sqs.consumer.SqsConsumer.ReceiveMessageHandler;
import com.bandwidth.sqs.consumer.SqsConsumer.RequestType;
import com.bandwidth.sqs.consumer.acknowledger.MessageAcknowledger;
import com.bandwidth.sqs.consumer.strategy.expiration.ExpirationStrategy;
import com.bandwidth.sqs.consumer.strategy.loadbalance.LoadBalanceStrategy;
import com.bandwidth.sqs.consumer.strategy.loadbalance.LoadBalanceStrategy.Action;
import com.bandwidth.sqs.consumer.strategy.backoff.BackoffStrategy;
import com.bandwidth.sqs.consumer.handler.ConsumerHandler;
import com.bandwidth.sqs.queue.SqsMessage;
import com.bandwidth.sqs.queue.SqsQueue;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import io.reactivex.Completable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.subjects.SingleSubject;

@SuppressWarnings("unchecked")
public class SqsConsumerTest {

    private static final String MESSAGE_BODY = "message body";
    private static final String RECEIPT_HANDLE = "receipt handle";
    private static final String MESSAGE_ID = "message-id";
    private static final String QUEUE_URL = "http://www.domain/path";
    private static final Duration CONFIGURED_SHUTDOWN_TIMEOUT = Duration.ofSeconds(300);
    private final SqsMessage<String> SQS_MESSAGE = SqsMessage.<String>builder()
            .body(MESSAGE_BODY)
            .id(MESSAGE_ID)
            .receiptHandle(RECEIPT_HANDLE)
            .receivedTime(Instant.now())
            .build();
    private static final int NUM_PERMITS = 2;
    private static final int NO_PERMITS = 0;
    private static final int MAX_QUEUE_SIZE = 20;
    private static final int MAX_QUEUE_SIZE_1 = 1;
    private static final int MESSAGE_COUNT = 7;
    private static final Duration WINDOW_SIZE = Duration.ofSeconds(10);

    private final ArrayDeque<SqsMessage<String>> messageBufferEmpty = spy(new ArrayDeque<>());
    private final ArrayDeque<SqsMessage<String>> messageBufferSmall = spy(new ArrayDeque<>());
    private final ArrayDeque<SqsMessage<String>> messageBufferFull = spy(new ArrayDeque<>());
    private final BackoffStrategy backoffStrategyMock = mock(BackoffStrategy.class);
    private final SqsConsumerManager consumerManagerMock = mock(SqsConsumerManager.class);
    private final ConsumerHandler<String> consumerHandlerMock = mock(ConsumerHandler.class);
    private final SqsQueue<String> sqsQueueMock = mock(SqsQueue.class);
    private final LoadBalanceStrategy loadBalanceStrategyMock = mock(LoadBalanceStrategy.class);
    private final ExpirationStrategy expirationStrategyMock = mock(ExpirationStrategy.class);

    @Captor
    private ArgumentCaptor<MessageAcknowledger> acknowledgerCaptor;

    @Captor
    private ArgumentCaptor<AsyncHandler> asyncHandlerCaptor;

    @Captor
    private ArgumentCaptor<ReceiveMessageHandler> receiveMessageHandlerCaptor;

    private SqsConsumer consumer;

    public SqsConsumerTest() {
        when(consumerHandlerMock.getPermitChangeRequests()).thenReturn(Observable.never());
        when(backoffStrategyMock.getWindowSize()).thenReturn(WINDOW_SIZE);
        when(sqsQueueMock.getAttributes()).thenReturn(Single.just(ATTRIBUTES));

        consumer = new SqsConsumerBuilder(consumerManagerMock, sqsQueueMock, consumerHandlerMock)
                .withNumPermits(NUM_PERMITS)
                .withBufferSize(MAX_QUEUE_SIZE)
                .withBackoffStrategy(backoffStrategyMock)
                .withExpirationStrategy(expirationStrategyMock)
                .withAutoExpire(true)
                .withShutdownTimeout(CONFIGURED_SHUTDOWN_TIMEOUT)
                .build();

        consumer.setLoadBalanceStrategy(loadBalanceStrategyMock);

        messageBufferSmall.push(SQS_MESSAGE);
        for (int i = 0; i < MAX_QUEUE_SIZE; i++) {
            messageBufferFull.push(SQS_MESSAGE);
        }
        when(backoffStrategyMock.getDelayTime(anyDouble())).thenReturn(Duration.ZERO);
        when(sqsQueueMock.receiveMessages(anyInt(), any(Optional.class))).thenReturn(Single.never());
        when(sqsQueueMock.deleteMessage((String) any())).thenReturn(Completable.never());
    }

    @Test
    public void testStartLongPollingRequest() {
        consumer.start();
        ArgumentCaptor<Optional<Duration>> requestCaptor = ArgumentCaptor.forClass(Optional.class);
        //the first request sent will be the long-polling request
        verify(sqsQueueMock).receiveMessages(anyInt(), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(Optional.of(SqsConsumer.MAX_WAIT_TIME));
    }

    @Test
    public void testSetNumPermits() {
        int numPermits = 1234;
        consumer.setNumPermits(numPermits);
        assertThat(consumer.getNumPermits()).isEqualTo(numPermits);
    }

    @Test
    public void testStartFirstLoadBalancedRequest() {
        when(consumerManagerMock.getAllocatedInFlightRequestsCount(consumer)).thenReturn(1);
        consumer.start();//long-polling request will be started first, then load balanced request 2nd
        ArgumentCaptor<Optional<Duration>> requestCaptor = ArgumentCaptor.forClass(Optional.class);
        verify(sqsQueueMock, times(2)).receiveMessages(anyInt(), requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1))
                .isEqualTo(Optional.of(SqsConsumer.LOAD_BALANCED_REQUEST_WAIT_TIME));
    }

    @Test
    public void testUpdateWhileAllRequestsStarted() {
        consumer.start();//long-polling request will be started
        consumer.update();//nothing should be started
        ArgumentCaptor<Optional<Duration>> requestCaptor = ArgumentCaptor.forClass(Optional.class);
        verify(sqsQueueMock).receiveMessages(anyInt(), requestCaptor.capture());
        assertThat(requestCaptor.getValue()).isEqualTo(Optional.of(SqsConsumer.MAX_WAIT_TIME));
    }

    @Test
    public void testStartNewRequestBufferFull() {
        consumer = new SqsConsumerBuilder(consumerManagerMock, sqsQueueMock, consumerHandlerMock)
                .withNumPermits(NO_PERMITS)
                .withBufferSize(MAX_QUEUE_SIZE_1)
                .withBackoffStrategy(backoffStrategyMock)
                .withExpirationStrategy(expirationStrategyMock)
                .withAutoExpire(false)
                .build();
        consumer.setMessageBuffer(messageBufferSmall);
        consumer.start();//buffer is full, no requests will be started
        verify(sqsQueueMock, never()).receiveMessages(anyInt(), any(Optional.class));
    }

    @Test
    public void testQueueForProcessing() {
        consumer.setMessageBuffer(messageBufferSmall);
        consumer.start();//will be queued for processing
        consumer.update();//already queued, won't queue again
        verify(consumerManagerMock).queueTask(any(), anyInt(), any());
    }

    @Test
    public void testBackoffDelay() {
        consumer = new SqsConsumerBuilder(consumerManagerMock, sqsQueueMock, consumerHandlerMock)
                .withNumPermits(NUM_PERMITS)
                .withBufferSize(MAX_QUEUE_SIZE_1)
                .withBackoffStrategy(backoffStrategyMock)
                .withExpirationStrategy(expirationStrategyMock)
                .withAutoExpire(true)
                .build();

        consumer.setMessageBuffer(messageBufferSmall);
        when(backoffStrategyMock.getDelayTime(anyDouble())).thenReturn(Duration.ofDays(999999));
        consumer.applyBackoffDelayIfNeeded();
        consumer.start();//backoffDelay prevents consumer from being queued
        verify(consumerManagerMock, never()).queueTask(any(), anyInt(), any());
    }

    @Test
    public void testNegativeBackoffDelay() {
        when(backoffStrategyMock.getDelayTime(anyDouble())).thenReturn(Duration.ofDays(-1));
        consumer.applyBackoffDelayIfNeeded();
        verify(consumerManagerMock, never()).queueTask(any(), anyInt(), any());
    }

    @Test
    public void testTimerTaskUpdate() {
        SqsConsumer consumerMock = mock(SqsConsumer.class);
        SqsConsumer.UpdateTimerTask task = consumerMock.new UpdateTimerTask();
        task.run();
        verify(consumerMock).update();
    }

    @Test
    public void testProcessNextMessage() {
        consumer.setMessageBuffer(messageBufferSmall);
        consumer.processNextMessage(consumer.getNextMessage());
        verify(consumerHandlerMock).handleMessage(eq(SQS_MESSAGE), any());
    }

    @Test
    public void testProcessNextMessageExpiredIgnore() {
        when(expirationStrategyMock.isExpired(any(), any())).thenReturn(true);
        when(sqsQueueMock.publishMessage(any(), any())).thenReturn(Single.just(MESSAGE_ID));

        ArrayDeque<SqsMessage<String>> messageBuffer = new ArrayDeque<>();
        messageBuffer.push(SqsMessage.<String>builder()
                .from(SQS_MESSAGE)
                .receivedTime(Instant.now().minus(ATTRIBUTES.getVisibilityTimeout()))
                .build());

        consumer.setMessageBuffer(messageBuffer);
        consumer.processNextMessage(consumer.getNextMessage());

        verify(consumerHandlerMock, never()).handleMessage(eq(SQS_MESSAGE), any());
        verify(sqsQueueMock, never()).publishMessage(any(), any());
        verify(sqsQueueMock, never()).deleteMessage(anyString());
    }

    @Test
    public void testLoadBalanceRequestUpdater() {
        consumer.setMessageBuffer(messageBufferSmall);
        LoadBalanceRequestUpdater updater = consumer.new LoadBalanceRequestUpdater(MESSAGE_COUNT);
        updater.getAction(0);
        verify(loadBalanceStrategyMock).onReceiveSuccess(MESSAGE_COUNT);
    }

    @Test
    public void testLoadBalanceRequestUpdaterBufferFull() {
        consumer.setMessageBuffer(messageBufferFull);
        LoadBalanceRequestUpdater updater = consumer.new LoadBalanceRequestUpdater(MESSAGE_COUNT);
        assertThat(updater.getAction(0)).isEqualTo(Action.Decrease);
    }

    @Test
    public void testReceiveMessageHandlerUpdateLoadBalanceRequests() {
        ReceiveMessageHandler handler = consumer.new ReceiveMessageHandler(RequestType.LOAD_BALANCED);
        handler.updateLoadBalanceRequests(MESSAGE_COUNT);
        verify(consumerManagerMock).updateAllocatedInFlightRequests(eq(consumer), any());
    }

    @Test
    public void testReceiveMessageHandlerOnError() {
        ReceiveMessageHandler handler = consumer.new ReceiveMessageHandler(RequestType.LOAD_BALANCED);
        ReceiveMessageHandler handlerSpy = spy(handler);
        handlerSpy.onError(new NullPointerException());
        verify(handlerSpy).always();
    }

    @Test
    public void testReceiveMessageHandlerOnSuccess() {
        SqsConsumer consumerSpy = spy(consumer);
        SingleObserver<List<SqsMessage<String>>> handler =
                spy(consumerSpy.new ReceiveMessageHandler(RequestType.LONG_POLLING));
        handler.onSuccess(Collections.singletonList(SQS_MESSAGE));

        verify(consumerSpy, times(2)).update();
    }

    @Test
    public void testReceiveMessageHandlerOnSuccessNoMessages() {
        consumer.setMessageBuffer(messageBufferEmpty);
        ReceiveMessageHandler handler = consumer.new ReceiveMessageHandler(RequestType.LONG_POLLING);
        handler.onSuccess(Collections.emptyList());

        assertThat(messageBufferEmpty.size()).isEqualTo(0);
    }

    @Test
    public void testDoNotReceiveMessageWhenInShutdown() {
        consumer.shutdown();
        consumer.update();
        verify(sqsQueueMock).getAttributes();
        verifyNoMoreInteractions(sqsQueueMock);
    }

    @Test
    public void testShutdownWithoutStarting() {
        consumer.setMessageBuffer(messageBufferEmpty);
        consumer.shutdown();
        assertThat(consumer.isShutdown()).isTrue();
    }

    @Test
    public void testIsNotTerminatedWhenIsNotShutdown() {
        consumer.setMessageBuffer(messageBufferEmpty);
        assertThat(consumer.isShutdown()).isFalse();
    }

    @Test
    public void testIsNotTerminatedWhenBufferIsNotEmpty() {
        consumer.setMessageBuffer(messageBufferSmall);
        consumer.shutdownAsync().test().assertNotComplete();
    }

    @Test
    public void testIsNotTerminatedWhenProcessingMessage() {
        consumer.setMessageBuffer(messageBufferSmall);
        consumer.processNextMessage(consumer.getNextMessage());
        consumer.shutdownAsync().test().assertNotComplete();
    }

    @Test
    public void testIsNotTerminatedWhenWaitingLongPollRequest() throws Exception {
        consumer.setMessageBuffer(messageBufferEmpty);
        consumer.update();
        consumer.shutdownAsync().test().assertNotComplete();
    }

    @Test
    public void testIsNotTerminatedWhenWaitingLoadBalancedRequest() throws Exception {
        consumer.setMessageBuffer(messageBufferFull);
        when(consumerManagerMock.getAllocatedInFlightRequestsCount(any())).thenReturn(1);
        consumer.update();
        consumer.setMessageBuffer(messageBufferEmpty);
        consumer.shutdownAsync().test().assertNotComplete();
    }

    @Test
    public void testShutdownWithPendingPermits() {
        SingleSubject<List<SqsMessage<String>>> singleSubject = SingleSubject.create();

        when(sqsQueueMock.deleteMessage(any(String.class))).thenReturn(Completable.complete());
        when(sqsQueueMock.receiveMessages(anyInt(), any(Optional.class))).thenReturn(singleSubject);

        consumer.setMessageBuffer(messageBufferSmall);
        consumer.processNextMessage(consumer.getNextMessage());

        //handler does not ack here, so permits will be pending forever

        Completable shutdownCompletable = consumer.shutdownAsync();
        singleSubject.onSuccess(Collections.emptyList());

        shutdownCompletable.test().assertNotComplete();
    }

    @Test
    public void testHandlerDeleteAndShutdown() {
        SingleSubject<List<SqsMessage<String>>> singleSubject = SingleSubject.create();

        when(sqsQueueMock.deleteMessage(any(String.class))).thenReturn(Completable.complete());
        when(sqsQueueMock.receiveMessages(anyInt(), any(Optional.class))).thenReturn(singleSubject);

        doAnswer((invocation -> {
            ((MessageAcknowledger) invocation.getArgument(1)).delete();
            return null;
        })).when(consumerHandlerMock).handleMessage(any(), any());

        consumer.setMessageBuffer(messageBufferSmall);
        consumer.processNextMessage(consumer.getNextMessage());
        Completable shutdownCompletable = consumer.shutdownAsync();
        singleSubject.onSuccess(Collections.emptyList());

        shutdownCompletable.test().assertComplete();
        verify(sqsQueueMock).deleteMessage(any(String.class));
    }

    @Test
    public void testHandlerIgnoreAndShutdown() {
        SingleSubject<List<SqsMessage<String>>> singleSubject = SingleSubject.create();

        when(sqsQueueMock.deleteMessage(any(String.class))).thenReturn(Completable.complete());
        when(sqsQueueMock.receiveMessages(anyInt(), any(Optional.class))).thenReturn(singleSubject);

        doAnswer((invocation -> {
            ((MessageAcknowledger) invocation.getArgument(1)).ignore();
            return null;
        })).when(consumerHandlerMock).handleMessage(any(), any());

        consumer.setMessageBuffer(messageBufferSmall);
        consumer.processNextMessage(consumer.getNextMessage());
        Completable shutdownCompletable = consumer.shutdownAsync();
        singleSubject.onSuccess(Collections.emptyList());

        shutdownCompletable.test().assertComplete();
        verify(sqsQueueMock, never()).deleteMessage(any(String.class));
    }

    @Test
    public void testShutdownTrueWhenComplete() {
        consumer.setMessageBuffer(messageBufferEmpty);
        boolean result = consumer.shutdown(Duration.ofMillis(100));
        assertThat(result).isTrue();
    }

    @Test
    public void testShutdownFalseWhenTimeout() {
        consumer.setMessageBuffer(messageBufferSmall);
        boolean result = consumer.shutdown(Duration.ofMillis(100));
        assertThat(result).isFalse();
    }

    @Test
    public void testUsingConfiguredShutdownTimeout() {
        SqsConsumer spy = spy(consumer);
        spy.shutdown();
        verify(spy).shutdown(CONFIGURED_SHUTDOWN_TIMEOUT);
        verify(spy, never()).shutdown(SqsConsumer.DEFAULT_SHUTDOWN_TIMEOUT);
    }

    @Test
    public void testRetryAck() {
        ConsumerHandler<String> handlerSpy = spy(new RetryingHandler());
        consumer = new SqsConsumerBuilder(consumerManagerMock, sqsQueueMock, handlerSpy)
                .withNumPermits(NO_PERMITS)
                .withBufferSize(MAX_QUEUE_SIZE_1)
                .withBackoffStrategy(backoffStrategyMock)
                .withExpirationStrategy(expirationStrategyMock)
                .build();
        consumer.setMessageBuffer(messageBufferSmall);
        consumer.processNextMessage(consumer.getNextMessage());
        consumer.processNextMessage(consumer.getNextMessage());
        verify(handlerSpy, times(2)).handleMessage(eq(SQS_MESSAGE), any());
    }

    private static class RetryingHandler implements ConsumerHandler<String> {
        @Override
        public void handleMessage(SqsMessage<String> message, MessageAcknowledger<String> messageAcknowledger) {
            messageAcknowledger.retry();
        }
    }
}
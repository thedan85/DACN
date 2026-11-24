package com.project.dacn.support;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.ShutdownSignalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.ChannelCallback;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExternalRabbitTest {

    private final RabbitAdmin admin = mock(RabbitAdmin.class);
    private final RabbitTemplate template = mock(RabbitTemplate.class);
    private final Channel inspection = mock(Channel.class);
    private final Exchange firstExchange = new DirectExchange("dacn.fixture.first");
    private final Exchange secondExchange = new DirectExchange("dacn.fixture.second");
    private final Queue firstQueue = new Queue("dacn.fixture.first");
    private final Queue secondQueue = new Queue("dacn.fixture.second");
    private final Binding binding = new Binding(firstQueue.getName(),
            Binding.DestinationType.QUEUE, firstExchange.getName(), "fixture", null);
    private final List<Declarable> declarations =
            List.of(firstExchange, secondExchange, firstQueue, secondQueue, binding);

    @BeforeEach
    void absentTargets() throws Exception {
        when(admin.getRabbitTemplate()).thenReturn(template);
        when(template.execute(any())).thenAnswer(invocation -> {
            ChannelCallback<?> callback = invocation.getArgument(0);
            try {
                return callback.doInRabbit(inspection);
            } catch (IOException e) {
                throw new AmqpIOException(e);
            }
        });
        doThrow(passiveFailure(404)).when(inspection).queueDeclarePassive(anyString());
        doThrow(passiveFailure(404)).when(inspection).exchangeDeclarePassive(anyString());
        when(admin.declareQueue(any(Queue.class)))
                .thenAnswer(invocation -> ((Queue) invocation.getArgument(0)).getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"queue", "exchange"})
    void refusesAnyPreExistingTargetBeforeMutation(String kind) throws Exception {
        if (kind.equals("queue")) {
            doReturn(null).when(inspection).queueDeclarePassive(secondQueue.getName());
        } else {
            doReturn(null).when(inspection).exchangeDeclarePassive(secondExchange.getName());
        }

        assertThatThrownBy(this::declareThenTearDown)
                .isInstanceOf(IllegalStateException.class);

        verifyNoMutation();
    }

    @ParameterizedTest
    @ValueSource(ints = {403, 406})
    void inspectionFailureDoesNotCountAsAnEmptyTarget(int replyCode) throws Exception {
        doThrow(passiveFailure(replyCode)).when(inspection)
                .queueDeclarePassive(firstQueue.getName());

        assertThatThrownBy(() -> ExternalRabbit.declare(admin, declarations))
                .isInstanceOf(AmqpIOException.class);

        verifyNoMutation();
    }

    @Test
    void partialExchangeFailureCleansOnlySuccessfulCreations() {
        doThrow(new IllegalStateException("declaration failed"))
                .when(admin).declareExchange(secondExchange);

        assertThatThrownBy(() -> ExternalRabbit.declare(admin, declarations))
                .isInstanceOf(IllegalStateException.class);

        verify(admin).deleteExchange(firstExchange.getName());
        verify(admin, never()).deleteExchange(secondExchange.getName());
        verify(admin, never()).deleteQueue(anyString());
        verify(admin, never()).purgeQueue(anyString());
    }

    @Test
    void partialQueueFailureDoesNotAdoptTheFailedQueue() {
        doThrow(new IllegalStateException("declaration failed"))
                .when(admin).declareQueue(secondQueue);

        assertThatThrownBy(() -> ExternalRabbit.declare(admin, declarations))
                .isInstanceOf(IllegalStateException.class);

        verify(admin).deleteQueue(firstQueue.getName());
        verify(admin, never()).deleteQueue(secondQueue.getName());
        verify(admin).deleteExchange(firstExchange.getName());
        verify(admin).deleteExchange(secondExchange.getName());
        verify(admin, never()).purgeQueue(anyString());
    }

    @Test
    void bindingFailureCleansRecordedQueuesAndExchanges() {
        doThrow(new IllegalStateException("binding failed"))
                .when(admin).declareBinding(binding);

        assertThatThrownBy(() -> ExternalRabbit.declare(admin, declarations))
                .isInstanceOf(IllegalStateException.class);

        verify(admin).deleteQueue(firstQueue.getName());
        verify(admin).deleteQueue(secondQueue.getName());
        verify(admin).deleteExchange(firstExchange.getName());
        verify(admin).deleteExchange(secondExchange.getName());
        verify(admin, never()).purgeQueue(anyString());
    }

    @Test
    void anUnconfirmedInspectionFailsClosed() {
        doReturn(null).when(template).execute(any());

        assertThatThrownBy(this::declareThenTearDown)
                .isInstanceOf(IllegalStateException.class);

        verifyNoMutation();
    }

    @Test
    void anUnconfirmedQueueCreationIsNeverPurgedOrDeleted() {
        when(admin.declareQueue(firstQueue)).thenReturn(null);

        assertThatThrownBy(this::declareThenTearDown)
                .isInstanceOf(IllegalStateException.class);

        verify(admin, never()).purgeQueue(anyString());
        verify(admin, never()).deleteQueue(anyString());
        verify(admin).deleteExchange(firstExchange.getName());
        verify(admin).deleteExchange(secondExchange.getName());
    }

    @Test
    void successfulOwnershipPurgesOnlyCreatedQueuesAndClosesOnceInSafeOrder() {
        ExternalRabbit.OwnedTopology owned = ExternalRabbit.declare(admin, declarations);
        owned.purgeQueues();
        owned.close();
        owned.close();

        var order = inOrder(admin);
        order.verify(admin).purgeQueue(firstQueue.getName());
        order.verify(admin).purgeQueue(secondQueue.getName());
        order.verify(admin).deleteQueue(firstQueue.getName());
        order.verify(admin).deleteQueue(secondQueue.getName());
        order.verify(admin).deleteExchange(firstExchange.getName());
        order.verify(admin).deleteExchange(secondExchange.getName());
        verify(admin, times(2)).deleteQueue(anyString());
        verify(admin, times(2)).deleteExchange(anyString());
        assertThatThrownBy(owned::purgeQueues).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void laterSetupFailureStillClosesOnlyInvocationOwnedObjects() {
        assertThatThrownBy(() -> {
            try (var owned = ExternalRabbit.declare(admin, declarations)) {
                throw new IllegalStateException("later setup failed");
            }
        }).isInstanceOf(IllegalStateException.class);

        verify(admin).deleteQueue(firstQueue.getName());
        verify(admin).deleteQueue(secondQueue.getName());
        verify(admin).deleteExchange(firstExchange.getName());
        verify(admin).deleteExchange(secondExchange.getName());
        verify(admin, never()).purgeQueue(anyString());
    }

    @Test
    void failedOwnedCleanupStillAttemptsTheOtherOwnedObjects() {
        ExternalRabbit.OwnedTopology owned = ExternalRabbit.declare(admin, declarations);
        doThrow(new AmqpException("unavailable")).when(admin).deleteQueue(firstQueue.getName());

        owned.close();

        verify(admin).deleteQueue(secondQueue.getName());
        verify(admin).deleteExchange(firstExchange.getName());
        verify(admin).deleteExchange(secondExchange.getName());
    }

    private void declareThenTearDown() {
        ExternalRabbit.OwnedTopology owned = null;
        try {
            owned = ExternalRabbit.declare(admin, declarations);
        } finally {
            if (owned != null) {
                owned.close();
            }
        }
    }

    private void verifyNoMutation() {
        verify(admin, never()).declareExchange(any());
        verify(admin, never()).declareQueue(any(Queue.class));
        verify(admin, never()).declareBinding(any());
        verify(admin, never()).purgeQueue(anyString());
        verify(admin, never()).deleteQueue(anyString());
        verify(admin, never()).deleteExchange(anyString());
    }

    private IOException passiveFailure(int replyCode) {
        AMQP.Channel.Close reason = new AMQP.Channel.Close.Builder()
                .replyCode(replyCode).replyText("inspection response")
                .classId(50).methodId(10).build();
        return new IOException(new ShutdownSignalException(false, false, reason, inspection));
    }
}

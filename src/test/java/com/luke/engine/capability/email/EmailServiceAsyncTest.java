package com.luke.engine.capability.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.luke.engine.capability.email.EmailServerService.SendContext;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * #59: the default send path is asynchronous — {@code sendRaw} persists a QUEUED row, publishes the
 * delivery event, and returns without touching Postmark; only the explicit {@code *Sync} variants
 * deliver inline.
 */
class EmailServiceAsyncTest {

    private final EmailMessageRepository repo = mock(EmailMessageRepository.class);
    private final EmailServerService servers = mock(EmailServerService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final EmailDispatcher dispatcher = mock(EmailDispatcher.class);
    private final EmailService service = new EmailService(repo, servers, events, dispatcher);

    private EmailRequest rawReq() {
        return new EmailRequest(null, "to@example.com", null, null, null, "Subject",
                "<p>hi</p>", "hi", null, null, null, "tag", null, null, null);
    }

    private void stubContext() {
        when(servers.resolveSendContext(any(), any())).thenReturn(new SendContext("server-token", "from@example.com", "outbound"));
        when(repo.save(any())).thenAnswer(a -> a.getArgument(0));
    }

    @Test
    void sendRawQueuesAsynchronouslyAndReturnsQueued() {
        stubContext();

        EmailMessage result = service.sendRaw("t1", "u1", rawReq());

        assertThat(result.getStatus()).isEqualTo(EmailStatus.QUEUED);
        verify(events).publishEvent(any(EmailQueuedEvent.class));
        verifyNoInteractions(dispatcher); // not delivered inline
    }

    @Test
    void sendRawSyncDeliversInlineAndReturnsTerminalStatus() {
        stubContext();
        EmailMessage delivered = new EmailMessage();
        delivered.setStatus(EmailStatus.SENT);
        when(dispatcher.deliver(any(), eq("server-token"), any(), eq(false))).thenReturn(delivered);

        EmailMessage result = service.sendRawSync("t1", "u1", rawReq());

        assertThat(result.getStatus()).isEqualTo(EmailStatus.SENT);
        verify(dispatcher).deliver(any(), eq("server-token"), any(), eq(false));
        verifyNoInteractions(events); // synchronous — no queue event
    }
}

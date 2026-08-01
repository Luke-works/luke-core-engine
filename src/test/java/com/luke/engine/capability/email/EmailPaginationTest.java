package com.luke.engine.capability.email;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** #52: the email list is paged + size-capped, and reports the full server-side total. */
class EmailPaginationTest {

    private EmailController controller(EmailMessageRepository repo) {
        return new EmailController(mock(EmailService.class), repo, mock(InboundEmailRepository.class));
    }

    @Test
    void capsPageSizeAndReportsTotal() {
        EmailMessageRepository repo = mock(EmailMessageRepository.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        Page<EmailMessage> page = new PageImpl<>(List.of(new EmailMessage()), PageRequest.of(0, 200), 999);
        when(repo.findByTenantId(eq("t"), pageable.capture())).thenReturn(page);

        EmailController.PagedEmails result = controller(repo).list("t", null, 0, 5000);

        assertEquals(999, result.total());                    // total from the server, not a client count
        assertEquals(1, result.items().size());
        assertEquals(200, pageable.getValue().getPageSize());  // over-large maxResults capped to MAX_PAGE
    }

    @Test
    void derivesPageFromOffsetAndAppliesStatusFilter() {
        EmailMessageRepository repo = mock(EmailMessageRepository.class);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        when(repo.findByTenantIdAndStatus(eq("t"), eq("FAILED"), pageable.capture()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 50), 120));

        controller(repo).list("t", "FAILED", 100, 50);        // offset 100 / size 50 = page 2

        assertEquals(2, pageable.getValue().getPageNumber());
        assertEquals(50, pageable.getValue().getPageSize());
    }
}

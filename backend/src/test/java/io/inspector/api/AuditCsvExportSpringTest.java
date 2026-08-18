package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.inspector.audit.AuditEntry;
import io.inspector.audit.AuditEntryRepository;
import io.inspector.audit.AuditOutcome;
import io.inspector.support.NoDbTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

/**
 * R-AUD-08 (usability W3-1): the operations-log CSV export — the SAME filters as
 * {@code GET /api/audit}, streamed as {@code text/csv} with an attachment disposition,
 * every text cell formula-escaped per R-OPS-08 (a hostile reason like
 * {@code =HYPERLINK(...)} must never reach a spreadsheet as a live formula). Payload and
 * response-snippet bodies are deliberately NOT exported — they stay role-gated in the app
 * (R-AUD-03); the CSV is the accountability skeleton.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NoDbTestSupport.class)
class AuditCsvExportSpringTest {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AuditEntryRepository repository;

    @BeforeEach
    void resetMock() {
        reset(repository);
    }

    private static AuditEntry row(String actor, String reason, AuditOutcome outcome) {
        AuditEntry entry = new AuditEntry(
                UUID.randomUUID(),
                "corr-1",
                actor,
                Instant.parse("2026-07-10T06:30:00Z"),
                "engine-a",
                null,
                "pi-42",
                "retry-job",
                reason,
                "OPS-7",
                "{\"jobId\":\"j1\"}",
                false);
        entry.close(outcome, 204, "secret-bearing snippet", false);
        return entry;
    }

    @Test
    void exportsCsvWithAttachmentDispositionAndSkeletonHeader() {
        when(repository.findLog(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(row("k.meier", "stuck after hotfix", AuditOutcome.ok)));

        ResponseEntity<String> response =
                rest.withBasicAuth("viewer", "dev").getForEntity("/api/audit/export", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition")).contains("operations-log.csv");
        String[] lines = response.getBody().split("\n");
        assertThat(lines[0])
                .isEqualTo("ts,actor,action,engineId,tenantId,instanceId,outcome,httpStatus,reason,ticketId,"
                        + "correlationId,breakGlass");
        assertThat(lines[1])
                .isEqualTo("2026-07-10T06:30:00Z,k.meier,retry-job,engine-a,,pi-42,ok,204,"
                        + "stuck after hotfix,OPS-7,corr-1,false");
        // The role-gated bodies never travel in the export (R-AUD-03).
        assertThat(response.getBody()).doesNotContain("jobId").doesNotContain("snippet");
    }

    @Test
    void hostileTextCellsAreFormulaEscaped() {
        when(repository.findLog(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of(row("=cmd|' /C calc'!A0", "=HYPERLINK(\"http://evil\")", AuditOutcome.failed)));

        String body = rest.withBasicAuth("viewer", "dev")
                .getForEntity("/api/audit/export", String.class)
                .getBody();

        assertThat(body).contains(",'=cmd|' /C calc'!A0,");
        assertThat(body).contains("\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(body).doesNotContain("\n=").doesNotContain(",=");
    }

    @Test
    void theSameFiltersAsTheJsonLogAreForwarded() {
        when(repository.findLog(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        ResponseEntity<String> response = rest.withBasicAuth("viewer", "dev")
                .getForEntity(
                        "/api/audit/export?actor=k.meier&action=retry-job&engineId=engine-a"
                                + "&ticketId=OPS-7&since=2026-07-10T00:00:00Z",
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // What this test claims is WHICH filters reach the repository — not how many times the
        // request was delivered. Asserting the latter made it flaky: nightly #53 (2026-08-14)
        // failed here with "wanted 1 time but was 2 times", both invocations carrying these
        // exact filters, on a SHA that passed the next four nights. The export handler
        // provably queries once per request (an empty page is short, so the streaming loop in
        // AuditController breaks after page 0), so a second identical invocation means the GET
        // itself was delivered twice — an idempotent request replayed underneath the client,
        // which says nothing about the controller.
        //
        // So: assert the filters on EVERY query instead of the call count. That is tolerant of
        // the replay and STRICTER than the old times(1) on what matters here — the loop passes
        // the filters afresh on each iteration, so a refactor that carried them on page 0 only
        // is a real regression class this now catches and times(1) never did. The
        // call-count/paging contract is covered by aFullFirstPageDoesNotEndTheExport_andLimitClampsIt.
        //
        // Deliberately NOT verifyNoMoreInteractions: this mock is shared across the cached
        // context and reset in @BeforeEach, so an unverified-interaction assertion would fail on
        // any stray late interaction — trading this flake for a subtler one.
        verify(repository, atLeastOnce())
                .findLog(
                        eq("k.meier"),
                        eq("retry-job"),
                        eq("engine-a"),
                        eq("OPS-7"),
                        eq(Instant.parse("2026-07-10T00:00:00Z")),
                        any(Pageable.class));
        assertThat(mockingDetails(repository).getInvocations())
                .isNotEmpty()
                .allSatisfy(invocation -> assertThat(invocation.getArguments())
                        .startsWith(
                                "k.meier", "retry-job", "engine-a", "OPS-7", Instant.parse("2026-07-10T00:00:00Z")));
    }

    @Test
    void aFullFirstPageDoesNotEndTheExport_andLimitClampsIt() {
        // External review (Copilot W3-1 #6/#7): the streaming loop must keep paging while
        // pages come back full — offset paging only returns a short page when the query is
        // exhausted — and the `limit` parameter must cap the row count mid-page.
        List<AuditEntry> fullPage = IntStream.range(0, 500)
                .mapToObj(i -> row("k.meier", "reason-" + i, AuditOutcome.ok))
                .toList();
        when(repository.findLog(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(fullPage)
                .thenReturn(List.of(row("k.meier", "tail-row", AuditOutcome.ok)));

        String body = rest.withBasicAuth("viewer", "dev")
                .getForEntity("/api/audit/export", String.class)
                .getBody();

        assertThat(body.split("\n")).hasSize(1 + 500 + 1); // header + full page + short page
        verify(repository).findLog(any(), any(), any(), any(), any(), eq(PageRequest.of(0, 500)));
        verify(repository).findLog(any(), any(), any(), any(), any(), eq(PageRequest.of(1, 500)));

        reset(repository);
        when(repository.findLog(any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(fullPage);

        String clamped = rest.withBasicAuth("viewer", "dev")
                .getForEntity("/api/audit/export?limit=3", String.class)
                .getBody();

        assertThat(clamped.split("\n")).hasSize(1 + 3); // header + the capped rows
    }

    @Test
    void unauthenticatedIsRefused() {
        ResponseEntity<String> response = rest.getForEntity("/api/audit/export", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}

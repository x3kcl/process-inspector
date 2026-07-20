package io.inspector.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.inspector.audit.ProtectedInstanceRepository;
import io.inspector.client.ProcessApiClient;
import io.inspector.config.InspectorProperties;
import io.inspector.dto.SearchRequest;
import io.inspector.dto.SearchRequest.InstanceStatus;
import io.inspector.dto.SearchResponse;
import io.inspector.dto.SearchResponse.SignatureGeneration;
import io.inspector.registry.EngineRegistry;
import io.inspector.triage.ErrorSignatureNormalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * #279 (R-SEM-03): a signature drill link is bound to the normalizer generation that minted its
 * hash. Rung-1 coverage of the pure {@link SearchService#signatureGenerationNotice} decision AND
 * the short-circuit it drives — a KNOWN-stale signature returns zero-WITH-REASON without touching
 * an engine (the read-path analogue of the write-path 409 refusal), while a legacy/unstamped link
 * still runs but carries the advisory. Version literals track {@link ErrorSignatureNormalizer
 * #ALGO_VERSION} rather than pinning a number (TEST-STRATEGY §4: a pinned literal fails loudly on
 * the next bump — a false red — instead of exercising its subject).
 */
class SearchServiceSignatureGenerationTest {

    private static final int CURRENT = ErrorSignatureNormalizer.ALGO_VERSION;
    private static final int STALE = CURRENT - 1;
    private static final String HASH = "deadbeef".repeat(8); // 64-hex opaque signature key

    private final EngineRegistry registry = mock(EngineRegistry.class);
    private final ProcessApiClient flowable = mock(ProcessApiClient.class);
    private final SearchService service = new SearchService(
            registry,
            flowable,
            new InspectorProperties(4, 10, null, null, null, List.of()),
            mock(ProtectedInstanceRepository.class));

    /* ---------------- the pure decision ---------------- */

    @Test
    void noNoticeWhenTheSearchDoesNotFilterOnASignature() {
        assertThat(SearchService.signatureGenerationNotice(signature(null, null)))
                .isNull();
    }

    @Test
    void noNoticeForACurrentGenerationLink() {
        assertThat(SearchService.signatureGenerationNotice(signature(HASH, CURRENT)))
                .isNull();
    }

    @Test
    void aStampedNonCurrentLinkIsProvablyEmptyWithAReason() {
        SignatureGeneration notice = SearchService.signatureGenerationNotice(signature(HASH, STALE));
        assertThat(notice).isNotNull();
        assertThat(notice.current()).isFalse();
        assertThat(notice.provablyEmpty()).isTrue();
        assertThat(notice.requestedAlgoVersion()).isEqualTo(STALE);
        assertThat(notice.currentAlgoVersion()).isEqualTo(CURRENT);
        assertThat(notice.reason())
                .contains("v" + STALE)
                .contains("v" + CURRENT)
                .contains("stale link");
    }

    @Test
    void aLegacyUnstampedLinkIsAssumedUnknownNotCurrentAndNotProvablyEmpty() {
        // The deliberate #279 decision: an un-versioned legacy param is assumed UNKNOWN, never
        // assumed-current (assuming current is exactly today's silent-zero default).
        SignatureGeneration notice = SearchService.signatureGenerationNotice(signature(HASH, null));
        assertThat(notice).isNotNull();
        assertThat(notice.current()).isFalse();
        assertThat(notice.provablyEmpty()).isFalse(); // it MIGHT carry a current hash — so it still runs
        assertThat(notice.requestedAlgoVersion()).isNull();
        assertThat(notice.reason()).contains("predates").contains("v" + CURRENT);
    }

    /* ---------------- the short-circuit it drives ---------------- */

    @Test
    void aKnownStaleSignatureSearchReturnsZeroWithReasonWithoutTouchingAnyEngine() {
        SearchResponse response = service.search(signature(HASH, STALE));

        assertThat(response.rows()).isEmpty();
        assertThat(response.perEngine()).isEmpty();
        assertThat(response.signatureGeneration()).isNotNull();
        assertThat(response.signatureGeneration().provablyEmpty()).isTrue();
        // Provably empty ⇒ never fanned out: no engine registry read, no Flowable call.
        verifyNoInteractions(flowable);
        verifyNoInteractions(registry);
    }

    @Test
    void aLegacyUnstampedSignatureSearchStillRunsAndCarriesTheAdvisory() {
        // No engines registered ⇒ the fan-out is a no-op, but the search PROCEEDED (registry read)
        // rather than short-circuiting — the legacy link is not provably empty.
        when(registry.all()).thenReturn(List.of());

        SearchResponse response = service.search(signature(HASH, null));

        assertThat(response.rows()).isEmpty();
        assertThat(response.signatureGeneration()).isNotNull();
        assertThat(response.signatureGeneration().provablyEmpty()).isFalse();
        assertThat(response.signatureGeneration().requestedAlgoVersion()).isNull();
        verifyNoInteractions(flowable); // no engines, so nothing to call — but it TRIED to resolve targets
    }

    private static SearchRequest signature(String hash, Integer algoVersion) {
        return new SearchRequest(
                null,
                List.of(InstanceStatus.FAILED),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                hash,
                null,
                null,
                null,
                null,
                null,
                algoVersion);
    }
}

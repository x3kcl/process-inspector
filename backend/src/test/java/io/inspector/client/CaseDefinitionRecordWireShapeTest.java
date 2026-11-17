package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed case-definition and case-migration batch records (#474) against RECORDED
 * flowable-rest documents ({@code src/test/resources/wire/}): {@code demo-case-migration-v1/v2}
 * deployed over REST on 6.8.0 and 7.1.0 (and removed), and one completed {@code caseMigration}
 * batch of the dev 7.1.0 engine. The mapper is a bare, STRICT {@link ObjectMapper}, so the
 * tolerance of the fields the inspector never reads ({@code url}, {@code deploymentUrl},
 * {@code diagramResource}, {@code description}, {@code startFormDefined}, the part's
 * {@code batchUrl} / {@code subScopeId} / {@code searchKey}, the page's {@code sort}/{@code order})
 * is proven on the record, not borrowed from Spring's lenient default.
 *
 * <p>CMMN is gated to 6.8+ and the batch route is 7.x only, so there is no older shape to pin.
 */
class CaseDefinitionRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, TypeReference<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in =
                            CaseDefinitionRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s", fixture)
                .doesNotThrowAnyException();
        return out.get();
    }

    private static <T> T read(String fixture, Class<T> type) {
        return read(fixture, new TypeReference<T>() {
            @Override
            public java.lang.reflect.Type getType() {
                return type;
            }
        });
    }

    private static <T> T parse(String json, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> out.set(STRICT.readValue(json, type)))
                .as("deserialising %s", json)
                .doesNotThrowAnyException();
        return out.get();
    }

    /* ============================ case definitions ============================ */

    @Test
    void caseDefinitionOf68HasABareUuidIdAndTheResourceOnlyAsAUrl() {
        CaseDefinitionRecord d = read("6.8/cmmn-case-definition.json", CaseDefinitionRecord.class);

        assertThat(d.id()).isEqualTo("6cf1b914-e239-11f1-937e-fac3e2cbda6e"); // bare uuid, no key:version
        assertThat(d.key()).isEqualTo("demoCaseMigration");
        assertThat(d.name()).isEqualTo("Demo case migration");
        assertThat(d.version()).isEqualTo(2);
        assertThat(d.deploymentId()).isEqualTo("6cebecb1-e239-11f1-937e-fac3e2cbda6e");
        assertThat(d.category()).isEqualTo("http://flowable.org/cmmn");
        assertThat(d.tenantId()).isEmpty(); // "" on the wire, not null
        assertThat(d.resource())
                .isEqualTo("http://localhost:8080/flowable-rest/cmmn-api/cmmn-repository/deployments/"
                        + "6cebecb1-e239-11f1-937e-fac3e2cbda6e/resources/demo-case-migration-v2.cmmn.xml");
        assertThat(d.graphicalNotationDefined()).isTrue();
        // A case definition serialises neither a plain resource name nor a suspension flag.
        assertThat(d.resourceName()).isNull();
        assertThat(d.suspended()).isNull();
    }

    @Test
    void caseDefinitionOf71HasTheSameShape() {
        CaseDefinitionRecord d = read("7.1/cmmn-case-definition.json", CaseDefinitionRecord.class);

        assertThat(d.id()).isEqualTo("6d2b184f-e239-11f1-acb8-7eebefdaa7d9");
        assertThat(d.key()).isEqualTo("demoCaseMigration");
        assertThat(d.version()).isEqualTo(2);
        assertThat(d.deploymentId()).isEqualTo("6d23501c-e239-11f1-acb8-7eebefdaa7d9");
        assertThat(d.tenantId()).isEmpty();
        assertThat(d.resource()).endsWith("/resources/demo-case-migration-v2.cmmn.xml");
        assertThat(d.resourceName()).isNull();
        assertThat(d.suspended()).isNull();
    }

    @Test
    void versionsDescPageKeepsTheEngineOrderAndTotalOnBothMajors() {
        for (String major : List.of("6.8", "7.1")) {
            CaseDefinitionPage page = read(major + "/cmmn-case-definitions-by-key-desc.json", CaseDefinitionPage.class);

            assertThat(page.total()).as(major).isEqualTo(2);
            assertThat(page.start()).as(major).isZero();
            assertThat(page.size()).as(major).isEqualTo(2);
            assertThat(page.dataOrEmpty())
                    .as(major)
                    .extracting(CaseDefinitionRecord::version)
                    .containsExactly(2, 1); // newest first, as asked
            assertThat(page.dataOrEmpty())
                    .as(major)
                    .extracting(CaseDefinitionRecord::key)
                    .containsOnly("demoCaseMigration");
            assertThat(page.dataOrEmpty().get(0).id())
                    .as(major)
                    .isNotEqualTo(page.dataOrEmpty().get(1).id());
        }
    }

    @Test
    void anAbsentFieldIsNullAndABlankOneStaysBlank() {
        CaseDefinitionRecord d =
                parse("{\"id\":\"d-1\",\"name\":\"\",\"resourceName\":\"a.cmmn.xml\"}", CaseDefinitionRecord.class);

        assertThat(d.id()).isEqualTo("d-1");
        assertThat(d.name()).isEmpty(); // blank is not absent
        assertThat(d.resourceName()).isEqualTo("a.cmmn.xml"); // read when an engine sends it
        assertThat(d.key()).isNull();
        assertThat(d.version()).isNull(); // absent, not 0
        assertThat(d.tenantId()).isNull();
        assertThat(d.graphicalNotationDefined()).isNull();
    }

    @Test
    void aPageWithoutDataIsEmptyNotNull() {
        assertThat(parse("{\"total\":0}", CaseDefinitionPage.class).dataOrEmpty())
                .isEmpty();
        assertThat(CaseDefinitionPage.empty().dataOrEmpty()).isEmpty();
        assertThat(CaseDefinitionPage.empty().total()).isZero();
    }

    /* ============================ case-migration batches ============================ */

    @Test
    void batchOf71CarriesSourceAndTargetDefinitionInTheSearchKeys() {
        BatchPage page = read("7.1/case-migration-batches.json", BatchPage.class);

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.dataOrEmpty()).hasSize(1);
        BatchRecord b = page.dataOrEmpty().get(0);
        assertThat(b.id()).isEqualTo("21153102-dec0-11f1-acb8-7eebefdaa7d9");
        assertThat(b.batchType()).isEqualTo("caseMigration");
        assertThat(b.searchKey()).isEqualTo("0bbfeae6-dec0-11f1-acb8-7eebefdaa7d9"); // source definition
        assertThat(b.searchKey2()).isEqualTo("0c0928ea-dec0-11f1-acb8-7eebefdaa7d9"); // target definition
        assertThat(b.createTime()).isEqualTo("2026-11-12T17:40:19.532Z"); // verbatim string
        assertThat(b.completeTime()).isEqualTo("2026-11-12T17:40:31.418Z");
        assertThat(b.status()).isEqualTo("completed");
        assertThat(b.tenantId()).isNull(); // explicit JSON null
    }

    @Test
    void aRunningBatchHasNoCompleteTime() {
        BatchRecord b = parse(
                "{\"id\":\"b-1\",\"status\":\"inProgress\",\"completeTime\":null,\"url\":\"http://x\"}",
                BatchRecord.class);

        assertThat(b.status()).isEqualTo("inProgress");
        assertThat(b.completeTime()).isNull();
        assertThat(b.searchKey2()).isNull();
        assertThat(b.createTime()).isNull();
    }

    @Test
    void aBatchPageWithoutDataIsEmptyNotNull() {
        assertThat(parse("{\"total\":0}", BatchPage.class).dataOrEmpty()).isEmpty();
        assertThat(BatchPage.empty().dataOrEmpty()).isEmpty();
    }

    @Test
    void batchPartsOf71AreABareArrayWithOneRowPerCase() {
        List<BatchPartRecord> parts =
                read("7.1/case-migration-batch-parts.json", new TypeReference<List<BatchPartRecord>>() {});

        assertThat(parts).hasSize(3);
        BatchPartRecord p = parts.get(0);
        assertThat(p.id()).isEqualTo("21155813-dec0-11f1-acb8-7eebefdaa7d9");
        assertThat(p.batchId()).isEqualTo("21153102-dec0-11f1-acb8-7eebefdaa7d9");
        assertThat(p.scopeId()).isEqualTo("0c510773-dec0-11f1-acb8-7eebefdaa7d9"); // the case instance
        assertThat(p.scopeType()).isEqualTo("cmmn");
        assertThat(p.status()).isEqualTo("success");
        assertThat(p.completeTime()).isEqualTo("2026-11-12T17:40:19.609Z");
        assertThat(parts).extracting(BatchPartRecord::status).containsOnly("success");
        assertThat(parts).extracting(BatchPartRecord::scopeId).doesNotHaveDuplicates();
    }

    @Test
    void aPartWithoutAStatusKeepsItNull() {
        BatchPartRecord p = parse("{\"id\":\"p-1\",\"scopeId\":\"c-1\",\"subScopeId\":null}", BatchPartRecord.class);

        assertThat(p.scopeId()).isEqualTo("c-1");
        assertThat(p.status()).isNull();
        assertThat(p.completeTime()).isNull();
    }
}

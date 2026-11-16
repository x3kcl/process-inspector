package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Pins the typed process-definition and deployment records (#474) against the RECORDED
 * flowable-rest 6.8 / 7.1 documents ({@code src/test/resources/wire/}). The mapper is a bare,
 * STRICT {@link ObjectMapper} — it fails on an unknown property unless the record itself tolerates
 * it — so the tolerance is proven on the record, not borrowed from Spring's lenient default. Every
 * recorded row carries fields the inspector never reads ({@code url}, {@code deploymentUrl},
 * {@code diagramResource}, {@code startFormDefined}, {@code parentDeploymentId}, {@code contentUrl},
 * …) and the page carries {@code order}/{@code sort}.
 */
class ProcessDefinitionRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in =
                            ProcessDefinitionRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    private static <T> T parse(String json, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> out.set(STRICT.readValue(json, type)))
                .as("deserialising %s", json)
                .doesNotThrowAnyException();
        return out.get();
    }

    @Test
    void definitionOf68KeepsEveryFieldTheReadersUse() {
        ProcessDefinitionRecord def = read("6.8/process-definition.json", ProcessDefinitionRecord.class);

        assertThat(def.id()).isEqualTo("demoFailingPayment:1:e553376f-de72-11f1-a5cc-c2b29413085e");
        assertThat(def.key()).isEqualTo("demoFailingPayment");
        assertThat(def.name()).isEqualTo("Demo Failing Payment");
        assertThat(def.version()).isEqualTo(1);
        assertThat(def.suspended()).isFalse(); // a real false, not absent
        assertThat(def.deploymentId()).isEqualTo("e547eccc-de72-11f1-a5cc-c2b29413085e");
        assertThat(def.category()).isEqualTo("http://inspector.io/demo");
        assertThat(def.tenantId()).isEmpty(); // "" on the wire, not null
        assertThat(def.graphicalNotationDefined()).isTrue();
        // The engine serialises the resource as a URL; the plain resourceName is not on the wire.
        assertThat(def.resource())
                .isEqualTo("http://localhost:8080/flowable-rest/service/repository/deployments/"
                        + "e547eccc-de72-11f1-a5cc-c2b29413085e/resources/demo-failing-payment.bpmn20.xml");
        assertThat(def.resourceName()).isNull();
    }

    @Test
    void definitionOf71ReadsTheSameWay() {
        ProcessDefinitionRecord def = read("7.1/process-definition.json", ProcessDefinitionRecord.class);

        assertThat(def.id()).isEqualTo("demoFailingPayment:1:e71b257e-de72-11f1-9e47-dad7623d9163");
        assertThat(def.key()).isEqualTo("demoFailingPayment");
        assertThat(def.version()).isEqualTo(1);
        assertThat(def.suspended()).isFalse();
        assertThat(def.deploymentId()).isEqualTo("e711617b-de72-11f1-9e47-dad7623d9163");
        assertThat(def.tenantId()).isEmpty();
        assertThat(def.resource()).endsWith("/resources/demo-failing-payment.bpmn20.xml");
    }

    @Test
    void definitionPageKeepsTotalAndRows() {
        ProcessDefinitionPage page68 = read("6.8/process-definitions-by-key.json", ProcessDefinitionPage.class);
        ProcessDefinitionPage page71 = read("7.1/process-definitions-by-key-retry.json", ProcessDefinitionPage.class);

        assertThat(page68.total()).isEqualTo(1);
        assertThat(page68.size()).isEqualTo(1);
        assertThat(page68.dataOrEmpty()).hasSize(1);
        assertThat(page68.dataOrEmpty().get(0))
                .isEqualTo(read("6.8/process-definition.json", ProcessDefinitionRecord.class));
        assertThat(page71.total()).isEqualTo(1);
        assertThat(page71.dataOrEmpty())
                .extracting(ProcessDefinitionRecord::key)
                .containsExactly("demoFailingRetry");
    }

    @Test
    void definitionWithoutTheOptionalFieldsLeavesThemNull() {
        ProcessDefinitionRecord def = parse(
                "{\"id\":\"p:3:9\",\"key\":\"p\",\"resourceName\":\"p.bpmn20.xml\",\"futureField\":{\"a\":1}}",
                ProcessDefinitionRecord.class);

        assertThat(def.id()).isEqualTo("p:3:9");
        assertThat(def.resourceName()).isEqualTo("p.bpmn20.xml");
        assertThat(def.version()).isNull(); // absent is null, never 0
        assertThat(def.suspended()).isNull(); // absent is null, never false
        assertThat(def.graphicalNotationDefined()).isNull();
        assertThat(def.tenantId()).isNull();
        assertThat(def.name()).isNull();
    }

    @Test
    void emptyDefinitionPageAndNullDataAreAnEmptyList() {
        assertThat(ProcessDefinitionPage.empty().dataOrEmpty()).isEmpty();
        assertThat(ProcessDefinitionPage.empty().total()).isZero();
        assertThat(parse("{\"total\":7,\"sort\":\"name\"}", ProcessDefinitionPage.class)
                        .dataOrEmpty())
                .isEmpty();
        assertThat(parse("{\"total\":7,\"sort\":\"name\"}", ProcessDefinitionPage.class)
                        .total())
                .isEqualTo(7);
    }

    @Test
    void deploymentKeepsItsVerbatimDeployTimeOnBothMajors() {
        DeploymentRecord d68 = read("6.8/deployment.json", DeploymentRecord.class);
        DeploymentRecord d71 = read("7.1/deployment.json", DeploymentRecord.class);

        assertThat(d68.id()).isEqualTo("e547eccc-de72-11f1-a5cc-c2b29413085e");
        assertThat(d68.name()).isEqualTo("demo-failing-payment");
        assertThat(d68.category()).isNull(); // explicit JSON null
        assertThat(d68.tenantId()).isEmpty(); // "" on the wire, not null
        assertThat(d68.deploymentTime()).isEqualTo("2026-11-12T08:27:27.953Z"); // verbatim string
        assertThat(d71.id()).isEqualTo("e711617b-de72-11f1-9e47-dad7623d9163");
        assertThat(d71.deploymentTime()).isEqualTo("2026-11-12T08:27:30.951Z");
    }

    @Test
    void deploymentPageKeepsTotalAndRowOrder() {
        DeploymentPage page68 = read("6.8/deployments.json", DeploymentPage.class);
        DeploymentPage page71 = read("7.1/deployments.json", DeploymentPage.class);

        assertThat(page68.total()).isEqualTo(6);
        assertThat(page68.dataOrEmpty()).hasSize(6);
        assertThat(page68.dataOrEmpty().get(0).name()).isEqualTo("Demo processes");
        assertThat(page68.dataOrEmpty().get(1)).isEqualTo(read("6.8/deployment.json", DeploymentRecord.class));
        assertThat(page71.total()).isEqualTo(6);
        assertThat(page71.dataOrEmpty().get(1)).isEqualTo(read("7.1/deployment.json", DeploymentRecord.class));
        assertThat(DeploymentPage.empty().dataOrEmpty()).isEmpty();
        assertThat(parse("{\"total\":2}", DeploymentPage.class).dataOrEmpty()).isEmpty();
    }

    @Test
    void deploymentResourcesAreABareArrayOfRowsNamedByTheirId() {
        for (String v : List.of("6.8", "7.1")) {
            DeploymentResourceRecord[] rows = read(v + "/deployment-resources.json", DeploymentResourceRecord[].class);

            assertThat(rows)
                    .as(v)
                    .extracting(DeploymentResourceRecord::id)
                    .containsExactly("demo-failing-payment.bpmn20.xml", "demo-failing-payment.demoFailingPayment.png");
            assertThat(rows[0].mediaType()).isEqualTo("text/xml");
            assertThat(rows[0].type()).isEqualTo("processDefinition");
            assertThat(rows[1].type()).isEqualTo("resource");
            assertThat(rows[0].name()).isNull(); // not serialised: the id IS the resource name
        }
    }
}

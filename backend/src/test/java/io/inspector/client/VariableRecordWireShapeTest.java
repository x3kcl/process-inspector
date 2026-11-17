package io.inspector.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the typed variable ENVELOPE (#474) — and the last fixed-shape reads of the family
 * ({@code /management/engine}, {@code /management/tables}, the CMMN case identity links) — against
 * the RECORDED flowable-rest 6.8 / 7.1 documents ({@code src/test/resources/wire/}), read with a
 * bare, STRICT {@link ObjectMapper}: tolerance of {@code processInstanceUrl}, the page's
 * {@code sort}/{@code order}, a table row's {@code url} … is proven on the record itself.
 *
 * <p>The envelope is typed; the VALUE stays whatever JSON the engine sent. The fixtures were
 * recorded from one throwaway instance / case carrying a long beyond 2^53, a small long, a date, a
 * null, a json document and a binary variable, plus two execution-local variables. What must
 * survive the typing: the engine's type string verbatim; a JSON number at its JSON width (a big
 * long stays a {@link Long}, never a double or an int); a date string verbatim; a null-valued
 * variable PRESENT with a null value and — as the engine serialises it — NO type; a binary
 * variable's {@code valueUrl}; the leg's own {@code scope} ({@code local} on the runtime lists,
 * JSON null on a single read and on every case variable, {@code global}/{@code local} in history).
 */
class VariableRecordWireShapeTest {

    private static final ObjectMapper STRICT = new ObjectMapper();
    private static final long BEYOND_DOUBLE = 9007199254740993L; // 2^53 + 1: a double cannot hold it

    private static <T> T read(String fixture, Class<T> type) {
        AtomicReference<T> out = new AtomicReference<>();
        assertThatCode(() -> {
                    try (InputStream in = VariableRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
                        if (in == null) throw new IOException("missing fixture wire/" + fixture);
                        out.set(STRICT.readValue(in, type));
                    }
                })
                .as("deserialising wire/%s into %s", fixture, type.getSimpleName())
                .doesNotThrowAnyException();
        return out.get();
    }

    private static JsonNode tree(String fixture) throws IOException {
        try (InputStream in = VariableRecordWireShapeTest.class.getResourceAsStream("/wire/" + fixture)) {
            return STRICT.readTree(in);
        }
    }

    private static Map<String, VariableRecord> byName(VariableRecord[] rows) {
        return Arrays.stream(rows)
                .collect(Collectors.toMap(VariableRecord::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    private static Map<String, Object> expectedJsonDocument() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("b", null);
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("a", List.of(1, 2, inner));
        doc.put("n", BEYOND_DOUBLE);
        return doc;
    }

    /** The value/type assertions every leg shares — the leg's scope is asserted by the caller. */
    private static void assertTypedValues(Map<String, VariableRecord> vars) {
        assertThat(vars.get("aLong").type()).isEqualTo("long");
        assertThat(vars.get("aLong").value()).isInstanceOf(Long.class).isEqualTo(BEYOND_DOUBLE);
        // a long that fits an int arrives at its JSON width — exactly as the untyped map read it
        assertThat(vars.get("aSmallLong").type()).isEqualTo("long");
        assertThat(vars.get("aSmallLong").value()).isInstanceOf(Integer.class).isEqualTo(5);
        assertThat(vars.get("anInteger").type()).isEqualTo("integer");
        assertThat(vars.get("anInteger").value()).isEqualTo(100);
        assertThat(vars.get("aDouble").type()).isEqualTo("double");
        assertThat(vars.get("aDouble").value()).isInstanceOf(Double.class).isEqualTo(1.5d);
        assertThat(vars.get("aBoolean").type()).isEqualTo("boolean");
        assertThat(vars.get("aBoolean").value()).isEqualTo(Boolean.FALSE);
        // a string that LOOKS like a date stays a string; a date stays the engine's verbatim text
        assertThat(vars.get("aString").type()).isEqualTo("string");
        assertThat(vars.get("aString").value()).isEqualTo("2026-01-02");
        assertThat(vars.get("aDate").type()).isEqualTo("date");
        assertThat(vars.get("aDate").value()).isInstanceOf(String.class).isEqualTo("2026-01-02T03:04:05Z");
        assertThat(vars.get("aJson").type()).isEqualTo("json");
        assertThat(vars.get("aJson").value()).isEqualTo(expectedJsonDocument());
        // null-valued: the row is THERE (distinct from an absent variable), value null, and the
        // engine serialises no "type" key at all
        assertThat(vars).containsKey("aNull");
        assertThat(vars.get("aNull").value()).isNull();
        assertThat(vars.get("aNull").type()).isNull();
    }

    /* ---------------- runtime process scope ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void instanceVariableListKeepsTypeValueWidthAndTheBinaryValueUrl(String major) {
        Map<String, VariableRecord> vars = byName(read(major + "/variables-typed.json", VariableRecord[].class));

        assertThat(vars)
                .containsOnlyKeys(
                        "aLong",
                        "aSmallLong",
                        "anInteger",
                        "aDouble",
                        "aBoolean",
                        "aString",
                        "aDate",
                        "aNull",
                        "aJson",
                        "aBinary");
        assertTypedValues(vars);
        assertThat(vars.values()).extracting(VariableRecord::scope).containsOnly("local");
        // binary: no inline value, the engine points at the data resource instead
        assertThat(vars.get("aBinary").type()).isEqualTo("binary");
        assertThat(vars.get("aBinary").value()).isNull();
        assertThat(vars.get("aBinary").valueUrl()).endsWith("/variables/aBinary/data");
        assertThat(vars.get("aLong").valueUrl()).isNull(); // absent on every inline-valued row
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void singleInstanceVariableReadsHaveANullScope(String major) {
        VariableRecord aLong = read(major + "/variable-aLong.json", VariableRecord.class);
        assertThat(aLong.name()).isEqualTo("aLong");
        assertThat(aLong.type()).isEqualTo("long");
        assertThat(aLong.value()).isInstanceOf(Long.class).isEqualTo(BEYOND_DOUBLE);
        assertThat(aLong.scope()).isNull(); // explicit JSON null on the single read

        VariableRecord aDate = read(major + "/variable-aDate.json", VariableRecord.class);
        assertThat(aDate.type()).isEqualTo("date");
        assertThat(aDate.value()).isEqualTo("2026-01-02T03:04:05Z");

        VariableRecord aNull = read(major + "/variable-aNull.json", VariableRecord.class);
        assertThat(aNull.name()).isEqualTo("aNull"); // found — a null VALUE is not a missing variable
        assertThat(aNull.value()).isNull();
        assertThat(aNull.type()).isNull();

        VariableRecord aJson = read(major + "/variable-aJson.json", VariableRecord.class);
        assertThat(aJson.type()).isEqualTo("json");
        assertThat(aJson.value()).isEqualTo(expectedJsonDocument());

        VariableRecord aBinary = read(major + "/variable-aBinary.json", VariableRecord.class);
        assertThat(aBinary.type()).isEqualTo("binary");
        assertThat(aBinary.value()).isNull();
        assertThat(aBinary.valueUrl()).endsWith("/variables/aBinary/data");

        // the fixture the client tests already replay (#464)
        VariableRecord amount = read(major + "/variable.json", VariableRecord.class);
        assertThat(amount.name()).isEqualTo("amount");
        assertThat(amount.type()).isEqualTo("integer");
        assertThat(amount.value()).isEqualTo(100);
        assertThat(amount.scope()).isNull();
        assertThat(amount.valueUrl()).isNull();
    }

    /* ---------------- execution-local (step-local) scope ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void executionLocalVariablesAreLocalAndKeepTheLong(String major) {
        Map<String, VariableRecord> locals =
                byName(read(major + "/execution-local-variables-typed.json", VariableRecord[].class));

        assertThat(locals).containsOnlyKeys("stepLong", "stepNull");
        assertThat(locals.get("stepLong").type()).isEqualTo("long");
        assertThat(locals.get("stepLong").value()).isInstanceOf(Long.class).isEqualTo(BEYOND_DOUBLE);
        assertThat(locals.get("stepLong").scope()).isEqualTo("local");
        assertThat(locals.get("stepNull").value()).isNull();
        assertThat(locals.get("stepNull").type()).isNull();
        assertThat(locals.get("stepNull").scope()).isEqualTo("local");

        VariableRecord single = read(major + "/execution-local-variable.json", VariableRecord.class);
        assertThat(single.name()).isEqualTo("stepLong");
        assertThat(single.type()).isEqualTo("long");
        assertThat(single.value()).isInstanceOf(Long.class).isEqualTo(BEYOND_DOUBLE);
        assertThat(single.scope()).isEqualTo("local");

        // an execution without locals answers a bare empty array
        assertThat(read(major + "/execution-local-variables.json", VariableRecord[].class))
                .isEmpty();
    }

    /* ---------------- history ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void historicVariablePageNestsTheEnvelopeUnderVariable(String major) {
        HistoricVariablePage page = read(major + "/historic-variable-instances-typed.json", HistoricVariablePage.class);

        assertThat(page.total()).isEqualTo(12);
        assertThat(page.start()).isZero();
        assertThat(page.size()).isEqualTo(12);
        assertThat(page.dataOrEmpty()).hasSize(12);
        Map<String, HistoricVariableRecord> rows = page.dataOrEmpty().stream()
                .collect(Collectors.toMap(r -> r.variable().name(), Function.identity()));
        Map<String, VariableRecord> vars = new LinkedHashMap<>();
        rows.forEach((name, row) -> vars.put(name, row.variable()));
        assertTypedValues(vars);

        HistoricVariableRecord aLong = rows.get("aLong");
        assertThat(aLong.id()).isNotBlank();
        assertThat(aLong.processInstanceId()).isNotBlank();
        // a process-scope row: its execution IS the instance; never a task
        assertThat(aLong.executionId()).isEqualTo(aLong.processInstanceId());
        assertThat(aLong.taskId()).isNull();
        assertThat(aLong.variable().scope()).isEqualTo("global");

        // the execution-local variable is in history too — scope local, on its own execution
        HistoricVariableRecord stepLong = rows.get("stepLong");
        assertThat(stepLong.variable().scope()).isEqualTo("local");
        assertThat(stepLong.variable().value()).isInstanceOf(Long.class).isEqualTo(BEYOND_DOUBLE);
        assertThat(stepLong.executionId()).isNotEqualTo(stepLong.processInstanceId());
        assertThat(stepLong.processInstanceId()).isEqualTo(aLong.processInstanceId());

        // binary in history: still no value, the valueUrl points at the HISTORIC data resource
        assertThat(rows.get("aBinary").variable().type()).isEqualTo("binary");
        assertThat(rows.get("aBinary").variable().value()).isNull();
        assertThat(rows.get("aBinary").variable().valueUrl())
                .contains("/history/historic-variable-instances/")
                .endsWith("/data");
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void theSeedHistoricPageStillReads(String major) {
        HistoricVariablePage page = read(major + "/historic-variable-instances.json", HistoricVariablePage.class);

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.dataOrEmpty())
                .extracting(
                        r -> r.variable().name(),
                        r -> r.variable().type(),
                        r -> r.variable().value())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("amount", "integer", 100),
                        org.assertj.core.groups.Tuple.tuple("divisor", "integer", 0));
    }

    /* ---------------- CMMN case scope ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void caseVariablesShareTheEnvelopeAndHaveANullScope(String major) {
        Map<String, VariableRecord> vars = byName(read(major + "/cmmn-case-variables.json", VariableRecord[].class));

        assertThat(vars).hasSize(9);
        assertTypedValues(vars);
        assertThat(vars.values()).extracting(VariableRecord::scope).containsOnlyNulls();

        VariableRecord single = read(major + "/cmmn-case-variable.json", VariableRecord.class);
        assertThat(single.name()).isEqualTo("aLong");
        assertThat(single.type()).isEqualTo("long");
        assertThat(single.value()).isInstanceOf(Long.class).isEqualTo(BEYOND_DOUBLE);
    }

    /**
     * {@code GET /api/cases/…/variables} and {@code …/identitylinks} hand the engine rows to the SPA
     * as they are: the typed row must serialise back to EXACTLY the recorded document — no key
     * dropped, none added (a null variable has no {@code type} key, an inline one no
     * {@code valueUrl}), every number at its width.
     */
    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void caseRowsSerialiseBackToTheRecordedDocument(String major) throws IOException {
        VariableRecord[] vars = read(major + "/cmmn-case-variables.json", VariableRecord[].class);
        assertThat((JsonNode) STRICT.valueToTree(vars)).isEqualTo(tree(major + "/cmmn-case-variables.json"));

        VariableRecord[] withBinary = read(major + "/variables-typed.json", VariableRecord[].class);
        assertThat((JsonNode) STRICT.valueToTree(withBinary)).isEqualTo(tree(major + "/variables-typed.json"));

        CaseIdentityLinkRecord[] links = read(major + "/cmmn-case-identity-links.json", CaseIdentityLinkRecord[].class);
        assertThat((JsonNode) STRICT.valueToTree(links)).isEqualTo(tree(major + "/cmmn-case-identity-links.json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void caseIdentityLinksNameTheUserAndCarryTheirUrl(String major) {
        CaseIdentityLinkRecord[] links = read(major + "/cmmn-case-identity-links.json", CaseIdentityLinkRecord[].class);

        assertThat(links)
                .extracting(CaseIdentityLinkRecord::user, CaseIdentityLinkRecord::type)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("rest-admin", "starter"),
                        org.assertj.core.groups.Tuple.tuple("kermit", "participant"));
        for (CaseIdentityLinkRecord link : links) {
            assertThat(link.group()).isNull(); // explicit JSON null
            assertThat(link.url()).endsWith("/identitylinks/users/" + link.user() + "/" + link.type());
        }
    }

    /* ---------------- management documents ---------------- */

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void engineInfoCarriesNameAndVersion(String major) {
        EngineInfoRecord info = read(major + "/engine.json", EngineInfoRecord.class);

        assertThat(info.name()).isEqualTo("default");
        assertThat(info.version()).isEqualTo(major + ".0");
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.8", "7.1"})
    void managementTablesIsABareArrayOfNameAndCount(String major) {
        TableCountRecord[] tables = read(major + "/management-tables.json", TableCountRecord[].class);

        assertThat(tables).hasSizeGreaterThan(50);
        assertThat(tables).extracting(TableCountRecord::name).contains("ACT_RU_EXECUTION", "ACT_RU_DEADLETTER_JOB");
        assertThat(tables).allSatisfy(t -> {
            assertThat(t.name()).isNotBlank();
            assertThat(t.count()).isNotNull().isGreaterThanOrEqualTo(0L);
        });
    }
}

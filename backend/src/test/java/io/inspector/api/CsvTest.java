package io.inspector.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * R-OPS-08 CSV injection defenses (usability W3-1, R-AUD-08): engine/user text is data. A
 * leading {@code = + - @} would execute as a spreadsheet formula on open, so it gets a
 * literal {@code '} prefix; separators/quotes/newlines get RFC-4180 quoting on top.
 */
class CsvTest {

    @Test
    void plainTextPassesThrough() {
        assertThat(Csv.cell("retry-job")).isEqualTo("retry-job");
        assertThat(Csv.cell("k.meier")).isEqualTo("k.meier");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(Csv.cell(null)).isEmpty();
    }

    @Test
    void formulaLeadersAreNeutralizedWithAQuotePrefix() {
        assertThat(Csv.cell("=HYPERLINK(\"http://evil\",\"x\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
        assertThat(Csv.cell("+1234")).isEqualTo("'+1234");
        assertThat(Csv.cell("-cmd")).isEqualTo("'-cmd");
        assertThat(Csv.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
    }

    @Test
    void nonLeadingFormulaCharactersStayUntouched() {
        assertThat(Csv.cell("a=b")).isEqualTo("a=b");
    }

    @Test
    void whitespaceLeadersAreNeutralizedToo() {
        // OWASP lists leading tab/CR as formula triggers; a leading LF hides a formula on
        // the cell's second line from naive viewers (external review, Gemini W3-1).
        assertThat(Csv.cell("\t=1+1")).isEqualTo("'\t=1+1"); // tab needs no RFC quoting
        assertThat(Csv.cell("\r=1+1")).isEqualTo("\"'\r=1+1\"");
        assertThat(Csv.cell("\n=SUM(A1)")).isEqualTo("\"'\n=SUM(A1)\"");
    }

    @Test
    void rfc4180QuotingForSeparatorsQuotesAndNewlines() {
        assertThat(Csv.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(Csv.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(Csv.cell("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(Csv.cell("line1\rline2")).isEqualTo("\"line1\rline2\"");
    }
}

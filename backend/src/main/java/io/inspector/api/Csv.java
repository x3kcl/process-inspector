package io.inspector.api;

/**
 * Shared CSV cell encoding for the export endpoints (R-OPS-08: engine/user text is data).
 * Used by {@link AuditController} (operations-log export, R-AUD-08) and
 * {@link AccessReviewController}.
 */
final class Csv {

    private Csv() {}

    /**
     * Spreadsheet-formula neutralization + RFC-4180 escaping. A leading {@code = + - @}
     * would execute as a formula when the file is opened in Excel/Sheets, so it gets a
     * literal {@code '} prefix (the OWASP CSV-injection defense — which also lists leading
     * tab/CR as triggers, so whitespace leaders are prefixed too; external review, Gemini
     * W3-1); cells containing separators, quotes or newlines are then quoted with doubled
     * inner quotes.
     */
    static String cell(String value) {
        String s = value == null ? "" : value;
        if (!s.isEmpty()) {
            char first = s.charAt(0);
            if (first == '='
                    || first == '+'
                    || first == '-'
                    || first == '@'
                    || first == '\t'
                    || first == '\r'
                    || first == '\n') {
                s = "'" + s;
            }
        }
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}

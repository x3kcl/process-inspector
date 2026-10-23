package io.inspector.cmmn;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Active plan-item <em>definition</em> ids versus the target case model (issue #500). The runtime
 * row's {@code id} is the instance and {@code elementId} is the DI shape key — neither is the
 * definition id (Phase-2 join trap).
 */
public final class CasePlanDefinitionIds {

    // ponytail: definitionRef scan, not a CMMN parser. Ceiling: deployed XML with double-quoted
    // definitionRef (the harness files). Upgrade: walk GET .../case-definitions/{id}/model.
    private static final Pattern DEFINITION_REF = Pattern.compile("definitionRef\\s*=\\s*\"([^\"]+)\"");

    private static final Set<String> ENDED = Set.of("completed", "terminated", "occurred");

    private CasePlanDefinitionIds() {}

    public static Set<String> definitionIds(String cmmnXml) {
        Set<String> ids = new LinkedHashSet<>();
        if (cmmnXml == null || cmmnXml.isBlank()) {
            return ids;
        }
        Matcher matcher = DEFINITION_REF.matcher(cmmnXml);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /** Live {@code planItemDefinitionId} values. Blank when the row has none — never the instance id. */
    public static List<String> activeDefinitionIds(List<Map<String, Object>> rows) {
        List<String> ids = new ArrayList<>();
        if (rows == null) {
            return ids;
        }
        for (Map<String, Object> row : rows) {
            if (!live(row)) {
                continue;
            }
            Object def = row.get("planItemDefinitionId");
            ids.add(def == null ? "" : String.valueOf(def).trim());
        }
        return ids;
    }

    public static List<String> missing(List<String> activeDefinitionIds, Set<String> targetDefinitionIds) {
        List<String> absent = new ArrayList<>();
        for (String id : activeDefinitionIds) {
            if (id == null || id.isBlank() || !targetDefinitionIds.contains(id)) {
                absent.add(id == null || id.isBlank() ? "(missing planItemDefinitionId)" : id);
            }
        }
        return absent;
    }

    private static boolean live(Map<String, Object> row) {
        if (row.get("endedTime") != null) {
            return false;
        }
        Object state = row.get("state");
        return state == null || !ENDED.contains(String.valueOf(state).toLowerCase(Locale.ROOT));
    }
}

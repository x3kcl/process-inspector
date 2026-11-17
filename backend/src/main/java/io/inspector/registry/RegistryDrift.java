package io.inspector.registry;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.inspector.config.InspectorProperties.Auth;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The DB-vs-YAML drift report (docs/REGISTRY-CRUD.md §4). When the registry is DB-authoritative
 * and non-empty but {@code inspector.engines} YAML is ALSO present, the YAML is ignored — but never
 * silently: an operator who edits {@code prod.yaml} expecting effect must see the no-op. This pure
 * comparison feeds the boot-time per-engine drift log and (S4) the {@code GET …/drift} badge.
 *
 * <p>Blocking startup on drift was rejected — it would fail every deploy once the table is seeded.
 * DB stays authoritative; drift is reported, not enforced.
 */
public final class RegistryDrift {

    private RegistryDrift() {}

    /**
     * @param added   ids present in YAML but not the DB (a YAML add that had no effect)
     * @param removed ids present in the DB but not YAML (a YAML delete that had no effect)
     * @param changed ids in both whose material config diverges (base-URL / environment / mode /
     *                enabled) — a YAML edit that had no effect
     */
    public record DriftReport(
            List<String> added,
            List<String> removed,
            List<String> changed,
            @JsonIgnore Map<String, List<String>> fields) {
        public DriftReport(List<String> added, List<String> removed, List<String> changed) {
            this(added, removed, changed, Map.of());
        }

        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        /**
         * A single-line summary for the boot WARN. Changed engines list each differing field with
         * its YAML and stored value; credential refs print {@code <differs>} only.
         */
        public String summary() {
            List<String> shown = new ArrayList<>();
            for (String id : changed) {
                List<String> f = fields.get(id);
                shown.add(f == null ? id : id + f);
            }
            return "engine registry drift (DB is authoritative; these YAML entries are ignored): added=" + added
                    + " removed=" + removed + " changed=" + shown;
        }
    }

    public static DriftReport compute(List<EngineConfig> yaml, List<EngineConfig> db) {
        Map<String, EngineConfig> yamlById = byId(yaml);
        Map<String, EngineConfig> dbById = byId(db);

        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        Map<String, List<String>> fields = new LinkedHashMap<>();
        for (var e : yamlById.entrySet()) {
            EngineConfig dbCfg = dbById.get(e.getKey());
            if (dbCfg == null) {
                added.add(e.getKey());
            } else {
                List<String> diffs = differingFields(e.getValue(), dbCfg);
                if (!diffs.isEmpty()) {
                    changed.add(e.getKey());
                    fields.put(e.getKey(), diffs);
                }
            }
        }

        List<String> removed = new ArrayList<>();
        for (String id : dbById.keySet()) {
            if (!yamlById.containsKey(id)) {
                removed.add(id);
            }
        }
        return new DriftReport(added, removed, changed, fields);
    }

    /** Material = what actually changes behaviour; timeouts/caps are intentionally not "drift". */
    private static List<String> differingFields(EngineConfig yaml, EngineConfig db) {
        List<String> out = new ArrayList<>();
        diff(out, "baseUrl", yaml.baseUrl(), db.baseUrl(), true);
        diff(out, "environment", yaml.environment(), db.environment(), true);
        diff(out, "mode", yaml.modeOrDefault(), db.modeOrDefault(), true);
        diff(out, "enabled", yaml.enabled(), db.enabled(), true);
        Auth y = yaml.auth();
        Auth d = db.auth();
        diff(out, "authType", y == null ? null : y.type(), d == null ? null : d.type(), true);
        diff(out, "authUsername", y == null ? null : y.username(), d == null ? null : d.username(), true);
        diff(out, "authPasswordRef", y == null ? null : y.passwordRef(), d == null ? null : d.passwordRef(), true);
        diff(out, "authTokenRef", y == null ? null : y.tokenRef(), d == null ? null : d.tokenRef(), false);
        diff(
                out,
                "authOauthClientSecretRef",
                y == null ? null : y.oauthClientSecretRef(),
                d == null ? null : d.oauthClientSecretRef(),
                false);
        diff(
                out,
                "authHeaderSecretRef",
                y == null ? null : y.headerSecretRef(),
                d == null ? null : d.headerSecretRef(),
                false);
        return out;
    }

    private static void diff(List<String> out, String field, Object yaml, Object db, boolean show) {
        if (!Objects.equals(yaml, db)) {
            out.add(field + (show ? ": yaml=" + yaml + " db=" + db : "=<differs>"));
        }
    }

    private static Map<String, EngineConfig> byId(List<EngineConfig> engines) {
        Map<String, EngineConfig> map = new LinkedHashMap<>();
        for (EngineConfig e : engines) {
            map.put(e.id(), e);
        }
        return map;
    }
}

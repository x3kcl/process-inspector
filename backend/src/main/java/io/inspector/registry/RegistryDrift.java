package io.inspector.registry;

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
    public record DriftReport(List<String> added, List<String> removed, List<String> changed) {
        public boolean isEmpty() {
            return added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        }

        /** A single-line, id-only summary for the boot WARN — never dumps config values. */
        public String summary() {
            return "engine registry drift (DB is authoritative; these YAML entries are ignored): added=" + added
                    + " removed=" + removed + " changed=" + changed;
        }
    }

    public static DriftReport compute(List<EngineConfig> yaml, List<EngineConfig> db) {
        Map<String, EngineConfig> yamlById = byId(yaml);
        Map<String, EngineConfig> dbById = byId(db);

        List<String> added = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (var e : yamlById.entrySet()) {
            EngineConfig dbCfg = dbById.get(e.getKey());
            if (dbCfg == null) {
                added.add(e.getKey());
            } else if (materiallyDiffers(e.getValue(), dbCfg)) {
                changed.add(e.getKey());
            }
        }

        List<String> removed = new ArrayList<>();
        for (String id : dbById.keySet()) {
            if (!yamlById.containsKey(id)) {
                removed.add(id);
            }
        }
        return new DriftReport(added, removed, changed);
    }

    /** Material = what actually changes behaviour; timeouts/caps are intentionally not "drift". */
    private static boolean materiallyDiffers(EngineConfig yaml, EngineConfig db) {
        return !Objects.equals(yaml.baseUrl(), db.baseUrl())
                || yaml.environment() != db.environment()
                || yaml.modeOrDefault() != db.modeOrDefault()
                || yaml.enabled() != db.enabled();
    }

    private static Map<String, EngineConfig> byId(List<EngineConfig> engines) {
        Map<String, EngineConfig> map = new LinkedHashMap<>();
        for (EngineConfig e : engines) {
            map.put(e.id(), e);
        }
        return map;
    }
}

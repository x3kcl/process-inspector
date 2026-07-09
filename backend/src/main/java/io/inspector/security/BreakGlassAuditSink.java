package io.inspector.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The break-glass audit fallback (IDP-SECURITY.md §7, ⚠️ sixth-seat) — the ONE deliberate exception
 * to fail-closed audit. A DB outage may be <i>concurrent</i> with the IdP outage that break-glass
 * exists for; blocking break-glass then would be a total lockout in the exact scenario it's for. So
 * when Postgres is unreachable, a break-glass event is appended to a local <b>tamper-evident,
 * append-only</b> file sink (each line hash-chained to the previous), write-success gates the action,
 * and it's loudly flagged for reconciliation into {@code audit_entry} on recovery. Normal-path
 * break-glass still writes to Postgres fail-closed; this is only the degraded path.
 */
@Component
public class BreakGlassAuditSink {

    private static final Logger log = LoggerFactory.getLogger(BreakGlassAuditSink.class);
    private static final String GENESIS = "break-glass-genesis";

    private final Path sinkFile;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Object lock = new Object();

    public BreakGlassAuditSink(BreakGlassProperties props, ObjectMapper mapper, Clock clock) {
        this.sinkFile = Path.of(props.sinkFileOrDefault());
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * Append a hash-chained line. Returns true on durable write (the action may proceed); false if
     * even the file sink is unwritable (then break-glass truly cannot proceed — nothing is unaudited).
     */
    public boolean append(String actor, String event, Map<String, Object> payload) {
        synchronized (lock) {
            try {
                String prevHash = lastHash();
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("ts", clock.instant().toString());
                row.put("actor", actor);
                row.put("event", event);
                row.put("payload", payload);
                row.put("prevHash", prevHash);
                String body = mapper.writeValueAsString(row);
                String hash = sha256(prevHash + body);
                String line = "{\"hash\":\"" + hash + "\",\"row\":" + body + "}\n";
                if (sinkFile.getParent() != null) {
                    Files.createDirectories(sinkFile.getParent());
                }
                Files.writeString(
                        sinkFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                log.warn(
                        "BREAK_GLASS_FILE_SINK actor={} event={} — Postgres audit was unavailable; wrote to the "
                                + "tamper-evident file sink {} (reconcile into audit_entry on recovery)",
                        actor,
                        event,
                        sinkFile);
                return true;
            } catch (IOException | RuntimeException e) {
                log.error("BREAK_GLASS_FILE_SINK_UNWRITABLE actor={} event={} — {}", actor, event, e.toString());
                return false;
            }
        }
    }

    private String lastHash() throws IOException {
        if (!Files.exists(sinkFile)) {
            return GENESIS;
        }
        var lines = Files.readAllLines(sinkFile, StandardCharsets.UTF_8);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String l = lines.get(i).strip();
            if (!l.isEmpty()) {
                var node = mapper.readTree(l);
                return node.has("hash") ? node.get("hash").asText() : GENESIS;
            }
        }
        return GENESIS;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

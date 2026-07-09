package io.inspector.api;

import io.inspector.client.FlowableEngineClient;
import io.inspector.config.InspectorProperties;
import io.inspector.config.InspectorProperties.EngineConfig;
import io.inspector.config.InspectorProperties.EngineEnvironment;
import io.inspector.config.RegistryProperties;
import io.inspector.registry.EngineRegistryMapper;
import io.inspector.registry.EngineRegistryRow;
import io.inspector.registry.EngineRegistryStore;
import io.inspector.registry.RegistryDrift.DriftReport;
import io.inspector.registry.RegistryUrlValidator;
import io.inspector.registry.RegistryWrite;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The runtime engine-registry admin surface (docs/REGISTRY-CRUD.md §9, R-SAFE-13) — the
 * highest-privilege surface in the tool. EVERY route is gated
 * {@code @PreAuthorize("@rbac.canAdministerRegistry(authentication)")} at the door AND the store
 * re-checks fail-closed audit + SSRF validation in the service. Distinct from the secret-free,
 * enabled-only {@code GET /api/engines}: this lists draft/disabled/tombstoned rows too and shows
 * secret-ref PRESENCE (never values).
 *
 * <p>Trust is earned: add → DRAFT (read-only), probe (read-only) → PROBED, enable → ACTIVE. A
 * prod enable-read-write and a remove require a typed token (the engine id) — the four-eyes
 * (R-SAFE-08 dual-control) layer is a separate follow-up, noted in the PR.
 */
@RestController
@RequestMapping("/api/admin/engines")
@PreAuthorize("@rbac.canAdministerRegistry(authentication)")
public class AdminEnginesController {

    private final EngineRegistryStore store;
    private final InspectorProperties inspectorProperties;
    private final RegistryProperties registryProperties;
    private final RegistryUrlValidator urlValidator;
    private final FlowableEngineClient flowable;
    private final Environment env;

    public AdminEnginesController(
            EngineRegistryStore store,
            InspectorProperties inspectorProperties,
            RegistryProperties registryProperties,
            RegistryUrlValidator urlValidator,
            FlowableEngineClient flowable,
            Environment env) {
        this.store = store;
        this.inspectorProperties = inspectorProperties;
        this.registryProperties = registryProperties;
        this.urlValidator = urlValidator;
        this.flowable = flowable;
        this.env = env;
    }

    @GetMapping
    public List<AdminEngineDto> list() {
        return store.findAllForAdmin().stream().map(this::toDto).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AdminEngineDto add(@Valid @RequestBody EngineWriteRequest body, Authentication authentication) {
        assertNotConfigPinned();
        return toDto(store.add(body.toWrite(body.id()), authentication, body.reason()));
    }

    @PutMapping("/{id}")
    public AdminEngineDto edit(
            @PathVariable String id, @Valid @RequestBody EngineWriteRequest body, Authentication authentication) {
        assertNotConfigPinned();
        return toDto(store.edit(id, body.toWrite(id), authentication, body.reason()));
    }

    /** Run the READ-ONLY probe now (version/reachability). Failures are coarse to the UI (oracle). */
    @PostMapping("/{id}/probe")
    public AdminEngineDto probe(@PathVariable String id, Authentication authentication) {
        assertNotConfigPinned();
        EngineRegistryRow row = store.requireRow(id);

        // SSRF re-validation at the DIAL point (Gemini S4 review): add/edit validate at WRITE, but the
        // probe is a live dial surface, so re-validate here — it RE-RESOLVES, so a base-URL that was
        // validated at add but has since rebound to an internal address is rejected before we dial.
        // (Socket-level pinning of the pinned IP for the health-loop / operation dials is S4b.)
        EngineEnvironment environment =
                EngineEnvironment.valueOf(row.getEnvironment().toUpperCase(Locale.ROOT));
        if (!urlValidator
                .validate(row.getBaseUrl(), environment, registryProperties.egressPolicy())
                .isAllowed()) {
            // Coarse to the UI (no oracle); the rejecting rail is in the store's log + audit.
            return toDto(store.recordProbe(id, false, authentication, "ssrf re-validation failed at probe"));
        }

        boolean ok;
        String detail;
        try {
            var info = flowable.engineInfo(engine(row));
            ok = info != null;
            detail = "version=" + (info != null ? info.get("version") : "?");
        } catch (RuntimeException e) {
            ok = false;
            detail = e.toString(); // audit only — never surfaced (topology oracle)
        }
        return toDto(store.recordProbe(id, ok, authentication, detail));
    }

    private static EngineConfig engine(EngineRegistryRow row) {
        return EngineRegistryMapper.toEngineConfig(row);
    }

    @PostMapping("/{id}/enable")
    public AdminEngineDto enable(
            @PathVariable String id, @Valid @RequestBody EnableRequest body, Authentication authentication) {
        assertNotConfigPinned();
        EngineRegistryRow row = store.requireRow(id);
        boolean readWrite = body.readWrite() != null && body.readWrite();
        // Prod read-write enable is the dangerous flip: typed token = the engine id (four-eyes TBD).
        if (readWrite && "prod".equals(row.getEnvironment())) {
            requireTypedToken(id, body.confirmToken());
        }
        return toDto(store.enable(id, readWrite, authentication, body.reason()));
    }

    @PostMapping("/{id}/disable")
    public AdminEngineDto disable(
            @PathVariable String id, @Valid @RequestBody ReasonRequest body, Authentication authentication) {
        assertNotConfigPinned();
        return toDto(store.disable(id, authentication, body.reason()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(
            @PathVariable String id, @Valid @RequestBody ConfirmRequest body, Authentication authentication) {
        assertNotConfigPinned();
        requireTypedToken(id, body.confirmToken()); // remove always requires the typed id
        store.remove(id, authentication, body.reason());
    }

    @PostMapping("/{id}/purge")
    public void purge(@PathVariable String id, @Valid @RequestBody ConfirmRequest body, Authentication authentication) {
        assertNotConfigPinned();
        requireTypedToken(id, body.confirmToken());
        store.purge(id, authentication, body.reason());
    }

    /** DB-vs-YAML drift (the admin badge / access-review). REGISTRY_ADMIN. */
    @GetMapping("/drift")
    public DriftReport drift() {
        return store.driftReport(inspectorProperties.engines());
    }

    /* ---------------- guards ---------------- */

    private void assertNotConfigPinned() {
        if (registryProperties.isConfigPinned()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "registry is config-pinned (inspector.registry.source=config) — CRUD is disabled");
        }
    }

    private void requireTypedToken(String id, String confirmToken) {
        if (!id.equals(confirmToken)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "type the engine id (\"" + id + "\") to confirm this action. Nothing happened.");
        }
    }

    private AdminEngineDto toDto(EngineRegistryRow row) {
        return new AdminEngineDto(
                row.getId(),
                row.getName(),
                row.getBaseUrl(),
                row.getEnvironment(),
                row.getMode(),
                row.getLifecycle(),
                row.getAccentColor(),
                row.getTenantId(),
                row.getTelemetryUrlTemplate(),
                row.getAuthType(),
                row.getAuthUsername(),
                row.getPasswordRef(),
                present(row.getPasswordRef()),
                row.getTokenRef(),
                present(row.getTokenRef()),
                row.getConnectMs(),
                row.getReadMs(),
                row.getWriteMs(),
                row.getMaxPageSize(),
                row.getDlqScanCap(),
                row.getSource(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getRemovedAt());
    }

    /** Secret-ref PRESENCE — is the named env var set in THIS deployment? Never the value. */
    private boolean present(String ref) {
        return ref != null && !ref.isBlank() && env.getProperty(ref) != null;
    }

    /* ---------------- DTOs ---------------- */

    public record AdminEngineDto(
            String id,
            String name,
            String baseUrl,
            String environment,
            String mode,
            String lifecycle,
            String accentColor,
            String tenantId,
            String telemetryUrlTemplate,
            String authType,
            String authUsername,
            String passwordRef,
            boolean passwordRefPresent,
            String tokenRef,
            boolean tokenRefPresent,
            Integer connectMs,
            Integer readMs,
            Integer writeMs,
            Integer maxPageSize,
            Integer dlqScanCap,
            String source,
            Instant createdAt,
            Instant updatedAt,
            Instant removedAt) {}

    public record EngineWriteRequest(
            String id, // POST only (immutable); ignored on PUT
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 2000) String baseUrl,
            EngineEnvironment environment,
            String accentColor,
            String tenantId,
            String telemetryUrlTemplate,
            String authType,
            String authUsername,
            String passwordRef,
            String tokenRef,
            Integer connectMs,
            Integer readMs,
            Integer writeMs,
            Integer maxPageSize,
            Integer dlqScanCap,
            Integer alarmOldestWarnMin,
            Integer alarmOldestCritMin,
            Integer alarmOverdueGraceS,
            @NotBlank @Size(min = 10, max = 500) String reason) {

        RegistryWrite toWrite(String effectiveId) {
            return new RegistryWrite(
                    effectiveId,
                    name,
                    baseUrl,
                    environment,
                    accentColor,
                    tenantId,
                    telemetryUrlTemplate,
                    authType,
                    authUsername,
                    passwordRef,
                    tokenRef,
                    connectMs,
                    readMs,
                    writeMs,
                    maxPageSize,
                    dlqScanCap,
                    alarmOldestWarnMin,
                    alarmOldestCritMin,
                    alarmOverdueGraceS);
        }
    }

    public record ReasonRequest(
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record EnableRequest(
            Boolean readWrite,
            String confirmToken,
            @NotBlank @Size(min = 10, max = 500) String reason) {}

    public record ConfirmRequest(
            String confirmToken,
            @NotBlank @Size(min = 10, max = 500) String reason) {}
}

package io.inspector.bulk;

import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.inspector.action.ActionVerb;
import io.inspector.action.GuardRefusedException;
import io.inspector.audit.AuditEntry;
import io.inspector.audit.ProtectedDefinition;
import io.inspector.audit.ProtectedInstance;
import io.inspector.client.FlowablePage;
import io.inspector.client.GuardedCaller.CallPriority;
import io.inspector.config.InspectorProperties.EngineConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.client.ResourceAccessException;

/** Submit path (protected-key exclusion included). Moved from {@link BulkJobService} (issue #475). */
final class BulkJobSubmitter {

    private final BulkJobService host;
    private final BulkJobRunner runner;

    BulkJobSubmitter(BulkJobService host, BulkJobRunner runner) {
        this.host = host;
        this.runner = runner;
    }

    BulkDtos.BulkJobDto submit(
            BulkDtos.BulkSubmitRequest request,
            Authentication auth,
            Map<String, Object> extraEnvelopePayload,
            int itemCap,
            BulkJob.ScopeKind scopeKind,
            String scopeLabel,
            Set<ActionVerb> allowedVerbs,
            String verbRefusalMessage) {

        ActionVerb verb = ActionVerb.fromPath(request.verb() != null ? request.verb() : "")
                .filter(allowedVerbs::contains)
                .orElseThrow(() ->
                        new GuardRefusedException(HttpStatus.BAD_REQUEST, "bulk-verb-not-allowed", verbRefusalMessage));
        List<BulkDtos.BulkTarget> targets = request.items() != null ? request.items() : List.of();
        if (targets.isEmpty()) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "bulk-empty", "The selection is empty — nothing to do.");
        }
        if (targets.size() > itemCap) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST,
                    "bulk-cap-exceeded",
                    "Bulk is capped at " + itemCap + " items (got " + targets.size()
                            + "). Narrow the selection. Nothing happened.");
        }
        // Reason is mandatory on EVERY submit door (usability fix C-back) — unified with the
        // error-class and filter siblings, which never allowed the optional escape.
        if (request.reason() == null
                || request.reason().isBlank()
                || request.reason().trim().length() < 10) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "reason-too-short", "The reason must be at least 10 characters.");
        }
        String reason = request.reason().trim();
        boolean taskVerb = verb == ActionVerb.REASSIGN_TASK || verb == ActionVerb.UNASSIGN_TASK;
        if (verb == ActionVerb.REASSIGN_TASK
                && (request.assignee() == null || request.assignee().isBlank())) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_REQUEST, "assignee-required", "Bulk reassign needs an assignee. Nothing happened.");
        }
        for (BulkDtos.BulkTarget target : targets) {
            host.registry.require(target.engineId()); // unknown engine refuses the WHOLE submit pre-flight
            if (target.instanceId() == null || target.instanceId().isBlank()) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST, "bulk-target-invalid", "Every item needs an instanceId.");
            }
            if (taskVerb && (target.jobId() == null || target.jobId().isBlank())) {
                throw new GuardRefusedException(
                        HttpStatus.BAD_REQUEST,
                        "bulk-target-invalid",
                        "Every task item needs a task id. Nothing happened.");
            }
        }

        // Protected auto-exclusion (R-SAFE-05): settled as skipped_protected at submit —
        // visible in the report, never silently dropped.
        Set<ProtectedInstance.Key> guarded = protectedKeys(targets);

        UUID jobId = UUID.randomUUID();
        // The envelope audit row (SPEC §7: one per item + ONE for the envelope) — the
        // fail-closed gate for the whole submission (audit down ⇒ 503, nothing recorded
        // ⇒ nothing dispatched).
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "bulk/" + verb.path() + "/v1");
        payload.put("bulkJobId", jobId.toString());
        payload.put("items", targets.size());
        payload.put(
                "targets",
                targets.stream().map(t -> t.engineId() + ":" + t.instanceId()).toList());
        if (request.continuedFrom() != null) {
            payload.put("continuedFrom", request.continuedFrom().toString());
        }
        payload.putAll(extraEnvelopePayload);
        String engineIds =
                targets.stream().map(BulkDtos.BulkTarget::engineId).distinct().collect(Collectors.joining(","));
        AuditEntry envelope = host.audit.beginPending(
                auth.getName(), engineIds, null, null, "bulk:" + verb.path(), reason, request.ticketId(), payload);

        // Scope provenance (usability fix E1): SELECTION derives its own label from the
        // ticked count; ERROR_CLASS/FILTER bring their own from the door that resolved them.
        String label = scopeLabel != null
                ? scopeLabel
                : targets.size() + " ticked instance" + (targets.size() == 1 ? "" : "s");
        BulkJob job = new BulkJob(
                jobId,
                auth.getName(),
                host.clock.instant(),
                verb.path(),
                reason,
                request.ticketId(),
                targets.size(),
                request.continuedFrom(),
                scopeKind,
                label);
        if (taskVerb && request.assignee() != null) {
            job.setAssignee(request.assignee().strip());
        }
        host.jobs.saveAndFlush(job);
        List<BulkJobItem> itemRows = new ArrayList<>();
        int ordinal = 0;
        for (BulkDtos.BulkTarget target : targets) {
            BulkJobItem item = new BulkJobItem(
                    jobId,
                    ordinal++,
                    target.engineId(),
                    target.instanceId(),
                    target.jobId(),
                    BulkJobItem.State.pending);
            if (guarded.contains(new ProtectedInstance.Key(target.engineId(), target.instanceId()))) {
                item.settle(
                        BulkJobItem.State.skipped_protected,
                        "protected instance (R-SAFE-05) — auto-excluded from bulk",
                        null,
                        host.clock.instant());
            }
            itemRows.add(item);
        }
        host.items.saveAllAndFlush(itemRows);

        // #303 defect 2: a plain executor.submit(...) Future was discarded — an exception that
        // somehow still escaped run()'s own try/catch/finally below (a raw Error, not a
        // RuntimeException) would vanish silently, exactly like the bug this whole fix addresses.
        // CompletableFuture.runAsync's .exceptionally is the same idiom already used for each
        // per-engine dispatch group below — never discarded again.
        CompletableFuture.runAsync(() -> runner.run(jobId, envelope, auth), host.executor)
                .exceptionally(ex -> {
                    host.log.error("bulk job {} run() thread escaped uncaught — settling INTERRUPTED", jobId, ex);
                    host.settleAfterRunFailure(jobId);
                    return null;
                });
        host.events.publishEvent(new BulkJobChangedEvent(jobId));
        return BulkDtos.BulkJobDto.of(job, itemRows, true);
    }

    private Set<ProtectedInstance.Key> protectedKeys(List<BulkDtos.BulkTarget> targets) {
        List<ProtectedInstance.Key> keys = targets.stream()
                .map(t -> new ProtectedInstance.Key(t.engineId(), t.instanceId()))
                .toList();
        Set<ProtectedInstance.Key> guarded = new HashSet<>(host.protectedInstances.findAllById(keys).stream()
                .map(p -> new ProtectedInstance.Key(p.getEngineId(), p.getInstanceId()))
                .collect(Collectors.toSet()));
        guarded.addAll(definitionProtectedKeys(targets));
        return guarded;
    }

    /**
     * #184: definition-key scope for bulk's pre-dispatch auto-exclusion (the "same dual-scope
     * check" the issue asked for). {@code BulkTarget} carries no definitionKey — a client-supplied
     * one can never be trusted for a security check — so it's resolved SERVER-SIDE, grouped per
     * engine, via the same batched {@code processInstanceIds} + canary-detect + per-id-fallback
     * pattern {@code SearchService#suspendedFlags} already proved (some engines, e.g. 6.3.1,
     * silently ignore the filter and return everything unfiltered). Returns synthetic
     * {@code ProtectedInstance.Key}s (composite ID, not a real row) so the caller's existing
     * {@code guarded.contains(...)} per-item check needs no changes for this second scope.
     */
    private Set<ProtectedInstance.Key> definitionProtectedKeys(List<BulkDtos.BulkTarget> targets) {
        Set<ProtectedInstance.Key> guarded = new HashSet<>();
        Map<String, List<BulkDtos.BulkTarget>> byEngine =
                targets.stream().collect(Collectors.groupingBy(BulkDtos.BulkTarget::engineId));
        for (Map.Entry<String, List<BulkDtos.BulkTarget>> entry : byEngine.entrySet()) {
            EngineConfig engine = host.registry.require(entry.getKey());
            Map<String, String> definitionKeyByInstance = definitionKeysOf(engine, entry.getValue());
            Set<String> distinctKeys = new HashSet<>(definitionKeyByInstance.values());
            distinctKeys.remove(null);
            if (distinctKeys.isEmpty()) continue;
            List<ProtectedDefinition.Key> defKeys = distinctKeys.stream()
                    .map(k -> new ProtectedDefinition.Key(entry.getKey(), k))
                    .toList();
            Set<String> protectedDefKeys = host.protectedDefinitions.findAllById(defKeys).stream()
                    .map(ProtectedDefinition::getDefinitionKey)
                    .collect(Collectors.toSet());
            if (protectedDefKeys.isEmpty()) continue;
            for (BulkDtos.BulkTarget t : entry.getValue()) {
                String key = definitionKeyByInstance.get(t.instanceId());
                if (key != null && protectedDefKeys.contains(key)) {
                    guarded.add(new ProtectedInstance.Key(t.engineId(), t.instanceId()));
                }
            }
        }
        return guarded;
    }

    /**
     * {@code instanceId -> definitionKey} for a batch on ONE engine. Fail-closed on engine
     * unreachability (same "engine-unreachable" refusal every other pre-flight engine read in
     * this codebase uses) — a bulk submit that cannot verify definition-scope protection refuses
     * the WHOLE submit rather than silently skipping the check.
     */
    private Map<String, String> definitionKeysOf(EngineConfig engine, List<BulkDtos.BulkTarget> targets) {
        Set<String> ids = targets.stream().map(BulkDtos.BulkTarget::instanceId).collect(Collectors.toSet());
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("processInstanceIds", List.copyOf(ids));
            body.put("size", ids.size());
            FlowablePage page = host.client.queryRuntimeProcessInstances(engine, CallPriority.INTERACTIVE, body);
            Map<String, String> byId = new HashMap<>();
            boolean filterIgnored = page == null || page.total() > ids.size();
            if (page != null) {
                for (Map<String, Object> pi : page.dataOrEmpty()) {
                    String id = String.valueOf(pi.get("id"));
                    if (!ids.contains(id)) {
                        filterIgnored = true;
                        break;
                    }
                    byId.put(id, definitionKeyOf(pi));
                }
            }
            if (!filterIgnored) return byId;

            host.log.debug("Engine {} ignored processInstanceIds on the runtime query — per-id fallback", engine.id());
            // ConcurrentHashMap rejects null values — definitionKeyOf legitimately returns null
            // (e.g. a mock/test double, or a genuinely definitionId-less response), so the key
            // must be resolved and checked BEFORE the put, never put-then-null.
            Map<String, String> fallback = new ConcurrentHashMap<>();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(CompletableFuture.runAsync(
                        () -> {
                            Map<String, Object> pi =
                                    host.client.getRuntimeProcessInstance(engine, CallPriority.INTERACTIVE, id);
                            String key = pi != null ? definitionKeyOf(pi) : null;
                            if (key != null) fallback.put(id, key);
                        },
                        host.executor));
            }
            try {
                CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                        .join();
            } catch (CompletionException e) {
                // join() always wraps the per-task exception — unwrap it so the outer catch below
                // still recognizes CallNotPermittedException/BulkheadFullException/ResourceAccessException
                // (a raw parallelStream().forEach() doesn't reliably wrap, which would let these same
                // exception types silently escape the intended fail-closed engine-unreachable refusal).
                if (e.getCause() instanceof RuntimeException cause) throw cause;
                throw e;
            }
            return fallback;
        } catch (CallNotPermittedException | BulkheadFullException | ResourceAccessException e) {
            throw new GuardRefusedException(
                    HttpStatus.BAD_GATEWAY,
                    "engine-unreachable",
                    "Engine '" + engine.id() + "' did not answer the pre-flight definition-protection check."
                            + " Nothing happened.");
        }
    }

    private static String definitionKeyOf(Map<String, Object> instance) {
        Object definitionId = instance.get("processDefinitionId");
        if (definitionId == null) return null;
        String s = String.valueOf(definitionId);
        int idx = s.indexOf(':');
        return idx > 0 ? s.substring(0, idx) : s;
    }
}

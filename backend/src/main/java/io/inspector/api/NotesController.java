package io.inspector.api;

import io.inspector.audit.InstanceNote;
import io.inspector.audit.InstanceNoteRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator notes per composite ID (SPEC §9) — the handover channel. Reading is VIEWER+;
 * writing is RESPONDER+ (notes are explicitly part of the L1/L2 runbook tier, R-SAFE-01).
 */
@RestController
@RequestMapping("/api/instances/{engineId}/{instanceId}/notes")
public class NotesController {

    private final InstanceNoteRepository repository;
    private final Clock clock;

    public NotesController(InstanceNoteRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("@rbac.atLeastOn(authentication, 'VIEWER', #engineId)")
    public List<NoteDto> list(@PathVariable String engineId, @PathVariable String instanceId) {
        return repository.findByEngineIdAndInstanceIdOrderByTsDesc(engineId, instanceId).stream()
                .map(NoteDto::from)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@rbac.atLeastOn(authentication, 'RESPONDER', #engineId)")
    public NoteDto create(
            @PathVariable String engineId,
            @PathVariable String instanceId,
            @Valid @RequestBody CreateNote body,
            Authentication authentication) {
        InstanceNote note = new InstanceNote(
                engineId,
                instanceId,
                authentication.getName(),
                clock.instant(),
                body.body().trim());
        return NoteDto.from(repository.save(note));
    }

    public record CreateNote(@NotBlank @Size(max = 10_000) String body) {}

    public record NoteDto(Long id, String engineId, String instanceId, String author, Instant ts, String body) {

        static NoteDto from(InstanceNote note) {
            return new NoteDto(
                    note.getId(),
                    note.getEngineId(),
                    note.getInstanceId(),
                    note.getAuthor(),
                    note.getTs(),
                    note.getBody());
        }
    }
}

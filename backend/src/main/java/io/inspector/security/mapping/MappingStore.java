package io.inspector.security.mapping;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional writes to the group→scope mapping store ({@code @Profile("db")}). S3 provides the
 * emptiness check + the one-shot file-seed import; S4 adds the audited CRUD (grant add/edit/remove)
 * on top. Kept a distinct bean from the boot seeder so the {@code @Transactional} proxy actually
 * applies (a self-invoked transactional method would not).
 */
@Service
@Profile("db")
public class MappingStore {

    private final GroupScopeGrantRepository ladderRepo;
    private final GroupFleetGrantRepository fleetRepo;

    public MappingStore(GroupScopeGrantRepository ladderRepo, GroupFleetGrantRepository fleetRepo) {
        this.ladderRepo = ladderRepo;
        this.fleetRepo = fleetRepo;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() {
        return ladderRepo.count() == 0 && fleetRepo.count() == 0;
    }

    /** One-shot seed import — both tables written in ONE transaction (all-or-nothing). */
    @Transactional
    public void seed(List<GroupScopeGrantEntity> ladder, List<GroupFleetGrantEntity> fleet) {
        ladderRepo.saveAll(ladder);
        fleetRepo.saveAll(fleet);
    }

    @Transactional(readOnly = true)
    public Optional<GroupScopeGrantEntity> ladderById(long id) {
        return ladderRepo.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<GroupFleetGrantEntity> fleetById(long id) {
        return fleetRepo.findById(id);
    }

    /** Apply one grant change (add/remove, ladder/fleet). Duplicate add / missing remove throws. */
    @Transactional
    public void apply(GrantChange change) {
        Instant now = Instant.now();
        switch (change.kind()) {
            case LADDER_ADD -> {
                ladderRepo
                        .findByGroupNameAndRoleAndEngineIdAndTenantId(
                                change.group(), change.role().name(), change.engineId(), change.tenantId())
                        .ifPresent(existing -> {
                            throw new IllegalArgumentException("grant already exists: " + change.summary());
                        });
                ladderRepo.save(new GroupScopeGrantEntity(
                        change.group(), change.role().name(), change.engineId(), change.tenantId(), "ui", now));
            }
            case FLEET_ADD -> {
                fleetRepo
                        .findByGroupNameAndGrantKind(
                                change.group(), change.fleetGrant().name())
                        .ifPresent(existing -> {
                            throw new IllegalArgumentException("fleet grant already exists: " + change.summary());
                        });
                fleetRepo.save(new GroupFleetGrantEntity(change.group(), change.fleetGrant(), "ui", now));
            }
            case LADDER_REMOVE -> {
                GroupScopeGrantEntity row = ladderRepo
                        .findByGroupNameAndRoleAndEngineIdAndTenantId(
                                change.group(), change.role().name(), change.engineId(), change.tenantId())
                        .orElseThrow(() -> new IllegalArgumentException("no such grant: " + change.summary()));
                ladderRepo.delete(row);
            }
            case FLEET_REMOVE -> {
                GroupFleetGrantEntity row = fleetRepo
                        .findByGroupNameAndGrantKind(
                                change.group(), change.fleetGrant().name())
                        .orElseThrow(() -> new IllegalArgumentException("no such fleet grant: " + change.summary()));
                fleetRepo.delete(row);
            }
        }
    }
}

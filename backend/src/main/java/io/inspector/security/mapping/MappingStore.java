package io.inspector.security.mapping;

import java.util.List;
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
}

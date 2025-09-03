package shrey.exchange.infra.adapter;

import shrey.exchange.infra.EntityManagerContextHolder;
import shrey.exchange.infra.adapter.entities.SnapshotEntity;
import shrey.exchange.infra.adapter.entities.SnapshotType;
import shrey.exchange.offset.SnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * @author shrey
 * @since 2024
 */
@Component
@RequiredArgsConstructor
public class SnapshotRepositoryAdapter implements SnapshotRepository {

    private final SnapshotRepositoryJpa snapshotRepositoryJpa;

    @Override
    public Long getLastOffset() {
        return snapshotRepositoryJpa.findById(SnapshotType.LAST_KAFKA_OFFSET.getType())
            .map(SnapshotEntity::getValue)
            .map(Long::parseLong)
            .orElse(-1L);
    }

    @Override
    public void persistLastOffset(long offset) {
        var entityManager = EntityManagerContextHolder.CONTEXT.get();
        entityManager.createQuery("update SnapshotEntity s set s.value = :value where s.id = :id")
            .setParameter("value", String.valueOf(offset))
            .setParameter("id", SnapshotType.LAST_KAFKA_OFFSET.getType())
            .executeUpdate();
    }
}

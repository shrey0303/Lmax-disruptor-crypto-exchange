package shrey.exchange.infra.adapter;

import shrey.exchange.infra.adapter.entities.SnapshotEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * @author shrey
 * @since 2024
 */
public interface SnapshotRepositoryJpa extends JpaRepository<SnapshotEntity, String> {
}

package com.finsight.infra.audit;

import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

/**
 * Repository for audit log entries.
 * Only save() is used — never delete() or update operations.
 * The append-only constraint is enforced at the DB level too.
 */
@Repository
@Profile("!mock")
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, UUID> {
}
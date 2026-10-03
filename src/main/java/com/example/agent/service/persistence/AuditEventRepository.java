package com.example.agent.service.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    List<AuditEventEntity> findBySessionIdOrderByTimestampAsc(String sessionId);

    List<AuditEventEntity> findByUserIdOrderByTimestampDesc(String userId, Pageable pageable);

    List<AuditEventEntity> findByEventTypeOrderByTimestampDesc(String eventType);
}

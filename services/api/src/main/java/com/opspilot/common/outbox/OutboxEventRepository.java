package com.opspilot.common.outbox;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

  long countByAggregateTypeAndAggregateId(String aggregateType, UUID aggregateId);
}

package com.conveyor.order.outbox;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRecordRepository extends JpaRepository<OutboxRecord, UUID> {}

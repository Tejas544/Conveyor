package com.conveyor.dispatch.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxRecordRepository extends JpaRepository<InboxRecord, InboxRecordId> {}

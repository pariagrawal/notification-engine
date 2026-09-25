package com.paridhi.notificationengine.repository;

import com.paridhi.notificationengine.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Claims a batch of pending events for this poller instance.
     *
     * <p>{@code for update skip locked} is what makes the poller safe to run on every
     * application node: concurrent pollers step over each other's locked rows instead of
     * blocking on them or publishing the same event twice.
     */
    @Query(value = """
            select * from outbox_event
            where status = 'PENDING'
            order by created_at
            limit :limit
            for update skip locked
            """, nativeQuery = true)
    List<OutboxEvent> claimPendingBatch(@Param("limit") int limit);

    long countByStatus(com.paridhi.notificationengine.domain.OutboxStatus status);
}

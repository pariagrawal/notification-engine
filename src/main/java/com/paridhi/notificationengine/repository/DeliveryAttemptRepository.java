package com.paridhi.notificationengine.repository;

import com.paridhi.notificationengine.domain.DeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeliveryAttemptRepository extends JpaRepository<DeliveryAttempt, Long> {

    List<DeliveryAttempt> findByNotificationIdOrderByAttemptNoAsc(UUID notificationId);
}

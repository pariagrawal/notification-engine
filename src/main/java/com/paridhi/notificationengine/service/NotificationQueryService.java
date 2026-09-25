package com.paridhi.notificationengine.service;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.DeliveryAttempt;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import com.paridhi.notificationengine.exception.NotFoundException;
import com.paridhi.notificationengine.repository.DeliveryAttemptRepository;
import com.paridhi.notificationengine.repository.NotificationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of the notification API. */
@Service
@Transactional(readOnly = true)
public class NotificationQueryService {

    private final NotificationRepository notifications;
    private final DeliveryAttemptRepository attempts;

    public NotificationQueryService(NotificationRepository notifications, DeliveryAttemptRepository attempts) {
        this.notifications = notifications;
        this.attempts = attempts;
    }

    public Notification require(UUID id) {
        return notifications.findById(id)
                .orElseThrow(() -> new NotFoundException("no notification with id " + id));
    }

    public Page<Notification> search(String userId, Channel channel, NotificationStatus status, Pageable pageable) {
        return notifications.findAll(NotificationRepository.matching(userId, channel, status), pageable);
    }

    public List<DeliveryAttempt> attemptsFor(UUID notificationId) {
        require(notificationId);
        return attempts.findByNotificationIdOrderByAttemptNoAsc(notificationId);
    }
}

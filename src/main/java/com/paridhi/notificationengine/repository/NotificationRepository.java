package com.paridhi.notificationengine.repository;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.Notification;
import com.paridhi.notificationengine.domain.NotificationStatus;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface NotificationRepository
        extends JpaRepository<Notification, UUID>, JpaSpecificationExecutor<Notification> {

    Optional<Notification> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(NotificationStatus status);

    /**
     * Filter on whichever of the three criteria were supplied. Built as a specification
     * rather than a JPQL query with {@code :param is null} guards, which force Hibernate
     * to infer a type for an untyped null enum parameter.
     */
    static Specification<Notification> matching(String userId, Channel channel, NotificationStatus status) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (userId != null) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            if (channel != null) {
                predicates.add(cb.equal(root.get("channel"), channel));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }
}

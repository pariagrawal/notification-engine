package com.paridhi.notificationengine.repository;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.NotificationTemplate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, Long> {

    Optional<NotificationTemplate> findByCodeAndChannelAndLocale(String code, Channel channel, String locale);

    List<NotificationTemplate> findByCode(String code);
}

package com.paridhi.notificationengine.repository;

import com.paridhi.notificationengine.domain.Channel;
import com.paridhi.notificationengine.domain.UserPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserPreferenceRepository extends JpaRepository<UserPreference, Long> {

    Optional<UserPreference> findByUserIdAndChannel(String userId, Channel channel);

    List<UserPreference> findByUserId(String userId);
}

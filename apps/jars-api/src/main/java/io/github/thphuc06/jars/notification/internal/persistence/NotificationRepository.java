package io.github.thphuc06.jars.notification.internal.persistence;

import io.github.thphuc06.jars.notification.internal.model.Notification;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Query("""
            select n from Notification n
            where n.userId = :userId and (:unreadOnly = false or n.readAt is null)
            order by n.createdAt desc, n.id desc
            """)
    List<Notification> firstPage(@Param("userId") UUID userId, @Param("unreadOnly") boolean unreadOnly, Limit limit);

    @Query("""
            select n from Notification n
            where n.userId = :userId and (:unreadOnly = false or n.readAt is null)
              and (n.createdAt < :at or (n.createdAt = :at and n.id < :id))
            order by n.createdAt desc, n.id desc
            """)
    List<Notification> pageAfter(
            @Param("userId") UUID userId,
            @Param("unreadOnly") boolean unreadOnly,
            @Param("at") Instant at,
            @Param("id") UUID id,
            Limit limit);

    /** Notifications created after the one with id {@code after}, oldest first (UUIDv7 ids sort by time). */
    List<Notification> findByUserIdAndIdGreaterThanOrderByIdAsc(UUID userId, UUID after, Limit limit);
}

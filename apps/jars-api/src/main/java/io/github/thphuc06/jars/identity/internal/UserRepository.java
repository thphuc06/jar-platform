package io.github.thphuc06.jars.identity.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByCognitoSub(String cognitoSub);

    /**
     * Race-safe "create if missing": when two first requests of the same user arrive together, both run this
     * statement but only one row is inserted; the other waits for the first transaction and then does nothing.
     *
     * <p>No conflict target on purpose. {@code ON CONFLICT (cognito_sub)} absorbs conflicts on that index ONLY: a
     * concurrent insert that happened to block on the e-mail index then failed with a unique violation, and the user
     * was told their own e-mail belonged to another account (found in P1.8, 19 of 25 repeated races failed). Without
     * a target, a conflict on ANY unique index means "do nothing", and the caller decides by reading the row.
     *
     * @return 1 if this call inserted the row, 0 if a row with this sub OR this e-mail already existed
     */
    @Modifying
    @Query(value = """
                    INSERT INTO users (id, cognito_sub, email, locale)
                    VALUES (:id, :cognitoSub, :email, :locale)
                    ON CONFLICT DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("cognitoSub") String cognitoSub,
            @Param("email") String email,
            @Param("locale") String locale);
}

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
     * @return 1 if this call inserted the row, 0 if it already existed
     */
    @Modifying
    @Query(value = """
                    INSERT INTO users (id, cognito_sub, email, locale)
                    VALUES (:id, :cognitoSub, :email, :locale)
                    ON CONFLICT (cognito_sub) DO NOTHING
                    """, nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("cognitoSub") String cognitoSub,
            @Param("email") String email,
            @Param("locale") String locale);
}

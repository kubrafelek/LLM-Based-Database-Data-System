package com.llmdiscovery.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByUsername(String username);

    /** The user's current token_version; empty when the user does not exist (any token is then invalid). */
    @Query("select u.tokenVersion from User u where u.username = :username")
    Optional<Integer> findTokenVersion(@Param("username") String username);

    /** Invalidate every token issued to this user so far. */
    @Transactional
    @Modifying
    @Query("update User u set u.tokenVersion = u.tokenVersion + 1 where u.username = :username")
    int bumpTokenVersion(@Param("username") String username);
}

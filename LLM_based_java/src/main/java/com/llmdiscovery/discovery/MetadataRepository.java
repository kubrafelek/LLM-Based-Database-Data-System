package com.llmdiscovery.discovery;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MetadataRepository extends JpaRepository<DbMetadata, UUID> {

    /** Tables and columns are loaded with one subselect each (see @Fetch on the collections). */
    @Query("select m from DbMetadata m join fetch m.connection where m.id = :id")
    Optional<DbMetadata> findWithConnection(@Param("id") UUID id);

    /** Delete a snapshot. Tables, columns and cached results go via ON DELETE CASCADE; the connection is kept. */
    @Modifying
    @Query("delete from DbMetadata m where m.id = :id")
    int deleteSnapshot(@Param("id") UUID id);

    @Query("select c from DbColumn c join fetch c.table t join fetch t.metadata m join fetch m.connection where c.id = :id")
    Optional<DbColumn> findColumnContext(@Param("id") UUID columnId);
}

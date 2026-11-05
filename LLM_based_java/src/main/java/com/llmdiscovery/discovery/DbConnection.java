package com.llmdiscovery.discovery;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/** A target database. The password is stored encrypted; one row per host/port/db/user (see ConnectionRepository). */
@Entity
@Table(name = "db_connections")
public class DbConnection {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "database_name", nullable = false)
    private String databaseName;

    @Column(nullable = false)
    private String username;

    @Column(name = "encrypted_password", nullable = false)
    private String encryptedPassword;

    protected DbConnection() {}

    public UUID getId() { return id; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getDatabaseName() { return databaseName; }
    public String getUsername() { return username; }
    public String getEncryptedPassword() { return encryptedPassword; }
}

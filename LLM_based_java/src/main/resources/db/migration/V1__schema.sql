-- System database schema. Identical to the Python service after Alembic 0001-0003,
-- so either service can run against the same database.

CREATE TABLE users (
    id uuid PRIMARY KEY,
    username varchar(150) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- A target database. The password is stored encrypted; one row per host/port/db/user.
CREATE TABLE db_connections (
    id uuid PRIMARY KEY,
    host varchar(255) NOT NULL,
    port integer NOT NULL,
    database_name varchar(255) NOT NULL,
    username varchar(255) NOT NULL,
    encrypted_password text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_db_connections_target UNIQUE (host, port, database_name, username)
);

-- One metadata extraction snapshot. RESTRICT: a connection only goes away once no snapshot uses it.
CREATE TABLE db_metadata (
    id uuid PRIMARY KEY,
    database_name varchar(255) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    connection_id uuid NOT NULL,
    schema_name varchar(255),
    table_count integer NOT NULL,
    CONSTRAINT fk_db_metadata_connection_id FOREIGN KEY (connection_id)
        REFERENCES db_connections (id) ON DELETE RESTRICT
);
CREATE INDEX ix_db_metadata_connection_id ON db_metadata (connection_id);
CREATE INDEX ix_db_metadata_created_at ON db_metadata (created_at);

CREATE TABLE db_tables (
    id uuid PRIMARY KEY,
    metadata_id uuid NOT NULL REFERENCES db_metadata (id) ON DELETE CASCADE,
    schema_name varchar(255) NOT NULL,
    table_name varchar(255) NOT NULL,
    table_type varchar(32) NOT NULL,
    UNIQUE (metadata_id, schema_name, table_name)
);
CREATE INDEX ix_db_tables_metadata_id ON db_tables (metadata_id);

CREATE TABLE db_columns (
    id uuid PRIMARY KEY,
    table_id uuid NOT NULL REFERENCES db_tables (id) ON DELETE CASCADE,
    column_name varchar(255) NOT NULL,
    data_type varchar(255) NOT NULL,
    is_nullable boolean NOT NULL,
    ordinal_position integer NOT NULL
);
CREATE INDEX ix_db_columns_table_id ON db_columns (table_id);

-- Cached /classify responses; a new sample set, model, prompt or setting gives a new cache_key.
CREATE TABLE classification_results (
    id uuid PRIMARY KEY,
    column_id uuid NOT NULL REFERENCES db_columns (id) ON DELETE CASCADE,
    cache_key varchar(64) NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_classification_results_key UNIQUE (column_id, cache_key)
);
CREATE INDEX ix_classification_results_column_id ON classification_results (column_id);

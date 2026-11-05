#!/bin/sh
# Least-privilege login for the discovery service: SELECT only, read-only by default.
# Runs after 01_demo_database.sql on first start. Idempotent, so it can also be applied to an
# existing volume:  docker compose exec demo-db sh /docker-entrypoint-initdb.d/02_readonly_user.sh
set -e

if [ -z "$DEMO_DB_READONLY_USER" ] || [ -z "$DEMO_DB_READONLY_PASSWORD" ]; then
  echo "02_readonly_user.sh: DEMO_DB_READONLY_USER / DEMO_DB_READONLY_PASSWORD not set, skipping"
  exit 0
fi

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v reader="$DEMO_DB_READONLY_USER" -v reader_password="$DEMO_DB_READONLY_PASSWORD" \
  -v dbname="$POSTGRES_DB" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'reader')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'reader') \gexec
ALTER ROLE :"reader" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD :'reader_password';
ALTER ROLE :"reader" SET default_transaction_read_only = on;
GRANT CONNECT ON DATABASE :"dbname" TO :"reader";
GRANT USAGE ON SCHEMA public TO :"reader";
GRANT SELECT ON ALL TABLES IN SCHEMA public TO :"reader";
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO :"reader";
SQL

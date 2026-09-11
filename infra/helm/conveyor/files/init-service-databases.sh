#!/bin/sh
# Runs once, on first container init, via /docker-entrypoint-initdb.d/.
# Creates one role + one database per service (ARCHITECTURE.md §4:
# "one Postgres instance, one database + role per service" — logical
# isolation, not physical database-per-service, for cost reasons).
set -eu

VERIFIER_USER="${VERIFIER_DB_USER:-conveyor_verifier}"
VERIFIER_PASSWORD="${VERIFIER_DB_PASSWORD:-verifier_local_dev_only}"

ensure_verifier_role() {
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${VERIFIER_USER}') THEN
        CREATE ROLE "${VERIFIER_USER}" LOGIN PASSWORD '${VERIFIER_PASSWORD}';
      END IF;
    END
    \$\$;
EOSQL
}

create_service_db() {
  db_name="$1"
  db_user="$2"
  db_password="$3"

  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" <<-EOSQL
    DO \$\$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = '${db_user}') THEN
        CREATE ROLE "${db_user}" LOGIN PASSWORD '${db_password}';
      END IF;
    END
    \$\$;
EOSQL

  if ! psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -lqt | cut -d '|' -f 1 | grep -qw "${db_name}"; then
    createdb --username "$POSTGRES_USER" --owner "${db_user}" "${db_name}"
  fi

  # ARCHITECTURE.md §12: conveyor-verifier gets a distinct SELECT-only role
  # across all schemas — explicit and revocable, never the owning service's
  # own credentials. The default-privileges rule means tables Flyway creates
  # *later*, owned by db_user, automatically grant the verifier SELECT
  # without a second migration step.
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" -d "${db_name}" <<-EOSQL
    GRANT CONNECT ON DATABASE "${db_name}" TO "${VERIFIER_USER}";
    GRANT USAGE ON SCHEMA public TO "${VERIFIER_USER}";
    ALTER DEFAULT PRIVILEGES FOR ROLE "${db_user}" IN SCHEMA public GRANT SELECT ON TABLES TO "${VERIFIER_USER}";
EOSQL
}

ensure_verifier_role

create_service_db "$ORDER_DB_NAME" "$ORDER_DB_USER" "$ORDER_DB_PASSWORD"
create_service_db "$INVENTORY_DB_NAME" "$INVENTORY_DB_USER" "$INVENTORY_DB_PASSWORD"
create_service_db "$PAYMENT_DB_NAME" "$PAYMENT_DB_USER" "$PAYMENT_DB_PASSWORD"
create_service_db "$SAGA_DB_NAME" "$SAGA_DB_USER" "$SAGA_DB_PASSWORD"
create_service_db "$DISPATCH_DB_NAME" "$DISPATCH_DB_USER" "$DISPATCH_DB_PASSWORD"

#!/bin/sh
# Runs once, the first time the Postgres volume is created.
#
# Least privilege: every service gets its own login and its own schema, so a bug
# (or a SQL injection) in one service cannot read or change another service's data.
#
#   core_svc  -> owns schema "core" in database hrms   (Flyway migrations from core-service)
#   ai_svc    -> owns schema "ai"   in database hrms   (document chunks + embeddings)
#   keycloak  -> owns database keycloak
set -eu

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname "$POSTGRES_DB" \
     -v core_pw="$CORE_DB_PASSWORD" \
     -v ai_pw="$AI_DB_PASSWORD" \
     -v kc_pw="$KEYCLOAK_DB_PASSWORD" <<'EOSQL'
-- :'name' makes psql quote the value safely as a SQL literal
CREATE ROLE core_svc LOGIN PASSWORD :'core_pw';
CREATE ROLE ai_svc   LOGIN PASSWORD :'ai_pw';
CREATE ROLE keycloak LOGIN PASSWORD :'kc_pw';

CREATE DATABASE keycloak OWNER keycloak;
REVOKE CONNECT ON DATABASE keycloak FROM PUBLIC;

REVOKE CONNECT ON DATABASE hrms FROM PUBLIC;
GRANT  CONNECT ON DATABASE hrms TO core_svc, ai_svc;

CREATE SCHEMA core AUTHORIZATION core_svc;
CREATE SCHEMA ai   AUTHORIZATION ai_svc;

-- pgvector needs superuser to install; the ai_svc role only uses it
CREATE EXTENSION IF NOT EXISTS vector;

ALTER ROLE core_svc SET search_path = core;
ALTER ROLE ai_svc   SET search_path = ai, public;
EOSQL

#!/bin/sh
set -e

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    SELECT 'CREATE DATABASE grupoasd_test_db'
    WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'grupoasd_test_db')\gexec
EOSQL

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "grupoasd_test_db" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS grupoasd_dev;
    CREATE SCHEMA IF NOT EXISTS grupoasd_prod;
EOSQL

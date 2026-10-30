-- =========================================================================
-- Script de inicialización de esquemas para Grupo ASD
-- Crea explícitamente los esquemas grupoasd_dev (origen) y grupoasd_prod (destino)
-- Hibernate se encarga de generar automáticamente las tablas dentro de cada esquema
-- =========================================================================

CREATE SCHEMA IF NOT EXISTS grupoasd_dev;
CREATE SCHEMA IF NOT EXISTS grupoasd_prod;

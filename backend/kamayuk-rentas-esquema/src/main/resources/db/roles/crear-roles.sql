-- ============================================================================
--  SGTM — Roles de base de datos (ARQ-03 §4)
--
--  NO es una migracion de Flyway. Se ejecuta ANTES de la primera migracion, con
--  una conexion de superusuario, porque:
--    - las politicas RLS de V6 nombran roles y estos deben existir;
--    - sgtm_owner necesita CREATE sobre el esquema para poder migrar;
--    - un rol no puede crearse a si mismo.
--
--  Idempotente: se puede volver a ejecutar sobre una base ya provisionada.
--
--  Las CLAVES NO ESTAN AQUI. Los roles se crean sin LOGIN; quien provisiona el
--  ambiente asigna la clave con `ALTER ROLE ... LOGIN PASSWORD ...` desde su
--  gestor de secretos. La prueba de aislamiento hace lo mismo con claves
--  generadas al vuelo.
--
--  NOSUPERUSER y NOBYPASSRLS son explicitos y no decorativos: un superusuario
--  omite RLS incluso con FORCE ROW LEVEL SECURITY (DAT-01 §0, hallazgo 1).
-- ============================================================================

DO $roles$
DECLARE
    r text;
BEGIN
    -- `rol_ingestor_catastro` entra con P5C: es quien escribe la proyeccion local de
    -- `catastro` (V4), y existe SEPARADO de `sgtm_app` a proposito. Que la proyeccion sea de
    -- solo lectura para la aplicacion no puede ser disciplina del repositorio: es un privilegio,
    -- la misma mecanica de `rol_carga_parametros` con los valores normativos.
    FOREACH r IN ARRAY ARRAY['sgtm_owner', 'sgtm_app', 'sgtm_readonly', 'rol_carga_parametros',
                             'rol_ingestor_catastro']
    LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = r) THEN
            EXECUTE format('CREATE ROLE %I NOLOGIN', r);
        END IF;
        EXECUTE format(
            'ALTER ROLE %I NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION', r);
    END LOOP;
END
$roles$;

-- Solo sgtm_owner hace DDL. La aplicacion nunca.
GRANT USAGE, CREATE ON SCHEMA public TO sgtm_owner;
GRANT USAGE           ON SCHEMA public TO sgtm_app, sgtm_readonly, rol_carga_parametros,
                                                rol_ingestor_catastro;

-- Sin GRANT de pertenencia entre roles: sgtm_owner concede privilegios sobre sus
-- propias tablas sin necesitarla, y ser miembro de sgtm_app le permitiria un
-- SET ROLE que borra la separacion.

-- ---------- Extensiones ----------
-- Van aqui por el mismo motivo que los roles: sgtm_owner no puede instalarlas
-- —no tiene CREATE sobre la base y no queremos darselo—, y la migracion que las
-- usa necesita que ya existan. Instalar una extension es provisionar el ambiente,
-- no versionar el esquema.
--
--   pg_trgm   busqueda de contribuyentes por aproximacion de nombre (RF-014).
--             Sin ella, un nombre mal escrito en ventanilla no encuentra a nadie
--             y se da de alta al mismo contribuyente por segunda vez.
--   unaccent  para que «PEÑA» y «PENA» sean el mismo nombre.
--
-- Las dos son trusted desde PostgreSQL 13, asi que en un ambiente donde
-- sgtm_owner sea dueño de la base tampoco harian falta privilegios especiales.
--
-- SON DOS Y NO CUATRO DESDE P5E, Y ESO SE MIDIO
-- ---------------------------------------------
-- Hasta P5E aqui se creaban tambien `postgis` y `btree_gist`, y `rentas` NO
-- NECESITA NINGUNA DE LAS DOS: la geometria del predio y las dos restricciones
-- de exclusion de vigencias son de `catastro`, y `V6` retira sus tablas.
--
-- Lo que las mantenia vivas era el baseline: `V1` las creaba para dejarlas caer
-- cinco migraciones despues. Medido sobre una base con las dos extensiones
-- fuera, `V1` moria en su linea 1610 con «type "geography" does not exist» —el
-- mismo modo de fallo exacto que P5D encontro en `caja` con `unaccent`, y el que
-- dejo `stg` cuatro dias sin desplegar con `V61` (#742)—. Y medido sobre el
-- esquema final: ni una columna de tipo PostGIS, ni un indice GiST, ni una
-- restriccion `EXCLUDE`.
--
-- P5E quito de `V1` esas nueve lineas —cinco columnas del predio, dos indices y
-- las dos exclusiones—, todas sobre tablas que `V6` borra y que nunca llegan a
-- tener una fila. El esquema resultante es el mismo, comprobado con `pg_dump`:
-- 12 164 lineas identicas a las 12 164 de antes.
--
-- Lo que compra: la base de `rentas` se levanta con dos extensiones *trusted* en
-- vez de con una que exige superusuario. Una ventanilla cuya base necesita
-- PostGIS no se levanta en cualquier sitio, y aqui no lo necesita.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS unaccent;

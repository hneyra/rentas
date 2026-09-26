-- ============================================================================
--  V42 — EL ACTA SE REGISTRA UNA VEZ: `Idempotency-Key` (#347)
--
--  QUE PASABA
--  ----------
--  La version de un acta es `max + 1` sobre su unidad, y una version 2 es LEGITIMA: es
--  como se corrige un area mal medida (V19). Pero nada distinguia una correccion de un
--  reintento: el `POST` que agotaba su espera en el cliente y se reenviaba dejaba una
--  «version 2» —una reinspeccion que nunca ocurrio—, cada una liquidable por su lado.
--
--  QUE CAMBIA
--  ----------
--  La clave del cliente viaja en la cabecera `Idempotency-Key`, como en certificados
--  (V51), anuncios y convenios, y se guarda con el acta. El reintento con la misma clave
--  devuelve el acta de la primera vez (200) en vez de escribir otra. La GARANTIA es este
--  indice, no la lectura que el caso de uso hace antes: entre las dos cabe otra peticion.
--
--  Nula para las actas historicas y para el cliente que no la mande. `kamayuk_app` tiene
--  `INSERT` sobre la tabla entera y `UPDATE` solo sobre `estado` (V19): la columna se
--  escribe al insertar y no pide tocar ningun privilegio.
-- ============================================================================

ALTER TABLE acta_fiscalizacion ADD COLUMN clave_idempotencia varchar(64);

CREATE UNIQUE INDEX acta_fisc_idempotencia_uq
    ON acta_fiscalizacion (municipalidad_id, clave_idempotencia)
    WHERE clave_idempotencia IS NOT NULL;

COMMENT ON COLUMN acta_fiscalizacion.clave_idempotencia IS
    'La cabecera Idempotency-Key con que se registro el acta (#347). Reenviar la misma peticion '
    'devuelve esta acta y no una version nueva: una version 2 es una correccion, no un reintento.';

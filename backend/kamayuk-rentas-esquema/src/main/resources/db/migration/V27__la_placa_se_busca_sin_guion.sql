-- ============================================================================
--  V27 — LA PLACA SE BUSCA SIN GUION, Y LA BUSQUEDA LLEGA AL INDICE (#423)
--
--  QUE PASABA
--  ----------
--  `Placa` —el objeto de valor de `dominio-compartido`— compara dos placas SIN
--  su guion: «ZLG-701» y «ZLG701» son el mismo vehiculo, y `nucleo` lo busca
--  asi. `sanciones` no: sus cinco consultas por placa —el internamiento vigente,
--  la grilla del deposito, el padron de papeletas que consulta la constancia
--  libre, la busqueda de papeletas y la grilla de constancias— comparaban el
--  texto crudo, `p.placa = :placa`. Medido en #423, por la API: se internaba dos
--  veces el mismo vehiculo, se contestaba 404 a la liberacion de uno que si
--  estaba internado, y se emitia con 201 una constancia de «no tiene papeletas
--  pendientes» sobre un vehiculo que tenia una.
--
--  Desde #423 el parametro sale de `Placa.formaDeBusqueda` —recortado, en
--  mayusculas, sin espacios y sin guion—. Esta migracion pone la MISMA forma del
--  lado de la columna.
--
--  QUE HACE
--  --------
--  En `papeleta`, `internamiento` y `constancia_libre`:
--
--    * una columna GENERADA, `placa_busqueda`, con la placa en mayusculas y sin
--      espacios ni guion. `placa` no cambia: el guion se conserva porque es lo
--      que el papel imprime (`Placa`), y ninguna fila se reescribe a mano;
--    * un indice `(municipalidad_id, placa_busqueda)`;
--    * y retira el indice sobre la columna cruda que la consulta ya no usa
--      —`papeleta_placa_ix`, `internamiento_placa_ix`,
--      `constancia_libre_placa_ix`, los de V1—. `papeleta_placa_prefijo_ix` NO
--      se toca: la busqueda por prefijo sigue siendo sobre la columna cruda, y
--      ese indice es el que la sirve.
--
--  Los espacios se quitan aunque `Placa` ya no los admita porque `Papeleta`,
--  `Internamiento` y `ConstanciaLibre` guardan la placa recortada pero CON sus
--  espacios interiores, y una placa cargada como «ZLG 701» es el mismo vehiculo.
--  En `vehiculo` basta quitar el guion porque ahi solo entra lo que `Placa` ya
--  limpio.
--
--  POR QUE UNA COLUMNA GENERADA Y NO UN INDICE SOBRE LA EXPRESION
--  --------------------------------------------------------------
--  Es el QUINTO hallazgo de DAT-01 §0 otra vez, y se midio antes de escribir
--  nada. `replace()` tiene `proleakproof = f` (leido de `pg_proc` en PostgreSQL
--  16.4), asi que bajo RLS PostgreSQL no puede evaluar
--  `replace(replace(placa, ' ', ''), '-', '') = $1` antes de la politica, y no la
--  admite como condicion de ningun indice — ni siquiera de uno construido sobre
--  esa misma expresion. La igualdad sobre una columna (`texteq`) SI es
--  leakproof. Medido como un rol sin privilegios, con RLS forzada y la misma
--  politica que estas tablas, sobre 30 000 filas por municipalidad en dos
--  municipalidades, `ANALYZE` hecho, buscando una placa que existe:
--
--    forma de preguntar                        plan               bloques  descartadas
--    ----------------------------------------  -----------------  -------  -----------
--    placa = 'BAA-001'  (la cruda de hasta     Index Scan               4            0
--      ahora: rapida, pero no encuentra
--      «BAA001»)
--    replace(...) = 'BAA001', CON un indice    Bitmap Heap Scan     1 342       29 999
--      sobre esa misma expresion               (solo la politica
--                                               en el Index Cond;
--                                               la expresion, al
--                                               Filter)
--    placa_busqueda = 'BAA001'  (ESTA)         Index Scan               4            0
--
--  La fila de en medio es la que el javadoc de `VehiculoRepositoryJdbc`
--  promete que no pasa, y es la de `nucleo` hoy: se anota en el PR de #423
--  para su propio issue, porque #423 deja fuera el `vehiculo` de `nucleo`.
--  Es la misma salida que `sgtm` #565 tomo para el nombre (una columna generada
--  saca la FUNCION no leakproof de la condicion), y la que V13 recuerda. Marcar
--  `replace` como LEAKPROOF se descarta por lo mismo que alli: es un acto de
--  superusuario y afirmaria algo de una funcion del nucleo que usa medio
--  sistema.
--
--  LO QUE CUESTA
--  -------------
--  Una columna `text` de hasta 10 caracteres por fila, y un indice del mismo
--  tamano que el que se retira (1 872 kB sobre 60 000 filas en la medida de
--  arriba, los dos). `ADD COLUMN ... GENERATED ... STORED` REESCRIBE la tabla y
--  la bloquea mientras tanto: es el precio de hacerlo en una migracion, que
--  Flyway corre en transaccion.
--
--  POR QUE ESTO NO ES DESTRUCTIVO
--  ------------------------------
--  No se borra ni se corrige ni una fila (regla 4, RNF-051): la columna nueva se
--  DERIVA de la que ya estaba, y ninguna escritura de la aplicacion la nombra
--  —una columna generada no admite que se escriba—. Los indices no son datos.
--  Y `kamayuk_app` y `kamayuk_readonly` la leen sin permiso nuevo: los `GRANT
--  SELECT` de estas tres tablas son sobre la tabla, no por columna.
-- ============================================================================

ALTER TABLE papeleta
    ADD COLUMN placa_busqueda text
        GENERATED ALWAYS AS (upper(replace(replace(placa::text, ' ', ''), '-', ''))) STORED;

ALTER TABLE internamiento
    ADD COLUMN placa_busqueda text
        GENERATED ALWAYS AS (upper(replace(replace(placa::text, ' ', ''), '-', ''))) STORED;

ALTER TABLE constancia_libre
    ADD COLUMN placa_busqueda text
        GENERATED ALWAYS AS (upper(replace(replace(placa::text, ' ', ''), '-', ''))) STORED;

DROP INDEX papeleta_placa_ix;
DROP INDEX internamiento_placa_ix;
DROP INDEX constancia_libre_placa_ix;

CREATE INDEX papeleta_placa_busqueda_ix
    ON papeleta (municipalidad_id, placa_busqueda) WHERE placa_busqueda IS NOT NULL;
CREATE INDEX internamiento_placa_busqueda_ix
    ON internamiento (municipalidad_id, placa_busqueda);
CREATE INDEX constancia_libre_placa_busqueda_ix
    ON constancia_libre (municipalidad_id, placa_busqueda);

COMMENT ON COLUMN papeleta.placa_busqueda IS
    'La placa en mayusculas, sin espacios ni guion: la forma con que se compara (#423), la misma '
    'que Placa.formaDeBusqueda. Generada para que la igualdad llegue al indice bajo RLS: '
    'replace() no es leakproof. La columna placa conserva el guion, que es lo que se imprime.';
COMMENT ON COLUMN internamiento.placa_busqueda IS
    'La placa en mayusculas, sin espacios ni guion: la forma con que se compara (#423). Ver '
    'papeleta.placa_busqueda.';
COMMENT ON COLUMN constancia_libre.placa_busqueda IS
    'La placa en mayusculas, sin espacios ni guion: la forma con que se compara (#423). Ver '
    'papeleta.placa_busqueda.';

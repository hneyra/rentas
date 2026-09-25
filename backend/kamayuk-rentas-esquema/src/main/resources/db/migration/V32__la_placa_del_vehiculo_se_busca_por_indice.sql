-- ============================================================================
--  V32 — LA PLACA DEL VEHICULO SE BUSCA POR INDICE (#511)
--
--  QUE PASABA
--  ----------
--  `VehiculoRepositoryJdbc.findByPlaca` —la busqueda de ventanilla, la de la
--  ficha, la del calculo vehicular por placa, la de la importacion de
--  transferencias— y el filtro de placa de la busqueda del padron comparaban
--  `replace(placa, '-', '') = :placa`, y el unico de V1, `vehiculo_placa_uq`,
--  estaba construido sobre esa misma expresion. El javadoc del repositorio
--  prometia que por eso «el planificador puede usarlo». Bajo RLS no puede:
--  `replace()` tiene `proleakproof = f`, PostgreSQL no evalua la condicion
--  antes de la politica y no la lleva a ningun indice —ni al de su misma
--  expresion—. Es el quinto hallazgo de DAT-01 §0, el que #423 midio en
--  `sanciones` y V27 resolvio alli.
--
--  Medido en #511 contra PostgreSQL 16, como `kamayuk_app` y con RLS activa,
--  sobre 30 000 vehiculos por municipalidad en dos municipalidades con las
--  MISMAS placas, guardadas con guion y pedidas sin el
--  (`LaPlacaDelVehiculoEnElPlanTest`):
--
--                         | sin V32                          | con V32
--    ---------------------+----------------------------------+---------------
--     findByPlaca         | Bitmap Heap Scan por la politica | Index Scan por
--                         | (vehiculo_contribuyente_ix), la  | vehiculo_placa_uq,
--                         | placa al Filter: 29 999          | 0 descartadas,
--                         | descartadas, 477 bloques         | 3 bloques
--     buscar, con placa   | lo mismo, y el titular por su    | Index Scan por
--                         | clave: 29 999 descartadas,       | vehiculo_placa_uq,
--                         | 480 bloques                      | 0 descartadas,
--                         |                                  | 6 bloques
--
--  Cada busqueda por placa leia el padron vehicular entero del inquilino.
--
--  QUE HACE
--  --------
--  La misma salida que V27:
--
--    * una columna GENERADA, `placa_busqueda`, con la placa sin guion. `placa`
--      no cambia: el guion se conserva porque es lo que el papel imprime
--      (`Placa`), y ninguna fila se reescribe a mano;
--    * y `vehiculo_placa_uq` pasa a ser el unico `(municipalidad_id,
--      placa_busqueda)`: la unicidad y la busqueda son LA MISMA columna. Se
--      conserva el NOMBRE porque es el que nombran el mensaje con que la base
--      rechaza una placa repetida, `ImportarVehiculos` y sus pruebas.
--
--  La igualdad sobre una columna (`texteq`) SI es leakproof, asi que la placa y
--  la politica entran JUNTAS en el `Index Cond`.
--
--  POR QUE SOLO EL GUION, Y NO LA EXPRESION ENTERA DE V27
--  ------------------------------------------------------
--  V27 quita tambien espacios y sube a mayusculas porque `Papeleta`,
--  `Internamiento` y `ConstanciaLibre` guardan la placa CON sus espacios
--  interiores. En `vehiculo` solo entra lo que `Placa` ya limpio —recortada,
--  en mayusculas, sin espacios—, y V27 ya lo anotaba: «En `vehiculo` basta
--  quitar el guion». Y hay un segundo motivo, que decide: con
--  `replace(placa::text, '-', '')` el unico nuevo se construye sobre EXACTAMENTE
--  los mismos valores que el de V1, asi que no puede fallar en ninguna base
--  donde el de V1 exista, y la unicidad no cambia de significado. Con la
--  expresion de V27, una fila cargada fuera de `Placa` en minusculas o con un
--  espacio podria chocar con otra y hacer fallar la migracion.
--
--  POR QUE UNA COLUMNA GENERADA Y NO MARCAR `replace` COMO LEAKPROOF
--  ------------------------------------------------------------------
--  Lo mismo que V27 y V13: marcarla es un acto de superusuario y afirmaria algo
--  de una funcion del nucleo que usa medio sistema.
--
--  LO QUE CUESTA
--  -------------
--  Una columna `text` de hasta 10 caracteres por fila; el indice sustituye al
--  de V1, del mismo tamano. `ADD COLUMN ... GENERATED ... STORED` REESCRIBE la
--  tabla y la bloquea mientras tanto: es el precio de hacerlo en una migracion,
--  que Flyway corre en transaccion. El `DROP` y el `CREATE` del unico van en la
--  misma transaccion: no hay un instante sin unicidad.
--
--  POR QUE ESTO NO ES DESTRUCTIVO
--  ------------------------------
--  No se borra ni se corrige ni una fila (regla 4, RNF-051): la columna nueva se
--  DERIVA de la que ya estaba, y ninguna escritura de la aplicacion la nombra
--  —una columna generada no admite que se escriba—. Los indices no son datos.
--  Y `kamayuk_app` y `kamayuk_readonly` la leen sin permiso nuevo: los `GRANT
--  SELECT` de `vehiculo` son sobre la tabla, no por columna.
-- ============================================================================

ALTER TABLE vehiculo
    ADD COLUMN placa_busqueda text
        GENERATED ALWAYS AS (replace(placa::text, '-', '')) STORED;

DROP INDEX vehiculo_placa_uq;

CREATE UNIQUE INDEX vehiculo_placa_uq
    ON vehiculo (municipalidad_id, placa_busqueda);

COMMENT ON COLUMN vehiculo.placa_busqueda IS
    'La placa sin guion: la forma con que se compara y se busca (#511), la de '
    'Placa.formaDeBusqueda para lo que Placa ya limpio. Generada para que la igualdad llegue al '
    'indice bajo RLS: replace() no es leakproof. La columna placa conserva el guion, que es lo que '
    'se imprime.';

COMMENT ON INDEX vehiculo_placa_uq IS
    'Una placa por municipalidad, sin distinguir el guion (V1), y el indice por el que '
    'VehiculoRepositoryJdbc busca por placa (#511): la unicidad y la busqueda son la misma columna.';

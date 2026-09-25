-- ============================================================================
--  V26 — EL HISTORICO VEHICULAR SE LEE POR INDICE (#473)
--
--  QUE PASABA
--  ----------
--  Desde #329 el contribuyente del impuesto vehicular de un ejercicio es quien
--  tenia el vehiculo al 1 de enero (TUO LTM art. 31), y eso se deriva de la
--  historia de `transferencia` (`PropietarioAlPrimeroDeEnero`). El calculo por
--  contribuyente (`RegistrarDeterminacionVehicular.vehiculosDe`) hace dos
--  preguntas a esta tabla:
--
--    * la cadena de UN vehiculo, por `vehiculo_id` y ordenada por fecha
--      (`TransferenciaRepositoryJdbc.HISTORICO_DE_VEHICULO`), una vez por
--      vehiculo candidato;
--    * los vehiculos que UN contribuyente transfirio desde el 1 de enero, por
--      `transferente_id` y `fecha_transferencia >= :fecha`
--      (`TransferenciaRepositoryJdbc.VEHICULOS_QUE_TRANSFIRIO_DESDE`).
--
--  Y la tabla no tenia mas indice que su clave (`municipalidad_id, id`). Medido en
--  #473 contra PostgreSQL 16, como `kamayuk_app` y con RLS activa, sobre 40 000
--  transferencias por municipalidad en dos municipalidades
--  (`HistoricoDeTransferenciasEnElPlanTest`):
--
--                                   | sin V26                  | con V26
--    -------------------------------+--------------------------+-----------------
--     la cadena de un vehiculo      | Seq Scan, 79 998 filas   | Index Scan,
--                                   | descartadas, 1 784       | 0 descartadas,
--                                   | bloques, 17,2 ms         | 13 bloques, 0,2 ms
--     lo que transfirio desde el 1/1| Bitmap por la politica,  | Index Scan,
--                                   | 39 998 descartadas,      | 0 descartadas,
--                                   | 1 048 bloques, 13,6 ms   | 8 bloques, 0,2 ms
--
--  (La cadena, sin V26, ni siquiera usa la clave: recorre la tabla de TODAS las
--  municipalidades y descarta tambien las filas de la vecina.)
--
--  Hoy lo llama solo el controlador, una persona por peticion, y no se nota. Una
--  masiva vehicular haria la primera pregunta una vez por vehiculo del padron:
--  recorrer la tabla entera por cada uno.
--
--  QUE HACE ESTA MIGRACION
--  -----------------------
--  Los dos indices que el issue pide, cada uno con la columna que la pregunta
--  iguala y la fecha detras, que es lo que la primera ordena y la segunda acota:
--
--    * `transferencia_vehiculo_fecha_ix`   (municipalidad_id, vehiculo_id, fecha_transferencia)
--    * `transferencia_transferente_fecha_ix` (municipalidad_id, transferente_id, fecha_transferencia)
--
--  Con `municipalidad_id` delante, como el resto de los indices del esquema: la
--  politica RLS lo pone en toda consulta, y las tres condiciones —la igualdad de
--  `bigint`, la de la politica y la desigualdad de `date`— son leakproof, asi que
--  entran JUNTAS en el `Index Cond` (quinto hallazgo de DAT-01 §0; medido).
--
--  PARCIALES, `WHERE vehiculo_id IS NOT NULL`, porque las dos preguntas son del
--  historico VEHICULAR: las filas de predio (`transferencia_objeto_ck` les exige
--  `vehiculo_id` nulo) no entran. La primera pregunta iguala `vehiculo_id`, que es
--  estricta e implica el `IS NOT NULL`; la segunda lo escribe. Si algun dia hace
--  falta leer por transferente las de PREDIO, es otro indice con su medida.
--
--  POR QUE NO ES DESTRUCTIVO
--  -------------------------
--  Un indice no es un dato (regla 4): no toca ni una fila. Va sin `CONCURRENTLY`
--  porque Flyway migra en transaccion y `CONCURRENTLY` no cabe en una; sobre el
--  tamaño que hoy tiene la tabla, el bloqueo dura lo que dura construirlo.
-- ============================================================================

CREATE INDEX transferencia_vehiculo_fecha_ix
    ON transferencia (municipalidad_id, vehiculo_id, fecha_transferencia)
    WHERE vehiculo_id IS NOT NULL;

CREATE INDEX transferencia_transferente_fecha_ix
    ON transferencia (municipalidad_id, transferente_id, fecha_transferencia)
    WHERE vehiculo_id IS NOT NULL;

COMMENT ON INDEX transferencia_vehiculo_fecha_ix IS
    'La cadena de transferencias de un vehiculo, por fecha (#473). La lee '
    'PropietarioAlPrimeroDeEnero, via TransferenciaRepositoryJdbc.historicoDeVehiculo, una vez '
    'por vehiculo candidato del calculo vehicular. Parcial: solo las filas de vehiculo.';

COMMENT ON INDEX transferencia_transferente_fecha_ix IS
    'Los vehiculos que un contribuyente transfirio desde una fecha (#473). Los lee el calculo '
    'vehicular por contribuyente para sumar a sus vehiculos de hoy los que tenia el 1 de enero. '
    'Parcial: solo las filas de vehiculo.';

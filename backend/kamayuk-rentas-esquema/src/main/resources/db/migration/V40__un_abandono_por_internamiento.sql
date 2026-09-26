-- ============================================================================
--  V40 — UN ABANDONO POR INTERNAMIENTO (#454)
--
--  QUE PASABA
--  ----------
--  El estado EN_ABANDONO de un vehiculo internado no tenia acto que lo
--  produjera: `MovimientoDeInternamiento.abandono` no tenia llamador y ningun
--  verbo de la API escribia la fila ABANDONO que el CHECK
--  `internamiento_movimiento_tipo_check` (V1) admite. #454 escribe el acto
--  (`DeclararAbandonoDeVehiculo`, `POST /transito/internamientos/{placa}/
--  abandono`).
--
--  QUE HACE
--  --------
--  Lo que la liberacion ya tiene (`internamiento_liberacion_uq`, V1): un
--  indice unico parcial que impide dos abandonos del mismo internamiento. El
--  caso de uso lo comprueba antes leyendo el historial; el indice cubre la
--  carrera y el SQL directo.
--
--  Lo que NO hace: retirar EN_ABANDONO del enumerado ni del CHECK (criterio
--  de #259: un padron migrado puede traer la fila), ni declarar abandonos por
--  si solo a partir de un plazo, que es otra decision.
-- ============================================================================

CREATE UNIQUE INDEX internamiento_abandono_uq
    ON internamiento_movimiento (municipalidad_id, internamiento_id)
    WHERE tipo = 'ABANDONO';

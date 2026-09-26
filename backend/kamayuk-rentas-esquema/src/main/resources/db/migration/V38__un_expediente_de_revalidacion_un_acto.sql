-- ============================================================================
--  V38 — UN EXPEDIENTE DE REVALIDACION, UN ACTO (#449)
--
--  QUE PASABA
--  ----------
--  La emision de una licencia de edificacion se protege dos veces: el caso de
--  uso comprueba que el expediente no la tenga, y `edificacion_movimiento_
--  emision_uq` lo sostiene en la base. La revalidacion no tenia ni lo uno ni
--  lo otro: el mismo expediente de revalidacion, pedido otra vez con otro
--  recibo y un plazo mayor, pasaba las tres comprobaciones y dejaba otra
--  resolucion y otro tramo de vigencia. Y como `edificacion_movimiento` y
--  `edificacion_vigencia` solo admiten INSERT (regla 4), el tramo de mas no se
--  puede retirar: la obra quedaba autorizada mas alla de lo que el tramite
--  pidio.
--
--  QUE HACE
--  --------
--  El indice unico parcial, hermano del de la emision: una REVALIDACION por
--  expediente. El caso de uso lo comprueba antes (`revalidacionDe`), y el
--  indice cubre la carrera de dos peticiones simultaneas.
--
--  Lo que NO hace: decidir que pasa con una base que ya tenga dos
--  revalidaciones del mismo expediente. Si las hay, crear el indice falla y
--  la migracion se detiene nombrandolo: cual de los dos actos vale es una
--  decision de la mesa de partes, no de una migracion.
-- ============================================================================

CREATE UNIQUE INDEX edificacion_movimiento_revalidacion_uq
    ON edificacion_movimiento (municipalidad_id, fue_id)
    WHERE tipo = 'REVALIDACION';

-- ============================================================================
--  V30 — EL PROGRAMA SE CIERRA, Y SOLO ESO SE MUEVE (#341)
--
--  LO QUE SE MIDIO
--  ---------------
--  `programa_fiscalizacion_estado_check` (V1) declara tres valores -ABIERTO,
--  EN_PROCESO, CERRADO-, `V1` le concede a `kamayuk_app` UPDATE sobre la tabla
--  entera ... y este sistema escribia UNO. No habia en `src/main` una sola
--  sentencia `UPDATE programa_fiscalizacion`: el programa nacia ABIERTO y ahi
--  se quedaba. La exclusion de #481 -`prediosEnProgramasAbiertos`, que aparta
--  los predios de otro programa ABIERTO o EN_PROCESO- retenia asi para siempre
--  a todo predio sorteado una vez, y el omiso cronico, que es justo lo que un
--  programa de omisos busca, no volvia a entrar en ninguna muestra.
--
--  Es el mismo patron que #214 con el acta (V19) y #243 con la papeleta (V20):
--  el CHECK declara un vocabulario y la produccion escribe un solo valor.
--
--  QUE SE ANADE, Y QUE NO
--  ----------------------
--  CERRADO. No se deriva de nada -ninguna fila dice que un programa termino, y
--  `fecha_fin` es el plazo programado, no el dia del cierre-: es un acto de la
--  administracion, y lo escribe `CerrarProgramaFiscalizacion`, que publica
--  `POST /fiscalizacion/programas/{id}/cierre` con su observacion, su fecha y
--  su auditoria.
--
--  EN_PROCESO NO. Si algun dia hace falta, se deriva de un hecho -«tiene acta
--  levantada»- y no se escribe, por lo mismo que V19 se nego a escribir
--  LIQUIDADA en el acta: dos verdades sobre el mismo hecho.
--
--  QUE HACE ESTA MIGRACION
--  -----------------------
--  Estrecha el privilegio: `kamayuk_app` pierde el UPDATE sobre la TABLA y
--  recibe el UPDATE sobre la COLUMNA `estado`. Es el mismo trato que V19 le
--  dio al acta y por el mismo motivo: lo que el programa declaro -su codigo, su
--  ejercicio, su sector, su criterio, su fiscalizador- es con lo que sorteo su
--  muestra, y reprogramar es registrar otro programa (#45), no reescribir este.
--
--  NO ESTRECHA EL CHECK. EN_PROCESO se queda en el vocabulario: es el que
--  `admiteVisitas` y la exclusion leen, y un padron migrado puede traerlo. Bajo
--  FORCE ROW LEVEL SECURITY el migrador corre sin contexto de tenant y no puede
--  ni leer esas filas, asi que un CHECK estrechado -aunque fuese NOT VALID-
--  volveria incerrable a un programa migrado que constase EN_PROCESO, porque
--  NOT VALID sigue comprobando toda fila que se ACTUALIZA (lo que #259 midio con
--  la papeleta).
-- ============================================================================

REVOKE UPDATE ON programa_fiscalizacion FROM kamayuk_app;
GRANT UPDATE (estado) ON programa_fiscalizacion TO kamayuk_app;

COMMENT ON COLUMN programa_fiscalizacion.estado IS
    'En que punto esta el programa (#341). ABIERTO es como nace todo programa; CERRADO lo escribe '
    'CerrarProgramaFiscalizacion -POST /fiscalizacion/programas/{id}/cierre-, que es el unico UPDATE '
    'de esta tabla y el que hace que la exclusion de #481 suelte los predios del programa. Es '
    'terminal: un programa cerrado no se reabre. EN_PROCESO no lo escribe este sistema: si hace '
    'falta, se deriva de que el programa tenga acta levantada. El dia del cierre no esta en la '
    'fila -fecha_fin es el plazo programado- sino en la auditoria.';

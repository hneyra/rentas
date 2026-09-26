-- ============================================================================
--  V32 — EL NUMERO DE NOTIFICACION NACE CON EL ACTO DE NOTIFICAR (#368)
--
--  QUE PASABA
--  ----------
--  `liquidacion_fiscalizacion.numero_notificacion` existia y el contrato la
--  publicaba dos veces —como campo `numeroNotificacion` de la ficha y como
--  filtro `nNotificacion` del historico (RF-056)—, pero ningun camino la
--  escribia: las dos fabricas de `Liquidacion` la dejaban en nulo, el INSERT
--  la copiaba nula, y despues ya no se podia escribir porque `kamayuk_app`
--  solo tiene INSERT y SELECT sobre la cabecera. El acto de notificar —un
--  movimiento NOTIFICADA en `liquidacion_movimiento`— no tenia donde dejar el
--  numero del cargo. Buscar por el papel que el contribuyente trae contestaba
--  «no hay nada» sobre una liquidacion notificada.
--
--  QUE HACE
--  --------
--  El numero nace con el acto, y su UNICA fuente de verdad es el movimiento
--  NOTIFICADA, no la cabecera: la liquidacion es inmutable y su estado se
--  deriva del historial, asi que lo que se sabe al notificar va en la fila que
--  registra la notificacion.
--
--    1. `liquidacion_movimiento.numero_notificacion varchar(40)`: el ancho es
--       el de la cabecera y el de `MovimientoDeLiquidacion.NUMERO_MAXIMO`.
--    2. Un CHECK: un movimiento NOTIFICADA lleva numero, y ningun otro lo
--       lleva. Es la misma regla que `MovimientoDeLiquidacion` comprueba al
--       construirse —y por la que el PATCH contesta 422—, escrita donde un
--       INSERT que no pase por el codigo tambien la encuentra.
--
--  POR QUE `NOT VALID`
--  -------------------
--  Porque un padron migrado puede traer movimientos NOTIFICADA sin numero, y
--  el numero no se puede inventar. `NOT VALID` no revisa las filas que ya hay
--  y SI toda fila nueva. El dominio lo sabe: lee un NOTIFICADA sin numero sin
--  reventar el historial, y solo se niega a ESCRIBIR uno.
--
--  LA COLUMNA DE LA CABECERA SE QUEDA, COMO VESTIGIO
--  -------------------------------------------------
--  `liquidacion_fiscalizacion.numero_notificacion` no se borra: retirar una
--  columna de una tabla que solo se agrega no gana nada, y un volcado migrado
--  podria traerla llena. Pero el codigo ya no la escribe ni la lee, y su
--  comentario lo dice para que ninguna consulta ad hoc la tome por la verdad.
--
--  Lo que NO hace: la notificacion de la resolucion de determinacion (tabla
--  `notificacion`, objeto RESOLUCION), que es otro papel con su diligencia.
-- ============================================================================

ALTER TABLE liquidacion_movimiento ADD COLUMN numero_notificacion varchar(40);

ALTER TABLE liquidacion_movimiento
    ADD CONSTRAINT liquidacion_movimiento_notificacion_ck
    CHECK (((estado)::text = 'NOTIFICADA'::text) = (numero_notificacion IS NOT NULL))
    NOT VALID;

COMMENT ON COLUMN liquidacion_movimiento.numero_notificacion IS
    'El «Nº Notificación» del cargo que se entrego al contribuyente (#368). Solo lo lleva el '
    'movimiento NOTIFICADA, y es la UNICA fuente del numero: la ficha y el filtro nNotificacion del '
    'historico lo leen del ultimo movimiento NOTIFICADA de la liquidacion.';
COMMENT ON CONSTRAINT liquidacion_movimiento_notificacion_ck ON liquidacion_movimiento IS
    'NOTIFICADA lleva su numero y ningun otro estado lo lleva (#368). NOT VALID: un padron migrado '
    'puede traer notificaciones sin numero, y el numero no se inventa.';
COMMENT ON COLUMN liquidacion_fiscalizacion.numero_notificacion IS
    'VESTIGIO (#368): ningun camino la escribe ni la lee. El numero de notificacion vive en '
    'liquidacion_movimiento.numero_notificacion, en el movimiento NOTIFICADA.';

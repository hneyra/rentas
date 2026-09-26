-- =============================================================================
--  V35 — `sistema_caja` ES QUIEN PUBLICA EL PAGO (#461)
-- =============================================================================
--
--  La columna nacio en V8 para decir «de que caja viene» el pago, pero el borde la llenaba con
--  `sistemaOrigen` del cuerpo, que es otra cosa: «a que sistema iba la orden, tal como la caja lo
--  registro». La caja lo manda en los cobros (con el sistema de la orden: `rentas`) y no en las
--  anulaciones, asi que el cobro quedaba con 'rentas' y su anulacion con 'caja': la pareja que se
--  tiene que cancelar caia en dos grupos de un `GROUP BY sistema_caja`.
--
--  Desde #461 el borde escribe quien publica, que es la cuenta de servicio que el guardia exige
--  (#429): la caja. Las filas anteriores NO se reescriben aqui: `pago_recibido` tiene RLS forzada
--  y ninguna migracion de este esquema corrige datos por encima de ella —hacerlo exigiria apagar
--  la politica a mitad de la migracion—. Se deja dicho en la columna, y no se pierde nada:
--  `sistemaOrigen` sigue en `cuerpo`, el JSON congelado tal como llego. V8 no se toca.
-- =============================================================================

COMMENT ON COLUMN pago_recibido.sistema_caja IS
    'Quien publico el pago: la cuenta de servicio de la caja que el borde exige (#429, #461). '
    'No es el sistema de la orden, que viaja como sistemaOrigen dentro de cuerpo. Las filas '
    'escritas antes de V35 pueden decir rentas en un cobro: entonces se copiaba sistemaOrigen.';

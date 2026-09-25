-- ============================================================================
--  V31 — A INICIADO NO SE VUELVE (#409)
--
--  QUE PASABA
--  ----------
--  `INICIADO` es con lo que nace un expediente coactivo al importar, antes de
--  cualquier REC. No esta en el desplegable «Nuevo estado» de la pantalla
--  `expediente_historial`, y `EstadoDelExpediente` lo dice: no se elige. Pero
--  el `PATCH /coactiva/expedientes/{numero}/estados` lo aceptaba —por el nombre
--  y por el codigo `000`— y contestaba 200, y la base tampoco lo impedia:
--  `expediente_movimiento_apertura_ck` exige INICIADO en la APERTURA, pero
--  nada lo prohibia en un movimiento ESTADO, y `..._estado_check` lo admite
--  porque la apertura lo necesita.
--
--  Un expediente con su REC-1 dictada, devuelto a INICIADO, pasaba a contar
--  como «sin REC-1» en `ExpedientesSinRec.cuantosSinRec1` —que alimenta el
--  panel de trabajo parado— y en `sinRec` del resumen de cartera. Y
--  `acto_rec1_uq` impide dictarle otra: quedaba contado como pendiente sin que
--  nadie pudiera atenderlo.
--
--  QUE HACE
--  --------
--  Un CHECK: un movimiento ESTADO no lleva INICIADO. Es la misma regla que
--  `MovimientoDelExpediente` comprueba al construirse —y por la que el PATCH
--  contesta 422—, escrita donde un INSERT que no pase por el codigo tambien la
--  encuentra.
--
--  Se valida contra las filas que ya hay, a proposito: si una base tuviera un
--  movimiento ESTADO en INICIADO, el dominio ya no podria leerlo, y es mejor
--  que la migracion lo diga nombrando la restriccion que un historial que
--  revienta al abrirse. Ninguna instalacion de este sistema lo tiene: no se
--  ha desplegado todavia.
--
--  Lo que NO hace: decidir si el cambio de estado manual puede retroceder entre
--  los seis estados del desplegable. Eso es una decision de negocio, y #409 la
--  deja fuera.
-- ============================================================================

ALTER TABLE expediente_movimiento
    ADD CONSTRAINT expediente_movimiento_iniciado_ck
    CHECK ((tipo)::text <> 'ESTADO'::text OR (estado)::text <> 'INICIADO'::text);

COMMENT ON CONSTRAINT expediente_movimiento_iniciado_ck ON expediente_movimiento IS
    'INICIADO es con lo que nace el expediente (APERTURA), y un cambio de estado no lo devuelve '
    'ahi (#409): con la REC-1 dictada contaria como «sin REC-1» sin que nadie pudiera dictarsela.';

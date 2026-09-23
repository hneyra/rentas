-- ============================================================================
--  V24 — LA ANULACION SE BUSCA POR EL PAGO QUE NOMBRA (#428)
--
--  QUE PASABA
--  ----------
--  `V8` le dio a `pago_recibido` la columna `pago_original_id` y escribio para que:
--  «Una anulacion dice QUE pago deshace. Sin ello habria que buscar sus asientos por
--  el numero del papel, que es texto y no una clave». Y el codigo hacia lo contrario:
--  la columna se escribia y en `src/main` no habia ni una consulta por ella. La
--  anulacion buscaba que deshacer por `"RECIBO " + numero`, y el cobro no miraba si
--  ya lo habian anulado.
--
--  La consecuencia, medida en #428: si la anulacion llegaba mientras su cobro
--  todavia no confirmaba —esperando un candado mas de 30 s, o en una replica que se
--  apagaba—, quedaba RECHAZADA con 201 y la caja la daba por entregada. El cobro se
--  imputaba despues: deuda extinguida con el dinero ya devuelto.
--
--  QUE HACE ESTA MIGRACION
--  -----------------------
--  Solo el indice que la pregunta nueva necesita. Desde #428, cada cobro que se
--  imputa pregunta antes «¿hay una anulacion que me nombre?»
--  (`PagoRecibidoRepositoryJdbc.anulacionDe`), y sin indice esa pregunta recorre el
--  buzon entero una vez por pago. La otra pregunta nueva —la anulacion buscando su
--  cobro— va por `pago_id`, que ya tenia `pago_recibido_uq`.
--
--  PARCIAL, porque solo una anulacion lleva `pago_original_id`
--  (`pago_recibido_original_ck`): los cobros, que son casi todas las filas, no
--  entran. Con `municipalidad_id` delante, como el resto de los indices de la tabla:
--  la politica RLS lo pone en toda consulta.
--
--  POR QUE NO ES UNICO
--  -------------------
--  Porque la pregunta que se hace es «¿hay alguna?», y para eso basta con uno que no
--  lo sea. Que un recibo se anule una sola vez lo garantiza la caja, que es quien
--  anula; declararlo aqui seria una segunda copia de una regla ajena, y en una base
--  que ya tuviera un duplicado haria fallar la migracion —y con ella el arranque—
--  en vez de dejar la fila a la vista de quien tenga que mirarla.
--
--  Y NO HAY CLAVE FORANEA, igual que en `V8`: la anulacion puede llegar ANTES que su
--  cobro —que es exactamente el caso de #428—, y una clave foranea la rechazaria en
--  vez de dejar que la aplicacion conteste «todavia no».
-- ============================================================================

CREATE INDEX pago_recibido_original_ix ON pago_recibido (municipalidad_id, pago_original_id)
    WHERE pago_original_id IS NOT NULL;

COMMENT ON INDEX pago_recibido_original_ix IS
    'La anulacion de un pago, buscada por el pago que nombra (#428). Lo lee la imputacion de cada '
    'cobro antes de tocar el libro: un cobro con una anulacion ya en el buzon se rechaza «anulado '
    'antes de imputarse». Parcial porque solo una anulacion lleva pago_original_id.';

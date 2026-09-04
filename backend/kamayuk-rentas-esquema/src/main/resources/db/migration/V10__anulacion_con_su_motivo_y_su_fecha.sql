-- ============================================================================
--  V10 — LA ANULACION DICE POR QUE Y CUANDO (C-1, desajustes 8 y 9)
--
--  QUE SE PERDIA, Y POR QUE NADIE PODIA VERLO
--  ------------------------------------------
--  `ComponedorDeEventosJson.pagoAnulado` de `caja` escribe DOS campos que `V8` no
--  tiene y que `PeticionDePago` no declaraba: `motivo` —el sustento del acto, que
--  la caja EXIGE (RNF-052)— y `fecha`, el dia en que se anulo.
--
--  Los cuatro backends tienen `FAIL_ON_UNKNOWN_PROPERTIES` apagado, asi que Jackson
--  los descartaba en silencio y el emisor recibia 201: el evento se marcaba
--  ENTREGADO, el buzon se vaciaba y el dato NO habia llegado. No hay reintento,
--  porque para la caja la entrega salio bien.
--
--  Y no sobrevivian «dentro del jsonb», como se creyo: `PagoController.congelar`
--  reserializa el `record`, asi que lo que se guarda en `cuerpo` es lo que el
--  `record` declara. Un campo que no declara no llega a la columna.
--
--  LAS DOS COLUMNAS, Y QUE DECIDE CADA UNA
--  ---------------------------------------
--    `motivo_anulacion`  POR QUE se anulo, en las palabras de quien lo autorizo en
--                        ventanilla. Va a la `Observacion` con la que se asientan los
--                        asientos de reversion, o sea al `motivo` de cada fila del
--                        libro: es con lo que se explica por que una deuda volvio a
--                        estar viva.
--    `fecha_anulacion`   CUANDO. Es la FECHA VALOR de la reversion, y decide ademas en
--                        que particion caen sus asientos. Hasta C-1 se reversaba con
--                        `fecha_pago` —la del recibo ORIGINAL—, de modo que anular en
--                        julio un recibo de marzo escribia la reversion en marzo: un
--                        estado de cuenta al 30 de abril recalculado despues cambiaba
--                        de respuesta, y el recibo estuvo vigente hasta julio. Es la
--                        regla 9 (RNF-075) y ADR-0006: el libro no se reescribe.
--
--  POR QUE NO SE REUSA LA COLUMNA `motivo` QUE YA HAY
--  --------------------------------------------------
--  Porque significa otra cosa, y `pago_recibido_motivo_ck` lo dice: `motivo` es por
--  que ESTE sistema no pudo imputar el pago, y la restriccion lo exige solo cuando el
--  estado es RECHAZADO. Meter ahi el motivo de la caja dejaria dos verdades en la
--  misma celda —una del emisor y otra del receptor—, y la que se lea en una pantalla
--  seria la que nadie recuerde cual es (#397, #481).
--
--  POR QUE `varchar(300)`
--  ----------------------
--  Porque este texto se compone dentro de una `Observacion`, que el esquema limita a
--  500 caracteres, junto con el identificador del pago y el numero del recibo (~114).
--  `caja` lo declara hoy `varchar(80)` en `recibo_movimiento.motivo`, asi que sobra
--  sitio; 300 deja margen si esa columna se ensancha y sigue cabiendo en la frase.
--
--  POR QUE EL `CHECK` VA `NOT VALID`
--  ---------------------------------
--  Por los datos, no por RLS. No se puede saber que hay hoy en `pago_recibido` de una
--  instalacion en marcha, y un `ALTER TABLE` validado que encontrara una anulacion
--  anterior a esta migracion fallaria con «is violated by some row» y la dejaria sin
--  migrar. Esas filas tampoco se pueden reparar desde aqui: el migrador corre sin
--  contexto de tenant y esta tabla tiene `FORCE ROW LEVEL SECURITY` (DAT-01 §0, la
--  misma lectura que `V64` y `V77` de `sgtm` dejaron escrita).
--
--  `NOT VALID` sigue comprobando cada `INSERT` y cada `UPDATE`, que es lo que hace
--  falta: NULL en estas dos columnas significa «esta fila es anterior a V10», no «se
--  desconoce por que se anulo».
-- ============================================================================

ALTER TABLE pago_recibido ADD COLUMN motivo_anulacion varchar(300);
ALTER TABLE pago_recibido ADD COLUMN fecha_anulacion  date;

ALTER TABLE pago_recibido
    ADD CONSTRAINT pago_recibido_anulacion_ck CHECK (
        (tipo = 'PAGO_ANULADO')
            = (motivo_anulacion IS NOT NULL AND fecha_anulacion IS NOT NULL))
    NOT VALID;

COMMENT ON COLUMN pago_recibido.motivo_anulacion IS
    'POR QUE se anulo el recibo, en las palabras de quien lo autorizo en ventanilla. Lo manda '
    '`caja` en el evento PAGO_ANULADO y lo exige (RNF-052). NO es `motivo`, que es por que ESTE '
    'sistema no pudo imputar el pago: son dos verdades distintas y una sola celda para las dos '
    'acaba siendo la que nadie recuerda cual es. Nulo solo en filas anteriores a V10.';
COMMENT ON COLUMN pago_recibido.fecha_anulacion IS
    'CUANDO se anulo, y por tanto la FECHA VALOR de los asientos de reversion. No es `fecha_pago`, '
    'que es la del recibo original: reversar con aquella reescribe la historia —el recibo estuvo '
    'vigente entre las dos fechas— y ademas mete los asientos en otra particion. Regla 9, ADR-0006.';

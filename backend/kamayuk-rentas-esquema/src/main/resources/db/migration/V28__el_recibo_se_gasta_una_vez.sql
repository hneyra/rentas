-- ============================================================================
--  V28 — EL RECIBO SE GASTA UNA VEZ (#383)
--
--  QUE PASABA
--  ----------
--  Un recibo de caja de tasas servia de prueba de pago SIN QUEDAR CONSUMIDO. Quien lo
--  comprobaba preguntaba cinco cosas —que exista, que sea de tasas, que no este
--  anulado, que sea del titular y que cubra el concepto— y ninguna era «¿ya se uso?».
--  Con un solo pago salian N licencias, N certificados, N duplicados, N revalidaciones
--  y N liberaciones del deposito, y la suma del «Derecho S/» del padron de
--  certificados era N veces lo que la caja cobro.
--
--  La base tampoco lo impedia: `recibo_id` de `certificado`, `edificacion_movimiento`,
--  `licencia_duplicado` y `licencia_funcionamiento`, y `recibo_custodia` de
--  `internamiento_movimiento`, no entran en ningun indice unico, y `V7` les quito
--  incluso la clave foranea al irse el recibo a `caja`.
--
--  QUIEN LLEVA LA CUENTA, Y POR QUE AQUI
--  -------------------------------------
--  «¿Este recibo vale para este tramite?» es verdad de `caja`, y se le sigue
--  preguntando por su puerto. «¿Ya se gasto?» solo lo sabe quien EMITE LOS ACTOS: `caja`
--  no sabe que licencia uso que recibo, y no tiene por que aprenderlo. Por eso la
--  constancia vive en esta base, detras del puerto `tesoreria.AplicacionDeRecibos`: si
--  manana `caja` ofrece consumir el recibo, cambia el adaptador y no los seis casos de
--  uso que la llaman.
--
--  POR QUE NO ES UN `UNIQUE (municipalidad_id, recibo_id)` EN CADA TABLA DE ACTO
--  ----------------------------------------------------------------------------
--  Seria lo mas corto y no sirve: un recibo puede cobrar VARIAS UNIDADES del mismo
--  concepto —`TasaCobrada.cantidad`; en la custodia, los dias—, y un recibo que cobro
--  dos certificados respalda dos. Ese indice rechazaria el segundo, que es legitimo.
--  Lo que se lleva es la cuenta: cada aplicacion es una fila con las unidades que
--  consumio, numerada por `orden` dentro de su recibo y su concepto.
--
--  LA CARRERA LA DECIDE EL INDICE, NO UN `if`
--  ------------------------------------------
--  `AplicacionDeRecibosJdbc` lee lo ya aplicado, rechaza si no alcanza e inserta con
--  `orden = max + 1`. Dos transacciones que gastan a la vez el ultimo saldo leen el
--  mismo maximo e insertan el MISMO `orden`: la segunda choca en `recibo_aplicado_uq`
--  y sale 409. Es la forma de la casa (`ResolucionDeGerenciaRepository`,
--  `convenio_movimiento_formalizacion_uq`). El precio, dicho: dos consumos simultaneos
--  de un recibo al que le queda saldo para los dos tambien chocan, y el segundo se
--  reintenta. Un falso rechazo que se reintenta es preferible a un acto sin pagar.
--
--  SOLO SE INSERTA (regla 4, RNF-051)
--  ----------------------------------
--  Nada se actualiza ni se borra. Anular una licencia NO libera el recibo, porque
--  tampoco devuelve el dinero; si la caja anula el recibo, lo dice `caja` y el recibo
--  deja de acreditar, que es otra pregunta.
--
--  NACE VACIA, y es deliberado. Los actos ya emitidos con un recibo repetido no se
--  siembran desde las columnas `recibo_id` existentes: `FORCE ROW LEVEL SECURITY` no
--  deja al migrador leer esas filas, y si se quiere es una pasada de la aplicacion por
--  municipalidad, aparte.
-- ============================================================================

CREATE TABLE recibo_aplicado (
    municipalidad_id bigint       NOT NULL REFERENCES municipalidad (id),
    numero_recibo    varchar(20)  NOT NULL,
    concepto         varchar(20)  NOT NULL,
    orden            integer      NOT NULL,
    unidades         integer      NOT NULL,
    tabla            varchar(40)  NOT NULL,
    acto_id          bigint       NOT NULL,
    usuario_registro varchar(60)  NOT NULL,
    fecha_registro   timestamptz  NOT NULL,

    -- LA GARANTIA. Ver la cabecera: dos consumos del mismo saldo chocan aqui.
    CONSTRAINT recibo_aplicado_uq UNIQUE (municipalidad_id, numero_recibo, concepto, orden),
    CONSTRAINT recibo_aplicado_orden_ck CHECK (orden >= 1),
    -- Una aplicacion de cero unidades no gasta nada y dejaria una fila que no significa
    -- nada: el acto no estaria pagado por ella.
    CONSTRAINT recibo_aplicado_unidades_ck CHECK (unidades >= 1),
    CONSTRAINT recibo_aplicado_tabla_ck CHECK (length(btrim(tabla)) >= 3),
    CONSTRAINT recibo_aplicado_acto_ck CHECK (acto_id >= 1)
);

-- El reverso: que recibo pago un acto. Es la pregunta de quien audita un papel.
CREATE INDEX recibo_aplicado_acto_ix ON recibo_aplicado (municipalidad_id, tabla, acto_id);

COMMENT ON TABLE recibo_aplicado IS
    'Que acto consumio que recibo de caja de tasas, y cuantas unidades (#383). «¿El recibo vale '
    'para este tramite?» lo contesta `caja`; «¿ya se gasto?» solo lo sabe este sistema, que emite '
    'los actos. Solo se inserta: anular un acto no libera el recibo, porque tampoco devuelve el '
    'dinero. Nacio vacia: los actos anteriores a #383 no estan aqui.';
COMMENT ON COLUMN recibo_aplicado.numero_recibo IS
    'Como lo imprimio la caja, `001-0000123`, y como la caja lo devolvio al acreditarlo. No es '
    'una clave foranea: el recibo vive en `caja` desde `V7`.';
COMMENT ON COLUMN recibo_aplicado.orden IS
    'El numero de esta aplicacion dentro de su recibo y su concepto: 1, 2, 3… Es lo que hace que '
    'dos consumos simultaneos del mismo saldo choquen en `recibo_aplicado_uq`.';
COMMENT ON COLUMN recibo_aplicado.unidades IS
    'Las que este acto consumio. Una licencia, un duplicado o una revalidacion consumen 1 de 1 '
    'hasta que `caja` publique la cantidad de `ReciboDeTramite`; un certificado, 1 de lo que '
    '`TasaCobrada.cantidad` dice; una liberacion del deposito, todas las del recibo.';
COMMENT ON COLUMN recibo_aplicado.tabla IS
    'La tabla del acto que el recibo pago: `licencia_funcionamiento`, `licencia_duplicado`, '
    '`certificado`, `edificacion_movimiento` o `internamiento_movimiento`.';

-- ----------------------------------------------------------------------------
--  RLS. Sin valor por omision: sin contexto de tenant, la consulta FALLA.
-- ----------------------------------------------------------------------------

ALTER TABLE recibo_aplicado ENABLE ROW LEVEL SECURITY;
ALTER TABLE recibo_aplicado FORCE ROW LEVEL SECURITY;
CREATE POLICY recibo_aplicado_tenant ON recibo_aplicado FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  Privilegios. `kamayuk_app` lee e inserta, y nada mas: ni UPDATE ni DELETE, y no los
--  va a recibir (regla 4). El escaner de fuentes vigila ademas las dos cadenas
--  (`TablasDeRentas.PROTEGIDAS` e `INMUTABLES`).
-- ----------------------------------------------------------------------------

GRANT SELECT, INSERT ON recibo_aplicado TO kamayuk_app;
GRANT SELECT ON recibo_aplicado TO kamayuk_readonly;

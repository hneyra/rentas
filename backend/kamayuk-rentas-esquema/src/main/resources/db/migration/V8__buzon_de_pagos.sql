-- ============================================================================
--  V8 — EL BUZON DE ENTRADA DE PAGOS (P5D, ADR-0026 §3)
--
--  Va aparte de `V7` a proposito, por lo mismo que `V3` iba aparte de `V2` en P5B y
--  `V4`/`V5` de `V6` en P5C: `V7` dice QUE SE VA —la caja— y esta dice QUE LLEGA en su
--  lugar. Leidas juntas cuentan la extraccion; leida cada una sola sigue siendo
--  entendible.
--
--  QUE ES ESTO
--  -----------
--  Hasta P5D, cobrar era UNA transaccion: la ventanilla emitia el recibo y asentaba los
--  abonos en el libro, aqui mismo. `V7` se llevo el recibo a otra base, y ADR-0026 §3
--  convierte ese COMMIT en dos:
--
--      ventanilla ──► CAJA: recibo + arqueo   [COMMIT 1]
--                       └─ outbox ──► inbox ──► RENTAS: imputa + asienta   [COMMIT 2]
--                                                  └─ conciliacion diaria = 0
--
--  Esta tabla es el INBOX: la mitad receptora. Una fila por pago que la caja publico.
--
--  POR QUE UNA TABLA Y NO UNA LLAMADA DIRECTA AL LIBRO
--  ---------------------------------------------------
--  Tres motivos, y ninguno es de estilo:
--
--  1. LA IDEMPOTENCIA (criterio 3 del encargo de P5D). `pago_recibido_uq` sobre
--     `pago_id` es lo que hace que un pago inyectado dos veces produzca UN solo
--     asiento. Y es del MOTOR, no de un `if`: dos entregas simultaneas del mismo
--     reintento leerian las dos «no esta» y las dos imputarian. Aqui colarse significa
--     asentar dos veces el mismo abono, o sea perdonarle al contribuyente el doble de
--     lo que pago.
--
--  2. EL «PAGO EN TRANSITO» (ADR-0026 §4). Entre los dos COMMIT el saldo esta
--     desactualizado, y tiene que VERSE asi —no como si el contribuyente no hubiera
--     pagado—. La fila con `estado = 'EN_TRANSITO'` y su `recibido_en` es lo que la
--     consulta de deuda lee para poder decirlo. Sin tabla, esa ventana seria invisible.
--
--  3. EL PAGO QUE NO SE PUDO IMPUTAR. Si la imputacion falla —el contribuyente no
--     existe, la obligacion ya se extinguio, el ejercicio no tiene particion—, el pago
--     NO se pierde ni se reintenta para siempre: queda `RECHAZADO` con su motivo, y la
--     conciliacion del dia lo cuenta. Es dinero cobrado que alguien tiene que mirar.
--
--  LO QUE ESTA TABLA NO ES
--  -----------------------
--  NO es el libro. Aqui no hay saldo, ni fase, ni concepto: lo que se imputa se escribe
--  en `cuenta_corriente_asiento` por `RegistroDeAbonos`, como cualquier otro abono, y
--  la regla de imputacion del art. 31 del Codigo Tributario sigue viviendo en un solo
--  sitio (ADR-0026 §2). Esta tabla es el acuse: dice QUE llego y SI se aplico.
--
--  Y NO ES UN CACHE DEL RECIBO. `recibo_numero` y `total` se guardan porque son lo que
--  el pago DIJO —congelado, como `recibo_movimiento.importe` (#34)—, no para poder
--  responder por el recibo: el recibo es de `caja` y se le pregunta a `caja`.
-- ============================================================================


-- ----------------------------------------------------------------------------
--  1. EL BUZON
-- ----------------------------------------------------------------------------

CREATE TABLE pago_recibido (
    municipalidad_id  bigint       NOT NULL REFERENCES municipalidad (id),
    id                bigint       GENERATED ALWAYS AS IDENTITY,
    pago_id           uuid         NOT NULL,
    tipo              varchar(20)  NOT NULL,
    pago_original_id  uuid,
    sistema_caja      varchar(20)  NOT NULL,
    recibo_numero     varchar(20)  NOT NULL,
    contribuyente_id  bigint,
    fecha_pago        date         NOT NULL,
    total             dinero       NOT NULL,
    cuerpo            jsonb        NOT NULL,
    estado            varchar(20)  NOT NULL,
    asientos          integer      NOT NULL DEFAULT 0,
    motivo            varchar(400),
    recibido_en       timestamptz  NOT NULL,
    aplicado_en       timestamptz,

    CONSTRAINT pago_recibido_pk PRIMARY KEY (municipalidad_id, id),
    -- LA GARANTIA DEL CRITERIO 3, y es del motor. Ver la cabecera.
    CONSTRAINT pago_recibido_uq UNIQUE (municipalidad_id, pago_id),
    CONSTRAINT pago_recibido_tipo_ck CHECK (tipo IN ('PAGO_REGISTRADO', 'PAGO_ANULADO')),
    CONSTRAINT pago_recibido_estado_ck CHECK (estado IN ('EN_TRANSITO', 'APLICADO', 'RECHAZADO')),
    CONSTRAINT pago_recibido_asientos_ck CHECK (asientos >= 0),
    -- Aplicado es un hecho con hora: sin ella, la conciliacion no puede decir cuanto
    -- tardo un pago en imputarse, que es la medida de la ventana que esta separacion abre.
    CONSTRAINT pago_recibido_aplicado_ck CHECK ((estado = 'APLICADO') = (aplicado_en IS NOT NULL)),
    -- Y un rechazo dice por que. Un pago cobrado que el libro no admitio y que no explica
    -- nada obliga a quien lo mire a reconstruirlo desde cero.
    CONSTRAINT pago_recibido_motivo_ck CHECK (
        estado <> 'RECHAZADO' OR (motivo IS NOT NULL AND length(btrim(motivo)) >= 5)),
    -- Una anulacion dice QUE pago deshace. Sin ello habria que buscar sus asientos por el
    -- numero del papel, que es texto y no una clave.
    CONSTRAINT pago_recibido_original_ck CHECK (
        (tipo = 'PAGO_ANULADO') = (pago_original_id IS NOT NULL))
);

-- El que lee la consulta de deuda: los pagos de un contribuyente que todavia no se
-- aplicaron. Parcial, porque es la unica pregunta que se hace sobre esta tabla en el
-- camino de una pantalla.
CREATE INDEX pago_en_transito_ix ON pago_recibido (municipalidad_id, contribuyente_id)
    WHERE estado = 'EN_TRANSITO';
-- El que lee la conciliacion del dia.
CREATE INDEX pago_recibido_fecha_ix ON pago_recibido (municipalidad_id, fecha_pago, estado);

COMMENT ON TABLE pago_recibido IS
    'Buzon de entrada de los pagos que la caja publica (P5D, ADR-0026 §3). Una fila por pago, '
    'deduplicada por `pago_id`: es lo que hace que un pago inyectado dos veces produzca UN solo '
    'asiento, y la garantia es del motor y no de un `if`. No es el libro: lo que se imputa se '
    'escribe en `cuenta_corriente_asiento` por `RegistroDeAbonos`, como cualquier otro abono.';
COMMENT ON COLUMN pago_recibido.pago_id IS
    'El identificador que GENERO LA CAJA al cobrar. Un reintento de entrega manda el MISMO uuid, y '
    'por eso se puede deduplicar. Si lo generara el transporte, dos entregas del mismo cobro serian '
    'dos pagos y habria dos asientos.';
COMMENT ON COLUMN pago_recibido.estado IS
    'EN_TRANSITO mientras no se imputo —es el «pago en transito» de ADR-0026 §4, y su hora es '
    '`recibido_en`: quien mire la deuda en ese rato tiene que ver que hay un pago en camino, no un '
    'saldo como si no hubiera pagado—; APLICADO con su hora; RECHAZADO con su motivo, que es dinero '
    'cobrado que el libro no admitio y que alguien tiene que mirar.';
COMMENT ON COLUMN pago_recibido.cuerpo IS
    'El evento entero, tal como llego. No se recompone: dentro de dos anios la orden que lo produjo '
    'podria decir otra cosa, y lo que se recibio tiene que poder explicarse solo.';
COMMENT ON COLUMN pago_recibido.total IS
    'Lo que el pago DIJO que se cobro, copiado y no releido. Es contra lo que la conciliacion del '
    'dia compara lo asentado: si se recalculara del libro, se estaria comprobando que el libro es '
    'igual al libro.';


-- ----------------------------------------------------------------------------
--  2. RLS. Sin valor por omision: sin contexto de tenant, la consulta FALLA.
-- ----------------------------------------------------------------------------

ALTER TABLE pago_recibido ENABLE ROW LEVEL SECURITY;
ALTER TABLE pago_recibido FORCE ROW LEVEL SECURITY;
CREATE POLICY pago_recibido_tenant ON pago_recibido FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);


-- ----------------------------------------------------------------------------
--  3. PRIVILEGIOS
--
--  `kamayuk_app` inserta y actualiza: el pago entra por HTTP —la aplicacion es quien
--  atiende— y su estado cambia al imputarse. NO recibe DELETE, y no lo va a recibir:
--  un pago rechazado se explica y se corrige, no se borra (regla 4, RNF-051).
--
--  A diferencia de la proyeccion de catastro (`V4`), aqui NO hay un rol ingestor
--  aparte, y es deliberado: el ingestor de catastro es un proceso que corre solo y
--  recibe los datos dentro del evento; este buzon lo escribe el propio borde HTTP de
--  la aplicacion, con el token del que publica. Un rol mas seria una credencial mas
--  apuntando al padron para nada.
-- ----------------------------------------------------------------------------

GRANT INSERT, SELECT, UPDATE ON pago_recibido TO kamayuk_app;
GRANT SELECT                  ON pago_recibido TO kamayuk_readonly;

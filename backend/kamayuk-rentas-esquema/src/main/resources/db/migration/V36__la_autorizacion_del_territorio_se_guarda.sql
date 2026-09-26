-- ============================================================================
--  V36 — LA AUTORIZACION DEL TERRITORIO SE GUARDA EN LA LICENCIA (#418)
--
--  QUE PASABA
--  ----------
--  Una licencia cuyo predio no consta en el territorio —hoy el caso normal,
--  porque no hay cartografia cargada—, o cuyo giro no cabe en la zona, solo
--  se emite si una persona lo asume por escrito (#43). Ese escrito se
--  validaba como observacion y se tiraba: `zona_origen = 'DECLARADA'` decia
--  «una persona lo autorizo por escrito» (el COMMENT de V14) y el escrito no
--  estaba en ninguna tabla, ni en la auditoria, ni en el papel. La licencia
--  es un acto que se impugna, y emitida por excepcion su fundamento es
--  justamente esa autorizacion.
--
--  QUE HACE
--  --------
--  Una columna, `autorizacion_territorio`, nulable y del mismo ancho que una
--  observacion (500), y un CHECK: no hay autorizacion sobre un territorio
--  que no se consulto (`NO_COMPROBADA`), porque no habia nada que autorizar.
--
--  Lo que NO hace:
--  · Rellenar hacia atras. Las autorizaciones de las licencias ya emitidas
--    se perdieron y no se pueden reconstruir: quedan nulas, que es lo que
--    la base sabe de ellas.
--  · Exigir la autorizacion cuando `zona_origen = 'DECLARADA'`. Seria la
--    guarda natural, pero V14 dio 'DECLARADA' POR OMISION a todas las filas
--    anteriores, que no tienen autorizacion. Con NOT VALID no se comprueban
--    al crearlo, pero si en cualquier UPDATE que las toque despues: la
--    aplicacion no puede hacerlo (REVOKE), pero una migracion futura que
--    corrija esas filas fallaria por un dato que nunca existio. La regla
--    vive en `TerritorioDeLaLicencia.de`, que es quien la compone.
-- ============================================================================

ALTER TABLE licencia_funcionamiento
    ADD COLUMN autorizacion_territorio varchar(500);

ALTER TABLE licencia_funcionamiento
    ADD CONSTRAINT licencia_autorizacion_territorio_ck
        CHECK (autorizacion_territorio IS NULL OR zona_origen <> 'NO_COMPROBADA');

COMMENT ON COLUMN licencia_funcionamiento.autorizacion_territorio IS
    'Por que se emitio aunque el territorio no lo respaldara, con las palabras'
    ' de quien lo asumio (#418). Nula si el territorio lo respaldaba, si no se'
    ' pregunto, o si la licencia es anterior a V36.';

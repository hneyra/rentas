-- ============================================================================
--  V33 — LA DECLARADA SE GUARDA AL LADO DE LA SELLADA (#362)
--
--  QUE PASABA
--  ----------
--  Cuando un predio tiene valuacion sellada por `catastro`, la determinacion
--  se calcula con ella (#38, AC-2) y `V14` guarda de donde salio: el origen
--  SELLADO, el conjunto y la huella. El autovaluo que el contribuyente
--  DECLARO, en cambio, no tenia columna: el codigo decia «se guarda al lado
--  para que la discrepancia se pueda ver» y la cifra vivia solo en memoria.
--  Ni esta tabla, ni `declaracion_jurada`, ni la auditoria la tenian.
--
--  Y el recalculo lo empeoraba: sin predios en la peticion, el «declarado»
--  se tomaba de `autovaluo` sin mirar el origen, o sea el SELLADO. Declarado
--  100 000 y sellado 180 000 acababan siendo 180 000 y 180 000, y la
--  subvaluacion que la fiscalizacion busca desaparecia sin dejar rastro.
--
--  QUE HACE
--  --------
--  Una columna, `autovaluo_declarado`, nulable, y un CHECK que la ata al
--  origen: solo tiene valor cuando `autovaluo_origen = 'SELLADO'`. En
--  DECLARADO la declarada ES `autovaluo`, y guardarla dos veces invitaria a
--  que las dos difieran. `DetalleDeterminacionPredio` repite la regla al
--  construirse, como ya repite `determinacion_detalle_procedencia_ck`.
--
--  En SELLADO puede seguir siendo nula: un predio que `catastro` valorizo y
--  que nadie declaro no tiene segunda cifra que comparar.
--
--  Lo que NO hace: rellenar hacia atras. Las determinaciones SELLADO ya
--  escritas no guardaron la declarada en ninguna parte, asi que no hay de
--  donde sacarla. Quedan con la columna nula, que es lo que fueron.
-- ============================================================================

ALTER TABLE determinacion_predio_detalle
    ADD COLUMN autovaluo_declarado dinero;

ALTER TABLE determinacion_predio_detalle
    ADD CONSTRAINT determinacion_detalle_declarado_ck
        CHECK (autovaluo_declarado IS NULL
            OR (autovaluo_origen = 'SELLADO' AND autovaluo_declarado >= 0));

COMMENT ON COLUMN determinacion_predio_detalle.autovaluo_declarado IS
    'El autovaluo que declaro el contribuyente cuando el predio se determino con la valuacion '
    'que `catastro` sello (#362). Solo en SELLADO: en DECLARADO la declarada es `autovaluo`. '
    'Nula si no habia declaracion, o si la fila es anterior a V33. Es lo que la fiscalizacion '
    'contrasta: la diferencia con `autovaluo` es la subvaluacion declarada';

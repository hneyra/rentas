-- ============================================================================
--  V41 — EL SORTEO DE LA MUESTRA ES UNA FILA, NO SE DEDUCE DE SUS EFECTOS (#346)
--
--  QUE PASABA
--  ----------
--  `GenerarMuestra` promete que el sorteo es un acto unico —«una muestra no se vuelve a
--  sortear», 409— y lo media contando filas de `programa_muestra`. Un sorteo en el que no
--  entra nadie (todos excluidos por otro programa o por acta) no escribe ninguna, asi que
--  el mismo programa se podia volver a sortear meses despues y salir con otra muestra. Y
--  dos `POST` simultaneos pasaban los dos la comprobacion: el segundo chocaba contra
--  `programa_muestra_uq` y salia 500, no 409.
--
--  QUE CAMBIA
--  ----------
--  El sorteo es una entidad con identidad propia: UNA fila por programa, con su fecha y
--  el reparto que `ResultadoDelSorteo` ya cuadraba —detectados = sorteados + excluidos por
--  otro programa + excluidos por acta—. Un sorteo vacio sigue siendo un sorteo, y la clave
--  primaria es la que decide la carrera: dos sorteos del mismo programa chocan aqui, y el
--  repositorio lo traduce a `MuestraYaSorteada` (409).
--
--  SOLO SE INSERTA (regla 4, RNF-051): un sorteo no se corrige ni se borra; para otra
--  muestra, otro programa.
--
--  NACE VACIA, y es deliberado. Los programas ya sorteados no se siembran desde
--  `programa_muestra`: FORCE ROW LEVEL SECURITY no deja al migrador leer esas filas, y un
--  sorteo vacio anterior a esta version no dejo ninguna. Para esos, `GenerarMuestra` sigue
--  mirando tambien si el programa ya tiene filas en la muestra.
-- ============================================================================

CREATE TABLE programa_sorteo (
    municipalidad_id               bigint       NOT NULL REFERENCES municipalidad (id),
    programa_id                    bigint       NOT NULL,
    fecha_sorteo                   date         NOT NULL,
    detectados                     integer      NOT NULL,
    sorteados                      integer      NOT NULL,
    sorteados_sin_titular          integer      NOT NULL,
    excluidos_por_otro_programa    integer      NOT NULL,
    excluidos_por_acta             integer      NOT NULL,
    observacion                    text         NOT NULL,
    usuario_registro               varchar(60)  NOT NULL,
    fecha_registro                 timestamptz  NOT NULL,

    -- LA GARANTIA: un programa, un sorteo. Ver la cabecera.
    CONSTRAINT programa_sorteo_pk PRIMARY KEY (municipalidad_id, programa_id),
    CONSTRAINT programa_sorteo_programa_fk FOREIGN KEY (municipalidad_id, programa_id)
        REFERENCES programa_fiscalizacion (municipalidad_id, id),
    CONSTRAINT programa_sorteo_no_negativos_ck CHECK (
        detectados >= 0 AND sorteados >= 0 AND sorteados_sin_titular >= 0
        AND excluidos_por_otro_programa >= 0 AND excluidos_por_acta >= 0),
    -- El mismo cuadre que `ResultadoDelSorteo` exige al construirse: cada predio detectado
    -- cae en exactamente una casilla.
    CONSTRAINT programa_sorteo_cuadra_ck CHECK (
        detectados = sorteados + excluidos_por_otro_programa + excluidos_por_acta),
    CONSTRAINT programa_sorteo_sin_titular_ck CHECK (sorteados_sin_titular <= sorteados),
    CONSTRAINT programa_sorteo_observacion_ck CHECK (length(btrim(observacion)) >= 1)
);

COMMENT ON TABLE programa_sorteo IS
    'El sorteo de la muestra de un programa de fiscalizacion, como acto (#346): una fila por '
    'programa, con el reparto del padron examinado. Es lo que dice «ya se sorteo», tambien '
    'cuando no entro nadie. Solo se inserta.';
COMMENT ON COLUMN programa_sorteo.detectados IS
    'Los predios que la deteccion entrego para el criterio del programa el dia del sorteo. '
    'Suman exactamente sorteados + excluidos_por_otro_programa + excluidos_por_acta.';

-- ----------------------------------------------------------------------------
--  RLS. Sin valor por omision: sin contexto de tenant, la consulta FALLA.
-- ----------------------------------------------------------------------------

ALTER TABLE programa_sorteo ENABLE ROW LEVEL SECURITY;
ALTER TABLE programa_sorteo FORCE ROW LEVEL SECURITY;
CREATE POLICY programa_sorteo_tenant ON programa_sorteo FOR ALL TO PUBLIC
    USING (municipalidad_id = current_setting('app.municipalidad_id')::bigint)
    WITH CHECK (municipalidad_id = current_setting('app.municipalidad_id')::bigint);

-- ----------------------------------------------------------------------------
--  Privilegios. `kamayuk_app` lee e inserta, y nada mas (regla 4). El escaner de
--  fuentes vigila ademas las dos cadenas (`TablasDeRentas.PROTEGIDAS` e `INMUTABLES`).
-- ----------------------------------------------------------------------------

GRANT SELECT, INSERT ON programa_sorteo TO kamayuk_app;
GRANT SELECT ON programa_sorteo TO kamayuk_readonly;

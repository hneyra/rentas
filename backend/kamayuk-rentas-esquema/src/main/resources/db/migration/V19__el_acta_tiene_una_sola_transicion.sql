-- ============================================================================
--  V19 — EL ACTA TIENE UNA SOLA TRANSICION, Y ES ANULARLA (#214)
--
--  LO QUE SE MIDIO
--  ---------------
--  `EstadoDeActa` declaraba CINCO valores -ABIERTA, LIQUIDADA, RELIQUIDADA,
--  TRANSFERIDA, ANULADA-, `acta_fiscalizacion_estado_check` admitia los cinco,
--  `V1` le concedia UPDATE sobre la tabla entera a `kamayuk_app` ... y este
--  sistema escribia UNO. No habia en `src/main` una sola sentencia
--  `UPDATE acta_fiscalizacion`: el acta que se liquidaba se quedaba ABIERTA, la
--  que se transferia al padron tambien, y `acta_fisc_transferencia_ck` -que
--  exige `fecha_transferencia` y `usuario_transferencia` cuando el estado es
--  TRANSFERIDA- no la podia alcanzar nadie.
--
--  POR QUE NO SE ANADEN LAS TRES ESCRITURAS QUE FALTABAN
--  ----------------------------------------------------
--  Porque las tres son DERIVABLES, y este sistema ya las deriva:
--
--    LIQUIDADA    es «el acta tiene liquidacion», y es lo que
--                 `LiquidacionRepository.ultimaVersionDeActa` contesta hoy para
--                 rechazar una segunda liquidacion (`ActaYaLiquidada`).
--    RELIQUIDADA  es «tiene mas de una version», y es lo que
--                 `versionesDeActa` contesta para numerar la siguiente.
--    TRANSFERIDA  es «su liquidacion tiene resolucion de determinacion», y es lo
--                 que `resoluciones.deLiquidacion` contesta para rechazar una
--                 segunda transferencia (`LiquidacionYaTransferida`).
--
--  Escribirlas ademas en esta columna dejaria DOS verdades sobre el mismo
--  hecho, y la que se lea en una pantalla seria la que nadie recalculo. Es
--  exactamente lo que `ReliquidarFiscalizacion` se nego a hacer con la version
--  anterior -«ni siquiera se marca la anterior como sustituida»-, lo que #481
--  decidio para la columna «Estado» de la muestra y lo que #18 dejo escrito en
--  `ficha_catastral`. Y es el patron que `V30`-`V34` y `V39` de `sgtm`
--  aplicaron cinco veces seguidas: la columna de estado que decia ABIERTO para
--  siempre se retira, y el estado se lee de los hechos.
--
--  LA QUE NO SE DERIVA DE NADA
--  ---------------------------
--  ANULADA. No hay ninguna fila en ninguna tabla que diga que una visita no
--  vale: es un acto de la administracion y no la consecuencia de otro. Por eso
--  es la unica que se escribe, y por eso la columna se queda -no se retira como
--  en V30-V34-: sigue haciendo falta para contestar «esta viva o no», que es lo
--  UNICO que hoy le preguntan `prediosConActaEnElPrograma`,
--  `prediosConActaEnElEjercicio` y `unidadesConActaViva`, las tres con
--  `estado <> 'ANULADA'` — tres filtros que hasta hoy no descartaban nada.
--
--  QUE HACE ESTA MIGRACION
--  -----------------------
--  1. Retira `acta_fisc_transferencia_ck` y las dos columnas que solo esa
--     restriccion tocaba. Son provablemente vacias: ningun INSERT de este
--     sistema las nombra -`ActaFiscalizacionRepositoryJdbc.insertar` lista sus
--     dieciseis columnas y no estan- y no existe ningun UPDATE. Una columna que
--     nadie puede llenar es una promesa, y la promesa era «aqui consta quien
--     transfirio»: quien transfirio consta en `resolucion_determinacion_fisc` y
--     en `auditoria`, que es donde se escribe de verdad.
--  2. Estrecha `acta_fiscalizacion_estado_check` a los dos valores alcanzables.
--  3. Estrecha el privilegio: `kamayuk_app` pierde el UPDATE sobre la TABLA y
--     recibe el UPDATE sobre la COLUMNA `estado`. Es el mismo trato que `V1` le
--     da a `declaracion_jurada` -`GRANT UPDATE (estado)`-, y por el mismo
--     motivo: lo que el fiscalizador midio en campo no se corrige en la base.
--     Corregir un area mal medida es levantar otra acta -otra version, que
--     `acta_fisc_version_uq` admite-, no reescribir la que firmo el titular.
--
--  POR QUE EL `CHECK` VA `NOT VALID`
--  ---------------------------------
--  Por lo mismo que `V10`: el migrador corre sin contexto de tenant y esta
--  tabla tiene FORCE ROW LEVEL SECURITY, asi que una validacion no puede
--  afirmar nada sobre lo que hay en una instalacion en marcha. `NOT VALID`
--  sigue comprobando cada INSERT y cada UPDATE, que es lo que hace falta.
-- ============================================================================

-- 1. La transferencia no deja rastro en el acta: lo deja en su resolucion.
ALTER TABLE acta_fiscalizacion DROP CONSTRAINT acta_fisc_transferencia_ck;
ALTER TABLE acta_fiscalizacion DROP COLUMN fecha_transferencia;
ALTER TABLE acta_fiscalizacion DROP COLUMN usuario_transferencia;

-- 2. Los dos valores que alguien puede escribir de verdad.
ALTER TABLE acta_fiscalizacion DROP CONSTRAINT acta_fiscalizacion_estado_check;
ALTER TABLE acta_fiscalizacion
    ADD CONSTRAINT acta_fiscalizacion_estado_check
        CHECK (estado IN ('ABIERTA', 'ANULADA'))
    NOT VALID;

-- 3. Del acta solo se puede mover el estado.
REVOKE UPDATE ON acta_fiscalizacion FROM kamayuk_app;
GRANT UPDATE (estado) ON acta_fiscalizacion TO kamayuk_app;

COMMENT ON COLUMN acta_fiscalizacion.estado IS
    'Si la visita vale o alguien dijo que no vale, y nada mas (#214). ABIERTA es como nace toda '
    'acta; ANULADA es terminal y es la unica transicion que este sistema escribe. Que el acta '
    'este liquidada, reliquidada o transferida NO se guarda aqui: se deriva de que exista su '
    'liquidacion, de que tenga mas de una version y de que su liquidacion tenga resolucion de '
    'determinacion. Guardarlo ademas dejaria dos verdades sobre el mismo hecho, y la que se lea '
    'en pantalla seria la que nadie recalculo.';

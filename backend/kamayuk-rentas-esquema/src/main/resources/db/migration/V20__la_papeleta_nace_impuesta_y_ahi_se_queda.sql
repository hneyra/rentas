-- ============================================================================
--  V20 — LA PAPELETA NACE IMPUESTA Y AHI SE QUEDA (#243)
--
--  LO QUE SE MIDIO
--  ---------------
--  `EstadoDePapeleta` declara SIETE valores -IMPUESTA, NOTIFICADA, RESUELTA,
--  PAGADA, COACTIVA, ANULADA, PRESCRITA-, `papeleta_estado_check` admite los
--  siete, `V1` le concede UPDATE sobre la TABLA ENTERA a `kamayuk_app` ... y
--  este sistema escribe UNO. Sobre el `src/main` de los diecisiete modulos:
--
--    INSERT INTO papeleta ....... uno, y pone `papeleta.estado().name()`
--    quien construye una Papeleta  `nuevaDeTransito` y `nuevaAdministrativa`,
--                                  las dos con IMPUESTA; el record no tiene
--                                  ningun metodo de transicion
--    UPDATE papeleta ............ UNO SOLO: `SET numero = :numeroNuevo` (#46)
--
--  De ahi que `count(*) FILTER (WHERE p.estado = 'PAGADA')` y su gemelo de
--  'COACTIVA' -los dos recuentos que #184 conecto a `tra-panel`- valgan CERO
--  para siempre en una instalacion nueva, igual que el de 'NOTIFICADA' que #222
--  retiro de la pantalla.
--
--  POR QUE EL `CHECK` NO SE ESTRECHA, AL REVES QUE EN V19
--  -----------------------------------------------------
--  Porque aqui la columna no la escribe solo este sistema. `papeleta` es la
--  tabla que un padron MIGRADO trae llena, y sus filas pueden venir con
--  'PAGADA' o 'COACTIVA' puestas por el sistema anterior.
--
--  Y esas filas NO SE PUEDEN NORMALIZAR desde una migracion: `papeleta` tiene
--  FORCE ROW LEVEL SECURITY y el migrador corre sin contexto de tenant, asi que
--  no puede ni leerlas ni reescribirlas -es la misma razon por la que `V10` y
--  `V19` ponen sus CHECK en NOT VALID-. Un CHECK estrechado ademas las volveria
--  intocables: `UPDATE papeleta SET numero` sobre una fila migrada 'PAGADA'
--  fallaria, porque un NOT VALID sigue comprobando toda fila que se actualiza.
--
--  Asi que el reparto es este, y esta escrito en el enumerado:
--    el ENUMERADO es el vocabulario de LECTURA -tiene que poder leer lo que
--      otro sistema dejo escrito, o `EstadoDePapeleta.valueOf(...)` revienta el
--      padron entero-;
--    la PANTALLA no dibuja como cifra lo que este sistema no produce, que es lo
--      que #243 corrige en `tra-panel`.
--
--  QUE SI HACE ESTA MIGRACION
--  --------------------------
--  Estrecha el privilegio, que es la mitad que no tiene ese problema. Hoy
--  `kamayuk_app` puede UPDATE sobre CUALQUIER columna de `papeleta`: la placa,
--  la fecha de la infraccion, la hora, el lugar, el importe a pagar. Es decir,
--  lo que el inspector escribio en la calle y firmo el infractor. Y de todo eso
--  la aplicacion escribe UNA columna, `numero`.
--
--  Se le deja `numero` -el cambio de numeracion de #46- y `estado`, que es la
--  unica otra columna que un acto futuro puede mover legitimamente y solo a los
--  dos valores que no se derivan de nada: ANULADA y PRESCRITA. Corregir un dato
--  mal tomado en campo no es un UPDATE: es anular la papeleta y levantar otra,
--  igual que `V19` decidio para el acta de fiscalizacion y `V1` para
--  `declaracion_jurada`.
-- ============================================================================

REVOKE UPDATE ON papeleta FROM kamayuk_app;
GRANT UPDATE (numero, estado) ON papeleta TO kamayuk_app;

COMMENT ON COLUMN papeleta.estado IS
    'En que punto esta la papeleta. Este sistema escribe UNO de los siete valores: IMPUESTA, en '
    'el INSERT, y nunca lo mueve (#243) — el unico UPDATE papeleta de src/main es SET numero. '
    'NOTIFICADA, RESUELTA, PAGADA y COACTIVA se DERIVAN de otros hechos y no se escriben aqui: la '
    'diligencia de la resolucion de gerencia, la existencia de la resolucion, el libro de cuenta '
    'corriente y el pase a coactiva del valor. Escribirlos ademas dejaria dos verdades sobre el '
    'mismo hecho. ANULADA y PRESCRITA no se derivan de nada —son actos— y todavia no existe el '
    'acto que las escriba. El CHECK sigue admitiendo los siete porque un padron MIGRADO puede '
    'traerlos, y bajo FORCE RLS ninguna migracion puede normalizar esas filas.';

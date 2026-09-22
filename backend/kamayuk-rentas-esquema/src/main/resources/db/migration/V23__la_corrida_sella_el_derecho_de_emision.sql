-- ============================================================================
--  V23 — LA CORRIDA SELLA EL DERECHO DE EMISION QUE APLICO (#312)
--
--  QUE FALTABA, Y POR QUE NADIE PODIA VERLO
--  ----------------------------------------
--  `corrida_predial` tenia DIECIOCHO columnas y ninguna era el derecho de emision.
--  La corrida lo APLICA —entra en `monto_emitido` a traves del `totalAPagar()` de
--  cada contribuyente, que es el impuesto insoluto MAS el derecho— y no lo SELLA.
--  O sea que la unica huella que dejaba de esa cifra estaba SUMADA dentro de otra,
--  de la que no se puede volver a separar.
--
--  Y lo que guardaba del conjunto tampoco alcanzaba: `conjunto varchar(60)` es su
--  NOMBRE —«2026 v1»—, y `CuadroPredialParametrizado.delConjunto` pide un
--  `conjunto_id`. No hay lectura por nombre. En una corrida que no determino a
--  nadie ese nombre es ademas la cadena VACIA, asi que ni siquiera hay nombre.
--
--  La consecuencia medida en #271: el unico camino que quedaba para publicar el
--  «Derecho de emision» del panel era `vigenteEn(ejercicio)` —el conjunto sellado
--  de HOY, que no tiene por que ser el que la corrida uso—. Esa es la cifra
--  equivocada de la peor clase, porque PARECE correcta: nada en la pantalla dice
--  que el numero sale de una ordenanza posterior a la emision que se esta leyendo.
--
--  Es la regla 6 sin cumplir para la corrida. «Recalcular 2027 en 2037 debe dar el
--  mismo centimo» valia para cada `determinacion` —que si sella su `conjunto_id`
--  desde `V1`— y NO para el resumen de la corrida que las produjo.
--
--  POR QUE SON DOS COLUMNAS Y NO UNA
--  ---------------------------------
--  Se midio antes de decidirlo, y la respuesta es que con una sola no se cierra
--  nada. `derecho_emision` conserva la cifra que se aplico; `conjunto_id` conserva
--  DE DONDE salio. Sin el segundo, la corrida guardaria un importe que nadie puede
--  contrastar contra la ordenanza que lo fijo —ni recomponer el resto del cuadro:
--  la UIT, los tramos, el minimo y los vencimientos con que se emitio—, y cualquier
--  otra cifra de la corrida seguiria sin ser reproducible. Es exactamente el par
--  que `determinacion` lleva desde `V1` (`conjunto_id`) y que `GET
--  /rentas/predial/determinaciones` publica junto al nombre.
--
--  LAS FILAS ANTERIORES A V23 SE QUEDAN EN NULO, Y ES UNA DECISION
--  --------------------------------------------------------------
--  NULO significa «esta corrida no lo guardo», nunca «no se cobro derecho de
--  emision» —que es lo que diria un CERO, y es falso: se cobro, y esta dentro de
--  `monto_emitido`—. No se rellena hacia atras, y no es por no poder: es que NO HAY
--  NADA CIERTO QUE ESCRIBIR. El valor de aquella corrida salio de un conjunto que
--  la fila no nombra, y leerlo del vigente hoy es el defecto exacto que esta
--  migracion existe para cerrar. Mismo trato que `V10` le dio a `motivo_anulacion`,
--  `V14` a `zona_del_territorio` y `V21` a `determinacion.modalidad`.
--
--  POR QUE LOS DOS `CHECK` SE VALIDAN, Y NO HACEN FALTA `NOT VALID`
--  ---------------------------------------------------------------
--  Las dos columnas NACEN aqui, asi que toda fila existente las tiene nulas y
--  ninguna puede violar un `CHECK` que admite el nulo. `V10` necesito el escape
--  porque su columna se anadia sobre datos que ya podian contradecirla; aqui no hay
--  ese riesgo, y emitirlo `NOT VALID` sin necesidad dejaria una restriccion que
--  nadie valida nunca.
--
--  LO QUE LA BASE **NO** PUEDE VIGILAR, Y QUIEN LO VIGILA
--  ------------------------------------------------------
--  Que una corrida NUEVA que determino a alguien no pueda volver a quedarse sin
--  sellar. No hay `CHECK` que lo diga: una restriccion no distingue una fila
--  insertada hoy de una anterior a V23, y `NOT NULL` dejaria sin migrar toda base
--  con corridas —y ademas seria falso, porque una corrida que no determino a nadie
--  no tiene ningun conjunto que sellar—. Lo vigila el DOMINIO, en el invariante de
--  `CorridaDeEmision`: las dos piezas viajan juntas o no viaja ninguna.
--
--  D-02b SIGUE ABIERTA, Y ESTO NO LA CIERRA
--  ----------------------------------------
--  `DERECHO_EMISION_PREDIAL` es de ordenanza local y hoy NO lo publica nadie, asi
--  que hoy una corrida que determine a alguien ni siquiera llega a terminar: el
--  cuadro revienta con `ParametroAusente` nombrando la llave. Sellar la columna no
--  hace aparecer el valor — hace que, el dia que exista, la corrida lo CONSERVE en
--  vez de aplicarlo y olvidarlo.
-- ============================================================================

-- El identificador del conjunto sellado con que la corrida emitio. Lo que la fila
-- guardaba hasta aqui era `conjunto`, que es su NOMBRE y no sirve para volver a
-- leerlo: no hay lectura por nombre, y en una corrida que no determino a nadie ese
-- nombre es la cadena vacia.
ALTER TABLE corrida_predial ADD COLUMN conjunto_id bigint;

-- El derecho de emision que la corrida aplico, en soles. `dinero` es
-- `numeric(15,2)` (regla 1, RNF-055): un importe en coma flotante pierde el
-- centimo sin dar error en ningun sitio.
ALTER TABLE corrida_predial ADD COLUMN derecho_emision dinero;

-- No hay derecho de emision negativo: seria una municipalidad devolviendo dinero
-- por emitir. Cero SI se admite, y no es lo mismo que nulo: una ordenanza puede
-- fijarlo en cero, y entonces la corrida lo sella como cero.
ALTER TABLE corrida_predial
    ADD CONSTRAINT corrida_predial_derecho_emision_ck
        CHECK (derecho_emision IS NULL OR (derecho_emision)::numeric >= (0)::numeric);

-- **Las dos piezas viajan juntas.** Una cifra sellada cuyo conjunto de origen se
-- desconoce no se puede contrastar contra nada —vuelve a ser un numero sin fuente,
-- que es el defecto que esta migracion cierra—, y un conjunto sin su cifra deja el
-- campo del panel sin nada que ensenar aunque la corrida si lo haya sabido. Los
-- tres estados legitimos quedan cubiertos: la corrida anterior a V23 (las dos
-- nulas), la que no determino a nadie y por tanto no resolvio ningun conjunto (las
-- dos nulas), y la que emitio (las dos escritas).
ALTER TABLE corrida_predial
    ADD CONSTRAINT corrida_predial_sello_completo_ck
        CHECK ((conjunto_id IS NULL) = (derecho_emision IS NULL));

COMMENT ON COLUMN corrida_predial.conjunto_id IS
    'El identificador del conjunto sellado de `normativa` con que la corrida emitio, que es lo '
    'que permite volver a leer su cuadro —UIT, tramos, minimo, derecho de emision y '
    'vencimientos— sin depender del conjunto vigente hoy (ARQ-09 §3). `conjunto` guarda su '
    'NOMBRE, que no sirve para eso. NULO significa «fila anterior a V23» o «esta corrida no '
    'determino a nadie», nunca «se emitio sin conjunto» (#312).';

COMMENT ON COLUMN corrida_predial.derecho_emision IS
    'El derecho de emision mecanizada que la corrida aplico a cada cuenta, en soles, tal como se '
    'lo dio el conjunto sellado el dia de la emision. Va SUMADO dentro de `monto_emitido` —que es '
    'impuesto mas derecho— y de ahi no se puede volver a separar; por eso se sella aparte. NULO '
    'significa «esta corrida no lo guardo» y NO «no se cobro derecho», que es lo que diria un '
    'cero: se cobro, y esta dentro de `monto_emitido`. Es de ordenanza local y D-02b sigue '
    'abierta: sellarlo no hace aparecer el valor, hace que el dia que exista la corrida lo '
    'conserve (#312).';

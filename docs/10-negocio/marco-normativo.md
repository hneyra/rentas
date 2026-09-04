# NEG-02 — Marco normativo

## 1. Normas que el manual cita

El manual (cap. 3, apertura) declara que el sistema está elaborado sobre:

| Norma | Materia |
|---|---|
| **D. Leg. N.º 776** — Ley de Tributación Municipal, y modificatorias | Impuestos municipales: predial, alcabala, patrimonio vehicular, espectáculos, juegos, apuestas. Tasas y arbitrios |
| **TUO del Código Tributario**, D.S. N.º 135-99-EF | Obligación tributaria, determinación, valores, notificación, prescripción, cobranza coactiva, sanciones |
| **Ley N.º 27616** — Ley que restituye recursos a los gobiernos locales | Modifica artículos de la Ley de Tributación Municipal |
| **Ley Orgánica de Municipalidades** | Atribución para crear tasas y derechos |

El manual también nombra, sin citarlas: el **Consejo Nacional de Catastro** (fichas oficiales de
las que parte el registro catastral), los códigos **CIIU** para giros de negocio, el
**Formulario Único de Edificaciones (FUE)** y el **Reglamento Nacional de Tránsito** (códigos de
infracción).

> Las referencias son las del manual, que documenta un sistema en operación desde 2010. **Antes
> de implementar cualquier cálculo hay que verificar la vigencia y el texto actual de cada norma
> citada**, incluidas las que la hayan sustituido.

## 2. Mapa normativo: qué falta, quién lo fija y quién lo espera

El manual describe qué calcula el sistema; no con qué cifras. Cada línea de este mapa es un dato
sin el cual una regla de cálculo no se puede escribir. **Todas siguen `‹VERIFICAR›` en su valor**:
lo que este mapa añade no es la cifra —eso es [E-3](../00-gobierno/plan-de-desbloqueo-D-02.md)—,
sino **de qué norma sale, qué parte de D-02 la bloquea y qué issue la está esperando**.

Sin esa tabla, asignar `D-02a`, `D-02b` o `D-02c` a un issue era una conjetura (hallazgo H-3 de
[GOB-03](../00-gobierno/plan-de-desbloqueo-D-02.md)). Con ella, el reetiquetado sale solo.

### 2.1 Cómo se decide la parte

La parte **no la fija el tributo: la fija quién produce el valor y qué hace falta para tenerlo**.

| Parte | Quién lo fija | Qué hace falta para tenerlo | Depende de D-01 |
|---|---|---|---|
| **D-02a** | Una **norma nacional publicada**: ley, decreto supremo o resolución ministerial | Buscarlo, transcribirlo y firmarlo. **No se decide** | **No** |
| **D-02b** | Una **ordenanza de la municipalidad en materia tributaria** | La ordenanza **y su acuerdo de ratificación provincial** cuando la emite una distrital (LOM art. 40). Sin ratificación no habilita emisión | Sí, o una municipalidad de demostración (E-6) |
| **D-02c** | Un acto de la propia municipalidad que **no** es ordenanza tributaria ratificada: ordenanza no tributaria, resolución del ejecutor, TUPA de servicio, calendario | Producirlo y firmarlo. Ni se busca en El Peruano ni se ratifica | Sí |

Reglas de lectura del mapa:

- La columna **Parte** admite `D-02a`, `D-02b` o `D-02c`, y puede llevar un `‹confirmar: …›` cuando
  la clasificación depende de una pregunta legal abierta. Lleva `‹POR CLASIFICAR: …›`, con su
  motivo, cuando ni eso se puede afirmar: es trabajo de E-3, y **se ve**.
- La columna **Issues** nombra los que **llevan la etiqueta** de esa parte, o dice `ninguno
  todavía — …` **con su motivo**: una fila que no dice a quién bloquea, ni por qué no bloquea a
  nadie, no se puede comprobar. Es la mitad de la comprobación cruzada; la otra mitad es §2.8.
- Un dato con **dos fuentes** se clasifica por la que bloquea la emisión, y la otra se anota en la
  columna de la norma. La partición sigue siendo una función: **una fila, una parte**.

### 2.2 Impuesto predial

| # | Dato | Norma que lo fija | Parte | Issues |
|---|---|---|---|---|
| 1 | UIT del ejercicio | Decreto supremo anual del MEF que fija la UIT | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; la UIT está firmada y publicada en el derivado |
| 2 | Tramos del autovalúo en UIT y alícuota de cada tramo | TUO de la Ley de Tributación Municipal (D.S. 156-2004-EF) art. 13 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; los tramos están firmados y publicados en el derivado |
| 3 | Deducción del pensionista: monto en UIT y requisitos | TUO LTM art. 19 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; la deducción está firmada y publicada en el derivado |
| 4 | Deducción del adulto mayor no pensionista, si aplica | Ley 30490 y sus modificatorias, sobre el art. 19 del TUO LTM `‹confirmar artículo vigente›` | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; la deducción está firmada y publicada en el derivado |
| 5 | Impuesto mínimo, si existe | TUO LTM art. 13, expresado como porcentaje de la UIT | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el mínimo está firmado y publicado en el derivado |
| 6 | Vencimientos: pago al contado y cuatro cuotas trimestrales | TUO LTM art. 15. La prórroga que una ordenanza local pueda dar es otro dato, y es D-02c | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y todavía sin fila en el derivado publicable |

### 2.3 Valuación del predio

| # | Dato | Norma que lo fija | Parte | Issues |
|---|---|---|---|---|
| 7 | Tabla de **valores unitarios** oficiales de edificación por categoría (**A–I**, nueve, leídas en el Anexo I.2), partida **y año de construcción** | Resolución anual del sector Vivienda, conforme al TUO LTM art. 11: para 2026, la R.M. 277-2025-VIVIENDA, art. 1 y Anexo I (I.1 a I.4, uno por región). **La tabla actual no tiene la dimensión «año de construcción»** (hallazgo H-4), y el anexo leído no la tiene tampoco: cruza categoría × **3 partidas** | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; la segunda firma llegó el 2026-08-29 y lo que este dato espera es el derivado publicable, cargarlo —el vocabulario de partidas quedo decidido el 2026-08-30 con `V59`: tres para el cuadro de la norma, siete para el formulario de la ficha— —tres en el Anexo I.2, siete en el esquema— y las otras tres regiones (GOB-03, H-14): trabajo, no decisión |
| 8 | **Aranceles** de terreno por vía y ejercicio | Planos arancelares aprobados por el sector Vivienda (TUO LTM art. 11). Norma nacional, pero **sus valores están referidos a las vías de cada localidad**: la transcripción es por municipalidad y necesita #16 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y sin fila en el derivado, porque la transcripción es por municipalidad y espera al catálogo vial |
| 9 | Tabla de **depreciación** por **uso de la edificación**, material, antigüedad y estado de conservación | Anexo I del Reglamento Nacional de Tasaciones (R.M. 172-2016-VIVIENDA), Tablas 01 a 04; la resolución anual de valores unitarios remite a él en su art. 4 | D-02a | ninguno todavía — **cerrado el 2026-08-29**: `V57` le dio a `depreciacion` su dimensión de uso y sus 492 filas ya se publican desde el corpus, así que a esta fila no le queda nada que esperar (GOB-03, H-15) |
| 10 | Valorización de **otras instalaciones** y obras complementarias, y el **factor de oficialización** que multiplica el resultado | Resolución anual del sector Vivienda, conforme al TUO LTM art. 11: para 2026, la R.M. 277-2025-VIVIENDA, cuyo art. 2 aprueba el Instructivo (Anexo II, que fija `Fo = 0,68`) y cuyo art. 3 aprueba los valores unitarios a costo directo (Anexo III, de uso **opcional** según la propia resolución). La metodología es la del Reglamento Nacional de Tasaciones, R.M. 172-2016-VIVIENDA art. 31 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el factor de oficialización tiene fuente y su archivo sigue en TRANSCRITO, a falta de la segunda firma (D-11) |

### 2.4 Arbitrios

| # | Dato | Norma que lo fija | Parte | Issues |
|---|---|---|---|---|
| 11 | Tasas de limpieza pública, relleno sanitario, parques y jardines y serenazgo, por sector y uso | Ordenanza municipal (TUO LTM arts. 68-69), ratificada por el concejo provincial cuando la emite una distrital (LOM art. 40) | D-02b | #189 |
| 12 | Criterios de distribución del costo del servicio | La misma ordenanza, con la justificación del costo que exige el TUO LTM art. 69 | D-02b | #189 |
| 13 | Descuento por pago anual adelantado que menciona el manual | Ordenanza municipal | D-02b | #189 |
| 14 | Inafectaciones: predios sin servicio de limpieza, parques o relleno | Ordenanza municipal, dentro del marco del TUO LTM | D-02b | #189 |

### 2.5 Otros tributos

| # | Dato | Norma que lo fija | Parte | Issues |
|---|---|---|---|---|
| 15 | Alcabala: alícuota, tramo inafecto en UIT, exoneraciones (primera venta de constructora, gobiernos, bomberos), **y el índice con que se ajusta el autovalúo del predio** antes de compararlo con el valor de transferencia | TUO LTM arts. 21 a 29; el ajuste, art. 24: IPM de Lima Metropolitana que publica el INEI | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y todavía sin fila en el derivado publicable, y del índice del art. 24 no hay ni archivo del corpus: es lo que deja «Impuesto de alcabala» sin poder liquidar (ver #432) |
| 16 | Patrimonio vehicular: alícuota, años afectos, tabla de valores referenciales del MEF | TUO LTM arts. 30 a 37; la tabla, por resolución ministerial anual del MEF | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el cuadro de 2026 ya se carga entero, y lo que queda es la categoría del vehículo (GOB-03, H-16) |
| 17 | Espectáculos públicos no deportivos: alícuotas por tipo | TUO LTM arts. 54 a 59 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y todavía sin fila en el derivado publicable |
| 18 | Anuncios y propaganda: tasas por tipo y dimensión | Ordenanza municipal `‹confirmar si su ordenanza es materia tributaria y por tanto se ratifica (LOM art. 40)›` | D-02b | #199 |

### 2.6 Recargos, plazos y sanciones

| # | Dato | Norma que lo fija | Parte | Issues |
|---|---|---|---|---|
| 19 | Interés moratorio: tasa vigente por periodo y forma de cálculo | TUO del Código Tributario art. 33: para los tributos de los gobiernos locales **la TIM la fija una ordenanza municipal**, con tope en la que establece la SUNAT | D-02b | ninguno todavía — lo consume la política de mora de #22, que cerró sin cifras |
| 20 | Reajuste: índice aplicable y momento de aplicación | TUO LTM art. 15, inc. b: variación acumulada del IPM que publica el INEI | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y todavía sin fila en el derivado publicable |
| 21 | Plazo de prescripción y sus causales de interrupción y suspensión | TUO del Código Tributario arts. 43 a 46 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; los plazos están firmados y publicados en el derivado |
| 22 | Plazos de notificación y de inicio de la cobranza coactiva | TUO del Código Tributario arts. 104 y 106, y Ley 26979 | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; los plazos están firmados y publicados en el derivado |
| 23 | Costas y gastos del procedimiento coactivo: aranceles vigentes | Ley 26979: las costas se liquidan conforme al arancel aprobado por la entidad | `D-02c ‹confirmar: falta saber quién aprueba ese arancel; si es una ordenanza tributaria ratificada, la fila pasa a D-02b›` | #193 |
| 24 | Tabla de infracciones de tránsito: código, porcentaje de la UIT, medida y puntos | Reglamento Nacional de Tránsito, D.S. 016-2009-MTC y su cuadro de infracciones `‹confirmar el anexo vigente›` | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y todavía sin fila en el derivado publicable |
| 25 | Cuadro único de infracciones y sanciones administrativas (CUIS) de la municipalidad | Ordenanza municipal `‹confirmar si se ratifica: la sanción administrativa no es tributo, y LOM art. 40 alcanza a la materia tributaria›` | D-02b | #196 |
| 26 | Descuentos por pronto pago de papeletas | Ordenanza municipal de beneficios, sin ratificación | D-02c | #195, #196 |
| 27 | Multa tributaria por declarar fuera de plazo | TUO del Código Tributario art. 176 y sus tablas de infracciones y sanciones `‹confirmar la tabla que corresponde a un gobierno local›`. El régimen de gradualidad, si la municipalidad lo aprueba, es local (D-02c) | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el archivo está firmado y todavía sin fila en el derivado publicable |

### 2.7 Las filas que faltaban, y las que se añadieron después

El hallazgo H-3 decía que la partición no era una función sobre los 27 datos. Dos de los cuatro
huecos eran de clasificación —aranceles y alcabala sí tenían fila, y ahora tienen parte— pero los
otros dos **bloqueaban un issue sin tener línea en este mapa**. Aquí están:

| # | Dato | Norma que lo fija | Parte | Issues |
|---|---|---|---|---|
| 28 | Interés del convenio de fraccionamiento y número máximo de cuotas | Ordenanza municipal que aprueba el reglamento de fraccionamiento, en el marco del TUO del Código Tributario art. 36 | D-02b | #191 |
| 29 | Derecho de trámite del TUPA para la licencia de edificación | Ordenanza que aprueba el TUPA, con su ratificación cuando corresponde | D-02b | #197 |
| 30 | Plazo de reclamación de los valores y exigibilidad coactiva municipal (RD, RM y OP) | TUO del Código Tributario arts. 78, 136 y 137, y TUO de la Ley 26979 art. 25 (con los arts. 29 y 31.2) | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; los plazos están firmados y publicados en el derivado |
| 31 | Inicio del cómputo de la prescripción, como desfase por tributo | TUO del Código Tributario art. 44, y TUO LTM art. 34 (la DJ anual del vehicular) | D-02a | ninguno todavía — D-02a cerrada el 2026-08-25; el desfase está firmado y publicado en el derivado |
| 32 | Porcentaje de la deducción del valor del predio en la Amazonía, y el ámbito territorial que da derecho a ella | Ley 27037 arts. 3 y 18, y su reglamento en esta materia, el D.S. 031-99-EF arts. 1 a 4. **El ámbito lo fija la Ley; el porcentaje, no**: el art. 3 del D.S. dice que «las Municipalidades de la Amazonía establecerán anualmente, el porcentaje de deducción» | D-02b — confirmado el 2026-08-28 por la Dirección del proyecto: el instrumento con que la municipalidad fija el porcentaje es una ordenanza en materia tributaria, ratificable (LOM art. 40). El D.S. 031-99-EF no lo nombra, por eso hubo que decidirlo | ninguno todavía — el factor lo esperaba D-11, y la deducción no aplica a la municipalidad piloto (Catacaos no está en el ámbito del art. 3) |
| 33 | **El `% actualización` del predial**: qué es, quién lo fija y con qué valor. Multiplica el autovalúo antes del `% propiedad`, y es el último de los cuatro factores de D-11 sin fuente | **Ninguna identificada todavía.** Se descartó la hipótesis del TUO LTM art. 14 —la actualización de valores de la emisión mecanizada— contra los manuales del SRTM del MEF: ahí ese acto es una **redeterminación** con las tablas del ejercicio nuevo, no un porcentaje. Queda como candidato el párrafo del TUO LTM que actualiza la base imponible del año anterior por Decreto Supremo cuando en un ejercicio **no se publican** los aranceles ni los valores unitarios, con tope en la variación de la UIT (`predial-porcentaje-de-actualizacion.md`) | D-02a | ninguno todavía — la etiqueta de bloqueo no le corresponde (§2.8 lista los que llevan `bloqueado:D-02`, y esto no es una decisión de D-02 sino una búsqueda de fuente abierta). Lo que sí espera son `RT-002`, `RT-005` y `RT-011`, que no se implementan ni estructuralmente |
| 34 | Derechos de trámite del TUPA para los procedimientos de **rentas y ejecución coactiva**, **licencia de funcionamiento** y **publicidad exterior** | La misma ordenanza que aprueba el TUPA que la fila 29 —Ordenanza Municipal N.° 012-2023-MDC de Catacaos—, ficha por ficha con su código de procedimiento | D-02b | ninguno todavía — están transcritos (`derecho-tramite-catacaos-2023-rentas-licencias-y-publicidad.md`) y **nadie los consume**: no existe el tipo `DERECHO_TRAMITE` en el código, ni puerto que lo lea, ni 422 que lo nombre. Y no cierran la fila 18: el derecho de trámite del expediente de publicidad no es la tasa de anuncios, que se cobra por el uso y la fija una ordenanza ratificada |

**Y son 34 desde que se leyó el TUPA entero.** La fila **34** es el resto de los derechos de
trámite de la **misma ordenanza** que ya sostenía la 29: la 29 nació nombrando solo la licencia de
edificación —que era lo transcrito— y el TUPA tarifa además los procedimientos de rentas, coactiva,
licencia de funcionamiento y publicidad exterior. Se separan en dos filas y no se ensancha la 29
porque el verificador exige **un archivo dueño por fila**, y son dos archivos: uno firmado y otro
todavía transcrito.

**Son 29 filas, no 27.** El número creció porque el mapa se construyó desde los issues hacia la
norma y no al revés: un issue bloqueado sin fila era un dato que nadie iba a buscar. La **32** y la
**33** se añadieron después por el mismo motivo, y las dos salieron de buscar los factores de D-11:
un dato sin fila es un dato que nadie va a buscar, y el `% actualización` llevaba desde M02 sin
tenerla.

**Y son 31 desde el punto 2 de #192.** Las filas 30 y 31 son datos que las filas 21 y 22 daban por
englobados y que el sistema pide con clave propia —el plazo de reclamación que separa una RD/RM
notificada de una deuda exigible coactivamente, y el desfase por tributo con el que el art. 44
computa—; el verificador del corpus exige un archivo dueño por fila, y estos dos viven en
`valores-plazos-de-reclamacion.md` y `prescripcion-inicio-del-computo.md`, no en el archivo de
las filas 21 y 22.

**Y son 32 desde que D-11 se buscó en la fuente.** La fila 32 —la deducción del predio en la
Amazonía— es uno de los cuatro factores que NEG-05 §0.1 marcaba «sin fuente identificada» y que
**ninguna fila del mapa nombraba**: no se podía buscar lo que nadie había escrito que faltaba.
Ahora tiene norma —Ley 27037 art. 18 y D.S. 031-99-EF— y archivo dueño,
`predial-deduccion-amazonia.md`. Lo que la búsqueda encontró es que la cifra **no es de norma
nacional**: la Ley fija el ámbito y el reglamento la mecánica, pero el porcentaje lo pone cada
municipalidad de la Amazonía todos los años, así que la fila nace en D-02b y no en D-02a. Los otros
dos factores de D-11 que se buscaron a la vez —el incremento del 5 % por piso y el factor de
oficialización— **no piden fila nueva**: el 5 % es una nota al pie del propio Cuadro de Valores
Unitarios (fila 7) y el `Fo = 0,68` es el Anexo II de la resolución del sector Vivienda (fila 10),
que por eso deja de estar `‹POR CLASIFICAR›`.

### 2.8 Los issues que esperan, y por qué

La otra dirección de la comprobación. Cada issue etiquetado aparece aquí con las partes que lo
bloquean y **las filas del mapa que las justifican**; si una etiqueta no tiene fila que la nombre,
o una fila nombra un issue que no la lleva, `verificar-mapa-normativo.mjs` lo señala.

| Issue | Partes | Filas del mapa |
|---|---|---|
| #189 arbitrios — las cifras | D-02b | 11, 12, 13, 14 |
| #191 fraccionamiento — las cifras | D-02b | 28 |
| #193 arancel de costas procesales — las cifras | D-02c | 23 |
| #195 cálculo de la papeleta de tránsito — las cifras | D-02c | 26 |
| #196 cálculo de la papeleta administrativa — las cifras | D-02b, D-02c | 25, 26 |
| #197 valorización de obra y derecho de trámite del FUE — las cifras | D-02b | 29 |
| #199 tasa de anuncios y propaganda — las cifras | D-02b | 18 |

**Ningún otro issue del repositorio lleva etiqueta de bloqueo por D-02.** Los siete de arriba son
las mitades «cifras» que salieron de partir sus padres en la frontera estructura/valor
([GOB-03 §E-2](../00-gobierno/plan-de-desbloqueo-D-02.md)); los padres quedaron sin bloqueo y se
pueden empezar hoy.

**Eran doce hasta el 2026-08-29**, cuando se retiró `bloqueado:D-02a` del tablero: D-02a se cerró
el 2026-08-25 y una etiqueta que nombra una decisión cerrada dice algo falso. Salieron enteros
#188, #190, #192, #194 y #198, y perdieron esa parte #195, #196 y #197, que siguen aquí por la
ordenanza o el acto local. **Retirar la etiqueta no dejó listas sus cifras**, y conviene no leerlo así: lo
que las filas 7 y 10 siguen esperando es lo que le falta al cuadro de valores unitarios —desde el
2026-08-29 ya no la segunda firma, sino su derivado publicable, el vocabulario de partidas y sus
tres regiones (H-14)—, la segunda firma de las obras complementarias, y el `% actualización` de
D-11. **La
fila 9 salió de esa lista el 2026-08-29**, con `V57`. Eso ya no es una decisión de D-02 —es trabajo, y vive en
[GOB-03](../00-gobierno/plan-de-desbloqueo-D-02.md) y en los propios issues—, que es exactamente
por lo que la etiqueta ya no lo puede representar.

### 2.9 Lo que cambió de sitio al construir el mapa

Cuatro filas no estaban donde GOB-02 las tenía, y el motivo es siempre el mismo: **se habían
clasificado por tributo y no por quién fija el valor**.

| Dato | Estaba | Está | Por qué |
|---|---|---|---|
| Espectáculos públicos (17) | D-02c | **D-02a** | La alícuota la fija el TUO LTM, no una ordenanza |
| Prescripción y plazos de notificación (21, 22) | D-02c | **D-02a** | Los fija el Código Tributario. **#192 deja de esperar a D-01** |
| Tabla de infracciones de tránsito (24) | D-02c | **D-02a** | La fija un decreto supremo nacional |
| Anuncios y propaganda (18) | D-02c | **D-02b** | La tasa la fija una ordenanza. Era el hallazgo H-3 |
| Interés moratorio (19) | D-02c | **D-02b** | El Código Tributario manda que la TIM municipal la fije una ordenanza |

El saldo: **cuatro datos pasan a poder buscarse hoy**, sin municipalidad piloto, y uno pasa a
depender de una ordenanza que antes parecía no necesitar.

## 3. Cómo entra un dato normativo al sistema

**Nunca como literal en el código** (regla 5 de [ARQ-04](../30-arquitectura/estandares-de-codigo-backend.md)).

1. Se carga como fila de `parametro_tributario` (o de la tabla específica: `arancel`,
   `valor_unitario`, `depreciacion`, `valor_referencial_vehiculo`) con su **vigencia** y su
   **documento fuente** —la ordenanza, el decreto o la resolución que lo fija.
2. Un segundo usuario lo **aprueba**: la tabla impide que quien carga sea quien aprueba.
3. Al cerrar el ejercicio se **sella** el conjunto de parámetros. Un conjunto sellado no cambia;
   corregirlo obliga a una versión nueva, y eso queda en el diff de los datos, no del código.

Así, recalcular 2027 en 2037 usa los parámetros de 2027 y da el mismo resultado.

> **Antes del paso 1 hay uno más, que es E-3:** el dato se transcribe en
> `docs/10-negocio/valores-normativos/` con su norma, su artículo, su fecha de publicación y **dos
> firmas distintas** —quien transcribe y quien verifica—. La doble verificación de `ADR-0007`
> empieza en el documento, antes de existir como fila. Y **cargar** las tres tablas de dato
> nacional se carga con `municipalidad_id` nulo desde que **D-13** se cerró el 2026-08-28
> ([ADR-0017](../30-arquitectura/adr/ADR-0017-tablas-de-valuacion-nacionales.md); §2.3, H-5).

## 4. Verificación del mapa

```bash
node docs/10-negocio/verificar-mapa-normativo.mjs
```

Comprueba, **en las dos direcciones**, que este documento y el tablero dicen lo mismo:

- las 32 filas tienen norma y parte, y todo `‹POR CLASIFICAR›` y todo `‹confirmar›` lleva su motivo;
- toda fila que dice `ninguno todavía` dice **por qué** no bloquea a nadie: el dato ya está
  firmado, su parte se cerró, o nadie lo consume todavía. Sin motivo, esa celda no se distingue de
  un olvido, y desde que D-02a se cerró la llevan veintidós de las treinta y dos;
- todo issue nombrado en una fila aparece en §2.8 con esa parte, y al revés;
- §2.8 coincide **exactamente** con las etiquetas reales del tablero, que viven en
  [`etiquetas-de-bloqueo.json`](etiquetas-de-bloqueo.json).

Quitar un issue de una fila dejándolo etiquetado —o etiquetar uno que ninguna fila nombra— pone el
guion en rojo, con el número del issue y la fila. Es la misma comprobación en dos direcciones que
hace el contrato de la API.

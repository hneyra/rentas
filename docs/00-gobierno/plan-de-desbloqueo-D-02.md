# GOB-03 — Plan de desbloqueo de D-02

| Campo | Valor |
|---|---|
| Origen | [#116](https://github.com/hneyra/sgtm/issues/116) — refinamiento técnico y funcional |
| Estado | **En ejecución.** E-1, E-2, E-3 y E-5 hechos; E-7 **cerrado para el piloto** (ADR-0018: la campaña quedó condicionada a la primera municipalidad que migre); E-6 esperando la ordenanza; E-4 **aplazado**, ya reevaluado. Ver §0 |
| Decide | Dirección del proyecto (E-4 y E-6); el resto no requiere autorización |
| No hace | No cierra D-02, no carga ninguna cifra y no sustituye a [GOB-02](decisiones-abiertas.md) |

Este documento convierte las siete estrategias de #116 en siete paquetes ejecutables: cada uno con
su entregable concreto, dónde vive, qué criterio lo da por terminado y **cómo se demuestra que su
verificación puede fallar** —que es lo que aquí distingue una verificación de una afirmación—.

Lo aprobado entra en GOB-02, que sigue siendo el registro. Este plan es el camino, no el registro.

## 0. Estado de ejecución

Del §1 al §7 está el plan **tal como se escribió**. Esta sección dice qué se hizo de él, qué salió
distinto y qué queda; es lo primero que hay que leer y lo único que cambia con el tiempo.

> **La etiqueta `bloqueado:D-02a` se retiró del tablero el 2026-08-29**, y con ella las veinte
> filas del mapa que la nombraban. D-02a se cerró el 2026-08-25 ([#200](https://github.com/hneyra/sgtm/issues/200)),
> y una etiqueta que nombra una decisión cerrada dice algo falso: salieron enteros #188, #190,
> #192, #194 y #198, y perdieron esa parte #195, #196 y #197. Quedan **siete** issues
> etiquetados, todos por ordenanza o acto local; la instantánea del tablero
> ([`etiquetas-de-bloqueo.json`](../10-negocio/etiquetas-de-bloqueo.json)) se regeneró en el mismo
> cambio, que es lo que `verificar-mapa-normativo.mjs` exige. **Retirar la etiqueta no dejó listas
> las cifras**: lo que las filas 7, 9 y 10 del mapa siguen esperando son H-14, H-15 y el
> `% actualización` de D-11, que son trabajo y no decisión —de ahí que la etiqueta ya no los pueda
> representar—. **H-15 se cerró el 2026-08-29** (`V57`, #188). De **H-14** quedaba, ese mismo día,
> «el proceso de publicación, el derivado con su huella, el vocabulario de partidas y tres
> regiones»: **las tres regiones se transcribieron el 2026-08-30** (#436), y leerlas añadió un
> quinto pendiente que nadie había visto —la **`J` de la Selva** no cabe en
> `categoria ~ '^[A-I]$'`—. Lo que la fila 7 espera hoy es entonces esquema y carga, no
> transcripción ni firma; y la 10, la segunda firma de las obras complementarias, llegó también el
> 2026-08-30.

### 0.1 Dos hallazgos más, del día de ejecutar

El plan se escribió el 19 de agosto y se ejecutó el 22. En esos tres días el repositorio se movió,
y hay que decirlo porque cambia el reparto:

| # | Hallazgo | Cómo se comprobó |
|---|---|---|
| **H-8** | **Tres de los quince issues ya están cerrados**: #17, #22 y #30, los tres «completado», los tres **conservando la etiqueta `bloqueado:D-02`**. #30 cerró con su estructura —detalle por predio, agregación por contribuyente, tramos como dato— y su mitad de cifras quedó sin issue que la siguiera: invisible | Consulta de issues por etiqueta, contra `git log` y `sgtm-rentas/dominio/predial/` |
| **H-9** | **Cuatro datos estaban en la parte equivocada, y siempre por el mismo motivo**: se habían clasificado por tributo y no por quién fija el valor. Espectáculos (TUO LTM art. 57), prescripción y plazos (Código Tributario) y la tabla de infracciones de tránsito (D.S. 016-2009-MTC) son **norma nacional** y estaban en `D-02c`; la TIM la fija una **ordenanza** (Código Tributario art. 33) y estaba en `D-02c`. Con el criterio escrito, la partición pasa a ser una función | Cruce fila por fila de NEG-02 §2 al construir el mapa |

H-8 es la razón de la única desviación de forma respecto del plan (§0.3), y H-9 es la razón de que
**cuatro datos más se puedan buscar hoy, sin municipalidad piloto**.

### 0.2 E-1 · Hecho

- El mapa vive en [NEG-02 §2](../10-negocio/marco-normativo.md): **29 filas** con norma, parte e
  issues que la esperan. Son 29 y no 27 porque dos datos bloqueaban un issue **sin tener fila**
  —el interés y el máximo de cuotas del fraccionamiento, y el derecho de trámite del TUPA—: un
  dato que nadie iba a buscar porque nadie lo había escrito.
- Dos filas quedaban sin parte firme, y **se veía**: la valorización de obras complementarias
  (`‹POR CLASIFICAR›`, sin norma identificada) y el arancel de costas coactivas (`D-02c
  ‹confirmar›`, según quién lo apruebe). Son las dos que la decisión 5 de §4 pone en manos de
  Rentas y asesoría legal. **La primera ya tiene parte**: leído el Anexo II de la R.M.
  277-2025-VIVIENDA, el factor de oficialización que le faltaba fuente es `Fo = 0,68` y lo fija esa
  resolución, así que la fila 10 pasa a `D-02a`. La segunda sigue abierta. Y **hay una fila más**,
  la 32 —la deducción del predio en la Amazonía—, que nació de buscar los factores de D-11: ver
  H-17.
- Las tres etiquetas existen, con el color `ededed` de la anterior, y **`bloqueado:D-02` no la
  lleva ya ningún issue** —queda la etiqueta vacía en el repositorio, para borrarla a mano—.
- `docs/10-negocio/verificar-mapa-normativo.mjs` comprueba las dos direcciones contra la
  instantánea del tablero. **Se demostró que puede fallar** con tres roturas: quitar `#194` de la
  fila 9 dejándolo etiquetado, etiquetar un issue que ninguna fila nombra, y dudar de una parte sin
  decir por qué. Las tres lo ponen en rojo, nombrando la fila y el issue.

### 0.3 E-2 · Hecho, con una desviación de forma

**Doce issues partidos**, y el mapa apunta a las mitades de cifras:

| Padre | Mitad de cifras | Etiquetas |
|---|---|---|
| #30 determinación del predial ‹cerrado› | #188 | `D-02a` |
| #31 arbitrios | #189 | `D-02b` |
| #32 vehicular, alcabala y espectáculos | #190 | `D-02a` |
| #35 fraccionamiento | #191 | `D-02b` |
| #39 notificación y prescripción | #192 | `D-02a` |
| #42 costas coactivas | #193 | `D-02c` |
| #45 actas de fiscalización | #194 | `D-02a` |
| #46 papeleta de tránsito | #195 | `D-02a`, `D-02c` |
| #47 papeleta administrativa | #196 | `D-02a`, `D-02b`, `D-02c` |
| #48 FUE de edificación | #197 | `D-02a`, `D-02b` |
| #49 liquidación de fiscalización | #198 | `D-02a` |
| #51 anuncios y propaganda | #199 | `D-02b` |

**La desviación:** el plan pedía dos hijos por padre —«estructura» y «cifras»— y se creó **uno
solo**, el de cifras, dejando al padre como el issue de estructura, sin etiqueta de bloqueo y con
su sección «Bloqueos» corregida. El motivo es H-8: #30 se cerró como «completado» al terminar su
estructura, lo que demuestra que en este repositorio **el padre ya se comporta como el issue de
estructura**. Un hijo «estructura» habría sido una copia del padre, y doce issues más que mantener
en sincronía con él. Lo que sí se conserva del plan es lo que le daba sentido: el hijo de cifras
cuelga del padre como sub-issue, así que la mitad que falta **está a la vista** —que es
exactamente lo que no pasó con #30—.

#17 y #22, cerrados, solo perdieron la etiqueta. #43 la perdió también, y su sección «Bloqueos»
ahora explica por qué nunca debió llevarla.

### 0.4 Lo que queda, con su issue

| Paquete | Issue | Estado |
|---|---|---|
| **E-3** transcribir y firmar D-02a | #200 | **Hecho, 2026-08-25** (commit `a8622c3`). Los 14 archivos que cerraban D-02a estaban `VERIFICADO`, cada uno con **dos firmas distintas**; `verificar-valores-normativos.mjs` y `verificar-mapa-normativo.mjs` pasan en verde. **Hoy el corpus tiene 19 archivos y cierra 22 filas del mapa**, y **dos** siguen en `TRANSCRITO`: los dos de D-11 —obras complementarias y deducción de Amazonía—. Los valores unitarios volvieron atrás al cotejarlos contra el Anexo I.2 real y **volvieron a `VERIFICADO` el 2026-08-29**, con la segunda firma puesta esta vez sobre la matriz sustituida (H-14). **Lo que decía esta fila del mecanismo de carga ya no aplica**: D-13 se cerró el 2026-08-28 (ADR-0017) y el camino existe entero —`PublicarParametros` (#188), `PublicarCuadros` y `AbrirConjuntoDeParametros` (#247 §2), con la credencial de `rol_carga_parametros`—. Lo que impide cargar el cuadro que falta es H-14, no la ausencia de mecanismo: **H-15 se cerró el 2026-08-29 con V57** y la tabla de depreciación ya se publica —492 filas, las cuatro tablas del Anexo I— |
| **E-5** el corpus de casos | #201 | **Hecho.** 32 casos en `sgtm-rentas/src/test/resources/casos/`, uno por regla de NEG-05, con los 17 casos borde de §2 enumerados. Dos ejecutables, y los otros treinta con **quien los impide** en su fila. Al hacerlo salieron tres hallazgos: H-10, H-11 y H-12 (§0.6) |
| **E-6** la municipalidad de demostración | #202 | Abierto. **Los cuatro entregables de código existen**: la marca en migración, el marcado de todo documento en los tres formatos, sus 19 pruebas, y —desde #212— la regla que impide que nada siembre fuera del perfil `batch`, que era el tercer criterio y no lo comprobaba nadie. Falta **elegir la ordenanza**, y con ella su transcripción y las cifras que el tenant carga |
| **E-7** puntos de redondeo y campaña de D-03c | #203 | **Los tres entregables de código, hechos**: `PuntoDeRedondeo` con sus catorce puntos, `PoliticasDeRedondeo` que falla cuando falta uno, el formulario de la campaña en `docs/10-negocio/observaciones-srtm-mef/`, y `PoliticasDeRedondeoSelladas`, que las **lee del conjunto sellado** —una fila `REDONDEO:‹punto›` con la escala en `valor_numerico` y el modo en `valor_texto`—. `RegistrarDeterminacionPredial` ya no las recibe: las lee. ~~Queda **solo la campaña**, que necesita acceso al SRTM del MEF~~ **La campaña dejó de hacer falta para el piloto** ([ADR-0018](../30-arquitectura/adr/ADR-0018-el-redondeo-decidido.md), 2026-08-28): arranca con padrón nuevo, sin determinaciones del SRTM que reproducir, y los puntos los fija el propio ADR (cierre de regla, céntimo, `HALF_UP`). La campaña **revive como prerrequisito de D-04** con la primera municipalidad que migre; su formulario sigue en `observaciones-srtm-mef/` |
| **E-4** el estado `PROVISIONAL` | — | **Reevaluado al cerrar #201, y sigue aplazado** —ahora con evidencia, ver §0.7—. No se abre issue para no fingir que hay trabajo aprobado |

### 0.5 Las cinco decisiones de §4

| # | Decisión | Estado |
|---|---|---|
| 1 | E-4 sí o no | **Resuelta: no todavía.** Reevaluada al cerrar #201 con el caso concreto delante, que es lo que el plan pedía. Ver §0.7 |
| 2 | E-6: qué ordenanza | **Abierta.** Decide Rentas; es lo único que le falta a #202 |
| 3 | D-13: ámbito de las tablas de valuación | **Cerrada el 2026-08-28 por la Dirección del proyecto** ([ADR-0017](../30-arquitectura/adr/ADR-0017-tablas-de-valuacion-nacionales.md)): nacionales, `municipalidad_id` nulo. Fuera de GOB-02 |
| 4 | H-4: la dimensión que falta en `valor_unitario_edificacion` | **Cerrada en el esquema, sin que nadie lo anotara: V18 ya la había puesto.** Lo que queda no es la columna sino cuál de las dos lecturas de RT-002 es la correcta (H-14) |
| 5 | H-3: anuncios y costas coactivas | **Anuncios, resuelto**: pasa a `D-02b` (fila 18). **Costas, abierto**: la fila 23 queda `D-02c ‹confirmar›` hasta saber quién aprueba el arancel |

### 0.6 Tres hallazgos más, del día de construir el corpus

E-5 se escribió el 22 de agosto y encontró tres cosas que ningún documento decía. Las tres cambian
lo que se puede prometer del predial.

| # | Hallazgo | Cómo se comprobó |
|---|---|---|
| **H-10** | **El motor solo sabía expresar reglas cuyo parámetro tiene clave constante.** Una regla lee `numero(tipo, clave)` con la clave escrita en el código, y eso vale para la UIT o una alícuota —una por ejercicio—, pero el **arancel es por vía**, el **valor unitario por categoría y año** y la **depreciación por material, antigüedad y estado**: la clave sale del predio. Sin un sitio de donde sacarla, `RT-001`, `RT-003` y `RT-004` **no tenían forma** | Intentar escribir `RT-001` contra `InsumosDeLaRegla` |
| **H-11** | **Los números de NEG-05 no cuadran con NEG-05.** §6 pide «resolución de los **14** casos borde de §2» y en §2 hay **diecisiete**. Y «`RT-001`…`RT-016`» son **doce** reglas: §2 no define `RT-006` a `RT-009`, y esos identificadores no existen | Enumeración una por una al construir el corpus |
| **H-12** | **De las doce reglas, hoy se puede escribir una.** `RT-001` es la única rama del grafo sin ninguno de los cuatro factores de D-11. `RT-002`, `RT-005`, `RT-010` y `RT-012` los llevan —y CLAUDE.md prohíbe implementarlos «ni estructuralmente»—; `RT-003` espera a H-4; `RT-004` a D-02a; `RT-015` y `RT-016` no caben en el motor, porque producen un **conjunto de cuotas** y una regla devuelve un importe; `RT-013` y `RT-014` existen como funciones puras fuera del motor | Clasificación de las doce, fila por fila, en el corpus |

**H-12 corrige la premisa de E-5.** El plan decía «se deja en blanco la cifra, no las aristas del
grafo», y da por hecho que las aristas se conocen. **Cuatro de ellas no las bloquea D-02 sino
D-11**: no es que falte el valor del factor, es que no se sabe si el factor existe. Por eso el
corpus no es solo un juego de casos: es un **libro mayor** donde cada caso dice quién lo impide, y
la prueba comprueba que eso sea verdad en las dos direcciones —un caso declarado «sin regla» cuya
regla sí está registrada la pone en rojo—.

### 0.7 E-4, reevaluado con el caso delante

El plan aplazaba el tercer estado `PROVISIONAL` y se comprometía a **reevaluarlo al cerrar #201**,
con un caso concreto en vez de una hipótesis. Ya lo hay, y la recomendación **se mantiene**. Lo que
cambia es que ahora se puede argumentar en vez de recomendar:

| Lo que E-4 prometía | Dónde está hoy |
|---|---|
| Ejercitar el grafo entero con cifras que no son normativas | `CorpusDeCasosTest` y `MotorDeReglasTest`, **en memoria**, sin una sola fila en la base |
| Que una cifra sin valor normativo no produzca un importe que alguien tome por bueno | La comprobación *no se admite un `esperado` si los parámetros son ficticios*, del propio corpus |
| Cuatro piezas: migración con `CHECK` y dos disparadores, `ParametrosDeSimulacion` como tipo, regla de arquitectura con su muestra, y prueba gemela sin el disparador | Sigue sin escribirse, y su coste no ha bajado |

La barrera que justificaba el estado —lo segundo— **existe al coste de una comprobación**, no de
una migración irreversible. Y H-7 sigue en pie: un `parametro_tributario` no sabe si es provisional,
así que el estado por sí solo no impediría que una fila provisional acabara en un conjunto sellado.

**Se reevaluará otra vez si aparece un caso que exija el camino persistido** —una emisión de punta a
punta que no se pueda montar en memoria—. Hoy no aparece, y meter cifras inventadas en la base para
cubrir un camino que ya está cubierto es la antiestrategia de §5.

## 1. Lo que se verificó antes de planificar

Siete hallazgos. Los tres primeros corrigen el inventario de #116; los cuatro últimos cambian lo que
hay que construir.

| # | Hallazgo | Cómo se comprobó |
|---|---|---|
| **H-1** | **Son nueve los issues que esperan solo por norma nacional, no seis.** Sin componente de ordenanza local: #17, #22, #30, #32, #39, #42, #45, #46, #49. Con componente local: #31, #35, #47, #48, #51 —cinco, eso sí coincide— | Recuento sobre la columna «bloqueo real» de la tabla del propio #116 |
| **H-2** | **Son quince issues, no catorce, y las tres listas que existen discrepan.** #43 declara `D-02` en su cuerpo y **no lleva la etiqueta**; la tabla de la épica #58 lo lista y en cambio **omite #39**, que sí la lleva | Consulta de issues por etiqueta (14), contra la fila «D-02» de #58 (14, distinta) y contra el cuerpo de #43 |
| **H-3** | **La partición D-02a/b/c todavía no es una función sobre los 27 datos de NEG-02 §2.** Al menos cuatro no están en ninguna de las tres enumeraciones de GOB-02 —aranceles de terreno, alícuota y tramo inafecto de alcabala, interés y número máximo de cuotas del fraccionamiento— y uno está en la parte discutible: «anuncios» figura en D-02c cuando la tasa la fija una ordenanza ratificada, que es la definición de D-02b | Cruce línea por línea de NEG-02 §2 (27 filas) contra las tres enumeraciones de GOB-02 |
| **H-4** | ~~**`valor_unitario_edificacion` no tiene la dimensión que M02 confirmó.**~~ **Cerrado en el esquema desde V18**, y esta fila estaba desactualizada: citaba la clave de V1. V18 (#17) le añadió `anio_construccion_desde`/`anio_construccion_hasta` y los metió en `valor_unitario_uq`, que es literalmente lo que `../srtm` NEG-05 §RT-002 pide —«el cuadro de valores unitarios es una matriz de dos dimensiones: categoría × año de construcción»—. **Queda una duda de fondo que el esquema no decide** (ver H-14): `valores-unitarios-2026.md` §3 sostiene, tras leer la RM 277-2025-VIVIENDA, que la resolución aprueba una matriz *categoría × partida* y que el año de construcción es la entrada de la tabla de depreciación, no de este cuadro. NEG-05 dice lo primero **literalmente**; la RM real, lo segundo. **Y NEG-05 no se contradice: deja esa forma marcada `‹VERIFICAR›` contra la resolución anual**, que es la comprobación que este repositorio hizo el 2026-08-28. Las columnas de V18 no estorban si gana la segunda lectura: se cargan con un solo tramo abierto | `V18__tablas_de_valuacion_por_conjunto.sql:59-82` contra `../srtm` NEG-05 §RT-002, leído el 2026-08-28 |
| **H-5** | ~~**Tres tablas de dato nacional están claveadas por municipalidad.**~~ **Cerrado el 2026-08-28 por D-13** ([ADR-0017](../30-arquitectura/adr/ADR-0017-tablas-de-valuacion-nacionales.md), V55): las tres pasan a catálogo nacional con `municipalidad_id` nulo, y no «nulo por omisión» sino con `CHECK (municipalidad_id IS NULL)`. Eso es lo que lo cierra **por construcción**: una sola copia no puede divergir de sí misma. Las tres están clasificadas como catálogo en la prueba de aislamiento, y el diff lo enseña | `V55`, `AislamientoMultiTenantTest` (18 pruebas en verde con la clasificación nueva) |
| **H-6** | **`PoliticaDeRedondeo` no puede expresar D-03c.** Es un par `(escala, modo)` único para todo el cálculo; D-03c trata de **en qué puntos** se redondea, y M02 ya mostró uno intermedio —el metrado redondeado—. Con el tipo de hoy, un punto no observado no falla: simplemente no redondea, y produce un importe plausible | `PoliticaDeRedondeo.java` contra GOB-02 §D-03c y NEG-05 §RT-005 |
| **H-7** | **El estado `PROVISIONAL` de E-4 no se sostiene solo en el conjunto.** Un `parametro_tributario` no sabe si es provisional: la fila vive fuera del conjunto y `conjunto_parametro_detalle` la referencia. Nada impediría que una fila cargada como provisional entrara después en un conjunto que sí se sella | `V1` (las tres tablas), `V9__conjuntos_sellados.sql` y `ParametrosRepository.agregarParametro` |
| **H-14** | **El cuadro de valores unitarios no se puede cargar todavía, y no por su ámbito.** Dos motivos, los dos escritos en su propio archivo del corpus: (a) `valores-unitarios-2026.md` §1 dice de sí mismo `‹NO CONFIRMADO EN FUENTE OFICIAL›` que sus 9×7 cifras sean las del Anexo I.2 de la RM 277-2025-VIVIENDA —se transcribieron de un cuadro directoral mensual con la fecha de corte del art. 11 del TUO LTM, que es la hipótesis mejor fundada, no una lectura de la RM—; y (b) la RM publica **un cuadro por región** (Costa, Lima/Callao, Sierra, Selva) y el archivo sólo trae Costa. Con ADR-0017 la región no necesita columna: cada región es una **edición**. Lo que falta es cotejar las cifras contra el Anexo I.2 real. **(a) resuelto el 2026-08-28, leyendo el PDF del Anexo I.2 — y el cotejo devolvió que el cuadro publicado era otro**: la norma tiene **3 partidas** (muros y columnas, techos, puertas y ventanas), no las 7 que el archivo transcribía, así que las 63 cifras que había no eran ni las correctas ni de la forma correcta; las 27 reales están ahora transcritas y comprobadas por tres métodos. **(a) queda cerrado del todo el 2026-08-29**, cuando HNA volvió al PDF y firmó la sustitución: el archivo es `VERIFICADO` otra vez, y conviene no leerlo como una re-confirmación —la firma del 25 no respalda ninguna de las 27 cifras que hoy se leen ahí—. **Lo que impide cargarlo ya no es ninguna firma**, y está enumerado en `valores-unitarios-2026.md` §2: `FilaDelManifiesto.CUADROS` no incluye `VALOR_UNITARIO`, no hay archivo de filas con su `sha256` ni fila en `cuadros-2026.csv`, y las tres partidas del anexo **no cancelan** las siete del vocabulario del esquema —`valor_unitario_edificacion` y `edificacion_estructura` las declaran, y `construccion` tiene una columna `categoria_*` por cada una—: cargar tres dejaría cuatro sin fila, que no se distingue de una edición completa hasta que alguien valoriza. **Y (b) se cerró el 2026-08-30 (#436): las cuatro regiones están transcritas** (§1.5), leídas de sus PDF por tres caminos cotejados celda por celda. Leerlas contestó de paso la pregunta que esta fila dejaba abierta —«leer el Anexo I.1 es lo único que puede decir si las siete partidas existen en alguna región»—: **no existen en ninguna**, las cuatro regiones publican las mismas tres. Y añadió un pendiente que nadie había visto: la **Selva tiene diez categorías** (A…J) y `categoria char(1) CHECK (~ '^[A-I]$')` **rechazaría la `J`**; la **Sierra pone el `0.00` en `G`-techos**, no en `H`, y Lima/Callao no trae nota de demarcación — o sea que deducir la estructura de una región y aplicarla a otra inventa una cifra en la casilla de al lado | `valores-unitarios-2026.md` §1, §2 y §3; el PDF del Anexo I.2 leído el 2026-08-28 y re-firmado el 2026-08-29; `FilaDelManifiesto.CUADROS`, `V1__nucleo_y_catastro.sql:400-412` y `V43__licencia_de_edificacion.sql:290`; los Anexos I.1, I.3 y I.4 leídos el 2026-08-30 | · **2026-08-30: vocabulario decidido en `V59`** (tres para la norma, siete para la ficha del manual).
| **H-15** | ~~**A `depreciacion` le falta el uso de la edificación, y son cuatro tablas.**~~ **Cerrado el 2026-08-29 con `V57` (#188).** El Anexo I del Reglamento Nacional de Tasaciones publica **cuatro** tablas —01 vivienda, 02 tiendas y depósitos, 03 oficinas, 04 salud/industria/educación—, cada una material × estado × antigüedad, y la tabla tenía `(material, estado_conservacion, antiguedad_hasta)` sin **ninguna columna de uso**. V57 le añade `uso` a la clave y hace `antiguedad_hasta` anulable —«Más de 50 años» no tiene tope, y un centinela se leería igual que un tope de verdad—, con `NULLS NOT DISTINCT` para que el tramo abierto no se pueda duplicar. **Y lo que se temía está medido, no razonado**: cargando el cuadro entero contra PostgreSQL entran **492 filas**, y sin el uso en la clave sobreviven **127** —365 descartadas en silencio, y **120 de esas 127 combinaciones tienen un porcentaje distinto según el uso**, que es exactamente depreciar una oficina con el de una vivienda—. Las 36 celdas que el Anexo marca `*` no se cargan con cero: no se cargan (#48) | `depreciacion.md` §1 contra `V57__depreciacion_por_uso_de_la_edificacion.sql`, y `PublicarCuadrosTest` contra PostgreSQL real |
| **H-16** | **A `valor_referencial_vehiculo` le faltaban la categoría y 20 caracteres de modelo, y lo encontró cargar el anexo de verdad.** De las 54 129 filas del anexo 2026, **1 905 se perdían en silencio**: 15 porque cinco modelos pasan de los 60 caracteres de la columna (el más largo tiene 67), y **1 890 porque el anexo publica «OTROS MODELOS» dentro de cada categoría** —472 pares `(marca, modelo)` repetidos entre categorías, con valor distinto en cada una— y la unicidad se quedaba con la primera. Un camión valorizado con la cifra de una camioneta no produce ningún error, produce otra base imponible. **Corregido en V55**: `categoria` entra en la tabla y en `valor_referencial_uq`, y `modelo` pasa a `varchar(80)`. Con eso entran 54 111 filas y los 18 rechazos restantes son seis líneas que el anexo **repite idénticas**. Lo que queda abierto es el otro lado: `vehiculo.categoria` es nulable desde V2, así que `buscar` lanza `ValorReferencialAmbiguo` en vez de elegir | Ejecutando `PublicarCuadros` sobre `tvr-2026.csv` contra PostgreSQL 16: 52 224 publicadas antes, 54 111 después |
| **H-17** | **Uno de los cuatro factores de D-11 no lo fija ninguna norma nacional, y el mapa no tenía dónde decirlo.** Buscar los factores «sin fuente» de NEG-05 §0.1 en los PDF oficiales devolvió tres hallazgos: el **factor de oficialización** es `Fo = 0,68` y lo fija el Anexo II de la R.M. 277-2025-VIVIENDA (fila 10, que deja de estar `‹POR CLASIFICAR›`); el **incremento del 5 %** es una nota al pie del propio Cuadro de Valores Unitarios (fila 7), y lo que le faltaba —si es único o acumulativo— **quedó resuelto el 2026-08-29 (#436): es único**, no acumulativo, y se aplica antes de la depreciación; la prueba que lo decide es la Nota 03 del manual del SRTM, que recomienda agrupar todos los pisos ≥ 5 bajo un solo nivel «para que el sistema pueda considerar adecuadamente el incremento del 5%» —lo que sería imposible si creciera piso a piso—; y la **deducción de Amazonía** tiene norma —Ley 27037 art. 18, reglamentada por el D.S. 031-99-EF, **no** por el D.S. 103-99-EF, que no menciona la palabra «predial» ni una vez— pero **no tiene cifra nacional**: su art. 3 dice que «las Municipalidades de la Amazonía establecerán anualmente, el porcentaje de deducción». Ese dato no tenía fila en el mapa, así que nadie lo iba a buscar: es la **fila 32**, y nace en `D-02b`, no en `D-02a`. Catacaos no está en el ámbito del art. 3 —el único punto que nombra a Piura alcanza al distrito de Carmen de la Frontera, provincia de Huancabamba—, así que la fila no bloquea al piloto | Los PDF oficiales de la Ley 27037, del D.S. 031-99-EF, del D.S. 103-99-EF y del Anexo II de la R.M. 277-2025-VIVIENDA, leídos el 2026-08-28; transcritos en `predial-deduccion-amazonia.md` y `obras-complementarias-y-oficializacion-2026.md` |
Lo que **se confirmó tal como #116 lo dice**: los cinco issues bloqueados por ordenanza local; que
cuatro issues declaran en su propio cuerpo que la parte bloqueada está fuera de su alcance (#17 y
#22 en «Fuera de alcance», #45 y #46 en «Bloqueos») —y hay un quinto, #30, que enumera al final lo
que sí se puede hacer—; y que las etiquetas `bloqueado:D-02a`, `bloqueado:D-02b` y `bloqueado:D-02c`
**no existen** en el repositorio: hay que crearlas.

## 2. Inventario corregido

«Alcance implementable hoy» es lo que se puede escribir **sin una sola cifra normativa**.

| Issue | Parte de D-02 que bloquea | Otros | Alcance implementable hoy | Paquete |
|---|---|---|---|---|
| #17 tablas de valuación | D-02a | D-11 | Todo el issue, **más la dimensión que falta** (H-4): estructura, versión nueva, sellado, consulta por ejercicio | E-2, E-3 |
| #22 `deudaActualizadaA(fecha)` | D-02c (TIM, reajuste) | D-03 | Toda la función: recorrido del libro, desglose, fecha como argumento, pureza | E-2, E-7 |
| #30 determinación predial | D-02a | D-03c, D-11, D-12, D-01 | Detalle por predio, imposibilidad de emitir por predio, esqueleto de reglas, corpus sin cifras | E-2, E-5 |
| #31 arbitrios | **D-02b** | D-03 | Cuotas del ejercicio, cambio de uso a mitad de año, exclusión por servicio, idempotencia | E-2, E-6 |
| #32 vehicular, alcabala, espectáculos | D-02a (vehicular, alcabala) + D-02c (espectáculos) | D-03 | Modo simulación que no escribe, plazo de afectación, elección de base con su fundamento | E-2 |
| #35 fraccionamiento | **D-02b** (interés y máximo de cuotas) | D-03 | Preconvenio, cuotas, anulación, quiebre y devolución de fase | E-2, E-6 |
| #39 notificación y prescripción | D-02c (plazos) | D-05 | Acuse, no hallado, reintento, pase a coactiva idempotente | E-2 |
| #42 costas y fraccionamiento coactivo | D-02c (arancel) ‹confirmar: si lo fija ordenanza pasa a D-02b› | — | La costa como cargo en el libro, el quiebre coactivo, las consultas con su fecha | E-1, E-2 |
| #43 catálogos de infracciones | D-02c (tránsito) + **D-02b** (CUIS) | — | **Todo el issue**: ninguno de sus cinco criterios de aceptación nombra una cifra. Es el ejemplo de la forma a la que E-2 lleva a los demás | E-1 |
| #45 actas de fiscalización | D-02a | D-03, D-11 | Casi todo: programación, acta, copia versionada, «no toca el padrón» | E-2 |
| #46 papeleta de tránsito | D-02c (tabla) + D-02a (UIT) | — | Casi todo: registro con los importes del acta física, desglose guardado, cambio de número, búsqueda | E-2 |
| #47 papeleta administrativa | **D-02b** (CUIS, plazo) + D-02a (UIT) | — | Notificación previa, subsanación, papeleta sin notificación, modelo compartido con tránsito | E-2, E-6 |
| #48 FUE de edificación | **D-02b** (TUPA) + D-02a (valores unitarios) | D-05 | Las secciones del FUE, ampliación, revalidación | E-2, E-6 |
| #49 liquidación de fiscalización | D-02a + D-02c (multa tributaria) | D-03, D-11 | Comparación hallado/declarado, reliquidación, omisos frente a extemporáneos | E-2 |
| #51 anuncios y propaganda | **D-02b** (H-3: hoy GOB-02 lo pone en D-02c) | — | Registro, idempotencia del cargo, cese sin borrar lo pasado | E-1, E-6 |

## 3. Los siete paquetes

Se conservan los identificadores `E-n` de #116 para no romper las referencias.

### E-1 · El mapa normativo, y el reetiquetado que sale de él

**El reetiquetado no es el trabajo: es la consecuencia.** Hoy no existe la tabla que dice qué norma
fija cada uno de los 27 datos de NEG-02 §2, y sin ella asignar `D-02a`/`b`/`c` a un issue es una
conjetura (H-3).

**Entregable**

1. En [`docs/10-negocio/marco-normativo.md`](../10-negocio/marco-normativo.md) §2, dos columnas
   nuevas en las 27 filas: **norma que lo fija** (rango, número y artículo) y **parte de D-02**. El
   dato que no se pueda asignar sin leer la norma queda `‹POR CLASIFICAR›`: es trabajo de E-3, no de
   E-1, y se ve.
2. Las cuatro filas que hoy no cubre ninguna parte (H-3) entran donde corresponda, y GOB-02 recoge
   la enumeración corregida.
3. Tres etiquetas nuevas —`bloqueado:D-02a`, `bloqueado:D-02b`, `bloqueado:D-02c`, color `ededed`,
   el de la actual— aplicadas **por partes**: un issue lleva todas las que lo bloquean (#32 lleva
   `a` y `c`). `bloqueado:D-02` se retira y se borra cuando ya no tenga issues.
   **Y la etiqueta solo se conserva si algo dentro del alcance declarado del issue necesita una
   cifra**: por eso #17 y #22 la pierden entera —su «Fuera de alcance» ya excluye las cifras— y #43
   nunca debió llevarla.
4. Las tres listas se reconcilian (H-2): #43 entra al inventario y la fila «D-02» de la épica #58 se
   reconstruye desde las etiquetas, no a mano.

**Criterios de aceptación**

- [ ] Ningún issue abierto lleva `bloqueado:D-02`.
- [ ] Todo issue etiquetado `bloqueado:D-02x` tiene al menos una fila del mapa que lo nombra, y toda
      fila del mapa nombra los issues que la esperan o dice «ninguno todavía». Se comprueba en las
      dos direcciones, como el contrato de la API.
- [ ] Las 27 filas tienen norma y parte, o `‹POR CLASIFICAR›` con motivo.
- [ ] La fila «D-02» de #58 coincide exactamente con las etiquetas.

**Cómo se demuestra que puede fallar:** quitar un issue de una fila del mapa dejándolo etiquetado —o
al revés— y la comprobación cruzada lo señala. Si se automatiza, va como
`docs/10-negocio/verificar-mapa-normativo.mjs`, junto a `generar-catalogo.mjs`, que es el precedente
de script de documentación en este repositorio.

**Tamaño:** S. **No requiere autorización.**

### E-2 · Partir cada issue en la frontera estructura/valor

La frontera ya existe en el código:
[ADR-0007](../30-arquitectura/adr/ADR-0007-parametros-versionados.md) separa el dato normativo de la
regla, y `ParametrosSellados` entra **como argumento**. Falta reflejarla en el tablero.

**La regla de corte, que es lo que hace el reparto decidible:** si el criterio de aceptación se
puede escribir **sin nombrar un importe**, es estructura. Si necesita una cifra esperada, es valor.
Aplicada a #43 da cero criterios de valor —por eso #43 nunca debió estar bloqueado— y aplicada a
#30 deja fuera solo la cifra de cada regla.

**Entregable**

Cada issue mixto pasa a padre con dos sub-issues (GitHub los soporta; la épica #58 es el precedente
de jerarquía):

| Hijo | Etiquetas | Contiene |
|---|---|---|
| «… — estructura» | las del padre, **sin** bloqueo | Modelo, migración, RLS, endpoints, idempotencia, auditoría, pruebas contra PostgreSQL y **las filas del corpus de E-5 de sus reglas, con la columna vacía** |
| «… — cifras» | `bloqueado:D-02x` | La regla que produce el importe, la columna «esperado» del corpus y la prueba de reproducibilidad al céntimo |

**Se parten doce**: #30, #31, #32, #35, #39, #42, #45, #46, #47, #48, #49 y #51 —veinticuatro hijos
bajo doce padres que quedan como contenedor—.

**Tres no se parten, y por motivos distintos:** #17 y #22 solo pierden la etiqueta, porque su propio
«Fuera de alcance» ya excluye las cifras y lo que queda del issue es entero; #43 nunca debió
llevarla, y lo que se corrige es su sección «Bloqueos».

**Criterios de aceptación**

- [ ] Ningún hijo «estructura» lleva etiqueta de bloqueo.
- [ ] Ningún hijo «estructura» tiene un criterio de aceptación que nombre un importe.
- [ ] Cada hijo «estructura» enumera las filas del corpus que entrega (contención del riesgo: sin
      eso, «estructura terminada» no se puede probar de punta a punta).
- [ ] El padre no se cierra hasta que cierran los dos hijos.

**Cómo se demuestra que puede fallar:** un hijo «estructura» con un criterio que exija una cifra
queda visible en la revisión del propio issue —y si se automatiza, la comprobación es un `grep` de
importes en los criterios de los issues sin etiqueta de bloqueo—.

**Tamaño:** M, casi todo trabajo de tablero. **Depende de E-1.** **No requiere autorización.**

### E-3 · Cerrar D-02a: buscar, transcribir y firmar

GOB-02 lo dice: D-02a **no se decide, se busca**. Son normas nacionales publicadas.

**Entregable:** `docs/10-negocio/valores-normativos/`, un archivo por norma, con esta cabecera
obligatoria —la doble verificación de ADR-0007 empieza en el documento, antes de existir como fila—:

```markdown
| Campo | Valor |
|---|---|
| Norma | D.S. 156-2004-EF, TUO de la Ley de Tributación Municipal |
| Artículo | 13 |
| Publicada | 2004-11-15, El Peruano |
| Ejercicios que rige | 2004 en adelante |
| Transcribió | ‹nombre›, ‹fecha› |
| Verificó | ‹nombre distinto›, ‹fecha› |
| Estado | TRANSCRITO · VERIFICADO |
```

Y tres secciones fijas: **la tabla tal como está en la norma** —sin reordenar, sin convertir
unidades, sin «arreglar» un encabezado—; **cómo entra al sistema**, nombrando el `tipo` y la `clave`
de `parametro_tributario` o la tabla específica que la recibe; y **qué no cabe hoy**, que es donde
se anota H-4: el cuadro de valores unitarios tiene una dimensión —año de construcción— que la tabla
actual no guarda.

Archivos previstos: `uit.md`, `predial-tramos-y-alicuotas.md`, `predial-deducciones.md`,
`predial-plazos-de-pago.md`, `predial-inafectaciones.md`, `valores-unitarios-‹ejercicio›.md`,
`depreciacion.md`, `aranceles-‹ejercicio›.md`, `vehicular-valores-referenciales-‹ejercicio›.md`,
`alcabala.md`.

**El arancel tiene una particularidad que conviene decir antes de empezar:** la norma es nacional
—resolución del sector Vivienda— pero sus valores están referidos a las vías de cada localidad. Su
transcripción es por municipalidad y necesita las vías cargadas (#16); no necesita D-01.

**Criterios de aceptación**

- [ ] Ningún archivo con transcriptor igual a verificador, ni sin fecha de publicación.
- [ ] Ninguna cifra sin norma y artículo al lado.
- [ ] Toda fila `‹VERIFICAR›` de NEG-02 §2 asignada a D-02a apunta al archivo que la cierra.
- [ ] **Ninguna fila cargada en la base.** Cargar es un paso posterior y depende de D-13 (§4).

**Cómo se demuestra que puede fallar:** poner el mismo nombre en «transcribió» y «verificó», o dejar
la fecha de publicación vacía, y la revisión —o el script, si se automatiza— rechaza el archivo.

**Tamaño:** L, y es transcripción legal, no programación. **No depende de D-01.** **Es la de mayor
rendimiento por unidad de esfuerzo, y la única que nadie está esperando para empezar.**

### E-4 · El tercer estado `PROVISIONAL` — con recomendación de **no hacerlo todavía**

**Recomendación: aplazarlo, y reevaluarlo cuando E-5 esté terminado.** El motivo es medible: la
cobertura de punta a punta que E-4 promete ya existe en el nivel donde importa. `MotorDeReglasTest`
arma sus propios `ParametrosSellados` en memoria —`parametrosFicticios()`, con claves llamadas
`FICTICIO` y conceptos `RAMA_UNO`/`RAMA_DOS` para que nadie los confunda con el predial— y ejercita
el grafo, la convergencia, la agregación por contribuyente y el fallo por parámetro ausente, sin
base de datos y sin una sola cifra normativa. Lo que E-4 añade sobre eso es el **camino
persistido**, a cambio de meter cifras sin valor normativo en la base de producción. El intercambio
no se justifica hasta que aparezca un camino de emisión que no se pueda cubrir en memoria; entonces
la decisión tendrá delante el caso concreto y no una hipótesis.

**Si aun así se aprueba, esta es la forma mínima, y H-7 dice por qué no basta con el estado del
conjunto:**

1. **Base.** `V15__conjunto_provisional.sql`: el `CHECK` de `conjunto_parametros.estado` admite
   `PROVISIONAL`; `parametro_tributario` gana `origen varchar(12) NOT NULL DEFAULT 'NORMATIVO' CHECK
   (origen IN ('NORMATIVO','PROVISIONAL'))`; un disparador rechaza la transición `PROVISIONAL →
   SELLADO` —**no existe**: para sellar hay que recargar con documento fuente—; y otro rechaza un
   parámetro `PROVISIONAL` en un conjunto que no lo es. Al revés sí se admite: un conjunto
   provisional lleva las cifras reales que ya haya y las inventadas que le falten —es el caso
   realista—, y por eso lo que se prohíbe no es su contenido sino su sellado.
2. **Tipo, no bandera.** `ParametrosDeSimulacion` es un **tipo distinto** de `ParametrosSellados`,
   no un `boolean` dentro de él. Una bandera se puede ignorar; un tipo no compila en el camino
   equivocado. `EntradaDeCalculo` pasa a recibir la interfaz común y **la emisión sigue exigiendo
   `ParametrosSellados`**.
3. **Regla de arquitectura con su muestra.** Ninguna clase que emita —determinación, valor, asiento—
   puede depender del paquete de simulación, con
   `muestras/MuestraDeEmisionConParametrosProvisionales` violándola, como exige ARQ-04.
4. **Demostración.** Contra PostgreSQL: sellar un conjunto provisional falla; agregar un parámetro
   provisional a un conjunto abierto falla; `LectorDeParametros.vigenteEn` no devuelve un
   provisional. Y la prueba gemela: **quitando el disparador, la emisión con cifras inventadas
   ocurre de verdad** —igual que la guardia del pool—.

Sin las cuatro, esto es la antiestrategia de §5 y no debe escribirse.

**Tamaño:** L. **Exige decisión explícita.**

### E-5 · El corpus de casos, ejecutable desde el primer día

#30 pide «casos de prueba con las cifras esperadas en blanco». Escrito así, un corpus sin cifras no
verifica nada. Hay una forma en que sí: **las cifras se dejan en blanco, pero las aristas del grafo
no.** Con parámetros ficticios se puede comprobar hoy, sin cerrar D-02, que cada caso aplica
exactamente las reglas que declara y produce exactamente los conceptos que declara.

**Entregable:** `backend/sgtm-parametros/src/test/resources/casos/RT-xxx.csv`, un archivo por regla,
con estas columnas:

| Columna | Contenido | ¿Bloqueada por D-02? |
|---|---|---|
| `caso` | `RT-013-c03` | No |
| `descripcion` | El caso borde de NEG-05 §2 que cubre | No |
| `ejercicio` | Del hecho imponible | No |
| `entradas` | Áreas, antigüedad, `% propiedad`: datos del predio, no cifras normativas | No |
| `parametros_requeridos` | Las claves que la regla pide, **sin sus valores** | No |
| `reglas_esperadas` | Qué `RT-xxx` deben haberse aplicado | No |
| `conceptos_esperados` | Qué conceptos deben existir al final | No |
| `esperado` | El importe | **Sí — vacío hasta D-02a** |
| `fuente_del_esperado` | SRTM del MEF, M02, liquidación real | **Sí** |

**Lo que la prueba exige ya, con la columna vacía:**

- [ ] Cada `RT-001`…`RT-016` tiene al menos un caso, y cada caso borde de NEG-05 §2 tiene su fila.
- [ ] Con parámetros ficticios, el motor aplica **exactamente** `reglas_esperadas` y produce
      **exactamente** `conceptos_esperados`. Ni una regla de más, ni un concepto de menos.
- [ ] `parametros_requeridos` coincide con lo que la regla pide de verdad: se corre con un
      `ParametrosSellados` vacío y se recogen las `ParametroAusente`; la lista tiene que coincidir
      exactamente. **Esto convierte la columna en verificada, y le dice a E-3 qué hay que
      transcribir**: el inventario de parámetros deja de ser una lista escrita a mano.
- [ ] Cuando `esperado` se llena, exige `fuente_del_esperado` y la comparación es al céntimo.
- [ ] El build imprime cuántos casos siguen `SIN_CIFRA`. Bajar ese número es el progreso de D-02a;
      subirlo sin querer se ve.

**Cómo se demuestra que puede fallar:** quitar una arista declarada de un caso, o registrar una
regla de más en el catálogo, y la comprobación de forma se pone roja; llenar un `esperado` con una
cifra equivocada y la comparación al céntimo se pone roja; declarar un parámetro que la regla no
pide y la comprobación de `parametros_requeridos` se pone roja.

**Es además el insumo de `CAL-02`.** **Tamaño:** M. **No requiere autorización.**

### E-6 · Municipalidad de demostración para lo que bloquea D-02b

Cinco issues esperan una ordenanza local **y su ratificación provincial**. Pero el sistema es
multi-municipal: una ordenanza **real, publicada y ratificada** de cualquier municipalidad se puede
cargar en un tenant de demostración con su documento fuente auténtico. El aislamiento ya está
verificado, así que los dos conjuntos conviven.

**Entregable**

1. La ordenanza elegida y su acuerdo de ratificación provincial, transcritos con la cabecera de E-3
   en `docs/10-negocio/valores-normativos/arbitrios-‹municipalidad›-‹ejercicio›.md`.
2. `municipalidad.es_demostracion boolean NOT NULL DEFAULT false`, en migración. El hecho vive en la
   base, no en configuración: una bandera de despliegue no viaja con el dato.
3. Todo documento emitido bajo un tenant de demostración sale marcado como tal.
4. La siembra del tenant **no está en el classpath del perfil por omisión**, con la comprobación que
   lo verifica —el precedente es `comprobar-compilaciones` del frontend, que demuestra que el juego
   de datos simulado no llega a producción—.

**Condiciones que no se negocian:** la ordenanza es real y está ratificada —inventar una es
exactamente lo que D-02 prohíbe— y el tenant de demostración no emite contra ningún contribuyente
real.

**Cómo se demuestra que puede fallar:** poner la siembra en el perfil por omisión y la comprobación
de compilaciones se pone roja; quitar la marca del documento y la prueba del documento emitido se
pone roja.

**Tamaño:** M. **Exige elegir la ordenanza.**

### E-7 · La campaña de observación de D-03c, y lo que se puede construir antes

No es D-02, es el bloqueo que comparten #22, #30, #45 y #49, y el más lento. Pero tiene una parte de
código que **no depende de la observación** y que hoy falta (H-6).

**Entregable 1 — el tipo que puede expresar la respuesta.** `PoliticaDeRedondeo` es hoy un par
`(escala, modo)` para todo el cálculo. D-03c trata de **puntos**. Se introduce `PuntoDeRedondeo`,
uno por cada punto candidato que las secuencias de NEG-05 revelan —valor unitario con el 5 %, valor
unitario depreciado, valor por nivel, metrado de obras complementarias, valor de obra, autovalúo del
predio, `% actualización`, `% propiedad`, base del contribuyente, tramo, impuesto anual, cuota,
reajuste, interés—, y la política se resuelve **por punto**. Un punto sin política es una excepción,
nunca «no redondear»: hoy el modo de falla es silencioso, porque no redondear también produce un
importe plausible.

**Entregable 2 — el formulario de la campaña.** `docs/10-negocio/observaciones-srtm-mef/`, una ficha
por determinación observada: predio y sus características, el desarrollo intermedio que la pantalla
muestra, y qué punto de la lista revela. GOB-02 §«Cómo se cierra D-03c» ya fija el procedimiento en
dos pasos para no usar el mismo contraste que descubre y que valida.

**Entregable 3 — el resultado como dato.** Cada punto observado entra como parámetro
(`REDONDEO:‹punto›` → escala y modo), no como código. Escribirlo a mano ya lo detecta el escáner de
fuentes.

**Cómo se demuestra que puede fallar:** quitar un punto de la política y el cálculo falla en vez de
seguir sin redondear; compilar una política a mano y el escáner la detecta —ya lo hace hoy—.

**Tamaño:** M el código, indeterminado la campaña. **Depende de acceso al SRTM del MEF, no de
D-01.**

## 4. Lo que hay que decidir antes de empezar

| # | Decisión | Quién | Recomendación |
|---|---|---|---|
| 1 | **E-4 `PROVISIONAL`: sí o no** | Dirección | **No todavía.** Reevaluar al terminar E-5, con el caso concreto delante (§E-4) |
| 2 | **E-6: qué ordenanza** | Rentas | Cualquiera real y ratificada; la elección es lo único que falta |
| 3 | ~~**D-13: ámbito de las tablas de valuación**~~ | Arquitectura | **Cerrada 2026-08-28** (ADR-0017): una vez con `municipalidad_id` nulo. H-5 resuelto por construcción |
| 4 | ~~**H-4: la dimensión que falta**~~ | Arquitectura + Catastro | **Ya estaba en V18.** Lo que sigue abierto es H-14: si RT-002 describe el cuadro o una vista que combina dos tablas |
| 5 | **H-3: anuncios y costas coactivas** | Rentas + asesoría legal | Se resuelve con la fuente en la mano, dentro de E-1 |

## 5. Lo que no se hace

- **Cifras inventadas «provisionales» sin un estado que impida emitir con ellas.** Es E-4 sin sus
  cuatro contenciones.
- **La alícuota detrás de una bandera de configuración.** Un archivo no tiene vigencia, ni documento
  fuente, ni doble verificación, ni se audita (ADR-0007, «Alternativas consideradas»).
- **Implementar y corregir después.** Una implementación de regla ya usada en una emisión no se
  modifica nunca; se crea otra con su rango de vigencia. «Después» cuesta un padrón.
- **Saltarse el documento fuente porque es de prueba.** Cargar sin documento fuente falla, y esa
  falla es una funcionalidad.

## 6. Orden

| Orden | Paquete | Desbloquea | Depende de |
|---|---|---|---|
| 1 | **E-7** (entregables 1 y 2) y **E-3**, en paralelo | el camino crítico de cuatro issues; #17, #45, #49 | nada; E-7 necesita acceso al SRTM del MEF para su campaña |
| 2 | **E-1** | #17 y #22 enteros, y #43 que nunca estuvo bloqueado; del resto, hace visible qué espera a qué | nada |
| 3 | **E-2** | los doce restantes, cada uno por su mitad de estructura | E-1 |
| 4 | **E-5** | hace terminables las mitades «estructura» de E-2 | E-2 |
| 5 | **E-6** | #31, #47, #51; parte de #35 y #48 | ordenanza elegida |
| 6 | **E-4**, si se decide | el extremo a extremo persistido de #30 y #31 | decisión explícita |

E-1, E-2, E-3, E-5 y los entregables de código de E-7 no requieren autorización de nadie y no pueden
hacer daño: no cargan una sola cifra.

## 7. Referencias

- [GOB-02 — Decisiones abiertas](decisiones-abiertas.md), §«Por qué D-02 bloquea de verdad» y
  §«Cómo se cierra D-03c».
- [NEG-02 — Marco normativo](../10-negocio/marco-normativo.md) §2: las 27 líneas `‹VERIFICAR›`.
- [ADR-0007 — Parámetros tributarios versionados y sellados](../30-arquitectura/adr/ADR-0007-parametros-versionados.md).
- [CAL-01 — Estrategia de pruebas](../A0-calidad/estrategia-de-pruebas.md).
- `../srtm` NEG-05 §0.1, §1, §5 y §6, y ARQ-09 §1.4, §2 y §3.
- Issues [#116](https://github.com/hneyra/sgtm/issues/116) y [#58](https://github.com/hneyra/sgtm/issues/58).

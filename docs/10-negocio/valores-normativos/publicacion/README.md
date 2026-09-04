# Lo publicable del corpus

`parametros-2026.csv` es el **derivado publicable** del corpus de valores normativos: la única
entrada desde la que `PublicarParametros` escribe en `parametro_tributario`. No es una fuente —lo
son los archivos del corpus, uno por norma— sino su proyección a las columnas que la tabla tiene.

**Ninguna cifra de este archivo se tecleó de memoria.** Cada una está letra por letra en el archivo
del corpus que su fila nombra, y [`verificar-publicacion.mjs`](../../verificar-publicacion.mjs) lo
comprueba fila a fila en cada PR: que el archivo exista, que su `Estado` sea `VERIFICADO`, que las
dos firmas sean las de su cabecera y sean distintas, que el documento fuente y el artículo estén
ahí, que cada fragmento del valor de texto aparezca verbatim y que la cifra numérica esté en ese
mismo texto. Que la comprobación puede fallar lo demuestran las doce muestras de `_muestras/`, una
por prohibición, con [`verificar-las-muestras-de-publicacion.mjs`](../../verificar-las-muestras-de-publicacion.mjs).

## La doble firma ya ocurrió, y es la que se guarda

ADR-0007 exige dos firmas para una cifra normativa, y `parametro_doble_verificacion_ck` (V1) lo
sostiene en la base: `usuario_aprueba <> usuario_carga`. **Las dos firmas de este archivo son las
del corpus**, no las del proceso que carga: `transcribio` y `verifico` se copian de la cabecera del
archivo del corpus y viajan tal cual a `usuario_carga` y `usuario_aprueba`. Quien corre
`publicar-parametros.sh` no firma nada; transporta lo que ya se firmó al leer la norma.

> La UIT estuvo verificada por «Agent» hasta el 2026-08-28 —una segunda lectura hecha por un
> agente y no por una persona, y quedó dicho aquí como deuda—. Ese día se re-verificó con firma
> humana: `uit.md` dice `Verificó | HNA` y las cinco filas de la UIT viajan con
> `usuario_aprueba = 'HNA'`, como los demás archivos del corpus. Cambiarlo costó lo que este
> párrafo anunciaba: una celda de `uit.md` y la columna `verifico` de cinco filas de este CSV,
> ni una línea de código.

## Un solo archivo para los dos pasos

Las tres primeras columnas son exactamente `tipo,clave,vigenciaDesde`, que es lo que
`ImportarParametrosDelConjunto` lee; las demás las ignora. Así el mismo archivo sirve para publicar
el valor y para componer el conjunto, y no hay dos listas que puedan separarse:

```bash
infra/carga-de-datos/publicar-parametros.sh      --ambiente stg --archivo parametros-2026.csv
infra/carga-de-datos/abrir-conjunto-parametros.sh --ambiente stg --municipalidad-id 4 \
    --conjunto-id N --archivo parametros-2026.csv
```

## Las treinta y dos filas, y de dónde sale cada una

| Tipo | Clave | Filas | Archivo del corpus |
|---|---|---|---|
| `UIT` | — | 5 (2022–2026) | `uit.md` |
| `TRAMO_PREDIAL` | `1`, `2`, `3` | 3 | `predial-tramos-y-alicuotas.md` |
| `TRAMO_PREDIAL_LIMITE` | `1`, `2` | 2 | `predial-tramos-y-alicuotas.md` |
| `DEDUCCION_PENSIONISTA` | — | 1 | `predial-deducciones.md` |
| `DEDUCCION_ADULTO_MAYOR` | — | 1 | `predial-deducciones.md` |
| `PREDIAL_MINIMO` | — | 1 | `predial-minimo.md` |
| `PLAZO` | `PRESCRIPCION-DECLARACION_PRESENTADA`, `PRESCRIPCION-SIN_DECLARACION`, `PRESCRIPCION-AGENTE_RETENCION` | 3 | `prescripcion-y-plazos.md` |
| `PLAZO` | `REC1_CUMPLIMIENTO` | 1 | `prescripcion-y-plazos.md` |
| `PLAZO` | `NOTIFICACION_VALOR-RD`, `NOTIFICACION_VALOR-RM`, `NOTIFICACION_VALOR-OP` | 3 | `valores-plazos-de-reclamacion.md` |
| `PLAZO` | `PRESCRIPCION_INICIO-PREDIAL`, `PRESCRIPCION_INICIO-VEHICULAR` | 2 | `prescripcion-inicio-del-computo.md` |
| `ALCABALA_ALICUOTA` | — | 1 | `alcabala.md` |
| `ALCABALA_TRAMO_INAFECTO_UIT` | — | 1 | `alcabala.md` |
| `ESPECTACULO_ALICUOTA` | `TAURINO-SUPERIOR-0.5-UIT`, `TAURINO-RESTO`, `CARRERAS-CABALLOS`, `CINEMATOGRAFICO`, `MUSICA-GENERAL`, `FOLCLOR-TEATRO-ZARZUELA-OPERA-BALLET-CIRCO`, `OTROS` | 7 | `espectaculos.md` |
| `FACTOR_OFICIALIZACION` | — | 1 (solo 2026) | `obras-complementarias-y-oficializacion-2026.md` |

La **UIT no lleva clave**: es la forma del tipo con un solo valor, y las cinco filas se distinguen
por `vigencia_desde`, como describe `LlaveDeParametro`. Cada una vale sólo su ejercicio
(`vigencia_hasta` en el 31 de diciembre), que es lo que dice `uit.md`: «la UIT de un año no deroga
la del anterior».

Las filas del TUO y de la Ley 30490 toman como `vigencia_desde` la **fecha de publicación** que su
cabecera declara —2004-11-15 y 2016-07-21—, y no un 1 de enero. Es la única fecha que el corpus
afirma: `predial-deducciones.md` lo dice expresamente al no resolver «si es el ejercicio 2016 o el
2017».

### Lo que el tramo del predial no resuelve

`TRAMO_PREDIAL` publica una fila por tramo, con la alícuota en `valor_numerico` y el tramo y su
alícuota **tal como los escribe la norma** en `valor_texto` (`Hasta 15 UIT; 0.2%`). Lo que ese
`0.2` significa —fracción o porcentaje— **no lo decide este archivo**: lo decide la regla que lo
consuma, y esa regla espera D-02a. Se publica el número que la norma imprime, sin convertirlo,
porque convertir unidades es exactamente lo que la transcripción prohíbe; el texto que lo acompaña
lleva el `%` para que la fila no se pueda leer mal en la base.

### La columna `valor_maquina`, y por qué hay dos formas del mismo valor

Las nueve filas de `PLAZO` traen una columna más, la última: `valor_maquina`. Es lo que va a
`parametro_tributario.valor_texto` —`4 ANIOS`, `7 DIAS_HABILES`—, mientras que `valor_texto` del CSV
sigue siendo la **transcripción verbatim** de la norma —«prescribe a los cuatro (4) años», «dentro
del plazo de siete (7) días hábiles de notificado»—, que es lo que se compara letra por letra contra
el corpus.

Hacen falta las dos porque no caben en una. `Plazo.de` sólo acepta «cantidad UNIDAD» y **rechaza a
propósito toda lectura tolerante**: un plazo interpretado «lo mejor posible» sale plausible y
equivocado, que es el modo de falla que nadie detecta. La norma, en cambio, escribe «cuatro (4)
años». Reescribir el `valor_texto` para que el código lo lea sería exactamente lo que la regla del
verbatim prohíbe; publicar la letra de la norma sería publicar algo que revienta en el primer uso,
en producción, contando el plazo de una REC-1.

Tres comprobaciones nuevas lo sostienen, con su muestra cada una:

| Qué exige | Muestra que lo demuestra |
|---|---|
| El entero de `valor_maquina` es el `valor_numerico` que la regla 5 ya buscó en la norma | `cifra-que-el-plazo-no-dice.csv` (`5 ANIOS` con `valor_numerico` 4) |
| Su unidad corresponde a las palabras del texto verbatim | `unidad-que-no-es-la-de-la-norma.csv` (`7 DIAS_CALENDARIO` sobre «días hábiles») |
| Una fila `PLAZO` la trae, y con la forma que `Plazo.de` acepta | `plazo-sin-forma-de-maquina.csv` |

La segunda es la que importa: veinte días hábiles y veinte calendario no son lo mismo, y de esa
diferencia depende si un expediente coactivo nació antes de tiempo. **Las unidades no están escritas
en la comprobación**: se leen de `UnidadDePlazo`, y una unidad del enumerado sin palabras declaradas
pone el verificador en rojo antes de que nadie pueda publicarla.

Lo que la comprobación **no** puede saber es si la clave con la que se publica es la que el código
lee: `PLAZO:PRESCRIPCION-DECLARACION` en vez de `PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA` pasa en
verde —la cifra está en la norma, las firmas son las del corpus— y deja el parámetro cargado bajo un
nombre que nadie pide. Eso lo cierran dos pruebas que leen **este** archivo desde el módulo que lo
consume: `PlazosDelDerivadoTest` (`sgtm-valores`) y `PlazoDeLaRec1DelDerivadoTest`
(`sgtm-coactiva`).

### Las fechas de vigencia de los plazos

Mismo criterio que las filas del TUO de Tributación Municipal —la única fecha que el corpus
afirma—, aplicado a dos normas distintas:

- **2013-06-22** para los tres plazos del art. 43. Es la fecha de publicación en El Peruano del
  D.S. N.º 133-2013-EF, que aprobó el TUO del Código Tributario, y es la que la cabecera del
  archivo del corpus declara.
- **2004-01-10** para `REC1_CUMPLIMIENTO`. La cabecera de `prescripcion-y-plazos.md` da tres fechas
  para la Ley 26979 (1998-09-21 el texto original, 2008-12-06 el TUO del D.S. 018-2008-JUS), pero
  **el texto que §1 transcribe no es el original**: es el que le dio el art. 1 de la Ley N.º 28165,
  publicada el 2004-01-10, como el propio §1 dice. La vigencia que se publica es la del texto que
  se transcribió, no la de la ley que lo contiene.
- **2013-06-22** también para las filas de `NOTIFICACION_VALOR-RD`/`-RM` (art. 137) y las de
  `PRESCRIPCION_INICIO` (art. 44): salen del mismo TUO del Código Tributario, en la edición que
  `valores-plazos-de-reclamacion.md` y `prescripcion-inicio-del-computo.md` consultaron.
- **2008-12-06** para `NOTIFICACION_VALOR-OP`: su fundamento es el art. 25.1.d del TUO de la Ley
  26979, y esa es la fecha de publicación del D.S. 018-2008-JUS que la cabecera afirma.

### Las dos cifras que la norma implica y no imprime

`NOTIFICACION_VALOR-OP` publica **cero (0) días hábiles** y `PRESCRIPCION_INICIO-*` un desfase de
**un año**: ninguna de las dos está impresa en un artículo. La primera es la lectura del art.
25.1.d —una orden de pago emitida conforme a ley es exigible sin espera, y reclamarla exige el pago
previo—; la segunda, la derivación del numeral 1 del art. 44 cruzado con el vencimiento de febrero.
Por eso su `valor_texto` combina el fragmento de la norma con la **frase de derivación de §2 del
archivo del corpus** —«el desfase publicado es de cero (0) días hábiles», «desfase de un año
respecto del ejercicio»—: la cifra derivada nace en ese §2, con las dos firmas del archivo, y el
verificador la exige verbatim ahí igual que exige la letra de la norma en §1.

### Las diez que faltaban, y solo una por una decisión

`alcabala.md` y `espectaculos.md` están `VERIFICADO` desde el 2026-08-28, son de **norma nacional**,
sus dos firmas están, y su §2 dice de sí mismos —los dos, con la misma frase— «**No se carga con
este archivo. Se carga con el derivado de `publicacion/`**». Y no tenían ninguna fila aquí.

No había un motivo escrito en ninguna parte: simplemente nadie las había pasado. Se añadieron el
2026-08-30 y publican de verdad —nueve filas sobre las 22 que ya estaban, medido contra `stg`—.

**Y con ellas entró la décima, que sí esperaba a algo:** `FACTOR_OFICIALIZACION`. Su archivo decía
«en cuanto una segunda persona vuelva a la fuente y firme, es una fila de `parametros-2026.csv` como
cualquier otra»; la firma llegó el 2026-08-30 (#436) y la fila entró detrás. Con eso, **uno de los
cuatro factores de D-11 deja de estar solo transcrito y pasa a estar publicado**: la regla que
valorice obras complementarias ya no tiene que fallar nombrando `FACTOR_OFICIALIZACION`, porque el
`0,68` está en el conjunto.

El conjunto del ejercicio pasa así de 22 filas a **32**.

Es la clase de hueco que este verificador no podía ver: `verificar-publicacion.mjs` comprueba que
**lo que está aquí** esté en el corpus, no que **lo que está en el corpus** esté aquí. Lo que sí lo
delataba era su propio recuento final —«VERIFICADO y sin publicar»—, que baja de once archivos a
**ocho**. De esos ocho: cinco son cuadros y van por el otro archivo (`aranceles-2026.md`,
`depreciacion.md`, `transito-tabla-de-infracciones.md`, `valores-unitarios-2026.md`,
`vehicular-valores-referenciales-2026.md`), uno es de ordenanza local y espera a D-02b
(`derecho-tramite-licencia-edificacion-catacaos-2023.md`), uno no puede resolverse
(`predial-plazos-y-reajuste.md`, abajo) y queda `multa-tributaria.md`.

**`multa-tributaria.md` no se publica, y conviene decir por qué.** Sus sanciones no son cifras sino
**alternativas**: «30% de la UIT **o** 0.6% de los IN», «0.6% de los I **o cierre**». Y cuál de las
tres Tablas se aplica no lo decide quién cobra el tributo sino **qué es el contribuyente infractor**
—su régimen del impuesto a la renta—, que es lo que el propio archivo investigó y dejó escrito.
Publicar una fila por numeral obligaría a elegir una Tabla y una de las dos ramas de cada
alternativa: dos decisiones que la norma no toma y que este derivado no puede tomar por ella.

## Lo que hoy no se publica, y por qué

`predial-plazos-y-reajuste.md` está `VERIFICADO` y **no tiene ninguna fila aquí**. No es un olvido:
su propia sección «3. Qué no cabe hoy» dice que la norma no da una fecha sino una regla —«último
día hábil de febrero»— y deja sin decidir dónde vive el cálculo de ese día. Publicar una fecha
resuelta sería inventarla, y una fecha de vencimiento inventada es un plazo mal contado en todo un
padrón.

## Antes de sellar: la lista, y por qué es una lista y no un paso

**Sellar es un acto único por ejercicio.** El disparador de `V9` hace inmutable lo sellado y
`conjunto_sellado_uq` admite **un solo** conjunto sellado por ejercicio y municipalidad, así que a un
conjunto sellado **no se le puede añadir una cifra más**: la única salida de un sello prematuro es
abrir otra versión. Y un conjunto abierto no lo lee nadie —la lectura exige `estado = 'SELLADO'`—, de
modo que el ejercicio se queda sin poder completarse hasta que alguien lo rehaga entero.

Por eso `--sellar` es explícito, y por eso esta lista existe. **Lo que hay que poder responder que
sí, antes de escribirlo:**

| | Qué comprobar | Cómo |
|---|---|---|
| 1 | Las filas sueltas están publicadas, y sin duplicar | `publicar-parametros.sh` termina en `RECHAZADAS=0`, o dice «ya estaba publicado» para todas — que es lo que se espera de la segunda corrida |
| 2 | Los cuadros que el ejercicio necesita están publicados | `publicar-cuadros.sh`, una edición por cuadro. Hoy la depreciación entra; la vehicular espera a #388 (su archivo de filas no cabe en un `ConfigMap`) y los valores unitarios no tienen camino de carga |
| 3 | El arancel de la municipalidad está cargado | `cargar-arancel-vial.sh`, contra **ese** conjunto |
| 4 | No falta ninguna cifra que una regla vaya a pedir | Cada `‹llave›` que una regla nombre y no esté produce un **422 nombrando la llave**. Eso es correcto en una consulta y es un desastre en una emisión masiva |
| 5 | Nada de lo que falta es de este ejercicio | Ver «Lo que hoy no se publica, y por qué», arriba: lo que espera a D-02b y D-02c **no** puede entrar hoy, y sellar sin ello es sellar un ejercicio incompleto |

**El punto 5 es el que decide, y hoy la respuesta es que no.** Faltan los valores unitarios (H-14:
sin camino de carga), el `% actualización` (D-11: sin fuente) y todo lo de ordenanza local (D-02b).
Sellar 2026 hoy dejaría el ejercicio con la mitad de sus cifras y sin poder recibir la otra mitad.

### Lo que sí está probado, contra `stg` real

El 2026-08-29, después de que #434 subiera `stg` de 25 a 48 migraciones y de que la credencial de
`rol_carga_parametros` llegara al motor (#435):

| Paso | Resultado |
|---|---|
| `abrir-conjunto-parametros.sh --ejercicio 2026` | `CONJUNTO_ID=3`, versión 1 |
| `publicar-parametros.sh` | **`PUBLICADAS=22 RECHAZADAS=0`** |
| `publicar-cuadros.sh` (edición de depreciación) | **`PUBLICADAS=492 RECHAZADAS=0`**, las cuatro tablas del Anexo I con su columna de uso |
| La **segunda** corrida de `publicar-parametros.sh` | informa las 22 como «ya estaba publicado» y **no duplica**: 23 filas de `parametro_tributario` antes, 23 después |

Lo que **no** se hizo, a propósito: `--sellar`.

## Los cuadros van por el otro archivo

Los tres cuadros de valuación no entran en `parametros-2026.csv`, y no es porque falte decidir nada:
van a otras tablas —`valor_unitario_edificacion`, `depreciacion` y `valor_referencial_vehiculo`— y
tienen miles de filas, así que su entrada es
[`cuadros-2026.csv`](cuadros-2026.csv), el **manifiesto de ediciones**, que lee `PublicarCuadros`.
D-13 se cerró el 2026-08-28 ([ADR-0017](../../../30-arquitectura/adr/ADR-0017-tablas-de-valuacion-nacionales.md)):
las tres son catálogos nacionales.

Un manifiesto no lleva ninguna cifra. Cada fila declara **una edición** —la resolución entera, con su
documento fuente y las dos firmas del corpus— y nombra el archivo de filas con su `sha256`, que
[`verificar-cuadros.mjs`](../../verificar-cuadros.mjs) comprueba en cada PR y `PublicarCuadros`
recalcula antes de publicar una sola fila.

| Cuadro | Estado |
|---|---|
| `vehicular-valores-referenciales-2026.md` | **Publicable hoy.** Su anexo es `fuentes/tvr-2026/tvr-2026.csv`, extraído mecánicamente y con su huella firmada; entran 54 111 filas |
| `valores-unitarios-2026.md` | **No, y ya no por sus cifras ni por sus firmas.** Las 27 del Anexo I.2 están leídas del PDF y `VERIFICADO` desde el 2026-08-29. Le faltan tres cosas y una es de este directorio: **no tiene archivo de filas ni huella**, así que no hay fila que escribir aquí; `FilaDelManifiesto.CUADROS` no incluye `VALOR_UNITARIO`; y sus **3 partidas** conviven con las **7** que declara el esquema, de modo que publicarlas dejaría cuatro sin fila sin que nada lo dijera. Sigue además sólo la región Costa de las cuatro (GOB-03, H-14) |
| `depreciacion.md` | **Publicable desde el 2026-08-29** (`V57`, H-15). Su archivo de filas es `fuentes/depreciacion-rnt-2016/depreciacion.csv`, **derivado del propio archivo del corpus** y no de un PDF —cabe entero en él— con su huella firmada; entran 492 filas, las cuatro tablas del Anexo I |

`PublicarCuadros` rechaza el que falta **nombrando el motivo**, en vez de publicar un cuadro
incompleto que nadie distinguiría de uno completo.

Y el manifiesto pone **`cuadro` en la última columna**, no en la primera. Es lo mismo que
`valor_maquina` en `parametros-2026.csv` y por lo mismo: sus tres consumidores lo leen por
**posición**, y las tres primeras columnas tienen que ser las de la llave —`tipo,clave,vigencia_desde`—
porque el mismo archivo sirve para publicar la edición y para componerla en el conjunto. Con
`cuadro` delante, ese segundo paso leía «2026» como fecha, rechazaba todas las filas y sellaba el
conjunto **sin la edición dentro**: el nombre del cuadro congelado sin su contenido.

De la familia de los plazos quedan fuera dos cosas, cada una por su motivo:

- el **cuarto plazo del art. 43** —el de solicitar o efectuar la compensación y la devolución—:
  está transcrito y verificado en `prescripcion-y-plazos.md`, y **ningún código lo consume**.
  `CausalDePrescripcion` no tiene esa causal porque el sistema no tramita solicitudes de
  devolución. Publicarlo sería una fila que nadie lee, y una fila que nadie lee no se puede
  comprobar contra su uso: no aparecería en ninguna de las dos pruebas que cruzan este archivo con
  las claves del código. Se cierra el día que exista la operación que lo pida;
- el **inicio del cómputo para arbitrios** (`PRESCRIPCION_INICIO-ARBITRIOS`): no tienen
  declaración jurada del contribuyente, así que el numeral 1 del art. 44 no les aplica, y elegir
  entre el 2 y el 3 es la decisión doctrinaria que `prescripcion-inicio-del-computo.md` §2 deja
  abierta a propósito. Un desfase «razonable» resuelto en silencio es exactamente lo que la
  regla 5 prohíbe.

El resto de archivos `VERIFICADO` —`alcabala.md`, `aranceles-2026.md`, `espectaculos.md`,
`multa-tributaria.md`, `transito-tabla-de-infracciones.md` y
`derecho-tramite-licencia-edificacion-catacaos-2023.md`— **sí** declaran `parametro_tributario` en
su sección 2 y son publicables con esta misma herramienta sin cambiarle una línea. No están aquí
porque este archivo empieza por lo que el conjunto de 2026 necesita para poder sellarse; añadirlos
es escribir filas y correr la comprobación, no construir nada. `verificar-publicacion.mjs` los
lista al final de su salida, en un libro mayor, para que la lista de pendientes no dependa de que
alguien se acuerde.

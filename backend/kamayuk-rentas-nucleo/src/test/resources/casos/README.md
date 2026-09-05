# El corpus de casos de NEG-05

Un archivo por regla `RT-xxx`, una fila por caso. Lo comprueba
`CorpusDeCasosTest`, y no es documentación: es una prueba que se pone roja.

**La idea que lo hace útil:** #30 pedía «casos de prueba con las cifras esperadas en blanco», y
escrito así un corpus sin cifras no verifica nada. Hay una forma en que sí: **se deja en blanco la
cifra, no las aristas del grafo.** Con parámetros ficticios se comprueba hoy que un caso aplica
exactamente las reglas que declara, produce exactamente los conceptos que declara y pide exactamente
los parámetros que declara —recogidos corriéndolo con un conjunto vacío, no escritos a mano—.

## Por qué vive aquí y no en `kamayuk-rentas-parametros`

GOB-03 §E-5 lo situaba en `kamayuk-rentas-parametros/src/test/resources/casos`. Está en `kamayuk-rentas-nucleo` porque
**las reglas del predial son de `rentas`**, y `parametros` no depende de ningún contexto acotado: es
de solo lectura para todos (ARQ-01). Un corpus que corre reglas tiene que vivir donde están las
reglas.

## Formato

Separador `;`, y `|` dentro de una celda para las listas. No es coma porque las celdas llevan llaves
como `ARANCEL:AV-GRAU` y descripciones con comas.

| Columna | Qué lleva |
|---|---|
| `caso` | `RT-xxx-cNN`. El prefijo tiene que ser el del archivo |
| `caso_borde` | El caso borde de NEG-05 §2 que cubre, si cubre alguno. Vacío si no |
| `descripcion` | Qué situación es |
| `ejercicio` | Del hecho imponible |
| `entradas` | `CONCEPTO=valor`, los datos declarados de la partida. **Nunca una cifra normativa** |
| `caracteristicas` | `nombre=valor`, lo que la partida **es**: la vía, la categoría, el material |
| `parametros_requeridos` | Las llaves que la regla pide, **sin sus valores** |
| `reglas_esperadas` | Los `RT-xxx` que deben haberse aplicado |
| `conceptos_esperados` | Los conceptos que el cálculo debe haber **producido** —los declarados no cuentan— |
| `estado` | Cómo se puede comprobar hoy. Ver abajo |
| `esperado` | El importe, cuando su cifra ya está publicada y firmada. Vacío mientras no |
| `fuente_del_esperado` | De dónde sale ese importe. Obligatorio en cuanto haya importe |

## Los cinco estados

| Estado | Qué significa | Qué comprueba la prueba |
|---|---|---|
| `EJECUTABLE` | Sus reglas están registradas en el motor | Corre el caso: reglas, conceptos y parámetros, exactos |
| `FALLA_ESPERADA:‹excepción›` | Correrlo **debe** fallar | Que falle, y con esa excepción |
| `SIN_REGLA:‹bloqueo›` | La regla todavía no se puede escribir | Que **de verdad** no esté registrada. `D-11`, `D-02a`, `H-4`, `MOTOR`, `NEG-05` |
| `SIN_CRITERIO:‹fuente›` | La regla existe; lo que falta es la decisión de este caso borde | Que sea un caso borde del inventario de NEG-05 §2 |
| `FUERA_DEL_MOTOR:‹clase›` | La regla existe como función pura, con su propia prueba | Que la clase exista y que el motor no la registre |

**`SIN_CRITERIO` no estaba previsto: lo exigió la propia prueba.** Los tres casos borde de `RT-001`
se habían escrito como `SIN_REGLA`, y `RT-001` sí está registrada: la comprobación se puso roja y
tenía razón. Lo que falta ahí no es la regla, es la decisión.

## Qué hay que hacer al añadir una regla

Cuando una regla pasa a estar registrada, sus casos `SIN_REGLA` **ponen la prueba en roja**: eso es
lo que impide que el corpus se quede viejo sin que nadie lo note. Hay que pasarlos a `EJECUTABLE` y
completar `parametros_requeridos` —o dejar que la prueba diga cuáles son de verdad—.

## Qué hay que hacer al cerrar una cifra

Rellenar `esperado` **y** `fuente_del_esperado`, y declarar en `parametros_requeridos` las llaves
que el cálculo necesita. La prueba **no las rellena con valores ficticios**: las busca en
[`parametros-2026.csv`](../../../../../../docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv),
el derivado publicable del corpus, y falla nombrando la que no esté ahí. Comparar contra un arancel
inventado no probaría nada y pasaría en verde, que es peor que no tener la cifra; comparar contra
una cifra transcrita de la norma y firmada a dos manos (ADR-0007) es lo que hace útil la columna.

Declararlas **de menos** también falla: el conjunto se compone con exactamente las llaves que el
caso declara, así que el cálculo revienta al no encontrar la que falta en vez de aprovechar la que
otro caso dejó cargada.

### Las tres cifras cerradas hoy, y por qué solo tres

`RT-013-c01`, `RT-013-c02` y `RT-014-c01`: las dos reglas del **artículo 13 del TUO LTM** —el cuadro
progresivo y el mínimo imponible—, cuyos parámetros (UIT, tres tramos, dos límites y el mínimo)
están publicados y firmados. Las demás esperan a `D-11`, a los dos cuadros de GOB-03 (H-14, H-15) y
a los valores de ordenanza (`D-02b`). El recuento vive en `elCorpusDiceCuantoFalta` y es el libro
mayor de #188: bajarlo cuesta transcribir y firmar, y subirlo sin querer pone la prueba roja.

**Las entradas de `RT-013` cambiaron al cerrar su cifra, y no por capricho.** Con la UIT real, una
base de 5 000 se queda entera en el primer tramo: el caso «progresivo acumulativo» habría llevado
una cifra correcta sin ejercitar ni una vez lo que su descripción dice. Ahora cruza los tres tramos,
y `RT-013-c02` es el caso de NEG-05 §1 con cifras: tres predios que juntos entran al segundo tramo y
uno a uno no salen del primero. La diferencia entre las dos formas de calcular es lo que
`laBaseDelContribuyenteNoEsLaDeCadaPredio` mide.

### El redondeo todavía no sale del conjunto

Se compara con la política que **ADR-0018** decidió —cierre de cada regla, a céntimo, `HALF_UP`—
escrita en la propia prueba, porque el derivado no publica ninguna fila `REDONDEO:‹punto›`. El ADR
las deja publicables; el día que se publiquen, `elDerivadoTodaviaNoPublicaElRedondeo` se pone roja
para que quien lo haga venga aquí a leerlas del conjunto en vez de dejar dos verdades sobre el
mismo redondeo.

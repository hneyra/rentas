# Inicio del cómputo de los plazos de prescripción

| Campo | Valor |
|---|---|
| Norma | TUO del Código Tributario, aprobado por D.S. N.º 133-2013-EF (art. 44; el numeral 2 lo modificó el D. Leg. 1263, el 6 lo incluyó el D. Leg. 953 y el 7 lo incorporó el D. Leg. 1113); TUO de la Ley de Tributación Municipal, aprobado por D.S. N.º 156-2004-EF (declaración jurada del vehicular: art. 34) |
| Artículo | 44 (TUO del Código Tributario) — 34 (TUO de la Ley de Tributación Municipal) |
| Publicada | 2013-06-22 (D.S. 133-2013-EF); 2004-11-15 (D.S. 156-2004-EF) — las dos en El Peruano |
| Ejercicios que rige | 2013– (art. 44, con el numeral 7 vigente desde el 2012 por el D. Leg. 1113); 2004– (art. 34 del TUO LTM) |
| Filas de NEG-02 §2 | 31 |
| Transcribió | Agent, 2026-08-28 |
| Verificó | HNA, 2026-08-28 |
| Estado | VERIFICADO |

**Fuente consultada:** `textoCompleto-TUO-CT.pdf` (SUNAT), descargado por el dueño del repositorio
el 2026-08-28 y archivado en S3 —
`s3://sgtm-fuentes-normativas/fuentes-normativas/codigo-tributario/200105/2026-08-28T23-45-38Z__textoCompleto-TUO-CT.pdf`—;
el art. 34, del PDF del TUO LTM de `muniate.gob.pe` alcanzado por HNA el 2026-08-28. El TUO LTM
completo (D.S. 156-2004-EF, edición con concordancias) quedó también archivado:
`s3://sgtm-fuentes-normativas/fuentes-normativas/tributacion-municipal/200105/2026-08-28T23-45-44Z__DS-156-2004-EF-TUO-Ley-Tributacion-Municipal.pdf`.
Huellas de ambos en [`fuentes/README.md`](fuentes/README.md).

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.**

### Cómputo (art. 44 del TUO del Código Tributario)

> Art. 44: «COMPUTO DE LOS PLAZOS DE PRESCRIPCION. El término prescriptorio se computará:
>
> 1. Desde el uno (1) de enero del año siguiente a la fecha en que vence el plazo para la
> presentación de la declaración anual respectiva.
>
> 2. Desde el uno (1) de enero siguiente a la fecha en que la obligación sea exigible, respecto
> de tributos que deban ser determinados por el deudor tributario no comprendidos en el inciso
> anterior y de los pagos a cuenta del Impuesto a la Renta. (Numeral modificado por el art. 3 del
> D. Leg. 1263, vigente desde el 2016-12-11.)
>
> 3. Desde el uno (1) de enero siguiente a la fecha de nacimiento de la obligación tributaria, en
> los casos de tributos no comprendidos en los incisos anteriores.
>
> 4. Desde el uno (1) de enero siguiente a la fecha en que se cometió la infracción o, cuando no
> sea posible establecerla, a la fecha en que la Administración Tributaria detectó la infracción.
>
> 5. Desde el uno (1) de enero siguiente a la fecha en que se efectuó el pago indebido o en
> exceso o en que devino en tal, tratándose de la acción a que se refiere el último párrafo del
> artículo anterior.
>
> 6. Desde el uno (1) de enero siguiente a la fecha en que nace el crédito por tributos cuya
> devolución se tiene derecho a solicitar, tratándose de las originadas por conceptos distintos a
> los pagos en exceso o indebidos. (Numeral incluido por el art. 19 del D. Leg. 953.)
>
> 7. Desde el día siguiente de realizada la notificación de las Resoluciones de Determinación o
> de Multa, tratándose de la acción de la Administración Tributaria para exigir el pago de la
> deuda contenida en ellas. (Numeral incorporado por el art. 4 del D. Leg. 1113, que entró en
> vigencia a los sesenta (60) días hábiles siguientes a la fecha de su publicación.)»

En la edición consultada del TUO el artículo termina en el numeral 7: **el D. Leg. 1421 no le
añadió ningún párrafo** —modificó los arts. 78, 100, 120, 141, 148, 150 y 156, y trató la
prescripción de las resoluciones ya notificadas en una disposición complementaria transitoria,
fuera del artículo—. Se dice aquí porque una versión anterior de este archivo lo afirmaba de
memoria, y la fuente lo desmintió.

### La declaración jurada anual del vehicular (art. 34 del TUO de la Ley de Tributación Municipal)

> Art. 34: «Los contribuyentes están obligados a presentar declaración jurada:
>
> a) Anualmente, el último día hábil del mes de febrero, salvo que la Municipalidad establezca
> una prórroga.
>
> b) Cuando se efectúe cualquier transferencia de dominio. En estos casos, la declaración jurada
> debe presentarse hasta el último día hábil del mes siguiente de producidos los hechos.
>
> c) Cuando así lo determine la administración tributaria para la generalidad de contribuyentes y
> dentro del plazo que determine para tal fin.
>
> La actualización de los valores de los vehículos por las Municipalidades, sustituye la
> obligación contemplada por el inciso a) del presente artículo, y se entenderá como válida en
> caso que el contribuyente no la objete dentro del plazo establecido para el pago al contado del
> impuesto.»

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | `parametro_tributario`, tipo `PLAZO` |
| Clave | `PRESCRIPCION_INICIO-` + tributo (`PREDIAL`, `VEHICULAR`) |
| Ámbito | nacional |
| Vigencia | 2013– |

**La decisión de modelado, escrita.** El numeral 1 del art. 44 da una regla —el 1 de enero del
año siguiente al vencimiento de la declaración anual—, y el código pide un desfase por tributo:
`inicioDelComputo(tributo)` devuelve un `Plazo` que, sumado al ejercicio, da el 1 de enero en que
el término prescriptorio empieza a correr. El mapeo es derivación, no transcripción, y por eso
queda aquí con su razonamiento:

| Clave | Forma máquina | Derivación |
|---|---|---|
| `PRESCRIPCION_INICIO-PREDIAL` | `1 ANIOS` | Numeral 1 del art. 44: la DJ anual del predial del ejercicio N vence el último día hábil de febrero **del propio N** (TUO LTM art. 14, ya `VERIFICADO` en `predial-plazos-y-reajuste.md` — no se re-transcribe). El 1 de enero siguiente a ese vencimiento es el de N+1: desfase de un año respecto del ejercicio. |
| `PRESCRIPCION_INICIO-VEHICULAR` | `1 ANIOS` | El mismo razonamiento, con el art. 34.a del TUO LTM transcrito en §1: la DJ anual del vehicular vence también el último día hábil de febrero del ejercicio. Que la actualización de valores de la municipalidad sustituya a la DJ (último párrafo del art. 34) no mueve el vencimiento del que el numeral 1 computa. |

**Arbitrios: fuera, como decisión pendiente.** No tienen declaración jurada del contribuyente
—los determina la administración—, así que el numeral 1 no les aplica, y elegir entre el 2 y el 3
es una decisión doctrinaria que este archivo no toma. Resolverla en silencio con un desfase
«razonable» es exactamente lo que la regla 5 prohíbe: la clave `PRESCRIPCION_INICIO-ARBITRIOS`
no existe hasta que la decisión se tome y se escriba aquí con su fundamento.

La carga **no** depende de D-13: la clave va a `parametro_tributario` por el derivado publicable
de `publicacion/` y la consume `PlazosParametrizados.inicioDelComputo(tributo)` en `sgtm-valores`,
ya escrito y probado — hoy responde `PlazoSinParametrizar` nombrando la llave.

## 3. Qué no cabe hoy

El numeral 7 del art. 44 no se publica como parámetro: no es un desfase por tributo sino una
regla estructural, y ya la implementa #39 —la exigibilidad de la deuda contenida en un valor se
deriva de su notificación, fila a fila—. Se transcribe en §1 porque recortarlo sería reordenar el
artículo; lo que el sistema parametriza de este archivo son solo los desfases de §2. Las cifras
de §1 no se repiten en §2 ni en §3 a propósito: lo único citable de §2 son los desfases
derivados —«desfase de un año respecto del ejercicio»—, que §1 no imprime y nacen aquí con las
dos firmas de este archivo.

# Observación ‹ejercicio› — predio ‹referencia catastral›

| Campo | Valor |
|---|---|
| Ejercicio determinado | ‹año› |
| Referencia catastral | ‹código› |
| Municipalidad | ‹nombre› |
| Pantalla observada | ‹M02 · determinación individual / otra› |
| Observó | ‹nombre›, ‹fecha› |
| Verificó | ‹nombre distinto›, ‹fecha› |

> Sin nombre del contribuyente ni documento de identidad: el redondeo no depende de quién es.

## 1. Características del predio, como las muestra la pantalla

| Dato | Valor |
|---|---|
| Área de terreno | ‹m²› |
| Arancel de la vía | ‹valor› |
| Área construida por nivel | ‹nivel: m²› |
| Año de construcción / antigüedad | ‹…› |
| Categorías constructivas | ‹muros, techos, pisos, …› |
| Estado de conservación | ‹…› |
| Obras complementarias | ‹tipo, cantidad, metrado› |
| % propiedad | ‹…› |

## 2. El desarrollo intermedio, transcrito

**Con todos los decimales que la pantalla muestre.** Si un valor aparece con dos decimales donde el
cálculo daría más, ese es el hallazgo.

| Paso | Punto candidato | Valor en pantalla | Valor sin redondear (calculado) | ¿Coinciden? |
|---|---|---|---|---|
| Valor unitario + 5 % | `VALOR_UNITARIO_INCREMENTADO` | ‹…› | ‹…› | ‹sí / no› |
| Valor unitario depreciado | `VALOR_UNITARIO_DEPRECIADO` | ‹…› | ‹…› | ‹…› |
| Valor por nivel | `VALOR_POR_NIVEL` | ‹…› | ‹…› | ‹…› |
| Metrado de obra | `METRADO_DE_OBRA` | ‹…› | ‹…› | ‹…› |
| Valor de obra | `VALOR_DE_OBRA` | ‹…› | ‹…› | ‹…› |
| Autovalúo del predio | `AUTOVALUO_DEL_PREDIO` | ‹…› | ‹…› | ‹…› |
| Autovalúo actualizado | `AUTOVALUO_ACTUALIZADO` | ‹…› | ‹…› | ‹…› |
| Base imponible del predio | `BASE_IMPONIBLE_DEL_PREDIO` | ‹…› | ‹…› | ‹…› |
| Base del contribuyente | `BASE_DEL_CONTRIBUYENTE` | ‹…› | ‹…› | ‹…› |
| Impuesto por tramo | `IMPUESTO_POR_TRAMO` | ‹…› | ‹…› | ‹…› |
| Impuesto anual | `IMPUESTO_ANUAL` | ‹…› | ‹…› | ‹…› |
| Cuota | `CUOTA` | ‹…› | ‹…› | ‹…› |
| Reajuste | `REAJUSTE` | ‹…› | ‹…› | ‹…› |
| Interés | `INTERES` | ‹…› | ‹…› | ‹…› |

Una fila sin dato se deja en blanco: **esta determinación no pasó por ese punto**, que no es lo
mismo que «ahí no redondea».

## 3. Qué revela esta ficha

| Punto | Escala observada | Modo compatible | Concluyente |
|---|---|---|---|
| ‹punto› | ‹n decimales› | ‹HALF_UP / DOWN / … , o «no se puede distinguir»› | ‹sí / no, y por qué› |

**«No se puede distinguir» es una respuesta válida y frecuente:** con una cifra que termina en 4 o
en 6, `HALF_UP` y `HALF_EVEN` dan lo mismo. Hace falta un caso que termine exactamente en 5.

## 4. Lo que queda pendiente de esta observación

- ‹qué otro predio haría falta para desempatar el modo›
- ‹qué paso no se vio en la pantalla›

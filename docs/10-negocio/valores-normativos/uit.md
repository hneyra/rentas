# UIT del ejercicio

| Campo | Valor |
|---|---|
| Norma | Decreto Supremo N.° 301-2025-EF, que aprueba el valor de la Unidad Impositiva Tributaria durante el año 2026 |
| Artículo | 1 |
| Publicada | 2025-12-17, El Peruano |
| Ejercicios que rige | 2026–2026 |
| Filas de NEG-02 §2 | 1 |
| Transcribió | JNA, 2026-08-24 |
| Verificó | HNA, 2026-08-28 |
| Estado | VERIFICADO |

## 1. La tabla tal como está en la norma

**Sin reordenar, sin convertir unidades, sin corregir un encabezado.** Cada ejercicio tiene su propio
decreto supremo del MEF, con un único artículo sustantivo ("Aprobación de la UIT para el año
correspondiente"). Se transcriben los últimos cinco ejercicios porque el sistema necesita poder
recalcular ejercicios pasados (ARQ-09).

| Ejercicio | UIT (S/) | Decreto Supremo | Publicada |
|---|---|---|---|
| 2022 | 4 600,00 | D.S. N.° 398-2021-EF | 2021-12-30, El Peruano |
| 2023 | 4 950,00 | D.S. N.° 309-2022-EF | 2022-12-24, El Peruano |
| 2024 | 5 150,00 | D.S. N.° 309-2023-EF | 2023-12-28, El Peruano |
| 2025 | 5 350,00 | D.S. N.° 260-2024-EF | 2024-12-17, El Peruano |
| 2026 | 5 500,00 | D.S. N.° 301-2025-EF | 2025-12-17, El Peruano |

> El decreto es anual: cada ejercicio tiene el suyo, y la UIT de un año no deroga la del anterior
> —conviven, porque `ARQ-09` versiona cada parámetro por vigencia—. El texto del artículo único del
> decreto vigente (2026, D.S. 301-2025-EF) dice: «Durante el año 2026, el valor de la Unidad
> Impositiva Tributaria (UIT) como índice de referencia en normas tributarias será de S/ 5 500,00
> (cinco mil quinientos y 00/100 soles)».
>
> Fila del 2022 a 2025 confirmada contra el número, la fecha y el monto de cada decreto tal como
> los reproduce El Peruano (`busquedas.elperuano.pe`) y los estudios que citan la norma con su
> fecha de publicación (ver fuentes en el informe de transcripción); el texto íntegro del artículo
> único solo se verificó palabra por palabra contra la fuente oficial para el ejercicio 2026 —el
> que rige la cabecera de este archivo—. Los ejercicios 2022-2025 quedan con el dato (decreto,
> fecha, monto) confirmado, pero sin el texto literal del artículo re-verificado contra
> `busquedas.elperuano.pe` uno por uno en esta pasada.

## 2. Cómo entra al sistema

| Qué | Dónde |
|---|---|
| Tipo | `parametro_tributario` (tipo `UIT`) |
| Clave | El ejercicio al que corresponde (por ejemplo `UIT-2026`), o el ejercicio como parte de la clave compuesta que use `parametro_tributario` |
| Ámbito | nacional |
| Vigencia | Cada fila de la tabla anterior vale solo para su propio ejercicio (por ejemplo, 2026–2026); no hay una UIT que rija "desde" un año "hasta" otro. |

**Se carga desde `publicacion/parametros-2026.csv`**, el derivado publicable de este
archivo, con `infra/carga-de-datos/publicar-parametros.sh` (#188, #247 §4). Las dos firmas
de la cabecera de arriba son las que llegan a `usuario_carga` y `usuario_aprueba`: la doble
verificación de ADR-0007 ocurrió aquí, y la herramienta la transporta.

## 3. Qué no cabe hoy

Nada.

# Archivos de carga de ejemplo de `rentas`

Cuatro CSV: el padrón de contribuyentes, el padrón vehicular, las transferencias y el saldo
inicial del libro. Los seis restantes de la siembra viven donde vive su sistema —cinco en
`catastro` y uno en `caja`—, y **cada uno está en un solo repositorio** desde C-6: dos copias del
mismo archivo divergen, y la que se lea decidiría contra qué se cruzan las demás.

La municipalidad de referencia es **Catacaos** (ubigeo `200104`), la piloto de D-01 —no Sullana,
de cuyo manual sale la especificación funcional—.

| Archivo | Qué contiene | Naturaleza | Se carga con | Paso |
|---|---|---|---|---|
| `contribuyentes.csv` | 16 contribuyentes | **Ficticio**: personas inventadas | `cargar-contribuyentes-demo.sh` | 5 |
| `vehiculos.csv` | 8 vehículos | **Ficticio** | `cargar-vehiculos-demo.sh` | 8 |
| `transferencias.csv` | 7 actos sobre predios y vehículos | **Ficticio** | `cargar-transferencias-demo.sh` | 9 |
| `deuda.csv` | 54 obligaciones | **Ficticio**: es un **saldo**, no una determinación | `cargar-deuda-demo.sh` | 10 |

Los cuatro **solo corren contra una instalación de demostración**. Antes de leer una fila
preguntan por `municipalidad.es_demostracion` —la misma fila que decide si un documento sale
marcado— y si la respuesta es «no», no escriben nada: `SoloEnDemostracion` lo impide. Un
`--municipalidad-id` equivocado en un dígito metería dieciséis personas que no existen en el
padrón de una municipalidad que ya opera, y aquí no se borra nada (RNF-051): deshacerlo sería dar
de baja fila a fila.

## El orden, y por qué no está aquí

Cada archivo nombra por código algo que otro tuvo que escribir antes, y desde el corte ese
«antes» puede estar **en otra base**: `transferencias.csv` y `deuda.csv` nombran predios de
`catastro`. El orden de los diez pasos, con su dueño, está escrito **una sola vez**, en
[`infrastructure/infra/carga-de-datos/siembra/pasos.tsv`](https://github.com/hneyra/infrastructure/blob/main/infra/carga-de-datos/siembra/pasos.tsv).

## Ninguna cifra normativa

Ni aranceles, ni valores unitarios, ni tramos, ni valores referenciales de vehículos. El monto de
`deuda.csv` es la única cifra de dinero, y no es una excepción sino otra cosa: es un **saldo** que
entra como dato, igual que entraría el de la base anterior el día que se cierre D-04.
`ArchivosDeEjemploDeRentasTest` recorre los tres archivos que llevan cifras y rechaza cualquier
línea que nombre un arancel, un valor unitario, una depreciación, la UIT o una alícuota.

## Estos archivos pasan por el analizador de verdad

`ArchivoDeContribuyentesDeEjemploTest` y `ArchivosDeEjemploDeRentasTest` los cargan fila a fila
con los importadores de producción, en su orden y sobre el mismo padrón en memoria, y exigen que
entren enteros. Un ejemplo roto no llega a un ambiente: aparece en el build, que cuesta segundos.
Para cruzar los titulares, `ArchivosDeEjemploDeRentasTest` lee `fichas.csv` **del clon hermano de
`catastro`**; sin él, falla nombrando el `git clone`.

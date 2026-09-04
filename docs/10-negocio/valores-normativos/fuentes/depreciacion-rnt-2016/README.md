# Fuente: tabla de depreciación, Anexo I del Reglamento Nacional de Tasaciones

Las cuatro tablas de depreciación del **Anexo I** del Reglamento Nacional de Tasaciones del Perú
(R.M. N.º 172-2016-VIVIENDA), proyectadas a la forma de filas que `PublicarCuadros` carga. El
archivo del corpus que las transcribe es
[`../../depreciacion.md`](../../depreciacion.md), y este directorio **no vuelve a transcribir
nada**: lo deriva.

## Por qué este derivado sale del corpus y no de un PDF

Es la diferencia con [`tvr-2026/`](../tvr-2026/README.md), y conviene tenerla presente al leer los
dos. El anexo vehicular son **169 páginas** de PDF con 18 043 filas: no cabe en un archivo del
corpus, así que se extrae de la fuente con `extraer_tvr.py` —dos métodos independientes por fila— y
lo que el corpus firma es su **huella**. El Anexo I del RNT cabe entero en cuatro tablas de doce
filas, y por eso **ya está transcrito celda por celda en `depreciacion.md`**, con la nota al pie de
los asteriscos y el título verbatim de cada tabla, `VERIFICADO` y firmado por dos personas
distintas (ADR-0007).

Escribir además un CSV a mano sería un **segundo sitio donde una cifra puede estar mal**, y el
corpus dejaría de ser la única fuente. Por eso `derivar-depreciacion.mjs` lo proyecta, y
`--comprobar` exige en cada PR que el archivo desplegado sea exactamente lo que el guion produce
hoy desde el corpus: el derivado no se edita, se regenera. Es la misma disciplina de
`generar-openapi.mjs --comprobar` (#312).

```bash
node docs/10-negocio/valores-normativos/fuentes/depreciacion-rnt-2016/derivar-depreciacion.mjs
node docs/10-negocio/valores-normativos/fuentes/depreciacion-rnt-2016/derivar-depreciacion.mjs --comprobar
```

## El PDF original

No se versiona aquí: está archivado en S3 con el resto del lote del 2026-08-28, identificado por su
contenido y no por su nombre —ver [`../README.md`](../README.md)—.

| Archivo | sha256 | Archivado en S3 |
|---|---|---|
| `RM_172-2016-VIVIENDA.pdf` | `0dab13bdad3837fbd50d325e5871e9f3dc2957116d7447ac3dcb44a92adce0f5` | `s3://sgtm-fuentes-normativas/fuentes-normativas/tasaciones/200105/2026-08-28T23-44-08Z__RM_172-2016-VIVIENDA.pdf` |

La transcripción de `depreciacion.md` §1 no salió de ese PDF sino de un ejemplar íntegro del Anexo I
alojado por una municipalidad —`gob.pe/vivienda` devuelve 418 a los agentes—, y el propio archivo
del corpus lo deja dicho con su `‹NO CONFIRMADO EN FUENTE OFICIAL›` sobre la identidad palabra por
palabra con la publicación original. Lo que la re-verificación humana firmó es la transcripción; lo
que este directorio garantiza es que lo que se carga es exactamente esa transcripción y no otra
cosa.

## `depreciacion.csv` — las cuatro tablas, celda por celda

**492 filas.** Columnas: `tabla, material, estado_conservacion, antiguedad_hasta, porcentaje`, y
`PublicarCuadros` las lee **por posición**.

| Tabla | Uso de la edificación | Filas |
|---|---|---|
| 01 | Casa habitación, departamentos para viviendas | 127 |
| 02 | Tiendas, depósitos, centros de recreación, clubes sociales | 123 |
| 03 | Edificios, oficinas | 123 |
| 04 | Salud, cines, industrias, uso educativo, talleres | 119 |
| | **Total** | **492** |

sha256 del CSV: `5e919b370b10473570187c33edefd15a6dd372653db5d77b25fc51e31deb6be6`

### Las dos cosas que el derivado dice callando

**Las 36 celdas `*` no están.** De las 528 combinaciones (4 tablas × 3 materiales × 4 estados × 11
tramos) el Anexo tabula 492: las otras 36 llevan un asterisco y su nota al pie dice «el perito fija
los porcentajes no tabulados». No se proyectan **con ningún valor**, tampoco con cero: la fila no
existe, y quien la busque tendrá que fallar nombrándola. Una celda que falta no vale cero (#48), y
aquí valer cero sería no depreciar una construcción ruinosa. El quinto estado que el reglamento
nombra sin tabular, «Muy malo», no aparece por lo mismo.

**El último tramo va sin tope.** La norma rotula sus once tramos «Hasta 5»… «Hasta 50» y **«Más de
50»**; ese último sale con la celda `antiguedad_hasta` vacía, que en `depreciacion` es un nulo con
el significado de «sin tope» (V57) —el mismo que `Tramo.sinTope` en el dominio—. Un centinela
—999, 0, 150— habría sido una cifra inventada dentro de un cuadro normativo, y además una que se
lee igual que un tope de verdad.

# Fuente: Tabla de Valores Referenciales de Vehículos, ejercicio 2026

Los tres PDF oficiales de la R.M. N.° 008-2026-EF/15 (MEF), tal como se publicaron en gob.pe,
**archivados en S3 —no en este directorio—** y el derivado mecánico de su anexo, `tvr-2026.csv`,
que sí vive aquí por ser texto verificable. El archivo del corpus que los lee es
[`../../vehicular-valores-referenciales-2026.md`](../../vehicular-valores-referenciales-2026.md).

## Los documentos y su huella

Descargados de gob.pe por el dueño del repositorio el 2026-08-28 (el acceso directo desde los
agentes está bloqueado por el CDN, como documenta el archivo del corpus) y ese mismo día
archivados en S3 con `scripts/valores-normativos/archivar_fuente_normativa.sh` —con el UBIGEO de
Catacaos en la ruta (`fuentes-normativas/vehicular/200105/…`) y con historial: nunca sobrescribe
una subida anterior—, con el sha256 verificado tras la subida (se re-descarga el objeto y se
compara la huella). Los tres PDF **no se versionan en git**: pesan ~5 MB juntos y su copia
durable es la de S3.

| Archivo | sha256 | Origen oficial | Archivado en S3 |
|---|---|---|---|
| `7623157-anexo-tvr-ipv-2026.pdf` | `3f5538b43f84c1c83adbb1c58a8cc3ad944a90855848f8b6d289c0fa575df5cd` | `https://cdn.www.gob.pe/uploads/document/file/9293914/7623157-anexo-tvr-ipv-2026.pdf` | `s3://sgtm-fuentes-normativas/fuentes-normativas/vehicular/200105/2026-08-28T17-33-56Z__7623157-anexo-tvr-ipv-2026.pdf` |
| `7623157-continuacion-anexo-tvr-2026.pdf` | `b22df2ac8e31fcaf05397330e5ece00f3a9855bafbfcf4008ec58a7cfd5af7d3` | `https://cdn.www.gob.pe/uploads/document/file/9293916/7623157-continuacion-anexo-tvr-2026.pdf` | `s3://sgtm-fuentes-normativas/fuentes-normativas/vehicular/200105/2026-08-28T17-33-59Z__7623157-continuacion-anexo-tvr-2026.pdf` |
| `7623157-rm-n-008-2026-ef-15.pdf` | `14eedda46a16d9eaddd0bc41e3843ff1b5718d9dd2ebf5d2fcf3c4443b1b94e6` | `https://cdn.www.gob.pe/uploads/document/file/9293913/7623157-rm-n-008-2026-ef-15.pdf` | `s3://sgtm-fuentes-normativas/fuentes-normativas/vehicular/200105/2026-08-28T17-34-02Z__7623157-rm-n-008-2026-ef-15.pdf` |

## `tvr-2026.csv` — el anexo, fila por fila

**18 043 filas**, una por cada fila de datos de las 169 páginas del anexo principal, extraídas
**mecánicamente** con `extraer_tvr.py` — nunca retecleadas ni reproducidas de memoria: un valor
referencial equivocado se convierte en base imponible equivocada de un padrón entero.

Columnas: `categoria, marca, modelo_2025, modelo_2026, valor_2025, valor_2024, valor_2023`. Los
valores van **tal como los imprime la norma** (coma de miles, sin decimales: `18,000` son dieciocho
mil soles). `modelo_2025` puede venir vacío: hay modelos publicados por primera vez para el 2026.

| Categoría | Filas |
|---|---|
| A1 | 309 |
| A2 | 1 723 |
| A3 | 3 223 |
| A4 | 1 483 |
| BUSES Y OMNIBUSES | 690 |
| CAMIONES | 2 527 |
| CAMIONETAS | 7 912 |
| REMOLCADORES | 176 |
| **Total** | **18 043** |

sha256 del CSV: `239a75a03ef76550018e844c990f4c1bb1a35390cab1b0163e05bd1a0121098a`

## Cómo se extrajo, y cómo se demostró

`extraer_tvr.py` (requiere `pdfplumber`) usa **dos métodos independientes por fila** y solo acepta
la fila si dicen lo mismo: asignación de cada palabra a su columna por coordenada X —con los
límites de columna leídos de los rectángulos que el propio PDF dibuja— contra el parseo del texto
plano de la línea. Tres filas de las 18 043 tienen el modelo tan largo que sus dos celdas se
imprimen físicamente solapadas y ningún método por posición las separa; esas se rescatan por
**orden del stream del PDF** (dentro de una corrida de texto los caracteres vienen en orden, y el
salto de X hacia atrás marca la frontera entre las dos celdas), y las tres se verificaron además
**mirando la página renderizada**: en las tres, modelo 2025 y modelo 2026 son el mismo texto.

Validaciones sobre el total: las 18 043 filas tienen sus tres valores numéricos bien formados;
**todos** los valores son múltiplos de 10; en **todas** las filas `2025 ≥ 2024 ≥ 2023`; y muestras
de tres páginas distintas (45, 110, 169) se compararon visualmente contra el PDF renderizado,
incluida la fila de `modelo_2025` vacío (marca UD, solo publicada para el 2026).

Reproducir: descargar `7623157-anexo-tvr-ipv-2026.pdf` de su URI de S3 (tabla de arriba) a este
directorio —`aws s3 cp s3://sgtm-fuentes-normativas/fuentes-normativas/vehicular/200105/2026-08-28T17-33-56Z__7623157-anexo-tvr-ipv-2026.pdf 7623157-anexo-tvr-ipv-2026.pdf`—
y correr `python3 extraer_tvr.py`, que regenera `tvr-2026.csv`; un byte distinto en la salida es
una diferencia que investigar, no que aceptar. Comprobar antes que el sha256 del PDF descargado
coincide con el de la tabla.

## Lo que este directorio no es

**No es la carga, pero ya es su entrada.** D-13 se cerró el 2026-08-28
([ADR-0017](../../../../30-arquitectura/adr/ADR-0017-tablas-de-valuacion-nacionales.md)):
`valor_referencial_vehiculo` es un catálogo nacional y `PublicarCuadros` lo publica desde
[`../../publicacion/cuadros-2026.csv`](../../publicacion/cuadros-2026.csv), que declara la edición
con sus dos firmas (ADR-0007) y nombra este archivo con el sha256 de arriba. Ese sha256 se recalcula
antes de publicar una sola fila: un byte distinto no entra.

Las 18 043 líneas entran como **54 111 filas** —una por año de fabricación—, y los 18 rechazos son
seis líneas que este anexo **repite idénticas**. Cargarlo de verdad encontró además dos defectos del
esquema, corregidos en `V55`: faltaba la columna `categoria` —el anexo publica «OTROS MODELOS» en
cada categoría, con valor distinto, y sin ella se perdían 1 890 filas en silencio— y `modelo` era
`varchar(60)` cuando el modelo más largo del anexo tiene 67 caracteres (GOB-03, H-16).

Esto no sustituye la re-verificación humana del archivo del corpus, que la firmó HNA el 2026-08-28.

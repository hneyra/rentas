# Las muestras de los valores normativos

Una por prohibición, **válida en todo salvo en la que viola**. Las corre
`docs/10-negocio/verificar-las-muestras-de-valores.mjs`, que exige que la comprobación las rechace
**nombrando esa prohibición y no otra**: rechazarlas por el motivo equivocado sería pasar por
casualidad.

Nada de aquí es una norma. Todo cita una «norma de mentira 000-0000-XX» —el mismo recurso que
`infra/verificaciones/secretos/muestras/clave-de-mentira.env`— para que ninguna cifra de una muestra
pueda confundirse jamás con un valor transcrito.

| Muestra | Qué viola |
|---|---|
| `transcriptor-igual-a-verificador` | Las dos firmas son la misma persona |
| `sin-fecha-de-publicacion` | `Publicada` sin fecha: no se puede volver a la fuente |
| `sin-articulo` | Una cifra sin artículo al lado |
| `verificado-sin-verificador` | `Estado: VERIFICADO` sin que nadie haya verificado |
| `fila-que-no-existe` | Cierra una fila de NEG-02 §2 que no existe |
| `fila-reclamada-dos-veces` | Dos archivos cierran la misma fila |
| `sin-la-seccion-de-que-no-cabe` | Le falta una de las tres secciones fijas |
| `cabecera-incompleta` | Le falta un campo obligatorio |
| `carga-en-la-base` | Una migración con `INSERT INTO parametro_tributario`. Cargar es el proceso batch de publicación, nunca una migración, y no es E-3 |
| `en-regla` | **Nada.** Va al revés: tiene que pasar |

`en-regla` es la que impide el fallo contrario. Sin ella, una comprobación que rechazara todo pasaría
las nueve anteriores y parecería más estricta que nunca. Se demostró rompiéndola: añadiendo un
rechazo incondicional, las nueve siguen en verde y `en-regla` es la única que se pone roja.

`_sin-migraciones/` está vacío a propósito: es el directorio de migraciones que se le pasa a las
muestras de documento, para que su rechazo hable de lo que traen ellas y no del `INSERT` de la
décima.

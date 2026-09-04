# Fuentes oficiales archivadas en S3 — el registro permanente

Cada documento oficial que el corpus lee se archiva en
`s3://sgtm-fuentes-normativas/` con `scripts/valores-normativos/archivar_fuente_normativa.sh`
—que nunca sobrescribe una subida anterior y re-verifica el sha256 del objeto tras subirlo— y se
identifica **por su contenido**: la huella de abajo, no el nombre del archivo. Este registro es lo
que queda cuando la rama de tránsito que trajo los PDF se borra; comprobar un original es
descargar su URI y comparar su sha256 con esta lista.

Convención de la ruta: `fuentes-normativas/<tipo>/<ubigeo>/<sello>__<archivo>`. El guion exige un
UBIGEO; para norma nacional el precedente (la R.M. vehicular, [`tvr-2026/`](tvr-2026/README.md))
usó el del piloto, `200105` (Catacaos), y se sigue igual.

## Los archivos de filas —el derivado, no la fuente— también se archivan aquí (issue #388)

Un cuadro normativo cuyo archivo de filas no cabe en un ConfigMap de Kubernetes (el límite
práctico de un objeto de etcd es ~1 MiB; el anexo vehicular de 2026 pesa 1,5 MB) se sube al mismo
bucket, con `scripts/valores-normativos/archivar_derivado.sh` —el hermano de
`archivar_fuente_normativa.sh`, con la misma disciplina de subir y releer para verificar—, bajo un
prefijo paralelo: `derivados-normativos/<tipo>/<ubigeo>/<sello>__<archivo>`.

La diferencia con las fuentes de arriba no es solo el prefijo: **el manifiesto en git nunca nombra
la URI**. `docs/10-negocio/valores-normativos/publicacion/cuadros-2026.csv` sigue declarando
`archivo_de_filas` como la misma ruta relativa de siempre — es lo que
`docs/10-negocio/verificar-cuadros.mjs` comprueba letra por letra contra el corpus en cada PR, y
reescribirla ahí sería reescribir un derivado firmado. Lo que decide de dónde vienen los bytes de
una fila —del propio git, o de S3— es
[`derivados-en-s3.csv`](derivados-en-s3.csv): un registro aparte, indexado por el **mismo sha256**
que la fila del manifiesto ya declara, que `infra/carga-de-datos/publicar-cuadros.sh` consulta
cuando el archivo local pesa más de lo que un ConfigMap admite. Un `initContainer` descarga esa URI
y verifica su sha256 contra el que el manifiesto declara ANTES de que el proceso de publicación
arranque — nunca carga parcial, y un byte distinto en S3 no llega a Postgres.

Hoy `derivados-en-s3.csv` está vacío: ningún archivo de filas se ha migrado todavía. El anexo
vehicular ([`tvr-2026/`](tvr-2026/README.md), 1,5 MB) sigue en git —`publicar-cuadros.sh` se
detiene nombrando los dos pasos que faltan si algún día hay que correrlo contra un ambiente real
sin haberlo migrado antes—.

## Lote del 2026-08-28

Los 30 documentos que la investigación de D-11 (#188), el punto 2 de #192 y el cotejo del TUO LTM
trajeron a la rama de tránsito `gob-docs`, subidos por el dueño del repositorio ese mismo día.
Advertencias de identidad que conviene no perder, heredadas de la identificación por contenido:

- `rm-277-2025-vivienda-valores-arancelarios-2026-escudo.pdf` **no** es de aranceles pese a su
  nombre: es la parte resolutiva de la R.M. 277-2025-VIVIENDA, la de valores **unitarios** (los
  aranceles son la R.M. 514-2025-EF/15, ya trabajada en `aranceles-2026.md`).
- Los `i-2/3/4-*-2025.pdf` son los cuadros del **ejercicio 2026**: se aprueban con valores «al 31
  de octubre de 2025» (TUO LTM art. 11); el año del nombre confunde.
- `ds-103-99-ef.pdf` se archivó como **contexto**: no es el reglamento de la deducción de
  Amazonía sobre el predial (ese es el D.S. 031-99-EF); no menciona «predial» ni una vez.
- `textoCompleto-TUO-CT.pdf` es la edición de SUNAT con modificaciones incorporadas hasta el
  D. Leg. 1540; su art. 44 termina en el numeral 7 (el D. Leg. 1421 no le añadió párrafo).
- `DS-156-2004-EF-TUO-Ley-Tributacion-Municipal.pdf` es la edición con concordancias del TUO LTM,
  38 páginas con capa de texto; la modificatoria más reciente que anota es el D. Leg. 1520
  (31-12-2021).

| sha256 | Archivado en S3 |
|---|---|
| `aeb0eea9d0d2f0f3e3e54b12f651bed8c01a36e3c5d8d29069ca54643f4861e2` | `s3://sgtm-fuentes-normativas/fuentes-normativas/amazonia/200105/2026-08-28T23-44-12Z__5136617-ley-n-27037.pdf` |
| `b9232da77a395949e749bc1c1a1d2b34563939b8fc792d1eccca10b2e7bcd46e` | `s3://sgtm-fuentes-normativas/fuentes-normativas/amazonia/200105/2026-08-28T23-44-15Z__DS-031-99-EF.pdf` |
| `aa3ca1872d35e3bde7bcf8911dfc99c13ff562ad302efaa1873b9016116a15b0` | `s3://sgtm-fuentes-normativas/fuentes-normativas/amazonia/200105/2026-08-28T23-44-17Z__ds-103-99-ef.pdf` |
| `d27a0caba13cb4a9b99304f12f4c6bba7f830c482ca625950235051d4023f333` | `s3://sgtm-fuentes-normativas/fuentes-normativas/codigo-tributario/200105/2026-08-28T23-45-38Z__textoCompleto-TUO-CT.pdf` |
| `b662a3eba4663c59543bd6ccd3dc1cbb892a692e529305ec04cec4f6934ec4f1` | `s3://sgtm-fuentes-normativas/fuentes-normativas/ejecucion-coactiva/200105/2026-08-28T23-45-41Z__DS-018-2008-JUS.pdf` |
| `9f743df4139b99d53839034c7785ac3d1210c0c08e07bd758d340d3fef97c7ed` | `s3://sgtm-fuentes-normativas/fuentes-normativas/mef-guias/200105/2026-08-28T23-45-33Z__Guia_para_el_registro_y_determinacion_IP.pdf` |
| `16edae4b1d9880f746991f1d65b8aecd56f6293e1a5ab7675a8d131270ee290e` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-32Z__MC_modulo_registro_determinacion_v0315040_a_0316030.pdf` |
| `fa66e7e13e0a9f1c4b7b3a8411a71f0ad41042ecc75d00829dec7e038bd42b18` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-37Z__MC_v03.15.03.00.pdf` |
| `918aff84f89382d402d712fa8232def37b18e7951d42f6de291ae4af43d636c8` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-41Z__v2.0.0_Manual_Usuario_Modulo_Rentas.pdf` |
| `fd7492aef0b8b3bfc58457cc25084f2dc8e4f6cb2643856942da0447c8d40da5` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-46Z__v2.1.0_Mejoras.pdf` |
| `74acc5312665575b5e1bdcbe22c6b7202f750991434774a76d326fd0ea07dc1d` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-49Z__v2.2.0_Mejoras.pdf` |
| `2001b4407de22f86e6ae6659b656349d02c8f672aa4ad2c800c6fd87eed13b54` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-53Z__v2.3.0_Mejoras.pdf` |
| `278c532c6426b403d53662c4992ded2ca91c6c1c812ee760ee47143723557eec` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-44-56Z__v3.0.0_Arbitrios_Municipales.pdf` |
| `65a380af5e25c78150d971539e4571f54e86dc5fc3fc6aa6b262f1f11abdc90d` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-45-03Z__v3.1.0_Proceso_Masivo.pdf` |
| `8973edd043319473683615802bbed68bfdaf1b79ff8a4eac61f39d9eb3d69995` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-45-08Z__v4.0.0_Manual_Usuario.pdf` |
| `c41076c851c479a494d6aac1b08500f41ba0fabd859c1e7dbce45f1b70e869be` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-45-13Z__v5.0.0_Beneficios_Tributarios_Notificaciones.pdf` |
| `f3338530af946f605c1bcd0a1e82fe06b255167513fc892df1af7efb49207759` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-45-19Z__v6.0.0_Multas_Tributarias_Fiscalizacion.pdf` |
| `2a91371e0971946f7b232d1f1c74ea8f9788ccfd2acb3ee3f94a38ac597424cb` | `s3://sgtm-fuentes-normativas/fuentes-normativas/srtm-manuales/200105/2026-08-28T23-45-29Z__v6.1.0_Clasificadores_de_Ingreso.pdf` |
| `0dab13bdad3837fbd50d325e5871e9f3dc2957116d7447ac3dcb44a92adce0f5` | `s3://sgtm-fuentes-normativas/fuentes-normativas/tasaciones/200105/2026-08-28T23-44-08Z__RM_172-2016-VIVIENDA.pdf` |
| `31ac1e01e0a8a5f2cd29ad838b4f6aef3e48bf08cbb772a1207e82d8b92f64fd` | `s3://sgtm-fuentes-normativas/fuentes-normativas/tributacion-municipal/200105/2026-08-28T23-45-44Z__DS-156-2004-EF-TUO-Ley-Tributacion-Municipal.pdf` |
| `9f8334a613177e043949ebc69d0b6745d7d19c0fc985886dc2aa12d01b4e9be5` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-39Z__anexo_ii.pdf` |
| `81eaf3a6014bb44a73cabe477523ecf9db605f1e36fc91c2918d6432acddaf74` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-42Z__i-1-f-lima-callao-2026.pdf` |
| `0df8ac02089aa627ea857213136c9b01b213dfc0049ff7ccb37a10a5da711b37` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-45Z__i-2-f-costa-2025.pdf` |
| `951022064676db0af7aa5619849f6b1dc09192a169ffe6ac7d3d425c3ecd495d` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-48Z__i-3-f-sierra-2025.pdf` |
| `eedb539b760342b01d6282ddecd470b083051282440d25cbdc1ba99998035086` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-50Z__i-4-f-selva-2025.pdf` |
| `94ac5d58dc4f814eb4376b6e4705964495ec65b3648e1fd4569e29883e809e15` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-53Z__iii-1-obras-complem-lima-callao-2026.pdf` |
| `62d0b3bfc9760a0c4668d9ab117abbb825bf311b104414068bc3161c5efcc105` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-56Z__iii-2-obras-complem-costa-2026.pdf` |
| `1edd4ce80c3699fa32118fd343e2e82bfc8adae57779c5543cd2b37d4ce96bd5` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-43-59Z__iii-3-obras-complem-sierra-2026.pdf` |
| `947bdaa9a6fe0f9310bd1f9b154d7c0c5416fdde61225eb60f8eab88cc2079e4` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-44-02Z__iii-4-obras-complem-selva-2026.pdf` |
| `9df028d51e7da15425d97dd6792d2bdf2fab6604cd9194d1aba5e9204cfa155d` | `s3://sgtm-fuentes-normativas/fuentes-normativas/valores-unitarios/200105/2026-08-28T23-44-05Z__rm-277-2025-vivienda-valores-arancelarios-2026-escudo.pdf` |

## Lote del 2026-08-28 (vehicular, anterior)

Los tres PDF de la R.M. 008-2026-EF/15 se archivaron antes que este lote y su registro —huella,
origen oficial y URI— vive en [`tvr-2026/README.md`](tvr-2026/README.md), junto al derivado
mecánico de su anexo.

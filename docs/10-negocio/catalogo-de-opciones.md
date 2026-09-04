# NEG-03 — Catálogo de opciones

Las **134 opciones** de los **12 módulos** del sistema, con el `endpoint` que cada una
declara en el prototipo de interfaz y el contexto acotado que la sirve.

**Este archivo se genera.** Regenerarlo con `node docs/10-negocio/generar-catalogo.mjs`
cuando cambie el catálogo del prototipo; no editarlo a mano.

Leyenda de bloque: `Registro` = registro y mantenimiento · `Procesos` · `Consultas` ·
`Documentos` = documentos y reportes. Es la **taxonomía del manual**, y la calcula el
título de la pantalla ([FRO-03 §4](../60-frontend/mapa-de-pantallas.md)).

> **No es la clasificación que agrupa la navegación de la interfaz.** Desde
> [`ADR-0014`](../30-arquitectura/adr/ADR-0014-navegacion-centrada-en-la-atencion.md) §4 el
> menú agrupa **por tarea**, con los grupos que declara módulo a módulo la tabla del
> portador (`frontend/scripts/grupos-por-tarea.mjs`); los cuatro bloques quedan ahí como
> respaldo de un módulo que la tabla no cubra. Las dos clasificaciones conviven sin
> estorbarse porque **nada funcional depende de esta columna**: el backend siembra los
> accesos leyendo de cada fila solo el `id` y el nombre de la opción
> (`CatalogoDeOpciones`), y el identificador de la opción sigue siendo la clave del
> permiso, agrupe quien agrupe.

El `endpoint` es el que **declara el prototipo**: dice qué operación pide la pantalla, no
si el backend la sirve ni si además se puede escribir en ella. Cuando lo publicado se
aparta de eso, el módulo lleva una **nota** bajo su título.

| Módulo | Manual | Contexto | Opciones |
|---|---|---|---|
| Inicio | — | `transversal` | 2 |
| Catastro | cap. 2 | `catastro` | 12 |
| Rentas · Registro | cap. 3 §Registro | `rentas` | 15 |
| Fiscalización | cap. 3 §Fiscalización | `fiscalizacion` | 8 |
| Tránsito | cap. 3 §Tránsito | `sanciones` | 23 |
| Infracciones administrativas | cap. 3 §Infracciones administrativas | `sanciones` | 13 |
| Tesorería | cap. 3 §Tesorería | `tesoreria` | 10 |
| Consultas | cap. 3 §Consultas | `cuentacorriente` | 11 |
| Valores | cap. 3 §Valores | `valores` | 6 |
| Coactiva | cap. 3 §Coactivas | `coactiva` | 12 |
| Autorizaciones y licencias | cap. 3 §Autorizaciones y §Licencias | `licencias` | 11 |
| Seguridad | cap. 4 | `seguridad` | 11 |
| **Total** | | | **134** |

## Inicio

Manual: — · contexto acotado: `transversal`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `inicio` | Panel de recaudación | Consultas | `GET /api/v1/indicadores/recaudacion?ejercicio=2026` |
| `portal` | Consulta y pago en línea | Consultas | `GET /api/v1/portal/deuda?doc=44218937` |

## Catastro

Manual: cap. 2 · contexto acotado: `catastro`

**Lo que el backend ya publica (#290).** `calles` y `sectores` dan de alta y editan, y la
baja es lógica: no se borra ninguna fila (RNF-051). `sectores` además da de alta manzanas, y
solo eso —el código de una manzana es un tramo del código catastral de sus predios, así que
cambiarlo los desalinearía a todos—. Las cuatro fichas se inscriben, y el alta
**crea el predio en el mismo acto** si todavía no existe —`ficha_catastral.predio_id` es
`NOT NULL`—, con su titularidad inicial si ya se conoce. `actualizacion_catastro` versiona
**los cuatro tipos de ficha**, no solo el urbano, aunque su endpoint declare la ruta del
urbano. Toda escritura exige la observación del usuario (RNF-052) y deja auditoría.

`aranceles`, `valores_unitarios` y `depreciacion` siguen **de solo lectura**, y no por olvido:
el arancel se carga por lote contra un conjunto de parámetros que alguien abre y sella
(`AdministrarParametros.abrirVersion` + `ImportarArancel`), no fila a fila desde una pantalla;
y las otras dos son catálogos nacionales desde ADR-0017, que además dice quién las escribe:
el proceso batch de publicación, nunca una pantalla.

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `ficha_urbana` | Ficha catastral urbana individual | Registro | `GET /api/v1/catastro/fichas/urbana/{codRefCatastral}` |
| `ficha_economica` | Ficha catastral económica | Registro | `GET /api/v1/catastro/fichas/economica/{codRefCatastral}` |
| `ficha_bienes` | Ficha de bienes comunes | Registro | `GET /api/v1/catastro/fichas/bienes-comunes/{codEdificacion}` |
| `ficha_rural` | Ficha catastral rural | Registro | `GET /api/v1/catastro/fichas/rural/{codUnidad}` |
| `consulta_fichas` | Consulta de fichas catastrales | Consultas | `GET /api/v1/catastro/fichas` |
| `actualizacion_catastro` | Actualización del catastro | Procesos | `PUT /api/v1/catastro/fichas/{codigo}/actualizacion` |
| `ficha_contribuyente_reporte` | Reporte de ficha del contribuyente | Documentos | `GET /api/v1/catastro/contribuyentes/{codigo}/ficha.pdf` |
| `calles` | Mantenimiento de vías y calles | Registro | `GET /api/v1/catastro/vias` |
| `sectores` | Sectores, manzanas y lotes | Registro | `GET /api/v1/catastro/sectores` |
| `aranceles` | Aranceles de terreno | Registro | `GET /api/v1/catastro/tablas/aranceles?anio=2026` |
| `valores_unitarios` | Valores unitarios de edificación | Registro | `GET /api/v1/catastro/tablas/valores-unitarios?anio=2026` |
| `depreciacion` | Tabla de depreciación | Registro | `GET /api/v1/catastro/tablas/depreciacion?anio=2026` |

## Rentas · Registro

Manual: cap. 3 §Registro · contexto acotado: `rentas`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `contribuyentes` | Contribuyentes | Registro | `GET /api/v1/rentas/contribuyentes` |
| `predios_rentas` | Predios del contribuyente | Registro | `GET /api/v1/rentas/predios?contribuyente={codigo}` |
| `predial_individual` | Cálculo individual del impuesto predial | Procesos | `POST /api/v1/rentas/predial/calculo-individual` |
| `predial_masivo` | Cálculo masivo del impuesto predial | Procesos | `POST /api/v1/rentas/predial/calculo-masivo` |
| `declaracion_jurada` | Declaración jurada — HR, PU y PR | Procesos | `GET /api/v1/rentas/declaraciones/{djNro}` |
| `arbitrios` | Arbitrios municipales | Registro | `GET /api/v1/rentas/arbitrios?anio=2026` |
| `transferencia_predio` | Transferencia de predio | Procesos | `POST /api/v1/rentas/transferencias/predio` |
| `alcabala` | Impuesto de alcabala | Registro | `POST /api/v1/rentas/alcabala` |
| `vehiculos` | Ficha de vehículo | Registro | `GET /api/v1/rentas/vehiculos/{placa}` |
| `vehicular_calculo` | Cálculo del impuesto vehicular | Procesos | `POST /api/v1/rentas/vehicular/calculo` |
| `transferencia_vehiculo` | Transferencia de vehículo | Procesos | `POST /api/v1/rentas/transferencias/vehiculo` |
| `espectaculos` | Espectáculos públicos no deportivos | Registro | `POST /api/v1/rentas/espectaculos` |
| `beneficios` | Beneficios y exoneraciones | Registro | `GET /api/v1/rentas/beneficios` |
| `alta_deuda` | Alta de deuda | Procesos | `POST /api/v1/rentas/deuda/altas` |
| `baja_deuda` | Baja de deuda | Procesos | `POST /api/v1/rentas/deuda/bajas` |

## Fiscalización

Manual: cap. 3 §Fiscalización · contexto acotado: `fiscalizacion`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `fisc_programa` | Programación de fiscalización | Registro | `POST /api/v1/fiscalizacion/programas` |
| `fisc_predial` | Fiscalización predial — acta de inspección | Registro | `POST /api/v1/fiscalizacion/predial/actas` |
| `fisc_vehicular` | Fiscalización vehicular | Registro | `POST /api/v1/fiscalizacion/vehicular` |
| `fisc_resultados` | Resultados y determinaciones | Registro | `GET /api/v1/fiscalizacion/resultados` |
| `fisc_omisos` | Omisos y subvaluadores | Registro | `GET /api/v1/fiscalizacion/omisos` |
| `fisc_estado_cuenta` | Estado de cuenta de fiscalización | Consultas | `GET /api/v1/fiscalizacion/estado-cuenta?contribuyente={codigo}` |
| `fisc_historico` | Histórico de fiscalización predial | Consultas | `GET /api/v1/fiscalizacion/predial/historico` |
| `resolucion_determinacion_fisc` | Resolución de determinación de fiscalización | Documentos | `GET /api/v1/fiscalizacion/resoluciones/{numero}` |

## Tránsito

Manual: cap. 3 §Tránsito · contexto acotado: `sanciones`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `papeletas` | Papeletas de infracción de tránsito | Registro | `GET /api/v1/transito/papeletas` |
| `transito_busqueda` | Búsqueda de infracciones | Consultas | `GET /api/v1/transito/papeletas/busqueda` |
| `codigos_transito` | Tabla de códigos de infracción de tránsito | Registro | `GET /api/v1/transito/codigos` |
| `transito_descargos` | Descargos y reclamos de papeletas | Registro | `POST /api/v1/transito/descargos` |
| `internamiento` | Internamiento vehicular | Registro | `GET /api/v1/transito/internamientos` |
| `transito_documentos` | Emisión de resoluciones y otros documentos | Documentos | `GET /api/v1/transito/papeletas/{numero}/actos` |
| `transito_valores` | Generación de valores de tránsito | Procesos | `POST /api/v1/transito/valores/generacion-masiva` |
| `transito_cambio_numero` | Cambio de número de papeleta de tránsito | Procesos | `PATCH /api/v1/transito/papeletas/{numero}/codigo` |
| `transito_reportes` | Reportes de infracción de tránsito | Documentos | `POST /api/v1/transito/reportes` |
| `transito_record_conductor` | Record de conductor | Documentos | `GET /api/v1/transito/reportes/record-conductor` |
| `transito_record_vehicular` | Record vehicular | Documentos | `GET /api/v1/transito/reportes/record-vehicular` |
| `transito_constancia_libre` | Constancia libre de infracciones | Documentos | `POST /api/v1/transito/constancias-libres` |
| `transito_padron` | Padrón de papeletas de tránsito | Documentos | `GET /api/v1/transito/reportes/padron` |
| `transito_estado_cuenta` | Estado de cuenta de infracciones | Consultas | `GET /api/v1/transito/estado-cuenta` |
| `transito_papeleta_reporte` | Reporte papeleta de infracción | Documentos | `GET /api/v1/transito/papeletas/{numero}/hoja-informativa` |
| `transito_rg_ordinaria` | Resolución de gerencia ordinaria | Documentos | `POST /api/v1/transito/resoluciones/ordinaria` |
| `transito_rg_sancionadora` | Resolución de gerencia sancionadora | Documentos | `POST /api/v1/transito/resoluciones/sancionadora` |
| `transito_padron_coactiva` | Padrón de papeletas enviadas a coactiva | Documentos | `GET /api/v1/transito/reportes/padron-coactiva` |
| `transito_padron_constancias` | Padrón de constancias libres de infracciones | Documentos | `GET /api/v1/transito/reportes/padron-constancias` |
| `transito_resumen_recaudacion` | Resumen de recaudación de tránsito | Documentos | `GET /api/v1/transito/reportes/resumen-recaudacion` |
| `transito_resumen_papeletas` | Resumen de papeletas pendientes y pagadas | Documentos | `GET /api/v1/transito/reportes/resumen-papeletas` |
| `transito_resumen_codigo` | Resumen de papeletas por código de infracción | Documentos | `GET /api/v1/transito/reportes/resumen-por-codigo` |
| `transito_resumen_placa` | Resumen de papeletas por iniciales de placa | Documentos | `GET /api/v1/transito/reportes/resumen-por-placa` |

## Infracciones administrativas

Manual: cap. 3 §Infracciones administrativas · contexto acotado: `sanciones`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `adm_notificacion` | Notificación administrativa | Procesos | `POST /api/v1/infracciones/administrativas/notificaciones` |
| `infracciones_adm` | Infracción administrativa | Registro | `GET /api/v1/infracciones/actas` |
| `codigos_cuis` | Cuadro único de infracciones y sanciones (CUIS) | Registro | `GET /api/v1/infracciones/cuis` |
| `adm_codigos_reporte` | Reporte de códigos de infracción administrativa | Documentos | `GET /api/v1/infracciones/administrativas/codigos/reporte` |
| `adm_valores` | Generación de valores administrativa | Procesos | `POST /api/v1/infracciones/administrativas/valores/generacion-masiva` |
| `adm_estado_cuenta` | Estado de cuenta de papeleta administrativa | Consultas | `GET /api/v1/infracciones/administrativas/estado-cuenta` |
| `adm_resolucion_gerencia` | Resolución de gerencia | Documentos | `POST /api/v1/infracciones/administrativas/resoluciones` |
| `adm_notificacion_resolucion` | Notificación de resolución de gerencia | Documentos | `POST /api/v1/infracciones/administrativas/resoluciones/{id}/notificacion` |
| `adm_reportes` | Reportes de infracción administrativa | Documentos | `POST /api/v1/infracciones/administrativas/reportes` |
| `adm_padron_notificaciones` | Padrón de notificaciones | Documentos | `GET /api/v1/infracciones/administrativas/reportes/padron-notificaciones` |
| `adm_notificaciones_vencidas` | Notificaciones vencidas | Procesos | `GET /api/v1/infracciones/administrativas/reportes/vencidas` |
| `adm_notificaciones_contribuyente` | Notificaciones por contribuyente | Procesos | `GET /api/v1/infracciones/administrativas/reportes/por-contribuyente` |
| `adm_resumen_recaudacion` | Resumen de recaudación de papeletas | Documentos | `GET /api/v1/infracciones/administrativas/reportes/resumen-recaudacion` |

## Tesorería

Manual: cap. 3 §Tesorería · contexto acotado: `tesoreria`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `caja_tributaria` | Caja tributaria | Registro | `POST /api/v1/tesoreria/caja/cobranza` |
| `caja_tasas` | Caja de tasas y derechos administrativos | Registro | `POST /api/v1/tesoreria/caja/tasas` |
| `fraccionamiento` | Fraccionamiento tributario | Procesos | `POST /api/v1/tesoreria/fraccionamientos` |
| `consulta_convenios` | Consulta de convenios | Consultas | `GET /api/v1/tesoreria/convenios` |
| `duplicado_recibo` | Duplicado de recibo | Procesos | `GET /api/v1/tesoreria/recibos/{nro}/duplicado` |
| `anulacion_recibo` | Anulación de recibo | Procesos | `POST /api/v1/tesoreria/recibos/{nro}/anulacion` |
| `anulacion_convenio` | Anulación de convenio | Procesos | `POST /api/v1/tesoreria/convenios/{numero}/anulacion` |
| `cierre_caja` | Cierre y arqueo de caja | Registro | `POST /api/v1/tesoreria/caja/cierre` |
| `avance_recaudacion` | Avance de recaudación | Registro | `GET /api/v1/tesoreria/recaudacion/avance` |
| `recaudacion_area` | Recaudación por área | Registro | `GET /api/v1/tesoreria/recaudacion/por-area` |

## Consultas

Manual: cap. 3 §Consultas · contexto acotado: `cuentacorriente`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `cuenta_corriente` | Estado de cuenta corriente | Consultas | `GET /api/v1/consultas/cuenta-corriente/{codigo}` |
| `consulta_deuda` | Consulta de deuda | Consultas | `GET /api/v1/consultas/deuda` |
| `consulta_unificada` | Consulta unificada predial-arbitrios | Consultas | `GET /api/v1/consultas/unificada?contribuyente={codigo}` |
| `consulta_resumen_predial` | Consulta resumen predial-arbitrios | Documentos | `GET /api/v1/consultas/resumen-predial` |
| `consulta_altas_bajas` | Consulta de altas y bajas | Consultas | `GET /api/v1/consultas/altas-bajas` |
| `consulta_deudas_beneficio` | Consulta de deudas con beneficio | Consultas | `GET /api/v1/consultas/deudas-con-beneficio` |
| `consulta_pagos` | Consulta de pagos | Consultas | `GET /api/v1/consultas/pagos` |
| `consulta_predios` | Consulta de predios | Consultas | `GET /api/v1/consultas/predios` |
| `consulta_vehiculos` | Consulta de vehículos | Consultas | `GET /api/v1/consultas/vehiculos` |
| `consulta_valores` | Consulta de valores emitidos | Consultas | `GET /api/v1/consultas/valores` |
| `constancia` | Constancia de no adeudo | Documentos | `GET /api/v1/consultas/constancias/no-adeudo` |

## Valores

Manual: cap. 3 §Valores · contexto acotado: `valores`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `valores_individual` | Generación individual de valores | Procesos | `POST /api/v1/valores` |
| `valores_masivo` | Generación masiva de valores | Procesos | `POST /api/v1/valores/masivo` |
| `valores_busqueda` | Búsqueda y mantenimiento de valores | Consultas | `GET /api/v1/valores` |
| `notificacion_valores` | Notificación de valores | Procesos | `POST /api/v1/valores/{nro}/notificacion` |
| `prescripcion` | Prescripción de la deuda | Procesos | `POST /api/v1/coactiva/prescripcion` |
| `pase_coactiva` | Pase de valores a coactiva | Procesos | `POST /api/v1/valores/{numero}/movimientos` |

## Coactiva

Manual: cap. 3 §Coactivas · contexto acotado: `coactiva`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `coactiva_expedientes` | Expedientes coactivos | Registro | `GET /api/v1/coactiva/expedientes` |
| `importacion_valores` | Importación de valores a coactiva | Procesos | `POST /api/v1/coactiva/expedientes/importacion` |
| `proceso_coactivo` | Proceso coactivo | Procesos | `GET /api/v1/coactiva/expedientes/{numero}/proceso` |
| `rec_impresion` | Impresión de resolución de ejecución coactiva | Documentos | `POST /api/v1/coactiva/rec/impresion` |
| `expediente_historial` | Gestionar historial del expediente | Registro | `PATCH /api/v1/coactiva/expedientes/{numero}/estados` |
| `cambiar_direccion_ref` | Cambiar dirección referencial | Procesos | `PATCH /api/v1/coactiva/expedientes/{numero}/direccion-referencial` |
| `costas_procesales` | Liquidación de costas procesales | Procesos | `POST /api/v1/coactiva/liquidaciones-costas` |
| `fraccionamiento_coactivo` | Fraccionamiento coactivo | Procesos | `POST /api/v1/coactiva/convenios` |
| `actos_coactivos` | Registro de actos coactivos | Procesos | `POST /api/v1/coactiva/expedientes/{numero}/actos` |
| `notificaciones_coactivas` | Emisión de notificaciones coactivas | Procesos | `POST /api/v1/coactiva/notificaciones` |
| `coactiva_consulta_deudas` | Consulta de deudas en coactiva | Consultas | `GET /api/v1/coactiva/deudas` |
| `coactiva_deudas_beneficio` | Consulta de deudas en beneficio (coactiva) | Consultas | `GET /api/v1/coactiva/deudas-en-beneficio` |

## Autorizaciones y licencias

Manual: cap. 3 §Autorizaciones y §Licencias · contexto acotado: `licencias`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `anuncios` | Anuncio y propaganda | Registro | `GET /api/v1/autorizaciones/anuncios` |
| `anuncios_reportes` | Reportes de anuncio y propaganda | Documentos | `POST /api/v1/autorizaciones/anuncios/reportes` |
| `licencia_funcionamiento` | Licencia de funcionamiento | Registro | `GET /api/v1/licencias/funcionamiento` |
| `licencia_padron` | Padrón de licencias de funcionamiento | Documentos | `POST /api/v1/licencias/funcionamiento/reportes/padron` |
| `licencia_resumen_anual` | Resumen de licencias por año | Documentos | `GET /api/v1/licencias/funcionamiento/reportes/resumen-anual` |
| `licencia_resolucion_cancelacion` | Resolución de cancelación de licencia | Documentos | `POST /api/v1/licencias/funcionamiento/{id}/cancelacion` |
| `licencia_resolucion_duplicado` | Resolución de duplicado de licencia | Documentos | `POST /api/v1/licencias/funcionamiento/{id}/duplicado` |
| `fue_edificacion` | Formulario único de edificación (FUE) | Registro | `GET /api/v1/licencias/edificacion` |
| `edificacion_reporte` | Reporte general de licencias de edificación | Documentos | `GET /api/v1/licencias/edificacion/reportes/general` |
| `ciiu` | Catálogo CIIU de giros | Registro | `GET /api/v1/licencias/ciiu` |
| `certificados` | Certificados de numeración y zonificación | Documentos | `POST /api/v1/licencias/certificados` |

## Seguridad

Manual: cap. 4 · contexto acotado: `seguridad`

| id | Opción | Bloque | Endpoint |
|---|---|---|---|
| `modulos` | Módulos del sistema | Registro | `GET /api/v1/seguridad/modulos` |
| `usuarios` | Usuarios del sistema | Registro | `GET /api/v1/seguridad/usuarios` |
| `grupos` | Grupos de usuarios | Registro | `GET /api/v1/seguridad/grupos` |
| `accesos` | Accesos y políticas | Registro | `GET /api/v1/seguridad/accesos` |
| `miembros` | Gestión de miembros | Registro | `POST /api/v1/seguridad/grupos/{grupo}/miembros` |
| `permisos` | Permisos y niveles de accesibilidad | Registro | `PUT /api/v1/seguridad/grupos/{id}/permisos` |
| `cambiar_anio` | Cambiar el año de trabajo | Procesos | `PUT /api/v1/seguridad/sesion/ejercicio` |
| `cambiar_clave` | Cambiar contraseña | Procesos | `PUT /api/v1/seguridad/usuarios/{id}/clave` |
| `auditoria` | Auditoría del sistema | Consultas | `GET /api/v1/seguridad/auditoria` |
| `parametros` | Parámetros del sistema | Registro | `GET /api/v1/seguridad/parametros` |
| `respaldo` | Copias de seguridad | Procesos | `POST /api/v1/seguridad/respaldos` |

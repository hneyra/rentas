# REQ-01 — Requisitos funcionales

Derivados del manual de usuario. Cada requisito indica las opciones del menú que lo realizan
(ids de [NEG-03](../10-negocio/catalogo-de-opciones.md)) y el contexto acotado que lo sirve.

**Convención:** `RF-xxx`. Un requisito que dependa de un dato normativo pendiente lleva
`‹bloqueado por D-02›`; puede diseñarse y probarse con parámetros de ejemplo, pero no
implementarse contra cifras inventadas.

Un requisito cuya **escritura** ya publica el backend lo dice al final, con el issue que la
trajo: `‹escritura publicada #290›`. El marcador dice que la operación existe y con qué límites,
no que la pantalla cubra ya todas las secciones que el manual dibuja.

---

## Catastro — `catastro`

| # | Requisito | Opciones |
|---|---|---|
| RF-001 | Registrar y actualizar la **ficha catastral única** de un predio urbano: datos generales, ubicación, características de titularidad, propietarios, características de la construcción por piso, otras instalaciones, inquilinos, arbitrios, observaciones, información complementaria, servicios, documentos, licencia de funcionamiento e imágenes ‹escritura publicada #290: el alta crea el predio en el mismo acto› | `ficha_urbana` |
| RF-002 | Registrar la **ficha catastral económica** (conductor, domicilio fiscal, autorización municipal de funcionamiento, autorización de anuncio, información complementaria) ‹escritura publicada #290› | `ficha_economica` |
| RF-003 | Registrar la **ficha de bienes comunes** de una edificación ‹escritura publicada #290› | `ficha_bienes` |
| RF-004 | Registrar la **ficha rural**: datos generales, descripción del condominio, propietarios, tipos de tierra y predios colindantes ‹escritura publicada #290› | `ficha_rural` |
| RF-005 | Identificar cada predio por **código de referencia catastral** con la estructura `DDPPddSSMMMLLLEEeeppUUU` y validar su composición | todas las fichas |
| RF-006 | Consultar fichas por código, contribuyente, dirección, sector o uso | `consulta_fichas` |
| RF-007 | **Versionar** toda modificación de ficha: copiar la ficha vigente, crear la versión nueva y registrar autor, fecha, hora y observación. Nunca sobrescribir ‹escritura publicada #290: los cuatro tipos de ficha, cada uno bajo la ruta con que se lee› | `actualizacion_catastro` |
| RF-008 | Mantener los catálogos catastrales: vías y calles, sectores y manzanas ‹escritura publicada #290: la vía y el sector se dan de alta y se editan —la baja es lógica—; la manzana **solo se da de alta**, porque su código es un tramo del código catastral de sus predios› | `calles`, `sectores` |
| RF-009 | Mantener las tablas de valuación por ejercicio: aranceles, valores unitarios de edificación y depreciación ‹de solo lectura por HTTP: el arancel se carga por lote contra un conjunto de parámetros antes de sellarlo; los valores unitarios y la depreciación son catálogos nacionales desde ADR-0017; la de depreciación la publica el proceso batch desde `V57`, y la de valores unitarios en cuanto el proceso sepa publicarla: sus dos firmas están desde el 2026-08-29, y le faltan el derivado con su huella, decidir el vocabulario de partidas y las otras tres regiones (GOB-03, H-14)› | `aranceles`, `valores_unitarios`, `depreciacion` |
| RF-010 | Emitir la **ficha del contribuyente** con sus datos generales | `ficha_contribuyente_reporte` |

## Contribuyentes — `contribuyentes`

| # | Requisito | Opciones |
|---|---|---|
| RF-011 | Registrar al contribuyente con **código único** que enlaza todas sus obligaciones: predios, vehículos, papeletas, licencias | `contribuyentes` |
| RF-012 | Registrar a los responsables solidarios: cónyuge, condóminos y poseedores | `contribuyentes` |
| RF-013 | Registrar domicilio fiscal con historial de vigencias, documentos, contactos, gestores, teléfonos, correo y observaciones | `contribuyentes` |
| RF-014 | Buscar contribuyentes por documento, nombre, código, dirección y aproximación de nombre | `contribuyentes` |

## Rentas · determinación — `rentas`

| # | Requisito | Opciones |
|---|---|---|
| RF-020 | Calcular el **impuesto predial** individual de un contribuyente y emitir HR, PU, PR, aviso de cobranza y ficha tributaria ‹bloqueado por D-02› | `predial_individual` |
| RF-021 | Calcular el **predial masivo** de todo el padrón de un ejercicio y generar los estados de cuenta ‹bloqueado por D-02› | `predial_masivo` |
| RF-022 | Calcular **arbitrios** por predio, uso y sector, con las cuotas del ejercicio ‹bloqueado por D-02› | `arbitrios` |
| RF-023 | Registrar la **declaración jurada** del contribuyente y su fecha de presentación; generar multa tributaria por presentación fuera de plazo ‹bloqueado por D-02› | `declaracion_jurada` |
| RF-024 | Registrar **vehículos** con los datos de la tarjeta de propiedad, y mantener marcas, modelos, valores referenciales y actualización de placas | `vehiculos` |
| RF-025 | Calcular el **impuesto al patrimonio vehicular** por contribuyente o placa, con modo **simulación** que no genera declaración jurada ‹bloqueado por D-02› | `vehicular_calculo` |
| RF-026 | Registrar la **transferencia de predio**, clasificada por tipo, y determinar la **alcabala**, con las exoneraciones que correspondan ‹bloqueado por D-02› | `transferencia_predio`, `alcabala` |
| RF-027 | Registrar la **transferencia de vehículo** y emitir los formatos de descargo | `transferencia_vehiculo` |
| RF-028 | Registrar **espectáculos públicos no deportivos** y determinar su impuesto ‹bloqueado por D-02› | `espectaculos` |
| RF-029 | Registrar **beneficios**: inafectación, exoneración de jubilados, hospedaje, monumento histórico, predio rústico, predios sin servicio de limpieza, parques o relleno; con vigencia y base legal | `beneficios` |
| RF-030 | Consultar el **histórico de transferencias** de un predio | `transferencia_predio` |

## Cuenta corriente — `cuentacorriente`

| # | Requisito | Opciones |
|---|---|---|
| RF-040 | Mantener la cuenta corriente del contribuyente como libro de **cargos y abonos**, inmutable: un asiento no se modifica ni se borra, se reversa | `cuenta_corriente` |
| RF-041 | Consultar deuda con filtros de año, tributo, unidad, cuota, fase y concepto, y emitir estado de cuenta consolidado, agrupado por arbitrios y detallado | `consulta_deuda`, `cuenta_corriente` |
| RF-042 | Calcular la deuda **a una fecha**, con insoluto, reajuste, interés y gasto desglosados. Toda cifra mostrada indica su fecha ‹bloqueado por D-02› | `consulta_deuda` |
| RF-043 | Registrar **alta de deuda** (nota de abono) indicando tributo, fase, concepto, cuotas, insoluto, reajuste, interés y gasto, con sustento | `alta_deuda` |
| RF-044 | Registrar **baja de deuda** (nota de cargo): cancelación o reducción parcial, afectando insoluto, reajuste, interés o gasto, con sustento | `baja_deuda` |
| RF-045 | Consultar altas y bajas y emitir sus formatos | `consulta_altas_bajas` |
| RF-046 | Consulta **unificada predial-arbitrios**: declaraciones juradas, saldo, deudas pendientes, pagos, movimientos, fraccionamientos y valores emitidos | `consulta_unificada`, `consulta_resumen_predial` |
| RF-047 | Consultar deuda **con beneficio** aplicado según la campaña vigente ‹bloqueado por D-02› | `consulta_deudas_beneficio` |
| RF-048 | Consultar pagos, a detalle y consolidados por recibo, con emisión de padrones | `consulta_pagos` |
| RF-049 | Emitir **constancia de no adeudo** | `constancia` |

## Fiscalización — `fiscalizacion`

| # | Requisito | Opciones |
|---|---|---|
| RF-050 | Programar procesos de fiscalización por criterio y periodo | `fisc_programa` |
| RF-051 | Registrar la ficha de fiscalización predial —homóloga de la ficha de rentas— y calcular el impuesto fiscalizado ‹bloqueado por D-02› | `fisc_predial` |
| RF-052 | Registrar la fiscalización vehicular y calcular el impuesto fiscalizado ‹bloqueado por D-02› | `fisc_vehicular` |
| RF-053 | **Liquidar** el resultado: consolidado de deudas y multas, con reliquidaciones | `fisc_resultados` |
| RF-054 | **Transferir a rentas** lo fiscalizado: lo hallado sobrescribe lo declarado, dejando versión y sustento ‹verificado en #52 contra PostgreSQL: la versión anterior queda intacta y cerrada, la nueva nace con `origen = FISCALIZACION`, y el padrón de antes se reconstruye pidiendo la ficha vigente a una fecha anterior. Con el último paso reventado no quedan ni la ficha nueva, ni los cargos, ni el papel› | `fisc_resultados` |
| RF-055 | Identificar **omisos y subvaluadores** | `fisc_omisos` |
| RF-056 | Consultar el estado de cuenta de fiscalización y el histórico de versiones de un proceso | `fisc_estado_cuenta`, `fisc_historico` |
| RF-057 | Emitir la **resolución de determinación** de fiscalización ‹#52: es el acto que determina de oficio, con su documento y su numeración, no un `valor` de tipo RD —un valor formaliza deuda ya asentada, y esta resolución es la que la asienta—. Las cifras que D-02a bloquea salen como «—» y nunca como cero› | `resolucion_determinacion_fisc` |

## Sanciones — `sanciones`

| # | Requisito | Opciones |
|---|---|---|
| RF-060 | Registrar **papeletas de tránsito** con infractor, propietario, vehículo, lugar, fecha y código de infracción | `papeletas` |
| RF-061 | Mostrar el desglose de la multa: base imponible, % de la infracción, importe, % realmente a cobrar, importe final y monto con beneficio ‹bloqueado por D-02› | `papeletas` |
| RF-062 | Buscar papeletas por número, placa, infractor, propietario, rango de fechas y estado de deuda | `transito_busqueda` |
| RF-063 | Mantener el catálogo de **códigos de infracción de tránsito** | `codigos_transito` |
| RF-064 | Registrar descargos e internamiento vehicular | `transito_descargos`, `internamiento` |
| RF-065 | Emitir resolución de gerencia **ordinaria** y **sancionadora**, y registrar todos los documentos emitidos por papeleta | `transito_rg_ordinaria`, `transito_rg_sancionadora`, `transito_documentos` |
| RF-066 | Generar **valores masivos** de papeletas, con numeración correlativa | `transito_valores` |
| RF-067 | Cambiar el número de una papeleta dejando traza | `transito_cambio_numero` |
| RF-068 | Emitir record de conductor, record vehicular, constancia libre de infracciones, padrones y resúmenes de recaudación, por código y por iniciales de placa | `transito_record_*`, `transito_padron*`, `transito_resumen_*` |
| RF-070 | Registrar la **notificación administrativa** previa | `adm_notificacion` |
| RF-071 | Registrar la **papeleta administrativa**, enlazada o no a su notificación, y generar la multa | `infracciones_adm` |
| RF-072 | Mantener el cuadro de infracciones y sanciones administrativas (CUIS) | `codigos_cuis` |
| RF-073 | Generar valores masivos de papeletas administrativas | `adm_valores` |
| RF-074 | Emitir resolución de gerencia y su notificación; emitir padrones de notificaciones, vencidas y por contribuyente, y resumen de recaudación | `adm_*` |

## Tesorería — `tesoreria`

| # | Requisito | Opciones |
|---|---|---|
| RF-080 | **Caja tributaria**: seleccionar deudas del contribuyente con filtros, aplicar forma de pago y campaña de beneficio, y cobrar en una transacción ‹escritura publicada #33: el importe lo relee `cuentacorriente` con `deudaActualizadaA(fecha de pago)` y el recibo dice a qué fecha; la campaña de beneficio se **registra** pero no descuenta, ‹bloqueado por D-02b›; modalidad `NORMAL` (a cuenta, preconvenio y cuota de convenio son de RF-084)› | `caja_tributaria` |
| RF-081 | **Caja de tasas**: cobrar derechos por área, contribuyente y tributo ‹escritura publicada #33: la tarifa sale de `tasa`, vigente a la fecha del cobro, nunca de la petición ni de una constante (regla 5)› | `caja_tasas` |
| RF-082 | Emitir **duplicado de recibo** por número ‹escritura publicada #34: el papel sale de lo congelado en `recibo`/`recibo_detalle`, nunca de volver a preguntarle al libro; va marcado como duplicado y numerado, y si el recibo está anulado lo dice. La reimpresión idéntica no se afirma: el primer duplicado guarda el SHA-256 y el segundo lo compara› | `duplicado_recibo` |
| RF-083 | **Anular recibo** el mismo día del pago, devolviendo la deuda cancelada a pendiente mediante asiento de reversión ‹escritura publicada #34: la anulación se **agrega** a `recibo_movimiento` —el recibo no se edita (`V29`)—, una sola vez por recibo (índice único parcial) y con el turno del recibo, para que el arqueo de RF-088 pueda restarla. Anular un recibo de otro cajero exige además el privilegio `ESPECIAL`› | `anulacion_recibo` |
| RF-084 | Registrar **convenio de fraccionamiento**: preconvenio (cuota inicial en caja), selección de deudas, generación de cuotas y emisión de solicitud, compromiso y resolución | `fraccionamiento` |
| RF-085 | **Anular, reformular o quebrar** un convenio; al hacerlo, las deudas fraccionadas vuelven a su estado anterior | `anulacion_convenio` |
| RF-086 | Consultar convenios: resumen, cuotas, deuda original, deudas fraccionadas, pagos y quiebre | `consulta_convenios` |
| RF-087 | Mostrar el **avance de recaudación** del día en tiempo real por cajero | `avance_recaudacion` |
| RF-088 | Emitir el **cierre de caja diario** por cajero | `cierre_caja` |
| RF-089 | Distribuir la recaudación **por área y por partida presupuestal**, y por tipo de tributo | `recaudacion_area` |

## Valores — `valores`

| # | Requisito | Opciones |
|---|---|---|
| RF-090 | Generar **valor individual**: orden de pago, resolución de determinación o resolución de multa, con base legal y deudas incluidas | `valores_individual` |
| RF-091 | Generar **valores masivos** en tres etapas: criterio (individual o importado de hoja de cálculo), generación con notificaciones e impresión masiva | `valores_masivo` |
| RF-092 | Buscar y mantener valores emitidos, con su formato impreso según tipo | `valores_busqueda` |
| RF-093 | Registrar la **notificación** del valor con su acuse | `notificacion_valores` |
| RF-094 | Declarar la **prescripción** de una deuda ‹bloqueado por D-02› | `prescripcion` |
| RF-095 | **Pasar valores a coactiva** | `pase_coactiva` |

## Coactiva — `coactiva`

| # | Requisito | Opciones |
|---|---|---|
| RF-100 | **Importar** un valor emitido a coactiva asignándole número de expediente | `importacion_valores` |
| RF-101 | Emitir la **REC** (Resolución de Ejecución Coactiva) y, para la medida cautelar, la **REC-2** | `rec_impresion`, `proceso_coactivo` |
| RF-102 | Registrar **actos coactivos** y emitir sus documentos | `actos_coactivos` |
| RF-103 | Registrar y emitir **notificaciones coactivas**, una o varias por expediente | `notificaciones_coactivas` |
| RF-104 | Liquidar **costas procesales** ‹bloqueado por D-02› | `costas_procesales` |
| RF-105 | Registrar **fraccionamiento coactivo**, con el mismo ciclo que el ordinario | `fraccionamiento_coactivo` |
| RF-106 | Gestionar el historial de estados del expediente y cambiar la dirección referencial | `expediente_historial`, `cambiar_direccion_ref` |
| RF-107 | Consultar deudas en coactiva, con y sin beneficio | `coactiva_consulta_deudas`, `coactiva_deudas_beneficio` |

## Autorizaciones y licencias — `licencias`

| # | Requisito | Opciones |
|---|---|---|
| RF-110 | Registrar **licencia de funcionamiento** con varios giros CIIU, ficha del predio, documentos (declaraciones juradas, defensa civil), duplicados, cancelación y observaciones; validar el recibo de caja del trámite | `licencia_funcionamiento` |
| RF-111 | Emitir resolución de licencia, de **cancelación** y de **duplicado** | `licencia_resolucion_*` |
| RF-112 | Mantener el catálogo **CIIU**, extensible por el usuario | `ciiu` |
| RF-113 | Registrar **licencia de edificación** siguiendo las secciones del FUE: licencia, solicitante, representante legal, datos urbanos, documentos, características del proyecto con valorización por pisos y estructuras, proyectistas, responsable de obra y ampliación o revalidación ‹bloqueado por D-02› | `fue_edificacion` |
| RF-114 | Registrar **anuncios y propaganda** y **generar automáticamente la deuda** por la tasa correspondiente ‹bloqueado por D-02› | `anuncios` |
| RF-115 | Emitir padrones y resúmenes de licencias y anuncios, y certificados | `licencia_padron`, `licencia_resumen_anual`, `edificacion_reporte`, `anuncios_reportes`, `certificados` |

## Seguridad — `seguridad`

| # | Requisito | Opciones |
|---|---|---|
| RF-120 | Mantener **módulos**, **grupos**, **usuarios**, **accesos y políticas**, **miembros** (usuarios en grupos) y **permisos** | `modulos`, `grupos`, `usuarios`, `accesos`, `miembros`, `permisos` |
| RF-121 | Otorgar privilegios diferenciados por acceso: ejecución, lectura, registro, modificación, eliminación, impresión y especiales | `permisos` |
| RF-122 | **Reconocer automáticamente una opción de menú nueva** y ofrecerla para configurar sus niveles de acceso | `accesos` |
| RF-123 | Limitar la autorización de acceso por **fecha de inicio y fin**, y habilitar o inhabilitar usuarios y grupos | `usuarios`, `grupos` |
| RF-124 | Consultar la **auditoría**: quién modificó qué, cuándo, desde qué máquina e IP, y con qué observación | `auditoria` |
| RF-125 | Cambiar el **ejercicio de trabajo** de la sesión y la contraseña propia | `cambiar_anio`, `cambiar_clave` |
| RF-126 | Consultar el estado de las **copias de seguridad** | `respaldo` |

## Transversales

| # | Requisito | Opciones |
|---|---|---|
| RF-130 | **Panel de recaudación** con indicadores del ejercicio en tiempo real | `inicio` |
| RF-131 | **Portal del contribuyente**: el ciudadano **inicia sesión** en su propio realm —emisor distinto del de funcionarios— y ve su situación **en todas las municipalidades del sistema donde figure**, sin teclear ningún documento: lo trae firmado su token. El servidor recorre el registro de municipalidades, una transacción y un `SET LOCAL` por rama, y compone la respuesta con **una sola** fecha de corte ‹D-07 cerrada por [ADR-0020](../30-arquitectura/adr/ADR-0020-la-sesion-del-ciudadano.md); D-15 decidida: el documento lo acredita el enrolamiento en ventanilla›. **El pago en línea queda fuera**, y no por comodidad: D-14 —la imputación de un pago parcial— sigue abierta, y el asiento de un cobro exige caja, serie, turno y cajero, que el ciudadano no tiene | `portal` (la vista del funcionario, sin cambios) y `apps/portal` |
| RF-132 | Exportar cualquier reporte a hoja de cálculo (`.xls`) y a texto enriquecido (`.rtf`), como promete el manual | todos los reportes |
| RF-133 | Toda operación compuesta se registra **completa o no se registra**: transacción única ‹verificado en #33 contra PostgreSQL: se provoca el fallo con el recibo ya insertado y sus abonos ya asentados, y quedan cero filas de las tres tablas› | todos los procesos |

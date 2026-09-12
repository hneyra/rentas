/**
 * Las tres lecturas de seguridad **tal como las contesta la instalacion**, copiadas de un
 * `curl` y no inventadas. Es el hermano de `sesionMedida.ts`, y existe por lo mismo.
 *
 * <h2>Para que existe</h2>
 *
 * Desde I-3 el arbol del marco no se puede montar sin decir que modulos publica el backend:
 * `MarcoProps` exige `arbol` y `permisos` sin respaldo, que es lo que impide que vuelva a
 * colarse una navegacion constante. Eso deja a las pruebas del marco —que son del MARCO y no
 * de la seguridad— teniendo que decirlo cuarenta y cuatro veces. Con cuarenta y cuatro
 * literales sueltos, el dia que `GET /seguridad/modulos` cambie de forma habria cuarenta y
 * cuatro sitios que corregir y ninguno que lo dijera.
 *
 * <h2>Por que es una captura y no una invencion</h2>
 *
 * Estos son los bytes que devuelve la instalacion, medidos el 2026-09-07 con la cuenta
 * `jperez` (municipalidad 1):
 *
 * <pre>
 * GET /rentas/api/v1/seguridad/modulos          -> 12 modulos, envoltorio paginado
 * GET /rentas/api/v1/seguridad/accesos?tamano=200 -> 134 accesos, cada uno con su `moduloId`
 * GET /rentas/api/v1/seguridad/sesion/permisos  -> 134 llaves, una por acceso
 * </pre>
 *
 * <h2>Lo que la captura deja MEDIDO, y no es lo que se esperaba</h2>
 *
 * **Las dos cuentas de la instalacion tienen exactamente los mismos permisos**: `jperez` y
 * `administrador` contestan las mismas 134 llaves con los mismos siete privilegios cada una
 * —comparado llave a llave, cero diferencias—. O sea que **ninguna cuenta de esta instalacion
 * ejercita el filtro del AC2**: con cualquiera de las dos, el arbol sale entero. Por eso la
 * prueba del filtro no se apoya en una cuenta sino en `sinLosAccesosDe`, que quita de esta
 * misma matriz los accesos de un modulo y comprueba que el modulo se cae. Una prueba escrita
 * contra «la cuenta que no puede» no se habria podido escribir, y peor: habria pasado en verde
 * sin filtrar nada.
 *
 * <h2>No lo importa ningun modulo de produccion, y se comprueba</h2>
 *
 * `verificaciones/camino-a-la-api.test.ts` recorre `src/` y exige que solo lo importen archivos
 * de prueba. Sin esa guarda, esto acabaria siendo el respaldo que `MarcoProps` existe para
 * prohibir: un `arbol ?? ARBOL_MEDIDO` en cualquier sitio devolveria la navegacion constante
 * que I-3 vino a quitar, y esta vez con una constante que ademas parece medida.
 *
 * ARCHIVO GENERADO de los tres `curl`. Se regenera; no se edita a mano.
 */

import type {
  AccesoDelSistema,
  ModuloDelSistema,
  PermisosDeLaSesion,
} from '../datos/lecturas.ts';

/** Los doce modulos que publica la instalacion, en el orden en que los publica. */
export const MODULOS_MEDIDOS: readonly ModuloDelSistema[] = [
  { id: 1, codigo: 'INICIO', nombre: 'Inicio', orden: 0, activo: true },
  { id: 3, codigo: 'CATASTRO', nombre: 'Catastro', orden: 0, activo: true },
  { id: 15, codigo: 'RENTAS_REGISTRO', nombre: 'Rentas · Registro', orden: 0, activo: true },
  { id: 30, codigo: 'FISCALIZACION', nombre: 'Fiscalización', orden: 0, activo: true },
  { id: 38, codigo: 'TRANSITO', nombre: 'Tránsito', orden: 0, activo: true },
  { id: 61, codigo: 'INFRACCIONES_ADMINISTRATIVAS', nombre: 'Infracciones administrativas', orden: 0, activo: true },
  { id: 74, codigo: 'TESORERIA', nombre: 'Tesorería', orden: 0, activo: true },
  { id: 84, codigo: 'CONSULTAS', nombre: 'Consultas', orden: 0, activo: true },
  { id: 95, codigo: 'VALORES', nombre: 'Valores', orden: 0, activo: true },
  { id: 101, codigo: 'COACTIVA', nombre: 'Coactiva', orden: 0, activo: true },
  { id: 113, codigo: 'AUTORIZACIONES_Y_LICENCIAS', nombre: 'Autorizaciones y licencias', orden: 0, activo: true },
  { id: 124, codigo: 'SEGURIDAD', nombre: 'Seguridad', orden: 0, activo: true },
];

/**
 * Los 134 accesos del catalogo, con el `moduloId` que los ata a su modulo.
 *
 * **Es la unica relacion que el backend publica entre un permiso y un modulo**, y por eso hace
 * falta pedirlos: la matriz de permisos es una bolsa de 134 codigos planos —`papeletas`,
 * `internamiento`, `certificados`— y sin este `moduloId` no hay forma de saber a que rama del
 * arbol pertenece ninguno. Deducirlo del prefijo del codigo seria inventarlo: sale medido que
 * sin el `moduloId` no se puede.
 */
export const ACCESOS_MEDIDOS: readonly AccesoDelSistema[] = [
  { id: 1, moduloId: 1, tipo: 'OPCION_MENU', codigo: 'inicio', nombre: 'Panel de recaudación', activo: true },
  { id: 2, moduloId: 1, tipo: 'OPCION_MENU', codigo: 'portal', nombre: 'Consulta y pago en línea', activo: true },
  { id: 3, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'ficha_urbana', nombre: 'Ficha catastral urbana individual', activo: true },
  { id: 4, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'ficha_economica', nombre: 'Ficha catastral económica', activo: true },
  { id: 5, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'ficha_bienes', nombre: 'Ficha de bienes comunes', activo: true },
  { id: 6, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'ficha_rural', nombre: 'Ficha catastral rural', activo: true },
  { id: 7, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'consulta_fichas', nombre: 'Consulta de fichas catastrales', activo: true },
  { id: 8, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'actualizacion_catastro', nombre: 'Actualización del catastro', activo: true },
  { id: 9, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'ficha_contribuyente_reporte', nombre: 'Reporte de ficha del contribuyente', activo: true },
  { id: 10, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'calles', nombre: 'Mantenimiento de vías y calles', activo: true },
  { id: 11, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'sectores', nombre: 'Sectores, manzanas y lotes', activo: true },
  { id: 12, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'aranceles', nombre: 'Aranceles de terreno', activo: true },
  { id: 13, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'valores_unitarios', nombre: 'Valores unitarios de edificación', activo: true },
  { id: 14, moduloId: 3, tipo: 'OPCION_MENU', codigo: 'depreciacion', nombre: 'Tabla de depreciación', activo: true },
  { id: 15, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'contribuyentes', nombre: 'Contribuyentes', activo: true },
  { id: 16, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'predios_rentas', nombre: 'Predios del contribuyente', activo: true },
  { id: 17, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'predial_individual', nombre: 'Cálculo individual del impuesto predial', activo: true },
  { id: 18, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'predial_masivo', nombre: 'Cálculo masivo del impuesto predial', activo: true },
  { id: 19, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'declaracion_jurada', nombre: 'Declaración jurada — HR, PU y PR', activo: true },
  { id: 20, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'arbitrios', nombre: 'Arbitrios municipales', activo: true },
  { id: 21, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'transferencia_predio', nombre: 'Transferencia de predio', activo: true },
  { id: 22, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'alcabala', nombre: 'Impuesto de alcabala', activo: true },
  { id: 23, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'vehiculos', nombre: 'Ficha de vehículo', activo: true },
  { id: 24, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'vehicular_calculo', nombre: 'Cálculo del impuesto vehicular', activo: true },
  { id: 25, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'transferencia_vehiculo', nombre: 'Transferencia de vehículo', activo: true },
  { id: 26, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'espectaculos', nombre: 'Espectáculos públicos no deportivos', activo: true },
  { id: 27, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'beneficios', nombre: 'Beneficios y exoneraciones', activo: true },
  { id: 28, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'alta_deuda', nombre: 'Alta de deuda', activo: true },
  { id: 29, moduloId: 15, tipo: 'OPCION_MENU', codigo: 'baja_deuda', nombre: 'Baja de deuda', activo: true },
  { id: 30, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_programa', nombre: 'Programación de fiscalización', activo: true },
  { id: 31, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_predial', nombre: 'Fiscalización predial — acta de inspección', activo: true },
  { id: 32, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_vehicular', nombre: 'Fiscalización vehicular', activo: true },
  { id: 33, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_resultados', nombre: 'Resultados y determinaciones', activo: true },
  { id: 34, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_omisos', nombre: 'Omisos y subvaluadores', activo: true },
  { id: 35, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_estado_cuenta', nombre: 'Estado de cuenta de fiscalización', activo: true },
  { id: 36, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'fisc_historico', nombre: 'Histórico de fiscalización predial', activo: true },
  { id: 37, moduloId: 30, tipo: 'OPCION_MENU', codigo: 'resolucion_determinacion_fisc', nombre: 'Resolución de determinación de fiscalización', activo: true },
  { id: 38, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'papeletas', nombre: 'Papeletas de infracción de tránsito', activo: true },
  { id: 39, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_busqueda', nombre: 'Búsqueda de infracciones', activo: true },
  { id: 40, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'codigos_transito', nombre: 'Tabla de códigos de infracción de tránsito', activo: true },
  { id: 41, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_descargos', nombre: 'Descargos y reclamos de papeletas', activo: true },
  { id: 42, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'internamiento', nombre: 'Internamiento vehicular', activo: true },
  { id: 43, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_documentos', nombre: 'Emisión de resoluciones y otros documentos', activo: true },
  { id: 44, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_valores', nombre: 'Generación de valores de tránsito', activo: true },
  { id: 45, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_cambio_numero', nombre: 'Cambio de número de papeleta de tránsito', activo: true },
  { id: 46, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_reportes', nombre: 'Reportes de infracción de tránsito', activo: true },
  { id: 47, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_record_conductor', nombre: 'Record de conductor', activo: true },
  { id: 48, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_record_vehicular', nombre: 'Record vehicular', activo: true },
  { id: 49, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_constancia_libre', nombre: 'Constancia libre de infracciones', activo: true },
  { id: 50, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_padron', nombre: 'Padrón de papeletas de tránsito', activo: true },
  { id: 51, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_estado_cuenta', nombre: 'Estado de cuenta de infracciones', activo: true },
  { id: 52, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_papeleta_reporte', nombre: 'Reporte papeleta de infracción', activo: true },
  { id: 53, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_rg_ordinaria', nombre: 'Resolución de gerencia ordinaria', activo: true },
  { id: 54, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_rg_sancionadora', nombre: 'Resolución de gerencia sancionadora', activo: true },
  { id: 55, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_padron_coactiva', nombre: 'Padrón de papeletas enviadas a coactiva', activo: true },
  { id: 56, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_padron_constancias', nombre: 'Padrón de constancias libres de infracciones', activo: true },
  { id: 57, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_resumen_recaudacion', nombre: 'Resumen de recaudación de tránsito', activo: true },
  { id: 58, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_resumen_papeletas', nombre: 'Resumen de papeletas pendientes y pagadas', activo: true },
  { id: 59, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_resumen_codigo', nombre: 'Resumen de papeletas por código de infracción', activo: true },
  { id: 60, moduloId: 38, tipo: 'OPCION_MENU', codigo: 'transito_resumen_placa', nombre: 'Resumen de papeletas por iniciales de placa', activo: true },
  { id: 61, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_notificacion', nombre: 'Notificación administrativa', activo: true },
  { id: 62, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'infracciones_adm', nombre: 'Infracción administrativa', activo: true },
  { id: 63, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'codigos_cuis', nombre: 'Cuadro único de infracciones y sanciones (CUIS)', activo: true },
  { id: 64, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_codigos_reporte', nombre: 'Reporte de códigos de infracción administrativa', activo: true },
  { id: 65, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_valores', nombre: 'Generación de valores administrativa', activo: true },
  { id: 66, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_estado_cuenta', nombre: 'Estado de cuenta de papeleta administrativa', activo: true },
  { id: 67, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_resolucion_gerencia', nombre: 'Resolución de gerencia', activo: true },
  { id: 68, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_notificacion_resolucion', nombre: 'Notificación de resolución de gerencia', activo: true },
  { id: 69, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_reportes', nombre: 'Reportes de infracción administrativa', activo: true },
  { id: 70, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_padron_notificaciones', nombre: 'Padrón de notificaciones', activo: true },
  { id: 71, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_notificaciones_vencidas', nombre: 'Notificaciones vencidas', activo: true },
  { id: 72, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_notificaciones_contribuyente', nombre: 'Notificaciones por contribuyente', activo: true },
  { id: 73, moduloId: 61, tipo: 'OPCION_MENU', codigo: 'adm_resumen_recaudacion', nombre: 'Resumen de recaudación de papeletas', activo: true },
  { id: 74, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'caja_tributaria', nombre: 'Caja tributaria', activo: true },
  { id: 75, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'caja_tasas', nombre: 'Caja de tasas y derechos administrativos', activo: true },
  { id: 76, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'fraccionamiento', nombre: 'Fraccionamiento tributario', activo: true },
  { id: 77, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'consulta_convenios', nombre: 'Consulta de convenios', activo: true },
  { id: 78, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'duplicado_recibo', nombre: 'Duplicado de recibo', activo: true },
  { id: 79, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'anulacion_recibo', nombre: 'Anulación de recibo', activo: true },
  { id: 80, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'anulacion_convenio', nombre: 'Anulación de convenio', activo: true },
  { id: 81, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'cierre_caja', nombre: 'Cierre y arqueo de caja', activo: true },
  { id: 82, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'avance_recaudacion', nombre: 'Avance de recaudación', activo: true },
  { id: 83, moduloId: 74, tipo: 'OPCION_MENU', codigo: 'recaudacion_area', nombre: 'Recaudación por área', activo: true },
  { id: 84, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'cuenta_corriente', nombre: 'Estado de cuenta corriente', activo: true },
  { id: 85, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_deuda', nombre: 'Consulta de deuda', activo: true },
  { id: 86, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_unificada', nombre: 'Consulta unificada predial-arbitrios', activo: true },
  { id: 87, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_resumen_predial', nombre: 'Consulta resumen predial-arbitrios', activo: true },
  { id: 88, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_altas_bajas', nombre: 'Consulta de altas y bajas', activo: true },
  { id: 89, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_deudas_beneficio', nombre: 'Consulta de deudas con beneficio', activo: true },
  { id: 90, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_pagos', nombre: 'Consulta de pagos', activo: true },
  { id: 91, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_predios', nombre: 'Consulta de predios', activo: true },
  { id: 92, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_vehiculos', nombre: 'Consulta de vehículos', activo: true },
  { id: 93, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'consulta_valores', nombre: 'Consulta de valores emitidos', activo: true },
  { id: 94, moduloId: 84, tipo: 'OPCION_MENU', codigo: 'constancia', nombre: 'Constancia de no adeudo', activo: true },
  { id: 95, moduloId: 95, tipo: 'OPCION_MENU', codigo: 'valores_individual', nombre: 'Generación individual de valores', activo: true },
  { id: 96, moduloId: 95, tipo: 'OPCION_MENU', codigo: 'valores_masivo', nombre: 'Generación masiva de valores', activo: true },
  { id: 97, moduloId: 95, tipo: 'OPCION_MENU', codigo: 'valores_busqueda', nombre: 'Búsqueda y mantenimiento de valores', activo: true },
  { id: 98, moduloId: 95, tipo: 'OPCION_MENU', codigo: 'notificacion_valores', nombre: 'Notificación de valores', activo: true },
  { id: 99, moduloId: 95, tipo: 'OPCION_MENU', codigo: 'prescripcion', nombre: 'Prescripción de la deuda', activo: true },
  { id: 100, moduloId: 95, tipo: 'OPCION_MENU', codigo: 'pase_coactiva', nombre: 'Pase de valores a coactiva', activo: true },
  { id: 101, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'coactiva_expedientes', nombre: 'Expedientes coactivos', activo: true },
  { id: 102, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'importacion_valores', nombre: 'Importación de valores a coactiva', activo: true },
  { id: 103, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'proceso_coactivo', nombre: 'Proceso coactivo', activo: true },
  { id: 104, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'rec_impresion', nombre: 'Impresión de resolución de ejecución coactiva', activo: true },
  { id: 105, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'expediente_historial', nombre: 'Gestionar historial del expediente', activo: true },
  { id: 106, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'cambiar_direccion_ref', nombre: 'Cambiar dirección referencial', activo: true },
  { id: 107, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'costas_procesales', nombre: 'Liquidación de costas procesales', activo: true },
  { id: 108, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'fraccionamiento_coactivo', nombre: 'Fraccionamiento coactivo', activo: true },
  { id: 109, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'actos_coactivos', nombre: 'Registro de actos coactivos', activo: true },
  { id: 110, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'notificaciones_coactivas', nombre: 'Emisión de notificaciones coactivas', activo: true },
  { id: 111, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'coactiva_consulta_deudas', nombre: 'Consulta de deudas en coactiva', activo: true },
  { id: 112, moduloId: 101, tipo: 'OPCION_MENU', codigo: 'coactiva_deudas_beneficio', nombre: 'Consulta de deudas en beneficio (coactiva)', activo: true },
  { id: 113, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'anuncios', nombre: 'Anuncio y propaganda', activo: true },
  { id: 114, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'anuncios_reportes', nombre: 'Reportes de anuncio y propaganda', activo: true },
  { id: 115, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'licencia_funcionamiento', nombre: 'Licencia de funcionamiento', activo: true },
  { id: 116, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'licencia_padron', nombre: 'Padrón de licencias de funcionamiento', activo: true },
  { id: 117, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'licencia_resumen_anual', nombre: 'Resumen de licencias por año', activo: true },
  { id: 118, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'licencia_resolucion_cancelacion', nombre: 'Resolución de cancelación de licencia', activo: true },
  { id: 119, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'licencia_resolucion_duplicado', nombre: 'Resolución de duplicado de licencia', activo: true },
  { id: 120, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'fue_edificacion', nombre: 'Formulario único de edificación (FUE)', activo: true },
  { id: 121, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'edificacion_reporte', nombre: 'Reporte general de licencias de edificación', activo: true },
  { id: 122, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'ciiu', nombre: 'Catálogo CIIU de giros', activo: true },
  { id: 123, moduloId: 113, tipo: 'OPCION_MENU', codigo: 'certificados', nombre: 'Certificados de numeración y zonificación', activo: true },
  { id: 124, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'modulos', nombre: 'Módulos del sistema', activo: true },
  { id: 125, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'usuarios', nombre: 'Usuarios del sistema', activo: true },
  { id: 126, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'grupos', nombre: 'Grupos de usuarios', activo: true },
  { id: 127, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'accesos', nombre: 'Accesos y políticas', activo: true },
  { id: 128, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'miembros', nombre: 'Gestión de miembros', activo: true },
  { id: 129, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'permisos', nombre: 'Permisos y niveles de accesibilidad', activo: true },
  { id: 130, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'cambiar_anio', nombre: 'Cambiar el año de trabajo', activo: true },
  { id: 131, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'cambiar_clave', nombre: 'Cambiar contraseña', activo: true },
  { id: 132, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'auditoria', nombre: 'Auditoría del sistema', activo: true },
  { id: 133, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'parametros', nombre: 'Parámetros del sistema', activo: true },
  { id: 134, moduloId: 124, tipo: 'OPCION_MENU', codigo: 'respaldo', nombre: 'Copias de seguridad', activo: true },
];

/**
 * La matriz de permisos efectivos de `jperez`: 134 llaves con sus siete privilegios.
 *
 * Es la respuesta entera y no un recorte. Recortarla a las llaves «interesantes» habria dejado
 * el filtro del AC2 midiendo un arbol al que ya le faltaban modulos por construccion de la
 * muestra, que es la manera silenciosa de que una prueba de permisos pase en verde.
 */
export const PERMISOS_MEDIDOS: PermisosDeLaSesion = {
  'accesos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'actos_coactivos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'actualizacion_catastro': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_codigos_reporte': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_estado_cuenta': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_notificacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_notificacion_resolucion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_notificaciones_contribuyente': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_notificaciones_vencidas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_padron_notificaciones': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_reportes': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_resolucion_gerencia': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_resumen_recaudacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'adm_valores': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'alcabala': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'alta_deuda': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'anulacion_convenio': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'anulacion_recibo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'anuncios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'anuncios_reportes': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'aranceles': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'arbitrios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'auditoria': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'avance_recaudacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'baja_deuda': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'beneficios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'caja_tasas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'caja_tributaria': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'calles': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'cambiar_anio': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'cambiar_clave': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'cambiar_direccion_ref': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'certificados': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'cierre_caja': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'ciiu': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'coactiva_consulta_deudas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'coactiva_deudas_beneficio': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'coactiva_expedientes': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'codigos_cuis': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'codigos_transito': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'constancia': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_altas_bajas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_convenios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_deuda': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_deudas_beneficio': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_fichas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_pagos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_predios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_resumen_predial': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_unificada': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_valores': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'consulta_vehiculos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'contribuyentes': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'costas_procesales': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'cuenta_corriente': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'declaracion_jurada': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'depreciacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'duplicado_recibo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'edificacion_reporte': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'espectaculos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'expediente_historial': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'ficha_bienes': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'ficha_contribuyente_reporte': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'ficha_economica': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'ficha_rural': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'ficha_urbana': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_estado_cuenta': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_historico': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_omisos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_predial': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_programa': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_resultados': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fisc_vehicular': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fraccionamiento': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fraccionamiento_coactivo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'fue_edificacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'grupos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'importacion_valores': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'infracciones_adm': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'inicio': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'internamiento': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'licencia_funcionamiento': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'licencia_padron': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'licencia_resolucion_cancelacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'licencia_resolucion_duplicado': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'licencia_resumen_anual': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'miembros': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'modulos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'notificacion_valores': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'notificaciones_coactivas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'papeletas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'parametros': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'pase_coactiva': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'permisos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'portal': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'predial_individual': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'predial_masivo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'predios_rentas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'prescripcion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'proceso_coactivo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'rec_impresion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'recaudacion_area': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'resolucion_determinacion_fisc': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'respaldo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'sectores': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transferencia_predio': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transferencia_vehiculo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_busqueda': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_cambio_numero': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_constancia_libre': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_descargos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_documentos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_estado_cuenta': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_padron': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_padron_coactiva': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_padron_constancias': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_papeleta_reporte': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_record_conductor': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_record_vehicular': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_reportes': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_resumen_codigo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_resumen_papeletas': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_resumen_placa': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_resumen_recaudacion': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_rg_ordinaria': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_rg_sancionadora': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'transito_valores': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'usuarios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'valores_busqueda': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'valores_individual': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'valores_masivo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'valores_unitarios': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'vehicular_calculo': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
  'vehiculos': ['ejecucion', 'lectura', 'registro', 'modificacion', 'eliminacion', 'impresion', 'especial'],
};

/**
 * La misma matriz sin ninguno de los accesos de esos modulos: la cuenta que NO puede abrirlos.
 *
 * Existe porque la instalacion no tiene una cuenta asi —las dos que hay pueden todo, medido— y
 * el AC2 pide comprobar las dos direcciones. Quitar por `moduloId` y no por una lista de
 * codigos escrita a mano es lo que hace que la muestra siga siendo cierta el dia que el
 * catalogo gane un acceso mas.
 */
export function sinLosAccesosDe(...codigosDeModulo: readonly string[]): PermisosDeLaSesion {
  const ids = new Set(
    MODULOS_MEDIDOS.filter((m) => codigosDeModulo.includes(m.codigo)).map((m) => m.id),
  );
  const fuera = new Set(
    ACCESOS_MEDIDOS.filter((a) => ids.has(a.moduloId)).map((a) => a.codigo),
  );
  return Object.fromEntries(
    Object.entries(PERMISOS_MEDIDOS).filter(([codigo]) => !fuera.has(codigo)),
  );
}

/** La misma matriz con `cambiar_anio` sin el privilegio `especial`, y con los otros seis. */
export function sinElPrivilegioEspecial(): PermisosDeLaSesion {
  return {
    ...PERMISOS_MEDIDOS,
    cambiar_anio: (PERMISOS_MEDIDOS['cambiar_anio'] ?? []).filter((p) => p !== 'especial'),
  };
}

import type {
  ActaDeFiscalizacion,
  FilaDeLaMuestra,
  Paginado,
  ProgramaDeFiscalizacion,
  ResolucionDeDeterminacion,
} from '../lecturas.ts';

/**
 * Las respuestas con que se ejercitan los tres conectores de Fiscalizacion (#179).
 *
 * <h2>Que son, y que NO son</h2>
 *
 * **No son una captura de la instalacion.** Son respuestas construidas campo a campo desde
 * `docs/50-api/formas-de-la-api.json` y desde el codigo de los seis controladores, igual que
 * `consultasDeMuestra.ts` (#169) — y corren **el mismo riesgo**, que es el peor que hay en `src/`:
 * parecen datos legitimos. Un `resolucion ?? RESOLUCION_SIN_CIFRAS` ensenaria la determinacion de
 * un contribuyente inventado con la cara de un dato medido. Por eso este archivo esta en la lista
 * `CAPTURAS` de `verificaciones/camino-a-la-api.test.ts`, que comprueba que **solo lo importan
 * archivos de prueba**.
 *
 * <h2>Por que hay DOS resoluciones y DOS actas</h2>
 *
 * Porque las dos ramas que hay que ejercitar son de verdad dos, y con una sola muestra cada
 * conector pasaria sin haberlas recorrido:
 *
 *   · **D-02a** — hoy los importes de una resolucion llegan **nulos** y la tabla dice «sin
 *     cifrar»; el dia que se firme el cuadro de valores llegaran con cifra y la misma tabla tendra
 *     que escribirlos. `RESOLUCION_SIN_CIFRAS` y `RESOLUCION_CIFRADA` son esas dos.
 *   · **`usoHallado` nulo** — «no se anoto», que no es «coincide con lo declarado», y solo un acta
 *     predial lo lleva. `ACTA_CON_USO` y `ACTA_SIN_USO` son esas dos.
 *
 * <h2>Y por que los importes no llevan separador de millares</h2>
 *
 * Porque es como el backend los sirve: texto decimal plano. Los que llevan coma son los del
 * artboard, y `verificaciones/sin-cifras-inventadas.test.ts` los prohibe en `src/` — con razon: una
 * cifra de ejemplo en el codigo que se sirve se lee como real.
 */

/** Un programa abierto, con las once columnas que `ProgramaResource` publica. */
export const PROGRAMA: ProgramaDeFiscalizacion = {
  id: 14,
  codigo: 'PF-2026-014',
  descripcion: 'Cruce de area construida en el sector 02',
  tipo: 'PREDIAL',
  fechaInicio: '2026-03-02',
  fechaFin: null,
  estado: 'EN_PROCESO',
  ejercicio: '2026',
  sector: '02',
  criterio: 'SUBVALUADOR',
  fiscalizador: 'Reto Santos, Victor',
};

/** La relacion de programas tal como llega con `?tamano=1`. */
export const PROGRAMAS: Paginado<ProgramaDeFiscalizacion> = {
  contenido: [PROGRAMA],
  pagina: 0,
  tamano: 1,
  totalElementos: 6,
  totalPaginas: 6,
  hayMas: true,
};

/** Ninguno: el caso en que no hay programa del que pedir muestra. */
export const SIN_PROGRAMAS: Paginado<ProgramaDeFiscalizacion> = {
  ...PROGRAMAS,
  contenido: [],
  totalElementos: 0,
  totalPaginas: 0,
  hayMas: false,
};

/**
 * Dos predios sorteados, y los dos ejercitan algo.
 *
 * El primero esta **visitado** —ya tiene acta en el programa— y tiene titular; el segundo no esta
 * visitado y **no tiene titular vigente**, que es un dato del padron y no un hueco de la interfaz.
 */
export const MUESTRA: Paginado<FilaDeLaMuestra> = {
  contenido: [
    {
      programaId: 14,
      predioId: 9014,
      codRefCatastral: '02-014-D-14-01',
      contribuyenteId: 25673,
      codContribuyente: '00000025673',
      titular: 'Suc. Rufina Medina Medina',
      sector: '02',
      condicion: 'SUBVALUADOR',
      areaCatastral: '198.00',
      areaDeclarada: '164.50',
      diferenciaDeArea: '33.50',
      visitado: true,
      fechaSorteo: '2026-03-02',
    },
    {
      programaId: 14,
      predioId: 9021,
      codRefCatastral: '04-021-B-07-00',
      contribuyenteId: null,
      codContribuyente: null,
      titular: null,
      sector: '04',
      condicion: 'OMISO',
      areaCatastral: '120.00',
      areaDeclarada: null,
      diferenciaDeArea: null,
      visitado: false,
      fechaSorteo: '2026-03-02',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 2,
  totalPaginas: 1,
  hayMas: false,
};

/** Un acta predial con uso anotado: las DOS filas del contraste. */
export const ACTA_CON_USO: ActaDeFiscalizacion = {
  id: 418,
  programaId: 14,
  version: 1,
  contribuyenteId: 25673,
  predioId: 9014,
  vehiculoId: null,
  fichaId: 3312,
  fechaVisita: '2026-04-18',
  fiscalizador: 'Reto Santos, Victor',
  hallazgo: 'SUBVALUADOR',
  areaHallada: '198.00',
  usoHallado: 'COMERCIO',
  detalle: 'Ampliacion no declarada en el segundo piso',
  estado: 'ABIERTA',
};

/** La misma acta sin uso anotado: `null` es «no se anoto», y la fila del uso no sale. */
export const ACTA_SIN_USO: ActaDeFiscalizacion = {
  ...ACTA_CON_USO,
  usoHallado: null,
  hallazgo: null,
};

/** La relacion de actas tal como llega con `?tamano=1`. */
export const ACTAS: Paginado<ActaDeFiscalizacion> = {
  contenido: [ACTA_CON_USO],
  pagina: 0,
  tamano: 1,
  totalElementos: 84,
  totalPaginas: 84,
  hayMas: true,
};

/** Ninguna: el caso en que no hay acta que dibujar. */
export const SIN_ACTAS: Paginado<ActaDeFiscalizacion> = {
  ...ACTAS,
  contenido: [],
  totalElementos: 0,
  totalPaginas: 0,
  hayMas: false,
};

/** La resolucion como llega HOY: con sus lineas y **sin una sola cifra** (D-02a). */
export const RESOLUCION_SIN_CIFRAS: ResolucionDeDeterminacion = {
  numero: 'RDF-2026-000001',
  fecha: '2026-06-30',
  aLaFecha: '2026-06-30',
  nLiquidacion: 'LIQ-2026-000418',
  versionDeLaLiquidacion: 1,
  periodoDesde: 2024,
  periodoHasta: 2026,
  codContribuyente: '00000025673',
  contribuyente: 'Suc. Rufina Medina Medina',
  predioId: 9014,
  vehiculoId: null,
  documentoSustento: 'ACT-2026-00418',
  sustento: 'Area construida hallada mayor que la declarada',
  baseLegal: 'Art. 176 del Codigo Tributario',
  fichaAnteriorId: 3312,
  fichaNuevaId: 3313,
  usuarioRegistro: 'jperez',
  observacion: 'Transferida a rentas',
  lineas: [
    {
      ejercicio: 2024,
      determinado: null,
      declarado: null,
      diferencia: null,
      multa: null,
      total: null,
      condicion: 'SUBVALUADOR',
      areaDeclarada: '164.50',
      areaHallada: '198.00',
    },
    {
      ejercicio: 2025,
      determinado: null,
      declarado: null,
      diferencia: null,
      multa: null,
      total: null,
      condicion: 'SUBVALUADOR',
      areaDeclarada: '164.50',
      areaHallada: '198.00',
    },
  ],
  cargosAsentados: null,
};

/** La misma resolucion el dia que D-02a este cerrada: las mismas lineas con cifra. */
export const RESOLUCION_CIFRADA: ResolucionDeDeterminacion = {
  ...RESOLUCION_SIN_CIFRAS,
  lineas: [
    {
      ejercicio: 2024,
      determinado: '33500.00',
      declarado: '27400.00',
      diferencia: '201.00',
      multa: '89.20',
      total: '290.20',
      condicion: 'SUBVALUADOR',
      areaDeclarada: '164.50',
      areaHallada: '198.00',
    },
  ],
};

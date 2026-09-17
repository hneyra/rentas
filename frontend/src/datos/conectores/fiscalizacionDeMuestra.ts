import type {
  ActaDeFiscalizacion,
  EmbudoDelPrograma,
  FilaDeLaMuestra,
  Paginado,
  ProgramaDeFiscalizacion,
  ResolucionDeDeterminacion,
  ResolucionEnLaRelacion,
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
 *   · **el lado DECLARADO ausente** (#191, #215) — un acta **vehicular**, y una predial de un
 *     predio sin ficha registrada a la fecha de la visita, llegan con `areaDeclarada`,
 *     `usoDeclarado` y `diferenciaDeArea` nulos. Eso **no es «no publicado»**: es «no consta», y la
 *     celda tiene que decir esa causa y no la otra. `ACTA_VEHICULAR` es esa.
 *   · **los totales de la resolucion** (#193) — `esperaSusCifras` separa «el campo llego vacio» de
 *     «el campo no esta», y las dos resoluciones lo traen a los dos lados.
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
  // Las dos mitades del contraste, con la diferencia YA RESTADA por el backend (#191): 198.00
  // menos 164.50. Aqui nunca se resta —es la columna que sostiene la determinacion—, se copia.
  areaDeclarada: '164.50',
  areaHallada: '198.00',
  diferenciaDeArea: '33.50',
  usoDeclarado: 'CASA_HABITACION',
  usoHallado: 'COMERCIO',
  detalle: 'Ampliacion no declarada en el segundo piso',
  estado: 'ABIERTA',
};

/** La misma acta sin uso anotado: `null` es «no se anoto», y la fila del uso no sale. */
export const ACTA_SIN_USO: ActaDeFiscalizacion = {
  ...ACTA_CON_USO,
  usoDeclarado: null,
  usoHallado: null,
  hallazgo: null,
};

/**
 * Un acta **vehicular**: las tres del lado declarado en nulo, y eso es un dato (#191).
 *
 * Un vehiculo no tiene area ni uso declarados contra los que contrastar, asi que el backend no
 * tiene de donde sacarlos — igual que una predial de un predio **sin ficha registrada a la fecha de
 * la visita**, que es justamente el predio que no consta en el catastro. Las dos son «no consta» y
 * ninguna es «no publicado»: se cierran con una ficha, no publicando un campo.
 */
export const ACTA_VEHICULAR: ActaDeFiscalizacion = {
  ...ACTA_CON_USO,
  id: 419,
  predioId: null,
  vehiculoId: 7714,
  fichaId: null,
  areaDeclarada: null,
  areaHallada: null,
  diferenciaDeArea: null,
  usoDeclarado: null,
  usoHallado: null,
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
  // El identificador INTERNO del acta, que es lo unico que la operacion publica de ella. No es el
  // «N.º de acta» que el artboard dibuja: un acta no se numera. Ver el javadoc de `FIS_RES`.
  actaId: 418,
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
  // Los tres nulos y `esperaSusCifras: true`: es como llega HOY, con D-02a abierta. El campo
  // existe y esta vacio, que no es lo mismo que no publicarlo — y por eso la pantalla dice «sin
  // cifrar» y no «no publicado».
  insolutoOmitido: null,
  multaTributaria: null,
  totalLiquidado: null,
  esperaSusCifras: true,
  lineas: [
    {
      ejercicio: 2024,
      determinado: null,
      declarado: null,
      baseOmitida: null,
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
      baseOmitida: null,
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

/**
 * La misma resolucion el dia que D-02a este cerrada: las mismas lineas con cifra, y **sus tres
 * totales publicados**.
 *
 * Los suma el backend (`TotalesDeLaDeterminacion`) y aqui se copian: 201.00 + 89.20 = 290.20. Esa
 * suma es la que la rotura R2 de #179 midio dando el total exacto al centimo, y es la razon por la
 * que la pantalla **no la hace**. `baseOmitida` es `determinado − declarado` —33 500 menos
 * 27 400—, tambien restada alli.
 */
export const RESOLUCION_CIFRADA: ResolucionDeDeterminacion = {
  ...RESOLUCION_SIN_CIFRAS,
  insolutoOmitido: '201.00',
  multaTributaria: '89.20',
  totalLiquidado: '290.20',
  esperaSusCifras: false,
  lineas: [
    {
      ejercicio: 2024,
      determinado: '33500.00',
      declarado: '27400.00',
      baseOmitida: '6100.00',
      diferencia: '201.00',
      multa: '89.20',
      total: '290.20',
      condicion: 'SUBVALUADOR',
      areaDeclarada: '164.50',
      areaHallada: '198.00',
    },
  ],
};

/** La relacion de resoluciones tal como llega con `?tamano=1` (#192). Sin una sola cifra. */
export const RESOLUCIONES: Paginado<ResolucionEnLaRelacion> = {
  contenido: [
    {
      numero: 'RDF-2026-000001',
      fecha: '2026-06-30',
      codContribuyente: '00000025673',
      contribuyente: 'Suc. Rufina Medina Medina',
      predioId: 9014,
      vehiculoId: null,
      nLiquidacion: 'LIQ-2026-000418',
      versionDeLaLiquidacion: 1,
      actaId: 418,
      periodoDesde: 2024,
      periodoHasta: 2026,
      documentoSustento: 'ACT-2026-00418',
    },
  ],
  pagina: 0,
  tamano: 1,
  totalElementos: 12,
  totalPaginas: 12,
  hayMas: true,
};

/** Ninguna: todavia no se ha transferido ni una liquidacion. No es una averia. */
export const SIN_RESOLUCIONES: Paginado<ResolucionEnLaRelacion> = {
  ...RESOLUCIONES,
  contenido: [],
  totalElementos: 0,
  totalPaginas: 0,
  hayMas: false,
};

/**
 * El embudo del programa, con las cuatro cifras cuadradas y su fecha (#196).
 *
 * `conActa` es 84 y **no es «con acta cerrada»**: cuenta las unidades con acta viva. Ningun acta
 * sale de `ABIERTA` en este sistema (#214), asi que un campo que contara las cerradas valdria cero
 * siempre — en verde y sin sintoma.
 */
export const EMBUDO: EmbudoDelPrograma = {
  programaId: 14,
  codigo: 'PF-2026-014',
  ejercicio: 2026,
  aLaFecha: '2026-09-17',
  detectadosPorCruce: 3418,
  parametroQueFalta: null,
  programados: 96,
  conActa: 84,
  conDiferencia: 61,
};

/**
 * El mismo embudo de un programa que **no declara sus parametros de sorteo**.
 *
 * `detectadosPorCruce` llega nulo y `parametroQueFalta` dice cual falta: la ausencia viene con su
 * causa dentro, y la celda tiene que decirla en vez de un cero — cero seria «el cruce no senalo a
 * nadie», que es lo contrario de «el cruce no se pudo hacer».
 */
export const EMBUDO_SIN_PARAMETROS: EmbudoDelPrograma = {
  ...EMBUDO,
  detectadosPorCruce: null,
  parametroQueFalta: 'sector',
};

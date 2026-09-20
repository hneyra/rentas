import { afterEach, describe, expect, it, vi } from 'vitest';

import { bloquesConSuIndice } from '../pantallas/bloques.ts';
import { PANTALLAS } from '../pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { coordenada } from '@kamayuk/ui';
import {
  CONECTORES,
  NO_ESTA_EN_EL_PADRON,
  NO_PUBLICADO,
  SIN_CRONOGRAMA,
  TODAVIA_SIN_DETERMINAR,
} from './conectores.ts';
import { CONSTANCIA_NEGADA, FICHA, SIN_CAMPANIA } from './conectores/consultasDeMuestra.ts';
import { RUTAS } from './lecturas.ts';
import {
  ACTA_CON_USO,
  EMBUDO,
  MUESTRA,
  RESOLUCION_SIN_CIFRAS,
} from './conectores/fiscalizacionDeMuestra.ts';
import type {
  CorridaDelPredial,
  DeterminacionGuardada,
  ResumenDeLaCarteraCoactiva,
  ExpedienteDeLaPapeleta,
  InternamientoEnDeposito,
  LiquidacionDeCostas,
  Paginado,
  PrescripcionDeclarada,
  MovimientoDeLaBitacora,
  ProcesoDelExpediente, IndicadorDeRecaudacion, ResumenDePapeletas, TrabajoParado, VehiculoServido } from './lecturas.ts';

/**
 * **Lo que cada pantalla conectada saca de su respuesta** (#97).
 *
 * Lo detallado de las dos hojas de Consultas esta en `conectores/consultas.test.ts` y lo de la
 * bitacora en `conectores/seguridad.test.ts`, al lado de su conector; aqui quedan el centinela del
 * registro y el recorrido que vale para los doce.
 *
 * Lo que se comprueba no es que el mapeo «funcione»: es que **ningun campo se quede sin decidir**.
 * Un campo de solo lectura de una pantalla conectada tiene que estar en uno de los dos sitios —con
 * dato, o declarado «no publicado»—, y **nunca en ninguno**: un campo olvidado se dibuja con el
 * motivo de la PANTALLA, que en una pantalla conectada dice que si esta conectada. Seria un hueco
 * mintiendo sobre su propia causa.
 */

/** La respuesta que la instalacion da de verdad, recortada a lo que el conector usa. */
const CORRIDA: CorridaDelPredial = {
  id: 1,
  ejercicio: '2026',
  alcance: 'PADRON',
  sector: null,
  simulacion: false,
  conjunto: 'V3',
  fechaCalculo: '28/01/2026 02:14',
  observados: 534,
  etapas: [
    { etapa: 'Lectura del padron', registros: 62418, monto: '—', observados: 0, estado: 'Conforme' },
    { etapa: 'Generacion de cuponeras', registros: 61350, monto: '—', observados: 534, estado: 'Observado' },
  ],
};

/**
 * Lo que `coa-panel` recibe desde #272: el resumen de la cartera coactiva, por etapa.
 *
 * Las cifras NO son las del artboard —1 184 / 796 / 412 / 388—, y eso es deliberado: una muestra
 * que las repitiera no podria distinguir la pantalla que dibuja lo que llego de la que dibuja lo
 * que su definicion ya decia.
 */
const RESUMEN_DE_CARTERA: ResumenDeLaCarteraCoactiva = {
  aLaFecha: '2026-09-20',
  ejercicio: null,
  expedientes: 41,
  abiertos: 37,
  sinRec: 12,
  conRecNotificada: 9,
  conMedidaCautelar: 5,
  porEtapa: [
    { etapa: 'INICIADO', codigo: '000', etiqueta: 'INICIADO', expedientes: 12 },
    { etapa: 'REC1_EMITIDA', codigo: '011', etiqueta: 'REC 01 EMITIDO', expedientes: 7 },
    { etapa: 'REC1_NOTIFICADA', codigo: '012', etiqueta: 'REC 01 NOTIFICADA', expedientes: 9 },
    { etapa: 'REC2_EMITIDA', codigo: '021', etiqueta: 'REC 02 EMITIDA', expedientes: 3 },
    { etapa: 'MEDIDA_CAUTELAR', codigo: '031', etiqueta: 'MEDIDA CAUTELAR', expedientes: 5 },
    { etapa: 'SUSPENDIDO', codigo: '041', etiqueta: 'SUSPENDIDO', expedientes: 1 },
    { etapa: 'CONCLUIDO', codigo: '051', etiqueta: 'CONCLUIDO', expedientes: 4 },
  ],
};

/**
 * Lo que `coa-exp` recibe: el proceso de un expediente. Recortado a lo que el conector usa.
 *
 * La forma sale de `docs/50-api/formas-de-la-api.json`; el detalle campo a campo lo prueba
 * `conectores/coactiva.test.tsx`.
 */
const PROCESO = {
  expediente: {
    numero: '2026-0418',
    codContribuyente: '00000000008',
    fechaDeApertura: '2026-08-04',
    deudaMateriaDeCobranza: '9412.15',
    costas: '96.00',
    deudaAlDia: '2026-09-06',
  },
  actuaciones: [
    { numero: '1', titulo: 'RESOLUCION DE EJECUCION COACTIVA', fecha: '2026-08-04', medida: null },
  ],
} as unknown as ProcesoDelExpediente;

/** Lo que `coa-cost` recibe: su liquidacion y la prescripcion del mismo tributo. */
const COSTAS = {
  liquidacion: {
    expedCoact: '2026-0418',
    tributo: 'PREDIAL',
    totalS: '96.00',
    fecha: '2026-09-06',
    costas: [
      {
        acto: 'REC1',
        descripcion: 'Resolucion de ejecucion coactiva',
        montoS: '18.00',
        arancelFuente: 'ARANCEL_COSTA:REC1',
      },
    ],
  },
  prescripcion: { plazo: '4 ANIOS' },
} as unknown as {
  readonly liquidacion: LiquidacionDeCostas;
  readonly prescripcion: PrescripcionDeclarada;
};

/**
 * La respuesta con que se ejercita cada conector. **Una por conector, y sin excepcion.**
 *
 * Es lo que hace total la guarda de mas abajo: un conector nuevo sin muestra no se salta la
 * comprobacion en silencio, sale rojo pidiendola.
 */
/**
 * Las dos paginas que Licencias pide, con la forma que `formas-de-la-api.json` publica (#168).
 *
 * Llegan aqui al mezclarse #170, que anadio la guarda de «cada conector tiene su muestra». Sin
 * ellas esa guarda salia roja nombrando las dos hojas: es el modo de fallo que fue escrita para
 * cazar —un registro que crece y una comprobacion que se calla sobre lo que no reconoce—, y lo
 * cazo en la primera mezcla que lo puso a prueba.
 */
const CIIU = {
  contenido: [
    {
      codigo: 'A-0111-01',
      descripcion: 'Cultivo de cereales',
      seccion: 'Agricultura',
      riesgoItse: 'Bajo',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 1842,
  totalPaginas: 93,
  hayMas: true,
};

const PADRON = {
  contenido: [
    {
      nroLicencia: 'LF-2026-0001',
      contribuyente: 'Comercial del Norte S.A.C.',
      denominacionComercial: 'Bodega El Sol',
      giros: [{ codigo: 'G-5211-01', descripcion: 'Venta al por menor', principal: true, activo: true }],
      estado: 'VIGENTE',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 1,
  totalPaginas: 1,
  hayMas: false,
};

const RECAUDACION_MEDIDA: IndicadorDeRecaudacion = {
  ejercicio: 2026,
  fechaCalculo: '2026-09-16',
  calculadoEn: '2026-09-16T10:00:00Z',
  cargado: { importe: '23725394.80', actualizadoA: '2026-09-16' },
  kpis: [
    {
      label: 'Recaudado 2026',
      value: 'S/ 18,424,251.20',
      note: '',
      importe: { importe: '18424251.20', actualizadoA: '2026-09-16' },
    },
    { label: 'Avance de cobranza', value: '77 %', note: '', importe: null },
  ],
  paneles: [
    {
      title: 'Recaudacion por tributo',
      note: '',
      rows: [
        {
          label: 'Impuesto predial',
          sub: '',
          value: 'S/ 8,420,118.40',
          pct: 89,
          avanceConocido: true,
          importe: { importe: '8420118.40', actualizadoA: '2026-09-16' },
          cargado: { importe: '9418204.60', actualizadoA: '2026-09-16' },
          pendiente: { importe: '998086.20', actualizadoA: '2026-09-16' },
        },
      ],
    },
  ],
};

const PARADO_MEDIDO: TrabajoParado = {
  ejercicio: 2026,
  fechaCalculo: '2026-09-16',
  calculadoEn: '2026-09-16T10:00:00Z',
  frentes: [
    {
      frente: 'TRANSITO',
      modulo: 'Transito',
      queEstaParado: 'papeletas sin resolucion de multa emitida',
      porQueCuestaDinero: 'sin emitir no se pueden notificar ni cobrar, y prescriben',
      cuantos: 1842,
      importe: null,
    },
  ],
};

/**
 * La pagina de la bitacora, con los DOCE campos que el contrato declara (#181).
 *
 * Los doce y no los cuatro que el conector lee: una muestra recortada a lo que se usa haria pasar
 * en verde un conector que leyera un campo con otro nombre —el sintoma mudo de C-1, un
 * `undefined` donde va una celda—. `origenEquipo`, `origenIp`, `datosAnteriores` y `datosNuevos`
 * estan aqui **porque llegan**, y el conector tiene que seguir sin ponerlos en ninguna celda.
 */
const BITACORA: Paginado<MovimientoDeLaBitacora> = {
  contenido: [
    {
      id: 41184,
      ejercicio: 2026,
      tabla: 'recibo',
      clave: '0003-0041184',
      operacion: 'ANULACION',
      usuario: 'jcardenas',
      origenEquipo: 'PC-CAJA-02',
      origenIp: '10.0.4.12',
      fecha: '2026-08-13T14:41:12Z',
      observacion: 'Anulado por duplicado a pedido del contribuyente',
      datosAnteriores: '{"estado":"VIGENTE"}',
      datosNuevos: '{"estado":"ANULADO"}',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 84182,
  totalPaginas: 4210,
  hayMas: true,
};

/**
 * Lo que `tra-pap` recibe: el expediente de una papeleta con sus actos (#180).
 *
 * La forma sale de `docs/50-api/formas-de-la-api.json`; el detalle columna a columna lo prueba
 * `conectores/transito.test.tsx`.
 */
const EXPEDIENTE_DE_PAPELETA: ExpedienteDeLaPapeleta = {
  papeleta: '0041182',
  familia: 'TRANSITO',
  estado: 'NOTIFICADA',
  descargos: [],
  actos: [
    {
      clase: 'RESOLUCION_GERENCIA',
      tipo: 'SANCIONADORA',
      numero: 'RG-2026-0884',
      fecha: '2026-07-24',
      documentoId: 41,
      observacion: 'Emitida por el area de transito',
      estado: 'SIN_DILIGENCIAR',
      acuses: [],
    },
  ],
};

/** Una fila del deposito, con los once campos que el contrato declara. */
function internado(campos: Partial<InternamientoEnDeposito> = {}): InternamientoEnDeposito {
  return {
    id: 1,
    placa: 'T2G-418',
    clase: 'AUTOMOVIL',
    papeleta: '0041182',
    deposito: 'DEPOSITO MUNICIPAL 1',
    fechaDeIngreso: '2026-07-18',
    fechaDeSalida: null,
    dias: 54,
    calculadoA: '2026-09-10',
    estado: 'EN_DEPOSITO',
    // El CONCEPTO del TUPA, no una tarifa. Ver `conectores/transito.ts`.
    tasaDeCustodia: 'TUPA-2.14 CUSTODIA DIARIA',
    acta: 'ACTA-2026-0311',
    ...campos,
  };
}

/** Una pagina del deposito, con el envoltorio entero. */
function paginaDeDeposito(
  contenido: readonly InternamientoEnDeposito[],
  totalElementos: number,
): Paginado<InternamientoEnDeposito> {
  return {
    contenido,
    pagina: 0,
    tamano: 20,
    totalElementos,
    totalPaginas: Math.ceil(totalElementos / 20),
    hayMas: totalElementos > contenido.length,
  };
}

/** Lo que `tra-veh` recibe: la ficha, el deposito entero y los internamientos de su placa. */
const LO_DE_TRA_VEH: readonly [
  VehiculoServido,
  Paginado<InternamientoEnDeposito>,
  Paginado<InternamientoEnDeposito>,
] = [
  {
    id: 7,
    placa: 'T2G-418',
    contribuyenteId: 25673,
    marca: 'TOYOTA',
    modelo: 'YARIS',
    categoria: 'M1',
    anioFabricacion: 2014,
    anioInscripcion: 2015,
    numeroMotor: '2NZ-1188412',
    numeroSerie: 'JTDBT923771118841',
    estado: 'ACTIVO',
    historialDePlacas: [],
  },
  paginaDeDeposito([internado()], 188),
  paginaDeDeposito([internado()], 1),
];

/**
 * Lo que `tra-panel` recibe: el resumen del ejercicio en curso, agrupado por ano (#184).
 *
 * **Una sola linea, y no es casualidad**: se pide `?agrupadoPor=ANO` sin rango, o sea del 1 de
 * enero al 31 de diciembre, y un ano natural agrupado por ano da exactamente un grupo. El conector
 * lo comprueba; el caso de varias lineas vive en `conectores/transito.test.tsx`.
 */
const RESUMEN_DE_PAPELETAS: ResumenDePapeletas = {
  agrupadoPor: 'ANO',
  desde: '2026-01-01',
  hasta: '2026-12-31',
  papeletas: 8412,
  importeTotal: '1542880.00',
  actualizadoA: '2026-09-17',
  lineas: [
    {
      clave: '2026',
      descripcion: null,
      ano: 2026,
      cantidad: 8412,
      importe: '1542880.00',
      pagadas: 2118,
      importeDeLasPagadas: '388440.00',
      pendientes: 6294,
      importeDeLasPendientes: '1154440.00',
      enCoactiva: 388,
      importeEnCoactiva: '71148.00',
      conResolucionNotificada: 5884,
      conResolucionDeMulta: 388,
      actualizadoA: '2026-09-17',
    },
  ],
};

/**
 * Lo que `territorio` recibe: la ultima determinacion guardada de un contribuyente (#207).
 *
 * Recortada a la forma del contrato —`docs/50-api/formas-de-la-api.json`, `GET
 * /rentas/predial/determinaciones`—. Traia **veinte campos y la pantalla no pintaba ninguno**
 * hasta #245, que le da su bloque a la memoria del calculo.
 *
 * <h2>La muestra CUADRA, y tres de sus cifras estan puestas para separar lo que se confunde</h2>
 *
 * Una muestra uniforme no distingue nada. Esta se derivo a mano con la escala de 2026 —UIT 5 350,
 * tramos a 15 y 60 UIT— sobre una base de 400 000,75, y trae **tres** cosas que ninguna muestra
 * redonda tendria:
 *
 *   · **Los TRES tramos, y el tercero SIN TOPE** (`limiteSuperior: null`). Con una lista vacia
 *     —como estaba— o con tramos todos acotados, la celda que dice «Sin tope» no se ejerce nunca,
 *     y el conector podria escribir cualquier cosa ahi sin que nada se enterara.
 *   · **Un aporte que `formatearImporte` NO admite**: 79 000,75 x 1 % = `790.00750000`, con
 *     cuatro decimales significativos. Es el que prueba que `formatearImporteSinRedondear` no es
 *     decoracion — con aportes redondos, los dos formateadores darian lo mismo.
 *   · **Y por eso la suma de los aportes NO es el impuesto**: 160,50 + 1 444,50 + 790,0075 =
 *     2 395,0075, y `impuestoInsoluto` es **2 395,01**. Un centimo, que es exactamente lo que
 *     `AporteDeTramo` avisa por escrito (ADR-0018). Con una muestra que cuadrara al centimo,
 *     sumar los tramos aqui para «adelantar» el insoluto pasaria en verde.
 *
 * `valuoExonerado` es **12 200,00 y no cero** por el mismo motivo: con cero no se distinguiria de
 * `valuoTotal` menos `valuoAfecto`, ni se veria que «Monto deducido» sigue sin rellenarse con el.
 */
const DETERMINACION_GUARDADA: DeterminacionGuardada = {
  id: 9014,
  ejercicio: '2026',
  codContribuyente: '00000000008',
  sujeto: 'MEDINA SILVA, RUFINA',
  conjuntoId: 3,
  conjunto: '2026 v1',
  estado: 'VIGENTE',
  origen: 'INDIVIDUAL',
  predios: [],
  valuoTotal: '412200.75',
  valuoExonerado: '12200.00',
  valuoAfecto: '400000.75',
  baseImponible: '400000.75',
  uit: '5350.00',
  tramos: [
    {
      orden: 1,
      limiteSuperior: '80250.00',
      alicuota: '0.2000',
      porcionGravada: '80250.00',
      aporte: '160.50000000',
    },
    {
      orden: 2,
      limiteSuperior: '321000.00',
      alicuota: '0.6000',
      porcionGravada: '240750.00',
      aporte: '1444.50000000',
    },
    // El ultimo, **sin tope**: `limiteSuperior` nulo. Ver el javadoc.
    {
      orden: 3,
      limiteSuperior: null,
      alicuota: '1.0000',
      porcionGravada: '79000.75',
      aporte: '790.00750000',
    },
  ],
  minimoImponible: '32.10',
  impuestoInsoluto: '2395.01',
  derechoDeEmision: '4.50',
  totalAPagar: '2399.51',
  // Al contado y con UNA cuota, no cuatro: si el montaje trajera la trimestral, un conector que
  // dibujara siempre cuatro filas pasaria en verde igual (#234).
  modalidad: 'CONTADO',
  cuotas: [{ numero: 1, vencimiento: '2026-02-27', importe: '2395.01' }],
  reglasAplicadas: ['RT-002'],
};

/**
 * Y la MISMA lectura de una determinacion **anterior a `V21`**: sin modalidad y sin cuotas (#234).
 *
 * Con un solo montaje —el de arriba— «la hoja no dibuja el cronograma porque no esta conectada» y
 * «no lo dibuja porque la fila no dice su modalidad» serian indistinguibles. Son dos cosas, y la
 * segunda se seguira diciendo cuando #252 conecte la tabla.
 */
const DETERMINACION_SIN_MODALIDAD: DeterminacionGuardada = {
  ...DETERMINACION_GUARDADA,
  modalidad: null,
  cuotas: [],
};

/**
 * Y la MISMA determinacion emitida al **fraccionado del articulo 15 b)** (#252).
 *
 * <h2>Las cuatro cifras estan puestas para separar lo que se confunde, y ninguna es redonda</h2>
 *
 * Una muestra de cuatro cuotas iguales, con fechas iguales o con importes que sumaran el total, no
 * distinguiria una implementacion buena de una mala. Esta trae **cuatro** contrastes:
 *
 *   · **Cuatro cuotas, y la del contado es una**: con un solo montaje, un conector que dibujara
 *     siempre cuatro filas —o siempre una— pasaria en verde. Los dos montajes se reparten con el
 *     mismo codigo y se comprueban por separado.
 *   · **Las cuatro fechas son distintas**, y son las del conjunto sellado —`PREDIAL_VENCIMIENTO`
 *     «1».. «4» de `PredialControllerTest`, no inventadas aqui—. Con las cuatro iguales, un
 *     conector que tomara el vencimiento de la primera para todas las filas no se veria.
 *   · **El centimo lo lleva la ULTIMA cuota, no la primera**: 2 395,01 / 4 = 598,7525, que
 *     redondeado en `PuntoDeRedondeo.CUOTA` —dos decimales, HALF_UP, del conjunto sellado— da
 *     598,75, y `CronogramaDelPredial` le da el resto a la ultima: 598,76. **El artboard dibuja lo
 *     contrario** —«por eso la cuota 1 es mayor»—, asi que una muestra copiada de el habria
 *     escondido esto en vez de ensenarlo (ver el issue que sale de aqui).
 *   · **Y las cuotas suman el INSOLUTO, no el total a pagar**: 598,75 x 3 + 598,76 = 2 395,01, y
 *     `totalAPagar` es 2 399,51 — el derecho de emision, 4,50, **no** se reparte. Con una muestra
 *     que sumara el total, sumar las cuatro cuotas para «adelantar» el total a pagar pasaria en
 *     verde, que es exactamente la tentacion que la regla de `conectores.ts` prohibe.
 */
const DETERMINACION_TRIMESTRAL: DeterminacionGuardada = {
  ...DETERMINACION_GUARDADA,
  modalidad: 'TRIMESTRAL',
  cuotas: [
    { numero: 1, vencimiento: '2026-02-27', importe: '598.75' },
    { numero: 2, vencimiento: '2026-05-29', importe: '598.75' },
    { numero: 3, vencimiento: '2026-08-31', importe: '598.75' },
    // La ultima se lleva el resto (ADR-0018, y el javadoc de `CronogramaDelPredial`).
    { numero: 4, vencimiento: '2026-11-30', importe: '598.76' },
  ],
};

/**
 * Lo que `val-tip` recibe: la bitacora de declaraciones de prescripcion, con su reloj (#230).
 *
 * **Los quince campos que la operacion publica**, y no los cuatro que el conector lee, por lo mismo
 * que `BITACORA`: una muestra recortada dejaria pasar en verde un conector que leyera un campo con
 * otro nombre.
 *
 * Dos declaraciones y no una: la tabla es una fila por **ejercicio**, y con una sola declaracion no
 * se veria que las filas se aplanan de varias. La primera trae los dos estados de la insignia
 * —prescrito y vigente—; la segunda, un `contribuyente` nulo, que es la fila que hay que revisar.
 */
const BITACORA_DE_PRESCRIPCIONES: Paginado<PrescripcionDeclarada> = {
  contenido: [
    {
      id: 41,
      codContribuyente: 'PR-0001',
      contribuyente: 'CHAVEZ IPANAQUE, MARIA',
      tributo: 'PREDIAL',
      ejercicioDesde: 2021,
      ejercicioHasta: 2022,
      fechaDePresentacion: '2026-03-02',
      plazoAplicable: 'DECLARACION_PRESENTADA',
      plazo: '4 ANIOS',
      resultado: 'PROCEDE_EN_PARTE',
      nDeResolucion: 'RES-0041-2026',
      ejerciciosPrescritos: [2021],
      ejercicios: [
        { ejercicio: 2021, prescribeEl: '2025-12-31', prescrita: true },
        { ejercicio: 2022, prescribeEl: '2026-12-31', prescrita: false },
      ],
      usuario: 'jperez',
      observacion: 'Solicitud del obligado',
    },
    {
      id: 42,
      codContribuyente: 'PR-0002',
      contribuyente: null,
      tributo: 'ARBITRIO',
      ejercicioDesde: 2020,
      ejercicioHasta: 2020,
      fechaDePresentacion: '2026-04-18',
      plazoAplicable: 'SIN_DECLARACION',
      plazo: '6 ANIOS',
      resultado: 'PROCEDE',
      nDeResolucion: null,
      ejerciciosPrescritos: [2020],
      ejercicios: [{ ejercicio: 2020, prescribeEl: '2026-12-31', prescrita: true }],
      usuario: 'jperez',
      observacion: 'De oficio',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 48,
  totalPaginas: 3,
  hayMas: true,
};

const MUESTRAS: Readonly<Partial<Record<ClaveDeHoja, unknown>>> = {
  panel: CORRIDA,
  territorio: DETERMINACION_GUARDADA,
  'coa-panel': RESUMEN_DE_CARTERA,
  // `coa-exp` recibe el proceso **y lo que se pudo saber de sus costas** desde #200: son dos
  // operaciones que se cruzan por `actoId`, y la del cruce puede fallar sin tumbar la tabla.
  'coa-exp': { proceso: PROCESO, costas: { seSupo: true, porActo: new Map([[11, '18.00']]) } },
  'coa-cost': COSTAS,
  'aut-cat': CIIU,
  'aut-tram': PADRON,
  'ini-panel': [RECAUDACION_MEDIDA, CORRIDA],
  'ini-flujo': RECAUDACION_MEDIDA,
  'ini-parado': PARADO_MEDIDO,
  'con-panel': [FICHA, SIN_CAMPANIA],
  'con-doc': CONSTANCIA_NEGADA,
  'seg-aud': BITACORA,
  // La de `fis-panel` es la **segunda** respuesta —el embudo—, que es lo que `repartir` recibe
  // (#215, #196): la relacion de programas solo sirve para saber de que programa es.
  'fis-panel': EMBUDO,
  // Las tres de Fiscalizacion (#179). La de `fis-prog` es la **segunda** respuesta —la muestra—,
  // porque es la que reparte: la relacion de programas solo aporta el `{id}` con que se pide.
  'fis-prog': MUESTRA,
  'fis-actas': ACTA_CON_USO,
  'fis-res': RESOLUCION_SIN_CIFRAS,
  'tra-panel': RESUMEN_DE_PAPELETAS,
  'tra-pap': EXPEDIENTE_DE_PAPELETA,
  'tra-veh': LO_DE_TRA_VEH,
  'val-tip': BITACORA_DE_PRESCRIPCIONES,
};

/**
 * Los campos de solo lectura de una pantalla, por su coordenada.
 *
 * Por `bloquesConSuIndice` y no filtrando (#288): el indice de un bloque es lo que empareja sus
 * datos, asi que renumerar despues de saltarse una pieza del consumidor los mandaria a otro sitio.
 */
function soloLecturaDe(clave: ClaveDeHoja): readonly string[] {
  return bloquesConSuIndice(PANTALLAS[clave]).flatMap(([bloque, b]) =>
    bloque.campos.flatMap((campo, c) => (campo.tipo.startsWith('r') ? [coordenada(b, c)] : [])),
  );
}

describe('los conectores', () => {
  it('EL CENTINELA: estan los veintiuno que estan, y no cero ni cuarenta', () => {
    // Cero dejaria todo lo de abajo sin sujeto. Cuarenta significaria que alguien conecto
    // pantallas cuyas operaciones no publican lo que ensenan, que es lo que este archivo evita.
    // La lista se escribe a mano y crece de una en una: conectar una pantalla es una decision, y
    // una decision se revisa leyendo su diff. Las dos de licencias llegan con #168, y su medida
    // —que publica cada operacion y que no— esta en `conectores/licencias.ts`. Las dos de
    // Consultas llegan con #169, y son las primeras que EXIGEN SUJETO: sin un codigo de
    // contribuyente en la ruta no piden nada, y la pantalla lo dice en vez de pedir el padron.
    // `seg-aud` llega con #181, y es la primera que EXIGE EJERCICIO: su obligatorio no va en la
    // ruta y sale de la sesion, que es la tercera forma. Ver `conectores/seguridad.ts`.
    //
    // Las TRES de Fiscalizacion llegan con #179, y son las primeras **de tabla en bloque**: dos de
    // ellas no llenan ni un `valores`, porque no tienen un solo campo de solo lectura. `fis-res` es
    // la segunda hoja que exige sujeto, y ahi no habia alternativa: no existe ninguna operacion
    // que publique la relacion de resoluciones, asi que no hay «la primera» que tomar.
    //
    // Las dos de Transito llegan con #180, y `tra-veh` es la primera cuyo sujeto es una PLACA y
    // viaja en la RUTA de la operacion (`/rentas/vehiculos/{placa}`) y no en su cadena de
    // consulta; su medida —que publica cada operacion y que no— esta en `conectores/transito.ts`.
    //
    // `fis-panel` llega con #215, y con ella Fiscalizacion queda entera: era la unica de las cuatro
    // que #179 no conecto, y no por falta de tiempo —no habia nada que pedirle, porque lo unico
    // que declaraba era `estado-cuenta`, que publica la deuda de UN contribuyente y ni una de sus
    // cuatro cifras—. #196 publico el embudo, y es la primera hoja que dice **de cuando son sus
    // cifras** por el canal de `Reparto.aLaFecha` (regla 9).
    //
    // `tra-panel` llega con #184, sobre una ruta que YA estaba publicada y que no consumia nadie
    // —una de las diez de `/transito/reportes/`—. Es la primera hoja cuyo reparto depende de la
    // FORMA de la respuesta y no solo de sus campos: pide `?agrupadoPor=ANO` sin rango, o sea el
    // ejercicio en curso, y de ahi sale UNA linea; si llegaran mas, dos de sus campos dicen «no
    // publicado» en vez de leer la primera. Su medida esta en `conectores/transito.ts`.
    //
    // `territorio` llega con #237 sobre la lectura que #207 publico a proposito **sin conectar la
    // hoja**, y es la primera que exige SUJETO y EJERCICIO a la vez. Llego sin pintar ni una celda
    // —su unico campo de solo lectura, «Monto deducido», no lo publica nadie— y lo que compro
    // entonces fueron sus TRES ausencias distintas. **Desde #245 pinta la memoria del calculo**,
    // diez campos y la tabla de los tramos del articulo 13: lo que faltaba no era backend sino
    // sitio en el artboard, y su propia instruccion ya lo prometia. Ver `conectores.ts`.
    //
    // `val-tip` llega con #230, y es la primera que entra **corrigiendo el artboard a la vez**:
    // declaraba `GET /coactiva/prescripcion` desde #170 y aun asi no podia pintarse, porque su
    // tabla dibujaba un agregado por ejercicio y la operacion publica una fila por solicitud. Su
    // medida —que dos de sus cinco columnas el backend se niega a publicar por escrito, y que la
    // tercera si se podia y estaba guardada desde #39— esta en `conectores/valores.ts`.
    expect(Object.keys(CONECTORES).sort()).toEqual(
      [
        'aut-cat', 'aut-tram', 'coa-cost', 'coa-exp', 'coa-panel',
        'con-doc', 'con-panel',
        'fis-actas', 'fis-panel', 'fis-prog', 'fis-res',
        'ini-flujo', 'ini-panel', 'ini-parado', 'panel',
        'seg-aud', 'territorio',
        'tra-panel', 'tra-pap', 'tra-veh',
        'val-tip',
      ].sort(),
    );
  });
  it('y cada uno tiene su muestra: sin ella, la guarda de abajo se lo saltaria', () => {
    // Una comprobacion que recorre un registro y se calla sobre lo que no reconoce deja de ser
    // una comprobacion el dia que alguien anade la quinta hoja.
    const sinMuestra = Object.keys(CONECTORES).filter(
      (clave) => MUESTRAS[clave as ClaveDeHoja] === undefined,
    );
    expect(sinMuestra, 'anade su respuesta a `MUESTRAS`').toEqual([]);
  });

  it('NINGUN campo de una pantalla conectada se queda sin decidir', () => {
    const olvidados: string[] = [];
    for (const [clave, conector] of Object.entries(CONECTORES)) {
      if (conector === undefined) continue;
      const respuesta = MUESTRAS[clave as ClaveDeHoja];
      const reparto = conector.repartir(respuesta as never);
      for (const coord of soloLecturaDe(clave as ClaveDeHoja)) {
        const decidido =
          reparto.valores.has(coord as never) || reparto.noPublicados.has(coord as never);
        if (!decidido) olvidados.push(`  ${clave} · ${coord}`);
      }
    }
    expect(
      olvidados,
      'Hay campos de una pantalla CONECTADA que no salen de ningun sitio ni se declaran «no\n' +
        'publicado». Se dibujarian con el motivo de la pantalla, que en una conectada dice que SI\n' +
        `esta conectada — un hueco mintiendo sobre su propia causa:\n${olvidados.join('\n')}`,
    ).toEqual([]);
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`territorio` — la determinacion guardada, y sus TRES ausencias (#237)', () => {
  const conector = CONECTORES.territorio;
  if (conector === undefined) throw new Error('falta el conector de `territorio`');

  it('exige sujeto Y ejercicio: sin uno de los dos no se pide nada', () => {
    // Sin `codContribuyente` la operacion es 422 —«no se contesta la de cualquiera»— y sin
    // `ejercicio` contesta 200 con la del ano del reloj del BACKEND, que no es el de trabajo de la
    // sesion. La segunda es la peligrosa: contesta bien, con cifras, de otro ejercicio.
    expect(conector.exigeSujeto).toBe(true);
    expect(conector.exigeEjercicio).toBe(true);
  });

  it('manda los DOS parametros, con el codigo codificado, y no uno solo', async () => {
    // Se mide lo que SALE por el cable y no lo que compone `RUTAS`: entre las dos hay un conector,
    // y es justo donde se pierde un argumento. Un codigo con una barra dentro ya rompio esto una
    // vez (#26), y el ejercicio que no viaja no da error: contesta 200, de otro ano.
    const doble = vi.fn<typeof fetch>(() =>
      Promise.resolve(new Response(null, { status: 204 })),
    );
    vi.stubGlobal('fetch', doble);

    const vacio = await conector.pedir({
      senal: new AbortController().signal,
      sujeto: 'A/1',
      ejercicio: 2025,
      enLaRuta: {},
    });

    expect(String(doble.mock.calls[0]?.[0])).toBe(
      '/rentas/api/v1/rentas/predial/determinaciones?codContribuyente=A%2F1&ejercicio=2025',
    );
    // Y el 204 llega como `null` y no revienta en `json()`, que es lo que hacia hasta #237.
    expect(vacio).toBeNull();
    expect(RUTAS.determinacionGuardada('A/1', 2025)).toBe(
      '/rentas/predial/determinaciones?codContribuyente=A%2F1&ejercicio=2025',
    );
  });

  it('LAS DOS AUSENCIAS NO SE CONFUNDEN: 404 no dice lo mismo que 204', () => {
    // Es la mitad de #207 que la pantalla podia tirar en el ultimo paso. El backend las publica
    // distintas porque #546 midio el dano de confundirlas: «ese codigo no existe» se arregla
    // escribiendo otro, y «todavia no se le ha determinado» se arregla determinando.
    expect(conector.noEncontrado).toBe(NO_ESTA_EN_EL_PADRON);
    expect(conector.sinDato).toBe(TODAVIA_SIN_DETERMINAR);
    expect(NO_ESTA_EN_EL_PADRON.enElCampo).not.toBe(TODAVIA_SIN_DETERMINAR.enElCampo);
    expect(NO_ESTA_EN_EL_PADRON.explicacion).not.toBe(TODAVIA_SIN_DETERMINAR.explicacion);
    // Y los tonos tampoco: un codigo que no existe pide corregir la direccion; un ejercicio sin
    // determinar no es ninguna anomalia.
    expect(NO_ESTA_EN_EL_PADRON.tono).toBe('atencion');
    expect(TODAVIA_SIN_DETERMINAR.tono).toBe('info');
    // Ninguna de las dos dice una cifra: no saber no es una afirmacion.
    expect(NO_ESTA_EN_EL_PADRON.enElCampo).not.toMatch(/\d/);
    expect(TODAVIA_SIN_DETERMINAR.enElCampo).not.toMatch(/\d/);
  });

  it('el «Monto deducido» dice «no publicado», y NO se rellena con `valuoExonerado`', () => {
    // Lo mas cercano que llega es la parte exonerada del valuo, que no es el importe que una
    // deduccion resta de la base. Pintar uno por otro daria una cifra al centimo indistinguible de
    // la correcta — y en un beneficio de pensionista esa cifra decide cuanto se cobra.
    //
    // **Y desde #245 esto hay que decirlo de otra forma**: `valuoExonerado` SI esta en la
    // pantalla, en su propio campo de la memoria (`2|1`), asi que «no aparece en ningun valor» ya
    // no vale como prueba y seria ademas falsa. Lo que se afirma es lo que importa: que el hueco
    // del «Monto deducido» sigue siendo un hueco, y que la celda que lleva la parte exonerada del
    // valuo es la que se llama asi.
    const reparto = conector.repartir(DETERMINACION_GUARDADA as never);
    const memoria = PANTALLAS.territorio.bloques[2];

    expect(reparto.noPublicados.get(coordenada(1, 3))).toBe(NO_PUBLICADO);
    expect(reparto.valores.has(coordenada(1, 3))).toBe(false);
    expect(PANTALLAS.territorio.bloques[1]?.campos[3]?.etiqueta).toBe('Monto deducido');
    expect(memoria?.campos[1]?.etiqueta).toBe('Valúo exonerado');
    expect(reparto.valores.get(coordenada(2, 1))).toBe('S/ 12,200.00');
  });

  it('la MEMORIA se pinta entera: diez de diez, y ninguna cifra compuesta aqui (#245)', () => {
    // El bloque que #245 le da a la hoja. Los nueve importes llegan formateados y sin tocar, y el
    // decimo es el nombre del conjunto SELLADO, que es lo que hace reproducible la memoria
    // (ARQ-09 §3): `uit`, `tramos`, `minimoImponible` y `derechoDeEmision` salen del conjunto que
    // ESA determinacion fijo y no del vigente hoy.
    const reparto = conector.repartir(DETERMINACION_GUARDADA as never);
    const memoria = PANTALLAS.territorio.bloques[2];

    expect(memoria?.titulo).toBe('Memoria del cálculo');
    expect(memoria?.campos).toHaveLength(10);
    const pintados = Array.from({ length: 10 }, (_, campo) =>
      reparto.valores.get(coordenada(2, campo)),
    );
    expect(pintados).toEqual([
      'S/ 412,200.75',
      'S/ 12,200.00',
      'S/ 400,000.75',
      'S/ 400,000.75',
      'S/ 5,350.00',
      'S/ 32.10',
      'S/ 2,395.01',
      'S/ 4.50',
      'S/ 2,399.51',
      '2026 v1',
    ]);
    // Ninguno de los diez dice «no publicado»: la operacion los publica todos.
    for (let campo = 0; campo < 10; campo += 1) {
      expect(reparto.noPublicados.has(coordenada(2, campo))).toBe(false);
    }
  });

  it('NO suma los tramos para adelantar el insoluto, y la muestra prueba que no cuadraria', () => {
    // 160,50 + 1 444,50 + 790,0075 = 2 395,0075, y el impuesto es 2 395,01. **Un centimo**, y es
    // el que `AporteDeTramo` avisa por escrito: los aportes corren sin redondear (ADR-0018) y el
    // unico redondeo es el del cierre de la regla. Sumarlos aqui daria una cifra exacta y
    // equivocada al lado de la que manda, que es la que la operacion publica.
    const reparto = conector.repartir(DETERMINACION_GUARDADA as never);
    const sumaDeLosAportes = DETERMINACION_GUARDADA.tramos.reduce(
      (total, tramo) => total + Number(tramo.aporte),
      0,
    );

    expect(sumaDeLosAportes).not.toBe(Number(DETERMINACION_GUARDADA.impuestoInsoluto));
    expect(reparto.valores.get(coordenada(2, 6))).toBe('S/ 2,395.01');
  });

  it('los TRAMOS salen de `tramos`, y el que no tiene tope dice «Sin tope» en vez de una raya', () => {
    // La tabla lleva `clave`, asi que sus filas viajan por `tablas` y sus celdas pueden decir que
    // no hay dato. Aqui hace falta: `limiteSuperior` es **nulo en el ultimo tramo**, y eso no es un
    // hueco del backend sino que ese tramo no tiene tope. Con una cadena —`'—'`— la pantalla
    // diria lo mismo que dice cuando falta un dato.
    const reparto = conector.repartir(DETERMINACION_GUARDADA as never);
    const tabla = reparto.tablas?.get('tramos-del-articulo-13');
    const definicion = PANTALLAS.territorio.bloques[2]?.tabla;

    expect(definicion?.titulo).toBe('Tramos del artículo 13');
    expect(definicion?.clave).toBe('tramos-del-articulo-13');
    expect(definicion?.sinDato?.texto).toBe('Sin tope');
    expect(tabla?.filas).toHaveLength(3);
    expect(tabla?.filas[0]?.celdas).toEqual([
      '1',
      'S/ 80,250.00',
      '0.2000 %',
      'S/ 80,250.00',
      // Sin redondear, y por eso no pasa por `formatearImporte`: reventaria con ocho decimales.
      'S/ 160.50000000',
    ]);
    // El tercero: sin tope, y con el aporte que **ningun** formateador de dos decimales admite.
    expect(tabla?.filas[2]?.celdas[1]).toEqual({ texto: null });
    expect(tabla?.filas[2]?.celdas[4]).toBe('S/ 790.00750000');
    // Cinco celdas por fila, que son las cinco columnas que la definicion declara.
    expect(definicion?.columnas).toHaveLength(5);
    // Sin total publicado: la operacion no pagina tramos, los publica enteros.
    expect(tabla?.totalElementos).toBeUndefined();
  });

  it('el CRONOGRAMA se reparte: una fila por cuota, con lo que la operacion publica (#252)', () => {
    // **Es el bloque 3 desde #245**, que mete la memoria delante, y su tabla lleva `clave` desde
    // #252: sus filas viajan por `tablas`, que es el unico camino cuyas celdas pueden decir que no
    // hay dato — y «Situacion» lo necesita.
    //
    // Las tres columnas que se llenan llegan **tal cual**: el numero que la cuota dice, la fecha
    // que el conjunto sellado fijo y el importe ya redondeado en `PuntoDeRedondeo.CUOTA`. Ninguna
    // se compone aqui, y por eso la muestra trae cuatro fechas distintas: con las cuatro iguales,
    // un conector que repitiera la de la primera cuota pasaria en verde.
    const reparto = conector.repartir(DETERMINACION_TRIMESTRAL as never);
    const cronograma = PANTALLAS.territorio.bloques[3]?.tabla;
    const tabla = reparto.tablas?.get('cronograma');

    expect(cronograma?.titulo).toBe('Cronograma');
    expect(cronograma?.clave).toBe('cronograma');
    expect(cronograma?.columnas).toHaveLength(4);
    // Las filas NO van por el indice del bloque: la tabla lleva clave.
    expect(reparto.filas.has(3)).toBe(false);
    expect(tabla?.filas).toHaveLength(4);
    expect(tabla?.filas.map((fila) => fila.celdas.slice(0, 3))).toEqual([
      ['1', '27/02/2026', 'S/ 598.75'],
      ['2', '29/05/2026', 'S/ 598.75'],
      ['3', '31/08/2026', 'S/ 598.75'],
      // La ultima lleva el centimo del resto, y no la primera: el artboard dibuja lo contrario.
      ['4', '30/11/2026', 'S/ 598.76'],
    ]);
    // Y la del CONTADO es **una**, con la misma linea de codigo: el articulo 15 a) no es cuatro.
    const alContado = conector.repartir(DETERMINACION_GUARDADA as never).tablas?.get('cronograma');
    expect(alContado?.filas).toHaveLength(1);
    expect(alContado?.filas[0]?.celdas.slice(0, 3)).toEqual(['1', '27/02/2026', 'S/ 2,395.01']);
    // Nada que decir de una ventana: la operacion no pagina cuotas, las publica enteras.
    expect(tabla?.totalElementos).toBeUndefined();
  });

  it('el numero de la CUOTA sale de su campo, no de contar filas — y esto se midio', () => {
    // **Esta prueba existe porque la rotura salio VERDE.** Cambiar `String(cuota.numero)` por
    // `String(i + 1)` no rompia nada: en las dos muestras de arriba el numero coincide con la
    // posicion, que es lo que `CronogramaDelPredial` compone **hoy** —`vencimientos.get(i)` con
    // `i + 1`—. Una muestra uniforme en ese eje no separa leer un campo de contar filas.
    //
    // Asi que se ejerce con una respuesta **cuyo orden no es el de los numeros**. No es la que el
    // backend compone hoy y se dice: lo que se afirma es que la celda dice lo que la operacion
    // publica en `numero`, y no la posicion en que llego. El dia que esa lista se pida ordenada
    // por vencimiento, o que una cuota anulada deje un hueco, el indice mentiria sin que nadie lo
    // notara — y la cuota es lo que el contribuyente busca en su cuponera.
    const alReves: DeterminacionGuardada = {
      ...DETERMINACION_TRIMESTRAL,
      cuotas: [...DETERMINACION_TRIMESTRAL.cuotas].reverse(),
    };

    const filas = conector.repartir(alReves as never).tablas?.get('cronograma')?.filas ?? [];

    expect(filas.map((fila) => fila.celdas[0])).toEqual(['4', '3', '2', '1']);
    // Y la clave de React es la misma que la celda: dos filas con la clave «1» se pisarian.
    expect(filas.map((fila) => fila.clave)).toEqual(['4', '3', '2', '1']);
    // Cada fila conserva ademas SU fecha y SU importe: no se reordena nada aqui.
    expect(filas[0]?.celdas.slice(1, 3)).toEqual(['30/11/2026', 'S/ 598.76']);
  });

  it('«SITUACION» no se llena: la celda dice que no hay dato y anuncia por que (#252)', () => {
    // Es la decision del issue, y se toma antes de pintar: la situacion de una cuota es un hecho de
    // CUENTA CORRIENTE —si se pago, cuando y cuanto— y ninguna operacion servida la publica.
    // Deducirla del vencimiento diria «vencida» sobre una cuota que puede estar pagada, o sea
    // afirmar deuda sin haber mirado un solo pago; dejarla en blanco la haria indistinguible de un
    // dato perdido por el camino. Va la celda sin dato, con el motivo dentro (#187).
    const reparto = conector.repartir(DETERMINACION_TRIMESTRAL as never);
    const cronograma = PANTALLAS.territorio.bloques[3]?.tabla;
    const filas = reparto.tablas?.get('cronograma')?.filas ?? [];

    expect(filas).toHaveLength(4);
    for (const fila of filas) {
      const situacion = fila.celdas[3];
      expect(typeof situacion, 'la situacion se colo como cadena').not.toBe('string');
      expect(situacion).toMatchObject({ texto: null });
      expect((situacion as { readonly nota?: string }).nota).toContain('CUENTA CORRIENTE');
    }
    // Y la palabra que se PINTA la declara la tabla, no la celda: sin `sinDato` seria la raya muda
    // del saco, que no distingue «nadie lo publica» de «esto esta roto».
    expect(cronograma?.sinDato?.texto).toBe('—');
    expect(cronograma?.sinDato?.nota).toContain('cuenta corriente');
    // Ninguna celda del cronograma dice un estado: ni deducido del vencimiento ni de ningun pago.
    const dichas = filas.flatMap((fila) =>
      fila.celdas.filter((celda): celda is string => typeof celda === 'string'),
    );
    expect(dichas.filter((celda) => /vencid|pagad|cancelad|al dia|por vencer/i.test(celda))).toEqual(
      [],
    );
  });

  it('NO se suman las cuotas para componer un total, y la muestra prueba que seria falso', () => {
    // La tentacion concreta de este conector: la operacion publica CADA cuota y no su suma. Y aqui
    // la suma seria falsa de dos maneras a la vez — las cuotas reparten el **insoluto** (2 395,01)
    // y no el total a pagar (2 399,51), porque el derecho de emision no se prorratea; y un importe
    // sacado de dividir el total entre cuatro (599,8775) no es ninguno de los cuatro publicados.
    const reparto = conector.repartir(DETERMINACION_TRIMESTRAL as never);
    const tabla = reparto.tablas?.get('cronograma');
    const centimos = DETERMINACION_TRIMESTRAL.cuotas.reduce(
      (total, cuota) => total + Math.round(Number(cuota.importe) * 100),
      0,
    );

    expect(centimos).toBe(Math.round(Number(DETERMINACION_TRIMESTRAL.impuestoInsoluto) * 100));
    expect(centimos).not.toBe(Math.round(Number(DETERMINACION_TRIMESTRAL.totalAPagar) * 100));
    // Ni la suma ni el cociente aparecen en ninguna celda de la tabla.
    const dichas = (tabla?.filas ?? []).flatMap((fila) =>
      fila.celdas.filter((celda): celda is string => typeof celda === 'string'),
    );
    expect(dichas).not.toContain('S/ 2,399.51');
    expect(dichas).not.toContain('S/ 2,395.01');
    expect(dichas).not.toContain('S/ 599.88');
    expect(tabla?.totalElementos).toBeUndefined();
  });

  it('una fila anterior a V21 NO recibe la tabla, y la pantalla dice por que (#234, #252)', () => {
    // La mitad que ninguna conexion arregla. Y **no se entrega `[]`**: eso significaria «la
    // operacion contesto que no hay ninguna cuota», que es una afirmacion; lo que pasa es que
    // aquella fila no dice con que cronograma se emitio. Sin tabla, el interprete dibuja la
    // ausencia y la frase de pantalla trae el motivo exacto.
    const enBlanco = conector.repartir(DETERMINACION_SIN_MODALIDAD as never);

    expect(DETERMINACION_SIN_MODALIDAD.modalidad).toBeNull();
    expect(DETERMINACION_SIN_MODALIDAD.cuotas).toHaveLength(0);
    expect(enBlanco.tablas?.has('cronograma')).toBe(false);
    expect(enBlanco.filas.has(3)).toBe(false);
    // Y no se lleva por delante la otra tabla de la hoja: los tramos siguen ahi.
    expect(enBlanco.tablas?.get('tramos-del-articulo-13')?.filas).toHaveLength(3);
    expect(enBlanco.loQueLaOperacionNoTrae).toBe(SIN_CRONOGRAMA);
    expect(SIN_CRONOGRAMA).toContain('cronograma');
    // La frase ya no manda a arreglar un backend arreglado ni un conector que ya existe (#239).
    expect(SIN_CRONOGRAMA).not.toContain('no lo publica');
    expect(SIN_CRONOGRAMA).not.toContain('conector');
    expect(SIN_CRONOGRAMA).not.toContain('todavia');
  });

  it('y las DOS que si dicen su modalidad no arrastran esa frase: seria mentir sobre lo pintado', () => {
    // El contraste que hace que la prueba de arriba signifique algo. Sin el, una frase escrita
    // siempre pasaria las dos, y la pantalla diria «no consta el cronograma» debajo de la tabla del
    // cronograma.
    expect(DETERMINACION_GUARDADA.modalidad).toBe('CONTADO');
    expect(DETERMINACION_TRIMESTRAL.modalidad).toBe('TRIMESTRAL');
    expect(conector.repartir(DETERMINACION_GUARDADA as never).loQueLaOperacionNoTrae).toBeUndefined();
    expect(
      conector.repartir(DETERMINACION_TRIMESTRAL as never).loQueLaOperacionNoTrae,
    ).toBeUndefined();
  });
});

describe('`panel` — la ultima corrida', () => {
  const conector = CONECTORES.panel;
  if (conector === undefined) throw new Error('falta el conector de `panel`');
  const reparto = conector.repartir(CORRIDA as never);

  it('la fecha y los observados salen de la respuesta', () => {
    expect(reparto.valores.get(coordenada(0, 1))).toBe('28/01/2026 02:14');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('534');
  });

  it('la tabla sale de `etapas`, con sus cinco columnas en orden', () => {
    const filas = reparto.filas.get(0);
    expect(filas).toHaveLength(2);
    expect(filas?.[1]).toEqual(['Generacion de cuponeras', '61350', '—', '534', 'Observado']);
    // Cinco celdas por fila, que son las cinco columnas que la definicion declara.
    expect(PANTALLAS.panel.bloques[0]?.tabla?.columnas).toHaveLength(5);
    expect(filas?.[0]).toHaveLength(5);
  });

  it('NO deduce «cuentas emitidas» de la ultima etapa, aunque el numero coincida', () => {
    // La ultima etapa trae 61 350 registros y el artboard ensena 61 350 cuentas emitidas. **Que
    // coincidan no las hace lo mismo**: una es «cuantas cuponeras se generaron» y la otra «cuantas
    // cuentas quedaron emitidas», y el dia que difieran nadie sabria que el numero era deducido.
    expect(reparto.valores.has(coordenada(0, 2))).toBe(false);
    expect(reparto.noPublicados.get(coordenada(0, 2))).toBe(NO_PUBLICADO);
    // Y por si alguien lo dedujera igualmente: el valor de la etapa no puede aparecer como valor.
    expect([...reparto.valores.values()]).not.toContain('61350');
  });
});

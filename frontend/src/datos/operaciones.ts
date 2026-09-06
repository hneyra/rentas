/**
 * Las operaciones que el proxy contesta, y el cuerpo JSON de cada una.
 *
 * <h2>La forma no se elige aqui</h2>
 *
 * Cada entrada de `OPERACIONES` lleva por clave `«VERBO /ruta»`, y esa clave tiene que existir
 * en `docs/50-api/formas-de-la-api.json` — el archivo que genera `FormasDeLaApiTest` del tipo
 * de retorno de cada controlador. `formas.test.ts` pide cada operacion POR HTTP, a traves del
 * proxy instalado, y compara **campo a campo** lo que sale con lo que ese archivo declara: si
 * aqui se renombra un campo, se anade uno que el backend no publica o se deja de publicar uno
 * que si, la prueba se pone roja nombrando el campo.
 *
 * Eso es todo lo que hace util a este proxy. Una pantalla escrita contra una forma inventada
 * hay que reescribirla el dia de la integracion, y entonces el proxy no habria adelantado
 * trabajo: lo habria duplicado.
 *
 * <h2>Lo que este archivo NO hace (AC8)</h2>
 *
 * No filtra, no ordena, no pagina, no valida y no persiste. Los constructores no reciben ni la
 * cadena de consulta ni el cuerpo de la peticion: **no pueden** mirarlos, que es mas fuerte que
 * no mirarlos. `?uso=Comercio` devuelve exactamente lo mismo que sin el, y dos `POST` iguales
 * devuelven lo mismo la primera vez y la decima.
 *
 * El envoltorio de paginacion se publica porque el backend lo publica —`{ contenido, pagina,
 * tamano, totalElementos, totalPaginas, hayMas }`, `tamano` sin enie—, y siempre con la pagina
 * cero, el conjunto entero y `hayMas` en falso. Fingir el corte por `?pagina=3` seria fingir
 * que existen paginas que nadie ha decidido como se cortan.
 *
 * <h2>De donde salen las cifras</h2>
 *
 * De `prototipo.ts`, que es la captura del artboard, y de `simulados.ts`, que es lo que hubo
 * que inventar y nombra la operacion que lo sustituira. Un valor que no venga de uno de los dos
 * no deberia existir en este archivo.
 *
 * <h2>Los importes son TEXTO, y llevan su fecha</h2>
 *
 * Ningun importe se convierte a numero en ningun punto de este archivo (regla 1, RNF-055): lo
 * unico que se les hace es quitarles el «S/ » y los separadores de millar que el artboard usa
 * para dibujar —`'S/ 1,842.60'` es una cifra formateada para un ojo, `'1842.60'` es la cifra
 * decimal que el backend publica—. Y toda cifra de deuda viaja envuelta en
 * `{ importe, actualizadoA }` (regla 9), con `FECHA_DE_CAPTURA` y no con la de hoy.
 *
 * <h2>Las cuentas del artboard se copian, no se cuadran</h2>
 *
 * Hay dos sitios donde el prototipo no cierra consigo mismo, y se dejan como estan porque
 * corregirlos seria inventar la correccion:
 *
 *   · La determinacion individual da un valuo total de 170,616.75 —el del contribuyente de
 *     `PREDIOS[0]`— y un total a pagar de 591.94, que es el de `PREDIOS[1]`.
 *   · El expediente declara una base vehicular de 61,400.00 y la memoria vehicular calcula
 *     sobre 112,800.00.
 */

import {
  DETERMINACIONES,
  EJERCICIO_DE_CAPTURA,
  EXPEDIENTE,
  FECHA_DE_CAPTURA,
  NODOS,
  PASOS,
  PREDIOS,
  VAL,
  type DeterminacionDelPrototipo,
  type PasoDelExpediente,
  type TablaDelPaso,
} from './prototipo.ts';
import { simulado } from './simulados.ts';

/** Verbos que el contrato usa. */
export type Verbo = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';

/** Una operacion que el proxy contesta. */
export interface Operacion {
  readonly metodo: Verbo;
  /** Ruta bajo `/rentas/api/v1`, con sus parametros entre llaves. */
  readonly ruta: string;
  /**
   * El cuerpo JSON que sirve.
   *
   * **No recibe argumentos, y es el corazon de AC8**: sin la peticion delante no hay manera de
   * filtrar por ella. Que la firma lo impida vale mas que un comentario pidiendo que no se
   * haga.
   */
  readonly cuerpo: () => unknown;
}

/** La clave con que la operacion aparece en `formas-de-la-api.json`. */
export function claveDe(operacion: Operacion): string {
  return `${operacion.metodo} ${operacion.ruta}`;
}

/** Un importe del backend: texto decimal y la fecha a la que esta actualizado (regla 9). */
interface ImporteActualizado {
  readonly importe: string;
  readonly actualizadoA: string;
}

/** El envoltorio de paginacion del backend. `tamano`, sin enie. */
interface Paginado<T> {
  readonly contenido: readonly T[];
  readonly pagina: number;
  readonly tamano: number;
  readonly totalElementos: number;
  readonly totalPaginas: number;
  readonly hayMas: boolean;
}

// ── Lo que el artboard escribe para dibujar, traducido a lo que el backend publica ──────────

/** `'S/ 1,842.60'` → `'1842.60'`. Sin pasar por `Number` en ningun punto (regla 1). */
function decimal(formateado: string): string {
  return formateado.replace(/S\/\s*/g, '').replace(/\s/g, '').replace(/,/g, '');
}

/** `'62,418'` → `62418`. Para cuentas de cosas, jamas para dinero. */
function cuantos(formateado: string): number {
  return Number(formateado.replace(/,/g, ''));
}

/** Un importe con su fecha, desde la cifra formateada del artboard. */
function conSuFecha(formateado: string): ImporteActualizado {
  return { importe: decimal(formateado), actualizadoA: FECHA_DE_CAPTURA };
}

/**
 * El conjunto entero, en la pagina cero.
 *
 * No pagina: sirve todo lo que hay y lo dice —`hayMas` en falso, una sola pagina—. Es la
 * respuesta honesta de quien no sabe como se corta.
 */
function todoEnUnaPagina<T>(contenido: readonly T[]): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    tamano: contenido.length,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  };
}

// ── Acceso a la captura, sin indices magicos ────────────────────────────────────────────────

function pasoDe(id: string): PasoDelExpediente {
  const paso = PASOS.find((p) => p.id === id);
  if (paso === undefined) {
    throw new Error(`El artboard no tiene la seccion «${id}» del expediente.`);
  }
  return paso;
}

function tablaDe(id: string): TablaDelPaso {
  const tabla = pasoDe(id).tabla;
  if (tabla === undefined) {
    throw new Error(`La seccion «${id}» del expediente no lleva tabla.`);
  }
  return tabla;
}

function determinacionDe(titulo: string): DeterminacionDelPrototipo {
  const determinacion = DETERMINACIONES.find((d) => d.titulo === titulo);
  if (determinacion === undefined) {
    throw new Error(`El artboard no tiene la determinacion «${titulo}».`);
  }
  return determinacion;
}

/**
 * El elemento de esa posicion, con el fallo dicho por su nombre.
 *
 * `noUncheckedIndexedAccess` obliga a comprobar todo indice, y es lo correcto: sin el, una
 * columna que el artboard no tiene viaja como `undefined` hasta el JSON y sale por la API
 * como un campo ausente, que es un desajuste de forma disfrazado de dato vacio.
 */
function elemento<T>(lista: readonly T[], posicion: number, donde: string): T {
  const valor = lista[posicion];
  if (valor === undefined) {
    throw new Error(`${donde}: no hay elemento ${posicion} en una lista de ${lista.length}.`);
  }
  return valor;
}

/** Una celda de una fila del artboard. */
function celda(fila: readonly string[], columna: number): string {
  return elemento(fila, columna, `La fila [${fila.join(' | ')}]`);
}

/** La fila de una tabla de valores cuyo primer campo es `concepto`. */
function valorNormativo(tabla: number, concepto: string): readonly string[] {
  const cuadro = VAL[tabla];
  if (cuadro === undefined) {
    throw new Error(`El artboard no tiene la tabla de valores ${tabla}.`);
  }
  const fila = cuadro.filas.find((f) => celda(f, 0) === concepto);
  if (fila === undefined) {
    throw new Error(`La tabla «${cuadro.label}» no trae «${concepto}».`);
  }
  return fila;
}

/** El texto del artboard tal cual, con la primera letra en mayuscula. */
function enMayuscula(texto: string): string {
  return texto.charAt(0).toUpperCase() + texto.slice(1);
}

// ── El padron ───────────────────────────────────────────────────────────────────────────────

/**
 * Un contribuyente del padron, con la forma que publica el backend.
 *
 * El tipo de documento, su numero y el tipo de persona salen de `titular` —«DNI 03593174 ·
 * sucesion indivisa»— partiendolo, no inventandolos; y la condicion especial, de `contexto`.
 */
function contribuyentesDelPadron() {
  return PREDIOS.map((contribuyente) => {
    const partes = /^(\S+)\s+(\S+)\s+·\s+(.+)$/.exec(contribuyente.titular);
    if (partes === null) {
      throw new Error(`El titular «${contribuyente.titular}» no tiene la forma del artboard.`);
    }
    const calificacion = /(principal|mediano|pequeño) contribuyente/.exec(contribuyente.contexto);
    return {
      id: cuantos(contribuyente.cod),
      codigo: contribuyente.cod,
      tipoDocumento: celda(partes, 1),
      numeroDocumento: celda(partes, 2),
      tipoPersona: enMayuscula(celda(partes, 3)),
      nombreRazonSocial: contribuyente.titulo,
      // Nulo, y no una cadena vacia: dos de los cinco no traen calificacion en el artboard, y
      // «no se sabe» no es «sin calificacion especial».
      condicionEspecial: calificacion === null ? null : enMayuscula(celda(calificacion, 0)),
      // El padron del artboard no tiene ninguna baja; el campo existe y se dice de donde sale.
      activo: contribuyente.estado !== 'Baja',
    };
  });
}

/** El contribuyente cuyo expediente esta abierto en el artboard: `PREDIOS[0]`. */
function elDelExpediente() {
  const contribuyente = contribuyentesDelPadron()[0];
  if (contribuyente === undefined) {
    throw new Error('El padron del artboard esta vacio.');
  }
  return contribuyente;
}

/** El texto de `EXPEDIENTE`, o el fallo dicho por su nombre. */
function delExpediente(clave: string): string {
  const valor = EXPEDIENTE[clave];
  if (typeof valor !== 'string') {
    throw new Error(`El expediente del artboard no trae «${clave}» como texto.`);
  }
  return valor;
}

/** El domicilio fiscal, compuesto como lo compone la propia tabla de predios del artboard. */
function domicilioFiscal() {
  const tipoVia = delExpediente('tipoVia').split(' — ')[1] ?? delExpediente('tipoVia');
  return {
    id: simulado<number>('domicilioId'),
    tipo: 'FISCAL',
    direccion: `${tipoVia} ${delExpediente('via')} ${delExpediente('numero')}`,
    referencia: `${delExpediente('habUrbana')} — Mz. ${delExpediente('mz')} Lt. ${delExpediente('lt')}`,
    ubigeo: simulado<string>('ubigeo'),
    // El expediente del artboard no fecha el domicilio ni dice de que documento nace. Nulo es
    // lo que hay; una fecha inventada aqui se leeria como una fecha de verdad.
    vigenciaDesde: null,
    vigenciaHasta: null,
    documentoOrigen: null,
  };
}

/** Los dos contactos declarados en el expediente: el telefono y el correo. */
function contactosDelExpediente() {
  const ids = simulado<readonly number[]>('contactoId');
  return [
    { tipo: 'TELEFONO', valor: delExpediente('telefonos') },
    { tipo: 'CORREO', valor: delExpediente('email') },
  ].map((contacto, i) => ({
    id: elemento(ids, i, 'contactoId'),
    tipo: contacto.tipo,
    valor: contacto.valor,
    // Un contacto del propio contribuyente no lleva tercero ni observacion: el artboard los
    // pone en la seccion de identificacion, no aqui.
    nombre: null,
    documento: null,
    observacion: null,
    vigente: true,
  }));
}

// ── Los predios y los beneficios del expediente ─────────────────────────────────────────────

function prediosDelExpediente() {
  const ids = simulado<readonly number[]>('predioId.deLaFicha');
  const tipos = simulado<readonly string[]>('tipoDelPredio');
  const sectores = simulado<readonly string[]>('sectorDelPredio');
  const condiciones = simulado<readonly string[]>('condicionDelPredio');
  return tablaDe('unidades').filas.map((fila, i) => ({
    predioId: elemento(ids, i, 'predioId.deLaFicha'),
    codigoReferenciaCatastral: celda(fila, 0),
    tipo: elemento(tipos, i, 'tipoDelPredio'),
    direccion: celda(fila, 1),
    uso: celda(fila, 2),
    sector: elemento(sectores, i, 'sectorDelPredio'),
    areaTerreno: decimal(celda(fila, 3)),
    porcentajePropiedad: decimal(celda(fila, 4)),
    condicion: elemento(condiciones, i, 'condicionDelPredio'),
  }));
}

function beneficiosDelExpediente() {
  const ids = simulado<readonly number[]>('beneficioId');
  const predios = simulado<readonly number[]>('predioId.deLaFicha');
  const bases = simulado<readonly string[]>('baseLegalDelBeneficio');
  const tributos = simulado<readonly string[]>('tributoDelBeneficio');
  // La deduccion de pensionista en soles no se calcula: se lee de la tabla de valores del
  // ejercicio, que es donde el artboard la publica ya resuelta (50 UIT = S/ 267,500.00).
  const deduccionDePensionista = decimal(celda(valorNormativo(0, 'Deducción de pensionista'), 3));

  return tablaDe('beneficios').filas.map((fila, i) => {
    const deduccion = celda(fila, 4);
    const esPorcentaje = deduccion.includes('%');
    // «2026 — indefinida» y «2025». El ano capturado se normaliza a la fecha con que el
    // backend publica una vigencia; lo que no se inventa es un fin que el artboard no da.
    const anio = celda(celda(fila, 3).split(' '), 0);
    const indefinida = celda(fila, 3).includes('indefinida');
    return {
      id: elemento(ids, i, 'beneficioId'),
      contribuyenteId: elDelExpediente().id,
      // La amnistia no cuelga de un predio: alcanza al interes de toda la deuda del ejercicio.
      predioId: esPorcentaje ? null : elemento(predios, 0, 'predioId.deLaFicha'),
      vehiculoId: null,
      tipo: celda(fila, 1),
      tributo: elemento(tributos, i, 'tributoDelBeneficio'),
      // La clase es el texto del artboard tal cual —«50 UIT», «100 % interes»—: traducirlo a
      // un vocabulario del backend seria elegir uno que el backend no ha publicado.
      clase: deduccion,
      porcentaje: esPorcentaje ? decimal(celda(deduccion.split(' '), 0)) : null,
      monto: esPorcentaje ? null : deduccionDePensionista,
      vigenciaDesde: `${anio}-01-01`,
      vigenciaHasta: indefinida ? null : `${anio}-12-31`,
      baseLegal: elemento(bases, i, 'baseLegalDelBeneficio'),
      documentoOrigen: celda(fila, 2),
    };
  });
}

// ── La deuda ────────────────────────────────────────────────────────────────────────────────

/** `'3 y 4'` → `[3, 4]`; `'1 a 8'` → `[1, 8]`; `'1'` → `[1, 1]`. */
function periodos(cuotas: string): readonly [number, number] {
  const numeros = cuotas.match(/\d+/g) ?? [];
  const desde = Number(celda(numeros, 0));
  const hasta = numeros.length > 1 ? Number(celda(numeros, numeros.length - 1)) : desde;
  return [desde, hasta];
}

function deudaPorConcepto() {
  return tablaDe('cuenta').filas.map((fila) => {
    const [desde, hasta] = periodos(celda(fila, 2));
    return {
      tributo: celda(fila, 1),
      ejercicio: cuantos(celda(fila, 0)),
      // La tabla del artboard agrupa por ano y concepto: no dice de que predio ni de que
      // vehiculo sale cada fila, y elegir uno seria imputar la deuda a una unidad al azar.
      predioId: null,
      vehiculoId: null,
      periodoDesde: desde,
      periodoHasta: hasta,
      fase: celda(fila, 6),
      deuda: {
        insoluto: conSuFecha(celda(fila, 3)),
        reajuste: conSuFecha(simulado<string>('reajusteDeLaDeuda')),
        interes: conSuFecha(celda(fila, 4)),
        gasto: conSuFecha(simulado<string>('gastoDeLaDeuda')),
        total: conSuFecha(celda(fila, 5)),
      },
    };
  });
}

// ── La corrida masiva ───────────────────────────────────────────────────────────────────────

/** Las etapas de la emision anual, tal como las lista el artboard. */
function etapasDeLaCorrida() {
  return determinacionDe('Predial — masivo').filas.map((fila) => ({
    etapa: celda(fila, 0),
    registros: cuantos(celda(fila, 1)),
    // El backend publica la cadena VACIA donde la etapa no mueve dinero, y no un cero: «no se
    // emitio nada» y «esta etapa no emite» no son lo mismo. El artboard escribe ahi un guion.
    monto: celda(fila, 2) === '—' ? '' : decimal(celda(fila, 2)),
    observados: cuantos(celda(fila, 3)),
    estado: celda(fila, 4),
  }));
}

/**
 * Los contribuyentes que quedan fuera de la emision.
 *
 * Es UNO, y la corrida dice 534: el padron del artboard tiene cinco contribuyentes y la corrida
 * cuenta 62,418. La escala no se cuadra —repetir el observado 534 veces seria fabricar 533
 * contribuyentes— y la cifra de la etapa se copia como esta.
 */
function observadosDeLaCorrida() {
  return PREDIOS.filter((contribuyente) => contribuyente.estado === 'Observado').map(
    (contribuyente) => ({
      codContribuyente: contribuyente.cod,
      nombre: contribuyente.titulo,
      motivo: contribuyente.contexto,
    }),
  );
}

/** Cuantos observados dejo la corrida, segun su ultima etapa. */
function observadosQueCuentaLaCorrida(): number {
  const etapas = etapasDeLaCorrida();
  const ultima = etapas[etapas.length - 1];
  if (ultima === undefined) {
    throw new Error('La corrida del artboard no tiene etapas.');
  }
  return ultima.observados;
}

/** El alcance de la corrida, de la propia nota del artboard. */
const ALCANCE_DE_LA_CORRIDA = 'Padrón completo';

// ── La determinacion individual ─────────────────────────────────────────────────────────────

/** La fila de la memoria de calculo cuyo concepto es ese. */
function memoria(titulo: string, concepto: string): readonly string[] {
  const fila = determinacionDe(titulo).filas.find((f) => celda(f, 1) === concepto);
  if (fila === undefined) {
    throw new Error(`La memoria «${titulo}» no trae «${concepto}».`);
  }
  return fila;
}

/** Las filas de tramo de la memoria del predial: las marcadas con «×». */
function filasDeTramo(): readonly (readonly string[])[] {
  return determinacionDe('Predial — individual').filas.filter((f) => celda(f, 0) === '×');
}

/**
 * Los tres tramos aplicados.
 *
 * La alicuota y el limite superior salen de la tabla de valores del ejercicio —`VAL[0]`—, que
 * es donde el artboard los publica; la porcion gravada y el aporte, de la memoria de calculo.
 * Ninguno se multiplica aqui: la escala es una regla tributaria y vive en el backend (regla 6).
 */
function tramosAplicados() {
  return filasDeTramo().map((fila, i) => {
    const valores = valorNormativo(0, `Tramo ${i + 1} del predial`);
    const tope = celda(valores, 3);
    const porcion = /S\/\s*([\d.,]+)/.exec(celda(fila, 2));
    return {
      orden: i + 1,
      // El ultimo tramo no tiene tope, y el backend lo publica nulo.
      limiteSuperior: tope === 'sin tope' ? null : decimal(tope),
      alicuota: decimal(celda(celda(valores, 2).split(' '), 0)),
      porcionGravada: porcion === null ? '0.00' : decimal(celda(porcion, 1)),
      aporte: decimal(celda(fila, 3)),
    };
  });
}

/** Los predios que entran en la base, con lo que el artboard dice de cada uno. */
function prediosDeLaBase() {
  const ids = simulado<readonly number[]>('predioId.deLaFicha');
  return tablaDe('unidades').filas.map((fila, i) => ({
    predioId: elemento(ids, i, 'predioId.deLaFicha'),
    codigoPredial: celda(fila, 0),
    ubicacion: celda(fila, 1),
    uso: celda(fila, 2),
    porcentajePropiedad: decimal(celda(fila, 4)),
    autovaluo: decimal(celda(fila, 5)),
    valuoExonerado: decimal(celda(memoria('Predial — individual', 'Valuo exonerado'), 3)),
    valuoAfecto: decimal(celda(fila, 5)),
    // NULO A PROPOSITO. La base de cada predio es su valuo afecto YA PONDERADO por la cuota de
    // propiedad, y RNF-083 prohibe recomponerla en la pantalla. El artboard publica la suma
    // ponderada (151,406.75) y no el reparto: multiplicar aqui 38,420.00 por el 50 % seria
    // ejecutar en el frontend una regla tributaria que es del backend (reglas 1 y 6).
    baseImponible: null,
    // Cuanto del predio tiene dueno registrado, y si eso llega a 100: dos cifras que el
    // artboard no trae y que el backend calcula sobre todas las cuotas del predio.
    porcentajeRegistradoDelPredio: null,
    titularidadCompleta: null,
  }));
}

function determinacionIndividual() {
  const uit = decimal(celda(valorNormativo(0, 'UIT 2026'), 3));
  const vencimientos = simulado<readonly string[]>('vencimientosDeLasCuotas');
  const total = memoria('Predial — individual', 'Total a pagar');
  const importeDeLaCuota = /S\/\s*([\d.,]+)/.exec(celda(total, 2));
  const contribuyente = contribuyentesDelPadron().find((c) => c.codigo === '00000003541');
  if (contribuyente === undefined) {
    throw new Error('El padron del artboard no trae el contribuyente de la memoria individual.');
  }

  return {
    id: simulado<number>('determinacionId'),
    simulacion: false,
    ejercicio: String(EJERCICIO_DE_CAPTURA),
    codContribuyente: contribuyente.codigo,
    sujeto: contribuyente.nombreRazonSocial,
    conjuntoId: simulado<number>('conjuntoId'),
    conjunto: simulado<string>('conjunto'),
    fechaCalculo: FECHA_DE_CAPTURA,
    predios: prediosDeLaBase(),
    valuoTotal: decimal(celda(memoria('Predial — individual', 'Valuo total del conjunto'), 3)),
    valuoExonerado: decimal(celda(memoria('Predial — individual', 'Valuo exonerado'), 3)),
    valuoAfecto: decimal(celda(memoria('Predial — individual', 'Valuo afecto'), 3)),
    baseImponible: decimal(celda(memoria('Predial — individual', 'Valuo afecto'), 3)),
    uit,
    tramos: tramosAplicados(),
    minimoImponible: decimal(celda(valorNormativo(0, 'Mínimo imponible predial'), 3)),
    impuestoInsoluto: decimal(celda(memoria('Predial — individual', 'Impuesto insoluto anual'), 3)),
    derechoDeEmision: decimal(celda(memoria('Predial — individual', 'Derecho de emisión'), 3)),
    totalAPagar: decimal(celda(total, 3)),
    modalidad: simulado<string>('modalidad'),
    cuotas: vencimientos.map((vencimiento, i) => ({
      numero: i + 1,
      vencimiento,
      importe: importeDeLaCuota === null ? '0.00' : decimal(celda(importeDeLaCuota, 1)),
    })),
    // La nota de la memoria y el rotulo de cada tramo, que es lo que el artboard escribe como
    // justificacion del calculo.
    reglasAplicadas: [
      determinacionDe('Predial — individual').nota,
      ...filasDeTramo().map((fila) => celda(fila, 1)),
    ],
  };
}

// ── Las otras tres determinaciones ──────────────────────────────────────────────────────────

/** Cuantos ejercicios cubre la memoria vehicular, segun el nodo del artboard: «3 ejercicios». */
function ejerciciosVehiculares(): number {
  const nodo = NODOS.find((n) => celda(n, 0) === 'Patrimonio vehicular');
  if (nodo === undefined) {
    throw new Error('El artboard no tiene el nodo de patrimonio vehicular.');
  }
  return cuantos(celda(celda(nodo, 1).split(' '), 0));
}

function calculoVehicular() {
  const ids = simulado<readonly number[]>('vehicularId');
  const base = decimal(celda(memoria('Patrimonio vehicular', 'Base imponible'), 3));
  const impuesto = decimal(celda(memoria('Patrimonio vehicular', 'Impuesto anual'), 3));
  const ejercicios = ejerciciosVehiculares();

  return {
    fechaCalculo: FECHA_DE_CAPTURA,
    conjuntoId: simulado<number>('conjuntoId'),
    conjunto: simulado<string>('conjunto'),
    alicuota: decimal(celda(celda(memoria('Patrimonio vehicular', 'Tasa'), 2).split(' '), 0)),
    minimoImponible: decimal(celda(valorNormativo(0, 'Mínimo imponible vehicular'), 3)),
    determinaciones: Array.from({ length: ejercicios }, (_sinUsar, i) => ({
      id: elemento(ids, i, 'vehicularId'),
      // Los tres ejercicios en que el vehiculo permanece afecto, contando hacia atras desde el
      // del artboard. La memoria es una sola y se repite: el artboard no da tres cuentas.
      ejercicio: String(EJERCICIO_DE_CAPTURA - (ejercicios - 1 - i)),
      vehiculoId: simulado<number>('vehiculoId'),
      placa: simulado<string>('placaDelVehiculo'),
      contribuyenteId: elDelExpediente().id,
      baseImponible: base,
      montoDeterminado: impuesto,
      simulacion: false,
    })),
  };
}

function calculoDeAlcabala() {
  return {
    id: simulado<number>('alcabalaId'),
    ejercicio: String(EJERCICIO_DE_CAPTURA),
    // La memoria de alcabala habla de la minuta EP-2218-2026 y de un autovaluo de 76,840.00,
    // que no es ninguno de los dos predios del expediente: no hay a que predio ni a que
    // adquirente amarrarla sin inventarselo.
    predioId: null,
    contribuyenteId: null,
    baseImponible: decimal(celda(memoria('Alcabala', 'Base imponible'), 3)),
    montoDeterminado: decimal(celda(memoria('Alcabala', 'Alcabala a pagar'), 3)),
  };
}

function calculoDeEspectaculos() {
  // La operacion determina UN espectaculo y el artboard lista tres: se sirve el primero, que es
  // el que su tabla pone arriba. Los otros dos siguen en la captura, sin operacion que los pida.
  const fila = determinacionDe('Espectáculos públicos').filas[0];
  if (fila === undefined) {
    throw new Error('El artboard no tiene ningun espectaculo.');
  }
  return {
    id: simulado<number>('espectaculoId'),
    ejercicio: String(EJERCICIO_DE_CAPTURA),
    // El artboard nombra al organizador —«Producciones del Norte E.I.R.L.»— y no lo identifica.
    organizadorId: null,
    ingresoDeclarado: decimal(celda(fila, 4)),
    montoDeterminado: decimal(celda(fila, 6)),
  };
}

function arbitriosDelEjercicio() {
  const ids = simulado<readonly number[]>('arbitrioId');
  const predios = simulado<readonly number[]>('predioId.deLaFicha');
  return determinacionDe('Arbitrios municipales')
    .filas.filter((fila) => celda(fila, 0) !== 'Total del ejercicio')
    .map((fila, i) => ({
      id: elemento(ids, i, 'arbitrioId'),
      ejercicio: String(EJERCICIO_DE_CAPTURA),
      servicio: celda(fila, 0),
      // El primer periodo: el artboard publica la tasa MENSUAL de cada servicio, y el arbitrio
      // se determina por periodo. Los doce periodos no estan en el artboard.
      periodo: 1,
      contribuyenteId: elDelExpediente().id,
      // Los arbitrios se determinan por predio, no por contribuyente — lo dice el pie de la
      // tabla de valores. Van al primero de la ficha, que es el de casa habitacion.
      predioId: elemento(predios, 0, 'predioId.deLaFicha'),
      monto: decimal(celda(fila, 3)),
      fechaCalculo: FECHA_DE_CAPTURA,
    }));
}

// ── La tabla ────────────────────────────────────────────────────────────────────────────────

/**
 * Las trece operaciones que el proxy contesta.
 *
 * Trece de las 181 que el backend publica, y son las que el artboard `RentasV6` alimenta: no se
 * sirve nada de lo que no haya datos capturados. Una operacion mas es una entrada aqui y su
 * constructor; una operacion que no este se contesta con 404 en `problem+json`, que es lo que
 * el backend contesta cuando la ruta no existe.
 */
export const OPERACIONES: readonly Operacion[] = [
  {
    metodo: 'GET',
    ruta: '/rentas/contribuyentes',
    cuerpo: () => todoEnUnaPagina(contribuyentesDelPadron()),
  },
  {
    metodo: 'GET',
    ruta: '/rentas/contribuyentes/{id}/ficha',
    cuerpo: () => ({
      contribuyente: elDelExpediente(),
      datosPersonales: {
        fechaNacimiento: delExpediente('nacimiento'),
        estadoCivil: delExpediente('estadoCivil'),
        // Viuda: el expediente trae el campo «Cónyuge» vacio.
        conyugeId: null,
      },
      aLaFecha: FECHA_DE_CAPTURA,
      domicilioFiscal: domicilioFiscal(),
      // El expediente del artboard tiene un solo domicilio, el fiscal. Un procesal inventado
      // seria una direccion a la que notificar que nadie ha declarado.
      domicilioProcesal: null,
      historialDeDomicilios: [domicilioFiscal()],
      contactos: contactosDelExpediente(),
      // El artboard no declara responsables de este contribuyente. Vacio no es un hueco de la
      // captura: es lo que el expediente dice.
      responsables: [],
    }),
  },
  {
    metodo: 'GET',
    ruta: '/rentas/predios',
    cuerpo: () => todoEnUnaPagina(prediosDelExpediente()),
  },
  {
    metodo: 'GET',
    ruta: '/rentas/beneficios',
    cuerpo: () => todoEnUnaPagina(beneficiosDelExpediente()),
  },
  {
    metodo: 'GET',
    ruta: '/rentas/arbitrios',
    cuerpo: () => todoEnUnaPagina(arbitriosDelEjercicio()),
  },
  {
    metodo: 'GET',
    ruta: '/consultas/deuda',
    cuerpo: () => todoEnUnaPagina(deudaPorConcepto()),
  },
  {
    metodo: 'GET',
    ruta: '/rentas/predial/corridas/ultima',
    cuerpo: () => ({
      id: simulado<number>('corridaId'),
      ejercicio: String(EJERCICIO_DE_CAPTURA),
      alcance: ALCANCE_DE_LA_CORRIDA,
      // Padron completo: no hay sector, y el backend publica el campo nulo cuando no lo hay.
      sector: null,
      simulacion: false,
      conjunto: simulado<string>('conjunto'),
      fechaCalculo: FECHA_DE_CAPTURA,
      observados: observadosQueCuentaLaCorrida(),
      etapas: etapasDeLaCorrida(),
    }),
  },
  {
    metodo: 'GET',
    ruta: '/rentas/predial/corridas/{corridaId}/observados',
    cuerpo: () => todoEnUnaPagina(observadosDeLaCorrida()),
  },
  {
    metodo: 'POST',
    ruta: '/rentas/predial/calculo-individual',
    cuerpo: determinacionIndividual,
  },
  {
    metodo: 'POST',
    ruta: '/rentas/predial/calculo-masivo',
    cuerpo: () => ({
      ejercicio: String(EJERCICIO_DE_CAPTURA),
      alcance: ALCANCE_DE_LA_CORRIDA,
      simulacion: false,
      conjunto: simulado<string>('conjunto'),
      fechaCalculo: FECHA_DE_CAPTURA,
      etapas: etapasDeLaCorrida(),
      observados: observadosDeLaCorrida(),
    }),
  },
  { metodo: 'POST', ruta: '/rentas/vehicular/calculo', cuerpo: calculoVehicular },
  { metodo: 'POST', ruta: '/rentas/alcabala', cuerpo: calculoDeAlcabala },
  { metodo: 'POST', ruta: '/rentas/espectaculos', cuerpo: calculoDeEspectaculos },
];

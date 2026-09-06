import { solicitar } from '../api/cliente.ts';

/**
 * Lo que las pantallas leen del backend, con la forma que el backend publica.
 *
 * <h2>Por que existe este archivo y no un `solicitar()` suelto en cada pantalla</h2>
 *
 * Porque **la forma se declara una vez**. `docs/50-api/formas-de-la-api.json` dice que publica
 * cada operacion, y `src/datos/formas.test.ts` comprueba campo a campo que el proxy sirve eso
 * mismo; lo que faltaba era que la pantalla lo LEYERA con esos nombres. Un `solicitar<any>` en
 * cada seccion volveria a abrir la puerta que #4 cerro: la pantalla se escribiria contra los
 * campos que alguien recuerde, y el desajuste no aparece hasta que el backend contesta.
 *
 * <h2>Los importes son texto, y ninguno viaja sin su fecha</h2>
 *
 * Regla 1 y regla 9. `ImporteConFecha` es la forma con que el backend publica todo lo que es
 * dinero —`{ importe, actualizadoA }`— y aqui se declara una sola vez para que ninguna pantalla
 * tenga que acordarse. Los que el contrato publica como texto plano —`totalS` de coactiva—
 * llevan su fecha al lado, en `aLaFecha`, y la pantalla los junta al dibujarlos.
 *
 * <h2>Lo que NO hace</h2>
 *
 * No filtra, no ordena y no compone. Filtrar y ordenar es de la pantalla mientras el backend no
 * los admita (el proxy ignora la cadena de consulta a proposito, AC8 de #4), y componer dos
 * respuestas en una es una decision de pantalla que se ve mejor donde se dibuja.
 */

/** Un importe del backend: texto decimal y la fecha a la que esta actualizado. */
export interface ImporteConFecha {
  readonly importe: string;
  readonly actualizadoA: string;
}

/** El envoltorio de paginacion del backend. `tamano`, sin enie. */
export interface Paginado<T> {
  readonly contenido: readonly T[];
  readonly pagina: number;
  readonly tamano: number;
  readonly totalElementos: number;
  readonly totalPaginas: number;
  readonly hayMas: boolean;
}

// ── El padron ───────────────────────────────────────────────────────────────────────────────

/**
 * Un contribuyente del padron, tal como `GET /rentas/contribuyentes` lo publica.
 *
 * **Son ocho campos, y no hay un noveno.** Ni el estado de cobranza ni la deuda estan aqui, que
 * son las dos cosas sobre las que el artboard construye la fila de la lista. No es un olvido de
 * este archivo: es lo que declara `docs/50-api/formas-de-la-api.json`, y lo comprueba
 * `verificaciones/secciones-del-artboard.test.ts` leyendo el propio archivo de formas.
 */
export interface ContribuyenteDelPadron {
  readonly id: number;
  readonly codigo: string;
  readonly tipoDocumento: string;
  readonly numeroDocumento: string;
  readonly tipoPersona: string;
  readonly nombreRazonSocial: string;
  readonly condicionEspecial: string | null;
  readonly activo: boolean;
}

/** Un expediente coactivo abierto, de `GET /coactiva/deudas`. */
export interface DeudaEnCoactiva {
  readonly expediente: string;
  readonly ano: number;
  readonly codContribuyente: string;
  readonly contribuyente: string;
  readonly deudaS: string;
  readonly costasS: string;
  readonly totalS: string;
  readonly aLaFecha: string;
  readonly estado: string;
}

/** Un contribuyente que la emision masiva dejo fuera, con su motivo. */
export interface ObservadoDeLaCorrida {
  readonly codContribuyente: string;
  readonly nombre: string;
  readonly motivo: string;
}

/** La ultima corrida de emision del predial. */
export interface CorridaDelPredial {
  readonly id: number;
  readonly ejercicio: string;
  readonly alcance: string;
  readonly fechaCalculo: string;
  readonly observados: number;
}

// ── El expediente del contribuyente ─────────────────────────────────────────────────────────

/** Un domicilio del contribuyente. */
export interface DomicilioServido {
  readonly id: number;
  readonly tipo: string;
  readonly direccion: string;
  readonly referencia: string;
  readonly ubigeo: string;
}

/** Un contacto declarado: telefono o correo. */
export interface ContactoServido {
  readonly tipo: string;
  readonly valor: string;
  readonly vigente: boolean;
}

/** La ficha del contribuyente, de `GET /rentas/contribuyentes/{id}/ficha`. */
export interface FichaDelContribuyente {
  readonly contribuyente: ContribuyenteDelPadron;
  readonly datosPersonales: {
    readonly fechaNacimiento: string;
    readonly estadoCivil: string;
    readonly conyugeId: number | null;
  };
  readonly aLaFecha: string;
  readonly domicilioFiscal: DomicilioServido | null;
  readonly contactos: readonly ContactoServido[];
}

/** Un predio del contribuyente, de `GET /rentas/predios`. */
export interface PredioServido {
  readonly predioId: number;
  readonly codigoReferenciaCatastral: string;
  readonly tipo: string;
  readonly direccion: string;
  readonly uso: string;
  readonly sector: string;
  readonly areaTerreno: string;
  readonly porcentajePropiedad: string;
  readonly condicion: string;
}

/** Un beneficio del contribuyente, de `GET /rentas/beneficios`. */
export interface BeneficioServido {
  readonly id: number;
  readonly tipo: string;
  readonly tributo: string;
  readonly clase: string;
  readonly porcentaje: string | null;
  readonly monto: string | null;
  readonly vigenciaDesde: string;
  readonly vigenciaHasta: string | null;
  readonly baseLegal: string;
  readonly documentoOrigen: string;
}

/** Las cinco partidas con que el backend publica una deuda. */
export interface PartidasDeLaDeuda {
  readonly insoluto: ImporteConFecha;
  readonly reajuste: ImporteConFecha;
  readonly interes: ImporteConFecha;
  readonly gasto: ImporteConFecha;
  readonly total: ImporteConFecha;
}

/** Una obligacion pendiente, de `GET /consultas/deuda`. */
export interface DeudaPorConcepto {
  readonly tributo: string;
  readonly ejercicio: number;
  readonly periodoDesde: number;
  readonly periodoHasta: number;
  readonly fase: string;
  readonly deuda: PartidasDeLaDeuda;
}

// ── El panel ────────────────────────────────────────────────────────────────────────────────

/** Una tarjeta de cabecera del panel. */
export interface KpiDeRecaudacion {
  readonly label: string;
  readonly value: string;
  readonly note: string;
  readonly importe: ImporteConFecha | null;
}

/** Una fila de un panel de avance. */
export interface FilaDeAvance {
  readonly label: string;
  readonly sub: string;
  readonly value: string;
  readonly pct: number;
  readonly avanceConocido: boolean;
}

/** Un panel de avance, con su titulo y su nota. */
export interface PanelDeAvance {
  readonly title: string;
  readonly note: string;
  readonly rows: readonly FilaDeAvance[];
}

/** `GET /indicadores/recaudacion`. */
export interface IndicadorDeRecaudacion {
  readonly ejercicio: number;
  readonly fechaCalculo: string;
  readonly calculadoEn: string;
  readonly kpis: readonly KpiDeRecaudacion[];
  readonly paneles: readonly PanelDeAvance[];
}

/** Un frente parado, de `GET /indicadores/trabajo-parado`. */
export interface FrenteParado {
  readonly frente: string;
  readonly modulo: string;
  readonly queEstaParado: string;
  readonly porQueCuestaDinero: string;
  readonly cuantos: number;
}

/** `GET /indicadores/trabajo-parado`. */
export interface TrabajoParado {
  readonly ejercicio: number;
  readonly fechaCalculo: string;
  readonly calculadoEn: string;
  readonly frentes: readonly FrenteParado[];
}

/** Un movimiento de la bitacora, de `GET /seguridad/auditoria`. */
export interface MovimientoDeLaBitacora {
  readonly id: number;
  readonly ejercicio: number;
  readonly tabla: string;
  readonly clave: string;
  readonly operacion: string;
  readonly usuario: string;
  readonly fecha: string;
  readonly observacion: string;
}

// ── Las rutas, escritas una vez ─────────────────────────────────────────────────────────────

/**
 * Las rutas que estas dos secciones piden.
 *
 * Escritas aqui y no en cada `solicitar()`: una ruta repetida en dos pantallas se corrige en
 * una sola el dia que cambie, y la otra se queda pidiendo la vieja hasta que alguien abra esa
 * pantalla. Las que llevan parametro son funciones, para que el parametro no se olvide.
 */
export const RUTAS = {
  padron: '/rentas/contribuyentes',
  ficha: (id: number) => `/rentas/contribuyentes/${String(id)}/ficha`,
  predios: '/rentas/predios',
  beneficios: '/rentas/beneficios',
  deuda: '/consultas/deuda',
  coactiva: '/coactiva/deudas',
  ultimaCorrida: '/rentas/predial/corridas/ultima',
  observados: (corridaId: number) => `/rentas/predial/corridas/${String(corridaId)}/observados`,
  recaudacion: '/indicadores/recaudacion',
  trabajoParado: '/indicadores/trabajo-parado',
  bitacora: '/seguridad/auditoria',
} as const;

/** Pide una operacion paginada y devuelve solo su contenido. */
export async function pedirLista<T>(ruta: string, senal?: AbortSignal): Promise<readonly T[]> {
  const pagina = await solicitar<Paginado<T>>(ruta, senal === undefined ? {} : { senal });
  return pagina.contenido;
}

/** Pide una operacion que contesta un objeto. */
export async function pedirUno<T>(ruta: string, senal?: AbortSignal): Promise<T> {
  return solicitar<T>(ruta, senal === undefined ? {} : { senal });
}

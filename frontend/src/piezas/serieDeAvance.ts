import type { DatoConNombre } from '@kamayuk/ui';

/**
 * **El contrato entre el conector de `ini-flujo` y el grafico que lo dibuja** (#288).
 *
 * <h2>Por que la serie viaja por `nombrados` y no por un canal propio</h2>
 *
 * Porque el punto de extension del interprete dice, en su propio javadoc, que una pieza del
 * consumidor **no lleva `ajustes`** —«un dato sin tipo es un contrato que ningun compilador
 * lee»— y que «lee lo suyo de `datos`». `DatosDeLaPantalla` tiene tres canales: `valores` (por
 * coordenada de campo), `filas` (por indice de bloque, celdas de tabla) y `nombrados` (por
 * nombre). Una serie no es un campo ni una fila de la tabla de un bloque, asi que va por nombre.
 *
 * <h2>Y por que el nombre lleva el indice dentro</h2>
 *
 * Porque `DatoConNombre` es `string | boolean | null` y **no admite ni numeros ni listas** —lo
 * declara asi a proposito: «en coma flotante un importe pierde el centimo antes de llegar a la
 * pantalla»—. Una serie de N tributos, entonces, solo cabe aplanada: un nombre por dato. Este
 * archivo es el unico sitio donde ese aplanado se escribe, y las dos puntas —el conector que lo
 * pone y la pieza que lo lee— lo derivan de aqui; escrito dos veces seria un nombre que un dia
 * cambia en una punta y deja la otra dibujando una serie vacia, en verde.
 *
 * <h2>Lo que la serie NO lleva, y por que no se calcula</h2>
 *
 * **Los soles.** El grafico mide `pct`, que es el unico numero que
 * `GET /indicadores/recaudacion` publica COMO numero: emitido, recaudado y saldo llegan como
 * `ImporteConFecha`, o sea texto decimal, y convertirlos a `number` para darle largo a una barra
 * es lo que prohibe la regla 1 y lo que muerde la prohibicion `importe-convertido-a-number`. Es
 * ademas la misma decision que ya tomo `ini-panel`, cuya pieza declarada es un `Progress` «de
 * avance por tributo»: la magnitud de una barra en este modulo es el avance medido, no el importe.
 *
 * Las cifras exactas las dice la tabla «Cuadre por tributo», que por eso no se quita.
 */

/** La clave con la que la definicion de `ini-flujo` nombra al grafico. Ver `PIEZAS_DE_RENTAS`. */
export const GRAFICO_DE_RECAUDACION = 'grafico-de-recaudacion';

/** El prefijo de los nombres de la serie. Un solo sitio donde se escribe. */
const PREFIJO = 'recaudacion';

/** El nombre del tributo de la fila `i`. Se pone **siempre**, haya avance medido o no. */
export const nombreDelTributo = (i: number): string => `${PREFIJO}.${String(i)}.tributo`;

/**
 * El nombre del avance de la fila `i`. **Solo se pone cuando `avanceConocido` es cierto.**
 *
 * Un dato que no llego no se pone —es la regla de `nombrados`—, y aqui eso vale doble: la
 * operacion publica `pct: 0` con `avanceConocido: false` para separar el cero medido del que no
 * se pudo medir, y una barra de largo cero se lee como «no se ha cobrado nada».
 */
export const nombreDelAvance = (i: number): string => `${PREFIJO}.${String(i)}.avance`;

/** Una barra del grafico: el tributo y su avance en tanto por ciento, ya medido por el backend. */
export interface BarraDelAvance {
  readonly tributo: string;
  readonly avance: number;
}

/** Lo que el grafico tiene que dibujar, y lo que se quedo fuera. */
export interface SerieDelAvance {
  readonly barras: readonly BarraDelAvance[];
  /** Cuantos tributos llegaron sin avance medido. Se dicen, no se dibujan al cero. */
  readonly sinMedir: number;
}

/** Un dato con nombre como texto, o `undefined` si no esta o no es texto. */
function texto(
  nombrados: ReadonlyMap<string, DatoConNombre> | undefined,
  nombre: string,
): string | undefined {
  const valor = nombrados?.get(nombre);
  return typeof valor === 'string' && valor !== '' ? valor : undefined;
}

/**
 * La serie, leida de los datos de la pantalla.
 *
 * Se recorre por indice y se para en el primer tributo que no esta: el conector los pone
 * correlativos desde el cero, asi que un hueco es el final de la lista y no un salto.
 */
export function serieDelAvance(
  nombrados: ReadonlyMap<string, DatoConNombre> | undefined,
): SerieDelAvance {
  const barras: BarraDelAvance[] = [];
  let sinMedir = 0;
  for (let i = 0; ; i += 1) {
    const tributo = texto(nombrados, nombreDelTributo(i));
    if (tributo === undefined) break;
    const avance = texto(nombrados, nombreDelAvance(i));
    // `Number` sobre un tanto por ciento, y no sobre un importe: `pct` sale del backend COMO
    // numero y solo pasa por texto porque `DatoConNombre` no admite otra cosa. Un `NaN` —que solo
    // podria venir de un dato corrupto— se trata como «sin medir», nunca como cero.
    const medido = avance === undefined ? Number.NaN : Number(avance);
    if (Number.isFinite(medido)) barras.push({ tributo, avance: medido });
    else sinMedir += 1;
  }
  return { barras, sinMedir };
}

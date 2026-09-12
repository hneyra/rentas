import { join } from 'node:path';

import { RAIZ } from './tokens.ts';

/**
 * Los artboards que este repositorio vendoriza, con su procedencia.
 *
 * <h2>Por que una lista, y por que con procedencia</h2>
 *
 * Porque un artboard que falta no se distingue de uno que nadie tenia que traer. La lista dice
 * cuales se esperan; la procedencia dice de donde se vuelve a sacar el que falte, que es la
 * pregunta que uno se hace justo despues de leer el rojo.
 *
 * Viven en `frontend/diseno/` y viajan en el arbol —no se descargan al verificar— porque una
 * guarda que depende de la red no es una guarda: es una que se salta el dia que la red falla.
 */
export interface Artboard {
  /** El archivo, relativo a `frontend/`. */
  readonly archivo: string;
  /** Que dibuja, en una linea. */
  readonly que: string;
  /** De donde se trae si falta. */
  readonly deDonde: string;
}

/** El proyecto de Claude Design del que salen los dos. */
const PROYECTO = 'SGTM Redesign (c562dcb9-e2d5-4c46-b77d-7897b0f95989)';

export const ARTBOARDS: readonly Artboard[] = [
  {
    archivo: 'diseno/RentasV6.dc.html',
    que: 'El marco V6 y las cuatro secciones que la interfaz tiene hoy. Contra el se comparan los tokens, el arbol y las cifras de las secciones.',
    deDonde: `${PROYECTO}, archivo «RentasV6.dc.html»`,
  },
  {
    archivo: 'diseno/RentasV8.dc.html',
    que: 'El artboard NUEVO: diez modulos, cuarenta submodulos y cuarenta pantallas, con la cabecera al modo de V7 y el modo «Entrega». Es contra el que se reimplanta la interfaz.',
    deDonde: `${PROYECTO}, archivo «RentasV8.dc.html»`,
  },
  {
    archivo: 'diseno/rentas-tokens.css',
    que: 'Los 42 tokens que el artboard V8 usa —38 colores, 2 radios, 2 sombras—. El `.dc.html` los ENLAZA y no los lleva dentro: por si solo trae 12 colores literales, los de su tabla «Tokens a Tailwind». Sin esta hoja no se puede comprobar contra el artboard mas de un cuarto de la paleta.',
    deDonde: `${PROYECTO}, archivo «rentas-tokens.css»`,
  },
  {
    archivo: 'diseno/escudo-catacaos.png',
    que: 'El escudo que la barra global de V8 dibuja. Comprobado byte a byte identico al que sirve `caja` en su `public/`.',
    deDonde: `${PROYECTO}, archivo «escudo-catacaos.png»`,
  },
];

/** La ruta absoluta de un artboard declarado. */
export const rutaDe = (artboard: Artboard): string => join(RAIZ, artboard.archivo);

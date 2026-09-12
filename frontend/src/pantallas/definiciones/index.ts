import type { ClaveDeHoja } from '../arbol.ts';
import type { Pantalla } from '../tipos.ts';
import { INICIO } from './inicio.ts';
import { RENTAS_REGISTRO } from './rentas-registro.ts';
import { FISCALIZACION } from './fiscalizacion.ts';
import { TRANSITO } from './transito.ts';
import { INFRACCIONES_ADMINISTRATIVAS } from './infracciones-administrativas.ts';
import { CONSULTAS } from './consultas.ts';
import { COACTIVA } from './coactiva.ts';
import { AUTORIZACIONES_Y_LICENCIAS } from './autorizaciones-y-licencias.ts';
import { SEGURIDAD } from './seguridad.ts';
import { VALORES } from './valores.ts';

/**
 * **Las cuarenta pantallas de V8**, reunidas (UI-5, #85, AC2).
 *
 * Una por hoja del arbol, repartidas por modulo en los diez archivos de al lado. Aqui solo se
 * juntan, y juntarlas es lo que pone a trabajar al compilador:
 *
 * `satisfies Record<ClaveDeHoja, Pantalla>` —sin `Partial`— exige **las cuarenta**. Una hoja
 * nueva en el arbol sin su pantalla no compila, y una pantalla cuya clave no sea de ninguna hoja,
 * tampoco. «Cero hojas sin pantalla y cero pantallas sin hoja» deja de ser algo que haya que
 * acordarse de comprobar.
 *
 * Que ademas **digan lo que el artboard dice** es otra cosa, y de eso responde la guarda
 * anti-deriva: el compilador cuenta, no lee.
 */
export const PANTALLAS = {
  ...INICIO,
  ...RENTAS_REGISTRO,
  ...FISCALIZACION,
  ...TRANSITO,
  ...INFRACCIONES_ADMINISTRATIVAS,
  ...CONSULTAS,
  ...COACTIVA,
  ...AUTORIZACIONES_Y_LICENCIAS,
  ...SEGURIDAD,
  ...VALORES,
} satisfies Record<ClaveDeHoja, Pantalla>;

/** La pantalla de una hoja. Con `ClaveDeHoja` no hay caso «no existe» que tratar. */
export const pantallaDe = (clave: ClaveDeHoja): Pantalla => PANTALLAS[clave];

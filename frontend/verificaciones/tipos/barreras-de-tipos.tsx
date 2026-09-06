import { formatearImporte } from '../../src/dominio/formato.ts';
import { Importe, Insignia } from '../../src/ds/index.ts';

/**
 * Las barreras que pone el COMPILADOR, y la prueba de que muerden.
 *
 * Este archivo no es una prueba de vitest: es una prueba de `tsc`, y se apoya en
 * una propiedad de `@ts-expect-error` que ninguna asercion tiene —
 * **`@ts-expect-error` falla cuando NO hay error**:
 *
 *     error TS2578: Unused '@ts-expect-error' directive.
 *
 * O sea que cada bloque de aqui abajo dice «esto tiene que no compilar», y si un
 * dia compila, `yarn typecheck` se pone rojo por eso mismo. Una prueba que
 * comprobara lo mismo con un `expect` no podria: para escribirla habria que
 * escribir primero el codigo que no compila, y entonces no compilaria la prueba.
 *
 * Va aparte de `src/` a proposito: aqui vive codigo que **esta mal escrito
 * queriendo**, y no tiene nada que hacer en el arbol que se empaqueta.
 */

/* eslint-disable no-restricted-syntax -- este archivo VIOLA las reglas a proposito; es lo que verifica */

/**
 * AC4, primera barrera: `<Importe>` sin `fechaCalculo` no compila.
 *
 * La segunda barrera es la prohibicion `importe-sin-fecha` de ESLint, con su
 * muestra en `verificaciones/muestras/importe-sin-fecha.tsx`. Las dos hacen
 * falta: esta sobrevive a que alguien apague la regla de ESLint, y aquella
 * sobrevive a que alguien le ponga un valor por omision al tipo.
 */
export const importeSinFecha = (
  // @ts-expect-error — falta `fechaCalculo`, que es obligatoria: no existe «la deuda», existe la deuda a una fecha (regla 9, RNF-075).
  <Importe valor="1842.60" />
);

/** Y no vale ponersela a `undefined` para callar al compilador. */
export const importeConFechaIndefinida = (
  // @ts-expect-error — `undefined` no es una `Fecha`.
  <Importe valor="1842.60" fechaCalculo={undefined} />
);

/**
 * Regla 1: un importe es texto, jamas `number`. En coma flotante `1842.6` ya ha
 * perdido la forma con la que llego, y `0.1 + 0.2` no es `0.30`.
 */
export const importeComoNumero = (
  // @ts-expect-error — `number` no es un `Importe`.
  <Importe valor={1842.6} fechaCalculo="2026-09-06" />
);

/** Lo mismo, una capa mas abajo: el formateador tampoco acepta un `number`. */
export function formatearUnNumero(): string {
  // @ts-expect-error — `formatearImporte` recibe texto decimal, no `number`.
  return formatearImporte(1842.6);
}

/**
 * AC5: una insignia sin texto no compila.
 *
 * Un estado que se comunica solo por color no se comunica a quien no distingue
 * ese color. `children` es obligatorio y no hay variante que pinte solo un punto.
 */
export const insigniaSinTexto = (
  // @ts-expect-error — falta el texto del estado; el color no es el unico canal.
  <Insignia tono="ok" />
);

/** Y el tono es uno de los cuatro del artboard, no una cadena cualquiera. */
export const insigniaConTonoInventado = (
  // @ts-expect-error — «verde» no es un `Tono`.
  <Insignia tono="verde">Vigente</Insignia>
);

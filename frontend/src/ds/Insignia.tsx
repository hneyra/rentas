import type { ReactNode } from 'react';

import type { Tono } from '../dominio/valores.ts';

/**
 * Insignia de estado: «Vigente», «Vencido», «Con deuda».
 *
 * **El texto va siempre dentro, y por eso `children` no es opcional.** Un estado
 * que se comunica solo por color no se comunica a quien no distingue ese color,
 * y en una caja de ventanilla eso no es una minoria teorica: es el 8 % de los
 * hombres. No hay variante que pinte solo un punto, y no la habra — el dia que
 * alguien la escriba, el tipo se lo impide antes de que compile.
 *
 * Que el tipo muerde lo comprueba `verificaciones/tipos/barreras-de-tipos.tsx`,
 * que no es una prueba de vitest sino un archivo con `@ts-expect-error`: si
 * `children` dejara de ser obligatorio, el error esperado no ocurriria y **`tsc`
 * se pondria rojo por eso mismo**.
 */
export interface InsigniaProps {
  readonly tono: Tono;
  /** El estado, escrito. Obligatorio: ver el javadoc del componente. */
  readonly children: ReactNode;
}

export function Insignia({ tono, children }: InsigniaProps) {
  return <span className={`kr-insignia kr-insignia--${tono}`}>{children}</span>;
}

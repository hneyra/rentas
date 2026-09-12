import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import { ARBOL } from '../src/pantallas/arbol.ts';
import { Pantalla } from '../src/pantallas/Pantalla.tsx';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';

/**
 * **Las cuarenta se dibujan** (#88, AC5).
 *
 * <h2>Por que un recorrido y no un recuento</h2>
 *
 * Porque «hay cuarenta definiciones» ya lo comprueba el compilador (#85) y no dice nada de que se
 * puedan pintar. Lo que puede fallar aqui es otra cosa: una definicion con un tipo de campo que
 * el interprete no sabe dibujar, un bloque sin campos ni tabla, una tabla con mas celdas que
 * columnas. Todo eso son cuarenta casos distintos, y el unico modo de saberlo es abrirlas.
 *
 * <h2>Y de cada una se exige algo que SOLO aparece con su definicion puesta</h2>
 *
 * Su instruccion y los titulos de todos sus bloques. Un interprete que devolviera un marco vacio
 * pasaria un «no reventó»; no pasa esto.
 */

const HOJAS = ARBOL.flatMap((modulo) =>
  modulo.hojas.map((hoja) => ({ modulo: modulo.rotulo, hoja })),
);

afterEach(cleanup);

describe('las cuarenta pantallas se dibujan', () => {
  it('EL CENTINELA: hay cuarenta hojas que recorrer', () => {
    // Sin esto, un arbol vacio dejaria el `it.each` de abajo sin casos y el archivo en verde
    // habiendo comprobado cero pantallas.
    expect(HOJAS).toHaveLength(40);
  });

  it.each(HOJAS)('«$hoja.clave» — $hoja.rotulo', ({ modulo, hoja }) => {
    const definicion = pantallaDe(hoja.clave);
    render(<Pantalla definicion={definicion} modulo={modulo} titulo={hoja.rotulo} />);

    // El titulo, que viene del arbol.
    expect(screen.getByRole('heading', { level: 1, name: hoja.rotulo })).toBeTruthy();
    // La instruccion, que viene de la definicion y es distinta en las cuarenta.
    expect(screen.getByText(new RegExp(escapar(definicion.instruccion)))).toBeTruthy();
    // Y TODOS los titulos de bloque: es lo que delata un bloque que no se pinto.
    for (const bloque of definicion.bloques) {
      expect(
        screen.getByRole('heading', { level: 2, name: bloque.titulo }),
        `«${hoja.clave}» no pinto el bloque «${bloque.titulo}»`,
      ).toBeTruthy();
    }
    // Y el pie, que siempre esta.
    expect(screen.getByRole('button', { name: 'Volver' })).toBeTruthy();
  });
});

/** Una instruccion lleva parentesis y puntos: sin escapar, la expresion regular no casa. */
function escapar(texto: string): string {
  return texto.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

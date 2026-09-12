import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeAll, describe, expect, it } from 'vitest';

import { Aplicacion } from '../src/aplicacion.tsx';
import { CATALOGO } from '../src/catalogo.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';

/**
 * **Los cuarenta destinos se abren, en la aplicacion de verdad** (#90, AC8).
 *
 * <h2>Que cambia frente a la version anterior de esta guarda</h2>
 *
 * Hasta #90 esto montaba `<Pantalla>` suelta, con su definicion en la mano. Ahora monta **la
 * aplicacion entera** y llega a cada destino **por su hash**, que es como se llega de verdad.
 *
 * La diferencia no es de estilo: montar la pantalla suelta no comprueba que el destino este en el
 * catalogo, ni que el armazon sepa enrutarlo, ni que la hoja y su definicion sigan emparejadas.
 * Las tres cosas pueden romperse sin tocar una sola pantalla — y las tres dejan la aplicacion sin
 * ese destino mientras las pruebas de pantalla siguen en verde.
 *
 * <h2>Y de cada uno se exige algo que SOLO aparece con su definicion puesta</h2>
 *
 * Su titulo, su instruccion y **todos** los titulos de sus bloques. Un armazon que enrutara bien y
 * dibujara un marco vacio pasaria un «no reventó»; no pasa esto.
 */

const DESTINOS = CATALOGO.flatMap((modulo) =>
  modulo.destinos.map((destino) => ({ modulo: modulo.rotulo, destino })),
);

beforeAll(() => {
  // Lo que jsdom no trae y las piezas del armazon piden. Sus motivos, en `@kamayuk/shell`.
  Element.prototype.scrollIntoView = () => {};
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.releasePointerCapture = () => {};
  globalThis.matchMedia ??= ((consulta: string) => ({
    matches: false,
    media: consulta,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as typeof matchMedia;
});

afterEach(() => {
  cleanup();
  window.location.hash = '';
});

/** Monta la aplicacion con el hash ya puesto, que es como se llega a un destino. */
function abrir(clave: string) {
  window.location.hash = `#/${clave}`;
  render(<Aplicacion />);
}

describe('los cuarenta destinos se recorren, en la aplicacion montada', () => {
  it('EL CENTINELA: el catalogo trae diez modulos y cuarenta destinos', () => {
    // Sin esto, un catalogo vacio dejaria el `it.each` de abajo sin casos y el archivo en verde
    // habiendo recorrido cero pantallas. Es la forma en que este repositorio ya se quedo sin
    // guarda dos veces (#78, #80).
    expect(CATALOGO).toHaveLength(10);
    expect(DESTINOS).toHaveLength(40);
  });

  it.each(DESTINOS)('«$destino.clave» — $destino.rotulo', ({ destino }) => {
    abrir(destino.clave);
    const definicion = pantallaDe(destino.clave as ClaveDeHoja);

    expect(
      screen.getByRole('heading', { level: 1, name: destino.rotulo }),
      `«${destino.clave}» no abrio por su hash`,
    ).toBeTruthy();
    expect(screen.getByText(new RegExp(escapar(definicion.instruccion)))).toBeTruthy();
    for (const bloque of definicion.bloques) {
      expect(
        screen.getByRole('heading', { level: 2, name: bloque.titulo }),
        `«${destino.clave}» no pinto el bloque «${bloque.titulo}»`,
      ).toBeTruthy();
    }
  });

  it('un hash que no es de ningun destino NO abre una pantalla', () => {
    // La otra direccion. Un enrutador que cayera en la primera pantalla ante cualquier hash
    // desconocido pasaria las cuarenta de arriba y ofreceria pantallas que nadie pidio.
    abrir('no-existe-este-destino');
    expect(screen.queryByRole('heading', { level: 2 })).toBeNull();
  });

  it('y el arbol ofrece los diez modulos, con sus rotulos', () => {
    render(<Aplicacion />);
    for (const modulo of CATALOGO) {
      expect(
        screen.getByRole('button', { name: new RegExp(escapar(modulo.rotulo)) }),
        `el carril no ofrece «${modulo.rotulo}»`,
      ).toBeTruthy();
    }
  });

  it('el carril despliega un modulo y ofrece sus cuatro destinos', () => {
    render(<Aplicacion />);
    const primero = CATALOGO[0];
    if (primero === undefined) throw new Error('el catalogo vino vacio');
    fireEvent.click(screen.getByRole('button', { name: new RegExp(escapar(primero.rotulo)) }));
    for (const destino of primero.destinos) {
      expect(screen.getByRole('button', { name: destino.rotulo })).toBeTruthy();
    }
  });
});

/** Una instruccion lleva parentesis y puntos: sin escapar, la expresion regular no casa. */
function escapar(texto: string): string {
  return texto.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

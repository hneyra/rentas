import { IDENTIDADES, MODOS, ProveedorDeTema } from '@kamayuk/ui';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { MandoDeTema } from './MandoDeTema.tsx';

/**
 * **El mando de preferencias: los dos ejes, y que la eleccion se recuerde** (#111, AC1 y AC2).
 *
 * <h2>Lo que esta prueba SI puede decir, y lo que no</h2>
 *
 * Puede decir que los dos atributos se estampan, que «el del sistema» **quita** el atributo en vez
 * de ponerlo en claro, y que lo elegido sobrevive a volver a montar — que es lo que una recarga
 * hace con el arbol de React.
 *
 * **No puede decir que la pantalla cambie de color**: aqui no hay CSS aplicado, ni cascada, ni
 * `prefers-color-scheme`. Eso es exactamente el defecto del que viene este issue —un archivo de
 * temas perfecto y perfectamente inalcanzable, con todas las pruebas en verde—, asi que se mide
 * donde se puede medir: en el navegador, sobre el valor computado
 * (`e2e/los-temas-llegan-al-navegador.spec.ts`).
 */

const PREFIJO = 'kamayuk.prueba';

function montar() {
  return render(
    <ProveedorDeTema configuracion={{ identidadPorOmision: 'institucional', prefijoDeClaves: PREFIJO }}>
      <MandoDeTema abierto alCerrar={() => {}} />
    </ProveedorDeTema>,
  );
}

const raiz = () => document.documentElement;

beforeEach(() => {
  localStorage.clear();
  raiz().removeAttribute('data-tema');
  raiz().removeAttribute('data-modo');
});

afterEach(cleanup);

describe('el mando de preferencias', () => {
  it('EL CENTINELA: ofrece TODO lo que la libreria publica, y no una lista escrita aqui', () => {
    // Sin esto, el dia que entre una cuarta identidad el mando se quedaria corto en silencio: el
    // tema existiria, su CSS viajaria en el paquete, y aqui no habria como elegirlo.
    montar();
    for (const identidad of IDENTIDADES) {
      expect(
        screen.getByRole('radio', { name: ROTULOS[identidad] }),
        `la identidad «${identidad}» no se ofrece`,
      ).toBeTruthy();
    }
    // Los modos son los de la libreria MAS uno que no es un modo: no elegir.
    expect(screen.getAllByRole('radio')).toHaveLength(IDENTIDADES.length + MODOS.length + 1);
    expect(screen.getByRole('radio', { name: 'El del sistema' })).toBeTruthy();
  });

  it('por omision: la identidad del servicio, y NINGUN modo', () => {
    montar();
    expect(raiz().getAttribute('data-tema')).toBe('institucional');
    // Ausente y no «claro»: es lo que devuelve el mando al sistema operativo. Un `claro` de
    // fabrica congelaria en claro a quien tenga el equipo en oscuro.
    expect(raiz().hasAttribute('data-modo')).toBe(false);
    expect(screen.getByRole('radio', { name: 'Institucional' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'El del sistema' })).toBeChecked();
  });

  it('elegir una identidad la estampa y la recuerda', () => {
    montar();
    fireEvent.click(screen.getByRole('radio', { name: 'Sepia' }));
    expect(raiz().getAttribute('data-tema')).toBe('sepia');
    expect(localStorage.getItem(`${PREFIJO}.tema`)).toBe('sepia');
    // Y el otro eje no se mueve: son independientes, que es el motivo de que sean dos atributos.
    expect(raiz().hasAttribute('data-modo')).toBe(false);
  });

  it('elegir un modo lo estampa, y volver a «el del sistema» lo QUITA', () => {
    montar();
    fireEvent.click(screen.getByRole('radio', { name: 'Oscuro' }));
    expect(raiz().getAttribute('data-modo')).toBe('oscuro');
    expect(localStorage.getItem(`${PREFIJO}.modo`)).toBe('oscuro');

    fireEvent.click(screen.getByRole('radio', { name: 'El del sistema' }));
    // Quitado, no puesto en claro: sin esto, quien devuelve el mando al equipo se quedaria
    // clavado en el modo que tuviera puesto al hacerlo.
    expect(raiz().hasAttribute('data-modo')).toBe(false);
    expect(localStorage.getItem(`${PREFIJO}.modo`)).toBeNull();
  });

  it('y lo elegido SOBREVIVE a volver a montar, que es lo que hace una recarga', () => {
    montar();
    fireEvent.click(screen.getByRole('radio', { name: 'Alto contraste' }));
    fireEvent.click(screen.getByRole('radio', { name: 'Claro' }));
    cleanup();
    raiz().removeAttribute('data-tema');
    raiz().removeAttribute('data-modo');

    montar();
    expect(raiz().getAttribute('data-tema')).toBe('alto-contraste');
    expect(raiz().getAttribute('data-modo')).toBe('claro');
    expect(screen.getByRole('radio', { name: 'Alto contraste' })).toBeChecked();
    expect(screen.getByRole('radio', { name: 'Claro' })).toBeChecked();
  });

  it('las dos claves llevan el prefijo del sistema: dos interfaces del mismo origen no se pisan', () => {
    montar();
    fireEvent.click(screen.getByRole('radio', { name: 'Sepia' }));
    // `/rentas/` y `/caja/` se sirven del mismo origen y comparten `localStorage`. Con una clave
    // pelada —`tema`—, cambiar el tema aqui se lo cambiaria a la otra.
    expect(Object.keys(localStorage).every((c) => c.startsWith(`${PREFIJO}.`))).toBe(true);
  });
});

/** Como se lee cada identidad en el mando. Vive aqui para que el centinela no dependa del orden. */
const ROTULOS: Readonly<Record<(typeof IDENTIDADES)[number], string>> = {
  institucional: 'Institucional',
  'alto-contraste': 'Alto contraste',
  sepia: 'Sepia',
};

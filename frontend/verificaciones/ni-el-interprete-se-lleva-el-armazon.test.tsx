import { cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import { ACCESOS_MEDIDOS, MODULOS_MEDIDOS, PERMISOS_MEDIDOS } from '../src/datos/seguridadMedida.ts';
import type { PantallaDeRentasProps } from '../src/pantallas/PantallaDeRentas.tsx';

/**
 * **Tampoco el interprete se lleva el armazon: la segunda frontera** (#354).
 *
 * `una-hoja-que-revienta-no-se-lleva-el-armazon` mide lo corriente —un conector que lanza al
 * repartir—, y ahi la primera frontera basta: dibuja la MISMA pantalla con una ausencia. Pero el
 * issue pide cubrir «cualquier excepcion de la hoja, incluida la del interprete», y si lo que lanza
 * es el interprete con esa definicion, dibujarla otra vez lanza lo mismo. Un error dentro de lo que
 * una frontera dibuja en su lugar ya no lo recoge ella: sube a la de react-router, y vuelve
 * «Unexpected Application Error!». Por eso `aplicacion.tsx` monta dos, y esto mide la segunda.
 *
 * <h2>Por que se sustituye `PantallaDeRentas` y no se busca una definicion rota</h2>
 *
 * Porque las cuarenta definiciones son correctas —las vigilan seis guardas— y no hay una rota que
 * abrir. Lo que se sustituye es solo la pantalla de `territorio`, y solo para lanzar: las demas
 * siguen dibujandose con el interprete de verdad, y el armazon es el de verdad.
 */

const LO_QUE_LANZA_EL_INTERPRETE = 'El interprete no sabe dibujar esta definicion';

vi.mock('../src/pantallas/PantallaDeRentas.tsx', async (importOriginal) => {
  const real = await importOriginal<typeof import('../src/pantallas/PantallaDeRentas.tsx')>();
  const { pantallaDe } = await import('../src/pantallas/definiciones/index.ts');
  const rota = pantallaDe('territorio');
  return {
    ...real,
    PantallaDeRentas: (props: PantallaDeRentasProps) => {
      if (props.definicion === rota) throw new Error(LO_QUE_LANZA_EL_INTERPRETE);
      return <real.PantallaDeRentas {...props} />;
    },
  };
});

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

beforeEach(() => {
  CONSULTAS.clear();
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      const json = (cuerpo: unknown) =>
        Promise.resolve(
          new Response(JSON.stringify(cuerpo), {
            status: 200,
            headers: { 'content-type': 'application/json' },
          }),
        );
      const pagina = (contenido: readonly unknown[]) =>
        json({
          contenido,
          pagina: 0,
          tamano: 200,
          totalElementos: contenido.length,
          totalPaginas: 1,
          hayMas: false,
        });
      if (url.includes('/seguridad/modulos')) return pagina(MODULOS_MEDIDOS);
      if (url.includes('/seguridad/accesos')) return pagina(ACCESOS_MEDIDOS);
      if (url.includes('/seguridad/sesion/permisos')) return json(PERMISOS_MEDIDOS);
      return Promise.resolve(new Response('{}', { status: 404 }));
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

describe('ni el interprete se lleva el armazon (#354)', () => {
  it('si la pantalla lanza con su definicion, el cuerpo dice la frase sola y el carril sigue', async () => {
    const consola = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      window.location.hash = '#/territorio';
      render(<Aplicacion />);

      await waitFor(() => {
        expect(document.querySelector('[data-slot="hoja-sin-dibujar"]')).not.toBeNull();
      });
      // La frase, con el motivo de lo que lanzo: la segunda vez, que es la que no se pudo dibujar.
      expect(document.body.textContent).toContain(LO_QUE_LANZA_EL_INTERPRETE);
      expect(document.body.textContent).toContain('esta pantalla no la pudo dibujar');
      // El armazon, en pie: el carril con otro modulo que abrir.
      expect(screen.getByRole('button', { name: /Tránsito/ })).toBeTruthy();
      expect(document.body.textContent).not.toContain('Unexpected Application Error!');
    } finally {
      consola.mockRestore();
    }
  });
});

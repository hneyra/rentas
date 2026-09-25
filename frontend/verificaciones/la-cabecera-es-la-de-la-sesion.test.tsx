import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../src/aplicacion.tsx';
import type { MunicipalidadDeLaSesion, SesionDeLaVentanilla } from '../src/datos/lecturas.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../src/datos/seguridadMedida.ts';
import { MUNICIPALIDAD_MEDIDA, SESION_MEDIDA } from '../src/datos/sesionMedida.ts';
import { FICHA, SIN_CAMPANIA } from '../src/datos/conectores/consultasDeMuestra.ts';

/**
 * **La cabecera dice de quien son las cifras y quien esta actuando, y lo dice la SESION** (#356).
 *
 * <h2>De que defecto viene</h2>
 *
 * `623a968` (#90) quito las dos lecturas de la sesion de `aplicacion.tsx` y puso en su lugar
 * `const ENTIDAD = 'Municipalidad Distrital de Catacaos'`, el escudo de Catacaos y una cuenta
 * `{ nombre: 'J. Cardenas Vega', iniciales: 'JC' }`. Desde entonces **cualquier** cuenta de
 * **cualquier** municipalidad veia, en las cuarenta pantallas y encima de las cifras de su propio
 * padron —que RLS si filtra—, el nombre y el escudo de Catacaos y a una persona que no existe. Con la
 * unica cuenta capturada ya salia mal: `sesionMedida.ts` dice «Administrador del Sistema».
 *
 * <h2>La muestra que distingue, y la que no</h2>
 *
 * La municipalidad que contesta el doble es **Sullana**, no la capturada. Con `MUNICIPALIDAD_MEDIDA`
 * —que es Catacaos— la entidad saldria verde **con el defecto puesto**: la constante y la lectura
 * dirian lo mismo, y la prueba no sabria de cual de las dos salio. Lo comprueba el centinela.
 */

/** La cuenta que contesta la instalacion: su nombre no es el del artboard. */
const SESION: SesionDeLaVentanilla = SESION_MEDIDA;

/** Una municipalidad DISTINTA de la capturada. Ver el javadoc. */
const SULLANA: MunicipalidadDeLaSesion = {
  id: 12,
  ubigeo: '200601',
  nombre: 'Municipalidad Provincial de Sullana',
  tipo: 'PROVINCIAL',
};

/** Como contestan las dos de la cabecera en cada prueba. Las tres del catalogo, siempre. */
let cabecera: 'contesta' | 'no-contesta-todavia' | 'falla' = 'contesta';

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
  // La cache es de MODULO y sobrevive a cada `render`: ver `aplicacion.tsx`.
  CONSULTAS.clear();
  cabecera = 'contesta';
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const ruta = new URL(String(entrada), 'http://localhost').pathname;
      const json = (cuerpo: unknown, estado = 200) =>
        Promise.resolve(
          new Response(JSON.stringify(cuerpo), {
            status: estado,
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
      if (ruta.endsWith('/seguridad/modulos')) return pagina(MODULOS_MEDIDOS);
      if (ruta.endsWith('/seguridad/accesos')) return pagina(ACCESOS_MEDIDOS);
      if (ruta.endsWith('/seguridad/sesion/permisos')) return json(PERMISOS_MEDIDOS);
      // La ficha de `con-panel`, que NO necesita la sesion: ver la ultima prueba.
      if (ruta.endsWith('/consultas/unificada')) return json(FICHA);
      if (ruta.endsWith('/consultas/deudas-con-beneficio')) return json(SIN_CAMPANIA);

      // Por la ruta EXACTA y no por `includes`: `/seguridad/sesion` es prefijo de las otras dos.
      const deLaCabecera = ruta.endsWith('/seguridad/sesion/municipalidad')
        ? SULLANA
        : ruta.endsWith('/seguridad/sesion')
          ? SESION
          : null;
      if (deLaCabecera === null) return json({}, 404);
      if (cabecera === 'no-contesta-todavia') return new Promise<Response>(() => {});
      if (cabecera === 'falla') return json({ title: 'Error interno', status: 500 }, 500);
      return json(deLaCabecera);
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

/** Monta la aplicacion y devuelve la barra global, que no existe hasta que hay arbol. */
async function laBarra(destino = 'ini-panel'): Promise<HTMLElement> {
  window.location.hash = `#/${destino}`;
  render(<Aplicacion />);
  let barra: HTMLElement | null = null;
  await waitFor(() => {
    barra = document.querySelector<HTMLElement>('[data-slot="barra-global"]');
    expect(barra).not.toBeNull();
  });
  return barra as unknown as HTMLElement;
}

/** Lo que la barra no puede decir a nadie: el artboard escrito a mano. */
function loDelArtboard(barra: HTMLElement): readonly string[] {
  const texto = barra.textContent;
  const escudos = [...barra.querySelectorAll('img')].map((i) => i.getAttribute('src') ?? '');
  return [
    ...(/Catacaos/i.test(texto) ? ['el nombre de Catacaos'] : []),
    ...(/C[aá]rdenas/i.test(texto) ? ['«J. Cardenas Vega»'] : []),
    ...escudos.filter((src) => /catacaos/i.test(src)).map((src) => `el escudo ${src}`),
  ];
}

describe('#356 — la cabecera es la de la sesion, no la del artboard', () => {
  it('EL CENTINELA: la municipalidad del doble NO es la capturada, y la cuenta tampoco es la del artboard', () => {
    // Sin esto, cambiar la muestra por `MUNICIPALIDAD_MEDIDA` dejaria la prueba de abajo en verde
    // con la constante puesta: las dos dirian «Catacaos».
    expect(SULLANA.nombre).not.toBe(MUNICIPALIDAD_MEDIDA.nombre);
    expect(SULLANA.ubigeo).not.toBe(MUNICIPALIDAD_MEDIDA.ubigeo);
    expect(SULLANA.nombre).not.toMatch(/Catacaos/);
    expect(SESION.nombre).not.toMatch(/C[aá]rdenas/);
  });

  it('la barra dice la municipalidad y la cuenta que contesta la sesion, y nada del artboard', async () => {
    const barra = await laBarra();

    await waitFor(() => {
      expect(within(barra).getAllByText(SULLANA.nombre).length).toBeGreaterThan(0);
    });
    expect(within(barra).getByText(SESION.nombre)).toBeTruthy();
    // Las iniciales salen del nombre de la sesion, no del artboard.
    expect(within(barra).getByText('AS')).toBeTruthy();
    expect(within(barra).queryByText('JC')).toBeNull();
    expect(loDelArtboard(barra), 'la barra ensena lo que el artboard escribio a mano').toEqual([]);
  });

  it('mientras la sesion no contesta, la barra lo dice y NO inventa un nombre', async () => {
    cabecera = 'no-contesta-todavia';
    const barra = await laBarra();

    expect(loDelArtboard(barra), 'la barra ensena lo que el artboard escribio a mano').toEqual([]);
    expect(within(barra).queryByText(SESION.nombre)).toBeNull();
    expect(within(barra).getByText('Averiguando la municipalidad de esta sesion')).toBeTruthy();
    expect(within(barra).getByText('Averiguando quien ha entrado')).toBeTruthy();
  });

  it('y si falla, lo dice, tambien sin inventar', async () => {
    cabecera = 'falla';
    const barra = await laBarra();

    await waitFor(() => {
      expect(within(barra).getByText('No se pudo saber de que municipalidad es esta sesion')).toBeTruthy();
    });
    expect(within(barra).getByText('No se pudo saber quien ha entrado')).toBeTruthy();
    expect(loDelArtboard(barra), 'la barra ensena lo que el artboard escribio a mano').toEqual([]);
  });

  it('y el fallo de la sesion se queda en la barra: una hoja que no la necesita pinta su dato', async () => {
    // La sesion es UNA consulta, con la llave que tambien lee `useDatosDeLaHoja` para el ejercicio.
    // Desde que la barra la pide en todas las pantallas, su error llega a todas las hojas: sin
    // acotarlo a las que la piden, `con-panel` —que no la necesita— decia «fallo» con su ficha
    // contestada. Lo que falla es saber quien ha entrado, no la cuenta corriente del contribuyente.
    cabecera = 'falla';
    const barra = await laBarra('con-panel/00000025673');

    await waitFor(() => {
      expect(screen.getByText('S/ 3,563.24')).toBeTruthy();
    });
    expect(within(barra).getByText('No se pudo saber quien ha entrado')).toBeTruthy();
  });
});

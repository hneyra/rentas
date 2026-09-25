import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../aplicacion.tsx';
import { ACCESOS_MEDIDOS, MODULOS_MEDIDOS, PERMISOS_MEDIDOS } from './seguridadMedida.ts';

/**
 * **El 403 sobre el catalogo nombra las dos opciones que faltan, y solo el ofrece reintentar** (#311).
 *
 * <h2>Que se mide, y por que montando la aplicacion entera</h2>
 *
 * `GET /seguridad/modulos` y `GET /seguridad/accesos` piden dos opciones de ADMINISTRACION, y a la
 * cuenta que no las tenga le contestan **403 `SIN_PRIVILEGIO`**: no se queda sin un modulo, se
 * queda sin arbol. Lo que la pantalla tiene que decir entonces es **el unico dato con que se
 * arregla** —que opciones faltan, por su nombre del catalogo— y ofrecer volver a pedir, porque el
 * guardia del backend comprueba cada peticion contra la base (ADR-0013) y el remedio surte efecto
 * sin cerrar la sesion.
 *
 * Se monta `<Aplicacion>` y no el gancho suelto: lo que falta en #311 es lo que se VE —la frase,
 * los dos nombres y el boton—, y un gancho que devolviera todo eso con una pantalla que no lo
 * dibujara saldria verde.
 *
 * <h2>Las ramas son tres a proposito, y no una</h2>
 *
 * Una muestra uniforme —solo el 403— daria verde a una pantalla que pusiera el boton en todas. Por
 * eso se prueban tambien el 401, el 500 y el 403 `SIN_MUNICIPALIDAD`, **sin** boton: en un 401
 * reintentar trae el mismo token y el mismo 401, y #291 acaba de retirar 39 botones que no
 * arreglaban nada.
 */

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

/** Como contesta el doble a cada una de las tres: con lo medido, o con un fallo. */
type Contesta = 'bien' | { readonly estado: number; readonly codigo?: string };

let contestan: { modulos: Contesta; accesos: Contesta; permisos: Contesta };
let pedidas: string[] = [];

const BIEN = { modulos: 'bien', accesos: 'bien', permisos: 'bien' } as const;
const SIN_PRIVILEGIO = { estado: 403, codigo: 'SIN_PRIVILEGIO' } as const;

function responder(como: Contesta, cuerpo: unknown): Promise<Response> {
  if (como === 'bien') {
    return Promise.resolve(
      new Response(JSON.stringify(cuerpo), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
  }
  // El `problem+json` que el backend publica, con su `codigo`: es lo que separa los dos 403.
  return Promise.resolve(
    new Response(
      JSON.stringify({ title: 'Prohibido', status: como.estado, ...(como.codigo ? { codigo: como.codigo } : {}) }),
      { status: como.estado, headers: { 'content-type': 'application/problem+json' } },
    ),
  );
}

function pagina(contenido: readonly unknown[]) {
  return {
    contenido,
    pagina: 0,
    tamano: 200,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  };
}

beforeEach(() => {
  // La cache es de MODULO: sin limpiarla, una prueba leeria lo que contesto la anterior.
  CONSULTAS.clear();
  pedidas = [];
  contestan = { ...BIEN };
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      pedidas.push(url);
      if (url.includes('/seguridad/modulos')) return responder(contestan.modulos, pagina(MODULOS_MEDIDOS));
      if (url.includes('/seguridad/accesos')) return responder(contestan.accesos, pagina(ACCESOS_MEDIDOS));
      if (url.includes('/seguridad/sesion/permisos')) return responder(contestan.permisos, PERMISOS_MEDIDOS);
      return Promise.resolve(new Response('{}', { status: 404 }));
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  window.location.hash = '';
});

const ESPERAR = { timeout: 3000 };

function elArmazon() {
  return document.querySelector('[data-slot="barra-global"]');
}

/** Monta y espera a que se diga algo que no sea «averiguando». */
async function montarYEsperarElPorQue() {
  render(<Aplicacion />);
  await waitFor(() => {
    expect(screen.queryByText('Averiguando que puede abrir esta cuenta.')).toBeNull();
  }, ESPERAR);
}

function elBotonDeReintentar() {
  return screen.queryByRole('button', { name: 'Reintentar' });
}

/**
 * El remedio del 401 (#355). Se mira en TODAS las ramas, y no solo en la suya: una pantalla que lo
 * pusiera en cualquier error saldria verde si solo se probara el 401 — y en un 500 o en un 403
 * volver a identificarse no arregla nada, solo manda de paseo a Keycloak.
 */
function elBotonDeVolver() {
  return screen.queryByRole('button', { name: 'Volver a identificarse' });
}

describe('el 403 SIN_PRIVILEGIO sobre el catalogo (#311)', () => {
  it('nombra las DOS opciones por su nombre del catalogo, no por su codigo, y ofrece reintentar', async () => {
    contestan = { ...BIEN, modulos: SIN_PRIVILEGIO, accesos: SIN_PRIVILEGIO };
    await montarYEsperarElPorQue();

    const aviso = document.querySelector('[data-slot="catalogo-sin-privilegio"]');
    expect(aviso, 'el 403 no tiene pantalla propia: cayo en la rama generica').not.toBeNull();
    const faltan = [...(aviso?.querySelectorAll('li') ?? [])].map((li) => li.textContent);
    expect(faltan).toEqual(['Módulos del sistema', 'Accesos y políticas']);
    expect(elBotonDeReintentar(), 'el 403 no ofrece reintentar').not.toBeNull();
    expect(elBotonDeVolver(), 'el 403 manda a identificarse otra vez').toBeNull();
    expect(elArmazon()).toBeNull();
  });

  it('nombra solo la que falta cuando falta una', async () => {
    contestan = { ...BIEN, accesos: SIN_PRIVILEGIO };
    await montarYEsperarElPorQue();

    const aviso = document.querySelector('[data-slot="catalogo-sin-privilegio"]');
    const faltan = [...(aviso?.querySelectorAll('li') ?? [])].map((li) => li.textContent);
    expect(faltan).toEqual(['Accesos y políticas']);
  });

  it('reintentar VUELVE a pedir, y si esta vez contesta, monta el arbol', async () => {
    contestan = { ...BIEN, modulos: SIN_PRIVILEGIO, accesos: SIN_PRIVILEGIO };
    await montarYEsperarElPorQue();
    const boton = elBotonDeReintentar();
    expect(boton).not.toBeNull();
    const antes = pedidas.filter((u) => u.includes('/seguridad/modulos')).length;

    // El administrador le dio las dos opciones mientras tanto.
    contestan = { ...BIEN };
    if (boton !== null) fireEvent.click(boton);

    await waitFor(() => {
      expect(elArmazon(), 'reintentar no monto el arbol').not.toBeNull();
    }, ESPERAR);
    expect(pedidas.filter((u) => u.includes('/seguridad/modulos')).length).toBeGreaterThan(antes);
  });
});

describe('y las otras ramas NO ofrecen reintentar: no arreglaria nada', () => {
  it('401: la sesion no vale, y lo que se dice es volver a entrar', async () => {
    contestan = { ...BIEN, modulos: { estado: 401, codigo: 'NO_AUTENTICADO' }, accesos: { estado: 401 } };
    await montarYEsperarElPorQue();

    expect(screen.getByText(/Vuelva a entrar/)).toBeTruthy();
    expect(elBotonDeReintentar(), 'un 401 ofrece reintentar').toBeNull();
    // Pero SI ofrece volver a identificarse, que es lo que arregla un 401 (#355).
    expect(elBotonDeVolver(), 'el 401 no trae su remedio').not.toBeNull();
    expect(document.querySelector('[data-slot="catalogo-sin-privilegio"]')).toBeNull();
  });

  it('401 SIN puerta —origen no seguro, sin `crypto.subtle`—: no se ofrece un boton que revienta', async () => {
    // Fuera de un origen seguro el navegador no expone `crypto.subtle` y no hay S256: `entrar()`
    // pasaria la sonda, escribiria sus llaves y reventaria al calcular el reto. Ofrecer ese boton
    // es ofrecer un fallo seguro; la condicion `hayPuerta()` de `useCatalogoPermitido` es la que lo
    // quita, y esta es la siembra que la distingue: con la puerta de jsdom, que si tiene
    // `crypto.subtle`, quitarla no cambia nada (#355, ronda 1).
    vi.stubGlobal('crypto', {
      getRandomValues: crypto.getRandomValues.bind(crypto),
      randomUUID: crypto.randomUUID.bind(crypto),
    });
    contestan = { ...BIEN, modulos: { estado: 401, codigo: 'NO_AUTENTICADO' }, accesos: { estado: 401 } };
    await montarYEsperarElPorQue();

    expect(screen.getByText(/Vuelva a entrar/)).toBeTruthy();
    expect(elBotonDeVolver(), 'sin puerta se ofrece volver a identificarse').toBeNull();
  });

  it('500: la rama generica, sin boton y sin nombrar opciones', async () => {
    contestan = { ...BIEN, modulos: { estado: 500 } };
    await montarYEsperarElPorQue();

    expect(screen.getByText(/No se pudo saber que modulos puede abrir esta cuenta/)).toBeTruthy();
    expect(elBotonDeReintentar(), 'un 500 ofrece reintentar').toBeNull();
    expect(elBotonDeVolver(), 'un 500 manda a identificarse otra vez').toBeNull();
    expect(screen.queryByText('Módulos del sistema')).toBeNull();
  });

  it('403 SIN_MUNICIPALIDAD: no falta una opcion, asi que no se nombra ninguna', async () => {
    contestan = {
      modulos: { estado: 403, codigo: 'SIN_MUNICIPALIDAD' },
      accesos: { estado: 403, codigo: 'SIN_MUNICIPALIDAD' },
      permisos: { estado: 403, codigo: 'SIN_MUNICIPALIDAD' },
    };
    await montarYEsperarElPorQue();

    expect(document.querySelector('[data-slot="catalogo-sin-privilegio"]')).toBeNull();
    expect(elBotonDeReintentar(), 'un SIN_MUNICIPALIDAD ofrece reintentar').toBeNull();
    expect(elBotonDeVolver(), 'un SIN_MUNICIPALIDAD manda a identificarse otra vez').toBeNull();
  });

  it('un 403 SIN_PRIVILEGIO junto a un 500 no se hace pasar por falta de permiso', async () => {
    // Si ademas algo se rompio, decir «solo le faltan estas dos» seria mentir: dada la opcion, la
    // pantalla seguiria sin arbol.
    contestan = { ...BIEN, modulos: SIN_PRIVILEGIO, permisos: { estado: 500 } };
    await montarYEsperarElPorQue();

    expect(document.querySelector('[data-slot="catalogo-sin-privilegio"]')).toBeNull();
    expect(elBotonDeReintentar()).toBeNull();
  });
});

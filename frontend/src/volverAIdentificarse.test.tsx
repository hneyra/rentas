import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { fijarToken } from './api/identidad.ts';
import { Aplicacion, CONSULTAS } from './aplicacion.tsx';
import { arrancar } from './arranque.ts';

/**
 * **Despues de «Cerrar sesion», o de un canje fallido, la pestana puede volver a entrar** (#355).
 *
 * <h2>De que defecto viene</h2>
 *
 * `salir()` deja la marca `kamayuk.pkce.salida` en `sessionStorage`, y con ella puesta el arranque
 * no va a la puerta: monta, las tres lecturas de seguridad contestan 401 y la aplicacion dice «Vuelva
 * a entrar.» en un parrafo suelto, **sin un solo control**. La marca solo se borra en `entrar()` —a
 * la que no se llega mientras siga puesta— y en `olvidarLaParada()`, que no tenia ni un consumidor
 * de produccion desde que #90 se llevo `Puerta.tsx` con su «Volver a identificarse». F5 repite lo
 * mismo, porque la marca sobrevive a la recarga: la pestana quedaba inservible hasta cerrarla.
 *
 * Y con un canje fallido pasaba lo mismo por el otro freno —el tope de tres idas—, perdiendo por el
 * camino **el motivo del emisor**: `arrancar` tiraba la `Vuelta` de `canjearSiVuelve`.
 *
 * <h2>Por que se pasa por `arrancar`, y no se monta la aplicacion a secas</h2>
 *
 * Porque la muestra uniforme es la que lo dejaba verde: montada **sin** la marca, una pestana va a
 * la puerta y nunca llega a esta rama. Aqui cada prueba siembra el freno que la trae —la marca, o
 * el tope agotado— y comprueba primero que el arranque MONTO, que es lo que la pone delante.
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

const SALIDA = 'kamayuk.pkce.salida';
const IDAS = 'kamayuk.pkce.idas';
const ESPERAR = { timeout: 3000 };

/** Sustituye `location`, que en jsdom no se puede espiar de otra manera. */
function ubicacion(href = 'http://localhost:5173/rentas/') {
  const url = new URL(href);
  const asignar = vi.fn();
  vi.stubGlobal('location', {
    origin: url.origin,
    href: url.href,
    pathname: url.pathname,
    search: url.search,
    hash: url.hash,
    assign: asignar,
    reload: vi.fn(),
  });
  return asignar;
}

/** Si la sonda del emisor (#112) llega. Las tres de seguridad contestan 401 en los dos casos. */
let elEmisorContesta = true;
let pedidas: string[] = [];

function sinSesion(url: string): Promise<Response> {
  pedidas.push(url);
  return Promise.resolve(
    new Response(JSON.stringify({ title: 'No autenticado', status: 401, codigo: 'NO_AUTENTICADO' }), {
      status: 401,
      headers: { 'content-type': 'application/problem+json' },
    }),
  );
}

beforeEach(() => {
  CONSULTAS.clear();
  sessionStorage.clear();
  fijarToken(null);
  elEmisorContesta = true;
  pedidas = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      if (url.includes('/.well-known/openid-configuration')) {
        return elEmisorContesta
          ? Promise.resolve(new Response(null, { status: 200 }))
          : Promise.reject(new TypeError('Failed to fetch'));
      }
      return sinSesion(url);
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  fijarToken(null);
  sessionStorage.clear();
});

/** Arranca como `main.tsx`: el montaje es el argumento. Devuelve si monto. */
async function arrancarYMontar(): Promise<boolean> {
  let monto = false;
  await arrancar(() => {
    monto = true;
    render(<Aplicacion />);
  });
  return monto;
}

async function elBotonDeVolver(): Promise<HTMLElement> {
  return screen.findByRole('button', { name: 'Volver a identificarse' }, ESPERAR);
}

describe('(a) recien salido: la pestana ofrece volver a identificarse', () => {
  it('con la marca de salida monta sin ir a la puerta, y el 401 trae el boton', async () => {
    const asignar = ubicacion();
    sessionStorage.setItem(SALIDA, '1');

    expect(await arrancarYMontar(), 'no monto: fue a la puerta').toBe(true);
    expect(asignar, 'volvio a entrar solo tras cerrar sesion').not.toHaveBeenCalled();
    await waitFor(() => {
      expect(pedidas.some((u) => u.includes('/seguridad/'))).toBe(true);
    }, ESPERAR);

    // Sin el boton, lo unico que hay es el parrafo y ninguna forma de salir de el.
    await elBotonDeVolver();
  });

  it('pulsarlo quita la marca y sale hacia el emisor', async () => {
    const asignar = ubicacion();
    sessionStorage.setItem(SALIDA, '1');
    await arrancarYMontar();

    fireEvent.click(await elBotonDeVolver());

    await waitFor(() => {
      expect(asignar).toHaveBeenCalled();
    }, ESPERAR);
    expect(String(asignar.mock.calls[0]?.[0])).toContain(
      'http://localhost:8181/realms/kamayuk/protocol/openid-connect/auth',
    );
    expect(sessionStorage.getItem(SALIDA), 'la marca de salida sigue puesta').toBeNull();
  });

  it('con el emisor apagado, pulsar ensena por que no se pudo (#112), no un boton mudo', async () => {
    const asignar = ubicacion();
    sessionStorage.setItem(SALIDA, '1');
    await arrancarYMontar();
    elEmisorContesta = false;

    fireEvent.click(await elBotonDeVolver());

    await screen.findByText(/No se pudo llegar al emisor de identidad/, undefined, ESPERAR);
    expect(screen.getByText(/\/\.well-known\/openid-configuration/).textContent).toContain(
      'Failed to fetch',
    );
    expect(asignar).not.toHaveBeenCalled();
  });
});

describe('(b) el emisor rechazo la entrada: se dice su motivo, y se puede volver', () => {
  const VUELTA =
    'http://localhost:5173/rentas/?error=access_denied' +
    '&error_description=' +
    encodeURIComponent('El usuario cancelo el formulario de identificacion');

  it('con el tope agotado monta, y dice el motivo y el detalle del emisor', async () => {
    const asignar = ubicacion(VUELTA);
    sessionStorage.setItem(IDAS, '3');

    expect(await arrancarYMontar(), 'no monto con el tope agotado').toBe(true);
    expect(asignar).not.toHaveBeenCalled();

    await screen.findByText(/No se completo la entrada/, undefined, ESPERAR);
    expect(screen.getByText(/El usuario cancelo el formulario de identificacion/)).toBeTruthy();
    // El motivo SUSTITUYE a la frase generica: con las dos, lo que se lee primero es la que no dice
    // nada de lo que paso.
    expect(screen.queryByText(/La sesion no vale para saber/)).toBeNull();
  });

  it('volver a identificarse cuenta la ida como la PRIMERA, no como la cuarta', async () => {
    // El tope corta un bucle automatico; esto es una persona pulsando un boton. Sin olvidar la
    // parada, la cuenta seguiria en 3 y la ida la subiria a 4: la proxima vuelta fallida no
    // tendria ni un rebote automatico mas.
    const asignar = ubicacion(VUELTA);
    sessionStorage.setItem(IDAS, '3');
    await arrancarYMontar();

    fireEvent.click(await elBotonDeVolver());

    await waitFor(() => {
      expect(asignar).toHaveBeenCalled();
    }, ESPERAR);
    expect(sessionStorage.getItem(IDAS)).toBe('1');
  });
});

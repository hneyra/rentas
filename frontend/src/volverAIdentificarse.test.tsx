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
  vi.restoreAllMocks();
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

  it('con el tope agotado monta, y dice el motivo y lo que escribio el emisor', async () => {
    const asignar = ubicacion(VUELTA);
    sessionStorage.setItem(IDAS, '3');

    expect(await arrancarYMontar(), 'no monto con el tope agotado').toBe(true);
    expect(asignar).not.toHaveBeenCalled();

    // El motivo es el titulo, solo: «…no dejo terminar la entrada: No se completo la entrada.»
    // decia dos veces lo mismo (ronda 1).
    expect((await screen.findByText(/No se completo la entrada/, undefined, ESPERAR)).textContent).toBe(
      'No se completo la entrada',
    );
    expect(screen.getByText(/access_denied/).textContent).toBe(
      'El emisor devolvio el codigo de error «access_denied».',
    );
    // Y solo lo que escribio el emisor se le atribuye.
    expect(screen.getByText(/El usuario cancelo el formulario de identificacion/).textContent).toBe(
      'Lo que dijo el emisor: «El usuario cancelo el formulario de identificacion»',
    );
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

/**
 * **Si la ida revienta antes de salir, el boton vuelve y se dice por que** (#355, ronda 1).
 *
 * `entrar()` puede RECHAZAR, y no solo devolver una falla: escribe cuatro llaves en
 * `sessionStorage` —que lanza si esta lleno o bloqueado— y calcula el reto con `crypto.subtle`. El
 * boton se deshabilita al pulsar para no lanzar dos idas a la vez, y sin atender el rechazo se
 * quedaba asi **para siempre**: la pestana volvia a no tener nada que pulsar, que es justo el
 * defecto que #355 cierra, y el rechazo salia sin atender a la consola.
 *
 * Se siembra `setItem` lanzando solo sobre las llaves de la puerta, y DESPUES de montar: el resto
 * de la aplicacion sigue escribiendo, y lo unico que falla es la ida.
 */
describe('(c) si la ida revienta antes de salir, el boton vuelve y se dice por que', () => {
  it('con el almacenamiento lleno: el boton se puede pulsar otra vez y la pantalla dice lo que paso', async () => {
    const asignar = ubicacion();
    sessionStorage.setItem(SALIDA, '1');
    await arrancarYMontar();
    const escribir = Storage.prototype.setItem;
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (
      this: Storage,
      clave: string,
      valor: string,
    ) {
      if (clave.startsWith('kamayuk.pkce.')) {
        throw new DOMException('Se lleno el almacenamiento de la pestana', 'QuotaExceededError');
      }
      escribir.call(this, clave, valor);
    });

    fireEvent.click(await elBotonDeVolver());

    await waitFor(() => {
      expect(
        (screen.getByRole('button', { name: 'Volver a identificarse' }) as HTMLButtonElement).disabled,
        'el boton se quedo deshabilitado: la pestana otra vez sin nada que pulsar',
      ).toBe(false);
    }, ESPERAR);
    expect(screen.getByText(/Se lleno el almacenamiento de la pestana/)).toBeTruthy();
    expect(asignar).not.toHaveBeenCalled();
  });
});

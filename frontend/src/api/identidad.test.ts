import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  canjearSiVuelve,
  entrar,
  fijarToken,
  hayPuerta,
  olvidarLaParada,
  puedeIrALaPuerta,
  salir,
  token,
  vieneDeSalir,
} from './identidad.ts';

/**
 * La puerta de identidad: **PKCE S256, y el token en memoria** (AC1).
 *
 * <h2>Lo que estas pruebas miran de verdad</h2>
 *
 * No que el parametro `code_challenge_method` diga `S256` —eso es una cadena, y una cadena se
 * puede escribir bien con el calculo mal—, sino que el reto **sea** el SHA-256 del verificador
 * que se guardo: la prueba lo recalcula por su cuenta y compara. Un reto que no cuadre lo
 * rechaza Keycloak en el canje, o sea que el sintoma llegaria en el rebote y no aqui.
 *
 * Y no que el codigo «no use localStorage» —eso lo mira ESLint por el nombre de la clave—, sino
 * que **despues del canje ningun almacenamiento del navegador contenga el token**, mire donde
 * mire y se llame como se llame la clave. Es la mitad que la prohibicion no puede ver:
 * `localStorage.setItem('kamayuk.preferencia', elToken)` pasa la prohibicion entera, porque la
 * prohibicion mira la clave.
 */

const REALM = 'http://localhost:8181/realms/sgtm';

/** Sustituye `location`, que en jsdom no se puede espiar de otra manera. */
function ubicacion(href = 'http://localhost:5173/') {
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

/** `BASE64URL(SHA256(verificador))`, calculado aqui y no leido del codigo que se prueba. */
async function retoEsperado(verificador: string): Promise<string> {
  const resumen = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verificador));
  let texto = '';
  new Uint8Array(resumen).forEach((b) => (texto += String.fromCharCode(b)));
  return btoa(texto).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

beforeEach(() => {
  sessionStorage.clear();
  localStorage.clear();
  fijarToken(null);
});

afterEach(() => {
  vi.unstubAllGlobals();
  sessionStorage.clear();
  localStorage.clear();
  fijarToken(null);
});

describe('AC1 — la ida a la puerta es codigo de autorizacion con PKCE S256', () => {
  it('manda al realm sgtm, al cliente sgtm-backoffice, con code y S256', async () => {
    const asignar = ubicacion();

    await entrar();

    const destino = new URL(String(asignar.mock.calls[0]?.[0]));
    expect(destino.origin + destino.pathname).toBe(`${REALM}/protocol/openid-connect/auth`);
    expect(destino.searchParams.get('response_type')).toBe('code');
    expect(destino.searchParams.get('client_id')).toBe('sgtm-backoffice');
    expect(destino.searchParams.get('code_challenge_method')).toBe('S256');
    // La URI de retorno es la raiz del origen, y una sola: declarar una por pantalla seria una
    // lista que ampliar cada vez que nace una seccion, y el sintoma de olvidarse es «Invalid
    // parameter: redirect_uri».
    expect(destino.searchParams.get('redirect_uri')).toBe('http://localhost:5173/');
  });

  it('y el reto ES el SHA-256 del verificador guardado, no una cadena que lo diga', async () => {
    const asignar = ubicacion();

    await entrar();

    const verificador = sessionStorage.getItem('kamayuk.pkce.verificador');
    expect(verificador).not.toBeNull();
    const destino = new URL(String(asignar.mock.calls[0]?.[0]));
    expect(destino.searchParams.get('code_challenge')).toBe(await retoEsperado(verificador ?? ''));
  });

  it('dos idas dan dos verificadores distintos: no hay secreto fijo', async () => {
    ubicacion();
    await entrar();
    const primero = sessionStorage.getItem('kamayuk.pkce.verificador');
    await entrar();

    expect(sessionStorage.getItem('kamayuk.pkce.verificador')).not.toBe(primero);
  });

  it('el verificador se guarda con una clave que NO nombra ninguna credencial', () => {
    // Tiene que sobrevivir al rebote, asi que va en `sessionStorage` — y por eso su clave no
    // lleva «token», «acceso» ni «sesion»: llamarlo `sgtm.token.verificador` obligaria a quien
    // lea esto dentro de seis meses a distinguir dos cosas que se llaman igual.
    const vigiladas = /token|jwt|bearer|credencial|contrasena|acceso|sesion/i;
    expect(vigiladas.test('kamayuk.pkce.verificador')).toBe(false);
  });

  it('hay puerta: jsdom expone crypto.subtle, que es lo que S256 necesita', () => {
    expect(hayPuerta()).toBe(true);
  });
});

describe('AC1 — el canje deja el token EN MEMORIA y en ningun almacenamiento', () => {
  const TOKEN = 'eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZG1pbmlzdHJhZG9yIn0.firma';
  const IDENTIDAD = 'eyJhbGciOiJSUzI1NiJ9.eyJpZCI6MX0.firma';

  /** Deja el navegador como si acabara de volver de Keycloak con un codigo bueno. */
  function vueltaBuena() {
    sessionStorage.setItem('kamayuk.pkce.verificador', 'el-verificador');
    sessionStorage.setItem('kamayuk.pkce.estado', 'el-estado');
    ubicacion('http://localhost:5173/?code=un-codigo&state=el-estado');
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() =>
        Promise.resolve(
          Response.json({ access_token: TOKEN, id_token: IDENTIDAD, refresh_token: 'refresco' }),
        ),
      ),
    );
  }

  it('canjea y el token queda disponible', async () => {
    vueltaBuena();

    await expect(canjearSiVuelve()).resolves.toEqual({ estado: 'canjeado' });
    expect(token()).toBe(TOKEN);
  });

  it('manda al canje el verificador, el cliente y el grant que toca', async () => {
    vueltaBuena();

    await canjearSiVuelve();

    const espia = vi.mocked(globalThis.fetch);
    expect(String(espia.mock.calls[0]?.[0])).toBe(`${REALM}/protocol/openid-connect/token`);
    const enviado = new URLSearchParams(String(espia.mock.calls[0]?.[1]?.body));
    expect(enviado.get('grant_type')).toBe('authorization_code');
    expect(enviado.get('client_id')).toBe('sgtm-backoffice');
    expect(enviado.get('code_verifier')).toBe('el-verificador');
    // Sin secreto: el cliente es publico, y un secreto dentro de un bundle no es un secreto.
    expect(enviado.get('client_secret')).toBeNull();
  });

  it('NADA de lo que se canjeo acaba en localStorage ni en sessionStorage', async () => {
    vueltaBuena();

    await canjearSiVuelve();

    // Se recorre el contenido y no las claves: la prohibicion de ESLint mira el NOMBRE de la
    // clave, asi que `localStorage.setItem('kamayuk.preferencia', elToken)` la pasaria entera.
    // Esta es la mitad que la prohibicion no puede ver.
    const guardado = [localStorage, sessionStorage].flatMap((donde) =>
      Object.keys(donde).map((clave) => donde.getItem(clave) ?? ''),
    );
    expect(guardado.some((valor) => valor.includes(TOKEN))).toBe(false);
    expect(guardado.some((valor) => valor.includes(IDENTIDAD))).toBe(false);
    expect(guardado.some((valor) => valor.includes('refresco'))).toBe(false);
    expect(localStorage.length).toBe(0);
  });

  it('el verificador y el estado se borran: un codigo no se canjea dos veces', async () => {
    vueltaBuena();

    await canjearSiVuelve();

    expect(sessionStorage.getItem('kamayuk.pkce.verificador')).toBeNull();
    expect(sessionStorage.getItem('kamayuk.pkce.estado')).toBeNull();
  });

  it('si el estado no cuadra con la ida, no canjea nada', async () => {
    sessionStorage.setItem('kamayuk.pkce.verificador', 'el-verificador');
    sessionStorage.setItem('kamayuk.pkce.estado', 'el-estado');
    ubicacion('http://localhost:5173/?code=un-codigo&state=OTRO');
    const espia = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', espia);

    const vuelta = await canjearSiVuelve();

    // El estado es lo unico que distingue nuestra vuelta de un codigo que alguien nos hizo
    // llegar. Sin comprobarlo, la puerta acepta cualquier codigo.
    expect(vuelta).toMatchObject({ estado: 'fallo', motivo: 'La vuelta no cuadra con la ida' });
    expect(espia).not.toHaveBeenCalled();
    expect(token()).toBeNull();
  });

  it('un ?error= del emisor se explica con su motivo, y no se vuelve a la puerta', async () => {
    ubicacion('http://localhost:5173/?error=access_denied&error_description=lo+cancelo');

    const vuelta = await canjearSiVuelve();

    expect(vuelta).toMatchObject({
      estado: 'fallo',
      motivo: 'No se completo la entrada',
      detalle: 'lo cancelo',
    });
  });

  it('sin code y sin error no ha pasado nada: «sin-vuelta»', async () => {
    ubicacion('http://localhost:5173/#panel');

    await expect(canjearSiVuelve()).resolves.toEqual({ estado: 'sin-vuelta' });
  });

  it('si el emisor no contesta, lo dice en vez de dejar la pagina en blanco', async () => {
    sessionStorage.setItem('kamayuk.pkce.verificador', 'v');
    sessionStorage.setItem('kamayuk.pkce.estado', 'e');
    ubicacion('http://localhost:5173/?code=c&state=e');
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() => Promise.reject(new TypeError('sin red'))),
    );

    await expect(canjearSiVuelve()).resolves.toMatchObject({
      estado: 'fallo',
      motivo: 'El emisor no contesto',
    });
  });
});

describe('AC1 — los dos frenos del rebote', () => {
  it('tres idas y para: un canje que falla siempre seria un bucle infinito', async () => {
    ubicacion();

    expect(puedeIrALaPuerta()).toBe(true);
    await entrar();
    await entrar();
    expect(puedeIrALaPuerta()).toBe(true);
    await entrar();

    expect(puedeIrALaPuerta()).toBe(false);
  });

  it('un canje bueno pone la cuenta a cero: el tope es para una racha, no para el dia', async () => {
    ubicacion();
    await entrar();
    await entrar();
    await entrar();
    expect(puedeIrALaPuerta()).toBe(false);

    sessionStorage.setItem('kamayuk.pkce.verificador', 'v');
    sessionStorage.setItem('kamayuk.pkce.estado', 'e');
    ubicacion('http://localhost:5173/?code=c&state=e');
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() => Promise.resolve(Response.json({ access_token: 'un-token' }))),
    );
    await canjearSiVuelve();

    expect(puedeIrALaPuerta()).toBe(true);
  });

  it('salir deja la marca que impide volver a entrar solo al instante', () => {
    const asignar = ubicacion();
    fijarToken('un-token', 'una-identidad');

    salir();

    expect(token()).toBeNull();
    expect(vieneDeSalir()).toBe(true);
    const destino = new URL(String(asignar.mock.calls[0]?.[0]));
    expect(destino.origin + destino.pathname).toBe(`${REALM}/protocol/openid-connect/logout`);
    // Sin `id_token_hint` el emisor no cierra SU sesion, y el siguiente arranque entraria solo
    // con la misma cuenta sin que nadie teclee nada.
    expect(destino.searchParams.get('id_token_hint')).toBe('una-identidad');
  });

  it('y «olvidarLaParada» levanta los dos frenos, que es el boton de la puerta', async () => {
    ubicacion();
    await entrar();
    await entrar();
    await entrar();
    expect(puedeIrALaPuerta()).toBe(false);

    olvidarLaParada();

    expect(puedeIrALaPuerta()).toBe(true);
    expect(vieneDeSalir()).toBe(false);
  });

  it('salir borra la cuenta de idas, y deja SOLO la marca de salida frenando', () => {
    ubicacion();
    sessionStorage.setItem('kamayuk.pkce.idas', '3');
    fijarToken('un-token');

    salir();

    // Salir no es una racha de fallos: es un gesto deliberado. Dejar el contador a tres haria
    // que la siguiente entrada del turno siguiente se encontrara la puerta cerrada por algo
    // que paso antes de cerrar sesion.
    expect(puedeIrALaPuerta()).toBe(true);
    expect(vieneDeSalir()).toBe(true);
  });
});

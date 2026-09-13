import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { arrancar, fallaDeLaPuerta } from './arranque.ts';
import { fijarToken, token, vieneDeSalir } from './api/identidad.ts';

/**
 * **Lo que tiene que pasar ANTES de que React monte.**
 *
 * `arrancar(montar)` recibe el montaje como argumento en vez de montarse en la linea de abajo,
 * porque hay cosas que no pueden colarse despues y la unica forma de garantizarlo es que el
 * montaje sea lo ultimo que esa funcion hace.
 *
 * Hasta #90 la cosa era instalar el proxy de datos, que salio con la V6 —se quedo sin nada que
 * contestar—; hoy es **el canje del codigo de autorizacion**, y esas pruebas siguen abajo. La
 * forma aguanta el cambio, que es por lo que se eligio.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));

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

/**
 * **Con token puesto**, que desde I-1 es lo que separa «arranca» de «va a la puerta».
 *
 * Sin el, `arrancar()` manda a Keycloak y no monta nada — y eso es correcto, pero convertiria
 * estas pruebas, que son del ORDEN de las dos instalaciones, en pruebas de la identidad. El caso
 * sin token tiene su propio grupo mas abajo.
 */
/**
 * **El emisor contesta la sonda**: el estado normal, en que `entrar()` navega como siempre.
 *
 * Desde #112 `entrar()` pregunta si el emisor esta antes de mandarle el navegador. Sin este doble,
 * las pruebas de este archivo saldrian a `localhost:8181` de verdad y la sonda diria —con razon—
 * que no hay nadie, con lo que estarian midiendo el camino de error creyendo medir el bueno.
 */
function elEmisorContesta() {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(() => Promise.resolve(new Response(null, { status: 200 }))),
  );
}

/** Y el otro camino: la navegacion no llega a ocurrir. Ver `#112`. */
function elEmisorNoContesta() {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(() => Promise.reject(new TypeError('Failed to fetch'))),
  );
}

beforeEach(() => {
  sessionStorage.clear();
  ubicacion();
  fijarToken('un-token-de-prueba');
  elEmisorContesta();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  fijarToken(null);
  sessionStorage.clear();
});

describe('main.tsx monta dentro de arrancar', () => {
  const fuente = readFileSync(join(AQUI, 'main.tsx'), 'utf8');

  it('el createRoot esta dentro del argumento de arrancar, no despues', () => {
    const dentro = /arrancar\(\(\) => \{[\s\S]*createRoot\(raiz\)[\s\S]*\}\);/.test(fuente);

    expect(
      dentro,
      'main.tsx monta React fuera de «arrancar». El orden es el criterio: hoy el canje del\n' +
        'codigo de autorizacion ocurriria DESPUES del montaje, y la primera peticion de la\n' +
        'primera pantalla saldria sin token.',
    ).toBe(true);
  });
});

/**
 * **La ida a la puerta la decide el arranque, no la pantalla** (I-1).
 *
 * Sin token no hay nada que ensenar, asi que se va a la puerta en vez de montar la aplicacion
 * para que ella descubra el 401. La diferencia se ve: con la sesion de Keycloak viva, ir a la
 * puerta va y vuelve sin dibujar nada; montar primero enseñaria un error de identidad **a
 * alguien que si esta identificado**, durante el tiempo que tarda la ida.
 */
describe('la puerta de identidad, en el arranque', () => {
  it('sin token va a la puerta y NO monta: montar seria dibujar sobre un documento que se va', async () => {
    fijarToken(null);
    const asignar = ubicacion();
    elEmisorContesta();
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    expect(monto).toBe(false);
    expect(String(asignar.mock.calls[0]?.[0])).toContain('/protocol/openid-connect/auth');
  });

  it('con token no va a ninguna parte: monta', async () => {
    const asignar = ubicacion();
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    expect(monto).toBe(true);
    expect(asignar).not.toHaveBeenCalled();
  });

  it('si volvemos con un codigo, lo canjea ANTES de montar', async () => {
    fijarToken(null);
    sessionStorage.setItem('kamayuk.pkce.verificador', 'v');
    sessionStorage.setItem('kamayuk.pkce.estado', 'e');
    ubicacion('http://localhost:5173/?code=c&state=e');
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() => Promise.resolve(Response.json({ access_token: 'el-canjeado' }))),
    );
    let tokenAlMontar: string | null = null;

    await arrancar(() => {
      tokenAlMontar = token();
    });

    // La primera peticion de la primera pantalla es `GET /seguridad/sesion`. Si el canje
    // ocurriera despues del montaje, esa peticion saldria sin token y contestaria 401 — un
    // peldano de identidad ensenado a quien acaba de identificarse.
    expect(tokenAlMontar).toBe('el-canjeado');
  });

  it('recien salido NO vuelve a entrar solo: monta, y la pantalla explica el 401', async () => {
    fijarToken(null);
    const asignar = ubicacion();
    sessionStorage.setItem('kamayuk.pkce.salida', '1');
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    // `post_logout_redirect_uri` trae de vuelta sin token. Sin esta marca, quien acaba de cerrar
    // sesion se encontraria DENTRO OTRA VEZ con la misma cuenta sin haber hecho nada.
    expect(vieneDeSalir()).toBe(true);
    expect(asignar).not.toHaveBeenCalled();
    expect(monto).toBe(true);
  });

  it('y con el tope de idas agotado tampoco: monta en vez de rebotar sin fin', async () => {
    fijarToken(null);
    const asignar = ubicacion();
    sessionStorage.setItem('kamayuk.pkce.idas', '3');
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    expect(asignar).not.toHaveBeenCalled();
    expect(monto).toBe(true);
  });
});

/**
 * **Sin emisor levantado, `yarn dev` dejaba la pagina EN BLANCO y la consola limpia** (#112).
 *
 * <h2>Las DOS ramas, y por que no vale medir solo una</h2>
 *
 * · La puerta **no** contesta: la navegacion no llega a ocurrir, asi que no hay documento nuevo
 *   *ni* aplicacion. Hay que montar y explicarse.
 * · La puerta **si** contesta: `entrar()` navega fuera, y montar dibujaria sobre un documento que
 *   el navegador esta a punto de tirar — un parpadeo. Eso NO cambia.
 *
 * Con solo la primera, un arreglo que montara siempre pasaria en verde y estropearia el camino
 * bueno; con solo la segunda, el defecto seguiria ahi.
 */
describe('#112 — la puerta que no contesta monta y se explica; la que si, no', () => {
  it('la puerta NO contesta: monta, y dice que emisor fallo y en que URL', async () => {
    fijarToken(null);
    const asignar = ubicacion();
    elEmisorNoContesta();
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    // Sin esto queda la pagina de antes, vacia: `body.innerText` vacio, `body.innerHTML` vacio y
    // la consola con dos lineas de Vite. Ni un error en pantalla ni en consola.
    expect(monto, 'no monto nada: la pagina se queda en blanco').toBe(true);
    expect(asignar, 'se creyo que habia navegado').not.toHaveBeenCalled();
    const falla = fallaDeLaPuerta();
    expect(falla?.emisor).toBe('http://localhost:8181/realms/kamayuk');
    expect(falla?.url).toContain('/.well-known/openid-configuration');
    expect(falla?.motivo).toBe('Failed to fetch');
  });

  it('la puerta SI contesta: sigue sin montar, y sin falla que contar', async () => {
    fijarToken(null);
    const asignar = ubicacion();
    elEmisorContesta();
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    expect(monto, 'dibujo sobre un documento que el navegador se va a llevar').toBe(false);
    expect(String(asignar.mock.calls[0]?.[0])).toContain('/protocol/openid-connect/auth');
    expect(fallaDeLaPuerta()).toBeNull();
  });

  it('y la falla no sobrevive a la pasada siguiente: cada arranque la vuelve a fijar', async () => {
    fijarToken(null);
    ubicacion();
    elEmisorNoContesta();
    await arrancar(() => {});
    expect(fallaDeLaPuerta()).not.toBeNull();

    // Con el emisor de vuelta, la pantalla de error de la pasada anterior no puede quedarse
    // pegada: seria un error que ya no existe ensenado sobre una aplicacion que si arranco.
    fijarToken('un-token-de-prueba');
    elEmisorContesta();
    await arrancar(() => {});

    expect(fallaDeLaPuerta()).toBeNull();
  });
});

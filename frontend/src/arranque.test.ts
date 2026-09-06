import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { arrancar } from './arranque.ts';
import { fijarToken, token, vieneDeSalir } from './api/identidad.ts';
import { desinstalarProxyDeDatos, proxyDeDatosInstalado } from './api/proxy.ts';

/**
 * El proxy se instala ANTES de montar React (AC2).
 *
 * Una pantalla pide sus datos en su primer efecto. Si el proxy se instalara despues del
 * montaje, esa primera peticion saldria al `fetch` de verdad — y en desarrollo la atenderia el
 * servidor de Vite, que devuelve el `index.html` con un `200`: no un error, una pagina HTML
 * donde la pantalla espera JSON. El fallo mas caro de leer que hay.
 *
 * Por eso el montaje entra a `arrancar()` como argumento: no se puede montar antes de
 * instalar. Aqui se comprueba mirando en que orden ocurren las dos cosas, con el proxy de
 * verdad y no con un doble.
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
beforeEach(() => {
  sessionStorage.clear();
  ubicacion();
  fijarToken('un-token-de-prueba');
});

afterEach(() => {
  desinstalarProxyDeDatos();
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  fijarToken(null);
  sessionStorage.clear();
});

describe('con el proxy encendido', () => {
  it('lo instala, y solo entonces monta', async () => {
    vi.stubEnv('VITE_KAMAYUK_PROXY_DE_DATOS', 'true');
    let instaladoAlMontar: boolean | null = null;

    await arrancar(() => {
      instaladoAlMontar = proxyDeDatosInstalado();
    });

    expect(instaladoAlMontar, 'la aplicacion se monto sin nadie que conteste').toBe(true);
    expect(proxyDeDatosInstalado()).toBe(true);
  });
});

describe('con el proxy apagado', () => {
  it.each([
    ['la bandera dice que no', 'false'],
    ['la bandera trae cualquier otra cosa', 'si'],
  ])('monta igual y no instala nada — %s', async (_que, valor) => {
    vi.stubEnv('VITE_KAMAYUK_PROXY_DE_DATOS', valor);
    let monto = false;

    await arrancar(() => {
      monto = true;
    });

    expect(monto).toBe(true);
    expect(proxyDeDatosInstalado()).toBe(false);
  });

  it('sin bandera tampoco: es opt-in, no opt-out', async () => {
    vi.stubEnv('VITE_KAMAYUK_PROXY_DE_DATOS', undefined);

    await arrancar(() => {});

    // Lo que decide si un despliegue lleva datos inventados no puede ser que alguien se
    // acuerde de apagarlos.
    expect(proxyDeDatosInstalado()).toBe(false);
  });
});

describe('la bandera sale de import.meta.env, que es lo que saca los datos del bundle (AC3)', () => {
  const fuente = readFileSync(join(AQUI, 'arranque.ts'), 'utf8');

  it('la decision se toma sobre un valor que Vite sustituye al construir', () => {
    // Lo que hace cierto el AC3 es de DONDE sale el valor, y esta medido: envolver la
    // comparacion en una funcion sobre `import.meta.env` da el mismo bundle al byte —Rollup
    // la inlinea y pliega igual—, y leerla en tiempo de ejecucion mete el trozo del proxy,
    // con las cifras del artboard dentro, en un build con la bandera apagada (227 205 bytes
    // frente a 193 592). Asi que lo que se afirma es la fuente del valor, no su sintaxis.
    expect(fuente).toContain('import.meta.env.VITE_KAMAYUK_PROXY_DE_DATOS');
  });

  it('y el proxy entra por import() dinamico, no por un import estatico', () => {
    expect(fuente).toMatch(/await import\('\.\/api\/proxy\.ts'\)/);
    expect(fuente).not.toMatch(/^import .* from '\.\/api\/proxy\.ts';$/m);
  });
});

describe('main.tsx monta dentro de arrancar', () => {
  const fuente = readFileSync(join(AQUI, 'main.tsx'), 'utf8');

  it('el createRoot esta dentro del argumento de arrancar, no despues', () => {
    const dentro = /arrancar\(\(\) => \{[\s\S]*createRoot\(raiz\)[\s\S]*\}\);/.test(fuente);

    expect(
      dentro,
      'main.tsx monta React fuera de «arrancar». El orden es el criterio: la primera\n' +
        'pantalla pediria datos antes de que hubiera quien contestara.',
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

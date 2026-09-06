import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { arrancar } from './arranque.ts';
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

afterEach(() => {
  desinstalarProxyDeDatos();
  vi.unstubAllEnvs();
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

// @vitest-environment node
//
// Abre sockets de verdad y lee el config del disco: no es un DOM lo que necesita.

import { readFileSync } from 'node:fs';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterEach, describe, expect, it } from 'vitest';

import {
  PUERTO,
  URL_DEL_ARNES,
  VARIABLE,
  elegirElPuerto,
  estaOcupado,
  puertoDerivadoDe,
} from '../puerto-del-arnes.mjs';

/**
 * **El puerto del arnes sale del arbol de trabajo, y si esta ocupado se dice** (#148).
 *
 * <h2>Que se rompio para saber que esto muerde</h2>
 *
 * El defecto original: `playwright.config.ts` escribia el 4173 en tres sitios. Con seis
 * worktrees a la vez, `yarn e2e` de una rama media el bundle de otra —paso al cerrar #140, y
 * dio `Received: 0`—. Las pruebas de aqui son las que se ponen rojas si el puerto vuelve a
 * ser una constante, si deja de depender del arbol, o si la comprobacion de ocupacion mira
 * una sola cara de `localhost`.
 *
 * <h2>Por que hay una prueba que abre sockets</h2>
 *
 * Porque el modo de fallo que se midio es exactamente ese: `vite preview` escucha en `[::1]`
 * y NO en `127.0.0.1`, asi que una comprobacion que solo mirase la cara IPv4 diria «libre» de
 * un puerto que no lo esta — y el arnes volveria a construir dos minutos para nada. Eso no lo
 * puede decir una prueba que no abra un socket.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const CONFIG = readFileSync(join(AQUI, '..', 'playwright.config.ts'), 'utf8');

/**
 * El config **sin su prosa**, que es sobre lo que se mide.
 *
 * Los comentarios SI nombran el 4173, y tienen que poder: ahi esta escrito de donde viene el
 * defecto de #140 y por que el puerto dejo de ser una constante. Borrar esa historia para que
 * una guarda pase de largo seria falsificarla. Es el mismo criterio que
 * `sin-el-nombre-del-monolito.test.ts` en `infrastructure`: se barre el codigo, no la prosa.
 *
 * Un comentario de linea solo se quita cuando abre la linea: asi, un `'http://localhost:4173/'`
 * que volviera al codigo NO se lo comeria el filtro — que es justo lo que hay que cazar.
 */
function codigoDe(fuente: string): string {
  return fuente
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .split('\n')
    .filter((linea) => !linea.trimStart().startsWith('//'))
    .join('\n');
}

const CODIGO = codigoDe(CONFIG);

/** Rutas de arbol de mentira, con la forma de las de verdad. */
const ARBOLES = [
  '/home/jorge/ws/rentas/frontend',
  '/home/jorge/ws/rentas-136/frontend',
  '/home/jorge/ws/rentas-137/frontend',
  '/home/jorge/ws/rentas-138/frontend',
  '/home/jorge/ws/rentas-143/frontend',
  '/home/jorge/ws/rentas-148/frontend',
];

const abiertos: ReturnType<typeof createServer>[] = [];

afterEach(async () => {
  await Promise.all(
    abiertos.splice(0).map((servidor) => new Promise((listo) => servidor.close(listo))),
  );
});

/** Abre un servidor en una sola cara de localhost y devuelve el puerto que le toco. */
async function servidorEn(host: string): Promise<number> {
  const servidor = createServer();
  abiertos.push(servidor);
  return await new Promise((listo) => {
    servidor.listen(0, host, () => {
      const direccion = servidor.address();
      listo(typeof direccion === 'object' && direccion !== null ? direccion.port : 0);
    });
  });
}

describe('el puerto del arnes', () => {
  it('EL CENTINELA: `playwright.config.ts` no escribe NINGUN puerto a mano', () => {
    // Esta es la prueba que se pone roja el dia que alguien vuelva a fijar el 4173 —o
    // cualquier otro— en el config. No comprueba un numero: comprueba que no haya numero.
    expect(CODIGO).not.toMatch(/localhost:\d/);
    expect(CODIGO).not.toMatch(/--port \d/);
    // El `4173` de CI vive en `puerto-del-arnes.mjs` con su motivo al lado, no aqui.
    expect(CODIGO).not.toMatch(/\d{4}/);
    // Y el filtro de prosa no puede ser la razon de que pase: el codigo sigue ahi entero.
    expect(CODIGO).toContain('defineConfig({');
    expect(CODIGO).toContain('URL_DEL_ARNES');
  });

  it('y `yarn e2e` lo comprueba ANTES de arrancar Playwright', () => {
    // El orden es la mitad de la guarda. Medido con un intruso en el puerto derivado: puesta
    // dentro de `webServer.command`, la comprobacion llega tarde —Playwright ya ha dicho lo
    // suyo, «… is already used», que no nombra ni el proceso ni el directorio—.
    const guion = JSON.parse(readFileSync(join(AQUI, '..', 'package.json'), 'utf8')) as {
      scripts: Record<string, string>;
    };
    const e2e = guion.scripts['e2e'] ?? '';
    expect(e2e.indexOf('node puerto-del-arnes.mjs')).toBe(0);
    expect(e2e.indexOf('node puerto-del-arnes.mjs')).toBeLessThan(e2e.indexOf('playwright test'));
  });

  it('`--strictPort` SE QUEDA: sin el, Vite se mueve y el `baseURL` no', () => {
    expect(CODIGO).toContain('--strictPort');
  });

  it('el de dos arboles distintos es distinto', () => {
    const puertos = ARBOLES.map(puertoDerivadoDe);
    expect(new Set(puertos).size).toBe(ARBOLES.length);
  });

  it('y el del mismo arbol es el mismo siempre: es lo que se puede nombrar al depurar', () => {
    // Un puerto libre de verdad daria otro en cada corrida —medido: diez sondeos, diez
    // puertos—, y entonces un `vite preview` colgado de ayer no estorbaria a nadie nunca,
    // que es como se acumulan tres en la misma maquina sin que nadie se entere.
    for (const arbol of ARBOLES) expect(puertoDerivadoDe(arbol)).toBe(puertoDerivadoDe(arbol));
  });

  it('cae entre los dos puertos de Vite, sin pisar ninguno de los dos', () => {
    for (const arbol of [...ARBOLES, AQUI]) {
      const puerto = puertoDerivadoDe(arbol);
      expect(puerto).toBeGreaterThan(4173); // `vite preview`, el de CI
      expect(puerto).toBeLessThan(5173); // `yarn dev`
    }
  });

  it('en CI es el de siempre, porque alli solo hay un arbol', () => {
    expect(elegirElPuerto({ raiz: '/home/runner/work/rentas/rentas/frontend', ci: true })).toEqual({
      puerto: 4173,
      motivo: expect.stringContaining('CI'),
    });
  });

  it('fuera de CI NUNCA es el de CI, aunque el arbol este en la misma ruta', () => {
    expect(
      elegirElPuerto({ raiz: '/home/runner/work/rentas/rentas/frontend', ci: false }).puerto,
    ).not.toBe(4173);
  });

  it(`«${VARIABLE}» manda sobre los dos, que es la salida cuando el derivado esta ocupado`, () => {
    expect(elegirElPuerto({ raiz: ARBOLES[0]!, ci: true, pedido: '4444' }).puerto).toBe(4444);
    expect(elegirElPuerto({ raiz: ARBOLES[0]!, ci: false, pedido: '4444' }).puerto).toBe(4444);
    // Y una cadena vacia no es una peticion: `KAMAYUK_E2E_PUERTO=` no puede dar el puerto 0.
    expect(elegirElPuerto({ raiz: ARBOLES[0]!, ci: false, pedido: '' }).puerto).toBe(
      puertoDerivadoDe(ARBOLES[0]!),
    );
  });

  it('y si lo que se pide no es un puerto, se dice en vez de servir en el 0', () => {
    expect(() => elegirElPuerto({ raiz: ARBOLES[0]!, ci: false, pedido: 'el que sea' })).toThrow(
      /no es un puerto/,
    );
    expect(() => elegirElPuerto({ raiz: ARBOLES[0]!, ci: false, pedido: '70000' })).toThrow(
      /no es un puerto/,
    );
  });

  it('el de ESTE arbol es el que lleva la URL con la que se mide', () => {
    expect(URL_DEL_ARNES).toBe(`http://localhost:${PUERTO}/rentas/`);
    // `/rentas/` es la `base` de `vite.config.ts`: con otra, `preview` contesta 404 en la raiz.
    const base = readFileSync(join(AQUI, '..', 'vite.config.ts'), 'utf8').match(/base: '([^']+)'/);
    expect(URL_DEL_ARNES).toContain(base?.[1] ?? 'NO SE ENCONTRO LA BASE EN vite.config.ts');
  });
});

describe('la comprobacion de que el puerto esta libre', () => {
  it('ve un servidor que solo escucha en `[::1]`, que es como escucha `vite preview`', async () => {
    // ESTE es el rojo que importa: mirando solo 127.0.0.1, `estaOcupado` devuelve `false` de
    // un puerto en el que `vite preview` no va a poder abrir. Medido con `ss -ltnp`: los tres
    // `preview` vivos de esta maquina estaban en `[::1]`, ninguno en `127.0.0.1`.
    expect(await estaOcupado(await servidorEn('::1'))).toBe(true);
  });

  it('y uno que solo escucha en `127.0.0.1`', async () => {
    expect(await estaOcupado(await servidorEn('127.0.0.1'))).toBe(true);
  });

  it('y no ve lo que no hay', async () => {
    const servidor = createServer();
    const puerto = await new Promise<number>((listo) => {
      servidor.listen(0, '127.0.0.1', () => {
        const direccion = servidor.address();
        listo(typeof direccion === 'object' && direccion !== null ? direccion.port : 0);
      });
    });
    await new Promise((cerrado) => servidor.close(cerrado));
    expect(await estaOcupado(puerto)).toBe(false);
  });
});

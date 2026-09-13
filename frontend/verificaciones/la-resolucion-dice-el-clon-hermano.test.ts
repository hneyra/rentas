// @vitest-environment node
//
// Mira el DISCO —la raiz de verdad— y finge un `require` que no encuentra nada. No es un DOM lo
// que necesita, y por el mismo motivo que sus dos vecinas de este directorio.

import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { enlacesDeclarados, loQuePideElEnlace } from './enlace.ts';

/**
 * **El mensaje que nombra el `git clone` llega a salir** (#113).
 *
 * <h2>Que se habia roto, y por que nadie lo vio</h2>
 *
 * `resolucion.ts` termina con un `throw` escrito para el clon hermano ausente, con su `git clone`
 * dentro. Y era **inalcanzable justo en ese caso**: `require.resolve('@kamayuk/api')` reventaba
 * doce lineas antes, en la primera vuelta del bucle. Lo medido, con `node_modules/@kamayuk/`
 * vacio —que es exactamente lo que deja `yarn install` sin el hermano, en verde y sin avisar—:
 *
 *     failed to load config from …/frontend/vite.config.ts
 *     error when starting dev server:
 *     Error: Cannot find module '@kamayuk/api'
 *     Require stack:
 *     - …/frontend/resolucion.ts
 *
 * Ni el hermano, ni la ruta, ni el comando. Y este es **el primer paso que toca el enlace**:
 * `yarn dev` y `yarn build` cargan `vite.config.ts`, que carga `resolucion.ts`. La guarda que si
 * lo explica —`enlace-con-kamayuk-lib.test.ts`— vive en `yarn verificar`, que es el SIGUIENTE.
 *
 * <h2>Por que esta prueba no importa `resolucion.ts`</h2>
 *
 * Porque su constante se evalua al cargar el modulo: sin el hermano, importarlo aqui mataria
 * este archivo en la recoleccion y la guarda se callaria **justo cuando tiene que hablar**. Es la
 * leccion que ya costo una vuelta en `enlace-con-kamayuk-lib.test.ts`, y por eso lo que se
 * ejercita es la funcion —`loQuePideElEnlace`— mas un escaneo del texto de `resolucion.ts`.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');

/**
 * Una raiz de frontend que no existe, con la disposicion de la de verdad: `…/rentas/frontend`,
 * para que `../../kamayuk-lib` caiga al lado de «rentas» y no en cualquier sitio.
 */
const RAIZ_SIN_HERMANO = join(tmpdir(), 'kamayuk-ws-de-mentira', 'rentas', 'frontend');

/** Y ahi es donde el `link:` dice que tiene que estar el hermano. */
const CLON_ESPERADO = join(tmpdir(), 'kamayuk-ws-de-mentira', 'kamayuk-lib');

/** El `require` de un clon recien hecho, sin el hermano al lado. Dice lo que decia el de verdad. */
const REQUERIDOR_CIEGO = {
  resolve(peticion: string): string {
    throw new Error(`Cannot find module '${peticion}'`);
  },
};

/** Lo que falla, como texto. `expect(...).toThrow()` no deja leer el mensaje entero. */
function rojoDe(raiz: string, paquete: string, declarada: string): string {
  try {
    loQuePideElEnlace(REQUERIDOR_CIEGO, { paquete, declarada }, raiz);
  } catch (fallo) {
    return (fallo as Error).message;
  }
  throw new Error(`«${paquete}» resolvio con un requeridor que no resuelve nada.`);
}

describe('LA MUESTRA: sin el clon hermano, el rojo dice donde mirar', () => {
  const rojo = rojoDe(RAIZ_SIN_HERMANO, '@kamayuk/api', '../../kamayuk-lib/paquetes/api');

  it('nombra el paquete que no resolvio', () => {
    // Son CINCO enlaces: «alguno de `@kamayuk/*`» obliga a probarlos a mano.
    expect(rojo).toContain('@kamayuk/api');
  });

  it('nombra la ruta exacta donde se espera el clon', () => {
    expect(rojo).toContain(CLON_ESPERADO);
    expect(rojo).toContain(join(CLON_ESPERADO, 'paquetes', 'api'));
  });

  it('y nombra el `git clone` que lo pone ahi', () => {
    // Lo unico que distingue este rojo del que salia antes —«Cannot find module '@kamayuk/api'»,
    // dentro de un archivo de configuracion de Vite— es que este se puede obedecer.
    expect(rojo).toContain('git clone https://github.com/hneyra/kamayuk-lib ../../kamayuk-lib');
  });

  it('cada uno de los seis enlaces declarados se nombra a si mismo', () => {
    const enlaces = enlacesDeclarados(readFileSync(join(FRONTEND, 'package.json'), 'utf8'));
    expect(enlaces.length).toBeGreaterThanOrEqual(5);
    for (const { paquete, declarada } of enlaces) {
      expect(rojoDe(RAIZ_SIN_HERMANO, paquete, declarada)).toContain(`«${paquete}»`);
    }
  });
});

describe('y si el clon ESTA, el remedio es otro y no miente', () => {
  it('manda a `yarn install`, no a clonar lo que ya esta clonado', () => {
    // Este es el caso de la rotura con que se demostro: `node_modules/@kamayuk/` apartado con el
    // hermano en su sitio. Mandar a clonar lo que ya esta seria un consejo falso, y quien lo
    // siguiera veria a git contestar «already exists and is not an empty directory».
    const rojo = rojoDe(FRONTEND, '@kamayuk/api', '../../kamayuk-lib/paquetes/api');

    expect(rojo).toContain('yarn install');
    expect(rojo).not.toContain('git clone');
  });
});

describe('LA GUARDA DE LA GUARDA: el `resolve` de `resolucion.ts` sigue envuelto', () => {
  const fuente = readFileSync(join(FRONTEND, 'resolucion.ts'), 'utf8');

  it('no hay ni un `require.resolve` a pelo', () => {
    const sueltos = fuente
      .split('\n')
      .filter((linea) => /\brequerir\.resolve\(/.test(linea) && !linea.trim().startsWith('*'));

    expect(
      sueltos,
      'Un `resolve` sin envolver vuelve a reventar antes del mensaje que nombra el `git clone`,\n' +
        'y el rojo vuelve a ser «Cannot find module» dentro de `vite.config.ts` (#113).',
    ).toEqual([]);
  });

  it('y lo que usa es la funcion que compone el mensaje', () => {
    expect(fuente).toContain('loQuePideElEnlace');
  });
});

describe('con el hermano en su sitio, resuelve', () => {
  it('los seis enlaces se resuelven, y los que piden algo lo piden de verdad', () => {
    // Si esto sale rojo, el hermano falta — y el rojo que se lea aqui ES el mensaje de arriba,
    // que es el asunto entero de este archivo.
    const requerir = createRequire(import.meta.url);
    const enlaces = enlacesDeclarados(readFileSync(join(FRONTEND, 'package.json'), 'utf8'));
    const pedidos = enlaces.flatMap((enlace) =>
      Object.keys(loQuePideElEnlace(requerir, enlace, FRONTEND)),
    );

    expect(new Set(pedidos).size).toBeGreaterThanOrEqual(9);
  });
});

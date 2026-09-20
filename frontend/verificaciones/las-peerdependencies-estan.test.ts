// @vitest-environment node
//
// Lee `package.json` del disco, aqui y en los paquetes enlazados.

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { enlacesDeclarados, loQuePideElEnlace } from './enlace.ts';

/**
 * **Lo que `@kamayuk/*` pide por `peerDependencies`, este frontend lo TIENE** (#88).
 *
 * <h2>El defecto que esto habria cazado, y que se descubrio a mano</h2>
 *
 * `@kamayuk/ui` se mezclo tres veces —#6, #8, #11— declarando `peerDependencies` que su unico
 * consumidor **nunca instalo**: `radix-ui`, `react-hook-form`, `react-day-picker`, `clsx`,
 * `tailwind-merge`, `class-variance-authority` y `tailwindcss`. Siete. Y la CI de los dos
 * repositorios estuvo **en verde todo el tiempo**, porque hasta #88 nada de aqui importaba una
 * pieza de la libreria en tiempo de ejecucion: el enlace resolvia, los tipos compilaban, y nadie
 * llegaba nunca a la linea que pide el paquete que falta.
 *
 * Cuando por fin se importo, el rojo **no menciono ninguna dependencia**:
 *
 *     Cannot read properties of null (reading 'useId')
 *
 * ...porque lo que pasaba es que la pieza cargaba React del arbol del hermano. Del paquete que
 * faltaba, ni una palabra.
 *
 * <h2>Por que la version tambien importa, y no solo la presencia</h2>
 *
 * Porque dos copias de una libreria con estado —React, y cualquiera que lleve contexto— se
 * comportan como dos librerias distintas. Aqui se comprueba que **la que este frontend declara
 * satisface el rango que la libreria pide**; que ademas se resuelva a UNA sola copia es lo que
 * hace `resolve.dedupe` de `vite.config.ts`, con su motivo escrito alli.
 */

const requerir = createRequire(import.meta.url);
const RAIZ = fileURLToPath(new URL('..', import.meta.url));

// **Contra `RAIZ`, y no contra el `cwd`** (#295). Era `readFileSync('package.json')`, con la
// constante de arriba ya calculada dos lineas antes y sin usar para esto. Funcionaba porque
// `yarn test` se lanza desde `frontend/`; medido con el `cwd` un nivel mas arriba
// —`vitest run --root frontend` desde la raiz del repositorio—, el archivo entero caia en la
// RECOLECCION con `ENOENT: no such file or directory, open 'package.json'`. Y en una raiz que SI
// tuviera un `package.json` —la del repositorio, la de un monorepo— habria leido el manifiesto
// equivocado y comparado versiones que no son las de este frontend, en verde.
const CRUDO = readFileSync(join(RAIZ, 'package.json'), 'utf8');
const ENLACES = enlacesDeclarados(CRUDO);

const mio = JSON.parse(CRUDO) as {
  dependencies?: Record<string, string>;
  devDependencies?: Record<string, string>;
};
const MIAS = { ...mio.dependencies, ...mio.devDependencies };

interface Peticion {
  readonly paquete: string;
  readonly pide: string;
  readonly rango: string;
}

// El `resolve` va envuelto —`loQuePideElEnlace`— y no suelto: sin el clon hermano, esto corre en
// la RECOLECCION, o sea que el rojo que salga aqui se lleva por delante el archivo entero. Que
// diga el `git clone` en vez de «Cannot find module» cuesta lo mismo (#113).
const PETICIONES: readonly Peticion[] = ENLACES.flatMap((enlace) =>
  Object.entries(loQuePideElEnlace(requerir, enlace, RAIZ)).map(([pide, rango]) => ({
    paquete: enlace.paquete,
    pide,
    rango,
  })),
);

/** `^1.6.7` / `>=19` -> el numero que hay que alcanzar. Basta para comparar mayores. */
const mayorDe = (version: string): number => Number(/(\d+)/.exec(version)?.[1] ?? '0');

describe('las peerDependencies de `@kamayuk/*` estan instaladas aqui', () => {
  it('EL CENTINELA: los paquetes enlazados piden algo', () => {
    // Sin esto, un `package.json` que dejara de declarar `peerDependencies` —o un enlace roto que
    // devolviera un objeto vacio— dejaria la comprobacion de abajo recorriendo la lista vacia y
    // pasando en verde. La libreria pide nueve solo en `@kamayuk/ui`.
    expect(PETICIONES.length, 'ningun paquete enlazado pidio nada').toBeGreaterThanOrEqual(9);
  });

  it('no falta ninguna', () => {
    const ausentes = PETICIONES.filter((p) => MIAS[p.pide] === undefined).map(
      (p) => `  ${p.paquete} pide «${p.pide}» (${p.rango}) y este frontend no lo declara`,
    );
    expect(
      ausentes,
      'Faltan dependencias que los paquetes enlazados dan por puestas:\n' +
        `${ausentes.join('\n')}\n\n` +
        '  Con `link:` no hay instalador que las traiga: `peerDependencies` significa que las\n' +
        '  pone el consumidor. Y el rojo que sale cuando faltan no las nombra.',
    ).toEqual([]);
  });

  it('y la version que hay alcanza a la que se pide', () => {
    const cortas = PETICIONES.filter((p) => {
      const mia = MIAS[p.pide];
      return mia !== undefined && mayorDe(mia) < mayorDe(p.rango);
    }).map((p) => `  ${p.pide}: aqui «${MIAS[p.pide] ?? ''}» y ${p.paquete} pide «${p.rango}»`);
    expect(
      cortas,
      `Hay dependencias por debajo de lo que la libreria pide:\n${cortas.join('\n')}`,
    ).toEqual([]);
  });
});

// @vitest-environment node
//
// Lee el DISCO y escribe raices de mentira en un temporal: no es un DOM lo que necesita. Es la
// segunda de este directorio con esta linea, y por el mismo motivo que `camino-a-la-api.test.ts`.

import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { afterAll, describe, expect, it } from 'vitest';

import { enlacesDeclarados, problemasDelEnlace } from './enlace.ts';

/**
 * **El primer clon hermano del frontend** (#74).
 *
 * Hasta aqui, `frontend.yml` decia por escrito que no necesitaba ninguno: «el frontend todavia no
 * depende de ningun paquete `@kamayuk/*` publicado […] el dia que dependa, este archivo se
 * parecera a aquel». Este es ese dia.
 *
 * <h2>Las dos mitades, y por que hacen falta las dos</h2>
 *
 * 1. **Que el enlace este bien puesto**, y que si no lo esta se diga NOMBRANDO EL `git clone`.
 *    Medido: `yarn install --frozen-lockfile` con el hermano ausente sale con **codigo 0** y no
 *    enlaza nada; ni `--check-files` lo caza. El rojo llega dos pasos despues como «Cannot find
 *    module», que manda a buscar el defecto en el codigo de este repositorio.
 * 2. **Que resuelva de verdad**, importando de los tres paquetes y ejecutandolos. Un `link:` que
 *    nadie importa no demuestra que el enlace funcione: demuestra que yarn escribio un symlink.
 *
 * <h2>Lo que NO se hacia aqui, y ya se puede</h2>
 *
 * Este archivo nacio con `@kamayuk/*` prohibido en `src/`: el destino del `link:` quedaba fuera
 * del contexto de Docker, asi que un import de produccion habria roto la imagen **en `main`, con
 * este flujo en verde**. **#75 lo cerro**: el `Dockerfile` alcanza al clon hermano por un
 * contexto con nombre de BuildKit, la imagen se construye ademas en cada PR, y tres guardas
 * nuevas de `imagen-y-despliegue.test.ts` atan los cuatro sitios que tienen que decir lo mismo.
 *
 * O sea que la reimplantacion sobre RentasV8 ya puede importar desde `src/`.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');

const temporales: string[] = [];

/** Una raiz de frontend de mentira, con el `package.json` que se le diga. */
function raizDeMentira(dependencias: Record<string, string>): string {
  const raiz = mkdtempSync(join(tmpdir(), 'kamayuk-enlace-'));
  temporales.push(raiz);
  writeFileSync(
    join(raiz, 'package.json'),
    JSON.stringify({ name: 'de-mentira', dependencies: dependencias }),
  );
  return raiz;
}

afterAll(() => {
  for (const raiz of temporales) rmSync(raiz, { recursive: true, force: true });
});

describe('el enlace con el clon hermano esta bien puesto', () => {
  it('EL CENTINELA: hay enlaces declarados que comprobar', () => {
    // Sin esto, lo de abajo pasaria sobre la lista vacia el dia que alguien quite los `link:`
    // — que es como una guarda se queda sin sujeto y sigue en verde.
    const enlaces = enlacesDeclarados(readFileSync(join(FRONTEND, 'package.json'), 'utf8'));
    // La lista se escribe entera, no se cuenta: anadir un paquete exige decir cual, y este
    // rojo —que es el que salio al enlazar `@kamayuk/ui`— es la unica senal de que la
    // superficie de dependencia del frontend acaba de crecer.
    expect(enlaces.map((e) => e.paquete).sort()).toEqual([
      '@kamayuk/api',
      '@kamayuk/formato',
      '@kamayuk/sesion',
      '@kamayuk/shell',
      '@kamayuk/ui',
    ]);
    // Y todos apuntan al mismo clon hermano: tres rutas a tres sitios distintos serian tres
    // dependencias que mantener, no una.
    expect(new Set(enlaces.map((e) => e.declarada.split('/paquetes/')[0]))).toEqual(
      new Set(['../../kamayuk-lib']),
    );
  });

  it('y no hay ni un problema en el arbol de verdad', () => {
    const problemas = problemasDelEnlace(FRONTEND);
    const detalle = problemas.map((p) => `  ${p.paquete}: ${p.que}\n  ${p.remedio}`).join('\n\n');
    expect(problemas, detalle).toEqual([]);
  });
});

describe('LA MUESTRA: la guarda muerde, y dice el `git clone`', () => {
  it('si el clon hermano no esta, lo dice — y no «Cannot find module»', () => {
    const raiz = raizDeMentira({ '@kamayuk/formato': 'link:../../kamayuk-lib/paquetes/formato' });

    const problemas = problemasDelEnlace(raiz);

    expect(problemas).toHaveLength(1);
    expect(problemas[0]?.que).toContain('no existe');
    // Lo que de verdad importa: el remedio NOMBRA el clon y el comando. Es lo unico que
    // distingue este rojo del que ya sale solo dos pasos mas tarde sin decir nada.
    expect(problemas[0]?.remedio).toContain('git clone https://github.com/hneyra/kamayuk-lib');
    expect(problemas[0]?.remedio).toContain('se instala con codigo 0 y sin avisar');
  });

  it('si apunta a un directorio que no es un paquete, tambien', () => {
    const raiz = raizDeMentira({ '@kamayuk/formato': 'link:./vacio' });
    mkdirSync(join(raiz, 'vacio'));

    const problemas = problemasDelEnlace(raiz);

    expect(problemas[0]?.que).toContain('no tiene package.json');
  });

  it('y si apunta al paquete EQUIVOCADO, que es lo que nadie mira', () => {
    // Pasa al mover un directorio dentro del hermano: el `link:` sigue resolviendo, el enlace
    // existe, yarn no dice nada — y se importa otra cosa.
    const raiz = raizDeMentira({ '@kamayuk/formato': 'link:./otro' });
    mkdirSync(join(raiz, 'otro'));
    writeFileSync(join(raiz, 'otro/package.json'), JSON.stringify({ name: '@kamayuk/api' }));

    const problemas = problemasDelEnlace(raiz);

    expect(problemas[0]?.que).toBe('«./otro» es «@kamayuk/api», no «@kamayuk/formato».');
  });

  it('con todo en su sitio, no dice nada', () => {
    const raiz = raizDeMentira({ '@kamayuk/formato': 'link:./bueno' });
    mkdirSync(join(raiz, 'bueno'));
    writeFileSync(join(raiz, 'bueno/package.json'), JSON.stringify({ name: '@kamayuk/formato' }));

    expect(problemasDelEnlace(raiz)).toEqual([]);
  });
});

describe('LA GUARDA DE LA GUARDA: este archivo no importa de `@kamayuk/*`, y no puede', () => {
  it('cero imports del hermano en el archivo que avisa de que el hermano falta', () => {
    // ESTO ES LO QUE ESTA GUARDA DESCUBRIO DE SI MISMA, y costo una vuelta.
    //
    // La primera version importaba de los tres paquetes aqui arriba, para probar en el mismo
    // archivo que el enlace resolvia. Apartado el clon hermano, el resultado NO fue el rojo que
    // dice el `git clone`: fue
    //
    //     Error: Cannot find package '@kamayuk/sesion' imported from …enlace-con-kamayuk-lib.test.ts
    //
    // en la RECOLECCION — y con el, las nueve pruebas de este archivo **desaparecieron**, la
    // guarda entre ellas. O sea que el unico archivo que sabia explicar el defecto se callaba
    // justo cuando el defecto ocurria, y lo que quedaba era el mensaje inutil que esta guarda
    // existe para sustituir.
    //
    // Por eso la prueba de resolucion vive aparte, en `resuelve-el-clon-hermano.test.ts`: ese
    // SI puede morir sin el hermano —es lo que mide— porque para entonces este ya hablo.
    const yo = readFileSync(join(AQUI, 'enlace-con-kamayuk-lib.test.ts'), 'utf8');
    const importa = yo
      .split('\n')
      .filter((linea) => /^import .* from '@kamayuk\//.test(linea.trim()));

    expect(
      importa,
      'Un import de `@kamayuk/*` aqui mata el archivo entero durante la recoleccion cuando el ' +
        'clon hermano falta — que es EXACTAMENTE cuando esta guarda tiene que hablar. La prueba ' +
        'de resolucion va en `resuelve-el-clon-hermano.test.ts`.',
    ).toEqual([]);
  });
});

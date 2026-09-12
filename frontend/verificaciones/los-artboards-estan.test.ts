// @vitest-environment node
//
// Lee el DISCO y nada mas. Y NO importa ni una sola cosa que pueda faltar: es su unico trabajo
// poder hablar cuando algo falta.

import { existsSync, readFileSync, statSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, rutaDe } from './artboards.ts';

/**
 * **Los artboards vendorizados estan, y si falta uno el rojo lo dice** (#78).
 *
 * <h2>De que defecto viene, medido</h2>
 *
 * Cuatro barreras de este directorio leen el artboard, y **tres lo hacen en el top-level del
 * modulo**:
 *
 *     arbol-del-artboard.test.ts:73                    const html = leer(ARTBOARD);
 *     secciones-del-artboard.test.ts:43                const html = leer(ARTBOARD);
 *     determinacion-y-valores-del-artboard.test.ts:43  const html = leer(ARTBOARD);
 *     tokens-del-artboard.test.ts:130                  constantesDelArtboard()
 *
 * El dia que el archivo no este, los cuatro mueren durante la RECOLECCION con un `ENOENT` de
 * `readFileSync`, y sus **77 `it` no llegan a existir**. El build sale rojo —eso si— pero el rojo
 * habla de un fichero que falta en una ruta larga, no de que dejo de comprobarse, y no hay forma
 * de saber cuantas afirmaciones se perdieron.
 *
 * Peor: `tokens-del-artboard.test.ts` tenia un `expect(existsSync(ARTBOARD), 'Falta el artboard
 * en …')` **cuatro lineas despues de la llamada que ya habia lanzado**. La comprobacion defensiva
 * existia y no podia ejecutarse nunca — que es peor que no tenerla, porque parece cobertura.
 *
 * <h2>Por que este archivo no importa nada que pueda faltar</h2>
 *
 * Es la leccion de #74, que costo una vuelta: la guarda que avisa de que algo falta **no puede
 * depender de ese algo**, o muere con el y se calla justo cuando tiene que hablar. Aqui solo
 * entran `node:fs` y la lista de artboards, que es una constante.
 */

describe('los artboards vendorizados estan', () => {
  it('EL CENTINELA: hay artboards declarados que comprobar', () => {
    // Sin esto, todo lo de abajo pasaria sobre la lista vacia el dia que alguien la vacie — que
    // es como una guarda se queda sin sujeto y sigue en verde.
    expect(ARTBOARDS.length).toBeGreaterThanOrEqual(2);
    expect(ARTBOARDS.map((a) => a.archivo)).toContain('diseno/RentasV8.dc.html');
  });

  it.each(ARTBOARDS.map((a) => [a.archivo, a] as const))('%s esta, y no esta vacio', (_n, a) => {
    const ruta = rutaDe(a);

    expect(
      existsSync(ruta),
      `FALTA UN ARTBOARD VENDORIZADO: ${a.archivo}\n\n` +
        `  Que dibuja: ${a.que}\n` +
        `  De donde se trae: ${a.deDonde}\n\n` +
        '  Sin el, las barreras que lo leen mueren durante la RECOLECCION y sus pruebas no\n' +
        '  llegan a existir: el build sale rojo, pero hablando de un fichero y no de que\n' +
        '  dejo de comprobarse. Este mensaje es lo que se pone en su lugar.',
    ).toBe(true);

    // Y que no este vacio: un archivo de cero bytes existe, pasa el `existsSync`, y deja el
    // analizador devolviendo listas vacias — que es como las once comparaciones de
    // `tokens-del-artboard` acabarian comparando `undefined` contra `undefined` en verde.
    expect(statSync(ruta).size, `${a.archivo} esta vacio`).toBeGreaterThan(1024);
  });

  it('y los dos `.dc.html` son artboards de verdad, no una pagina cualquiera', () => {
    // La forma minima que los analizadores de `tokens.ts` dan por hecha. Un archivo que exista,
    // pese algo y no sea un artboard los dejaria devolviendo vacio, en verde.
    for (const a of ARTBOARDS.filter((x) => x.archivo.endsWith('.dc.html'))) {
      const html = readFileSync(rutaDe(a), 'utf8');
      expect(html, `${a.archivo} no trae el bloque <x-dc>`).toContain('<x-dc>');
      expect(html, `${a.archivo} no trae su guion`).toContain('type="text/x-dc"');
    }
  });
});

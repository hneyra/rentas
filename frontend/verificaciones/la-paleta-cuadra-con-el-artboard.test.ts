// @vitest-environment node
//
// Lee dos archivos del disco y compara texto. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, rutaDe } from './artboards.ts';

/**
 * **La paleta que `@kamayuk/ui` publica es la que el artboard dibuja** (#80).
 *
 * <h2>La direccion, que no es simetrica</h2>
 *
 * La libreria **publica** la paleta; quien tiene el artboard vendorizado es este repositorio, asi
 * que es este quien comprueba que el suyo cuadra. Al reves —la libreria leyendo el artboard de un
 * sistema— seria `@kamayuk/ui` dependiendo de `rentas`, que es exactamente lo que ADR-0030 §4
 * prohibe y lo que `sin-suponer-un-sistema` vigila del otro lado.
 *
 * <h2>Por que hace falta la hoja aparte</h2>
 *
 * El `.dc.html` **enlaza** `rentas-tokens.css` y no lo lleva dentro: por si solo trae **12**
 * colores literales —los de su tabla «Tokens a Tailwind»— y no los 42 que define. Sin vendorizar
 * la hoja no se podia comprobar contra el artboard mas de un cuarto de la paleta.
 *
 * <h2>Como se traducen los nombres</h2>
 *
 * El artboard escribe `--azul`; el `@theme` de Tailwind v4 escribe `--color-azul`, y ese prefijo
 * es lo que hace que se generen `bg-azul` y `text-azul`. Los radios van a `--radius-*` y las
 * sombras a `--shadow-*`. La traduccion se hace aqui, en un sitio, y la comparacion es token a
 * token.
 */

const requerir = createRequire(import.meta.url);

/** La hoja del artboard, por la lista de artboards declarados. */
const HOJA_DEL_ARTBOARD = (() => {
  const declarada = ARTBOARDS.find((a) => a.archivo.endsWith('rentas-tokens.css'));
  if (declarada === undefined) {
    throw new Error(
      'La hoja de tokens del artboard no esta declarada en `artboards.ts`. Sin ella, esta guarda ' +
        'no sabe contra que comparar.',
    );
  }
  return rutaDe(declarada);
})();

/**
 * El `@theme` de `@kamayuk/ui`, alcanzado POR EL ENLACE y no por una ruta al clon hermano.
 *
 * Que se resuelva por `node_modules/@kamayuk/ui` y no por `../../kamayuk-lib/...` no es un detalle
 * de estilo: es lo que hace que esta guarda comprobe la paleta **que este frontend usa de verdad**.
 * Una ruta al hermano leeria el archivo aunque el `link:` estuviera roto.
 */
const PALETA_DE_LA_LIBRERIA = join(
  dirname(requerir.resolve('@kamayuk/ui')),
  'estilos',
  'estilos.css',
);

/** `--azul: #005284;` -> `['--azul', '#005284']`, sin comentarios de por medio. */
function declaraciones(css: string): Map<string, string> {
  const sinComentarios = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  const salida = new Map<string, string>();
  for (const [, nombre, valor] of sinComentarios.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
    salida.set(nombre ?? '', (valor ?? '').trim());
  }
  return salida;
}

/** El nombre que el `@theme` le da a un token del artboard. */
function comoLoLlamaTailwind(nombre: string): string {
  const pelado = nombre.slice(2);
  if (pelado.startsWith('radio')) return `--radius-${pelado}`;
  if (pelado.startsWith('sombra')) return `--shadow-${pelado}`;
  return `--color-${pelado}`;
}

const delArtboard = declaraciones(readFileSync(HOJA_DEL_ARTBOARD, 'utf8'));
const deLaLibreria = declaraciones(readFileSync(PALETA_DE_LA_LIBRERIA, 'utf8'));

/** `color-scheme` no es un token: es una declaracion de la pagina. */
const TOKENS_DEL_ARTBOARD = [...delArtboard].filter(([n]) => n.startsWith('--'));

describe('la paleta de @kamayuk/ui es la del artboard', () => {
  it('EL CENTINELA: las dos lecturas traen tokens', () => {
    // Sin esto, un cambio de formato en cualquiera de los dos archivos dejaria su mapa VACIO, y
    // la comparacion de abajo pasaria en verde comparando nada — que es como una guarda se queda
    // sin sujeto sin que nadie la borre.
    expect(TOKENS_DEL_ARTBOARD.length, 'el artboard no declaro ni un token').toBe(42);
    expect(deLaLibreria.size, 'la libreria no publico ni un token').toBe(42);
  });

  it('y son exactamente los mismos cuarenta y dos, con el mismo valor', () => {
    const discrepancias: string[] = [];
    for (const [nombre, valor] of TOKENS_DEL_ARTBOARD) {
      const enTailwind = comoLoLlamaTailwind(nombre);
      const publicado = deLaLibreria.get(enTailwind);
      if (publicado === undefined) {
        discrepancias.push(`  ${nombre}: el artboard lo declara y la libreria no publica ${enTailwind}`);
      } else if (publicado !== valor) {
        discrepancias.push(`  ${nombre}: el artboard dice «${valor}» y la libreria «${publicado}»`);
      }
    }

    expect(
      discrepancias,
      'La paleta que `@kamayuk/ui` publica dejo de ser la que el artboard dibuja:\n' +
        `${discrepancias.join('\n')}\n\n` +
        '  El artboard manda. Si el cambio es deliberado, entra primero en el artboard y de ahi\n' +
        '  se deriva el `@theme` de la libreria — no al reves.',
    ).toEqual([]);
  });

  it('y la libreria no publica ninguno que el artboard no tenga', () => {
    // La otra direccion, que es la que nadie mira: un token inventado en la libreria se usa en una
    // pantalla, se ve bien, y no esta en ningun artboard — asi es como una paleta empieza a tener
    // colores que nadie decidio.
    const esperados = new Set(TOKENS_DEL_ARTBOARD.map(([n]) => comoLoLlamaTailwind(n)));
    const sobrantes = [...deLaLibreria.keys()].filter((n) => !esperados.has(n));

    expect(
      sobrantes,
      'La libreria publica tokens que el artboard no declara. Un color que nadie dibujo es un\n' +
        'color que nadie decidio.',
    ).toEqual([]);
  });

  it('el `--tinta-4` sigue siendo el que NO se usa como texto', () => {
    // El artboard lo dice en su propio comentario: sobre papel blanco da 2.59:1 y WCAG 1.4.3 pide
    // 4.5:1. Se fija aqui para que la guarda de contraste de UI-3 tenga contra que comparar, y
    // para que nadie lo «arregle» subiendolo sin enterarse de que es un trazo decorativo.
    expect(delArtboard.get('--tinta-4')).toBe('#93a3af');
    expect(deLaLibreria.get('--color-tinta-4')).toBe('#93a3af');
  });
});

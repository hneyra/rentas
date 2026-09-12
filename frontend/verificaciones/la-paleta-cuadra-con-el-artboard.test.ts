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
 * <h2>Como se traducen los nombres, y por que el radio NO es uno a uno</h2>
 *
 * El artboard escribe `--azul`; el `@theme` de Tailwind v4 escribe `--color-azul`, y ese prefijo
 * es lo que hace que se generen `bg-azul` y `text-azul`. Las sombras van a `--shadow-*`. Los
 * colores y las sombras se comparan **token a token**.
 *
 * **El radio no.** El artboard declara dos —`--radio` y `--radio-sm`, los dos de 3 px— y la
 * libreria publica cuatro: `--radius`, que es el knob que shadcn lee, y los tres tamanos de los
 * que shadcn deriva. No hay una correspondencia uno a uno que escribir, asi que lo que se
 * comprueba es la REGLA: **todo radio que la libreria publica vale lo que el artboard dice**.
 * Escribir un mapa a mano aqui obligaria a tocarlo cada vez que shadcn cambie de tamanos.
 *
 * <h2>De donde viene esta forma (kamayuk-lib#8)</h2>
 *
 * De un rojo real. El `@theme` emitia `--radius-radio` y shadcn lee `var(--radius)`, asi que el
 * token existia y no se llamaba como shadcn lo busca. Al renombrarlo, esta guarda salio roja con
 * `expected 44 to be 42` — **y nada en la CI de `kamayuk-lib` lo dijo**: se cazo corriendo la
 * suite del consumidor a mano. El acoplamiento entre los dos repositorios no lo vigila nadie.
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

/** El nombre que el `@theme` le da a un token del artboard. Solo colores y sombras: ver el radio. */
function comoLoLlamaTailwind(nombre: string): string {
  const pelado = nombre.slice(2);
  if (pelado.startsWith('sombra')) return `--shadow-${pelado}`;
  return `--color-${pelado}`;
}

const esRadio = (nombre: string): boolean => nombre.startsWith('--radio');

const delArtboard = declaraciones(readFileSync(HOJA_DEL_ARTBOARD, 'utf8'));
const deLaLibreria = declaraciones(readFileSync(PALETA_DE_LA_LIBRERIA, 'utf8'));

/** `color-scheme` no es un token: es una declaracion de la pagina. */
const TOKENS_DEL_ARTBOARD = [...delArtboard].filter(([n]) => n.startsWith('--'));
/** Los que se comparan uno a uno: todos menos los radios. */
const UNO_A_UNO = TOKENS_DEL_ARTBOARD.filter(([n]) => !esRadio(n));

describe('la paleta de @kamayuk/ui es la del artboard', () => {
  it('EL CENTINELA: las dos lecturas traen tokens', () => {
    // Sin esto, un cambio de formato en cualquiera de los dos archivos dejaria su mapa VACIO, y
    // la comparacion de abajo pasaria en verde comparando nada — que es como una guarda se queda
    // sin sujeto sin que nadie la borre.
    expect(TOKENS_DEL_ARTBOARD.length, 'el artboard no declaro ni un token').toBe(42);
    // La libreria publica MAS: los 38 colores y las 2 sombras uno a uno, mas CUATRO radios
    // —`--radius` y los tres tamanos de shadcn— donde el artboard declara dos. Ver el javadoc.
    expect(deLaLibreria.size, 'la libreria no publico ni un token').toBe(44);
  });

  it('los colores y las sombras son los mismos, uno a uno', () => {
    const discrepancias: string[] = [];
    for (const [nombre, valor] of UNO_A_UNO) {
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
    const esperados = new Set(UNO_A_UNO.map(([n]) => comoLoLlamaTailwind(n)));
    const sobrantes = [...deLaLibreria.keys()].filter(
      (n) => !esperados.has(n) && !n.startsWith('--radius'),
    );

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
  it('TODO radio que la libreria publica vale lo que el artboard dice', () => {
    // La regla, y no un mapa de nombres: el artboard declara `--radio` y `--radio-sm`, los dos de
    // 3 px, y la libreria publica `--radius` mas los tres tamanos de shadcn. Lo que importa no es
    // como se llaman sino que ninguno se aparte del valor del artboard — que es lo que haria que
    // los componentes de shadcn cayeran en su `0.625rem`.
    const delArtboardRadios = [...new Set(
      TOKENS_DEL_ARTBOARD.filter(([n]) => esRadio(n)).map(([, v]) => v),
    )];
    expect(delArtboardRadios, 'el artboard declara mas de un radio distinto').toHaveLength(1);
    const esperado = delArtboardRadios[0];

    const publicados = [...deLaLibreria].filter(([n]) => n.startsWith('--radius'));
    expect(publicados.length, 'la libreria no publica ningun radio').toBeGreaterThanOrEqual(4);
    // `--radius` a secas tiene que estar: es el que shadcn lee.
    expect(deLaLibreria.has('--radius'), 'falta `--radius`, que es el knob de shadcn').toBe(true);

    const distintos = publicados
      .filter(([, v]) => v !== esperado)
      .map(([n, v]) => `  ${n}: «${v}», y el artboard dice «${esperado ?? ''}»`);
    expect(
      distintos,
      `Un radio publicado se aparto del que dibuja el artboard:\n${distintos.join('\n')}`,
    ).toEqual([]);
  });
});

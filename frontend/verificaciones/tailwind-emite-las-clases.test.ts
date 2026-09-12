// @vitest-environment node
//
// Compila CSS de verdad y lee archivos del disco. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, rutaDe } from './artboards.ts';
import { RAIZ_DE_UI, clasesDe, compilar, fuentesDe } from './tailwind.ts';

/**
 * **Una clase de Tailwind produce una REGLA, y no solo esta escrita** (#91).
 *
 * <h2>Lo que hoy no comprueba nadie</h2>
 *
 * Las piezas de `@kamayuk/ui` y el interprete de #88 estan escritos en clases —`bg-azul`,
 * `border-borde-campo`, `rounded-sm`—. Un `bg-azull`, un `text-tinta-5` o un `rounded-xs`
 * compilan, pasan el lint, y pasan las pruebas, que comparan `className` **como texto**. El
 * elemento se queda sin ningun estilo, y en una pantalla de cuarenta campos uno sin filo no se ve
 * hasta que alguien lo mira.
 *
 * <h2>Y el modo de fallo que lo motiva</h2>
 *
 * Que el token EXISTA en el `@theme` y la utilidad NO se genere. Tailwind v4 saca `bg-*` de
 * `--color-*` y de nada mas: un token bien escrito con el prefijo equivocado deja la paleta
 * «puesta» y las utilidades sin generar. Es exactamente lo que le paso al radio en
 * `kamayuk-lib`#8 —`--radius-radio` en vez de `--radius`— y alli se comprobo que el token se
 * declara. Aqui se comprueba que **llega al CSS**.
 *
 * <h2>Por que esto se puede hacer sin conectar Tailwind a la aplicacion</h2>
 *
 * Porque no hace falta: la hoja se compila **dentro de la prueba**, con la API de `tailwindcss`.
 * Conectarlo a la aplicacion traeria su *preflight*, que le cambiaria la cara a la V6 — que sigue
 * sirviendose hasta #90.
 */

/** Las piezas que dibujan: las de la libreria y las del interprete. */
const FUENTES = [...fuentesDe(RAIZ_DE_UI), ...fuentesDe('src/pantallas')];
const CLASES = [...new Set(FUENTES.flatMap((f) => clasesDe(readFileSync(f, 'utf8'))))].sort();

const HOJA_DEL_ARTBOARD = (() => {
  const declarada = ARTBOARDS.find((a) => a.archivo.endsWith('rentas-tokens.css'));
  if (declarada === undefined) throw new Error('La hoja de tokens no esta en `artboards.ts`.');
  return rutaDe(declarada);
})();

/**
 * `--azul: #005284;` -> `{ azul, #005284 }`. Los VALORES, que es lo que tiene que salir en el CSS.
 *
 * Se cogen los opacos y los translucidos: **treinta y tres en hex y cinco en `rgba()`** —los
 * velos y los realces de la barra—, que suman los 38 colores del artboard. Contarlos por separado
 * no es capricho: buscar solo `#rrggbb` da 33 y parece que faltan cinco.
 */
const COLORES_DEL_ARTBOARD = [
  ...readFileSync(HOJA_DEL_ARTBOARD, 'utf8')
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .matchAll(/--([a-z0-9-]+)\s*:\s*(#[0-9a-f]{6}|rgba\([^)]*\))\s*;/gi),
].map(([, nombre, valor]) => ({ nombre: nombre ?? '', valor: (valor ?? '').replace(/\s+/g, ' ') }));

describe('Tailwind emite lo que las piezas piden', () => {
  it('EL CENTINELA: hay fuentes, clases y valores que comprobar', async () => {
    // Sin esto, un cambio de ruta o de extension dejaria las tres listas vacias y todo lo de
    // abajo pasando en verde sobre la nada. Ya paso en este repositorio con el artboard (#78).
    expect(FUENTES.length, 'no se leyo ni una pieza').toBeGreaterThanOrEqual(15);
    expect(CLASES.length, 'no se extrajo ni una clase').toBeGreaterThanOrEqual(60);
    expect(COLORES_DEL_ARTBOARD.length, 'el artboard no declaro ni un color').toBe(38);

    const css = await compilar(['bg-azul']);
    expect(css.length, 'Tailwind no emitio CSS: la hoja no compila').toBeGreaterThan(500);
  });

  it('CADA color del artboard genera su utilidad, con su valor', async () => {
    // Se pide `bg-<token>` de los 38 a proposito. Tailwind v4 **solo emite lo que se usa**, asi
    // que preguntar por las clases que hoy se escriben mediria que tokens estan en uso, no que la
    // paleta funcione. Pidiendolos todos se mide el CAMINO: que de `--color-x` salga `bg-x` y que
    // lleve el valor que el artboard dibuja.
    //
    // Es el modo de fallo de `kamayuk-lib`#8 en su forma general: un token bien escrito con el
    // prefijo equivocado deja la paleta «puesta» y las utilidades sin generar.
    const css = await compilar(COLORES_DEL_ARTBOARD.map((c) => `bg-${c.nombre}`));
    const plano = css.replace(/\\/g, '').replace(/\s+/g, ' ');

    const mudos = COLORES_DEL_ARTBOARD.filter((c) => !plano.includes(`.bg-${c.nombre}`)).map(
      (c) => `  --${c.nombre}: no genera «bg-${c.nombre}»`,
    );
    const torcidos = COLORES_DEL_ARTBOARD.filter(
      (c) => plano.includes(`.bg-${c.nombre}`) && !plano.includes(c.valor),
    ).map((c) => `  --${c.nombre}: genera la utilidad y NO trae «${c.valor}»`);

    expect(
      [...mudos, ...torcidos],
      'La paleta del artboard no llega entera al CSS:\n' +
        `${[...mudos, ...torcidos].join('\n')}\n\n` +
        '  Un token declarado cuya utilidad no se genera deja al elemento sin estilo, y eso no lo\n' +
        '  ve ninguna prueba que compare `className` como texto.',
    ).toEqual([]);
  });

  it('el RADIO emitido es el del artboard, y no el `0.625rem` de shadcn', async () => {
    const css = await compilar(['rounded-sm', 'rounded-md', 'rounded-lg']);
    // Es la mitad que falta de `kamayuk-lib`#8: alli se comprobo que el token se declara.
    expect(css).not.toContain('0.625rem');
    for (const clase of ['rounded-sm', 'rounded-md', 'rounded-lg']) {
      expect(css, `no se emitio .${clase}`).toContain(`.${clase}`);
    }
    expect(css).toMatch(/border-radius:\s*(?:var\(--radius-(?:sm|md|lg)\)|3px)/);
  });

  it('TODA clase que las piezas usan produce una regla', async () => {
    const css = await compilar(CLASES);
    // Tailwind emite la utilidad con el nombre escapado: `data-[state=checked]:bg-azul` sale como
    // `.data-\[state\=checked\]\:bg-azul`. Se compara sobre el CSS con las barras quitadas.
    const plano = css.replace(/\\/g, '');
    const mudas = CLASES.filter((c) => !plano.includes(`.${c}`)).map((c) => `  ${c}`);
    expect(
      mudas,
      'Hay clases escritas que Tailwind NO genera. El elemento que las lleva se queda sin\n' +
        `estilo, y eso no se ve en ninguna prueba que compare \`className\` como texto:\n${mudas.join('\n')}`,
    ).toEqual([]);
  });
});

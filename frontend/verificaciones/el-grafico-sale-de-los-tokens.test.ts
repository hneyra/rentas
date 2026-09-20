// @vitest-environment node
//
// Lee el arbol del disco y compila CSS de verdad. No es un DOM lo que necesita.

import { readFileSync, readdirSync } from 'node:fs';
import { join, relative } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, RAIZ, rutaDe } from './artboards.ts';
import { clasesDe, compilar, paletaDelTema, reglasDe, resolver, utilidadDesnuda } from './tailwind.ts';

/**
 * **El grafico de `ini-flujo` no tiene ni un color propio** (#288, AC-4).
 *
 * <h2>Por que esto necesita guarda, y no basta con haberlo escrito bien</h2>
 *
 * Porque recharts trae **sus propios colores por omision** —un azul, un `#eee` para la pista de la
 * barra, un gris para los ejes— y un `fill` olvidado no da ningun error: dibuja. La pantalla se ve
 * bien en el tema claro institucional, que es el que todo el mundo tiene abierto, y **se rompe en
 * los otros cinco**: un azul de recharts sobre el papel del tema oscuro, o sobre el de alto
 * contraste, es el unico elemento de la pantalla que no cambia con el tema. Nadie lo ve hasta que
 * alguien abre el mando de preferencias.
 *
 * Y tiene un segundo motivo, que es el que importa mas: el dia que esto suba a `@kamayuk/ui`
 * —`kamayuk-lib`#25, con el segundo sistema que pida una serie— la mudanza es mecanica si todo lo
 * que pinta sale de un token, y es un rediseno si hay un color escrito aqui.
 *
 * <h2>Las tres cosas que mide</h2>
 *
 * 1. Que en `src/piezas/` **no haya ni un color literal** —hexadecimal, `rgb(`, `hsl(` o uno de los
 *    nombres de CSS que se escriben sin darse cuenta—.
 * 2. Que cada `var(--color-x)` y cada utilidad `fill-x`/`stroke-x` que escribe nombre un token que
 *    **el artboard declara**, y que la utilidad emita una regla cuyo valor sea el del artboard.
 * 3. Que el radio sea `var(--radius)` —los 3 px del artboard— y **no un numero**: el `radius` de
 *    recharts es un `number`, y un numero escrito aqui seria el radio de este grafico y no el del
 *    producto. Por eso la barra es un `<rect>` con `rx` por CSS y no el `Rectangle` de recharts,
 *    que emite un `<path>` y no tiene `rx`.
 */

/** Donde viven las piezas que este sistema dibuja dentro del interprete. */
const RAIZ_DE_LAS_PIEZAS = join(RAIZ, 'src', 'piezas');

/** Los `.ts`/`.tsx` de `src/piezas/`, sin sus pruebas: lo que VIAJA al navegador. */
function fuentes(desde = RAIZ_DE_LAS_PIEZAS): readonly string[] {
  return readdirSync(desde, { withFileTypes: true }).flatMap((entrada) => {
    const ruta = join(desde, entrada.name);
    if (entrada.isDirectory()) return fuentes(ruta);
    return /\.tsx?$/.test(entrada.name) && !/\.test\.tsx?$/.test(entrada.name) ? [ruta] : [];
  });
}

const FUENTES = fuentes();

/**
 * Los tokens de color que el artboard declara, por su nombre y con su valor.
 *
 * Del artboard y no del `@theme` de la libreria: es la misma direccion que
 * `tailwind-emite-las-clases` —la libreria PUBLICA la paleta y este repositorio comprueba que su
 * artboard cuadra con ella—, y es lo que hace que «sale de un token» signifique «sale de un color
 * que alguien dibujo» y no «sale de algo que existe en el CSS».
 */
const COLORES_DEL_ARTBOARD: ReadonlyMap<string, string> = (() => {
  const declarada = ARTBOARDS.find((a) => a.archivo.endsWith('rentas-tokens.css'));
  if (declarada === undefined) throw new Error('La hoja de tokens no esta en `artboards.ts`.');
  const hoja = readFileSync(rutaDe(declarada), 'utf8').replace(/\/\*[\s\S]*?\*\//g, ' ');
  return new Map(
    [...hoja.matchAll(/--([a-z0-9-]+)\s*:\s*(#[0-9a-f]{6}|rgba\([^)]*\))\s*;/gi)].map(
      ([, nombre, valor]) => [nombre ?? '', (valor ?? '').replace(/\s+/g, ' ').toLowerCase()],
    ),
  );
})();

/** Lo que es un color escrito a mano. `currentColor`, `none` y `transparent` no lo son. */
const UN_COLOR_LITERAL =
  /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|color-mix|oklch|lab)\s*\(|\b(?:white|black|red|green|blue|gray|grey|silver|orange|yellow|purple|teal|navy)\b/;

/** `var(--color-azul)` -> `azul`. */
const TOKEN_EN_VAR = /var\(\s*--color-([a-z0-9-]+)\s*\)/g;

/** Las utilidades de Tailwind que pintan: `fill-azul`, `stroke-linea-2`, `text-tinta-3`. */
const UTILIDAD_QUE_PINTA = /^(?:fill|stroke|text|bg|border)-([a-z0-9-]+)$/;

/**
 * El codigo de `src/piezas/`, archivo a archivo y **sin comentarios**: la prosa cita valores.
 *
 * Los comentarios se vacian **conservando sus saltos de linea**, no se colapsan: el rojo dice la
 * linea del archivo, y un docblock de cuarenta lineas convertido en un espacio la corre cuarenta
 * —medido al demostrar la rotura, que senalaba la linea 47 de un defecto que estaba en la 102—.
 */
const SIN_COMENTARIOS: readonly { readonly ruta: string; readonly codigo: string }[] = FUENTES.map(
  (ruta) => ({
    ruta: relative(RAIZ, ruta),
    codigo: readFileSync(ruta, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, (bloque) => bloque.replace(/[^\n]/g, ' '))
      .replace(/^(\s*)\/\/.*$/gm, '$1'),
  }),
);

describe('el grafico de `ini-flujo` sale de los tokens y de ningun otro sitio', () => {
  it('EL CENTINELA: hay piezas que leer, y el artboard trae sus colores', () => {
    // Sin esto, un cambio de ruta dejaria las dos listas vacias y todo lo de abajo pasando en
    // verde sobre la nada. Es el modo de fallo que este repositorio ya pago con el artboard (#78).
    expect(FUENTES.length, 'no se leyo ni una pieza de `src/piezas/`').toBeGreaterThanOrEqual(3);
    expect(COLORES_DEL_ARTBOARD.size, 'el artboard no declaro ni un color').toBe(38);
    // Y que el detector de colores literales detecte: si dejara de casar, la comprobacion de
    // abajo diria que no hay ninguno teniendolos todos.
    expect(UN_COLOR_LITERAL.test('fill="#005284"')).toBe(true);
    expect(UN_COLOR_LITERAL.test('fill="var(--color-azul)"')).toBe(false);
  });

  it('AC-4: ni un color literal en `src/piezas/`', () => {
    const escritos = SIN_COMENTARIOS.flatMap(({ ruta, codigo }) =>
      codigo
        .split('\n')
        .flatMap((linea, i) => {
          const casado = UN_COLOR_LITERAL.exec(linea);
          return casado === null ? [] : [`  ${ruta}:${String(i + 1)}  «${casado[0]}»`];
        }),
    );
    expect(
      escritos,
      'Hay un color escrito a mano en una pieza de este sistema:\n' +
        `${escritos.join('\n')}\n\n` +
        '  Los colores salen de los tokens de `@kamayuk/ui` (`--color-*`), y no por gusto: un color\n' +
        '  propio es el unico elemento de la pantalla que NO cambia con los seis temas, y nadie lo\n' +
        '  ve hasta que alguien abre el mando de preferencias. Y el dia que esto suba a la libreria\n' +
        '  (`kamayuk-lib`#25) la mudanza deja de ser mecanica y pasa a ser un rediseno.',
    ).toEqual([]);
  });

  it('cada `var(--color-x)` que nombra es un token que el artboard declara', () => {
    const nombrados = SIN_COMENTARIOS.flatMap(({ ruta, codigo }) =>
      [...codigo.matchAll(TOKEN_EN_VAR)].map(([, token]) => ({ ruta, token: token ?? '' })),
    );
    expect(nombrados.length, 'la pieza no nombra ni un token por `var(--color-x)`').toBeGreaterThan(0);
    const inventados = nombrados
      .filter(({ token }) => !COLORES_DEL_ARTBOARD.has(token))
      .map(({ ruta, token }) => `  ${ruta}: --color-${token}`);
    expect(
      inventados,
      'Hay un `var(--color-x)` que el artboard no declara:\n' +
        `${inventados.join('\n')}\n\n` +
        '  Un token que no existe no da error: `var()` sin valor deja la propiedad sin declarar y el\n' +
        '  elemento se pinta con lo que herede. Es el mismo defecto que `--radius-radio` en\n' +
        '  `kamayuk-lib`#8, y no se ve mirando el codigo.',
    ).toEqual([]);
  });

  it('y cada utilidad que pinta lleva el valor que el artboard dibuja', async () => {
    const utilidades = [
      ...new Set(SIN_COMENTARIOS.flatMap(({ codigo }) => clasesDe(codigo))),
    ].filter((clase) => UTILIDAD_QUE_PINTA.test(clase));
    expect(utilidades.length, 'la pieza no usa ni una utilidad de color').toBeGreaterThan(0);

    const reglas = reglasDe(await compilar(utilidades));
    const paleta = paletaDelTema(reglas);
    const torcidas = utilidades.flatMap((clase) => {
      const token = UTILIDAD_QUE_PINTA.exec(clase)?.[1] ?? '';
      const delArtboard = COLORES_DEL_ARTBOARD.get(token);
      if (delArtboard === undefined) return [`  ${clase}: el artboard no declara «--${token}»`];
      const utilidad = utilidadDesnuda(reglas, clase);
      if (utilidad === undefined) return [`  ${clase}: Tailwind no genera la utilidad`];
      // La declaracion que la utilidad trae, sea `fill`, `color`, `background-color` o `stroke`:
      // se resuelve el `var(--color-x)` contra la paleta del `@theme`, como hace #139.
      const declarado = [...utilidad.declaraciones.values()][0] ?? '';
      const emitido = resolver(declarado, paleta);
      return emitido === delArtboard
        ? []
        : [`  ${clase}: pinta «${emitido}» y el artboard dice «${delArtboard}»`];
    });
    expect(
      torcidas,
      'Una utilidad del grafico no pinta lo que el artboard dibuja:\n' +
        `${torcidas.join('\n')}\n\n` +
        '  Lo que se compara es lo que declara la REGLA, con su token resuelto, y no que el\n' +
        '  hexadecimal ande suelto por el CSS (#139).',
    ).toEqual([]);
  });

  it('AC-4: TODO rectangulo lleva el radio de `var(--radius)`, y no un numero', async () => {
    // Por cada rectangulo y no «en alguna parte del archivo»: con dos —la barra y la pista— una
    // comprobacion de «contiene» pasaria teniendo una de las dos con la esquina cuadrada.
    const rectangulos = SIN_COMENTARIOS.flatMap(({ ruta, codigo }) =>
      [...codigo.matchAll(/rectangulo\(\s*'([^']*)'/g)].map(([, clases]) => ({ ruta, clases: clases ?? '' })),
    );
    expect(rectangulos.length, 'no se encontro ni un rectangulo del grafico').toBeGreaterThanOrEqual(2);
    const sinRadio = rectangulos
      .filter(({ clases }) => !clases.includes('[rx:var(--radius)]'))
      .map(({ ruta, clases }) => `  ${ruta}: «${clases}»`);
    expect(
      sinRadio,
      'Hay un rectangulo del grafico que no ata su radio a `--radius`:\n' +
        `${sinRadio.join('\n')}\n\n` +
        '  El artboard dibuja 3 px y el `radius` de recharts es un `number`: escrito como numero,\n' +
        '  este grafico tendria SU radio y no el del producto, y el dia que el artboard lo cambie no\n' +
        '  cambiaria con el. Y `rounded-*` no vale sobre un `<rect>` de SVG: `border-radius` no\n' +
        '  redondea una forma SVG — lo que la redondea es `rx`.',
    ).toEqual([]);

    // Y que la clase produzca regla: una utilidad arbitraria mal escrita no da error, no emite
    // nada y la esquina se queda cuadrada sin que ninguna prueba de `className` lo note.
    const css = await compilar(['[rx:var(--radius)]']);
    expect(css.replace(/\s+/g, ' ')).toContain('rx: var(--radius)');
    // Y que el `--radius` al que apunta vale lo que el artboard dibuja: su `--radio`, 3 px.
    const hojaDelArtboard = ARTBOARDS.find((a) => a.archivo.endsWith('rentas-tokens.css'));
    if (hojaDelArtboard === undefined) throw new Error('La hoja de tokens no esta en `artboards.ts`.');
    expect(readFileSync(rutaDe(hojaDelArtboard), 'utf8')).toMatch(/--radio\s*:\s*3px/);
  });
});

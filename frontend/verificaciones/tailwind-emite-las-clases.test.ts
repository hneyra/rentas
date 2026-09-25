// @vitest-environment node
//
// Compila CSS de verdad y lee archivos del disco. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, RAIZ, rutaDe } from './artboards.ts';
import {
  RAIZ_DE_UI,
  clasesDe,
  clasesEmitidas,
  compilar,
  fuentesDe,
  paletaDelTema,
  reglasDe,
  resolver,
  utilidadDesnuda,
} from './tailwind.ts';

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
 * <h2>Y por que se pregunta a la REGLA y no al archivo (#139)</h2>
 *
 * Porque `includes` sobre todo el CSS emitido responde «¿existe esto en alguna parte?», y esa no
 * es la pregunta. Desde `kamayuk-lib`#23 la hoja arrastra `temas.css`, que reproduce los 38
 * valores del artboard en su bloque `institucional/claro`: preguntando asi, el hexadecimal
 * aparecia **siempre** —lo traia la paleta, no la utilidad— y la mitad «con su valor» dejo de
 * poder fallar. Medido con `--color-tinta-2: #ff00ff` puesto en el `@theme` y la hoja de hoy:
 * cuatro pruebas en verde con el defecto dentro.
 *
 * Y la mitad «genera la utilidad» tenia el filo romo por lo mismo en pequeno: `includes` es
 * subcadena, asi que `.bg-superficie` contestaba que si a `.bg-sup` y `.bg-tinta-2` a `.bg-tinta`.
 * **Siete de los 38 colores del artboard son prefijo de otro** —`sup`, `tinta`, `azul`, `linea`,
 * `esqueleto`, `sobre-barra` y `velo`—, o sea que siete utilidades podian dejar de emitirse sin
 * que esto lo dijera. Medido retirando `--color-tinta` del `@theme`: la mitad «genera la utilidad»
 * seguia en verde.
 *
 * Desde #139 se lee el CSS por reglas —`reglasDe`, en `tailwind.ts`— y se le pregunta a la regla
 * `.bg-<nombre>`: que exista con ese selector exacto, y que lo que declara, resuelto contra la
 * paleta **del `@theme`**, sea el valor del artboard.
 *
 * <h2>Y que NO mide, que desde #145 tiene guarda propia</h2>
 *
 * Lo que el navegador acaba pintando. Lo acotado al `@layer theme` es el `@theme`, o sea el origen
 * de las utilidades; el bloque sin capa de `temas.css` le gana en la cascada y decide el color que
 * se ve. Eso lo compara contra el artboard `la-paleta-que-gana-la-cascada.test.ts`. El acotado de
 * #139 **no se deshace**: sin el, una mutacion del `@theme` se la tapa ese mismo bloque, que
 * repite los valores — que es el defecto que #139 vino a cerrar.
 *
 * <h2>Por que esto se puede hacer sin conectar Tailwind a la aplicacion</h2>
 *
 * Porque no hace falta: la hoja se compila **dentro de la prueba**, con la API de `tailwindcss`.
 * Conectarlo a la aplicacion traeria su *preflight*, que le cambiaria la cara a la V6 — que sigue
 * sirviendose hasta #90.
 */

/**
 * Las piezas que dibujan: las de la libreria, las del interprete y las de la costura.
 *
 * `src/preferencias` entra con #111. Es la unica pieza que este repositorio dibuja fuera del
 * interprete, y llego escribiendo clases que ninguna otra usa —`accent-azul` entre ellas—: dejarla
 * fuera de esta lista seria dejar sin vigilar justo la unica que estrena utilidades.
 *
 * `src/piezas` entra con #288, por lo mismo y con mas motivo: son las piezas del consumidor que el
 * interprete monta, y la primera —el grafico— estrena `fill-*` y una utilidad arbitraria para el
 * radio. Fuera de esta lista, una clase suya mal escrita dejaria la barra sin color en una pantalla
 * que se dibuja igual.
 *
 * <h2>Las TRES de este arbol se resuelven contra ESTE archivo, y no contra el `cwd` (#295)</h2>
 *
 * Hasta #295 eran `fuentesDe('src/pantallas')`, `fuentesDe('src/piezas')` y
 * `fuentesDe('src/preferencias')`, o sea rutas relativas que `readdirSync` resuelve contra el
 * directorio de trabajo. Funcionaban porque `yarn test` se lanza desde `frontend/`, y eran —con el
 * `readFileSync('package.json')` de `las-peerdependencies-estan`— las unicas rutas de este
 * directorio que no derivaban su raiz de `import.meta.url`: las otras 41 guardas si.
 * Medido corriendo la suite desde la raiz del repositorio —
 * `vitest run --root frontend`, con el `cwd` un nivel mas arriba—: el archivo entero caia en la
 * RECOLECCION con `ENOENT: no such file or directory, scandir 'src/pantallas'`, sin llegar a
 * ejecutar ni una prueba. Y el otro desenlace es peor que el rojo: un `cwd` que si tuviera un
 * `src/pantallas` se habria leido en silencio, que es el modo de fallo de #91 —medir el arbol
 * equivocado— un nivel mas arriba.
 *
 * `RAIZ_DE_UI` no tenia el defecto: sale de `requerir.resolve('@kamayuk/ui')`, que va por el
 * enlace y es absoluta.
 */
const RAICES: readonly { readonly raiz: string; readonly que: string }[] = [
  { raiz: RAIZ_DE_UI, que: 'las piezas de `@kamayuk/ui`' },
  { raiz: join(RAIZ, 'src', 'pantallas'), que: 'el interprete y la costura de este arbol' },
  { raiz: join(RAIZ, 'src', 'piezas'), que: 'las piezas del consumidor (#288)' },
  { raiz: join(RAIZ, 'src', 'preferencias'), que: 'el mando de los temas (#111)' },
];
const FUENTES = RAICES.flatMap((r) => fuentesDe(r.raiz));
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
    //
    // **Y se pregunta RAIZ POR RAIZ, y no por el total** (#295). Un umbral sobre la suma contesta
    // «¿hay bastantes?», y cuando #295 lo midio eran **43 fuentes: 40 de la libreria y UNA de cada
    // uno de los tres directorios de aqui**. O sea que las tres de este arbol podian quedarse a cero a la vez y
    // 40 >= 15 seguiria en verde — justo las tres que estrenan utilidades que ninguna otra usa:
    // `accent-azul` de `src/preferencias` (#111) y los `fill-*` del grafico de `src/piezas`
    // (#288). Preguntando raiz por raiz, la que se quede vacia sale nombrada.
    //
    // Lo que NO se hace es escribir los nombres, y no por pereza: casi todos viven en
    // `kamayuk-lib`, que este repositorio no gobierna. Medido sobre su `git log` el 2026-09-20:
    // **10 de sus 101 commits** anaden o borran un `.tsx` de `paquetes/ui` —49 altas y bajas de
    // archivo en trece dias—, o sea que una lista literal saldria roja AQUI, y bloqueando, una de
    // cada diez veces que alguien toca alla. La identidad se pregunta al nivel que no se mueve:
    // las raices.
    const vacias = RAICES.filter((r) => fuentesDe(r.raiz).length === 0).map(
      (r) => `  ${r.raiz} — ${r.que}`,
    );
    expect(
      vacias,
      'Hay una raiz que no aporto ni una pieza, y lo de abajo se mediria sin ella:\n' +
        `${vacias.join('\n')}`,
    ).toEqual([]);
    expect(FUENTES.length, 'no se leyo ni una pieza').toBeGreaterThanOrEqual(15);
    expect(CLASES.length, 'no se extrajo ni una clase').toBeGreaterThanOrEqual(60);
    expect(COLORES_DEL_ARTBOARD.length, 'el artboard no declaro ni un color').toBe(38);

    const css = await compilar(['bg-azul']);
    expect(css.length, 'Tailwind no emitio CSS: la hoja no compila').toBeGreaterThan(500);

    // Y que el lector de #139 encuentra sus dos sujetos en el CSS DE VERDAD, no solo en la
    // muestra de abajo. Si Tailwind cambiara la forma en que emite el `@theme` —hoy,
    // `@layer theme { :root, :host { … } }`—, la paleta se leeria vacia y los 38 colores saldrian
    // «torcidos» a la vez: un rojo correcto con el motivo equivocado. Este dice cual es.
    const reglas = reglasDe(css);
    expect(
      utilidadDesnuda(reglas, 'bg-azul'),
      'el lector no encontro la regla `.bg-azul` en el CSS emitido',
    ).toBeDefined();
    expect(
      paletaDelTema(reglas).size,
      'el `@theme` no se leyo: Tailwind ya no lo emite como `@layer theme { :root, :host { … } }`',
    ).toBeGreaterThan(0);
  });

  it('EL CENTINELA: el lector del CSS distingue lo que `includes` confundia', () => {
    // Este es el defecto de #139 en miniatura, escrito a mano para que se pueda mirar. Si el
    // lector dejara de distinguir cualquiera de estas tres cosas, las comprobaciones de abajo
    // volverian a pasar en verde sobre un defecto puesto — que es como llegamos aqui.
    const muestra = [
      '@layer theme {',
      '  :root, :host { --color-sup: #f7fbfe; --color-superficie: #ffffff; }',
      '}',
      '@layer utilities {',
      '  .bg-superficie { background-color: var(--color-superficie); }',
      '}',
      // Fuera de toda capa, como `temas.css`: gana la cascada y REPITE los valores del artboard.
      // Con `includes` sobre el CSS entero, este bloque contestaba por la utilidad.
      ":root, [data-tema='institucional'] { --color-sup: #f7fbfe; --color-superficie: #0000ff; }",
    ].join('\n');
    const reglas = reglasDe(muestra);

    // 1) La clase se reconoce entera, y no por prefijo: hay `.bg-superficie` y NO hay `bg-sup`.
    const emitidas = clasesEmitidas(reglas);
    expect(emitidas.has('bg-superficie'), 'el lector no vio la clase que si esta').toBe(true);
    expect(
      emitidas.has('bg-sup'),
      'el lector contesta por subcadena: `.bg-superficie` no genera la utilidad `bg-sup`',
    ).toBe(false);
    expect(utilidadDesnuda(reglas, 'bg-sup'), 'la utilidad desnuda tambien va por prefijo').toBe(
      undefined,
    );

    // 2) La paleta sale del `@theme` y no del bloque sin capa, aunque los dos declaren el token.
    expect(paletaDelTema(reglas).get('--color-superficie')).toBe('#ffffff');

    // 3) Y el valor de la utilidad se lee de SU regla, resolviendo el `var(--…)`.
    const utilidad = utilidadDesnuda(reglas, 'bg-superficie');
    expect(utilidad, 'no se encontro la regla de la utilidad').toBeDefined();
    expect(
      resolver(utilidad?.declaraciones.get('background-color') ?? '', paletaDelTema(reglas)),
    ).toBe('#ffffff');
  });

  it('CADA color del artboard genera su utilidad, con su valor', async () => {
    // Se pide `bg-<token>` de los 38 a proposito. Tailwind v4 **solo emite lo que se usa**, asi
    // que preguntar por las clases que hoy se escriben mediria que tokens estan en uso, no que la
    // paleta funcione. Pidiendolos todos se mide el CAMINO: que de `--color-x` salga `bg-x` y que
    // lleve el valor que el artboard dibuja.
    //
    // Es el modo de fallo de `kamayuk-lib`#8 en su forma general: un token bien escrito con el
    // prefijo equivocado deja la paleta «puesta» y las utilidades sin generar.
    const reglas = reglasDe(await compilar(COLORES_DEL_ARTBOARD.map((c) => `bg-${c.nombre}`)));
    // La paleta del `@theme`, que es la que la utilidad apunta con su `var(--color-x)`. Las seis
    // de `temas.css` quedan fuera a proposito: ver `paletaDelTema` y #139.
    const paleta = paletaDelTema(reglas);

    const mudos = COLORES_DEL_ARTBOARD.filter(
      (c) => utilidadDesnuda(reglas, `bg-${c.nombre}`) === undefined,
    ).map((c) => `  --${c.nombre}: no genera «bg-${c.nombre}»`);

    const torcidos = COLORES_DEL_ARTBOARD.flatMap((c) => {
      const utilidad = utilidadDesnuda(reglas, `bg-${c.nombre}`);
      if (utilidad === undefined) return [];
      // Lo que la REGLA declara, con su `var(--color-x)` resuelto. Una utilidad de color no
      // lleva el hexadecimal dentro: lleva el token, y sin seguirlo no hay valor que comparar.
      const declarado = utilidad.declaraciones.get('background-color') ?? '(sin background-color)';
      const emitido = resolver(declarado, paleta);
      if (emitido === c.valor.toLowerCase()) return [];
      return [`  --${c.nombre}: «bg-${c.nombre}» pinta «${emitido}» y el artboard dice «${c.valor}»`];
    });

    expect(
      [...mudos, ...torcidos],
      'La paleta del artboard no llega entera al CSS:\n' +
        `${[...mudos, ...torcidos].join('\n')}\n\n` +
        '  Un token declarado cuya utilidad no se genera deja al elemento sin estilo, y eso no lo\n' +
        '  ve ninguna prueba que compare `className` como texto. Y una utilidad que se genera con\n' +
        '  otro valor pinta la pantalla de un color que nadie dibujo: lo que se compara es lo que\n' +
        '  declara la REGLA `.bg-<nombre>`, no que el hexadecimal ande suelto por el CSS (#139).',
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
    // Tailwind emite la utilidad con el nombre escapado: `data-[state=checked]:bg-azul` sale como
    // `.data-\[state\=checked\]\:bg-azul`. `clasesEmitidas` deshace los escapes leyendo cada
    // selector caracter a caracter, que es lo unico que sabe donde ACABA el nombre de la clase.
    //
    // La pertenencia es EXACTA y no por subcadena (#139): con `includes`, `.text-tinta-2`
    // contestaba que si a `text-tinta`, asi que una utilidad podia dejar de emitirse y esto
    // seguia verde mientras existiera otra cuyo nombre la tuviera por prefijo.
    const emitidas = clasesEmitidas(reglasDe(await compilar(CLASES)));
    const mudas = CLASES.filter((c) => !emitidas.has(c)).map((c) => `  ${c}`);
    expect(
      mudas,
      'Hay clases escritas que Tailwind NO genera. El elemento que las lleva se queda sin\n' +
        `estilo, y eso no se ve en ninguna prueba que compare \`className\` como texto:\n${mudas.join('\n')}`,
    ).toEqual([]);
  });
});

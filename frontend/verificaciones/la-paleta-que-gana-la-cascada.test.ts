// @vitest-environment node
//
// Lee dos archivos del disco y compara texto. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, RAIZ, rutaDe } from './artboards.ts';
import { hermanaDe, hojaDeUi } from './especificadores.ts';
import { type Regla, reglasDe } from './tailwind.ts';

/**
 * **La paleta que GANA LA CASCADA es la del artboard** (#145).
 *
 * <h2>El hueco del que sale, dicho sin rodeos</h2>
 *
 * Desde `kamayuk-lib`#23 la hoja de `@kamayuk/ui` arrastra `estilos/temas.css`, que trae las seis
 * paletas. Su primer bloque —`:root, [data-tema='institucional']`— entra **fuera de toda capa**, y
 * en la cascada (CSS Cascade 5) lo que esta fuera de toda capa le gana a lo que esta dentro de una
 * **con independencia del orden y de la especificidad**. El `@theme` de Tailwind sale dentro de
 * `@layer theme`. O sea que **lo que el navegador pinta no es el `@theme`: es ese bloque**.
 *
 * Y contra el artboard nadie lo comparaba. El barrido de #145:
 *
 * ```
 *   la-paleta-cuadra-con-el-artboard   lee `estilos.css` como texto, sin seguir el @import   no
 *   tailwind-emite-las-clases (#139)   lee el @theme, acotado a `@layer theme`               no, a proposito
 *   e2e/los-temas-llegan-al-navegador   dos tokens por paleta, de las seis                    los roza
 *   e2e/se-ve                           dos tokens en el navegador                            dos
 * ```
 *
 * La paleta que se comparaba token a token contra el artboard era **la que no se pinta**.
 *
 * <h2>CUAL VIGILA QUE, ahora que son dos (AC2)</h2>
 *
 * Las dos se quedan, porque miden dos cosas distintas y las dos pueden romperse solas:
 *
 * ```
 *   la-paleta-cuadra-con-el-artboard   el @theme de `estilos.css`     DE DONDE SALEN LAS UTILIDADES
 *   tailwind-emite-las-clases (#139)   el @theme ya compilado         que `--color-x` genere `bg-x`
 *   ESTA                               el bloque sin capa de temas.css  LO QUE EL NAVEGADOR PINTA
 * ```
 *
 * El `@theme` no es decorativo aunque no se pinte: Tailwind v4 saca `bg-*`, `text-*` y `border-*`
 * **solo** de `--color-*` declarados en el `@theme`, y con el torcido la utilidad se genera con el
 * valor equivocado aunque la variable acabe valiendo otra cosa —porque la utilidad no lleva el
 * hexadecimal, lleva `var(--color-x)`, y ese lo resuelve luego la cascada—. Al reves, un `@theme`
 * impecable con este bloque torcido pinta la pantalla de un color que nadie decidio.
 *
 * Asi que: **el `@theme` decide que utilidades EXISTEN; este bloque decide de que color SE VEN.**
 * Ninguna de las dos comprobaciones cubre a la otra, y por eso #139 —acotar la lectura del `@theme`
 * a `@layer theme`— no se deshace: sin ese acotado, una mutacion del `@theme` se la tapaba este
 * mismo bloque, que repite los valores.
 *
 * Lo que este archivo NO comprueba, porque el bloque no los declara: los radios y las sombras. De
 * esos manda el `@theme` —y el `:root` de `estilos.css` para `--radius`—, y los vigila
 * `la-paleta-cuadra-con-el-artboard`.
 *
 * <h2>El centinela: como se encuentra el bloque, y por que NO por su comentario</h2>
 *
 * El archivo rotula cada bloque con un comentario —`/* institucional/claro *\/`—, y es lo primero
 * que se rompe: es un comentario de un archivo generado. Buscarlo asi dejaria esta guarda sin
 * sujeto el dia que el generador cambie de rotulo, y una guarda sin sujeto **pasa en verde**.
 *
 * Se busca por **lo que hace que gane**, que es lo unico que no puede cambiar sin que cambie lo
 * pintado: una regla que no esta dentro de ninguna *at-rule* —ni `@media`, ni `@layer`, ni
 * `@supports`— y cuya lista de selectores incluye `:root` **a secas**. Es la que le toca a un
 * documento que no ha elegido nada.
 *
 * Y encima se contrasta con una **tercera fuente**, que es la que contesta a «¿y si
 * `institucional/claro` deja de ser el bloque de origen?»: la identidad que ESTE frontend estampa
 * por omision, leida de `src/aplicacion.tsx`. Se exige que el mismo bloque lleve tambien su
 * `[data-tema='…']`. Si la libreria reorganizara las seis paletas de modo que el documento por
 * omision cayera en otra regla, aqui sale rojo nombrando las dos — no verde comparando la de ayer.
 *
 * Las cuatro formas de quedarse sin sujeto tienen su rojo, y las cuatro se demostraron (#145):
 * que la hoja deje de arrastrar `temas.css` —lo dice `hermanaDe`—, que el `@import` la encape
 * —entonces «gana la cascada» deja de ser cierto por regla—, que no haya bloque sin capa con
 * `:root`, y que el bloque se lea vacio.
 */

/** Un artboard declarado en `artboards.ts`, por el final de su nombre de archivo. */
function artboardDeclarado(sufijo: string): string {
  const declarado = ARTBOARDS.find((a) => a.archivo.endsWith(sufijo));
  if (declarado === undefined) {
    throw new Error(
      `«${sufijo}» no esta declarado en \`artboards.ts\`. Sin el, esta guarda no sabe contra que ` +
        'comparar.',
    );
  }
  return rutaDe(declarado);
}

/** La hoja del artboard: quien DECLARA los tokens. */
const HOJA_DEL_ARTBOARD = artboardDeclarado('rentas-tokens.css');

/** El artboard en si: quien los USA. La tercera fuente que impide fijar una cifra (#118). */
const DIBUJO_DEL_ARTBOARD = artboardDeclarado('RentasV8.dc.html');

/**
 * La hoja publicada, y la vecina que ella arrastra.
 *
 * Las dos POR EL ESPECIFICADOR (#138): `@kamayuk/ui/estilos.css` pasa por el `exports` del
 * paquete, y `temas.css` se alcanza desde ahi siguiendo el `@import` relativo que esa hoja
 * escribe — que es el unico camino por el que llega al navegador. Una ruta al clon hermano leeria
 * el archivo aunque el paquete hubiera dejado de publicarlo o de arrastrarlo.
 */
const HOJA_PUBLICADA = hojaDeUi();
const HOJA_DE_LOS_TEMAS = hermanaDe(HOJA_PUBLICADA, './temas.css');

/** `--azul: #005284;` -> `['--azul', '#005284']`, sin comentarios de por medio. */
function declaraciones(css: string): Map<string, string> {
  const sinComentarios = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  const salida = new Map<string, string>();
  for (const [, nombre, valor] of sinComentarios.matchAll(/(--[a-z0-9-]+)\s*:\s*([^;]+);/gi)) {
    salida.set(nombre ?? '', (valor ?? '').trim());
  }
  return salida;
}

/** `var(--azul)` -> `'--azul'`. USOS, y no declaraciones: la tercera fuente del centinela. */
function usados(texto: string): readonly string[] {
  return [...new Set([...texto.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)].map(([, n]) => n ?? ''))].sort();
}

/** Dos valores de CSS son el mismo si solo se diferencian en espacios y en mayusculas. */
const igual = (a: string, b: string): boolean =>
  a.replace(/\s+/g, ' ').trim().toLowerCase() === b.replace(/\s+/g, ' ').trim().toLowerCase();

/** El nombre con que la paleta llama a un token del artboard. `--azul` -> `--color-azul`. */
const comoLoLlamaLaPaleta = (nombre: string): string => `--color-${nombre.slice(2)}`;

const esRadio = (nombre: string): boolean => nombre.startsWith('--radio');
const esSombra = (nombre: string): boolean => nombre.startsWith('--sombra');
/** Los que el bloque declara: los colores. Radios y sombras van por el `@theme` — ver el javadoc. */
const esColor = (nombre: string): boolean => !esRadio(nombre) && !esSombra(nombre);

/**
 * **La identidad que este frontend estampa cuando nadie ha elegido nada.**
 *
 * Leida de la costura de ESTE repositorio —`src/aplicacion.tsx`— y no escrita aqui: es la tercera
 * fuente del centinela, la que hace que «el bloque de origen» no sea una suposicion sobre el
 * nombre de un comentario de la libreria.
 */
function identidadPorOmision(): string {
  const costura = join(RAIZ, 'src', 'aplicacion.tsx');
  const escrita = /identidadPorOmision:\s*'([a-z0-9-]+)'/.exec(readFileSync(costura, 'utf8'));
  if (escrita?.[1] === undefined) {
    throw new Error(
      `«${costura}» ya no declara \`identidadPorOmision\`.\n` +
        '  Esa es la tercera fuente de esta guarda: sin ella no se puede comprobar que el bloque\n' +
        '  que gana la cascada sea el de la identidad que este frontend sirve de fabrica, y la\n' +
        '  comprobacion se quedaria apoyada en un comentario de un archivo generado.',
    );
  }
  return escrita[1];
}

const IDENTIDAD_POR_OMISION = identidadPorOmision();

/**
 * **El bloque que gana**: fuera de toda *at-rule* y con `:root` a secas entre sus selectores.
 *
 * Devuelve todos los candidatos —y no el primero— a proposito: que haya DOS es tan roto como que
 * no haya ninguno, porque entonces cual pinta depende del orden, que es justo lo que estar fuera
 * de capa venia a quitar de en medio.
 */
function bloquesQueGanan(reglas: readonly Regla[]): readonly Regla[] {
  return reglas.filter((r) => r.dentroDe.length === 0 && r.selectores.includes(':root'));
}

/** Como la hoja publicada arrastra `temas.css`, tal cual lo escribe. */
function comoSeArrastra(): string {
  const css = readFileSync(HOJA_PUBLICADA, 'utf8').replace(/\/\*[\s\S]*?\*\//g, ' ');
  const escrito = /@import\s+(?:url\(\s*)?['"]\.\/temas\.css['"][^;]*/.exec(css);
  // El `@import` existe: lo acaba de comprobar `hermanaDe` al construir `HOJA_DE_LOS_TEMAS`.
  return (escrito?.[0] ?? '').replace(/\s+/g, ' ').trim();
}

/**
 * **Un desvio DECLARADO del artboard, con su motivo y sus dos valores.**
 *
 * No es una exencion: es un pin. Se escriben los DOS valores —el que el artboard dibuja y el que
 * la libreria deriva— y se exigen los dos. Si la libreria mueve el suyo, rojo. Si el artboard
 * mueve el suyo, rojo tambien. Lo unico que la lista compra es que el rojo no salte HOY por algo
 * que ya esta decidido y medido en otro repositorio.
 */
interface Desvio {
  /** El token, con el nombre que usa la paleta. */
  readonly token: string;
  /** Lo que el artboard dibuja. */
  readonly artboard: string;
  /** Lo que la libreria deriva para la combinacion que gana. */
  readonly pintado: string;
  /** Por que, con el issue que lo decidio. */
  readonly porque: string;
}

/**
 * Los desvios declarados. **Hoy hay uno, y lo encontro esta misma guarda al escribirse.**
 *
 * `kamayuk-lib`#41: el disco del avatar se pinta con `--barra-realce` DENTRO del boton de sesion,
 * que al pasar el raton se tiñe de `--barra-hover`. O sea que bajo las iniciales hay **dos velos
 * blancos apilados** y su alfa no se suma: se compone. Con los del artboard —0.20 y 0.18— eso deja
 * `--sobre-barra` en 4.4973:1, que WCAG 1.4.3 no pasa y que estaba escrito como «4.50» por el
 * redondeo. La libreria reparte la rebaja dejando el REPOSO intacto —0.09 y 0.20, byte a byte los
 * del artboard— y bajando solo el hover.
 *
 * Se anota aqui, en el consumidor, porque es una diferencia entre lo que el artboard dibuja y lo
 * que la pantalla se ve, y hasta hoy **no estaba escrita en ningun sitio de este lado**.
 */
const DESVIOS: readonly Desvio[] = [
  {
    token: '--color-barra-hover',
    artboard: 'rgba(255, 255, 255, 0.18)',
    pintado: 'rgba(255, 255, 255, 0.17)',
    porque:
      'kamayuk-lib#41: con el 0.18 del artboard, las iniciales del avatar quedan en 4.4973:1 ' +
      'sobre los dos velos blancos apilados, y WCAG 1.4.3 pide 4.5:1. El reposo de la barra ' +
      'sigue siendo el del artboard; lo unico que cede es el hover.',
  },
];

const delArtboard = declaraciones(readFileSync(HOJA_DEL_ARTBOARD, 'utf8'));
const PINTADOS_POR_EL_ARTBOARD = usados(readFileSync(DIBUJO_DEL_ARTBOARD, 'utf8'));
const REGLAS = reglasDe(readFileSync(HOJA_DE_LOS_TEMAS, 'utf8'));
const CANDIDATOS = bloquesQueGanan(REGLAS);
/** Lo que el bloque declara, o vacio si no hay exactamente uno: el centinela lo dice primero. */
const pintado: ReadonlyMap<string, string> =
  CANDIDATOS.length === 1 ? (CANDIDATOS[0]?.declaraciones ?? new Map()) : new Map();

/** Los colores del artboard, que son los que el bloque tiene que traer. */
const COLORES_DEL_ARTBOARD = [...delArtboard].filter(([n]) => esColor(n));

/** Lo que se espera pintado para un token del artboard: su valor, o el del desvio declarado. */
function loQueDeberiaPintarse(token: string, delDibujo: string): string {
  return DESVIOS.find((d) => d.token === token)?.pintado ?? delDibujo;
}

describe('la paleta que GANA LA CASCADA es la del artboard', () => {
  it('EL CENTINELA: hay UN bloque sin capa con `:root`, y es el de la identidad por omision', () => {
    // 1) La premisa entera: si el `@import` encapara la hoja, «gana la cascada» dejaria de ser
    //    cierto POR REGLA y pasaria a depender del orden de emision y de la especificidad —que es
    //    exactamente lo que la libreria dice que no quiere que dependa—. El sujeto de esta guarda
    //    seguiria existiendo, pero ya no seria «lo que se pinta».
    const arrastre = comoSeArrastra();
    expect(
      arrastre.includes('layer'),
      `La hoja publicada arrastra los temas asi: «${arrastre}».\n` +
        '  Con `layer(…)` las seis paletas caen DENTRO de una capa, y entonces cual gana depende\n' +
        '  del orden en que Tailwind emita los dos bloques y del peso de los selectores. Esta\n' +
        '  guarda dice «el bloque que gana» y dejaria de poder decirlo. Si el cambio es\n' +
        '  deliberado, hay que volver a decidir QUE se compara contra el artboard.',
    ).toBe(false);

    // 2) El bloque existe, y es UNO. Dos es tan roto como ninguno: vuelve a decidirlo el orden.
    const aNivelSuperior = REGLAS.filter((r) => r.dentroDe.length === 0).map((r) =>
      r.selectores.join(', '),
    );
    expect(
      CANDIDATOS.map((r) => r.selectores.join(', ')),
      'No hay UN solo bloque fuera de toda capa con `:root` a secas en `temas.css`.\n' +
        '  Los que hay a nivel superior son:\n' +
        `${aNivelSuperior.map((s) => `    ${s}`).join('\n')}\n\n` +
        '  Con ninguno, esta guarda no sabe que comparar y lo dice en vez de comparar nada. Con\n' +
        '  dos, cual pinta vuelve a depender del orden — que es justo lo que estar fuera de capa\n' +
        '  venia a quitar de en medio. En los dos casos hay que volver a mirar como reparte la\n' +
        '  libreria sus seis paletas ANTES de tocar nada aqui.',
    ).toHaveLength(1);

    // 3) Y no se lee vacio. Un cambio de formato que `reglasDe` no supiera partir dejaria el mapa
    //    a cero, y las comparaciones de abajo pasarian en verde sobre el conjunto vacio.
    expect(
      [...pintado.keys()].filter((n) => n.startsWith('--color-')).length,
      `«${HOJA_DE_LOS_TEMAS}» no declaro ni un \`--color-*\` en el bloque que gana: se leyo vacio.`,
    ).toBeGreaterThan(0);

    // 4) LA TERCERA FUENTE, y la respuesta a «¿y si `institucional/claro` deja de ser el bloque de
    //    origen?». No se busca el comentario que rotula el bloque —es el rotulo de un
    //    archivo generado, o sea lo primero que se mueve—: se lee la identidad que ESTE frontend
    //    estampa de fabrica y se exige que caiga en el MISMO bloque que el `:root` desnudo.
    const selectores = CANDIDATOS[0]?.selectores ?? [];
    const suyo = selectores.filter((s) => s.includes(`data-tema=`));
    expect(
      suyo.some((s) => s.includes(`'${IDENTIDAD_POR_OMISION}'`) || s.includes(`"${IDENTIDAD_POR_OMISION}"`)),
      `El bloque que gana con un documento desnudo es «${selectores.join(', ')}», y este frontend ` +
        `estampa \`data-tema='${IDENTIDAD_POR_OMISION}'\` de fabrica (\`src/aplicacion.tsx\`).\n` +
        '  Son dos reglas distintas, o sea que lo que se ve de fabrica NO es lo que esta guarda\n' +
        '  esta comparando. O la libreria reorganizo las seis paletas, o esta costura cambio de\n' +
        '  identidad por omision — y en los dos casos hay que volver a decidir cual es el bloque\n' +
        '  de origen antes de que esto vuelva a verde.',
    ).toBe(true);

    // 5) Y el artboard se leyo. Igual que en `la-paleta-cuadra-con-el-artboard`, el suelo no es
    //    una cifra sino los `var(--…)` que el `.dc.html` pinta: una tercera lectura, de otro
    //    archivo y de otra manera, que un cambio de formato en la hoja de tokens no vacia.
    expect(
      PINTADOS_POR_EL_ARTBOARD.length,
      'el `.dc.html` no pinta ni un `var(--…)`: no hay contra que contrastar',
    ).toBeGreaterThan(0);

    const sinDeclarar = PINTADOS_POR_EL_ARTBOARD.filter((n) => !delArtboard.has(n));
    expect(
      sinDeclarar,
      'El artboard pinta tokens que su hoja no declara:\n' +
        `${sinDeclarar.map((n) => `  ${n}`).join('\n')}\n\n` +
        '  O `rentas-tokens.css` se vendorizo a medias, o dejo de leerse — y entonces lo que se\n' +
        '  compara contra lo pintado es menos paleta de la que el artboard dibuja.',
    ).toEqual([]);
  });

  it('CADA color del artboard se pinta con su valor', () => {
    const ausentes: string[] = [];
    const torcidos: string[] = [];
    for (const [nombre, valorDelArtboard] of COLORES_DEL_ARTBOARD) {
      const token = comoLoLlamaLaPaleta(nombre);
      const valor = pintado.get(token);
      if (valor === undefined) {
        ausentes.push(`  ${token}: el artboard declara «${nombre}» y el bloque no lo pinta`);
        continue;
      }
      const esperado = loQueDeberiaPintarse(token, valorDelArtboard);
      if (!igual(valor, esperado)) {
        const desvio = DESVIOS.find((d) => d.token === token);
        torcidos.push(
          `  ${token}: se pinta «${valor}» y deberia «${esperado}»` +
            (desvio === undefined
              ? ` (el artboard dice «${valorDelArtboard}»)`
              : `\n      — es un desvio declarado: ${desvio.porque}`),
        );
      }
    }

    expect(
      [...ausentes, ...torcidos],
      'Lo que el navegador PINTA dejo de ser lo que el artboard dibuja:\n' +
        `${[...ausentes, ...torcidos].join('\n')}\n\n` +
        '  Esto no es el `@theme`: es el bloque sin capa de `temas.css`, que le gana en la\n' +
        '  cascada. Un `@theme` impecable con esto torcido pinta la pantalla de un color que\n' +
        '  nadie decidio, y ninguna de las otras guardas lo ve (#145).\n' +
        '  Si el cambio es deliberado y viene de la libreria —como el de `kamayuk-lib`#41—, se\n' +
        '  anota en `DESVIOS` con su motivo y sus DOS valores. Si no, entra primero en el\n' +
        '  artboard y de ahi se deriva la paleta: al reves, no.',
    ).toEqual([]);
  });

  it('y el bloque no pinta ningun color que el artboard no declare', () => {
    // La otra direccion. Un token inventado aqui se ve en una pantalla, se ve bien, y no esta en
    // ningun artboard: asi es como una paleta empieza a tener colores que nadie decidio.
    const esperados = new Set(COLORES_DEL_ARTBOARD.map(([n]) => comoLoLlamaLaPaleta(n)));
    const sobrantes = [...pintado.keys()].filter((n) => n.startsWith('--') && !esperados.has(n));

    expect(
      sobrantes,
      'El bloque que gana pinta tokens que el artboard no declara:\n' +
        `${sobrantes.map((n) => `  ${n}: ${pintado.get(n) ?? ''}`).join('\n')}\n\n` +
        '  Ojo con `color-scheme`, que NO es un token: el bloque claro no la declara a proposito\n' +
        '  —el `:root` de `estilos.css` ya dice `light`— y los tres oscuros si (kamayuk-lib#33).\n' +
        '  Si aparece aqui, es que el bloque que gana dejo de ser el claro.',
    ).toEqual([]);
  });

  it('los DESVIOS declarados siguen siendo desvios, y siguen siendo los del artboard', () => {
    // Un pin que ya no pinta nada es peor que no tenerlo: exime de una comparacion que hoy pasaria
    // sola. Y un pin cuyo lado del artboard se quedo viejo compara contra un dibujo que ya no
    // existe. Las dos se dicen por nombre.
    const huerfanos = DESVIOS.filter((d) => !delArtboard.has(`--${d.token.slice('--color-'.length)}`))
      .map((d) => `  ${d.token}: el artboard ya no declara ese token`);

    const desfasados = DESVIOS.flatMap((d) => {
      const enElArtboard = delArtboard.get(`--${d.token.slice('--color-'.length)}`);
      if (enElArtboard === undefined || igual(enElArtboard, d.artboard)) return [];
      return [`  ${d.token}: el desvio dice que el artboard vale «${d.artboard}» y vale «${enElArtboard}»`];
    });

    const yaNoDesvian = DESVIOS.filter((d) => igual(d.artboard, d.pintado)).map(
      (d) => `  ${d.token}: el desvio declara el mismo valor en los dos lados`,
    );

    expect(
      [...huerfanos, ...desfasados, ...yaNoDesvian],
      'La lista de desvios declarados se quedo vieja:\n' +
        `${[...huerfanos, ...desfasados, ...yaNoDesvian].join('\n')}\n\n` +
        '  `DESVIOS` no es una exencion sino un pin: fija los DOS valores. Si el artboard movio\n' +
        '  el suyo, lo que hay que rehacer es la medicion del otro repositorio, no esta lista.\n' +
        '  Y un desvio que ya no desvia se BORRA: mientras siga escrito, exime de una comparacion\n' +
        '  que hoy pasaria sola.',
    ).toEqual([]);
  });
});

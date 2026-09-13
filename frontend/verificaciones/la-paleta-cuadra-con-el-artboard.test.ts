// @vitest-environment node
//
// Lee dos archivos del disco y compara texto. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, rutaDe } from './artboards.ts';
import { hojaDeUi } from './especificadores.ts';

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
 * <h2>QUE VIGILA ESTA, ahora que son dos (#145)</h2>
 *
 * **Esta vigila el `@theme` de `estilos.css`, que es DE DONDE SALEN LAS UTILIDADES** — y no lo que
 * el navegador pinta. Desde `kamayuk-lib`#23 la hoja arrastra `estilos/temas.css`, cuyo primer
 * bloque entra **fuera de toda capa** y por eso le gana al `@theme`, que Tailwind emite dentro de
 * `@layer theme`. Ese bloque lo compara con el artboard `la-paleta-que-gana-la-cascada.test.ts`,
 * y hasta #145 no lo comparaba nadie.
 *
 * Las dos hacen falta y ninguna cubre a la otra. Tailwind v4 saca `bg-*`, `text-*` y `border-*`
 * **solo** de los `--color-*` del `@theme`: con el `@theme` torcido la utilidad ni se genera —o se
 * genera apuntando a un token que no existe— por muy bien que este el bloque que pinta. Y al
 * reves, un `@theme` impecable con ese bloque torcido pinta la pantalla de un color que nadie
 * decidio. **El `@theme` decide que utilidades EXISTEN; el bloque sin capa, de que color SE VEN.**
 *
 * Lo que SOLO vigila esta: los radios y las sombras. El bloque sin capa no los declara — vienen
 * del `@theme`, y `--radius` del `:root` de `estilos.css`.
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
 *
 * <h2>Por que el centinela NO cuenta (#118)</h2>
 *
 * Aquel `expected 44 to be 42` era un rojo correcto con un mensaje inutil: no nombraba el token que
 * se habia ido. Y el mismo `44` tenia el filo contrario — el dia que la libreria publicase un token
 * mas, rojo **sin que nada estuviera mal**.
 *
 * Asi que el centinela ya no fija ninguna cifra: contrasta contra **una tercera fuente**, los
 * `var(--…)` que el propio `.dc.html` pinta, y **nombra** lo que falta. Que sea una tercera fuente
 * es lo que lo mantiene en pie: se lee de otro archivo y de otra manera —USOS, no DECLARACIONES—,
 * asi que un cambio de formato que vaciara cualquiera de los dos mapas de declaraciones no la
 * vacia a ella, y el centinela conserva contra que contrastar. Sobre el conjunto vacio no pasa: lo
 * que sale es la lista entera de tokens que dejaron de estar.
 *
 * El dia que `temas.css` salga del paquete (kamayuk-lib#23) y se mueva el numero de tokens
 * visibles, esta prueba dira **cuales** faltan o **cuales** sobran, que es lo unico accionable.
 * Por lo mismo no queda ni una cuenta en el resto del archivo: los radios que shadcn lee se exigen
 * por nombre.
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

/** El artboard en si: quien los USA. Es la tercera fuente del centinela — ver el javadoc. */
const DIBUJO_DEL_ARTBOARD = artboardDeclarado('RentasV8.dc.html');

/**
 * El `@theme` de `@kamayuk/ui`, alcanzado POR EL ESPECIFICADOR que el codigo escribe.
 *
 * Que se resuelva por `@kamayuk/ui/estilos.css` y no por `../../kamayuk-lib/...` no es un detalle
 * de estilo: es lo que hace que esta guarda comprobe la paleta **que este frontend usa de verdad**.
 * Una ruta al hermano leeria el archivo aunque el `link:` estuviera roto.
 *
 * Y desde #138 tampoco es `join(raiz, 'estilos', 'estilos.css')`: eso leia el archivo aunque el
 * `exports` del paquete ya no lo publicara —o sea, aunque el bundle no pudiera importarlo—, que es
 * el modo de fallo que `especificadores.ts` cuenta entero.
 */
const PALETA_DE_LA_LIBRERIA = hojaDeUi();

/** `var(--azul)` -> `'--azul'`. Se leen USOS, y no declaraciones: es la otra mitad del centinela. */
function usados(texto: string): readonly string[] {
  return [...new Set([...texto.matchAll(/var\(\s*(--[a-z0-9-]+)/gi)].map(([, n]) => n ?? ''))].sort();
}

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

/**
 * Los tokens que el artboard PINTA, leidos de sus `var(--…)`.
 *
 * De aqui sale que el centinela no tenga que fijar un numero: lo que exige es que cada token que
 * el dibujo usa este declarado en la hoja y publicado por la libreria. Es un **suelo**, no una
 * cuenta — la libreria puede publicar mas sin que esto se mueva, y si publica de mas lo dice la
 * guarda de sobrantes, por nombre.
 */
const PINTADOS_POR_EL_ARTBOARD = usados(readFileSync(DIBUJO_DEL_ARTBOARD, 'utf8'));

/** `color-scheme` no es un token: es una declaracion de la pagina. */
const TOKENS_DEL_ARTBOARD = [...delArtboard].filter(([n]) => n.startsWith('--'));
/** Los que se comparan uno a uno: todos menos los radios. */
const UNO_A_UNO = TOKENS_DEL_ARTBOARD.filter(([n]) => !esRadio(n));

describe('la paleta de @kamayuk/ui es la del artboard', () => {
  it('EL CENTINELA: las dos lecturas traen los tokens que el artboard pinta', () => {
    // Sin esto, un cambio de formato en cualquiera de los dos archivos dejaria su mapa VACIO, y
    // la comparacion de abajo pasaria en verde comparando nada — que es como una guarda se queda
    // sin sujeto sin que nadie la borre.
    //
    // Lo que exige NO es una cifra, sino un suelo tomado de una tercera fuente: los `var(--…)` que
    // el `.dc.html` pinta. Asi el rojo NOMBRA lo que falta, y publicar un token de mas no lo mueve.
    expect(
      PINTADOS_POR_EL_ARTBOARD.length,
      'el `.dc.html` no pinta ni un `var(--…)`: el centinela se quedo sin contra que contrastar, ' +
        'y sin el las comparaciones de abajo pasarian sobre el conjunto vacio',
    ).toBeGreaterThan(0);

    const sinDeclarar = PINTADOS_POR_EL_ARTBOARD.filter((n) => !delArtboard.has(n));
    expect(
      sinDeclarar,
      'El artboard pinta tokens que su hoja no declara:\n' +
        `${sinDeclarar.map((n) => `  ${n}`).join('\n')}\n\n` +
        '  O `rentas-tokens.css` se vendorizo a medias, o dejo de leerse — y entonces lo que se\n' +
        '  compara contra la libreria es menos paleta de la que el artboard dibuja.',
    ).toEqual([]);

    const sinPublicar = [
      // El knob que shadcn lee: si no esta, no hay radio que comprobar.
      '--radius',
      ...PINTADOS_POR_EL_ARTBOARD.filter((n) => !esRadio(n)).map(comoLoLlamaTailwind),
    ].filter((n) => !deLaLibreria.has(n));
    expect(
      sinPublicar,
      'La libreria dejo de publicar tokens que el artboard pinta:\n' +
        `${sinPublicar.map((n) => `  ${n}`).join('\n')}\n\n` +
        '  Si el `@theme` de `@kamayuk/ui` se leyo vacio, aqui sale la paleta entera. Si solo son\n' +
        '  algunos, o se renombraron sin avisar al consumidor (kamayuk-lib#8) o se fueron con la\n' +
        '  hoja que los traia (kamayuk-lib#23) — y en ese caso el artboard los sigue pintando.',
    ).toEqual([]);
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
      'La libreria publica tokens que el artboard no declara:\n' +
        `${sobrantes.map((n) => `  ${n}`).join('\n')}\n\n` +
        '  Un color que nadie dibujo es un color que nadie decidio. Este es el rojo que sale\n' +
        '  cuando la libreria publica uno de mas, y por eso el centinela ya no cuenta: contar\n' +
        '  decia «expected 45 to be 44» sin nombrar cual (#118).\n' +
        '  Si el token es deliberado, entra primero en el artboard y de ahi se deriva el `@theme`.',
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
    // Los cuatro que shadcn lee, POR NOMBRE y no por cuenta: `toBeGreaterThanOrEqual(4)` se cumplia
    // con cuatro nombres cualesquiera, y saldria rojo —sin que nada estuviera mal— el dia que
    // shadcn anadiera un tamano. Nombrarlos dice CUAL falto, que es lo unico accionable (#118).
    const QUE_SHADCN_LEE = ['--radius', '--radius-sm', '--radius-md', '--radius-lg'];
    const faltan = QUE_SHADCN_LEE.filter((n) => !deLaLibreria.has(n));
    expect(
      faltan,
      'La libreria dejo de publicar radios que shadcn lee:\n' +
        `${faltan.map((n) => `  ${n}`).join('\n')}\n\n` +
        '  Sin `--radius` los componentes de shadcn caen en su `0.625rem` por omision, que no es\n' +
        '  el radio del artboard.',
    ).toEqual([]);

    const distintos = publicados
      .filter(([, v]) => v !== esperado)
      .map(([n, v]) => `  ${n}: «${v}», y el artboard dice «${esperado ?? ''}»`);
    expect(
      distintos,
      `Un radio publicado se aparto del que dibuja el artboard:\n${distintos.join('\n')}`,
    ).toEqual([]);
  });
});

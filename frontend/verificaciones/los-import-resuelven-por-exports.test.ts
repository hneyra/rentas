// @vitest-environment node
//
// Resuelve modulos y lee archivos del disco. No es un DOM lo que necesita.

import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  ESPECIFICADOR_DE_LA_HOJA,
  hojasDe,
  importesDe,
  importesDeLaLibreria,
  importesQueNoResuelven,
  hojaDeUi,
} from './especificadores.ts';

/**
 * **Cada `@import '@kamayuk/…'` del codigo resuelve POR EL `exports` de su paquete** (#138).
 *
 * <h2>El hueco que cierra, medido</h2>
 *
 * `kamayuk-lib`#24 convirtio el `exports` de cada paquete en un contrato con guarda propia. De este
 * lado no habia nada atado a el. Quitando `"./estilos.css"` del `exports` de `@kamayuk/ui`
 * —exactamente lo que #24 hizo con `"./fuentes"`—:
 *
 *     $ npx vitest run verificaciones/ src/
 *      Tests  567 passed (567)          <- VERDE
 *     $ npx vite build
 *     [@tailwindcss/vite:generate:build] Missing "./estilos.css" specifier in "@kamayuk/ui" package
 *     file: …/src/estilos.css
 *
 * O sea: `yarn verificar` entero en verde con la libreria publicando un paquete del que este
 * frontend **no puede importar su hoja**. Lo cazaba `yarn build`, que es el paso siguiente y no el
 * que se corre antes de commitear.
 *
 * <h2>Por que una guarda y no `yarn build` dentro de `yarn verificar`</h2>
 *
 * Porque el hueco no es «no se construye el bundle»: es «nada pregunta por el contrato». Construir
 * el bundle lo contesta de rebote, cuesta el empaquetado entero y contradice lo que `yarn verificar`
 * dice ser —lint, tipos, i18n y pruebas, sin navegador y sin backend—. Y ya corre en la CI, justo
 * despues (`frontend.yml`). Lo que faltaba es que la pregunta se hiciera **aqui**, en la orden que
 * se corre antes de commitear, y con un rojo que nombre el paquete, el subcamino y donde se
 * arregla. El razonamiento entero y sus cifras estan en `docs/agent/HISTORY.md`, fila de #138.
 *
 * <h2>La lista se DESCUBRE, no se escribe</h2>
 *
 * Se lee del arbol: toda hoja de `src/` —que es lo que F-1 declara codigo de produccion— y de ella
 * sus `@import`. Una lista escrita a mano se queda vieja el dia que alguien anade el segundo
 * `@import`, que es justo el dia en que el segundo no estaria vigilado.
 *
 * Por eso el centinela no es decorativo: si el extractor dejara de encontrarlos —una hoja movida,
 * un `@import` escrito de otra forma—, esta guarda pasaria en verde sobre el conjunto vacio, que es
 * como una guarda se queda sin sujeto sin que nadie la borre.
 *
 * <h2>Y por que solo los de `@kamayuk/*`</h2>
 *
 * Porque son los unicos cuyo `exports` es un contrato entre dos repositorios. `@import
 * "tailwindcss"` tambien es un especificador de paquete, pero no lo resuelve Node: lo intercepta
 * el propio plugin de Tailwind por su nombre, y preguntarle a `require.resolve` daria una respuesta
 * que no es la que el empaquetador usa.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const RAIZ_DEL_FRONTEND = dirname(AQUI);

/** Donde vive el codigo de produccion de este frontend (F-1). */
const CODIGO = join(RAIZ_DEL_FRONTEND, 'src');

/** Un requeridor con la base de LA HOJA que escribe el `@import`, como el empaquetador. */
const requeridorDe = (hoja: string): { resolve(peticion: string): string } => createRequire(hoja);

/** Los `@import` de paquete que el codigo escribe hoy, descubiertos y no listados. */
const IMPORTES = hojasDe(CODIGO).flatMap((hoja) =>
  importesDeLaLibreria(hoja, readFileSync(hoja, 'utf8')),
);

describe('los `@import` de `@kamayuk/*` pasan por el `exports`', () => {
  it('EL CENTINELA: el codigo escribe alguno, y el extractor discrimina', () => {
    // 1) Sin esto, todo lo de abajo pasaria sobre el conjunto vacio.
    expect(
      IMPORTES.map((i) => `${relative(RAIZ_DEL_FRONTEND, i.hoja)}:${i.linea} ${i.especificador}`),
      `No se encontro ni un \`@import '@kamayuk/…'\` en «${CODIGO}».\n` +
        '  O el codigo dejo de importar la hoja de la libreria —y entonces la pantalla se dibuja\n' +
        '  sin paleta—, o el extractor dejo de encontrarlos y esta guarda se quedo sin sujeto.',
    ).not.toEqual([]);

    // 2) Y que lo que encuentra son `@import` de verdad: ni los relativos, que no pasan por
    //    ningun `exports`, ni los que viven dentro de un comentario. Lo segundo no es hipotetico:
    //    la hoja de `@kamayuk/ui` escribe `@import '@kamayuk/ui/estilos.css'` DENTRO de un
    //    comentario, explicando lo que hace el consumidor.
    const muestra = [
      "/* asi lo escribe el consumidor: @import '@kamayuk/ui/mentira.css'; */",
      "@import './vecina.css';",
      '@import url("@kamayuk/ui/estilos.css") layer(base);',
    ].join('\n');
    expect(importesDe(muestra)).toEqual(['./vecina.css', '@kamayuk/ui/estilos.css']);
    expect(importesDeLaLibreria('hoja.css', muestra).map((i) => i.especificador)).toEqual([
      '@kamayuk/ui/estilos.css',
    ]);
  });

  it('todos resuelven', () => {
    const sinSalida = importesQueNoResuelven(requeridorDe, IMPORTES);

    expect(
      sinSalida.map((p) => `${p.donde}\n  ${p.porque}`),
      'Hay `@import` que el empaquetador no puede resolver:\n' +
        `${sinSalida.map((p) => `  ${p.donde} -> ${p.especificador}`).join('\n')}\n\n` +
        '  Esto es un `yarn build` roto, dicho un paso antes.',
    ).toEqual([]);
  });

  /**
   * **LA MUESTRA**: un paquete inventado que publica una hoja y **esconde la de al lado**.
   *
   * Es el equivalente de las `muestras/` para una guarda que no mira codigo sino resolucion. Y lo
   * que la hace valer es la segunda mitad: la hoja escondida **existe en el disco**. Si esta guarda
   * mirara el archivo —que es lo que hacian las tres que este issue cambia— la encontraria y diria
   * que si. Lo que se exige es otra cosa: que el paquete la PUBLIQUE.
   *
   * Se levanta en un directorio temporal y no en el arbol: `node_modules` esta en el `.gitignore`,
   * y un paquete de muestra tiene que vivir en un `node_modules` para que Node lo busque por su
   * nombre. Asi ademas no se toca el clon hermano, que es compartido.
   */
  it('LA MUESTRA: un subcamino que existe pero no se publica sale rojo', () => {
    const raiz = mkdtempSync(join(tmpdir(), 'kamayuk-exports-'));
    const paquete = join(raiz, 'node_modules', '@muestra', 'hojas');
    mkdirSync(paquete, { recursive: true });
    writeFileSync(
      join(paquete, 'package.json'),
      JSON.stringify({
        name: '@muestra/hojas',
        version: '0.0.0',
        exports: { '.': './index.js', './publicada.css': './publicada.css' },
      }),
    );
    writeFileSync(join(paquete, 'index.js'), 'export default 1;\n');
    writeFileSync(join(paquete, 'publicada.css'), '.publicada {}\n');
    writeFileSync(join(paquete, 'escondida.css'), '.escondida {}\n');

    const hoja = join(raiz, 'estilos.css');
    writeFileSync(
      hoja,
      "@import '@muestra/hojas/publicada.css';\n@import '@muestra/hojas/escondida.css';\n",
    );

    // El archivo ESTA. Es la mitad que hace que esta prueba signifique algo.
    expect(existsSync(join(paquete, 'escondida.css'))).toBe(true);

    // El extractor filtra por `@kamayuk/` —eso lo mide el centinela—, asi que aqui los dos
    // `@import` del paquete inventado se le pasan a mano: lo que se ejercita es la resolucion.
    expect(importesDeLaLibreria(hoja, readFileSync(hoja, 'utf8'))).toEqual([]);
    const sinSalida = importesQueNoResuelven(requeridorDe, [
      { hoja, especificador: '@muestra/hojas/publicada.css', linea: 1 },
      { hoja, especificador: '@muestra/hojas/escondida.css', linea: 2 },
    ]);
    expect(sinSalida.map((p) => p.especificador)).toEqual(['@muestra/hojas/escondida.css']);
    // Y el rojo dice lo mismo que diria el empaquetador, para que quien lo lea reconozca el otro.
    expect(sinSalida[0]?.porque).toContain(
      'Missing "./escondida.css" specifier in "@muestra/hojas" package',
    );
    expect(sinSalida[0]?.porque).toContain('no publicado» no es «no esta');

    // Y LA OTRA RAMA del mensaje, que es la mitad que hace util distinguirlas: desde esa misma
    // hoja, `@kamayuk/ui` no se alcanza —ese `node_modules` solo tiene el paquete inventado—, y
    // entonces lo que falta no es una linea del `exports` sino el clon hermano. El remedio lo
    // escribe `enlace.ts`, una sola vez, y nombra el `git clone` (#113).
    const sinClon = importesQueNoResuelven(requeridorDe, [
      { hoja, especificador: ESPECIFICADOR_DE_LA_HOJA, linea: 3 },
    ]);
    expect(sinClon[0]?.porque).toContain('git clone https://github.com/hneyra/kamayuk-lib');
    expect(sinClon[0]?.porque).not.toContain('no publica');

    rmSync(raiz, { recursive: true, force: true });
  });

  it('y la hoja que las guardas leen es la MISMA que el codigo importa', () => {
    // El otro filo del mismo contrato: que las guardas no lean por un camino que la aplicacion no
    // tiene. Si `src/estilos.css` importara un especificador y las guardas otro, la libreria podria
    // dejar de publicar el de la aplicacion sin que ninguna guarda se moviera.
    const escritos = IMPORTES.map((i) => i.especificador);
    expect(
      escritos,
      `Las guardas leen «${ESPECIFICADOR_DE_LA_HOJA}» y el codigo ya no lo importa: ` +
        `${escritos.join(', ')}`,
    ).toContain(ESPECIFICADOR_DE_LA_HOJA);

    // Se resuelve AQUI DENTRO, y no en un `const` del modulo. Las otras tres guardas si lo tienen
    // arriba —lo necesitan para leer la hoja—, asi que con el `exports` roto se caen al CARGAR, y
    // vitest lo cuenta como «Failed Suites … no tests». Esta contesta como una prueba con nombre,
    // que es lo que hace que el rojo se lea de un vistazo.
    const hoja = hojaDeUi();
    expect(existsSync(hoja), `no esta la hoja de @kamayuk/ui en ${hoja}`).toBe(true);
  });
});

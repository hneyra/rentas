// @vitest-environment node
//
// Barre `src/` y lee el artboard. No es un DOM lo que necesita.

import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { ARTBOARDS, RAIZ, rutaDe } from './artboards.ts';
import { artboardV8 } from './artboard-v8.ts';

/**
 * **Ni una cifra inventada en el codigo que se sirve** (#97, y la mitad de #44 que estaba abierta).
 *
 * <h2>La historia, porque explica por que esta guarda es como es</h2>
 *
 * · Hasta la V6 la propiedad se cumplia por una via distinta: las cifras vivian en
 *   `datos/prototipo.ts` detras de una bandera, y Rollup plegaba el `import()` entero cuando
 *   estaba apagada. Medido: 193 592 bytes y cero cifras, frente a 227 205 con ellas.
 * · **#90 la rompio** —no por descuido: las cuarenta pantallas de V8 llevaban las cifras de ejemplo
 *   DENTRO de su definicion, y las definiciones se importan estaticamente—. Quedo dicho en el
 *   `Dockerfile` con su numero en vez de taparse.
 * · **#97 la recupera**: las definiciones conservan la FORMA y las cifras se quedan solo en el
 *   artboard, que viaja vendorizado, no esta bajo `src/` y no lo importa una linea de produccion.
 *
 * <h2>Por que se barre `src/` y no el `dist/`</h2>
 *
 * Porque el `dist/` hay que construirlo —diez segundos— y porque un rojo sobre el `dist` dice «hay
 * una cifra en un archivo minificado de 683 kB», que no se puede leer. Barriendo `src/` el rojo
 * nombra **el archivo y la cifra**. La comprobacion sobre lo servido sigue existiendo, en el
 * `Dockerfile`, y es la que impide publicar una imagen sucia; esta es la que lo dice antes.
 *
 * <h2>Que cifras, y por que NO todas las del artboard</h2>
 *
 * El artboard trae **346 cadenas** entre valores y celdas, y muchas son inocentes: «2026», «—»,
 * «Conforme». Buscarlas todas daria rojos sobre codigo legitimo. Se buscan las **inconfundibles**:
 * importes con separador de millares y dos decimales — `9,418,204.60`—, de los que el artboard
 * trae **cincuenta**. Ninguna cadena de esa forma aparece en codigo que no sea una cifra de
 * ejemplo.
 *
 * **Los nombres de persona quedan fuera a proposito**, aunque un «Rufina Medina Medina» en el
 * codigo seria igual de malo. El patron que los cazaria —tres palabras capitalizadas— casa tambien
 * con «Impuesto Predial Urbano», que es un rotulo legitimo y esta en las definiciones. Una guarda
 * que da rojos sobre codigo bueno se acaba desactivando, y con ella se va la que si servia. El
 * importe es la forma que **no puede** ser otra cosa, y en un sistema de recaudacion es ademas la
 * que mas dano hace: una cifra se lee como real.
 *
 * <h2>Y se omiten los COMENTARIOS, como hacen las otras guardas de este arbol</h2>
 *
 * Porque un formateador **tiene que poder ensenar un ejemplo**: `formato.ts` documenta su regla
 * con `«"1842.6" -> "S/ 1,842.60"»`, y eso es la explicacion de lo que hace, no un dato. Es la
 * misma decision que toma `sin-el-nombre-del-monolito` en `infrastructure` y por el mismo motivo.
 * Lo que no puede aparecer es en el CODIGO, que es lo que viaja.
 */

const ARTBOARD = (() => {
  const declarado = ARTBOARDS.find((a) => a.archivo.endsWith('RentasV8.dc.html'));
  if (declarado === undefined) throw new Error('`RentasV8.dc.html` no esta en `artboards.ts`.');
  return rutaDe(declarado);
})();

/** Un importe con millares y dos decimales: `9,418,204.60`. No hay forma de que sea otra cosa. */
const IMPORTE = /^\d{1,3}(?:,\d{3})+\.\d{2}$/;

/** Todas las cadenas de ejemplo del artboard: los valores de solo lectura y las celdas. */
function cifrasDelArtboard(): readonly string[] {
  const bloques = Object.values(artboardV8().pantallas).flat();
  const valores = bloques.flatMap((b) =>
    b[2].filter((c) => c[1] === 'r' || c[1] === 'r1').map((c) => String(c[2] ?? '')),
  );
  const celdas = bloques.flatMap((b) => (b[3] === undefined ? [] : b[3].f.flat()));
  return [...new Set([...valores, ...celdas])];
}

const INCONFUNDIBLES = cifrasDelArtboard().filter((s) => IMPORTE.test(s));

/** Sin comentarios de bloque ni de linea. Ver el javadoc. */
const sinComentarios = (fuente: string): string =>
  fuente.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\/\/.*$/gm, ' ');

/** Todo el codigo de produccion, archivo a archivo. */
function fuentes(dir: string): readonly { readonly ruta: string; readonly texto: string }[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((e) => {
    const ruta = join(dir, e.name);
    if (e.isDirectory()) return fuentes(ruta);
    if (!/\.tsx?$/.test(e.name) || e.name.includes('.test.')) return [];
    return [{ ruta, texto: sinComentarios(readFileSync(ruta, 'utf8')) }];
  });
}

const FUENTES = fuentes(join(RAIZ, 'src'));

describe('el codigo que se sirve no lleva cifras inventadas', () => {
  it('EL CENTINELA: el artboard trae cifras inconfundibles, y hay fuentes que barrer', () => {
    // Sin esto, un artboard que dejara de traerlas —o una ruta mal calculada— dejaria la
    // comprobacion de abajo buscando la lista vacia y pasando en verde sobre la nada. Es como este
    // repositorio se quedo sin guarda dos veces (#78, #80).
    expect(INCONFUNDIBLES.length, 'el artboard no trae ni una cifra inconfundible').toBeGreaterThan(
      40,
    );
    expect(FUENTES.length, 'no se leyo ni una fuente de `src/`').toBeGreaterThan(20);
    expect(
      readFileSync(ARTBOARD, 'utf8').length,
      'el artboard vino vacio',
    ).toBeGreaterThan(100_000);
  });

  it('ninguna aparece en `src/`', () => {
    const hallazgos = FUENTES.flatMap(({ ruta, texto }) =>
      INCONFUNDIBLES.filter((cifra) => texto.includes(cifra)).map(
        (cifra) => `  ${ruta}: «${cifra}»`,
      ),
    );
    expect(
      hallazgos,
      'Hay cifras de ejemplo del artboard en el codigo que se sirve:\n' +
        `${hallazgos.join('\n')}\n\n` +
        '  En un sistema de recaudacion una cifra se lee como REAL. Las de ejemplo viven en\n' +
        '  `diseno/RentasV8.dc.html`, que no viaja; lo que la pantalla no sepa, se dice.',
    ).toEqual([]);
  });

  it('y el `Dockerfile` vuelve a comprobarlo sobre lo SERVIDO', () => {
    // Esta prueba mira `src/`; la del Dockerfile mira lo que nginx tiene como raiz, que es lo
    // unico que impide **publicar** una imagen sucia. Las dos hacen falta: #44 midio que un `COPY`
    // en la ultima etapa se salta cualquier comprobacion hecha en una anterior.
    const dockerfile = readFileSync(join(RAIZ, 'Dockerfile'), 'utf8');
    expect(dockerfile, 'el Dockerfile dejo de buscar cifras').toMatch(/for cadena in/);
    // Tres de las cincuenta, y **comprobadas contra el artboard** aqui mismo: una lista escrita en
    // un Dockerfile se queda vieja sin que nada lo diga, y entonces busca cadenas que no existen —
    // que es una guarda que no puede fallar. Ya paso: hasta #97 buscaba «SULLON VILCHEZ», que es
    // del volcado de la marcha blanca y **no esta en el artboard V8**.
    for (const cadena of ['1,842,116,420.00', '9,418,204.60', '8,420,118.40']) {
      expect(dockerfile, `el Dockerfile no busca «${cadena}»`).toContain(cadena);
      expect(
        INCONFUNDIBLES,
        `«${cadena}» ya no esta en el artboard: el Dockerfile busca algo que no existe`,
      ).toContain(cadena);
    }
  });
});

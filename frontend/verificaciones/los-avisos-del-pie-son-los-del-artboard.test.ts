// @vitest-environment node
//
// Lee el artboard del disco. No es un DOM lo que necesita.

import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

import { AVISOS_DE_V8 } from '../src/pantallas/avisos.ts';
import { ARTBOARDS, rutaDe } from './artboards.ts';

/**
 * **Los dos avisos del pie son los que V8 escribe** (#88).
 *
 * <h2>Por que esto merece una guarda propia</h2>
 *
 * Porque son las dos frases que dicen **lo que el boton de al lado implica** —«nada se escribe
 * hasta que pulse Guardar»— y son las mas faciles de reescribir «para que suene mejor». Una vez
 * reescritas, la pantalla sigue funcionando y el contrato que el usuario leyo ya no es el que la
 * pantalla cumple.
 *
 * <h2>Esta guarda mira el DISENO; hay otra que mira el DOM, y hacen falta las dos (#281)</h2>
 *
 * Esto compara `AVISOS_DE_V8` contra el artboard y **no monta nada**. Hasta #281 era lo unico que
 * miraba esa constante —medido en #262: su unico `import` era este—, mientras el pie que el
 * usuario leia salia de dos frases escritas a mano en `i18n/textosDelMarco.ts`. Asi que esta
 * guarda podia estar verde para siempre con el pie diciendo otra cosa, y la decia.
 *
 * Desde #281 el saco del marco **deriva** de `AVISOS_DE_V8`, y quien comprueba que lo que se
 * dibuja es de verdad eso es `el-pie-que-se-ve-sale-de-aqui.test.tsx`, que monta la aplicacion y
 * lee el pie del DOM. Ninguna de las dos sirve sola: aquella no sabe que dice el diseno, y esta no
 * sabe que llega a la pantalla.
 *
 * <h2>Y el aviso de consulta perdio «en el padrón» en el artboard, no aqui</h2>
 *
 * Porque no era verdad donde salia: la rama de consulta la ensena **una** de las cuarenta hojas
 * —`seg-panel`, que cuenta usuarios, permisos y contrasenas—, y es generica para los diez modulos.
 * La medida entera esta en el javadoc de `src/pantallas/avisos.ts` y, resumida, en el comentario
 * que quedo al lado de la frase en el propio artboard.
 */

const ARTBOARD = (() => {
  const declarado = ARTBOARDS.find((a) => a.archivo.endsWith('RentasV8.dc.html'));
  if (declarado === undefined) {
    throw new Error('`RentasV8.dc.html` no esta declarado en `artboards.ts`.');
  }
  return rutaDe(declarado);
})();

const fuente = readFileSync(ARTBOARD, 'utf8');

describe('los avisos del pie son los del artboard', () => {
  it('EL CENTINELA: el artboard se leyo entero', () => {
    // Sin esto, un archivo vacio o truncado dejaria los `toContain` de abajo fallando por el
    // motivo equivocado —o pasando, si alguien los invirtiera—.
    expect(fuente.length, 'el artboard vino vacio').toBeGreaterThan(100_000);
    expect(fuente, 'no parece el artboard').toContain('const PANTALLAS');
  });

  it('el de consulta y el de escritura estan escritos IGUAL que en V8', () => {
    for (const aviso of Object.values(AVISOS_DE_V8)) {
      expect(
        fuente.includes(aviso),
        `El artboard no dice «${aviso}».\n` +
          '  Estas dos frases dicen lo que el boton de al lado implica. Si el cambio es\n' +
          '  deliberado, entra primero en el artboard y de ahi se copia — no al reves.',
      ).toBe(true);
    }
  });
});

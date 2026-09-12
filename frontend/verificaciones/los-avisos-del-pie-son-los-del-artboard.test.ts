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
 * Y hay un motivo mas, de estructura: el interprete trae unos avisos NEUTROS porque esta
 * destinado a `@kamayuk/ui`, donde no puede decir «padron». Los de V8 viven en `rentas`. Ese
 * reparto solo se sostiene si alguien comprueba que los de aqui siguen siendo los del artboard —
 * si no, la separacion se convierte en dos textos que divergen.
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

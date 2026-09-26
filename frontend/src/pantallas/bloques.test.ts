import { describe, expect, it } from 'vitest';
import { CLAVES_DE_HOJA } from './arbol.ts';
import { bloquesConSuIndice, bloquesDe, esBloque } from './bloques.ts';
import { pantallaDe } from './definiciones/index.ts';

/**
 * **Ninguna pieza que no es bloque va delante de un bloque, en ninguna de las cuarenta** (#299).
 *
 * Los datos del interprete se reparten por indice de pieza —`filas` por indice, `valores` por
 * `bloque|campo`—, y hay sitios que recorren `bloquesDe` y usan el indice del arreglo FILTRADO: los
 * dos de `la-insignia-no-se-pinta-verde-sin-regla.test.ts` lo cruzan contra los bloques del
 * artboard, que tampoco lleva piezas, y por eso coinciden. Coinciden **mientras** ninguna pieza vaya
 * delante: una segunda `delConsumidor` puesta antes de un bloque correria el indice de todo lo que
 * viene detras, y los datos irian al bloque equivocado en silencio.
 *
 * Hasta #299 eso lo afirmaba una sola hoja escrita a mano —`ini-flujo`, en
 * `el-grafico-de-ini-flujo-se-dibuja.test.tsx`—, y una pieza delante en otra hoja no ponia rojo
 * nada. Aqui se recorren las cuarenta. Quien necesite el indice real no depende de esto: para eso
 * esta `bloquesConSuIndice`.
 */
describe('las piezas que no son bloques van detras de todos los bloques (#299)', () => {
  it('EL CENTINELA: son las cuarenta, y al menos una lleva una pieza que no es bloque', () => {
    expect(CLAVES_DE_HOJA).toHaveLength(40);
    const conPiezas = CLAVES_DE_HOJA.filter((hoja) =>
      pantallaDe(hoja).bloques.some((pieza) => !esBloque(pieza)),
    );
    // Sin ninguna, lo de abajo pasaria sobre la nada.
    expect(conPiezas.length, 'ninguna hoja lleva una pieza que no sea bloque').toBeGreaterThan(0);
  });

  it('en ninguna hoja una pieza que no es bloque precede a un bloque', () => {
    const delante: string[] = [];
    for (const hoja of CLAVES_DE_HOJA) {
      const piezas = pantallaDe(hoja).bloques;
      const ultimoBloque = piezas.map(esBloque).lastIndexOf(true);
      piezas.forEach((pieza, i) => {
        if (!esBloque(pieza) && i < ultimoBloque) {
          delante.push(`  ${hoja}: la pieza ${String(i)} («${String(pieza.tipo)}») va delante de un bloque`);
        }
      });
    }
    expect(
      delante,
      'Una pieza que no es bloque va DELANTE de un bloque:\n' +
        `${delante.join('\n')}\n\n` +
        '  Los datos van por indice de pieza, y quien recorre `bloquesDe` con el indice del arreglo\n' +
        '  filtrado repartiria los datos al bloque equivocado sin un solo error. Pongala detras de\n' +
        '  los bloques, o use `bloquesConSuIndice` donde el indice importe.',
    ).toEqual([]);
  });

  it('`bloquesConSuIndice` devuelve el indice REAL, y `bloquesDe` los mismos bloques', () => {
    for (const hoja of CLAVES_DE_HOJA) {
      const definicion = pantallaDe(hoja);
      const conIndice = bloquesConSuIndice(definicion);
      expect(conIndice.map(([bloque]) => bloque)).toEqual(bloquesDe(definicion));
      for (const [bloque, i] of conIndice) {
        expect(definicion.bloques[i], `${hoja}: el indice ${String(i)} no es el del bloque`).toBe(bloque);
      }
    }
  });
});

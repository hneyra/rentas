import { describe, expect, it } from 'vitest';

import { ARBOL, HOJAS, SECCIONES } from '../src/marco/arbol.ts';
import { ARTBOARD, leer } from './tokens.ts';

/**
 * AC1 — el arbol dice lo que dice el artboard, menos los dos modulos que son de
 * otro repositorio.
 *
 * **Se compara contra `frontend/diseno/RentasV6.dc.html`, no contra una copia de
 * sus datos escrita aqui.** Es la misma disciplina que `tokens-del-artboard`: si
 * la lista viviera en esta prueba, cambiar el arbol y cambiar la lista serian el
 * mismo commit y nadie se enteraria. Cuarenta destinos con su rotulo y su icono
 * son justo la clase de dato que se «arregla» de memoria.
 *
 * Lo unico escrito a mano es **cuales dos salen y por que**, que es lo que una
 * maquina no puede deducir: `Catastro` es de `../catastro` y `Tesoreria` es de
 * `../caja` (ADR-0029). El comentario del artboard nombra tambien a `Valores`,
 * pero habla de que su PANTALLA no se rediseno alli — y sus datos lo incluyen.
 */

/** Los modulos del artboard que este repositorio no sirve, con su motivo. */
const DE_OTRO_REPOSITORIO: ReadonlyArray<readonly [string, string]> = [
  ['Catastro', 'es de ../catastro'],
  ['Tesorería', 'es de ../caja'],
];

/**
 * El literal que sigue a `const <nombre> = ` en el artboard, con los corchetes
 * contados.
 *
 * Contar en vez de buscar el primer `];` no es rebuscamiento: `ARBOL` es un
 * array de arrays, y el primer cierre que aparece es el de su primer modulo.
 */
function literalDelArtboard(html: string, nombre: string): string {
  const inicio = html.indexOf(`const ${nombre} = `);
  if (inicio === -1) {
    throw new Error(`El artboard ya no declara «const ${nombre}»: la referencia cambio.`);
  }
  const desde = inicio + `const ${nombre} = `.length;
  const abre = html[desde];
  const cierra = abre === '[' ? ']' : '}';

  let profundidad = 0;
  for (let i = desde; i < html.length; i += 1) {
    if (html[i] === abre) {
      profundidad += 1;
    } else if (html[i] === cierra) {
      profundidad -= 1;
      if (profundidad === 0) {
        return html.slice(desde, i + 1);
      }
    }
  }
  throw new Error(`El literal «const ${nombre}» no se cierra.`);
}

/**
 * El literal de JavaScript del artboard, leido como dato.
 *
 * Comillas simples a dobles y claves de objeto entrecomilladas: es todo lo que
 * separa estos literales del JSON, porque ninguno de sus textos lleva un
 * apostrofo —se comprobo antes de escribir esto— y ninguno tiene comentarios
 * dentro. Si algun dia los tuviera, `JSON.parse` revienta y la prueba lo dice,
 * que es mejor que interpretarlo a medias.
 */
function comoDato(literal: string): unknown {
  return JSON.parse(
    literal.replace(/'/g, '"').replace(/([{,]\s*)([A-Za-z_][A-Za-z0-9_]*)\s*:/g, '$1"$2":'),
  );
}

const html = leer(ARTBOARD);
const arbolDelArtboard = comoDato(literalDelArtboard(html, 'ARBOL')) as [
  string,
  string,
  string,
  [string, string][],
][];
const modulosDelArtboard = comoDato(literalDelArtboard(html, 'MODULOS')) as [string, string[]][];
const secsDelArtboard = comoDato(literalDelArtboard(html, 'SECS')) as [
  string,
  string,
  string,
  string,
][];
const iconosDeSeccion = comoDato(literalDelArtboard(html, 'ICO_SEC')) as Record<string, string[]>;

/** Los modulos del artboard que este sistema si sirve, en su orden. */
const esperados = arbolDelArtboard.filter(
  (fila) => !DE_OTRO_REPOSITORIO.some(([rotulo]) => rotulo === fila[0]),
);

describe('el artboard sigue diciendo lo que esta prueba cree que dice', () => {
  it('declara DOCE modulos de cuatro submodulos cada uno', () => {
    // Sin esto, un artboard que cambiara de forma daria listas vacias y todas
    // las comparaciones de abajo pasarian comparando nada con nada.
    expect(arbolDelArtboard).toHaveLength(12);
    expect(arbolDelArtboard.map((fila) => fila[3].length)).toEqual(Array(12).fill(4));
  });

  it('declara los doce iconos de modulo y las cuatro secciones propias', () => {
    expect(modulosDelArtboard).toHaveLength(12);
    expect(secsDelArtboard).toHaveLength(4);
    expect(Object.keys(iconosDeSeccion).sort()).toEqual(
      ['panel', 'predios', 'territorio', 'valores'].sort(),
    );
  });

  it.each(DE_OTRO_REPOSITORIO)('«%s» esta en el artboard, y sale por otro motivo (%s)', (rotulo) => {
    // Que estos dos ESTEN en el artboard es la premisa de la resta. El dia que
    // el artboard deje de traerlos, restarlos deja de significar algo.
    expect(arbolDelArtboard.map((fila) => fila[0])).toContain(rotulo);
  });
});

describe('AC1 — diez modulos y cuarenta destinos', () => {
  it('son DIEZ modulos, y son los del artboard menos los dos de otro repositorio', () => {
    expect(ARBOL.map((modulo) => modulo.rotulo)).toEqual(esperados.map((fila) => fila[0]));
    expect(ARBOL).toHaveLength(10);
  });

  it('son CUARENTA destinos', () => {
    const destinos = ARBOL.flatMap((modulo) => modulo.submodulos.map((hoja) => hoja.clave));

    expect(destinos).toHaveLength(40);
    expect(new Set(destinos).size, 'dos submodulos con la misma clave').toBe(40);
    expect(HOJAS.size).toBe(40);
  });

  it.each(DE_OTRO_REPOSITORIO)('«%s» NO aparece (%s)', (rotulo) => {
    expect(ARBOL.map((modulo) => modulo.rotulo)).not.toContain(rotulo);
  });

  it('«Valores» SI aparece, con sus cuatro submodulos', () => {
    const valores = ARBOL.find((modulo) => modulo.rotulo === 'Valores');

    expect(
      valores,
      'Sin «Valores» —o sin ningun otro modulo ajeno— el AC9 se queda sin caso que probar:\n' +
        'la pestana ajena es la que abre un submodulo DE OTRO MODULO.',
    ).toBeDefined();
    expect(valores?.clave, 'la clave es «valores-mod» para no chocar con la seccion «valores»').toBe(
      'valores-mod',
    );
    expect(valores?.submodulos.map((hoja) => hoja.clave)).toEqual([
      'val-panel',
      'val-val',
      'val-cart',
      'val-tip',
    ]);
  });
});

describe('AC1 — cada modulo dice lo que el artboard dice de el', () => {
  it.each(esperados.map((fila) => [fila[0], fila] as const))(
    '«%s»: rotulo, nota, clave, submodulos e icono',
    (_rotulo, fila) => {
      const [rotuloDelArtboard, nota, clave, submodulos] = fila;
      const nuestro = ARBOL.find((modulo) => modulo.rotulo === rotuloDelArtboard);
      const icono = modulosDelArtboard.find(([nombre]) => nombre === rotuloDelArtboard);

      expect(nuestro).toBeDefined();
      expect(nuestro?.nota).toBe(nota);
      expect(nuestro?.clave).toBe(clave);
      expect(nuestro?.submodulos.map((hoja) => [hoja.clave, hoja.rotulo])).toEqual(submodulos);
      expect(
        nuestro?.trazos,
        'Los trazos se copian de `const MODULOS`, uno a uno. Un icono redibujado a ojo se\n' +
          'nota al lado de los que no lo estan.',
      ).toEqual(icono?.[1]);
    },
  );
});

describe('AC1 — las cuatro secciones propias son las de `const SECS`', () => {
  it('mismas claves, mismos rotulos y mismos slugs, en el mismo orden', () => {
    expect(SECCIONES.map((seccion) => [seccion.clave, seccion.rotulo, seccion.slug])).toEqual(
      secsDelArtboard.map((fila) => [fila[0], fila[1], fila[3]]),
    );
  });

  it.each(SECCIONES.map((seccion) => [seccion.clave, seccion] as const))(
    '«%s» lleva el icono que `ICO_SEC` le da',
    (clave, seccion) => {
      expect(seccion.trazos).toEqual(iconosDeSeccion[clave]);
    },
  );

  it('el modulo propio usa en el arbol las claves de sus secciones, sin prefijo', () => {
    // La convencion que los DOCE artboards comparten, incluidos los sanos: los
    // otros nueve modulos llevan prefijo (`fis-panel`, `coa-exp`) y este no. No
    // es un residuo — es lo que deja a una seccion propia y a una hoja ajena en
    // el mismo espacio de claves sin un mapa de traduccion en medio.
    const propio = ARBOL.find((modulo) => modulo.rotulo === 'Rentas · Registro');

    expect(propio?.submodulos.map((hoja) => hoja.clave)).toEqual(
      SECCIONES.map((seccion) => seccion.clave),
    );
  });
});

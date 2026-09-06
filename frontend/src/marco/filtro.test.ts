import { describe, expect, it } from 'vitest';

import { ARBOL } from './arbol.ts';
import { conteoDelFiltro, modulosQueCasan } from './filtro.ts';

/**
 * AC7 — el filtro del arbol y su conteo.
 *
 * Las tres cosas que hay que separar, y que un solo caso no separa: que un
 * modulo entre por su NOMBRE ensena sus cuatro submodulos aunque ninguno case;
 * que entre por un SUBMODULO ensena solo los que casan; y que el conteo distinga
 * singular de plural en cada mitad por separado.
 */

describe('sin filtro, estan los diez modulos enteros', () => {
  it('los diez, con sus cuatro submodulos cada uno', () => {
    const visibles = modulosQueCasan('');

    expect(visibles).toHaveLength(10);
    expect(visibles.flatMap((fila) => fila.submodulos)).toHaveLength(40);
  });

  it('y el conteo no dice nada: no hay nada que contar', () => {
    expect(conteoDelFiltro('')).toBe('');
    expect(conteoDelFiltro('   ')).toBe('');
  });
});

describe('AC7 — un modulo entra por su nombre, y entonces entero', () => {
  it('«coactiva» ensena los CUATRO submodulos de Coactiva, no solo los que casan', () => {
    const visibles = modulosQueCasan('coactiva');

    expect(visibles).toHaveLength(1);
    expect(visibles[0]?.modulo.rotulo).toBe('Coactiva');
    expect(
      visibles[0]?.submodulos.map((hoja) => hoja.clave),
      'Buscar el nombre de un modulo tiene que ensenar lo que hay dentro, no una lista\n' +
        'vacia debajo de un titulo que si casaba.',
    ).toEqual(['coa-panel', 'coa-exp', 'coa-cart', 'coa-cost']);
  });

  it('y el conteo lo dice en singular: «1 módulo · 4 submódulos»', () => {
    expect(conteoDelFiltro('coactiva')).toBe('1 módulo · 4 submódulos');
  });
});

describe('AC7 — un modulo entra por un submodulo, y entonces solo ese', () => {
  it('«papeletas» deja Tránsito con un solo submodulo visible', () => {
    const visibles = modulosQueCasan('papeletas');

    expect(visibles).toHaveLength(1);
    expect(visibles[0]?.modulo.rotulo).toBe('Tránsito');
    expect(visibles[0]?.submodulos.map((hoja) => hoja.clave)).toEqual(['tra-pap']);
  });

  it('y el conteo lo dice en singular por los dos lados', () => {
    expect(conteoDelFiltro('papeletas')).toBe('1 módulo · 1 submódulo');
  });

  it('«panel» casa en los diez, uno por modulo', () => {
    expect(conteoDelFiltro('panel')).toBe('10 módulos · 10 submódulos');
  });
});

describe('AC7 — sin coincidencias', () => {
  it('el conteo lo dice, y no ensena un cero', () => {
    expect(conteoDelFiltro('zzz')).toBe('Sin coincidencias');
    expect(modulosQueCasan('zzz')).toEqual([]);
  });
});

describe('el filtro no distingue mayusculas, y el orden del arbol se conserva', () => {
  it('«COACTIVA» y «coactiva» dan lo mismo', () => {
    expect(conteoDelFiltro('COACTIVA')).toBe(conteoDelFiltro('coactiva'));
  });

  it('los visibles salen en el orden del arbol, no en el de coincidencia', () => {
    const visibles = modulosQueCasan('panel').map((fila) => fila.modulo.rotulo);

    expect(visibles).toEqual(ARBOL.map((modulo) => modulo.rotulo));
  });
});

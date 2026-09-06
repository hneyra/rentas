import type { Modulo, Submodulo } from './arbol.ts';

/**
 * El filtro del arbol (AC7), aparte del componente porque es una funcion pura
 * sobre el arbol y porque su conteo tiene tres casos que conviene probar sin
 * teclear en un `input`.
 *
 * **Desde I-3 el arbol entra como argumento y no se importa.** Antes las dos
 * funciones leian `ARBOL`, la constante del artboard, de modo que el filtro
 * podia devolver —y el conteo podia contar— un modulo que el backend no publica
 * o que esta cuenta no puede abrir. Escribir «coactiva» habria seguido
 * ensenando Coactiva a quien no la tiene: la puerta trasera del AC2 mas facil de
 * dejar abierta, porque la lista filtrada no se parece a la lista del panel.
 *
 * La regla es la del artboard, y no es «se queda lo que casa»: **si casa el
 * nombre del MODULO, el modulo entra con sus cuatro submodulos**, aunque ninguno
 * de ellos case. Buscar «coactiva» tiene que ensenar lo que hay en Coactiva, no
 * una lista vacia debajo de un titulo que si casaba.
 *
 * Y el que casa se despliega solo: buscar y tener que abrir despues serian dos
 * gestos para uno.
 */

/** Un modulo del arbol con solo los submodulos que el filtro deja ver. */
export interface ModuloFiltrado {
  readonly modulo: Modulo;
  readonly submodulos: readonly Submodulo[];
}

const normalizado = (texto: string): string => texto.trim().toLowerCase();

/** Los modulos que el filtro deja ver, con sus submodulos visibles. */
export function modulosQueCasan(
  arbol: readonly Modulo[],
  filtro: string,
): readonly ModuloFiltrado[] {
  const busqueda = normalizado(filtro);
  if (busqueda === '') {
    return arbol.map((modulo) => ({ modulo, submodulos: modulo.submodulos }));
  }

  return arbol.flatMap((modulo) => {
    const casaElModulo = modulo.rotulo.toLowerCase().includes(busqueda);
    const casan = modulo.submodulos.filter((submodulo) =>
      submodulo.rotulo.toLowerCase().includes(busqueda),
    );
    if (!casaElModulo && casan.length === 0) {
      return [];
    }
    return [{ modulo, submodulos: casaElModulo ? modulo.submodulos : casan }];
  });
}

/**
 * Lo que dice el conteo bajo la caja de filtro.
 *
 * Cadena vacia cuando no hay filtro —no hay nada que contar—, «Sin
 * coincidencias» cuando no casa nada, y el recuento en singular o plural en cada
 * mitad por separado: «1 módulo · 4 submódulos» es una frase que ocurre.
 */
export function conteoDelFiltro(arbol: readonly Modulo[], filtro: string): string {
  if (normalizado(filtro) === '') {
    return '';
  }

  const casan = modulosQueCasan(arbol, filtro);
  if (casan.length === 0) {
    return 'Sin coincidencias';
  }

  const hojas = casan.reduce((suma, fila) => suma + fila.submodulos.length, 0);
  const modulos = casan.length;
  return (
    `${modulos} ${modulos === 1 ? 'módulo' : 'módulos'} · ` +
    `${hojas} ${hojas === 1 ? 'submódulo' : 'submódulos'}`
  );
}

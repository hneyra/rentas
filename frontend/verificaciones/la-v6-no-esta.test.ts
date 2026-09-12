// @vitest-environment node
//
// Mira el arbol del disco. No es un DOM lo que necesita.

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

import { RAIZ } from './artboards.ts';

/**
 * **La V6 no esta** (#90, AC3).
 *
 * <h2>Por que una guarda, si basta con mirar</h2>
 *
 * Porque un borrado se deshace sin querer. Un `git revert` mal acotado, una rama vieja que se
 * mezcla, un `git checkout` de un archivo suelto para «ver como era»: las tres formas devuelven
 * parte de la V6 al arbol, y **ninguna rompe nada** — la V8 sigue montando y la suite sigue en
 * verde, porque lo devuelto no lo importa nadie. Lo unico que pasa es que el repositorio vuelve a
 * tener dos interfaces, una de ellas muerta, y quien llegue despues no sabe cual mirar.
 *
 * <h2>Y por que por RUTA y no por recuento</h2>
 *
 * Un recuento —«hay menos de N archivos en `src/`»— pasa en verde con `src/marco/` entero de
 * vuelta si a la vez se borro otra cosa. Las rutas dicen exactamente que no puede estar.
 */

const SE_FUE: readonly { readonly ruta: string; readonly que: string }[] = [
  { ruta: 'src/estilos', que: 'los 9 archivos de CSS escrito a mano; la paleta la publica `@kamayuk/ui`' },
  { ruta: 'src/ds', que: 'los 8 componentes propios; los publica `@kamayuk/ui`' },
  { ruta: 'src/marco', que: 'el marco V6; lo publica `@kamayuk/shell`' },
  { ruta: 'src/secciones', que: 'las 4 secciones a mano; las dibuja el interprete desde sus definiciones' },
  { ruta: 'src/datos/prototipo.ts', que: 'la captura del artboard V6' },
  { ruta: 'src/datos/operaciones.ts', que: 'las 18 operaciones del proxy' },
  { ruta: 'src/api/proxy.ts', que: 'el proxy de datos; se quedo sin nada que contestar' },
  { ruta: 'diseno/RentasV6.dc.html', que: 'el artboard de la interfaz anterior' },
  { ruta: 'e2e', que: 'el arnes, atado a las pantallas de V6; se rehace aparte' },
];

/** Lo que sustituyo a cada cosa, y que tiene que estar. La otra direccion de la misma guarda. */
const ESTA: readonly string[] = [
  'src/aplicacion.tsx',
  'src/catalogo.ts',
  'src/pantallas/Pantalla.tsx',
  'src/pantallas/arbol.ts',
  'src/pantallas/definiciones/index.ts',
  'diseno/RentasV8.dc.html',
];

describe('la V6 no esta, y la V8 si', () => {
  it('EL CENTINELA: la lista dice algo y el arbol se puede leer', () => {
    // Sin esto, una `RAIZ` mal calculada haria que `existsSync` diera false para todo y la guarda
    // pasara en verde afirmando que no esta nada — incluida la V8.
    expect(SE_FUE.length).toBeGreaterThanOrEqual(9);
    expect(readdirSync(join(RAIZ, 'src')).length, 'no se pudo leer `src/`').toBeGreaterThan(3);
  });

  it('ninguna pieza de la V6 volvio al arbol', () => {
    const vueltas = SE_FUE.filter((x) => existsSync(join(RAIZ, x.ruta))).map(
      (x) => `  ${x.ruta} — ${x.que}`,
    );
    expect(
      vueltas,
      'Parte de la V6 esta otra vez en el arbol:\n' +
        `${vueltas.join('\n')}\n\n` +
        '  El repositorio vuelve a tener dos interfaces, una de ellas muerta, y quien llegue\n' +
        '  despues no sabe cual mirar. Si hace falta consultar como era, esta en el `git log`.',
    ).toEqual([]);
  });

  it('y lo que la sustituyo SI esta: sin esto, borrarlo todo tambien pasaria', () => {
    const ausentes = ESTA.filter((ruta) => !existsSync(join(RAIZ, ruta)));
    expect(ausentes, `Falta lo que sustituyo a la V6:\n  ${ausentes.join('\n  ')}`).toEqual([]);
  });

  it('nadie importa ya una hoja de estilos propia: la paleta es la de la libreria', () => {
    // La V6 encadenaba cinco archivos de tokens desde `src/estilos/estilos.css`. Si alguien
    // anadiera una hoja propia al lado de la de `@kamayuk/ui`, habria dos fuentes de verdad para
    // el mismo color — y la que gana depende del orden en que Vite resuelva los modulos.
    const main = readFileSync(join(RAIZ, 'src/main.tsx'), 'utf8');
    expect(main).toContain("import '@kamayuk/ui/estilos.css'");
    const propias = [...main.matchAll(/import '([^']*\.css)'/g)]
      .map(([, ruta]) => ruta ?? '')
      .filter((ruta) => !ruta.startsWith('@kamayuk/'));
    expect(propias, `Hay hojas de estilo propias: ${propias.join(', ')}`).toEqual([]);
  });
});

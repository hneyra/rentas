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
];

/** Lo que sustituyo a cada cosa, y que tiene que estar. La otra direccion de la misma guarda. */
const ESTA: readonly string[] = [
  'src/aplicacion.tsx',
  'src/catalogo.ts',
  // `src/pantallas/Pantalla.tsx` salio de aqui en #153: el interprete es de `@kamayuk/ui`, y que
  // no vuelva lo vigila `el-interprete-es-de-la-libreria.test.ts`. Lo que lo monta, si esta.
  'src/pantallas/PantallaDeRentas.tsx',
  'src/pantallas/arbol.ts',
  'src/pantallas/definiciones/index.ts',
  'diseno/RentasV8.dc.html',
  // El arnes VOLVIO con #107, y es otro: corre contra el bundle construido en Chromium y mide lo
  // que jsdom no puede —que la interfaz se vea—. El de la instalacion, que entraba por Keycloak
  // de verdad, sigue sin poder correr y sigue declarado en `frontend.yml`.
  'e2e/se-ve.spec.ts',
  'e2e/los-cuarenta.spec.ts',
  'playwright.config.ts',
  'src/estilos.css',
];

describe('la V6 no esta, y la V8 si', () => {
  it('EL CENTINELA: la lista dice algo y el arbol se puede leer', () => {
    // Sin esto, una `RAIZ` mal calculada haria que `existsSync` diera false para todo y la guarda
    // pasara en verde afirmando que no esta nada — incluida la V8.
    // Ocho desde #107: `e2e/` volvio, y es otro arnes — corre contra el bundle en Chromium y mide
    // que la interfaz se VEA, que es lo que jsdom no puede.
    expect(SE_FUE.length).toBeGreaterThanOrEqual(8);
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

  it('la hoja propia NO define colores: solo importa la de la libreria y dice donde mirar', () => {
    // La V6 encadenaba cinco archivos de tokens desde `src/estilos/estilos.css`, 3 446 lineas.
    //
    // Desde #107 vuelve a haber UNA hoja propia, y su motivo es otro: Tailwind **omite
    // `node_modules`** al buscar clases, y `@kamayuk/{ui,shell}` viven ahi por el `link:`. Sin un
    // `@source` que lo diga, mas de la mitad de las clases de la libreria no generaban regla — el
    // CSS salia con 157 en vez de 419, y la rejilla de campos se dibujaba **en una sola columna**.
    //
    // Asi que lo que se prohibe no es el archivo: es que DECLARE valores. La paleta es del
    // artboard y la publica `@kamayuk/ui`; un color escrito aqui seria una segunda fuente de
    // verdad, y la que gana depende del orden en que el empaquetador resuelva los modulos.
    const main = readFileSync(join(RAIZ, 'src/main.tsx'), 'utf8');
    const hojas = [...main.matchAll(/import '([^']*\.css)'/g)].map(([, ruta]) => ruta ?? '');
    expect(hojas, 'main.tsx importa mas de una hoja, o ninguna').toHaveLength(1);

    const propia = readFileSync(join(RAIZ, 'src/estilos.css'), 'utf8');
    expect(propia, 'la hoja propia no importa la de la libreria').toContain('@kamayuk/ui/estilos.css');
    // Sin comentarios: la prosa de arriba explica el `--color-*` que la libreria publica.
    const sinComentarios = propia.replace(/\/\*[\s\S]*?\*\//g, ' ');
    const declaraciones = [...sinComentarios.matchAll(/(--[a-z0-9-]+)\s*:/gi)].map(([, n]) => n ?? '');
    expect(
      declaraciones,
      'La hoja propia declara tokens. La paleta es del artboard y la publica `@kamayuk/ui`:\n' +
        'dos sitios para el mismo color es uno de mas, y el que gana depende del empaquetador.',
    ).toEqual([]);
  });

  it('y le dice a Tailwind donde esta la libreria, que es todo lo que hace', () => {
    // Es el modo de fallo mas silencioso que este repositorio ha encontrado: **la pantalla se
    // dibuja**, con su estructura correcta y la mitad de su aspecto. Ninguna de las 507 pruebas de
    // `vitest` podia verlo — comparan `className` como texto, y el texto estaba bien. Lo cazo el
    // arnes de #107 midiendo en un navegador que los campos se reparten en columnas.
    const propia = readFileSync(join(RAIZ, 'src/estilos.css'), 'utf8');
    const fuentes = [...propia.matchAll(/@source\s+"([^"]+)"/g)].map(([, r]) => r ?? '');
    expect(fuentes.length, 'la hoja no declara ni un `@source`').toBeGreaterThanOrEqual(2);
    for (const fuente of fuentes) {
      expect(
        existsSync(join(RAIZ, 'src', fuente)),
        `«@source ${fuente}» apunta a un sitio que no existe: Tailwind no mirara ahi y nadie lo dira`,
      ).toBe(true);
    }
  });
});

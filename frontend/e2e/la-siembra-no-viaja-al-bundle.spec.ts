import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import { expect, test } from '@playwright/test';

import { ACCESOS_MEDIDOS, PERMISOS_MEDIDOS } from '../src/datos/seguridadMedida.ts';

/**
 * **La siembra de desarrollo NO viaja al paquete que se publica** (#114, AC2).
 *
 * <h2>Por que este camino vive aqui y no en `vitest`</h2>
 *
 * Porque lo que hay que medir es **el `dist/`**, y el `dist/` hay que construirlo: `yarn verificar`
 * no construye nada, y meterle un `vite build` le anadiria dieciseis segundos a la orden que se
 * ejecuta veinte veces al dia. Este arnes YA construye el bundle antes de levantar nada
 * —`playwright.config.ts`, `webServer: yarn build && yarn preview`—, asi que aqui la medicion
 * sale gratis y encima es sobre **el artefacto de verdad**, que es lo unico que afirma algo.
 *
 * No abre navegador, y es el unico camino de este arnes que no lo hace. Se queda aqui igualmente
 * porque lo que lo hace posible es el `yarn build` del arnes, y separarlo en otra orden seria
 * construir dos veces para medir lo mismo.
 *
 * <h2>Que se busca, y por que ESTAS cadenas</h2>
 *
 * Los rotulos de los accesos de **CATASTRO**, que es un modulo que este sistema **no sirve**
 * —es de otro (ADR-0024), y por eso `catalogo.ts` no lo tiene—. Un «Ficha catastral rural» dentro
 * del paquete de rentas no puede venir de ninguna pantalla de rentas: solo puede venir de la
 * captura. Y se **derivan** de la captura en vez de escribirse a mano, para que el dia que el
 * catalogo del backend gane un acceso mas la guarda siga buscando lo que hay y no lo que habia.
 *
 * Mas dos llaves de la matriz de permisos, que son la tercera de las tres respuestas: sin ellas,
 * una siembra que dejara de traer los accesos y siguiera trayendo la matriz pasaria en verde.
 *
 * <h2>Y se mira el `.map` tambien, a proposito</h2>
 *
 * `vite.config.ts` declara `build.sourcemap: true`, y un `.map` lleva dentro el **codigo fuente
 * entero** de cada modulo que entro en el paquete. Es la parte que ya midio I-2: «Rufina Medina
 * Medina» aparecia en el `.map` y **cero** veces en el `.js`. O sea que mirar solo el `.js` diria
 * que el modulo no viajo cuando si viajo — la imagen borra los mapas al final (`Dockerfile`), pero
 * eso es la imagen, y esto mide `yarn build`.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const DIST = join(AQUI, '../dist');

/** El modulo de la captura que este sistema no sirve. Sus rotulos no pueden salir de otro sitio. */
const CATASTRO = 3;

const ROTULOS_AJENOS = ACCESOS_MEDIDOS.filter((a) => a.moduloId === CATASTRO).map((a) => a.nombre);

/** Dos llaves de la matriz, que es la tercera respuesta sembrada. */
const LLAVES_DE_PERMISO = ['transito_rg_sancionadora', 'valores_masivo'];

/** Todo lo que `vite build` dejo en `dist/`, con su ruta relativa. */
function loConstruido(desde = DIST): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    return statSync(ruta).isDirectory() ? loConstruido(ruta) : [ruta];
  });
}

test('EL CENTINELA: hay un `dist/` que mirar y cadenas que buscar', () => {
  // Sin esto, un `dist/` vacio —o un filtro que no case con nada— dejaria las dos comprobaciones
  // de abajo buscando nada en ninguna parte, y las dos saldrian verdes sin haber medido.
  const construido = loConstruido();
  expect(construido.filter((r) => r.endsWith('.js')).length).toBeGreaterThan(0);
  expect(ROTULOS_AJENOS).toHaveLength(12);
  for (const llave of LLAVES_DE_PERMISO) {
    expect(Object.keys(PERMISOS_MEDIDOS), `«${llave}» ya no esta en la matriz`).toContain(llave);
  }
});

test('ni un rotulo de la captura de seguridad esta en el bundle construido', () => {
  const construido = loConstruido();
  const culpables: string[] = [];

  for (const archivo of construido) {
    const contenido = readFileSync(archivo, 'utf8');
    for (const cadena of [...ROTULOS_AJENOS, ...LLAVES_DE_PERMISO]) {
      if (contenido.includes(cadena)) {
        culpables.push(`${relative(DIST, archivo)} — «${cadena}»`);
      }
    }
  }

  expect(
    culpables,
    'La captura de `seguridadMedida.ts` VIAJO al paquete:\n' +
      `  ${culpables.join('\n  ')}\n\n` +
      '  La siembra de desarrollo solo puede entrar por el `import()` dinamico de\n' +
      '  `src/arranque.ts`, y solo si las dos condiciones que lo guardan se leen AL CONSTRUIR.\n' +
      '  Leidas en tiempo de ejecucion, Rollup no puede plegarlas y el modulo se queda dentro —\n' +
      '  medido en F-4 con el proxy de V6: 227 205 bytes con las cifras dentro frente a 193 592\n' +
      '  sin ellas, y `yarn dev` igual de verde en los dos casos.',
  ).toEqual([]);
});

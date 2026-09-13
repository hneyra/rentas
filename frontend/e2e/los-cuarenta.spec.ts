import { expect, test } from '@playwright/test';

import { CATALOGO } from '../src/catalogo.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **Los cuarenta destinos, en un navegador de verdad** (#107).
 *
 * <h2>Que anade a la version de `vitest`, que ya los recorre</h2>
 *
 * Aquella corre en jsdom: comprueba que el DOM sale bien. Esta comprueba lo demas — que el bundle
 * **construido** arranca, que el enrutado por hash funciona sobre `history` de verdad, que el CSS
 * que la imagen lleva pinta esas pantallas, y que ninguna revienta en el navegador.
 *
 * Y una cosa mas que jsdom no puede: que **se vea algo**. Una pantalla puede tener su DOM perfecto
 * y estar pintada en blanco sobre blanco, o con altura cero. Aqui se exige que el titulo sea
 * visible de verdad, que en Playwright significa que tiene caja y no esta tapado.
 */

test.beforeEach(async ({ page }) => {
  await conLaSeguridadContestada(page);
});

const DESTINOS = CATALOGO.flatMap((modulo) =>
  modulo.destinos.map((destino) => ({ modulo: modulo.rotulo, destino })),
);

test('EL CENTINELA: hay cuarenta destinos que recorrer', () => {
  // Sin esto, un catalogo vacio dejaria el bucle de abajo sin casos y el archivo en verde
  // habiendo abierto cero pantallas.
  expect(DESTINOS).toHaveLength(40);
});

for (const { destino } of DESTINOS) {
  test(`«${destino.clave}» — ${destino.rotulo} se abre y se ve`, async ({ page }) => {
    await abrir(page, destino.clave);

    const titulo = page.getByRole('heading', { level: 1, name: destino.rotulo });
    await expect(titulo, `«${destino.clave}» no abrio por su hash`).toBeVisible();

    // Y sus bloques, que es lo que solo aparece con su definicion puesta.
    const definicion = pantallaDe(destino.clave as ClaveDeHoja);
    for (const bloque of definicion.bloques) {
      await expect(
        page.getByRole('heading', { level: 2, name: bloque.titulo }),
        `«${destino.clave}» no pinto el bloque «${bloque.titulo}»`,
      ).toBeVisible();
    }

    // Visible de verdad: con caja. Una pantalla con altura cero tiene su DOM perfecto y no se ve.
    const caja = await titulo.boundingBox();
    expect(caja?.height ?? 0, `el titulo de «${destino.clave}» no ocupa nada`).toBeGreaterThan(10);
  });
}

import { expect, test } from '@playwright/test';

import { ARBOL } from '../src/pantallas/arbol.ts';
import type { ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { bloquesDe } from '../src/pantallas/bloques.ts';
import { pantallaDe } from '../src/pantallas/definiciones/index.ts';
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
 *
 * <h2>Por que se lee `ARBOL` y no `CATALOGO`, que seria lo natural</h2>
 *
 * Porque `catalogo.ts` importa `@kamayuk/shell`, y **el cargador de Playwright no es el de Vite**:
 * no honra `preserveSymlinks` ni el `dedupe` de `resolucion.ts`, asi que resuelve las dependencias
 * de la libreria contra el arbol del clon hermano — que en CI se clona y **no se instala**. El rojo
 * fue `Cannot find package 'class-variance-authority' imported from …/paquetes/ui/shadcn/boton.tsx`,
 * y no decia ni una palabra de Playwright ni de symlinks.
 *
 * Podria arreglarse con `--preserve-symlinks` en el arranque del runner. No se hace: **un arnes de
 * extremo a extremo no deberia importar la libreria de componentes para nada** — prueba el
 * artefacto construido, no sus piezas. `ARBOL` y las definiciones son dato de este repositorio y
 * no tiran de nadie.
 *
 * `bloques.ts` entra con #288 y **no rompe esa regla**: lo unico que toma de `@kamayuk/ui` son
 * TIPOS, que el transpilador borra. En tiempo de ejecucion no importa nada de la libreria.
 */

test.beforeEach(async ({ page }) => {
  await conLaSeguridadContestada(page);
});

const DESTINOS = ARBOL.flatMap((modulo) =>
  modulo.hojas.map((hoja) => ({ modulo: modulo.rotulo, destino: hoja })),
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
    //
    // **Los bloques, y no todas las piezas** (#288). Una pieza del consumidor no tiene `titulo`, y
    // `getByRole('heading', { name: undefined })` no busca «el que no tiene nombre»: busca
    // CUALQUIERA, asi que casaba con los dos de `ini-flujo` y el modo estricto de Playwright lo
    // tumbaba. Que la pieza se dibuja lo mide su propio caso, ahi abajo.
    const definicion = pantallaDe(destino.clave as ClaveDeHoja);
    for (const bloque of bloquesDe(definicion)) {
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

/**
 * **La pieza del consumidor se MONTA en un navegador de verdad** (#288).
 *
 * Es lo que jsdom no puede decir: que el componente que el interprete busca en su registro llega al
 * bundle construido y se dibuja con caja. Y aqui **no hay backend**, asi que lo que se ve es el
 * grafico sin serie — que es justamente el caso que tiene que decir su hueco en vez de dejar un
 * lienzo en blanco. La palabra es `fallo`, porque esta hoja SI pide: `conLaSeguridadContestada`
 * dobla las tres lecturas de seguridad y no `GET /indicadores/recaudacion`, asi que la peticion sale
 * y no llega. Que dibuje barras con datos lo mide
 * `verificaciones/el-grafico-de-ini-flujo-se-dibuja.test.tsx`, que si puede doblar la red.
 */
test('«ini-flujo» monta el grafico que el artboard le declara, y sin datos dice su hueco', async ({
  page,
}) => {
  await abrir(page, 'ini-flujo');

  const grafico = page.locator('[data-grafico="grafico-de-recaudacion"]');
  await expect(grafico, 'el grafico de `ini-flujo` no se monto').toBeVisible();
  const caja = await grafico.boundingBox();
  expect(caja?.height ?? 0, 'el grafico no ocupa nada').toBeGreaterThan(10);

  // Sin backend no hay serie, y el lienzo no se dibuja: se dice por que no hay dato. Es la palabra
  // de la pantalla —la misma que sale en cada campo—, no una escrita dentro del grafico.
  await expect(grafico.locator('.recharts-surface')).toHaveCount(0);
  expect(await grafico.textContent()).toContain('fallo');

  // Y la tabla del artboard sigue ahi, al lado (AC-5).
  await expect(page.getByText('Cuadre por tributo')).toBeVisible();
});

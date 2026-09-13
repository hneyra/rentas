import { expect, test } from '@playwright/test';

import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **Que la interfaz se VE** (#107).
 *
 * Todo lo de aqui es lo que las 507 pruebas de `vitest` **no pueden** decir, porque comparan
 * `className` como texto: que Tailwind emita el CSS, que el navegador lo aplique, que la rejilla
 * se reacomode y que un `overflow` no corte una tabla.
 */

test.beforeEach(async ({ page }) => {
  await conLaSeguridadContestada(page);
});

test('la paleta del artboard LLEGA al navegador, no solo al CSS', async ({ page }) => {
  await abrir(page, 'ini-panel');

  // El lienzo. `--fondo` del artboard es #f2f6f9, que el navegador devuelve como rgb.
  const lienzo = await page.evaluate(() =>
    getComputedStyle(document.body).getPropertyValue('--color-fondo').trim(),
  );
  expect(lienzo, 'el token del lienzo no llego al documento').toBe('#f2f6f9');

  // Y la cabecera de tarjeta es AZUL de verdad, no una clase escrita.
  const cabecera = page.locator('[data-slot="tarjeta-cabecera"]').first();
  await expect(cabecera).toBeVisible();
  const fondo = await cabecera.evaluate((e) => getComputedStyle(e).backgroundColor);
  // #005284
  expect(fondo, 'la cabecera de la tarjeta no esta pintada de azul').toBe('rgb(0, 82, 132)');
});

test('el radio es el del artboard —3 px— y NO el 0.625rem de shadcn', async ({ page }) => {
  await abrir(page, 'ini-panel');
  const tarjeta = page.locator('[data-slot="tarjeta"]').first();
  const radio = await tarjeta.evaluate((e) => getComputedStyle(e).borderRadius);
  // `kamayuk-lib`#8 midio que con el token mal nombrado shadcn cae en 10px. Esto lo ve de verdad.
  expect(radio, 'la tarjeta cayo en el radio por omision de shadcn').toBe('3px');
});

test('la tipografia es la del artboard: ninguna webfont', async ({ page }) => {
  await abrir(page, 'ini-panel');
  const familia = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
  expect(familia.toLowerCase()).toContain('arial');
  // Y ninguna hoja de Google Fonts: el artboard no carga ninguna, y cargarla seria una ida a la
  // red por cada pantalla de una ventanilla que a veces no tiene salida.
  const externas = await page.evaluate(() =>
    [...document.querySelectorAll('link[rel="stylesheet"]')]
      .map((l) => l.getAttribute('href') ?? '')
      .filter((h) => h.startsWith('http')),
  );
  expect(externas, 'la pagina carga una hoja de estilos externa').toEqual([]);
});

test('la rejilla SE REACOMODA: varias columnas anchas, una sola estrecha', async ({ page }) => {
  await page.setViewportSize({ width: 1400, height: 900 });
  await abrir(page, 'ini-panel');
  const campos = page.locator('[data-slot="tarjeta-campos"] > [data-slot="etiqueta"]');
  await expect(campos.first()).toBeVisible();

  /** Cuantas filas distintas ocupan los campos, por su posicion vertical. */
  const filasDe = async () =>
    page.evaluate(() => {
      const nodos = [
        ...document.querySelectorAll('[data-slot="tarjeta-campos"] > [data-slot="etiqueta"]'),
      ];
      return new Set(nodos.map((n) => Math.round(n.getBoundingClientRect().top))).size;
    });

  const anchas = await filasDe();
  await page.setViewportSize({ width: 400, height: 900 });
  const estrechas = await filasDe();

  // `auto-fit` con minimo de 216 px promete esto y nadie lo habia visto cumplirse: con seis campos
  // a 1400 px caben en dos o tres filas; a 400 px van uno debajo de otro, o sea seis.
  expect(anchas, 'a 1400 px los campos no se reparten en columnas').toBeLessThan(estrechas);
  expect(estrechas, 'a 400 px los campos no bajaron a una columna').toBeGreaterThanOrEqual(5);
});

test('ninguna tabla desplaza la PAGINA de lado', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 900 });
  // `ini-flujo` lleva una tabla de cinco columnas; `predios`, una de seis.
  for (const clave of ['ini-flujo', 'predios']) {
    await abrir(page, clave);
    await expect(page.locator('[data-slot="tabla"]').first()).toBeVisible();
    const seSale = await page.evaluate(
      () => document.documentElement.scrollWidth > document.documentElement.clientWidth,
    );
    // El desplazamiento se acota a la tabla, que es lo unico que lo necesita. Si la pagina entera
    // se desplaza, el resto de la pantalla se va de lado con ella.
    expect(seSale, `«${clave}» hace que la pagina se desplace de lado`).toBe(false);
  }
});

test('y la consola queda limpia', async ({ page }) => {
  const errores: string[] = [];
  page.on('console', (m) => {
    if (m.type() === 'error') errores.push(m.text());
  });
  page.on('pageerror', (e) => errores.push(e.message));

  await abrir(page, 'ini-panel');
  await page.locator('[data-slot="tarjeta"]').first().waitFor();

  // Las 404 de las operaciones que este arnes no contesta son esperadas y salen como peticion
  // fallida, no como error de consola. Lo que no puede haber es un error de la aplicacion.
  expect(errores.filter((e) => !e.includes('404') && !e.includes('Failed to load resource'))).toEqual([]);
});

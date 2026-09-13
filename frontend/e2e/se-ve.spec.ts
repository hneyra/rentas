import { expect, test } from '@playwright/test';

import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **Que la interfaz se VE** (#107).
 *
 * Todo lo de aqui es lo que las pruebas de `vitest` **no pueden** decir, porque comparan
 * `className` como texto: que Tailwind emita el CSS, que el navegador lo aplique, que la rejilla
 * se reacomode y que un `overflow` no corte una tabla.
 */

/**
 * **Este archivo mide la paleta CLARA de `institucional`, y desde #111 lo dice** (`kamayuk-lib`#23).
 *
 * Con las seis paletas ya en el CSS servido, `--color-fondo` deja de tener un valor: tiene seis, y
 * cual sale depende de dos atributos del `<html>` y —cuando el segundo falta— de
 * `prefers-color-scheme`. Lo de aqui se compara contra `diseno/RentasV8.dc.html`, que es la
 * combinacion `institucional/claro` y ninguna otra.
 *
 * <h2>Por que se declara, si Playwright ya va en claro</h2>
 *
 * Porque «ya va en claro» es un valor por omision de otra herramienta, medido y no supuesto:
 * `contextOptions.colorScheme ?? "light"` en `playwright-core`, o sea que emula claro **aunque el
 * equipo este en oscuro**, y solo `colorScheme: null` —que es `no-override`— hereda el del sistema.
 * Comprobado en este arbol: con `'dark'`, el lienzo mide `#111213` y esta comparacion sale
 * «Expected: "#f2f6f9" / Received: "#111213"».
 *
 * O sea: el rojo que este archivo daria si el eje se moviera **no hablaria del eje**, hablaria de
 * un color. Declararlo cuesta una linea y convierte «funciona porque Playwright hace esto» en «se
 * mide esta combinacion a proposito». El otro eje —que la pagina cambie de color de verdad al
 * elegir otra— se mide entero en `los-temas-llegan-al-navegador.spec.ts`.
 */
test.use({ colorScheme: 'light' });

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

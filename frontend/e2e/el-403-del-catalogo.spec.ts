import { expect, test } from '@playwright/test';

import { conLaSeguridadContestada } from './instalacion.ts';
import type { CuerpoDelSinPrivilegio } from '../src/datos/useCatalogoPermitido.ts';

/**
 * **El 403 sobre el catalogo, en el navegador: nombra lo que falta y reintentar monta el arbol** (#311).
 *
 * La prueba de `src/datos/useCatalogoPermitido.test.tsx` mide lo mismo en jsdom. Aqui se mide lo
 * que jsdom no puede: que el aviso **se vea** —con estilo, no como texto suelto— y que el boton
 * sea un boton que el navegador pulsa y que vuelve a salir a la red.
 *
 * Las dos lecturas del catalogo contestan 403 `SIN_PRIVILEGIO` mientras `privilegio` es falso; la
 * matriz y lo demas los contesta `conLaSeguridadContestada`, que es donde vive el paso de la
 * puerta sin identidad. El `route` de aqui se registra DESPUES y por eso se consulta ANTES; lo
 * que no es suyo lo devuelve con `fallback()`.
 */

test.use({ colorScheme: 'light' });

/** El cuerpo con que el doble contesta las dos lecturas del catalogo mientras faltan. */
const SIN_PRIVILEGIO: CuerpoDelSinPrivilegio = {
  title: 'Prohibido',
  status: 403,
  codigo: 'SIN_PRIVILEGIO',
};

test('el 403 dice las dos opciones que faltan, y reintentar monta el arbol cuando las dan', async ({
  page,
}) => {
  await conLaSeguridadContestada(page);

  let privilegio = false;
  let idasAModulos = 0;
  await page.route('**/rentas/api/v1/seguridad/**', async (ruta) => {
    const url = ruta.request().url();
    const esDelCatalogo = url.includes('/seguridad/modulos') || url.includes('/seguridad/accesos');
    if (url.includes('/seguridad/modulos')) idasAModulos += 1;
    if (!esDelCatalogo || privilegio) return ruta.fallback();
    return ruta.fulfill({
      status: 403,
      contentType: 'application/problem+json',
      body: JSON.stringify(SIN_PRIVILEGIO),
    });
  });

  await page.goto('./#/ini-panel');

  const aviso = page.locator('[data-slot="catalogo-sin-privilegio"]');
  await expect(aviso).toBeVisible({ timeout: 15_000 });
  await expect(aviso.locator('li')).toHaveText(['Módulos del sistema', 'Accesos y políticas']);

  // SE VE: el aviso lleva el tono `info` de la libreria, no texto sin estilo. Se compara con el
  // tono y no con un color escrito: el color es de la paleta, y la paleta tiene su propia guarda.
  const alerta = aviso.locator('[data-slot="alerta"]');
  await expect(alerta).toHaveAttribute('data-tono', 'info');
  const fondo = await alerta.evaluate((e) => getComputedStyle(e).backgroundColor);
  expect(fondo, 'el aviso no tiene fondo: la clase se escribio y no se emitio').not.toBe(
    'rgba(0, 0, 0, 0)',
  );
  await expect(page.locator('[data-slot="barra-global"]')).toHaveCount(0);

  const antes = idasAModulos;
  privilegio = true;
  await aviso.getByRole('button', { name: 'Reintentar' }).click();

  await page.locator('[data-slot="barra-global"], header').first().waitFor({ timeout: 15_000 });
  expect(idasAModulos, 'reintentar no volvio a pedir').toBeGreaterThan(antes);
  await expect(aviso).toHaveCount(0);
});

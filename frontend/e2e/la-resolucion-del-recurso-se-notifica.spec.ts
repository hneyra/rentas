import { expect, test } from '@playwright/test';

import type { DiligenciaDeUnaResolucion } from '../src/datos/lecturas.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **La resolucion de un recurso se notifica desde `tra-pap`** (#638), recorrida en Chromium.
 *
 * `src/datos/notificar-la-resolucion-del-recurso.test.tsx` mide lo mismo en jsdom; aqui se mide que
 * el bundle servido lo lleve y que el gesto entero cierre en un navegador: el boton de la hoja, el
 * formulario de la diligencia con su fecha ELEGIDA EN EL CALENDARIO y sus dos desplegables —capas
 * de Radix, que es lo que jsdom mide peor—, la confirmacion de lo que no se corrige, y lo que
 * contesta el backend en la tarjeta de lo hecho.
 *
 * El reloj del navegador va fijo en septiembre de 2026, como en `lo-elegido-va-en-la-ruta.spec.ts`:
 * el mes que el calendario abre —y el dia que se pulsa— no dependen del dia en que se corra.
 */

const EXPEDIENTE = 'REC-2026-000031';

const DILIGENCIADA: DiligenciaDeUnaResolucion = {
  id: 7,
  resolucion: 'RGR-2026-000014',
  numero: 'RGR-2026-000014/1',
  intento: 1,
  fechaDeNotificacion: '2026-09-01',
  modalidad: 'CEDULON',
  resultado: 'NO_UBICADO',
  notificador: 'Vilchez Rojas, Andres',
  direccion: 'Av. Jose de Lama 812, Sullana',
  recibidoPor: null,
  acuse: null,
  exigibleDesde: null,
  abreElPlazoDeLaSancionadora: false,
};

const LA_DILIGENCIA = JSON.stringify(DILIGENCIADA);

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-09-15T12:00:00-05:00'));
  await conLaSeguridadContestada(page);
});

test('en `tra-pap`, notificar la resolucion de un recurso manda el POST del recurso y dice si el plazo corre', async ({
  page,
}) => {
  const mandadas: { readonly camino: string; readonly cuerpo: unknown }[] = [];
  await page.route('**/transito/descargos/*/resolucion/notificacion', (ruta) => {
    const peticion = ruta.request();
    if (peticion.method() !== 'POST') return ruta.fallback();
    mandadas.push({ camino: new URL(peticion.url()).pathname, cuerpo: peticion.postDataJSON() as unknown });
    return ruta.fulfill({ status: 201, contentType: 'application/json', body: LA_DILIGENCIA });
  });

  await abrir(page, 'tra-pap');
  await page.getByRole('button', { name: 'Notificar la resolución de un recurso' }).click();
  // El acto, y no el bloque de la hoja: el bloque tiene su propia fecha.
  const acto = page.locator('form').filter({ has: page.getByLabel('Expediente del recurso') });
  await acto.getByLabel('Expediente del recurso').fill(EXPEDIENTE);

  // El disparador del calendario se llama como lo que ensena, no como su etiqueta: ver
  // `lo-elegido-va-en-la-ruta.spec.ts`. El acto tiene uno solo.
  const fecha = acto.getByRole('button', { name: /^(dd\/mm\/aaaa|\d{2}\/\d{2}\/\d{4})$/ });
  await fecha.click();
  await page.locator('[data-slot="calendario"] td:not([data-outside]) button').filter({ hasText: /^1$/ }).click();
  await page.keyboard.press('Escape');
  await expect(fecha).toHaveText('01/09/2026');

  // Los dos desplegables: se lee el rotulo, y viaja el nombre del enumerado.
  await acto.getByRole('combobox', { name: 'Forma de notificación' }).click();
  await page.getByRole('option', { name: 'Cedulón' }).click();
  await acto.getByRole('combobox', { name: 'Resultado' }).click();
  await page.getByRole('option', { name: 'No ubicado' }).click();

  await acto.getByLabel('Notificador').fill('Vilchez Rojas, Andres');
  await acto.getByLabel('Observación').fill('Cedulon fijado: no se encontro a nadie en el domicilio');
  await page.getByRole('button', { name: 'Registrar la diligencia' }).click();

  // Lo que no se corrige se confirma, y hasta entonces no sale nada.
  const confirmacion = page.getByRole('alertdialog');
  await expect(confirmacion).toContainText('La diligencia no se corrige');
  expect(mandadas).toEqual([]);
  await confirmacion.getByRole('button', { name: 'Si, confirmar' }).click();

  // NO_UBICADO es el unico resultado que no surte efecto: lo hecho lo dice, y no dice «notificada».
  await expect(page.getByText('No se ubicó a nadie: el plazo no corre, y hay que volver a diligenciar')).toBeVisible();
  await expect(
    page.getByText(`Diligencia ${DILIGENCIADA.numero} de la resolución ${DILIGENCIADA.resolucion}`, { exact: false }),
  ).toBeVisible();
  expect(mandadas).toHaveLength(1);
  expect(mandadas[0]?.camino).toMatch(new RegExp(`/transito/descargos/${EXPEDIENTE}/resolucion/notificacion$`));
  expect(mandadas[0]?.cuerpo).toEqual({
    fechaDeNotificacion: '2026-09-01',
    modalidad: 'CEDULON',
    resultado: 'NO_UBICADO',
    notificador: 'Vilchez Rojas, Andres',
    observacion: 'Cedulon fijado: no se encontro a nadie en el domicilio',
  });
});

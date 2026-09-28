import { expect, test } from '@playwright/test';

import type { ActoDeEdificacion } from '../src/datos/lecturas.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **La anulacion de la licencia de edificacion tiene boton** (#629, #455), recorrida en Chromium.
 *
 * `src/datos/actos.test.tsx` mide lo mismo en jsdom; aqui se mide que el bundle servido lo lleve y
 * que el gesto entero cierre en un navegador: el boton de `aut-sol`, el formulario con su
 * observacion, la confirmacion de lo que no se deshace —una capa de Radix, que es lo que jsdom mide
 * peor— y la resolucion que contesta el backend en la tarjeta de lo hecho.
 */

const EXPEDIENTE = 'EXP-2026-0042';

const ANULADA: ActoDeEdificacion = {
  nroExpediente: EXPEDIENTE,
  nroLicencia: 'LE-2026-0017',
  acto: 'ANULACION',
  fecha: '2026-09-28',
  resolucion: { numero: 'RES-AE-2026-0003', formato: 'PDF', resumen: 'a1b2', bytes: 18_422, reimpresiones: 0 },
  vigencias: [],
  valorDeObraNoDisponible: null,
};

const LA_ANULACION = JSON.stringify(ANULADA);

test('en `aut-sol`, anular la licencia de edificacion manda el POST del expediente y dice la resolucion', async ({
  page,
}) => {
  await conLaSeguridadContestada(page);
  const mandadas: { readonly camino: string; readonly cuerpo: unknown }[] = [];
  await page.route('**/licencias/edificacion/*/anulacion', (ruta) => {
    const peticion = ruta.request();
    if (peticion.method() !== 'POST') return ruta.fallback();
    mandadas.push({ camino: new URL(peticion.url()).pathname, cuerpo: peticion.postDataJSON() as unknown });
    return ruta.fulfill({ status: 201, contentType: 'application/json', body: LA_ANULACION });
  });

  await abrir(page, 'aut-sol');
  await page.getByRole('button', { name: 'Anular una licencia de edificación' }).click();
  await page.getByLabel('Expediente del FUE').fill(EXPEDIENTE);
  await page.getByLabel('Motivo de la anulación').fill('Se aprobo sobre planos que no correspondian');
  await page.getByLabel('Observación').fill('Anulacion ordenada por la gerencia');
  await page.getByRole('button', { name: 'Anular la licencia' }).click();

  // Lo que no se deshace se confirma, y hasta entonces no sale nada.
  const confirmacion = page.getByRole('alertdialog');
  await expect(confirmacion).toContainText('La anulación no se deshace');
  expect(mandadas).toEqual([]);
  await confirmacion.getByRole('button', { name: 'Si, confirmar' }).click();

  await expect(page.getByText('Licencia anulada')).toBeVisible();
  await expect(
    page.getByText(`Resolución ${ANULADA.resolucion.numero}: la licencia ${ANULADA.nroLicencia}`, { exact: false }),
  ).toBeVisible();
  expect(mandadas).toHaveLength(1);
  expect(mandadas[0]?.camino).toMatch(new RegExp(`/licencias/edificacion/${EXPEDIENTE}/anulacion$`));
  expect(mandadas[0]?.cuerpo).toEqual({
    motivo: 'Se aprobo sobre planos que no correspondian',
    observacion: 'Anulacion ordenada por la gerencia',
  });
});

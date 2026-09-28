import { expect, test } from '@playwright/test';

import type {
  InternamientoEnDeposito,
  MovimientoDeLaBitacora,
  Paginado,
  SesionDeLaVentanilla,
  VehiculoServido,
} from '../src/datos/lecturas.ts';
import { SESION_MEDIDA } from '../src/datos/sesionMedida.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **Lo elegido en un campo de `seg-aud` y de `tra-veh` va a la ruta, y de la ruta a la lectura**
 * (#629; `kamayuk-lib`#97 nombraba estos mandos junto al buscador de `aut-cat`, #172).
 *
 * Lo que jsdom no alcanza: elegir un dia **dentro** del calendario abre la capa de Radix, y bajo
 * jsdom eso no cabe en el tope —lo dejo medido `kamayuk-lib`#97—. Aqui se recorre el gesto entero
 * en Chromium: abrir «Desde», pulsar un dia, ver la direccion cambiar y la bitacora salir con el
 * parametro. Y en `tra-veh`, teclear la placa, salir del campo y ver la hoja abrirse con ella.
 *
 * El reloj del navegador va fijo en septiembre de 2026 para que el mes que el calendario abre —y el
 * dia que se pulsa— no dependan del dia en que se corra.
 */

const EJERCICIO = 2026;

const SESION_CON_EJERCICIO: SesionDeLaVentanilla = { ...SESION_MEDIDA, ejercicioDeTrabajo: EJERCICIO };

const MOVIMIENTO: MovimientoDeLaBitacora = {
  id: 41184,
  ejercicio: EJERCICIO,
  tabla: 'recibo',
  clave: '0003-0041184',
  operacion: 'ANULACION',
  usuario: 'administrador',
  origenEquipo: null,
  origenIp: null,
  fecha: '2026-09-03T09:41:12-05:00',
  observacion: 'Anulado por duplicado',
  datosAnteriores: null,
  datosNuevos: null,
};

const FICHA: VehiculoServido = {
  id: 7,
  placa: 'T2G-418',
  contribuyenteId: 25673,
  marca: 'TOYOTA',
  modelo: 'YARIS',
  categoria: 'M1',
  anioFabricacion: 2014,
  anioInscripcion: 2015,
  numeroMotor: '2NZ-1188412',
  numeroSerie: 'JTDBT923771118841',
  estado: 'ACTIVO',
  historialDePlacas: [],
};

const INTERNADO: InternamientoEnDeposito = {
  id: 1,
  placa: 'T2G-418',
  clase: 'AUTOMOVIL',
  papeleta: '0041182',
  deposito: 'DEPOSITO MUNICIPAL 1',
  fechaDeIngreso: '2026-07-18',
  fechaDeSalida: null,
  dias: 54,
  calculadoA: '2026-09-10',
  estado: 'INTERNADO',
  tasaDeCustodia: 'TUPA-2.14 CUSTODIA DIARIA',
  acta: 'ACTA-2026-0311',
};

function paginaDe<T>(contenido: readonly T[]): Paginado<T> {
  return { contenido, pagina: 0, tamano: 20, totalElementos: contenido.length, totalPaginas: 1, hayMas: false };
}

const BITACORA = JSON.stringify(paginaDe([MOVIMIENTO]));
const LA_SESION = JSON.stringify(SESION_CON_EJERCICIO);
const LA_FICHA = JSON.stringify(FICHA);
const EL_DEPOSITO = JSON.stringify(paginaDe([INTERNADO]));

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(new Date('2026-09-15T12:00:00-05:00'));
  await conLaSeguridadContestada(page);
});

test('`seg-aud`: elegir un dia en «Desde» lo lleva a la ruta y a la lectura de la bitacora', async ({ page }) => {
  // La sesion CON ejercicio: sin el, `seg-aud` no pide nada y no habria lectura que mirar. La ruta
  // registrada despues gana a la de `conLaSeguridadContestada`.
  await page.route('**/rentas/api/v1/seguridad/sesion', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: LA_SESION }),
  );
  const pedidas: string[] = [];
  await page.route('**/seguridad/auditoria*', (ruta) => {
    pedidas.push(new URL(ruta.request().url()).search);
    return ruta.fulfill({ status: 200, contentType: 'application/json', body: BITACORA });
  });

  await abrir(page, 'seg-aud');
  await expect.poll(() => pedidas.length, { message: 'la bitacora no se pidio ni una vez' }).toBeGreaterThan(0);
  expect(pedidas.every((consulta) => !consulta.includes('desde='))).toBe(true);

  // El disparador del calendario NO toma su nombre de la etiqueta: se llama como lo que ensena
  // —«dd/mm/aaaa» vacio, la fecha despues—, medido en el arbol de accesibilidad de Chromium, y
  // `getByLabel('Desde')` no encuentra nada. Es un hueco de `@kamayuk/ui`, anotado en el informe de
  // #629. Asi que se toma el PRIMER campo de fecha de la hoja, que en `seg-aud` es «Desde».
  const desde = page.getByRole('button', { name: /^(dd\/mm\/aaaa|\d{2}\/\d{2}\/\d{4})$/ }).first();
  await desde.click();
  await page
    .locator('[data-slot="calendario"] td:not([data-outside]) button')
    .filter({ hasText: /^3$/ })
    .click();

  await expect
    .poll(() => new URL(page.url()).hash, {
      message: 'el dia elegido no llego a la ruta de la hoja: el calendario sigue sin mover nada',
    })
    .toContain('desde=2026-09-03');
  await expect
    .poll(() => pedidas.some((consulta) => consulta.includes('desde=2026-09-03')), {
      message: 'la ruta cambio y la bitacora no salio con el parametro',
    })
    .toBe(true);
  // Y el campo lo LEE como se lee aqui una fecha.
  await expect(desde).toHaveText('03/09/2026');
});

test('`tra-veh`: teclear la placa y salir del campo la hace el sujeto de la ruta, y la hoja la pide', async ({
  page,
}) => {
  const fichas: string[] = [];
  await page.route('**/rentas/vehiculos/*', (ruta) => {
    fichas.push(new URL(ruta.request().url()).pathname);
    return ruta.fulfill({ status: 200, contentType: 'application/json', body: LA_FICHA });
  });
  await page.route('**/transito/internamientos*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: EL_DEPOSITO }),
  );

  await abrir(page, 'tra-veh');
  // Sin placa en la direccion, no se pide ninguna ficha.
  await expect(page.getByLabel('Placa')).toBeVisible();
  expect(fichas).toEqual([]);

  await page.getByLabel('Placa').fill('T2G-418');
  await page.getByLabel('Placa').press('Tab');

  await expect
    .poll(() => new URL(page.url()).hash, { message: 'la placa tecleada no llego a la ruta de la hoja' })
    .toMatch(/^#\/tra-veh\/T2G-418/);
  await expect
    .poll(() => fichas.some((camino) => camino.endsWith('/rentas/vehiculos/T2G-418')), {
      message: 'la ruta cambio y la ficha de esa placa no se pidio',
    })
    .toBe(true);
  await expect(page.getByLabel('Placa')).toHaveValue('T2G-418');
});

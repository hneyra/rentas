import { expect, test } from '@playwright/test';

import type { GiroCiiu, Paginado } from '../src/datos/lecturas.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **La caja «Buscar giro o actividad» de `aut-cat` escribe la ruta, y la ruta llega a la
 * operacion** (#172 AC4).
 *
 * El canal de este lado existia desde #229 —el conector declara y manda `?descripcion=`— y lo que
 * faltaba era quien lo escribiera desde la pantalla: `@kamayuk/ui` guardaba lo tecleado en su
 * estado y no lo publicaba. `kamayuk-lib`#94 lo publica con `eleccion.enLaRuta`, y aqui se mide
 * el camino entero en un navegador: teclear, salir del campo, ver la direccion cambiar y la lectura
 * salir con el parametro.
 *
 * Los giros no son cifras: son dos filas del catalogo CIIU con su codigo y su descripcion, que es
 * lo minimo para distinguir la lista entera de la acotada.
 */

const RESTAURANTE: GiroCiiu = {
  codigo: '5610',
  descripcion: 'Restaurantes y servicio movil de comidas',
  seccion: 'I',
  riesgoItse: 'MEDIO',
  zonificacionCompatible: 'CZ',
  requiereSectorial: false,
  extendido: false,
  activo: true,
};

const BODEGA: GiroCiiu = {
  codigo: '4711',
  descripcion: 'Bodega de abarrotes',
  seccion: 'G',
  riesgoItse: 'BAJO',
  zonificacionCompatible: 'CZ',
  requiereSectorial: false,
  extendido: false,
  activo: true,
};

function paginaDe(contenido: readonly GiroCiiu[]): Paginado<GiroCiiu> {
  return {
    contenido,
    pagina: 0,
    tamano: 20,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  };
}

const TODOS = JSON.stringify(paginaDe([RESTAURANTE, BODEGA]));
const SOLO_BODEGA = JSON.stringify(paginaDe([BODEGA]));

test('teclear en el buscador y salir del campo lleva la busqueda a la ruta y a la lectura', async ({
  page,
}) => {
  await conLaSeguridadContestada(page);
  const pedidas: string[] = [];
  await page.route('**/licencias/ciiu*', (ruta) => {
    const url = new URL(ruta.request().url());
    pedidas.push(url.search);
    const acotada = url.searchParams.get('descripcion') !== null;
    return ruta.fulfill({
      status: 200,
      contentType: 'application/json',
      body: acotada ? SOLO_BODEGA : TODOS,
    });
  });

  await abrir(page, 'aut-cat');
  await expect(page.getByText(RESTAURANTE.descripcion)).toBeVisible();

  await page.getByLabel('Buscar giro o actividad').fill('bodega');
  await page.getByLabel('Buscar giro o actividad').press('Tab');

  await expect
    .poll(() => new URL(page.url()).hash, {
      message: 'lo tecleado no llego a la ruta de la hoja: la caja sigue sin mover nada',
    })
    .toContain('descripcion=bodega');
  await expect
    .poll(() => pedidas.some((consulta) => consulta.includes('descripcion=bodega')), {
      message: 'la ruta cambio y la lectura no salio con el parametro',
    })
    .toBe(true);
  await expect(page.getByText(BODEGA.descripcion)).toBeVisible();
  await expect(page.getByText(RESTAURANTE.descripcion)).toHaveCount(0);
});

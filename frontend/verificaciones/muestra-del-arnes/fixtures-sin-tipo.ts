// Viola: `verificaciones/los-fixtures-del-arnes-llevan-tipo.test.ts`. A PROPOSITO, las seis formas.
//
// Compila, pasa el lint y Playwright la ejecutaria sin rechistar: el arnes transpila sin comprobar
// tipos, y aunque `tsc` la lee —esta bajo `verificaciones/`— no tiene contra que compararla. Es la
// forma exacta del defecto de #313: el fixture de `la-insignia…` servia una corrida sin los dos
// campos que #271 anadio, y el rojo llego como un tiempo agotado esperando la barra.
//
// No vive en `e2e/`, asi que Playwright no la corre; y no vive en `muestras/`, que es de las
// prohibiciones de ESLint y exige que cada archivo tenga una que lo reclame.

import type { Page } from '@playwright/test';

import type { CorridaDelPredial, Paginado } from '../../src/datos/lecturas.ts';

/** (1) Un objeto literal, sin tipo: `tsc` no lo compara con nada. */
const CORRIDA_CORTA = {
  id: 1,
  ejercicio: '2026',
};

/**
 * (6) Una interfaz que NO es del contrato, declarada aqui mismo y no en `src/datos/`: es lo que
 * seria la fila de una pagina inventada.
 */
interface NoEsDelContrato {
  readonly inventado: string;
}

/**
 * (6, sigue) Un generico DE `src/datos/` —`Paginado<T>`— instanciado con un `T` que NO lo es.
 *
 * Hallazgo de revision (#314): la comprobacion original solo miraba el CONTENEDOR (`Paginado`,
 * que si es de `src/datos/`) y nunca el ARGUMENTO. `T` es la fila, y la fila es exactamente la
 * parte que se rompe en silencio si crece sin que el fixture la seria —el defecto de #313, un
 * nivel mas adentro del contenedor que #312 ya tipaba—.
 */
const PAGINA_DE_INVENTADO: Paginado<NoEsDelContrato> = {
  contenido: [{ inventado: 'x' }],
  pagina: 0,
  tamano: 1,
  totalElementos: 1,
  totalPaginas: 1,
  hayMas: false,
};

export async function sirveFixturesSinTipo(pagina: Page): Promise<void> {
  await pagina.route('**/rentas/predial/corridas/ultima*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CORRIDA_CORTA) }),
  );

  // (2) Un `as`: compila con un campo, porque una asercion solo exige que los tipos se solapen.
  await pagina.route('**/rentas/predial/corridas/ultima?x*', (ruta) =>
    ruta.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ id: 1 } as CorridaDelPredial),
    }),
  );

  // (3) La opcion `json` de Playwright, que serializa por su cuenta: sin `JSON.stringify` a la vista.
  await pagina.route('**/indicadores/trabajo-parado*', (ruta) =>
    ruta.fulfill({ status: 200, json: { ejercicio: 2026 } }),
  );

  // (4) El fixture escrito como texto: ningun compilador lo lee.
  await pagina.route('**/fiscalizacion/programas?*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: '{"contenido":[]}' }),
  );

  // (5) Un envoltorio que BORRA el tipo al pasarlo por `unknown`: el valor lo tenia, el cuerpo no.
  const json = (cuerpo: unknown) =>
    pagina.route('**/fiscalizacion/programas/*/muestra*', (ruta) =>
      ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(cuerpo) }),
    );
  await json(CORRIDA_CORTA);

  // (6) El CONTENEDOR es de `src/datos/` —`Paginado`— pero el ARGUMENTO no lo es.
  await pagina.route('**/fiscalizacion/programas/*/inventado*', (ruta) =>
    ruta.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(PAGINA_DE_INVENTADO),
    }),
  );
}

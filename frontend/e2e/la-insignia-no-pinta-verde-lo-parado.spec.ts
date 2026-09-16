import { expect, test } from '@playwright/test';

import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **`ini-parado` deja de pintar verde lo que esta parado, medido en el navegador** (#175, AC3).
 *
 * <h2>Por que este camino, si ya hay dos guardas de tonos</h2>
 *
 * Porque las dos miran codigo. `src/pantallas/tono.test.ts` prueba la funcion; la guarda de
 * `verificaciones/` barre las 40 definiciones y el enumerado del backend. **Ninguna de las dos
 * dice de que color sale la celda**: entre `tonoDe()` y el pixel estan el interprete de
 * `@kamayuk/ui`, la clase `bg-info-fondo`, que Tailwind la emita —y `@source` llega a
 * `node_modules` (#107)— y que el navegador la aplique. El defecto de #175 era **un color**, y un
 * color se mide en un navegador.
 *
 * <h2>Lo que se contesta, y por que este archivo SI contesta a una operacion</h2>
 *
 * `instalacion.ts` 404 todo lo que no es seguridad, a proposito: las pantallas conectadas tienen
 * que verse **en su estado de error**, y contestarles algo inventado seria ensenar cifras que no
 * existen. Aqui se contesta a una sola operacion y **sin una sola cifra**: las cuatro frases son
 * las del enumerado `FrenteDeTrabajo` del backend de este repositorio, los recuentos van a cero y
 * los importes a `null` —que es la forma en que la operacion dice «sin cifrar»—. Lo que se mide es
 * el COLOR de la quinta columna; un recuento inventado seria una cifra que se lee como real.
 *
 * <h2>Y se mide el contraejemplo en la misma corrida</h2>
 *
 * Una comprobacion de «esto no es verde» la pasa tambien una interfaz en la que **nada** es verde
 * —por ejemplo, si `bg-ok-fondo` no llegara al CSS servido—. Asi que el segundo camino abre
 * `panel` con la respuesta medida de la ultima corrida y exige que «Conforme» **si** salga verde y
 * «Observado» rojo. Los dos caminos juntos dicen la propiedad entera: el verde llega, y no cae
 * donde no se ha ganado.
 */

/** Los cuatro tonos del artboard, tal como `diseno/rentas-tokens.css` los declara. */
const TONOS = {
  /** `--ok-fondo: #dcefe3` */
  ok: 'rgb(220, 239, 227)',
  /** `--mal-fondo: #fbe4e0` */
  mal: 'rgb(251, 228, 224)',
  /** `--info-fondo: #e4f4fd`, que es el tono de «no se» desde #175. */
  sinReconocer: 'rgb(228, 244, 253)',
};

/**
 * Las cuatro frases que `GET /indicadores/trabajo-parado` publica, copiadas del enumerado
 * `FrenteDeTrabajo` del backend de este repositorio.
 *
 * **Que esta copia siga siendo la del backend no se vigila aqui**, y no hace falta: la guarda
 * `verificaciones/la-insignia-no-se-pinta-verde-sin-regla.test.ts` lee ese Java y comprueba las
 * frases una a una. Aqui hacen de cuerpo de una respuesta, que es lo que un arnes necesita.
 */
const FRENTES = [
  {
    frente: 'TRANSITO',
    modulo: 'Transito',
    queEstaParado: 'papeletas sin resolucion de multa emitida',
    porQueCuestaDinero: 'sin emitir no se pueden notificar ni cobrar, y prescriben',
    cuantos: 0,
    importe: null,
  },
  {
    frente: 'VALORES',
    modulo: 'Valores',
    queEstaParado: 'valores emitidos y sin notificar',
    porQueCuestaDinero: 'existen, no cobran, y el plazo de prescripcion les corre igual',
    cuantos: 0,
    importe: null,
  },
  {
    frente: 'COACTIVA',
    modulo: 'Coactiva',
    queEstaParado: 'expedientes importados sin REC-1',
    porQueCuestaDinero: 'el expediente esta abierto y el procedimiento no ha empezado',
    cuantos: 0,
    importe: null,
  },
  {
    frente: 'CATASTRO',
    modulo: 'Catastro',
    queEstaParado: 'predios con ficha y sin conciliar con rentas',
    porQueCuestaDinero: 'tienen ficha catastral y no generan deuda predial',
    cuantos: 0,
    importe: null,
  },
];

/**
 * La ultima corrida del padron, **medida contra la instalacion** el 2026-09-07 y recortada a lo
 * que la tabla de `panel` dibuja. Es la misma que ejercita `src/datos/conectores.test.ts`.
 */
const CORRIDA = {
  id: 1,
  ejercicio: '2026',
  alcance: 'PADRON',
  sector: null,
  simulacion: false,
  conjunto: 'V3',
  fechaCalculo: '28/01/2026 02:14',
  observados: 534,
  etapas: [
    { etapa: 'Lectura del padron', registros: 62418, monto: '—', observados: 0, estado: 'Conforme' },
    { etapa: 'Generacion de cuponeras', registros: 61350, monto: '—', observados: 534, estado: 'Observado' },
  ],
};

test.use({ colorScheme: 'light' });

test.beforeEach(async ({ page }) => {
  await conLaSeguridadContestada(page);
  // Va DESPUES de la de `instalacion.ts` a proposito: Playwright prueba los manejadores en orden
  // inverso al de registro, asi que este gana sobre el 404 general sin tocar el archivo comun.
  await page.route('**/indicadores/trabajo-parado*', (ruta) =>
    ruta.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        ejercicio: 2026,
        fechaCalculo: '2026-09-16',
        calculadoEn: '2026-09-16T10:00:00Z',
        frentes: FRENTES,
      }),
    }),
  );
  await page.route('**/rentas/predial/corridas/ultima*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CORRIDA) }),
  );
});

test('la insignia de `ini-parado` NO dice «conforme» sobre trabajo parado', async ({ page }) => {
  await abrir(page, 'ini-parado');

  for (const frente of FRENTES) {
    const insignia = page.getByText(frente.porQueCuestaDinero, { exact: true });
    await expect(insignia, `no se dibujo el frente «${frente.frente}»`).toBeVisible();

    const fondo = await insignia.evaluate((e) => getComputedStyle(e).backgroundColor);
    expect(
      fondo,
      `«${frente.porQueCuestaDinero}» se pinta de VERDE: la interfaz esta diciendo «conforme»\n` +
        'sobre trabajo que esta parado y cuesta dinero. Es el defecto de #175.',
    ).not.toBe(TONOS.ok);
    expect(fondo, 'el tono de «no se» no llego al navegador').toBe(TONOS.sinReconocer);

    // Y la frase sigue DENTRO de la insignia: el tono neutro deja de calificar el estado, no de
    // ensenarlo. Un tono que se comunicara solo por color no se comunicaria a quien no lo ve.
    await expect(insignia).toHaveText(frente.porQueCuestaDinero);
  }
});

test('y el verde SI llega donde se ha ganado: «Conforme» en `panel` es verde, «Observado» rojo', async ({
  page,
}) => {
  await abrir(page, 'panel');

  const conforme = page.getByText('Conforme', { exact: true }).first();
  await expect(conforme, 'la tabla de la corrida no se dibujo').toBeVisible();
  expect(
    await conforme.evaluate((e) => getComputedStyle(e).backgroundColor),
    'el verde de «conforme» no llego al navegador: entonces la comprobacion de arriba no dice nada',
  ).toBe(TONOS.ok);

  const observado = page.getByText('Observado', { exact: true }).first();
  await expect(observado).toBeVisible();
  expect(await observado.evaluate((e) => getComputedStyle(e).backgroundColor)).toBe(TONOS.mal);
});

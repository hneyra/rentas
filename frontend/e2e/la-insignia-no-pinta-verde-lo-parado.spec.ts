import { expect, test } from '@playwright/test';

import type {
  CorridaDelPredial,
  FilaDeLaMuestra,
  FrenteParado,
  Paginado,
  ProgramaDeFiscalizacion,
  TrabajoParado,
} from '../src/datos/lecturas.ts';
import { abrir, conLaSeguridadContestada } from './instalacion.ts';

/**
 * **`ini-parado` no pinta de ningun semaforo lo que esta parado, medido en el navegador** (#175
 * AC3, #218).
 *
 * <h2>Lo que este archivo media, y en que cambia con #218</h2>
 *
 * Media dos mitades: que la frase de `ini-parado` **no saliera verde** y que el verde **si
 * llegara** donde se gana. #218 cierra la primera por el otro lado: la quinta columna **deja de
 * ser de insignia**, porque le llega `porQueCuestaDinero` —una FRASE— y #183 midio que el backend
 * no puede publicar el estado. O sea que ya no hay semaforo que colorear ahi, y lo que este camino
 * mide ahora es **que no lo haya**: la frase sigue en su celda, sin fondo de insignia ninguno.
 *
 * Y la mitad que #218 dejaria huerfana —«el tono de "no se" llega al navegador»— **no se pierde**:
 * se muda a `fis-prog`, cuya quinta columna sigue siendo de insignia y trae los dos casos en la
 * misma tabla —«Inspeccionado» se gana el verde y «Programado» no—. Sin ese tercer camino, la
 * comprobacion de «esto no es verde» la pasaria tambien una interfaz en la que **nada** es verde.
 *
 * <h2>Por que estos caminos, si ya hay dos guardas de tonos</h2>
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

/** Lo que el navegador contesta de una celda de texto: ningun fondo propio. */
const SIN_FONDO = 'rgba(0, 0, 0, 0)';

/**
 * Las cuatro frases que `GET /indicadores/trabajo-parado` publica, copiadas del enumerado
 * `FrenteDeTrabajo` del backend de este repositorio.
 *
 * **Que esta copia siga siendo la del backend no se vigila aqui**, y no hace falta: la guarda
 * `verificaciones/la-insignia-no-se-pinta-verde-sin-regla.test.ts` lee ese Java y comprueba las
 * frases una a una. Aqui hacen de cuerpo de una respuesta, que es lo que un arnes necesita.
 */
const FRENTES: readonly FrenteParado[] = [
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
 *
 * **Y lleva el tipo de la operacion** (#312). Sin el, el fixture se quedo corto dos veces al
 * crecer esta misma operacion —#271 y #313— y el rojo salio como un tiempo agotado esperando la
 * barra, sin nombrar ni el archivo ni el campo. Con el tipo, un campo que falte no compila.
 */
const CORRIDA: CorridaDelPredial = {
  id: 1,
  ejercicio: '2026',
  // `TODOS`, que es un alcance que el backend contesta: `PADRON` no es ninguno, y desde #357 el
  // conector lee el alcance.
  alcance: 'TODOS',
  sector: null,
  simulacion: false,
  conjunto: 'V3',
  // ISO y sin hora, que es la forma que el backend publica (#389): `panel` la pasa por
  // `formatearFecha`, y con el texto del artboard la hoja revienta.
  fechaCalculo: '2026-01-28',
  observados: 534,
  // Los dos que #271 anadio a la operacion. Sin ellos el conector llama a `formatearEntero` con
  // `undefined`, revienta, y el armazon **no llega a montarse**: el rojo sale como un tiempo
  // agotado esperando la barra, sin nombrar ni este archivo ni el campo que falta.
  determinados: 58412,
  montoEmitido: '8772431.05',
  // Y los dos que #312 anadio: el conjunto sellado con que emitio y el derecho que aplico. Este
  // arnes no abre `panel` —lo que mira es `ini-panel`—, pero el fixture es la forma de la
  // operacion y un fixture corto se queda corto en silencio, que es como se rompieron dos caminos
  // en la ola anterior.
  conjuntoId: 77,
  derechoDeEmision: '4.50',
  // Los estados y el monto vacio que compone `CorridaGuardadaResource` (#389): «OK» y «CON
  // OBSERVACIONES», no el «Conforme» y el «Observado» que el artboard dibuja.
  etapas: [
    { etapa: 'Padrón leído', registros: 62418, monto: '', observados: 0, estado: 'OK' },
    { etapa: 'Determinados', registros: 61350, monto: '9418204.60', observados: 534, estado: 'CON OBSERVACIONES' },
  ],
};

/**
 * Las dos lecturas de `fis-prog`, construidas desde `docs/50-api/formas-de-la-api.json`.
 *
 * **Sin una sola cifra de dinero**: la muestra no publica ninguna —sus tres magnitudes son areas—
 * y lo que se mide aqui es el COLOR de la quinta columna. Las dos filas son los dos casos de
 * `visitado`, que es de donde sale esa columna.
 */
const PROGRAMAS: Paginado<ProgramaDeFiscalizacion> = {
  contenido: [
    {
      id: 14,
      codigo: 'PF-2026-014',
      descripcion: 'Cruce de area construida en el sector 02',
      tipo: 'PREDIAL',
      fechaInicio: '2026-03-02',
      fechaFin: null,
      estado: 'EN_PROCESO',
      ejercicio: '2026',
      sector: '02',
      criterio: 'SUBVALUADOR',
      fiscalizador: 'Reto Santos, Victor',
    },
  ],
  pagina: 0,
  tamano: 1,
  totalElementos: 6,
  totalPaginas: 6,
  hayMas: true,
};

const MUESTRA: Paginado<FilaDeLaMuestra> = {
  contenido: [
    {
      programaId: 14,
      predioId: 9014,
      codRefCatastral: '02-014-D-14-01',
      contribuyenteId: 25673,
      codContribuyente: '00000025673',
      titular: 'Suc. Rufina Medina Medina',
      sector: '02',
      condicion: 'SUBVALUADOR',
      areaCatastral: '198.00',
      areaDeclarada: '164.50',
      diferenciaDeArea: '33.50',
      visitado: true,
      fechaSorteo: '2026-03-02',
    },
    {
      programaId: 14,
      predioId: 9021,
      codRefCatastral: '04-021-B-07-00',
      contribuyenteId: null,
      codContribuyente: null,
      titular: null,
      sector: '04',
      condicion: 'OMISO',
      areaCatastral: '120.00',
      areaDeclarada: null,
      diferenciaDeArea: null,
      visitado: false,
      fechaSorteo: '2026-03-02',
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 84,
  totalPaginas: 5,
  hayMas: true,
};

/** `GET /indicadores/trabajo-parado`, con los cuatro frentes de arriba. */
const TRABAJO_PARADO: TrabajoParado = {
  ejercicio: 2026,
  fechaCalculo: '2026-09-16',
  calculadoEn: '2026-09-16T05:00:00-05:00',
  frentes: FRENTES,
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
      body: JSON.stringify(TRABAJO_PARADO),
    }),
  );
  await page.route('**/rentas/predial/corridas/ultima*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(CORRIDA) }),
  );
  // Las DOS lecturas de `fis-prog`, en su orden: la relacion dice cual programa y la muestra trae
  // sus predios. El orden de los manejadores importa —el mas especifico va DESPUES—, porque
  // Playwright los prueba en orden inverso al de registro.
  await page.route('**/fiscalizacion/programas?*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(PROGRAMAS) }),
  );
  await page.route('**/fiscalizacion/programas/*/muestra*', (ruta) =>
    ruta.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(MUESTRA) }),
  );
});

test('la quinta columna de `ini-parado` dice la frase y NO es un semaforo (#218)', async ({
  page,
}) => {
  await abrir(page, 'ini-parado');

  for (const frente of FRENTES) {
    const celda = page.getByText(frente.porQueCuestaDinero, { exact: true });
    await expect(celda, `no se dibujo el frente «${frente.frente}»`).toBeVisible();

    // **La frase se queda**: lo que #218 retira es la insignia, no la columna. Sin esto, quitar la
    // columna entera pasaria este camino igual de bien que quitarle el semaforo.
    await expect(celda).toHaveText(frente.porQueCuestaDinero);

    const fondo = await celda.evaluate((e) => getComputedStyle(e).backgroundColor);
    expect(
      fondo,
      `«${frente.porQueCuestaDinero}» se pinta de VERDE: la interfaz esta diciendo «conforme»\n` +
        'sobre trabajo que esta parado y cuesta dinero. Es el defecto de #175.',
    ).not.toBe(TONOS.ok);
    // Y tampoco del tono de «no se»: desde #218 ahi no hay insignia ninguna. Un semaforo que no
    // se puede encender nunca es un semaforo que sobra — #183 midio que el estado del frente no lo
    // publica nadie y que no hay plazo publicado con el que juzgarlo.
    expect(
      fondo,
      'La quinta columna de `ini-parado` volvio a dibujarse como INSIGNIA. No puede: lo que le\n' +
        'llega es una frase, y #183 se cerro midiendo que el backend no publica ningun estado.',
    ).not.toBe(TONOS.sinReconocer);
    expect(fondo, 'una celda de texto no lleva fondo propio').toBe(SIN_FONDO);
  }
});

test('y el tono de «no se» SI llega al navegador, en la tabla de `fis-prog` (#218)', async ({
  page,
}) => {
  // La mitad que #218 dejaria huerfana. Se mide en la misma tabla y en la misma corrida, con sus
  // dos casos: `visitado: true` -> «Inspeccionado», que la lista de CONFORME de `tono.ts` reconoce
  // y se gana el verde; `visitado: false` -> «Programado», que ninguna regla reconoce —«todavia no
  // se ha ido a mirar» no es un juicio— y sale con el tono de «no se».
  await abrir(page, 'fis-prog');

  const inspeccionado = page.getByText('Inspeccionado', { exact: true }).first();
  await expect(inspeccionado, 'la muestra del programa no se dibujo').toBeVisible();
  expect(
    await inspeccionado.evaluate((e) => getComputedStyle(e).backgroundColor),
    'el verde no llego a la insignia de `fis-prog`',
  ).toBe(TONOS.ok);

  const programado = page.getByText('Programado', { exact: true }).first();
  await expect(programado).toBeVisible();
  expect(
    await programado.evaluate((e) => getComputedStyle(e).backgroundColor),
    '«Programado» se pinta de VERDE, y no es un juicio de la administracion: dice que todavia no\n' +
      'se ha ido a mirar. Es el defecto de #175 en la hoja que lo hereda.',
  ).toBe(TONOS.sinReconocer);
});

test('y el verde SI llega donde se ha ganado: «OK» en `panel` es verde, «CON OBSERVACIONES» rojo', async ({
  page,
}) => {
  // Con los DOS estados que el backend publica (#389). Hasta #389 esta prueba buscaba «Conforme» y
  // «Observado» —las palabras del artboard, que el fixture traia y la instalacion no contesta—, y
  // con las de verdad las dos insignias salian con el tono de «no se».
  await abrir(page, 'panel');

  const conforme = page.getByText('OK', { exact: true }).first();
  await expect(conforme, 'la tabla de la corrida no se dibujo').toBeVisible();
  expect(
    await conforme.evaluate((e) => getComputedStyle(e).backgroundColor),
    'el verde de «OK» no llego al navegador: entonces la comprobacion de arriba no dice nada',
  ).toBe(TONOS.ok);

  const observado = page.getByText('CON OBSERVACIONES', { exact: true }).first();
  await expect(observado).toBeVisible();
  expect(await observado.evaluate((e) => getComputedStyle(e).backgroundColor)).toBe(TONOS.mal);
});

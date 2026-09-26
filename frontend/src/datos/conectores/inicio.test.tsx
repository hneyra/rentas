import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { pantallaDe } from '../../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import type { CorridaDelPredial, IndicadorDeRecaudacion, TrabajoParado } from '../lecturas.ts';
import {
  DE_UNA_PARTE_DEL_PADRON,
  LA_ULTIMA_FUE_UNA_SIMULACION,
  SIN_EMISION_DEL_EJERCICIO,
} from '../palabrasDeHueco.ts';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';

/**
 * **Que las tres hojas de Inicio ensenen lo que LLEGO, y no su forma** (#167, AC1 y AC3).
 *
 * <h2>Por que se monta la pantalla entera y no se llama al conector</h2>
 *
 * Porque llamar a `repartir()` y comparar su `Map` comprueba que el conector devuelve lo que el
 * conector devuelve. Lo que hay que saber es otra cosa: **que eso llega al DOM**. Entre el reparto
 * y la pantalla estan la coordenada de cada campo, el orden de las columnas y el interprete, y
 * cualquiera de los tres puede estar mal sin que el `Map` lo note — una coordenada corrida de uno
 * pinta el recaudado bajo el rotulo «Avance» y la prueba del `Map` sale verde.
 *
 * <h2>Y por que se contesta DOS veces con respuestas distintas</h2>
 *
 * Es la diferencia entre conectar y **parecer** conectado. Una pantalla que dibuja las cifras de
 * su definicion —o las del artboard, o un respaldo— se ve exactamente igual que una conectada
 * mientras el doble conteste lo que ella ya dibujaba. Con dos respuestas distintas solo hay una
 * forma de pasar: leer la que llego.
 *
 * Por eso cada caso de aqui afirma las dos mitades — que sale lo servido **y** que NO sale lo de
 * la otra respuesta—.
 */

function arnes() {
  // Un cliente por prueba: compartido, la respuesta de la primera se quedaria en la cache de la
  // segunda y las «dos respuestas distintas» serian la misma.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Hoja({ clave }: { readonly clave: ClaveDeHoja }) {
    return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave)} />;
  }
  return (clave: ClaveDeHoja) =>
    render(
      <QueryClientProvider client={cliente}>
        <Hoja clave={clave} />
      </QueryClientProvider>,
    );
}

/**
 * Lo que contesta una ruta con **204 y sin cuerpo** (#354).
 *
 * Un simbolo y no `null`: `null` es un cuerpo JSON valido —`"null"` con 200—, y lo que el backend
 * manda cuando no hay corrida del ejercicio no es eso, es un 204 sin una sola letra
 * (`PredialController`, #523). Son dos respuestas distintas y el cliente las trata distinto.
 */
const SIN_CUERPO = Symbol('204');

/** Sustituye `fetch` por una respuesta por ruta. Lo que no este declarado contesta 404. */
function contesta(porRuta: Readonly<Record<string, unknown>>) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      const ruta = Object.keys(porRuta).find((r) => url.includes(r));
      if (ruta === undefined) {
        return Promise.resolve(new Response('{}', { status: 404 }));
      }
      if (porRuta[ruta] === SIN_CUERPO) {
        return Promise.resolve(new Response(null, { status: 204 }));
      }
      return Promise.resolve(
        new Response(JSON.stringify(porRuta[ruta]), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

/** La forma que publica `GET /indicadores/recaudacion`, con las cifras que se le den. */
function recaudacion(
  ejercicio: number,
  cargado: string,
  recaudado: string,
  avance: string,
  tributo: { nombre: string; cargado: string; cobrado: string; pendiente: string; pct: number },
): IndicadorDeRecaudacion {
  const conFecha = (importe: string) => ({ importe, actualizadoA: '2026-09-16' });
  return {
    ejercicio,
    fechaCalculo: '2026-09-16',
    calculadoEn: '2026-09-16T05:00:00-05:00',
    cargado: conFecha(cargado),
    kpis: [
      {
        label: `Recaudado ${String(ejercicio)}`,
        value: 'lo redacta el servidor',
        note: '',
        importe: conFecha(recaudado),
      },
      { label: 'Avance de cobranza', value: avance, note: '', importe: null },
      { label: 'Cartera pendiente', value: '', note: '', importe: conFecha('1.00') },
    ],
    paneles: [
      {
        title: 'Recaudacion por tributo',
        note: '',
        rows: [
          {
            label: tributo.nombre,
            sub: '',
            value: 'lo redacta el servidor',
            pct: tributo.pct,
            avanceConocido: true,
            importe: conFecha(tributo.cobrado),
            cargado: conFecha(tributo.cargado),
            pendiente: conFecha(tributo.pendiente),
          },
          // La fila sin base: `pct` es 0 y `avanceConocido` falso. Es el par que separa el cero
          // medido del que no se pudo medir, y esta aqui para que ninguna prueba lo deje sin
          // ejercitar.
          {
            label: 'Alcabala',
            sub: 'sin cargos asentados en el ejercicio',
            value: '',
            pct: 0,
            avanceConocido: false,
            importe: conFecha('0.00'),
            cargado: conFecha('0.00'),
            pendiente: conFecha('0.00'),
          },
        ],
      },
      // El segundo bloque, que la pantalla NO dibuja: es por mes y su tabla es «por tributo».
      { title: 'Recaudacion por mes', note: '', rows: [] },
    ],
  };
}

/** La forma que publica `GET /rentas/predial/corridas/ultima`, recortada a lo que se usa. */
function corrida(observados: number): CorridaDelPredial {
  return {
    id: 1,
    ejercicio: '2026',
    // `TODOS`, que es un alcance que el backend contesta: `PADRON` no es ninguno (#357).
    alcance: 'TODOS',
    sector: null,
    simulacion: false,
    conjunto: 'V3',
    conjuntoId: 77,
    // ISO y sin hora, como la publica el backend (#389): el texto del artboard no llega nunca.
    fechaCalculo: '2026-01-28',
    determinados: 61350,
    montoEmitido: '9418204.60',
    derechoDeEmision: '4.50',
    observados,
    etapas: [],
  };
}

/** La forma que publica `GET /indicadores/trabajo-parado`. */
function trabajoParado(cuantos: number, importe: string | null): TrabajoParado {
  return {
    ejercicio: 2026,
    fechaCalculo: '2026-09-16',
    calculadoEn: '2026-09-16T05:00:00-05:00',
    frentes: [
      {
        frente: 'TRANSITO',
        modulo: 'Transito',
        queEstaParado: 'papeletas sin resolucion de multa emitida',
        porQueCuestaDinero: 'sin emitir no se pueden notificar ni cobrar, y prescriben',
        cuantos,
        importe: importe === null ? null : { importe, actualizadoA: '2026-09-16' },
      },
    ],
  };
}

const RECAUDACION = '/indicadores/recaudacion';
const PARADO = '/indicadores/trabajo-parado';
// Con el parametro, y es lo que se prueba: el doble contesta 404 a la ruta sin el, asi que una
// hoja que pidiera la ultima CORRIDA —simulaciones incluidas— saldria roja en vez de ensenar un
// ensayo bajo «Observados sin emision» (#357).
const CORRIDA = '/rentas/predial/corridas/ultima?simulacion=false';

describe('`ini-panel` — el avance del ejercicio', () => {
  it('ensena lo que llega de LAS DOS operaciones, campo a campo', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '23725394.80', '18424251.20', '77 %', {
        nombre: 'Impuesto predial',
        cargado: '9418204.60',
        cobrado: '8420118.40',
        pendiente: '998086.20',
        pct: 89,
      }),
      // Por encima del millar (#389): con 534 —lo de antes— `String` y `formatearEntero` escribian
      // lo mismo, y esta hoja podia decir «1204» donde `panel` dice «1,204».
      [CORRIDA]: corrida(1204),
    });
    const { container } = arnes()('ini-panel');

    await waitFor(() => {
      expect(screen.getByText('S/ 23,725,394.80')).toBeInTheDocument();
    });
    expect(screen.getByText('S/ 18,424,251.20')).toBeInTheDocument();
    expect(screen.getByText('77 %')).toBeInTheDocument();
    // «Observados sin emision» no sale del panel de recaudacion: sale de la ultima corrida, que
    // es la segunda operacion que esta hoja pide.
    expect(screen.getByText('1,204')).toBeInTheDocument();
    // Y el desplegable de ejercicio dice el de la respuesta, no el primero de su lista.
    expect(container.textContent).toContain('2026');
  });

  it('LA ROTURA: con otra respuesta ensena OTRA cosa (AC3)', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2025, '11111111.10', '22222222.20', '33 %', {
        nombre: 'Arbitrios municipales',
        cargado: '1.00',
        cobrado: '2.00',
        pendiente: '3.00',
        pct: 4,
      }),
      [CORRIDA]: corrida(777),
    });
    arnes()('ini-panel');

    await waitFor(() => {
      expect(screen.getByText('S/ 11,111,111.10')).toBeInTheDocument();
    });
    expect(screen.getByText('S/ 22,222,222.20')).toBeInTheDocument();
    expect(screen.getByText('33 %')).toBeInTheDocument();
    expect(screen.getByText('777')).toBeInTheDocument();
    // La otra mitad, que es la que distingue conectar de parecer conectado: lo de la PRIMERA
    // respuesta no puede seguir ahi. Una pantalla que dibujara cifras suyas pasaria la mitad de
    // arriba en cuanto el doble contestara lo que ella ya ensenaba.
    expect(screen.queryByText('S/ 23,725,394.80')).toBeNull();
    expect(screen.queryByText('1,204')).toBeNull();
  });

  it('«Contribuyentes activos» dice «no publicado», y NO se deduce de la corrida', async () => {
    contesta({ [RECAUDACION]: recaudacion(2026, '1.00', '2.00', '3 %', { nombre: 'x', cargado: '1.00', cobrado: '1.00', pendiente: '1.00', pct: 1 }), [CORRIDA]: corrida(534) });
    arnes()('ini-panel');

    await waitFor(() => {
      expect(screen.getByText('534')).toBeInTheDocument();
    });
    // Ninguna de las dos operaciones publica cuantos contribuyentes estan activos. Deducirlo de
    // la corrida daria un numero indistinguible de uno real.
    expect(screen.getAllByText('no publicado').length).toBeGreaterThan(0);
  });
});

/**
 * **Sin corrida del ejercicio, la hoja dice «todavia no» en su campo y el resto sigue en pie**
 * (#354).
 *
 * Es el estado de cualquier municipalidad entre el 1 de enero y su primera corrida del ano, o
 * recien implantada: `/indicadores/recaudacion` contesta 200 y `/rentas/predial/corridas/ultima`
 * contesta **204**. Hasta #354 el conector pedia la corrida con `pedirUno`, que con un 204 devuelve
 * un `null` que su tipo no declara, y `repartir` leia `corrida.observados` sobre el: un `TypeError`
 * en el render que se llevaba la aplicacion entera — barra, arbol y pie — por un campo de seis.
 *
 * La muestra **no** es la vacia: la recaudacion trae cifras de verdad, porque lo que se afirma es
 * que el hueco de la corrida es de UN campo y no de la pantalla. Una hoja que dijera «sin datos»
 * de arriba abajo pasaria la mitad de «no revienta» y esconderia cinco cifras que si llegaron.
 */
describe('`ini-panel` sin corrida del ejercicio (#354)', () => {
  it('la corrida en 204: las cinco de la recaudacion salen, y «Observados» dice «sin corrida»', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '23725394.80', '18424251.20', '77 %', {
        nombre: 'Impuesto predial',
        cargado: '9418204.60',
        cobrado: '8420118.40',
        pendiente: '998086.20',
        pct: 89,
      }),
      [CORRIDA]: SIN_CUERPO,
    });
    arnes()('ini-panel');

    await waitFor(() => {
      expect(screen.getByText('S/ 23,725,394.80')).toBeInTheDocument();
    });
    expect(screen.getByText('S/ 18,424,251.20')).toBeInTheDocument();
    expect(screen.getByText('77 %')).toBeInTheDocument();
    // La palabra de «todavia no se ha corrido», en el campo que la corrida llenaria. No es
    // «no publicado» —la operacion SI lo publica— ni un cero: cero observados es un resultado, y
    // aqui no hay resultado todavia.
    expect(screen.getByText(SIN_EMISION_DEL_EJERCICIO)).toBeInTheDocument();
    expect(screen.queryByText('0')).toBeNull();
  });
});

/**
 * **Un ensayo no pasa por emision en «Observados sin emision»** (#357).
 *
 * El escenario del issue: la emision del padron dejo 534 observados, y despues se simulo un sector
 * con 3. La ruta ya pide `?simulacion=false`, asi que la simulacion no deberia llegar; esto es la
 * red de seguridad del conector para el dia que llegue. La muestra es la simulacion —la uniforme
 * de arriba lleva `simulacion: false` y dejaria en verde a un conector que no mira el campo—.
 */
describe('`ini-panel` con una simulacion (#357)', () => {
  it('los observados de un ENSAYO no se escriben como los del ejercicio', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '23725394.80', '18424251.20', '77 %', {
        nombre: 'Impuesto predial',
        cargado: '9418204.60',
        cobrado: '8420118.40',
        pendiente: '998086.20',
        pct: 89,
      }),
      [CORRIDA]: { ...corrida(3), alcance: 'SECTOR', sector: '04', simulacion: true },
    });
    arnes()('ini-panel');

    await waitFor(() => {
      expect(screen.getByText('S/ 23,725,394.80')).toBeInTheDocument();
    });
    expect(screen.queryByText('3')).toBeNull();
    expect(screen.getByText(LA_ULTIMA_FUE_UNA_SIMULACION)).toBeInTheDocument();
  });

  it('los de una emision de UN SECTOR tampoco: no son los del ejercicio', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '23725394.80', '18424251.20', '77 %', {
        nombre: 'Impuesto predial',
        cargado: '9418204.60',
        cobrado: '8420118.40',
        pendiente: '998086.20',
        pct: 89,
      }),
      [CORRIDA]: { ...corrida(3), alcance: 'SECTOR', sector: '04' },
    });
    arnes()('ini-panel');

    await waitFor(() => {
      expect(screen.getByText('S/ 23,725,394.80')).toBeInTheDocument();
    });
    expect(screen.queryByText('3')).toBeNull();
    expect(screen.getByText(DE_UNA_PARTE_DEL_PADRON)).toBeInTheDocument();
  });
});

describe('`ini-flujo` — el cuadre por tributo', () => {
  it('sus cinco columnas salen de la fila servida, y ninguna se resta', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '23725394.80', '18424251.20', '77 %', {
        nombre: 'Impuesto predial',
        cargado: '9418204.60',
        cobrado: '8420118.40',
        pendiente: '998086.20',
        pct: 89,
      }),
    });
    arnes()('ini-flujo');

    await waitFor(() => {
      expect(screen.getByText('Impuesto predial')).toBeInTheDocument();
    });
    // Sin el «S/» delante: el rotulo de la columna ya lo dice, como en el artboard.
    expect(screen.getByText('9,418,204.60')).toBeInTheDocument();
    expect(screen.getByText('8,420,118.40')).toBeInTheDocument();
    // El saldo es `pendiente`, un campo que la operacion publica. Restar los otros dos daria
    // 998,086.20 tambien en este caso, y por eso la prueba de que no se resta es la de abajo.
    expect(screen.getByText('998,086.20')).toBeInTheDocument();
    expect(screen.getByText('89 %')).toBeInTheDocument();
  });

  it('el saldo es el PUBLICADO, aunque no cuadre con emitido menos recaudado', async () => {
    // Es la unica forma de demostrar que no se resta: se sirve un `pendiente` que no es la resta.
    // Un conector que calculara «emitido − recaudado» ensenaria 100.00 y esta prueba lo veria.
    contesta({
      [RECAUDACION]: recaudacion(2026, '1.00', '1.00', '1 %', {
        nombre: 'Impuesto predial',
        cargado: '500.00',
        cobrado: '400.00',
        pendiente: '7777.77',
        pct: 80,
      }),
    });
    arnes()('ini-flujo');

    await waitFor(() => {
      expect(screen.getByText('7,777.77')).toBeInTheDocument();
    });
    expect(screen.queryByText('100.00')).toBeNull();
  });

  it('una fila sin base dice «sin medir», y NUNCA «0 %»', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '1.00', '1.00', '1 %', {
        nombre: 'Impuesto predial',
        cargado: '500.00',
        cobrado: '400.00',
        pendiente: '100.00',
        pct: 80,
      }),
    });
    arnes()('ini-flujo');

    await waitFor(() => {
      expect(screen.getByText('Alcabala')).toBeInTheDocument();
    });
    // `pct` llega como 0 porque una barra sin numero no se puede pintar, y `avanceConocido` dice
    // que ese 0 no se midio. «0 %» se leeria como «no se ha cobrado nada» de un tributo que ni
    // siquiera tiene cargos asentados.
    expect(screen.getByText('sin medir')).toBeInTheDocument();
    expect(screen.queryByText('0 %')).toBeNull();
  });

  it('y el bloque «por mes» NO se cuela en una tabla que se titula «por tributo»', async () => {
    contesta({
      [RECAUDACION]: recaudacion(2026, '1.00', '1.00', '1 %', {
        nombre: 'Impuesto predial',
        cargado: '500.00',
        cobrado: '400.00',
        pendiente: '100.00',
        pct: 80,
      }),
    });
    arnes()('ini-flujo');

    await waitFor(() => {
      expect(screen.getByText('Impuesto predial')).toBeInTheDocument();
    });
    // Dos filas —el tributo y la que no tiene base—, y ni una del segundo bloque.
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });
});

describe('`ini-parado` — los frentes abiertos', () => {
  it('sus cinco columnas salen del frente servido', async () => {
    contesta({ [PARADO]: trabajoParado(1842, '788976.00') });
    arnes()('ini-parado');

    await waitFor(() => {
      expect(screen.getByText('Transito')).toBeInTheDocument();
    });
    expect(screen.getByText('papeletas sin resolucion de multa emitida')).toBeInTheDocument();
    // Agrupado, como cualquier otro conteo del arbol (#389). Hasta #389 esta prueba FIJABA «1842»,
    // y el javadoc del conector lo justificaba con que `panel` tampoco agrupaba sus etapas.
    expect(screen.getByText('1,842')).toBeInTheDocument();
    expect(screen.getByText('788,976.00')).toBeInTheDocument();
    expect(
      screen.getByText('sin emitir no se pueden notificar ni cobrar, y prescriben'),
    ).toBeInTheDocument();
  });

  it('LA ROTURA: con otra respuesta ensena OTRA cosa (AC3)', async () => {
    contesta({ [PARADO]: trabajoParado(7, '12.34') });
    arnes()('ini-parado');

    await waitFor(() => {
      expect(screen.getByText('7')).toBeInTheDocument();
    });
    expect(screen.getByText('12.34')).toBeInTheDocument();
    expect(screen.queryByText('1,842')).toBeNull();
    expect(screen.queryByText('788,976.00')).toBeNull();
  });

  it('un frente que no se puede cifrar dice «sin cifrar», y NUNCA «0.00»', async () => {
    contesta({ [PARADO]: trabajoParado(42, null) });
    arnes()('ini-parado');

    await waitFor(() => {
      expect(screen.getByText('42')).toBeInTheDocument();
    });
    // El backend manda `null` a proposito para que esto se pueda distinguir de un `"0.00"` de
    // verdad, que es lo que sale cuando el frente cifrado no tiene ni una fila.
    expect(screen.getByText('sin cifrar')).toBeInTheDocument();
    expect(screen.queryByText('0.00')).toBeNull();
  });
});

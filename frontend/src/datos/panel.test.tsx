import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { pantallaDe } from '../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../pantallas/PantallaDeRentas.tsx';
import type { CorridaDelPredial } from './lecturas.ts';
import { useDatosDeLaHoja } from './useDatosDeLaHoja.ts';

/**
 * **`panel` ensena los agregados que LLEGARON, y no los suyos ni los de su tabla** (#271).
 *
 * <h2>Por que se monta la pantalla y no se llama al conector</h2>
 *
 * Porque comparar el `Map` que `repartir()` devuelve comprueba que el conector devuelve lo que el
 * conector devuelve. Lo que hay que saber es que **eso llega al DOM**: entre el reparto y la
 * pantalla estan la coordenada de cada campo y el interprete, y una coordenada corrida de uno
 * pinta el monto bajo «Observados» sin que el `Map` lo note.
 *
 * <h2>La muestra separa TRES implementaciones, y por eso no es uniforme</h2>
 *
 * El artboard dibuja `61,350` cuentas y `S/ 9,418,204.60`, y la ultima etapa de la corrida trae
 * **justo esos** 61 350 registros: con una respuesta que los repitiera, «lee el campo publicado»,
 * «lee la segunda fila de la tabla» y «dibuja lo de su definicion» pasarian las tres. Asi que la
 * corrida de aqui trae:
 *
 *   · `determinados` **58 412**, que no es el registro de ninguna de sus etapas;
 *   · `montoEmitido` **8 772 431,05**, que no es el monto de ninguna de ellas;
 *   · y unas etapas cuyas cifras —61 350 y 9 418 204,60— son **las del artboard**, puestas ahi a
 *     proposito: si la pantalla las ensena arriba, es que las saco de la tabla.
 *
 * Y se contesta dos veces con corridas distintas, que es la diferencia entre conectar y **parecer**
 * conectado: una pantalla que dibuja cifras propias se ve igual que una conectada mientras el doble
 * conteste lo que ella ya dibujaba.
 *
 * <h2>Y el sexto campo desde #312, con sus DOS corridas</h2>
 *
 * «Derecho de emision» era el ultimo hueco. `V23` le dio columna a `corrida_predial` y la operacion
 * lo publica, asi que aqui se monta con **dos** respuestas: una que lo sello —`3.70`, que no es el
 * `S/ 4.50` del artboard, para que ensenarlo no pueda salir de la definicion— y otra **anterior al
 * sello**, que lo trae nulo. Sin la segunda la rama del nulo no correria nunca y un `S/ 0.00`
 * saldria en verde — y un cero afirma que no se cobro derecho de emision, que es falso: se cobro,
 * y esta sumado dentro de `montoEmitido`.
 */

/** Lo que el artboard dibuja en `panel`, y que por tanto no puede salir de una respuesta. */
const DEL_ARTBOARD = ['61,350', 'S/ 9,418,204.60', 'S/ 4.50'] as const;

function arnes() {
  // Un cliente por prueba: compartido, la respuesta de la primera se quedaria en la cache de la
  // segunda y las «dos corridas distintas» serian la misma.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Hoja() {
    return <PantallaDeRentas definicion={pantallaDe('panel')} datos={useDatosDeLaHoja('panel')} />;
  }
  return () =>
    render(
      <QueryClientProvider client={cliente}>
        <Hoja />
      </QueryClientProvider>,
    );
}

/** Sustituye `fetch` por una respuesta unica: esta hoja pide una sola operacion. */
function contesta(corrida: CorridaDelPredial) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response(JSON.stringify(corrida), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    ),
  );
}

/** La forma que publica `GET /rentas/predial/corridas/ultima`, con las cifras que se le den. */
function corrida(cambios: Partial<CorridaDelPredial> = {}): CorridaDelPredial {
  return {
    id: 20,
    ejercicio: '2026',
    alcance: 'TODOS',
    sector: null,
    simulacion: false,
    conjunto: '2026 v1',
    conjuntoId: 77,
    fechaCalculo: '28/01/2026 02:14',
    determinados: 58_412,
    montoEmitido: '8772431.05',
    // El derecho que ESTA corrida sello (#312). No es el `S/ 4.50` del artboard a proposito: si la
    // pantalla lo ensenara, no se sabria si lo saco de la respuesta o de su propia definicion.
    derechoDeEmision: '3.70',
    observados: 1204,
    // Las cifras del ARTBOARD, en la tabla. Si alguna sube a un campo, es que se dedujo de aqui.
    etapas: [
      { etapa: 'Padron leido', registros: 62_418, monto: '', observados: 0, estado: 'OK' },
      {
        etapa: 'Determinados',
        registros: 61_350,
        monto: '9418204.60',
        observados: 534,
        estado: 'CON OBSERVACIONES',
      },
    ],
    ...cambios,
  };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`panel` — el estado de la emision', () => {
  it('«Cuentas emitidas» y «Monto determinado» salen de la respuesta', async () => {
    contesta(corrida());
    const { container } = arnes()();

    await waitFor(() => {
      expect(screen.getByText('58,412')).toBeInTheDocument();
    });
    expect(screen.getByText('S/ 8,772,431.05')).toBeInTheDocument();
    // Y lo que ya salia sigue saliendo: la fecha y los observados.
    expect(screen.getByText('28/01/2026 02:14')).toBeInTheDocument();
    expect(screen.getByText('1,204')).toBeInTheDocument();
    // Ninguna cifra del artboard sube a un campo. `61,350` esta EN LA TABLA de esta misma
    // respuesta —sin separador, como la celda la trae—, asi que lo que se comprueba es que no
    // aparezca la forma de campo, que es la que lleva millares.
    for (const suya of DEL_ARTBOARD) {
      expect(screen.queryByText(suya)).toBeNull();
    }
    expect(container.textContent).not.toContain('S/ 9,418,204.60');
  });

  it('LA ROTURA: con otra corrida ensena OTRA cosa', async () => {
    contesta(
      corrida({
        determinados: 7,
        montoEmitido: '412.80',
        observados: 39,
        fechaCalculo: '03/02/2027 18:40',
      }),
    );
    arnes()();

    await waitFor(() => {
      expect(screen.getByText('7')).toBeInTheDocument();
    });
    expect(screen.getByText('S/ 412.80')).toBeInTheDocument();
    expect(screen.getByText('39')).toBeInTheDocument();
    expect(screen.getByText('03/02/2027 18:40')).toBeInTheDocument();
    // Y nada de la primera corrida sobrevive.
    expect(screen.queryByText('58,412')).toBeNull();
    expect(screen.queryByText('S/ 8,772,431.05')).toBeNull();
  });

  /**
   * **«Derecho de emision» ensena la cifra que la corrida SELLO** (#312, D-02b).
   *
   * <h2>Que decia esta prueba antes</h2>
   *
   * Decia que ahi no aparecia **ninguna** cifra, y estaba bien dicho mientras `corrida_predial` no
   * tuviera columna: la corrida lo aplicaba —dentro de `montoEmitido`— y no lo guardaba, asi que
   * el unico numero disponible era el del conjunto vigente hoy, que no tiene por que ser aquel.
   *
   * `V23` sello la columna. Ahora la cifra sale, y lo que hay que medir es **cual**: la de la
   * respuesta —`S/ 3.70`— y no la del artboard, que es `S/ 4.50` y esta en `DEL_ARTBOARD`.
   */
  it('«Derecho de emision» ensena el que llego, y no el del artboard', async () => {
    contesta(corrida());
    const { container } = arnes()();

    await waitFor(() => {
      expect(screen.getByText('58,412')).toBeInTheDocument();
    });
    expect(screen.getByText('S/ 3.70')).toBeInTheDocument();
    expect(container.textContent).not.toContain('S/ 4.50');
    // Y no se cuela el importe de la corrida en su lugar: el monto sale UNA vez, en su campo.
    expect(screen.getAllByText('S/ 8,772,431.05')).toHaveLength(1);
  });

  /**
   * **Una corrida ANTERIOR al sello dice la palabra que le toca, y no un cero** (#312).
   *
   * <h2>Esto es lo que una muestra uniforme no podria decir</h2>
   *
   * Con solo la corrida sellada, un conector que escribiera `S/ 0.00` —o que siguiera diciendo «no
   * publicado»— pasaria en verde. Las dos afirmaciones son falsas: el derecho de aquella corrida
   * **se cobro** —esta sumado dentro de `montoEmitido`— y la operacion **si** publica el campo.
   *
   * <h2>Y aqui la asercion SI muerde, que antes de #312 no podia</h2>
   *
   * «No publicado» es lo que `useDatosDeLaHoja` pone en todo campo que el reparto no llena, asi
   * que afirmarlo pasaria con la declaracion del conector y sin ella —medido en #271—. Esta
   * palabra no la pone nadie mas: solo puede haber llegado del conector.
   */
  it('con una corrida anterior al sello, el campo dice «no consta en la corrida»', async () => {
    contesta(corrida({ conjuntoId: null, derechoDeEmision: null }));
    const { container } = arnes()();

    await waitFor(() => {
      expect(screen.getByText('58,412')).toBeInTheDocument();
    });
    expect(screen.getByText('no consta en la corrida')).toBeInTheDocument();
    // `queryByText` y NO `container.textContent`: la frase de PANTALLA cita «no publicado» dentro
    // de su explicacion —«Los campos marcados «no publicado» los pide y la operacion que los sirve
    // no los trae»— y esa frase sale siempre. Lo que aqui no puede haber es un CAMPO que diga esa
    // palabra, que es lo que `queryByText` busca: un nodo cuyo texto entero sea ese.
    expect(screen.queryByText('no publicado')).toBeNull();
    expect(container.textContent).not.toContain('S/ 0.00');
    expect(container.textContent).not.toContain('S/ 3.70');
  });
});

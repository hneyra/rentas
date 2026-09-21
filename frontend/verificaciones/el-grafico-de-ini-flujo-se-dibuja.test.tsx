import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { piezasSinRegistrar, type DefinicionDePantalla, type PiezaDeLaPantalla } from '@kamayuk/ui';
import { render, screen, waitFor } from '@testing-library/react';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { useDatosDeLaHoja } from '../src/datos/useDatosDeLaHoja.ts';
import { CLAVES_DE_HOJA, hojaDe, type ClaveDeHoja } from '../src/pantallas/arbol.ts';
import { PANTALLAS, pantallaDe } from '../src/pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../src/pantallas/PantallaDeRentas.tsx';
import { PIEZAS_DE_RENTAS } from '../src/piezas/index.ts';
import { GRAFICO_DE_RECAUDACION } from '../src/piezas/serieDeAvance.ts';
import type { IndicadorDeRecaudacion } from '../src/datos/lecturas.ts';

/**
 * **El grafico que el artboard pide para `ini-flujo` se dibuja, y es de barras horizontales**
 * (#288, AC-1, AC-2 y AC-5).
 *
 * <h2>Que se mide, y por que montando</h2>
 *
 * El artboard declara para esa hoja `['Chart', 'Barras horizontales; recharts, que shadcn
 * envuelve']` y hasta #288 la serie se dibujaba **solo** como tabla. Lo que hay que saber no es que
 * la definicion lleve una pieza —eso lo dice el compilador— sino que **la pieza llega al DOM con lo
 * que llego por la red**: entre la respuesta y la barra estan el conector, el nombre de cada dato
 * en `nombrados` y el punto de extension del interprete, y cualquiera de los tres puede estar mal
 * sin que nada se ponga rojo. Una clave que nadie registra dibuja un aviso, no un grafico; y un
 * nombre cambiado en una sola punta dibuja un grafico vacio.
 *
 * <h2>Y por que hace falta un `ResizeObserver` de mentira</h2>
 *
 * Porque el `ResponsiveContainer` de recharts mide su caja con `ResizeObserver` y **jsdom no lo
 * tiene**: sin el, recharts no revienta —comprueba `typeof ResizeObserver !== 'undefined'`— y
 * simplemente no dibuja nada, porque su ancho se queda en el `-1` inicial. O sea que sin este doble
 * la prueba no mediria «el grafico no sale» sino «jsdom no mide cajas».
 *
 * El doble va **en este archivo y no en `vitest.setup.ts`**: las otras 62 pruebas se montan hoy sin
 * ResizeObserver, y darselo a todas cambiaria lo que miden sin que nadie lo pidiera. En particular
 * `todo-el-texto-se-traduce` monta las cuarenta pantallas SIN datos, donde el grafico no tiene
 * serie que dibujar y no llega a pedir su caja.
 *
 * **Y lo que NO se dobla es `getBoundingClientRect`**, aunque recharts lo llame: medido: doblandolo
 * para que toda caja midiera 720 px, recharts creia que cada rotulo del eje media eso y **descartaba
 * los ticks por solapamiento** —el eje de las categorias salia vacio y el de los numeros con una
 * sola marca—. Sin doblarlo, jsdom contesta cero, no hay solapamiento posible y se dibujan los
 * cinco. Un doble de mas midio menos que ninguno.
 */

/** Lo ancho y lo alto que se le dice a recharts que tiene su caja. */
const CAJA = { width: 720, height: 240 };

/** El `ResizeObserver` de antes, para devolverlo: `globalThis` lo comparten los archivos. */
const ANTES = Reflect.get(globalThis, 'ResizeObserver') as unknown;

beforeAll(() => {
  class ResizeObserverDeMentira {
    private readonly avisar: ResizeObserverCallback;
    constructor(avisar: ResizeObserverCallback) {
      this.avisar = avisar;
    }
    observe(objetivo: Element) {
      // Se avisa en cuanto se observa, que es lo que un navegador hace: la primera entrega llega
      // sin que nada cambie de tamano. `contentRect` es lo unico que recharts lee.
      this.avisar(
        [{ target: objetivo, contentRect: CAJA } as unknown as ResizeObserverEntry],
        this as unknown as ResizeObserver,
      );
    }
    unobserve() {}
    disconnect() {}
  }
  Reflect.set(globalThis, 'ResizeObserver', ResizeObserverDeMentira);
});

afterAll(() => {
  Reflect.set(globalThis, 'ResizeObserver', ANTES);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/** La respuesta de `GET /indicadores/recaudacion`, con los tributos que se le den. */
function recaudacion(
  tributos: readonly { readonly nombre: string; readonly pct: number; readonly medido: boolean }[],
): IndicadorDeRecaudacion {
  const conFecha = (importe: string) => ({ importe, actualizadoA: '2026-09-16' });
  return {
    ejercicio: 2026,
    fechaCalculo: '2026-09-16',
    calculadoEn: '2026-09-16T05:00:00-05:00',
    cargado: conFecha('23725394.80'),
    kpis: [{ label: 'Recaudado 2026', value: '', note: '', importe: conFecha('18424251.20') }],
    paneles: [
      {
        title: 'Recaudacion por tributo',
        note: '',
        rows: tributos.map((tributo) => ({
          label: tributo.nombre,
          sub: '',
          value: '',
          pct: tributo.pct,
          avanceConocido: tributo.medido,
          importe: conFecha('8420118.40'),
          cargado: conFecha('9418204.60'),
          pendiente: conFecha('998086.20'),
        })),
      },
      { title: 'Recaudacion por mes', note: '', rows: [] },
    ],
  };
}

/** Sustituye `fetch` por esa respuesta. Lo que no sea el panel de recaudacion contesta 404. */
function contesta(indicador: IndicadorDeRecaudacion) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) =>
      Promise.resolve(
        String(entrada).includes('/indicadores/recaudacion')
          ? new Response(JSON.stringify(indicador), {
              status: 200,
              headers: { 'content-type': 'application/json' },
            })
          : new Response('{}', { status: 404 }),
      ),
    ),
  );
}

function monta(clave: ClaveDeHoja) {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Hoja() {
    return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave)} />;
  }
  return render(
    <QueryClientProvider client={cliente}>
      <Hoja />
    </QueryClientProvider>,
  );
}

/** Las barras que recharts dibujo: los `<rect>` de la serie, sin las pistas de detras. */
function barras(container: HTMLElement): readonly Element[] {
  return [...container.querySelectorAll('.recharts-bar-rectangle rect')];
}

/**
 * Los rotulos de un eje.
 *
 * Van en su propio grupo y **no dentro de `.recharts-yAxis`**: recharts dibuja las lineas de los
 * ejes en una capa y sus textos en otra, mas arriba, para que ninguna barra los tape. Buscarlos
 * dentro del eje devuelve la cadena vacia, que es lo que hace pasar una prueba mal escrita.
 */
function rotulosDelEje(container: HTMLElement, eje: 'x' | 'y'): string {
  return container.querySelector(`.recharts-${eje}Axis-tick-labels`)?.textContent ?? '';
}

/** El grafico, por la marca que lleva su tarjeta. */
function elGrafico(): HTMLElement | null {
  return document.querySelector(`[data-grafico="${GRAFICO_DE_RECAUDACION}"]`);
}

/**
 * Las claves que una definicion nombra y el registro no trae.
 *
 * **El `as` es un hallazgo, no una comodidad**, y va a `kamayuk-lib`#25: `piezasSinRegistrar`
 * declara su segundo parametro como `Record<string, ComponentType<never>>` y eso **no admite un
 * `PiezasDelConsumidor`**, que es el tipo del registro que la propia libreria publica para pasarlo a
 * `<Pantalla piezas>`. O sea que la unica funcion que existe para vigilar el punto de extension no
 * acepta el dato que el punto de extension usa: `ComponentType<never>` no es un supertipo de
 * `ComponentType<PropsDeUnaPiezaDelConsumidor>` —`defaultProps` es invariante— y el compilador lo
 * dice con ocho lineas sobre `defaultProps`. Se estrecha aqui, en un solo sitio y con su motivo
 * escrito, en vez de repetir el `as` en cada llamada.
 */
function elRegistroNoTrae(definicion: DefinicionDePantalla<PiezaDeLaPantalla>): readonly string[] {
  return piezasSinRegistrar(definicion, PIEZAS_DE_RENTAS as Parameters<typeof piezasSinRegistrar>[1]);
}

describe('AC-1 — la pieza se engancha por `delConsumidor` y su clave esta registrada', () => {
  it('`ini-flujo` declara la pieza, sin `ajustes`', () => {
    const piezas = PANTALLAS['ini-flujo'].bloques.filter((pieza) => pieza.tipo === 'delConsumidor');
    expect(piezas, 'la definicion de `ini-flujo` no declara ninguna pieza del consumidor').toHaveLength(1);
    // La forma entera, y no solo la clave: el punto de extension NO lleva `ajustes` a proposito
    // —«un dato sin tipo es un contrato que ningun compilador lee»— y una definicion que se los
    // inventara compilaria igual, porque `ComunDeUnaPieza` no prohibe claves de mas.
    expect(piezas[0]).toStrictEqual({ tipo: 'delConsumidor', clave: GRAFICO_DE_RECAUDACION });
  });

  it('y NINGUNA de las cuarenta nombra una clave que el registro no traiga', () => {
    // Con la `piezasSinRegistrar` que la libreria publica justo para esto. Sin ella habria que
    // abrir la hoja: una clave sin componente dibuja un aviso visible, y ese aviso solo lo ve
    // quien entra en esa pantalla.
    const huerfanas = CLAVES_DE_HOJA.flatMap((clave) =>
      elRegistroNoTrae(pantallaDe(clave)).map(
        (pieza) => `  «${clave}» nombra la pieza «${pieza}» y nadie la registro`,
      ),
    );
    expect(
      huerfanas,
      'Hay una pieza del consumidor sin componente:\n' +
        `${huerfanas.join('\n')}\n\n` +
        '  El interprete dibuja un aviso en su sitio —nunca un hueco en blanco—, pero ese aviso solo\n' +
        '  se ve en la hoja que alguien abre. Se registra en `src/piezas/index.ts`.',
    ).toEqual([]);
  });

  it('EL CENTINELA: y el registro puede FALTAR, o lo de arriba no mide nada', () => {
    // Sin esta mitad, `piezasSinRegistrar` podria devolver siempre la lista vacia —porque dejara de
    // reconocer la pieza, o porque la definicion perdiera la suya— y la guarda de arriba pasaria en
    // verde sobre una pantalla sin grafico.
    expect(
      piezasSinRegistrar(pantallaDe('ini-flujo') as DefinicionDePantalla<PiezaDeLaPantalla>, {}),
      'con el registro vacio, `ini-flujo` tendria que decir que le falta su pieza',
    ).toEqual([GRAFICO_DE_RECAUDACION]);
  });
});

describe('AC-2 — son barras HORIZONTALES, y salen de lo que llego', () => {
  it('una barra por tributo medido, con el tributo en el eje de las categorias', async () => {
    contesta(
      recaudacion([
        { nombre: 'Impuesto predial', pct: 89, medido: true },
        { nombre: 'Arbitrios municipales', pct: 41, medido: true },
      ]),
    );
    const { container } = monta('ini-flujo');

    await waitFor(() => {
      expect(barras(container)).toHaveLength(2);
    });
    // Horizontal, y no «horizontal segun quien lo mire»: la categoria va en el eje Y —que es lo que
    // `layout="vertical"` significa en recharts— y el numero en el X. Con el grafico vertical, los
    // tributos saldrian en el eje de abajo y los porcentajes en el de la izquierda.
    expect(rotulosDelEje(container, 'y')).toContain('Impuesto predial');
    expect(rotulosDelEje(container, 'y')).toContain('Arbitrios municipales');
    expect(rotulosDelEje(container, 'x')).toBe('0 %25 %50 %75 %100 %');
    expect(rotulosDelEje(container, 'x')).not.toContain('Impuesto predial');

    // Y las barras miden lo que llego: 89 es mas larga que 41, y las dos empiezan en el mismo sitio.
    const anchos = barras(container).map((rect) => Number(rect.getAttribute('width')));
    expect(anchos[0]).toBeGreaterThan(anchos[1] ?? 0);
    expect(new Set(barras(container).map((rect) => rect.getAttribute('x'))).size).toBe(1);
  });

  it('LA ROTURA: con otra respuesta dibuja OTRA cosa', async () => {
    contesta(recaudacion([{ nombre: 'Patrimonio vehicular', pct: 12, medido: true }]));
    const { container } = monta('ini-flujo');

    await waitFor(() => {
      expect(barras(container)).toHaveLength(1);
    });
    expect(rotulosDelEje(container, 'y')).toContain('Patrimonio vehicular');
    // La otra mitad, que es la que distingue dibujar el dato de dibujar una figura: lo de la
    // primera respuesta no puede seguir ahi.
    expect(rotulosDelEje(container, 'y')).not.toContain('Impuesto predial');
    expect(elGrafico()?.textContent).toContain('12 %');
  });

  it('un tributo SIN avance medido no dibuja barra, y se dice cuantos son', async () => {
    contesta(
      recaudacion([
        { nombre: 'Impuesto predial', pct: 89, medido: true },
        // `pct: 0` con `avanceConocido: false` es el par con que la operacion separa el cero medido
        // del que no se pudo medir. Dibujarlo al cero diria «no se ha cobrado nada» de un tributo
        // que ni siquiera tiene cargos asentados.
        { nombre: 'Alcabala', pct: 0, medido: false },
      ]),
    );
    const { container } = monta('ini-flujo');

    await waitFor(() => {
      expect(barras(container)).toHaveLength(1);
    });
    expect(rotulosDelEje(container, 'y')).not.toContain('Alcabala');
    expect(screen.getByText(/no dibuja barra/)).toBeInTheDocument();
    // Y en el lienzo no aparece ningun «0 %» que no sea la marca del eje: la barra que no se pudo
    // medir no lleva cifra en la punta.
    expect(container.querySelector('.recharts-label-list')?.textContent).toBe('89 %');
  });

  it('sin serie no se dibuja un lienzo en blanco: se dice la palabra del hueco', () => {
    // La hoja montada sin conector, que es como la monta la guarda de traduccion: `datos` trae solo
    // su `ausencia`. Un grafico vacio se leeria como «no hay ni un tributo con movimiento».
    render(
      <PantallaDeRentas
        definicion={pantallaDe('ini-flujo')}
        datos={{ ausencia: { enElCampo: 'sin conectar', explicacion: '', tono: 'info' } }}
      />,
    );
    expect(elGrafico()?.textContent).toContain('sin conectar');
    expect(document.querySelector('.recharts-bar-rectangle')).toBeNull();
  });
});

describe('AC-5 — la tabla «Cuadre por tributo» NO se quita', () => {
  it('el grafico y la tabla salen los dos, y de la misma respuesta', async () => {
    contesta(recaudacion([{ nombre: 'Impuesto predial', pct: 89, medido: true }]));
    const { container } = monta('ini-flujo');

    await waitFor(() => {
      expect(barras(container)).toHaveLength(1);
    });
    // Las cifras exactas, que son lo que un grafico no dice: emitido, recaudado y saldo.
    expect(screen.getByText('9,418,204.60')).toBeInTheDocument();
    expect(screen.getByText('8,420,118.40')).toBeInTheDocument();
    expect(screen.getByText('998,086.20')).toBeInTheDocument();
    expect(screen.getByText('Cuadre por tributo')).toBeInTheDocument();
    // Y las dos cosas son DOS piezas: las cifras estan fuera del grafico y los rotulos del eje,
    // fuera de la tabla. Dibujar la tabla dentro del grafico —o al reves— pasaria lo de arriba.
    expect(elGrafico()?.textContent).not.toContain('Cuadre por tributo');
    expect(elGrafico()?.textContent).not.toContain('9,418,204.60');
  });

  it('y el grafico va DESPUES del bloque, que es lo que deja sus datos en su sitio', () => {
    // El interprete reparte por indice de pieza: `filas` al 0 y los campos al `0|0`. Con el grafico
    // delante, el bloque pasaria al 1 y el conector seguiria repartiendo al 0 — la tabla se
    // quedaria vacia y el desplegable de ejercicio sin rellenar, sin un solo error.
    expect(pantallaDe('ini-flujo').bloques.map((pieza) => pieza.tipo)).toEqual([
      undefined,
      'delConsumidor',
    ]);
    expect(hojaDe('ini-flujo').piezasDeclaradas.map((pieza) => pieza.pieza)).toEqual(['Chart']);
  });
});

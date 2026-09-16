import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada } from '@kamayuk/ui';
import {
  CONSTANCIA_NEGADA,
  FICHA,
  OTRA_FICHA,
  SIN_CAMPANIA,
} from './conectores/consultasDeMuestra.ts';
import { useDatosDeLaHoja } from './useDatosDeLaHoja.ts';

/**
 * **Los cuatro estados de una pantalla que pide** (#97).
 *
 * Se prueban con `fetch` sustituido y no con un doble del gancho, a proposito: lo que puede
 * fallar aqui es como se traduce **una respuesta de verdad** —o su ausencia— a lo que la pantalla
 * ensena, y un doble del gancho se saltaria justo esa traduccion.
 */

function arnes() {
  // Un cliente por prueba: compartido, la respuesta de una se quedaria en la cache de la
  // siguiente y el estado «cargando» no se veria nunca.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
}

/** Sustituye `fetch` por una respuesta fija. */
function contesta(cuerpo: unknown, estado = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response(JSON.stringify(cuerpo), {
          status: estado,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    ),
  );
}

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
  ],
};

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Sustituye `fetch` por un doble que contesta segun la ruta pedida. Devuelve lo que se pidio. */
function contestaSegunLaRuta(porRuta: Readonly<Record<string, unknown>>): readonly string[] {
  const pedidas: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      pedidas.push(url);
      const clave = Object.keys(porRuta).find((trozo) => url.includes(trozo));
      return Promise.resolve(
        new Response(JSON.stringify(clave === undefined ? {} : porRuta[clave]), {
          status: clave === undefined ? 404 : 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }),
  );
  return pedidas;
}

describe('una pantalla SIN conector no toca la red', () => {
  it('no pide nada, y dice por que no hay dato', () => {
    const pedir = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', pedir);

    // `fis-panel` y no `ini-panel`: desde #167 las tres hojas de Inicio SI tienen conector, y una
    // hoja conectada no sirve de ejemplo de lo que hace una que no lo esta.
    const { result } = renderHook(() => useDatosDeLaHoja('fis-panel'), { wrapper: arnes() });

    // Es lo que hace que 38 de las 40 pantallas no manden una sola peticion: sin conector, la
    // consulta no se habilita. Sin esto, abrir el arbol entero serian cuarenta idas a la red
    // contra rutas que nadie sirve — cuarenta 404 y cuarenta huecos identicos.
    expect(pedir).not.toHaveBeenCalled();
    expect(result.current.ausencia.enElCampo).toBe('sin conectar');
    expect(result.current.valores).toBeUndefined();
  });
});

describe('una pantalla CON conector recorre sus estados', () => {
  it('primero dice que esta pidiendo, y no finge un hueco', () => {
    contesta(CORRIDA);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes() });

    // Sin este estado, una pantalla llena de huecos durante dos segundos es indistinguible de una
    // pantalla sin backend — y para entonces el usuario ya se fue.
    expect(result.current.ausencia.enElCampo).toBe('pidiendo…');
  });

  it('y cuando llega, reparte lo que trae y marca lo que NO trae', async () => {
    contesta(CORRIDA);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes() });

    await waitFor(() => {
      expect(result.current.valores?.size).toBeGreaterThan(0);
    });
    expect(result.current.valores?.get(coordenada(0, 1))).toBe('28/01/2026 02:14');
    expect(result.current.filas?.get(0)).toHaveLength(1);
    // Y los tres que la operacion no publica van marcados campo a campo, no con el motivo de la
    // pantalla: la pantalla SI esta conectada, y decir lo contrario ahi seria falso.
    expect(result.current.ausenciaPorCampo?.get(coordenada(0, 2))).toBe('no publicado');
  });

  it('un 401 se dice como lo que es —vuelva a entrar—, no como «fallo la red»', async () => {
    contesta({ estado: 401, titulo: 'No autorizado' }, 401);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes() });

    await waitFor(() => {
      expect(result.current.ausencia.tono).toBe('atencion');
    });
    // La diferencia decide que hace el usuario: recargar no arregla una sesion caducada.
    expect(result.current.ausencia.explicacion).toMatch(/Vuelva a entrar/);
    expect(result.current.ausencia.enElCampo).toBe('sin acceso');
  });

  it('y otro error dice su codigo, para que se pueda buscar', async () => {
    contesta({ estado: 500 }, 500);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes() });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('fallo');
    });
    expect(result.current.ausencia.explicacion).toContain('500');
  });
});

/**
 * **Una hoja de un contribuyente concreto** (#169).
 *
 * Las tres operaciones de Consultas contestan 422 sin el codigo del contribuyente, asi que la
 * pregunta que decide esta pantalla no es «llego la respuesta» sino **de quien es**. El codigo
 * viaja en la direccion y llega aqui por `useHoja().ruta.sujeto`.
 */
describe('una pantalla que es de un contribuyente', () => {
  it('sin sujeto NO pide nada, y lo dice con su propia frase', () => {
    const pedidas = contestaSegunLaRuta({});
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel'), { wrapper: arnes() });

    // Lo contrario seria mandar la peticion sin el parametro y ensenar el 422 del backend como si
    // fuera una averia de la pantalla. Y lo OTRO contrario —elegir un contribuyente aqui— pintaria
    // la cuenta de una persona de verdad a quien nadie pregunto.
    expect(pedidas).toEqual([]);
    expect(result.current.ausencia.enElCampo).toBe('falta el contribuyente');
    expect(result.current.valores).toBeUndefined();
  });

  it('con sujeto pide SUS DOS operaciones, con el codigo dentro', async () => {
    const pedidas = contestaSegunLaRuta({
      '/consultas/unificada': FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel', '00000025673'), {
      wrapper: arnes(),
    });

    await waitFor(() => {
      expect(result.current.valores?.size).toBeGreaterThan(0);
    });
    expect(pedidas).toHaveLength(2);
    expect(pedidas[0]).toContain('/consultas/unificada?contribuyente=00000025673');
    expect(pedidas[1]).toContain('/consultas/deudas-con-beneficio?contribuyente=00000025673');
  });

  it('y lo que pinta es lo que CONTESTO el doble, no lo que dice su definicion', async () => {
    contestaSegunLaRuta({
      '/consultas/unificada': FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel', '00000025673'), {
      wrapper: arnes(),
    });

    await waitFor(() => {
      expect(result.current.valores?.get(coordenada(0, 6))).toBe('S/ 3,563.24');
    });
    expect(result.current.valores?.get(coordenada(0, 1))).toBe('DNI 29614026');
    expect(result.current.ausenciaPorCampo?.get(coordenada(0, 4))).toBe('no publicado');
  });

  it('CAMBIADA la respuesta del doble, cambia la pantalla — y es el AC3', async () => {
    contestaSegunLaRuta({
      '/consultas/unificada': OTRA_FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel', '00000003541'), {
      wrapper: arnes(),
    });

    await waitFor(() => {
      expect(result.current.valores?.get(coordenada(0, 6))).toBe('S/ 591.94');
    });
    // La misma hoja, la misma definicion, otro contribuyente: si algo saliera de la definicion,
    // este caso y el de arriba darian lo mismo.
    expect(result.current.valores?.get(coordenada(0, 1))).toBe('DNI 44218937');
    expect(result.current.valores?.get(coordenada(0, 2))).toBe('31/01/2026');
  });

  it('la constancia pide con `codContribuyente`, que es el nombre que ESA operacion admite', async () => {
    const pedidas = contestaSegunLaRuta({ '/consultas/constancias': CONSTANCIA_NEGADA });
    const { result } = renderHook(() => useDatosDeLaHoja('con-doc', '00000025673'), {
      wrapper: arnes(),
    });

    await waitFor(() => {
      expect(result.current.filas?.get(0)).toHaveLength(2);
    });
    expect(pedidas[0]).toContain(
      '/consultas/constancias/no-adeudo?codContribuyente=00000025673',
    );
    expect(result.current.valores?.get(coordenada(0, 5))).toContain('Con deuda al 12/09/2026');
  });

  it('dos contribuyentes de la misma hoja NO comparten cache', async () => {
    contestaSegunLaRuta({
      '/consultas/unificada': FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    // Un solo cliente para los dos, que es lo que hay en la aplicacion: la cache es de modulo.
    const wrapper = arnes();
    const uno = renderHook(() => useDatosDeLaHoja('con-panel', '00000025673'), { wrapper });
    await waitFor(() => {
      expect(uno.result.current.valores?.get(coordenada(0, 6))).toBe('S/ 3,563.24');
    });

    contestaSegunLaRuta({
      '/consultas/unificada': OTRA_FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const otro = renderHook(() => useDatosDeLaHoja('con-panel', '00000003541'), { wrapper });

    // **En la PRIMERA pintada**, que es donde esta el defecto: sin el sujeto en la clave, el
    // segundo contribuyente abre con lo que cacheo el primero —«S/ 3,563.24»— mientras llega lo
    // suyo, y despues lo sustituye. Esperar a que acabe no ve nada; en una ventanilla, ese
    // parpadeo es ensenarle a alguien la deuda de otro.
    expect(otro.result.current.valores?.get(coordenada(0, 6))).not.toBe('S/ 3,563.24');
    expect(otro.result.current.ausencia.enElCampo).toBe('pidiendo…');

    await waitFor(() => {
      expect(otro.result.current.valores?.get(coordenada(0, 6))).toBe('S/ 591.94');
    });
  });
});

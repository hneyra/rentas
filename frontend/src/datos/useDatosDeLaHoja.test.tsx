import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada } from '../pantallas/datos.ts';
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

describe('una pantalla SIN conector no toca la red', () => {
  it('no pide nada, y dice por que no hay dato', () => {
    const pedir = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', pedir);

    const { result } = renderHook(() => useDatosDeLaHoja('ini-panel'), { wrapper: arnes() });

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

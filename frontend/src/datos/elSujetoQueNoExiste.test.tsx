import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { pantallaDe } from '../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../pantallas/PantallaDeRentas.tsx';
import { CONECTORES } from './conectores.ts';
import { CODIGO_QUE_NO_EXISTE } from './conectores/consultas.ts';
import { useDatosDeLaHoja } from './useDatosDeLaHoja.ts';

/**
 * **Un sujeto de la direccion que no existe se explica como un sujeto que no existe** (#457).
 *
 * Los dos 404 —«esta cuenta no esta dada de alta aqui» y «ese codigo no esta en el padron»— llevan
 * el mismo `codigo`, asi que la escalera no los puede separar: sin `noEncontrado`, el de negocio
 * caia en el peldano de identidad y la pantalla mandaba a revisar la cuenta cuando lo que estaba
 * mal era un digito tecleado. Lo dice el conector, que es quien sabe que la direccion trae un
 * sujeto, y esta guarda exige que lo diga todo el que lo lleva.
 */
describe('un sujeto de la direccion que no existe (#457)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('todo conector con sujeto en la ruta declara que decir ante un 404', () => {
    const conSujeto = Object.entries(CONECTORES).filter(
      ([, conector]) => conector?.sujeto !== undefined,
    );
    // EL CENTINELA: sin conectores con sujeto, lo de abajo pasaria sobre la nada.
    expect(conSujeto.length, 'ningun conector lleva sujeto en la ruta').toBeGreaterThanOrEqual(5);

    const mudos = conSujeto
      .filter(([, conector]) => conector?.noEncontrado === undefined)
      .map(([hoja]) => `  ${hoja}`);
    expect(
      mudos,
      'Una hoja lee su sujeto de la direccion y no dice que hacer cuando no existe:\n' +
        `${mudos.join('\n')}\n\n` +
        '  Su 404 caeria en el peldano de identidad —«Revise con que cuenta esta entrando»—, que\n' +
        '  manda a revisar la cuenta cuando lo que esta mal es lo que se tecleo. Declare su\n' +
        '  `noEncontrado`: que no esta, y que se arregla escribiendo otro.',
    ).toEqual([]);
  });

  it('con-panel con un codigo que no esta: dice que no esta, y no manda a revisar la cuenta', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() =>
        Promise.resolve(
          new Response(
            JSON.stringify({
              status: 404,
              codigo: 'NO_ENCONTRADO',
              mensaje: 'No hay ningun contribuyente con el codigo 00000099999 en esta municipalidad',
            }),
            { status: 404, headers: { 'content-type': 'application/problem+json' } },
          ),
        ),
      ),
    );
    const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const arnes = ({ children }: { readonly children: ReactNode }) => (
      <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
    );
    function Hoja() {
      return (
        <PantallaDeRentas
          definicion={pantallaDe('con-panel')}
          datos={useDatosDeLaHoja('con-panel', { sujeto: '00000099999', parametros: {} })}
        />
      );
    }

    const { container } = render(<Hoja />, { wrapper: arnes });
    await waitFor(() => {
      expect(screen.queryByText(/pidiendo/i)).toBeNull();
    });

    expect(container.textContent).toContain(CODIGO_QUE_NO_EXISTE.explicacion);
    expect(container.textContent, 'hasta #457 el remedio era el de identidad').not.toMatch(
      /con que cuenta/i,
    );
  });
});

import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi, type MockInstance } from 'vitest';

import { FronteraDeLaHoja } from './FronteraDeLaHoja.tsx';

/**
 * **La frontera de una hoja, suelta: que recoge, cuando se reinicia y cuando NO** (#354).
 *
 * La prueba que importa —la aplicacion montada, con la respuesta medida— es
 * `verificaciones/una-hoja-que-revienta-no-se-lleva-el-armazon.test.tsx`. Esta mide las dos
 * propiedades de la clase que aquella no separa:
 *
 * · **Que no se reinicia sola.** Volver a pintar con el mismo `reinicio` deja la hoja caida: si se
 *   reiniciara en cada pintada, una hoja que lanza en cada render entraria en un bucle de montar y
 *   caer, y React acabaria cortandolo con su propio error.
 * · **Que lo que lanza su propio `enSuLugar` NO lo recoge ella.** Es lo que React hace con toda
 *   frontera, y es el motivo por el que `aplicacion.tsx` monta dos: si el interprete lanza con una
 *   definicion, dibujar la misma definicion con una ausencia lanzaria lo mismo.
 */

/** Un hijo que lanza mientras `lanza` sea cierto. */
function Hoja({ lanza }: { readonly lanza: boolean }) {
  if (lanza) throw new Error('Importe con una forma que el backend no sirve: «5500.000000».');
  return <p>la hoja se dibujo</p>;
}

const enSuLugar = (lanzado: unknown) => (
  <p>{`cayo: ${lanzado instanceof Error ? lanzado.message : String(lanzado)}`}</p>
);

let consola: MockInstance<typeof console.error>;

beforeEach(() => {
  // React avisa por consola de cada error que una frontera recoge. Es lo esperado aqui.
  consola = vi.spyOn(console, 'error').mockImplementation(() => {});
});

afterEach(() => {
  consola.mockRestore();
});

describe('`FronteraDeLaHoja` (#354)', () => {
  it('recoge lo que la hoja lanza y dibuja en su lugar lo que se le diga, con lo lanzado', () => {
    render(
      <FronteraDeLaHoja reinicio="a" enSuLugar={enSuLugar}>
        <Hoja lanza />
      </FronteraDeLaHoja>,
    );

    expect(screen.getByText(/cayo: .*5500\.000000/)).toBeInTheDocument();
  });

  it('con el MISMO reinicio sigue caida, aunque la hoja ya no lanzara', () => {
    const { rerender } = render(
      <FronteraDeLaHoja reinicio="a" enSuLugar={enSuLugar}>
        <Hoja lanza />
      </FronteraDeLaHoja>,
    );

    rerender(
      <FronteraDeLaHoja reinicio="a" enSuLugar={enSuLugar}>
        <Hoja lanza={false} />
      </FronteraDeLaHoja>,
    );

    expect(screen.getByText(/cayo:/)).toBeInTheDocument();
    expect(screen.queryByText('la hoja se dibujo')).toBeNull();
  });

  it('con OTRO reinicio vuelve a intentar la hoja', () => {
    const { rerender } = render(
      <FronteraDeLaHoja reinicio="a" enSuLugar={enSuLugar}>
        <Hoja lanza />
      </FronteraDeLaHoja>,
    );

    rerender(
      <FronteraDeLaHoja reinicio="b" enSuLugar={enSuLugar}>
        <Hoja lanza={false} />
      </FronteraDeLaHoja>,
    );

    expect(screen.getByText('la hoja se dibujo')).toBeInTheDocument();
    expect(screen.queryByText(/cayo:/)).toBeNull();
  });

  it('lo que lanza su `enSuLugar` sube a la frontera de fuera, que es por que hay dos', () => {
    render(
      <FronteraDeLaHoja reinicio="a" enSuLugar={() => <p>la de fuera</p>}>
        <FronteraDeLaHoja reinicio="a" enSuLugar={() => <Hoja lanza />}>
          <Hoja lanza />
        </FronteraDeLaHoja>
      </FronteraDeLaHoja>,
    );

    expect(screen.getByText('la de fuera')).toBeInTheDocument();
  });
});

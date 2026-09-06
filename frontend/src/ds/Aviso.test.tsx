import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { Aviso } from './Aviso.tsx';
import { Boton } from './Boton.tsx';

/**
 * AC6 — `Aviso` cubre los tres casos, con titulo, detalle y traza copiable de un
 * gesto.
 */

/** Enchufa un portapapeles de mentira y devuelve lo que se copio en el. */
function conPortapapeles(): { copiado: () => string | undefined } {
  const escribir = vi.fn<(texto: string) => Promise<void>>().mockResolvedValue(undefined);
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText: escribir },
    configurable: true,
  });
  return { copiado: () => escribir.mock.calls[0]?.[0] };
}

afterEach(() => {
  Reflect.deleteProperty(navigator, 'clipboard');
  vi.restoreAllMocks();
});

describe('los tres casos', () => {
  it.each(['vacio', 'error', 'sin-permiso'] as const)('«%s» dibuja titulo y detalle', (tipo) => {
    render(<Aviso tipo={tipo} titulo="Ningun contribuyente coincide" detalle="Pruebe por documento." />);

    expect(screen.getByText('Ningun contribuyente coincide')).toBeInTheDocument();
    expect(screen.getByText('Pruebe por documento.')).toBeInTheDocument();
  });

  it('un vacio NO interrumpe: no es una alerta', () => {
    // Es el resultado normal de una busqueda que no encontro nada. Anunciarlo como
    // alerta interrumpe al lector de pantalla por algo que no lo merece.
    render(<Aviso tipo="vacio" titulo="Ningun contribuyente coincide" />);

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it.each(['error', 'sin-permiso'] as const)('«%s» SI interrumpe', (tipo) => {
    render(<Aviso tipo={tipo} titulo="No se pudo leer la cuenta corriente" />);

    expect(screen.getByRole('alert')).toBeInTheDocument();
  });

  it('el detalle del backend va tal cual, sin reescribir', () => {
    // Llega ya redactado en castellano y en lenguaje del dominio. Una segunda
    // version aqui se separa de la suya en la primera correccion.
    const delBackend =
      'El contribuyente 00012345 no tiene predios declarados: sin unidad afecta no hay impuesto que determinar.';
    render(<Aviso tipo="vacio" titulo="Sin predios" detalle={delBackend} />);

    expect(screen.getByText(delBackend)).toBeInTheDocument();
  });

  it('sin detalle, no dibuja un parrafo vacio', () => {
    const { container } = render(<Aviso tipo="vacio" titulo="Sin deuda pendiente" />);

    expect(container.querySelectorAll('.kr-aviso__detalle')).toHaveLength(0);
  });

  it('las acciones que se le pasen se dibujan', () => {
    render(
      <Aviso tipo="vacio" titulo="Ningun contribuyente coincide">
        <Boton variante="primario">Nuevo contribuyente</Boton>
      </Aviso>,
    );

    expect(screen.getByRole('button', { name: 'Nuevo contribuyente' })).toBeInTheDocument();
  });
});

describe('la traza se copia de un gesto', () => {
  it('se ve en pantalla y se copia con un solo clic', async () => {
    const { copiado } = conPortapapeles();
    render(<Aviso tipo="error" titulo="No se pudo leer" traza="a1b2c3d4e5f6" />);

    expect(screen.getByText('Traza a1b2c3d4e5f6')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Copiar' }));

    expect(copiado()).toBe('a1b2c3d4e5f6');
    expect(await screen.findByText('Copiada')).toBeInTheDocument();
  });

  it('sin portapapeles no revienta: el numero sigue en pantalla y se dicta', async () => {
    // En una PC de ventanilla vieja `navigator.clipboard` puede no existir. Que la
    // pantalla que esta explicando un error se caiga por eso seria lo peor posible.
    render(<Aviso tipo="error" titulo="No se pudo leer" traza="a1b2c3d4e5f6" />);

    await userEvent.click(screen.getByRole('button', { name: 'Copiar' }));

    expect(screen.getByText('Traza a1b2c3d4e5f6')).toBeInTheDocument();
    expect(screen.queryByText('Copiada')).toBeNull();
  });

  it('si el portapapeles rechaza, tampoco dice que copio', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText: vi.fn().mockRejectedValue(new Error('denegado')) },
      configurable: true,
    });
    render(<Aviso tipo="error" titulo="No se pudo leer" traza="a1b2c3d4e5f6" />);

    await userEvent.click(screen.getByRole('button', { name: 'Copiar' }));

    expect(screen.queryByText('Copiada')).toBeNull();
  });

  it('sin traza no hay boton que copiar nada', () => {
    render(<Aviso tipo="vacio" titulo="Sin deuda pendiente" />);

    expect(screen.queryByRole('button', { name: 'Copiar' })).toBeNull();
  });
});

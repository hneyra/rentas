import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Boton } from './Boton.tsx';
import { Esqueleto } from './Esqueleto.tsx';
import { FechaDeCalculo } from './FechaDeCalculo.tsx';
import { Icono } from './Icono.tsx';
import { Importe } from './Importe.tsx';
import { Insignia } from './Insignia.tsx';

describe('Boton', () => {
  it('es «button» por omision y NO «submit»', () => {
    // El defecto clasico del HTML: un boton sin `type` dentro de un `<form>` lo
    // envia. Un «Buscar» junto a un formulario de alta lo guardaria.
    render(<Boton>Buscar</Boton>);

    expect(screen.getByRole('button', { name: 'Buscar' })).toHaveAttribute('type', 'button');
  });

  it('pero quien quiera enviar, puede', () => {
    render(<Boton type="submit">Guardar y cerrar</Boton>);

    expect(screen.getByRole('button', { name: 'Guardar y cerrar' })).toHaveAttribute(
      'type',
      'submit',
    );
  });

  it.each(['primario', 'secundario', 'fantasma'] as const)('la variante «%s» se ve', (variante) => {
    render(<Boton variante={variante}>Emitir orden de cobro</Boton>);

    expect(screen.getByRole('button')).toHaveClass(`kr-boton--${variante}`);
  });

  it('deshabilitado no llama a nadie', async () => {
    const pulsar = vi.fn();
    render(
      <Boton disabled onClick={pulsar}>
        Anular recibo
      </Boton>,
    );

    await userEvent.click(screen.getByRole('button'));

    expect(pulsar).not.toHaveBeenCalled();
  });

  it('conserva la clase que le pasen, sin perder las suyas', () => {
    render(<Boton className="mia">Ir</Boton>);

    expect(screen.getByRole('button')).toHaveClass('kr-boton', 'kr-boton--secundario', 'mia');
  });
});

describe('Insignia', () => {
  it('el texto va SIEMPRE dentro, no solo el color (AC5)', () => {
    render(<Insignia tono="mal">Vencido</Insignia>);

    // Que se pueda encontrar POR SU TEXTO es exactamente la propiedad: quien no
    // distingue el rojo del verde lee «Vencido» igual.
    expect(screen.getByText('Vencido')).toBeInTheDocument();
  });

  it.each(['ok', 'atencion', 'mal', 'info'] as const)('el tono «%s» se ve', (tono) => {
    render(<Insignia tono={tono}>Estado</Insignia>);

    expect(screen.getByText('Estado')).toHaveClass(`kr-insignia--${tono}`);
  });
});

describe('Importe', () => {
  it('dice el importe Y la fecha a la que esta calculado (AC4)', () => {
    render(<Importe valor="1842.60" fechaCalculo="2026-09-06" />);

    expect(screen.getByText('S/ 1,842.60')).toBeInTheDocument();
    expect(screen.getByText('al 06/09/2026')).toBeInTheDocument();
  });

  it('con «fechaImplicita» calla la fecha, pero sigue habiendo que pasarla', () => {
    render(<Importe valor="1842.60" fechaCalculo="2026-09-06" fechaImplicita />);

    expect(screen.getByText('S/ 1,842.60')).toBeInTheDocument();
    expect(screen.queryByText('al 06/09/2026')).toBeNull();
  });

  it('no hace aritmetica: pinta el texto que le dieron', () => {
    render(<Importe valor="0.10" fechaCalculo="2026-09-06" fechaImplicita />);
    render(<Importe valor="0.20" fechaCalculo="2026-09-06" fechaImplicita />);

    expect(screen.getByText('S/ 0.10')).toBeInTheDocument();
    expect(screen.getByText('S/ 0.20')).toBeInTheDocument();
  });
});

describe('FechaDeCalculo', () => {
  it('dice de cuando son las cifras de la pantalla', () => {
    render(<FechaDeCalculo fecha="2026-08-31" />);

    expect(screen.getByText(/Cifras actualizadas al/)).toBeInTheDocument();
    expect(screen.getByText('31/08/2026')).toBeInTheDocument();
  });
});

describe('Esqueleto', () => {
  it('ocupa el sitio del dato y no se anuncia', () => {
    const { container } = render(<Esqueleto alto={20} ancho="12ch" />);
    const marcador = container.querySelector('.kr-esqueleto');

    expect(marcador).toHaveAttribute('aria-hidden', 'true');
    expect(marcador).toHaveStyle({ height: '20px', width: '12ch' });
  });
});

describe('Icono', () => {
  it('es decorativo: se esconde del lector de pantalla', () => {
    const { container } = render(<Icono nombre="lupa" />);
    const svg = container.querySelector('svg');

    expect(svg).toHaveAttribute('aria-hidden', 'true');
    expect(svg).toHaveAttribute('focusable', 'false');
  });

  it('hereda el color de quien lo contiene', () => {
    // Es lo que hace que el mismo icono salga blanco dentro de un boton primario y
    // desvaido dentro de un aviso, sin que nadie le pase un color.
    const { container } = render(<Icono nombre="alerta" />);

    expect(container.querySelector('svg')).toHaveAttribute('stroke', 'currentColor');
  });

  it('dibuja los trazos del artboard, y la reja de 24x24', () => {
    const { container } = render(<Icono nombre="expediente" />);

    expect(container.querySelector('svg')).toHaveAttribute('viewBox', '0 0 24 24');
    expect(
      [...container.querySelectorAll('path')].map((trazo) => trazo.getAttribute('d')),
    ).toEqual(['M6.5 3.5h7.5l4 4v13h-11.5z', 'M14 3.5v4h4', 'M9.5 12.5h5']);
  });
});

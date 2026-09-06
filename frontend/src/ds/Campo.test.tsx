import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { Campo } from './Campo.tsx';

describe('todo control lleva su etiqueta asociada', () => {
  it.each(['text', 'date', 'sel', 'area', 'chk', 'ro'] as const)(
    'el tipo «%s» se encuentra por su etiqueta',
    (tipo) => {
      // `getByLabelText` falla si la etiqueta no apunta al control. Es la forma de
      // comprobar la asociacion sin mirar el `id`, que es un detalle.
      render(<Campo etiqueta="Apellido paterno" tipo={tipo} opciones={['', 'Natural']} />);

      expect(screen.getByLabelText('Apellido paterno')).toBeInTheDocument();
    },
  );

  it('dos campos con la MISMA etiqueta no comparten id', () => {
    // Sin `useId`, una rejilla que repite el mismo campo —una lista de cuotas—
    // dejaria la etiqueta de todos apuntando al primero.
    render(
      <>
        <Campo etiqueta="Numero" tipo="text" />
        <Campo etiqueta="Numero" tipo="text" />
      </>,
    );

    const [uno, otro] = screen.getAllByLabelText('Numero');

    expect(uno?.id).not.toBe(otro?.id);
    expect(uno?.id).toBeTruthy();
  });
});

describe('el error del backend', () => {
  it('se ve, y el control queda marcado como invalido', () => {
    render(
      <Campo
        etiqueta="Documento"
        tipo="text"
        error="El documento 44218937 ya esta registrado a nombre de otro contribuyente."
      />,
    );

    expect(
      screen.getByText('El documento 44218937 ya esta registrado a nombre de otro contribuyente.'),
    ).toBeInTheDocument();
    expect(screen.getByLabelText('Documento')).toHaveAttribute('aria-invalid', 'true');
  });

  it('se anuncia ANTES que la ayuda: primero lo que hay que corregir', () => {
    render(<Campo etiqueta="Documento" tipo="text" error="No es un DNI." ayuda="Ocho digitos." />);

    const control = screen.getByLabelText('Documento');
    const referencias = (control.getAttribute('aria-describedby') ?? '').split(' ');
    const textos = referencias.map((referencia) => document.getElementById(referencia)?.textContent);

    expect(textos).toEqual(['No es un DNI.', 'Ocho digitos.']);
  });

  it('sin error, el control no se declara invalido', () => {
    render(<Campo etiqueta="Documento" tipo="text" />);

    expect(screen.getByLabelText('Documento')).not.toHaveAttribute('aria-invalid');
  });
});

describe('el desplegable', () => {
  it('un valor que la API sirvio y el catalogo no tiene se ensena igual', () => {
    // Las dos listas vienen de sitios distintos y no tienen por que coincidir. Un
    // `<select>` con un valor que no esta en sus opciones se dibuja mostrando la
    // PRIMERA, y entonces la pantalla ensena una eleccion que nadie hizo.
    render(
      <Campo
        etiqueta="Tipo de via"
        tipo="sel"
        valor="AV — AVENIDA"
        opciones={['AV — Avenida', 'CA — Calle']}
      />,
    );

    expect(screen.getByLabelText('Tipo de via')).toHaveValue('AV — AVENIDA');
  });

  it('avisa del cambio con el valor elegido', async () => {
    const cambiar = vi.fn();
    render(
      <Campo
        etiqueta="Tipo de via"
        tipo="sel"
        valor=""
        opciones={['', 'AV — Avenida', 'CA — Calle']}
        onCambio={cambiar}
      />,
    );

    await userEvent.selectOptions(screen.getByLabelText('Tipo de via'), 'CA — Calle');

    expect(cambiar).toHaveBeenCalledWith('CA — Calle');
  });
});

describe('bloqueado y solo lectura no son lo mismo', () => {
  it('un campo bloqueado sigue en el recorrido del tabulador', async () => {
    // `readonly` y no `disabled`: un campo deshabilitado sale del recorrido, y en
    // ventanilla se trabaja con teclado.
    render(<Campo etiqueta="Codigo" tipo="text" valor="00012345" bloqueado />);
    const control = screen.getByLabelText('Codigo');

    expect(control).toHaveAttribute('readonly');
    expect(control).not.toBeDisabled();

    await userEvent.tab();

    expect(control).toHaveFocus();
  });

  it('un «ro» es un valor calculado, y ensena una raya cuando no lo hay', () => {
    render(<Campo etiqueta="Departamento" tipo="ro" />);

    expect(screen.getByLabelText('Departamento')).toHaveTextContent('—');
  });
});

describe('mientras el dato no llega', () => {
  it('en el sitio del control hay un esqueleto, y no un control vacio', () => {
    const { container } = render(<Campo etiqueta="Autovaluo" tipo="text" cargando />);

    expect(container.querySelector('.kr-esqueleto')).toBeInTheDocument();
    expect(screen.queryByLabelText('Autovaluo')).toBeNull();
  });
});

describe('lo accesorio', () => {
  it('«opcional» se dice con palabras', () => {
    render(<Campo etiqueta="Razon social" tipo="text" opcional />);

    expect(screen.getByText('opcional')).toBeInTheDocument();
  });

  it('la casilla manda «si» o vacio, no un booleano', async () => {
    const cambiar = vi.fn();
    render(
      <Campo
        etiqueta="Es pensionista"
        tipo="chk"
        ph="Acogido a la deduccion de 50 UIT"
        onCambio={cambiar}
      />,
    );

    await userEvent.click(screen.getByLabelText('Es pensionista'));

    expect(cambiar).toHaveBeenCalledWith('si');
  });

  it('«ancho» ocupa la fila entera de la rejilla', () => {
    const { container } = render(<Campo etiqueta="Nombre de la via" tipo="text" ancho />);

    expect(container.querySelector('.kr-campo')).toHaveClass('kr-campo--ancho');
  });
});

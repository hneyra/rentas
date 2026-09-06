import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { ErrorDeLaApi } from '../api/cliente.ts';
import { Ejercicio } from './Ejercicio.tsx';

/**
 * AC3, AC4 y AC5 — cambiar de ejercicio es un acto, y se ve como tal.
 *
 * <h2>Lo que estas pruebas separan, y que un solo caso no separa</h2>
 *
 * Que **se pida la observacion antes de mandar nada** (regla 10); que **la barra diga lo que
 * contesto el backend** y no lo que se tecleo; que **sin privilegio no haya mando**; y que un
 * rechazo del backend —el 422 mas probable de todos— **no se pinte como una averia**.
 */

/** Un `problem+json` del backend, con la forma que publica `ManejadorDeErrores`. */
function rechazo(estado: number, codigo: string, mensaje: string): ErrorDeLaApi {
  return new ErrorDeLaApi(estado, 'PUT /seguridad/sesion/ejercicio', {
    status: estado,
    title: mensaje,
    codigo,
    mensaje,
  });
}

const abrirElActo = async (usuario: ReturnType<typeof userEvent.setup>, rotulo: string) => {
  await usuario.click(screen.getByRole('button', { name: rotulo }));
};

const rellenar = async (
  usuario: ReturnType<typeof userEvent.setup>,
  anio: string,
  observacion: string,
) => {
  await usuario.clear(screen.getByLabelText('Ejercicio'));
  if (anio !== '') {
    await usuario.type(screen.getByLabelText('Ejercicio'), anio);
  }
  if (observacion !== '') {
    await usuario.type(screen.getByLabelText('Observación'), observacion);
  }
};

const boton = () => screen.getByRole('button', { name: 'Cambiar el ejercicio' });

describe('AC3 — no es un desplegable: pide su observacion y manda un PUT', () => {
  it('el mando abre un dialogo, y hasta entonces no ha salido ninguna peticion', async () => {
    const usuario = userEvent.setup();
    const cambiar = vi.fn(() => Promise.resolve());
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={cambiar} />);

    await abrirElActo(usuario, '2026');

    expect(
      screen.getByRole('dialog', { name: 'Cambiar el ejercicio de trabajo' }),
    ).toBeInTheDocument();
    // Un `<select>` habria cambiado de valor al pasar por encima. Aqui no ha pasado nada.
    expect(cambiar).not.toHaveBeenCalled();
  });

  it('sin observacion NO se puede mandar, y ese boton bloqueado rodea el defecto #30', async () => {
    const usuario = userEvent.setup();
    const cambiar = vi.fn(() => Promise.resolve());
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={cambiar} />);
    await abrirElActo(usuario, '2026');

    await rellenar(usuario, '2025', '');

    // Medido contra la instalacion: el cuerpo sin `observacion` contesta **500** con un
    // NullPointerException. Es el defecto #30 y no se arregla desde aqui — se rodea no
    // mandando nunca la unica peticion que se sabe que revienta.
    expect(boton()).toBeDisabled();
    await usuario.click(boton());
    expect(cambiar).not.toHaveBeenCalled();
  });

  it('con observacion manda el ejercicio y la observacion, y nada mas', async () => {
    const usuario = userEvent.setup();
    const cambiar = vi.fn(() => Promise.resolve());
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={cambiar} />);
    await abrirElActo(usuario, '2026');

    await rellenar(usuario, '2025', 'Cierre del ejercicio anterior');
    await usuario.click(boton());

    // El ejercicio va como NUMERO, que es lo que el `record CambioDeEjercicio` declara.
    expect(cambiar).toHaveBeenCalledWith(2025, 'Cierre del ejercicio anterior');
  });

  it('y al aceptarlo el dialogo se cierra', async () => {
    const usuario = userEvent.setup();
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={() => Promise.resolve()} />);
    await abrirElActo(usuario, '2026');
    await rellenar(usuario, '2025', 'Cierre del ejercicio anterior');

    await usuario.click(boton());

    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('cancelar no manda nada', async () => {
    const usuario = userEvent.setup();
    const cambiar = vi.fn(() => Promise.resolve());
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={cambiar} />);
    await abrirElActo(usuario, '2026');
    await rellenar(usuario, '2025', 'Cierre del ejercicio anterior');

    await usuario.click(screen.getByRole('button', { name: 'Cancelar' }));

    expect(cambiar).not.toHaveBeenCalled();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('un ano que no es un numero no se manda: `Number` y no `parseInt`', async () => {
    const usuario = userEvent.setup();
    const cambiar = vi.fn(() => Promise.resolve());
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={cambiar} />);
    await abrirElActo(usuario, '2026');

    await rellenar(usuario, '20a6', 'Cierre del ejercicio anterior');
    await usuario.click(boton());

    // `parseInt('20a6')` da **20** y habria mandado un ejercicio que nadie tecleo; el backend
    // lo habria rechazado por rango y el mensaje habria hablado del ano 20.
    expect(cambiar).not.toHaveBeenCalled();
  });
});

describe('AC4 — sin ejercicio fijado, el mando no se inventa ninguno', () => {
  it('dice «Sin fijar», y el campo arranca VACIO', async () => {
    const usuario = userEvent.setup();
    render(<Ejercicio ejercicio={null} puedeCambiar alCambiar={() => Promise.resolve()} />);

    expect(screen.getByRole('button', { name: 'Sin fijar' })).toBeInTheDocument();
    await abrirElActo(usuario, 'Sin fijar');

    // Rellenarlo con el ano del reloj del puesto seria exactamente la afirmacion que el backend
    // se niega a hacer: «ponerle el ano del reloj del servidor afirmaria que alguien lo eligio».
    expect(screen.getByLabelText('Ejercicio')).toHaveValue('');
  });

  it('con uno fijado, el campo arranca en el que rige', async () => {
    const usuario = userEvent.setup();
    render(<Ejercicio ejercicio={2026} puedeCambiar alCambiar={() => Promise.resolve()} />);

    await abrirElActo(usuario, '2026');

    expect(screen.getByLabelText('Ejercicio')).toHaveValue('2026');
  });
});

describe('AC5 — sin el privilegio no se ofrece el mando', () => {
  it('el ejercicio se lee y no se pulsa', () => {
    render(
      <Ejercicio ejercicio={2026} puedeCambiar={false} alCambiar={() => Promise.resolve()} />,
    );

    // El valor SIGUE estando: esconderlo dejaria a quien atiende sin saber sobre que ejercicio
    // esta trabajando, que es peor que no poder cambiarlo.
    expect(screen.getByText('2026')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('y sin ejercicio fijado tampoco, pero sigue diciendo «Sin fijar»', () => {
    render(
      <Ejercicio ejercicio={null} puedeCambiar={false} alCambiar={() => Promise.resolve()} />,
    );

    expect(screen.getByText('Sin fijar')).toBeInTheDocument();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('un 403 SIN_PRIVILEGIO que llegue igual se explica, y NO como una averia', async () => {
    const usuario = userEvent.setup();
    render(
      <Ejercicio
        ejercicio={2026}
        puedeCambiar
        alCambiar={() =>
          Promise.reject(
            rechazo(403, 'SIN_PRIVILEGIO', 'No tiene el privilegio ESPECIAL sobre cambiar_anio'),
          )
        }
      />,
    );
    await abrirElActo(usuario, '2026');
    await rellenar(usuario, '2025', 'Cierre del ejercicio anterior');

    await usuario.click(boton());

    expect(await screen.findByText('Falta un permiso para esta operacion')).toBeInTheDocument();
    expect(
      screen.getByText('No tiene el privilegio ESPECIAL sobre cambiar_anio'),
    ).toBeInTheDocument();
    // El candado y no el aspa: mandar a mirar un despliegue cuando lo que falta es una fila en
    // una tabla de permisos es el defecto que `escalera.ts` existe para impedir.
    expect(screen.getByRole('alert').className).toContain('kr-aviso--sin-permiso');
  });
});

describe('el 422 del backend se ensena con sus palabras, y el dialogo NO se cierra', () => {
  it('la observacion corta: se dice la regla que el backend dijo, con su cifra', async () => {
    const usuario = userEvent.setup();
    render(
      <Ejercicio
        ejercicio={2026}
        puedeCambiar
        alCambiar={() =>
          Promise.reject(
            rechazo(
              422,
              'VALIDACION',
              'La observacion debe explicar el cambio: al menos 5 caracteres, y no espacios en blanco (ADR-0008)',
            ),
          )
        }
      />,
    );
    await abrirElActo(usuario, '2026');
    await rellenar(usuario, '2025', 'ok');

    await usuario.click(boton());

    expect(
      await screen.findByText(/al menos 5 caracteres, y no espacios en blanco/),
    ).toBeInTheDocument();
    // **No es una averia**: el backend leyo la peticion, la entendio y la rechazo por una regla
    // suya. Sin el peldano del 422 esto caia en «avise a soporte», o sea que escribir «ok»
    // mandaba a llamar por telefono.
    expect(screen.getByRole('alert').className).toContain('kr-aviso--sin-permiso');
    expect(screen.getByRole('alert').className).not.toContain('kr-aviso--error');
  });

  it('el dialogo sigue abierto, con lo que ya estaba escrito dentro', async () => {
    const usuario = userEvent.setup();
    render(
      <Ejercicio
        ejercicio={2026}
        puedeCambiar
        alCambiar={() => Promise.reject(rechazo(422, 'VALIDACION', 'Ejercicio fuera de rango: 1800. Se admite de 1990 a 2100'))}
      />,
    );
    await abrirElActo(usuario, '2026');
    await rellenar(usuario, '1800', 'Reconstruccion historica');

    await usuario.click(boton());

    expect(await screen.findByText(/Se admite de 1990 a 2100/)).toBeInTheDocument();
    // Cerrar obligaria a teclear otra vez la observacion que ya estaba escrita, y el 422 mas
    // probable es justo «corrige una de las dos cosas y vuelve a mandar».
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText('Observación')).toHaveValue('Reconstruccion historica');
  });

  it('y un corte de red SI es una averia, y se ve distinto', async () => {
    const usuario = userEvent.setup();
    render(
      <Ejercicio
        ejercicio={2026}
        puedeCambiar
        alCambiar={() => Promise.reject(new TypeError('Failed to fetch'))}
      />,
    );
    await abrirElActo(usuario, '2026');
    await rellenar(usuario, '2025', 'Cierre del ejercicio anterior');

    await usuario.click(boton());

    expect(await screen.findByText('El sistema no contesta')).toBeInTheDocument();
    expect(screen.getByRole('alert').className).toContain('kr-aviso--error');
  });
});

describe('el ano no se elige de una lista, porque ninguna operacion la publica', () => {
  it('no hay `<select>` ni ninguna opcion: se teclea', async () => {
    const usuario = userEvent.setup();
    const { container } = render(
      <Ejercicio ejercicio={2026} puedeCambiar alCambiar={() => Promise.resolve()} />,
    );
    await abrirElActo(usuario, '2026');

    // El artboard ofrecia `['2026','2025','2024','2023']`. **Esa lista es una invencion**:
    // ninguna de las 181 operaciones publica los ejercicios que la municipalidad admite. Lo que
    // si esta medido es el rango que el backend acepta —1990 a 2100— y lo dice el, en su 422.
    expect(container.querySelectorAll('option')).toHaveLength(0);
    expect(screen.queryByRole('button', { name: '2023' })).toBeNull();
  });
});

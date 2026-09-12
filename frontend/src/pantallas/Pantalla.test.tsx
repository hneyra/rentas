import { PIEZA_POR_TIPO } from '@kamayuk/ui';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { Pantalla } from './Pantalla.tsx';
import type { Bloque, Campo, Pantalla as Definicion } from './tipos.ts';

/**
 * **El interprete dibuja lo que la definicion dice** (#88).
 *
 * Las pruebas son sobre definiciones INVENTADAS y no sobre las cuarenta de verdad, a proposito:
 * aqui se comprueba el interprete, y una prueba que dependiera de que «ini-panel» tiene seis
 * campos se rompe el dia que el artboard cambie sin que el interprete tenga nada que ver. Que las
 * cuarenta se dibujen es otra prueba, y esta en `las-cuarenta-se-dibujan.test.tsx`.
 */

const campo = (c: Campo): Definicion => ({
  instruccion: 'haga lo que toque.',
  bloques: [{ titulo: 'Un bloque', nota: '', campos: [c] }],
});

const monta = (definicion: Definicion, extra: Partial<Parameters<typeof Pantalla>[0]> = {}) =>
  render(<Pantalla definicion={definicion} modulo="Un modulo" titulo="Una pantalla" {...extra} />);

describe('los siete tipos de campo, cada uno con su pieza', () => {
  it('EL CENTINELA: la tabla de piezas de `@kamayuk/ui` sigue teniendo los siete', () => {
    // Si la libreria se quedara con uno, los `it` de abajo seguirian pasando de uno en uno y la
    // cobertura se habria evaporado sin que nada lo dijera.
    expect(Object.keys(PIEZA_POR_TIPO)).toHaveLength(7);
  });

  it('«s» es un desplegable de LISTA CERRADA, no un cuadro de texto', () => {
    monta(campo({ etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025'] }));
    expect(screen.getByRole('combobox', { name: 'Ejercicio' })).toBeTruthy();
    // Lo que distingue a la lista cerrada: no hay donde teclear.
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('«r» es un dato de solo lectura, y NO un campo desactivado', () => {
    monta(campo({ etiqueta: 'Recaudado', tipo: 'r', valor: 'S/ 18,424,251.20' }));
    const dato = document.querySelector('[data-slot="dato"]');
    expect(dato?.textContent).toBe('S/ 18,424,251.20');
    // `<output>`: se lee, sigue en el recorrido del tabulador, y no viaja con el formulario.
    expect(dato?.tagName).toBe('OUTPUT');
    expect(screen.queryByRole('textbox')).toBeNull();
  });

  it('«c» es una casilla, y su texto se lee AL LADO de la marca', () => {
    monta(campo({ etiqueta: 'Notificacion', tipo: 'c', casilla: 'Autoriza notificar al correo' }));
    expect(screen.getByRole('checkbox', { name: 'Autoriza notificar al correo' })).toBeTruthy();
  });

  it('«a» es un area, y «» un campo de una linea', () => {
    const { unmount } = monta(campo({ etiqueta: 'Observaciones', tipo: 'a' }));
    expect(screen.getByLabelText('Observaciones').tagName).toBe('TEXTAREA');
    unmount();
    monta(campo({ etiqueta: 'Nombre', tipo: '' }));
    expect(screen.getByLabelText('Nombre').tagName).toBe('INPUT');
  });

  it('«d» abre un calendario y NO un `input type=date`', () => {
    monta(campo({ etiqueta: 'Desde', tipo: 'd' }));
    // El nativo no se puede pintar y cambia de formato con el idioma del SISTEMA operativo: en
    // una fecha de vencimiento, «03/04» leido al reves no es una molestia, es otro dia.
    expect(document.querySelector('input[type="date"]')).toBeNull();
    expect(screen.getByRole('button', { name: /dd\/mm\/aaaa/ })).toBeTruthy();
  });

  it('el `1` estira el campo y NO cambia el control', () => {
    monta(campo({ etiqueta: 'Observaciones', tipo: 'a1' }));
    const etiqueta = document.querySelector('[data-slot="etiqueta"]');
    expect(etiqueta?.getAttribute('data-ancho')).toBe('1');
    expect(screen.getByLabelText('Observaciones').tagName).toBe('TEXTAREA');
  });

  it('un tipo que no existe REVIENTA en vez de dibujarse como texto', () => {
    // Es lo que impide que una definicion mal escrita se esconda detras de una pantalla que se ve
    // bien. El error viene de `tipoDe()` de la libreria, que es donde vive la tabla.
    expect(() =>
      monta(campo({ etiqueta: 'Raro', tipo: 'x' as 's', opciones: [] })),
    ).toThrow(/no es un tipo de campo/);
  });
});

describe('lo que se escribe, se escribe', () => {
  it('teclear cambia el valor y ENSUCIA la pantalla, una sola vez', async () => {
    const usuario = userEvent.setup({ delay: null });
    const sucias: number[] = [];
    monta(campo({ etiqueta: 'Nombre', tipo: '' }), { alEnsuciar: () => sucias.push(1) });

    await usuario.type(screen.getByLabelText('Nombre'), 'Rufina');
    expect((screen.getByLabelText('Nombre') as HTMLInputElement).value).toBe('Rufina');
    // Una vez, y no seis: quien escucha esto marca la hoja como sucia, y marcarla en cada tecla
    // son seis renderizados del arbol entero.
    expect(sucias, 'aviso de sucia en cada tecla').toHaveLength(1);
  });
});

const soloLectura: Bloque = {
  titulo: 'Consulta',
  nota: '',
  campos: [{ etiqueta: 'Recaudado', tipo: 'r', valor: 'S/ 1.00' }],
};
const conEscritura: Bloque = {
  titulo: 'Alta',
  nota: '',
  campos: [{ etiqueta: 'Nombre', tipo: '' }],
};

describe('las acciones al pie las decide el DATO', () => {
  it('sin ningun campo que se escriba: exportar e imprimir', () => {
    monta({ instruccion: 'consulte.', bloques: [soloLectura] });
    expect(screen.getByRole('button', { name: 'Exportar' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Imprimir' })).toBeTruthy();
    // Un boton de guardar aqui seria un boton que miente: no hay nada que guardar.
    expect(screen.queryByRole('button', { name: 'Guardar' })).toBeNull();
    // El aviso por omision NO dice «padron»: ese vocabulario es del sistema, y lo pasa el.
    expect(screen.getByText('Los datos son los que figuran a la fecha de hoy.')).toBeTruthy();
  });

  it('con UN solo campo que se escriba, aunque haya diez de consulta: limpiar y guardar', () => {
    monta({ instruccion: 'complete.', bloques: [soloLectura, conEscritura] });
    expect(screen.getByRole('button', { name: 'Guardar' })).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Limpiar' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Imprimir' })).toBeNull();
    expect(screen.getByText(/Nada se escribe hasta que pulse Guardar/)).toBeTruthy();
  });

  it('la que confirma es la UNICA primaria', () => {
    monta({ instruccion: 'complete.', bloques: [conEscritura] });
    expect(screen.getByRole('button', { name: 'Guardar' }).className).toContain('bg-azul');
    expect(screen.getByRole('button', { name: 'Limpiar' }).className).not.toContain('bg-azul');
  });

  it('el aviso lo puede poner quien monta la pantalla, con SUS palabras', () => {
    // Es lo que deja a `rentas` usar el texto de V8 —que dice «padron»— sin que esa palabra entre
    // en un archivo destinado a `@kamayuk/ui`.
    monta(
      { instruccion: 'consulte.', bloques: [soloLectura] },
      { avisos: { consulta: 'Lo que figura en el padron de ESTE sistema.', escritura: 'x' } },
    );
    expect(screen.getByText('Lo que figura en el padron de ESTE sistema.')).toBeTruthy();
  });

  it('«Limpiar» vacia lo tecleado', async () => {
    const usuario = userEvent.setup({ delay: null });
    monta({ instruccion: 'complete.', bloques: [conEscritura] });
    await usuario.type(screen.getByLabelText('Nombre'), 'Rufina');
    await usuario.click(screen.getByRole('button', { name: 'Limpiar' }));
    expect((screen.getByLabelText('Nombre') as HTMLInputElement).value).toBe('');
  });
});

describe('la cabecera', () => {
  it('el titulo va en peso 400 y es el UNICO `h1`', () => {
    monta({ instruccion: 'haga.', bloques: [conEscritura] });
    const h1 = screen.getByRole('heading', { level: 1, name: 'Una pantalla' });
    // 27 px en negrita pesa mas que la barra global y la pantalla se lee al reves. El tamano ya
    // jerarquiza; el peso encima lo hace gritar.
    expect(h1.className).toContain('font-normal');
    expect(h1.className).toContain('text-[27px]');
  });

  it('la miga lleva el modulo y la hoja, y la hoja es la actual', () => {
    monta({ instruccion: 'haga.', bloques: [conEscritura] });
    const miga = screen.getByRole('navigation', { name: 'Ruta' });
    expect(within(miga).getByText('Un modulo')).toBeTruthy();
    expect(within(miga).getByText('Una pantalla').className).toContain('font-bold');
  });

  it('la instruccion dice QUE HACER, y va aparte de la nota que dice QUE ES', () => {
    monta({
      instruccion: 'elija el ejercicio y revise lo emitido.',
      bloques: [conEscritura],
    }, { nota: 'El estado de la emision del padron.' });
    expect(screen.getByText(/elija el ejercicio y revise lo emitido/)).toBeTruthy();
    expect(screen.getByText('El estado de la emision del padron.')).toBeTruthy();
  });
});

describe('la tabla de un bloque', () => {
  const conTabla: Definicion = {
    instruccion: 'consulte.',
    bloques: [
      {
        titulo: 'Cuadre',
        nota: '',
        campos: [],
        tabla: {
          titulo: 'Por tributo',
          columnas: [
            { rotulo: 'Tributo', alineadoDerecha: false },
            { rotulo: 'Emitido S/', alineadoDerecha: true },
            { rotulo: 'Situacion', alineadoDerecha: false },
          ],
          filas: [
            ['Predial', '9,418,204.60', 'Conforme'],
            ['Arbitrios', '5,884,110.20', 'Vencida'],
          ],
          columnaDeInsignia: 2,
          nota: 'El saldo no es deuda perdida.',
          accion: 'Anadir',
        },
      },
    ],
  };

  it('la columna de cifras va a la derecha, y la primera identifica la fila', () => {
    monta(conTabla);
    expect(screen.getByRole('cell', { name: '9,418,204.60' }).className).toContain('tabular-nums');
    expect(screen.getByRole('cell', { name: 'Predial' }).className).toContain('whitespace-nowrap');
  });

  it('la columna de situacion se pinta como insignia, con el tono que el TEXTO pide', () => {
    monta(conTabla);
    // «Vencida» pide accion hoy; «Conforme» no. El tono sale del texto y no de un campo aparte,
    // que en 113 filas seria un dato a mano que el dia que se equivoque miente en verde.
    expect(screen.getByText('Vencida').className).toContain('bg-mal-fondo');
    expect(screen.getByText('Conforme').className).toContain('bg-ok-fondo');
  });

  it('la nota va FUERA de la tabla, y el conteo se cuenta solo', () => {
    monta(conTabla);
    // Dentro seria una fila mas y el lector la contaria como dato.
    expect(within(screen.getByRole('table')).queryByText(/deuda perdida/)).toBeNull();
    expect(screen.getByText('2 registros')).toBeTruthy();
  });

  it('una tabla sola, sin campos, NO ofrece guardar', () => {
    monta(conTabla);
    expect(screen.getByRole('button', { name: 'Exportar' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Guardar' })).toBeNull();
  });
});

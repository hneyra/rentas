import { PIEZA_POR_TIPO } from '@kamayuk/ui';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';

import { Pantalla } from './Pantalla.tsx';
import type { Campo, Pantalla as Definicion } from './tipos.ts';

/**
 * **El interprete dibuja lo que la definicion dice** (#88).
 *
 * Las pruebas son sobre definiciones INVENTADAS y no sobre las cuarenta de verdad, a proposito:
 * aqui se comprueba el interprete, y una prueba que dependiera de que «ini-panel» tiene seis
 * campos se rompe el dia que el artboard cambie sin que el interprete tenga nada que ver. Que las
 * cuarenta se dibujen es otra prueba, y desde #90 corre **contra la aplicacion montada**, en
 * `verificaciones/los-cuarenta-destinos-se-recorren.test.tsx`.
 *
 * <h2>La cabecera y el pie NO se prueban aqui, porque ya no se dibujan aqui</h2>
 *
 * Los pone `@kamayuk/shell`, que tiene sus propias pruebas de las dos. Este archivo tenia una
 * copia de esas comprobaciones hasta #90 y se retiran con el codigo que las justificaba: una
 * prueba que sobrevive a lo que probaba se convierte en una que pasa por otro motivo.
 */

const campo = (c: Campo): Definicion => ({
  instruccion: 'haga lo que toque.',
  bloques: [{ titulo: 'Un bloque', nota: '', campos: [c] }],
});

const monta = (definicion: Definicion, extra: Partial<Parameters<typeof Pantalla>[0]> = {}) =>
  render(<Pantalla definicion={definicion} {...extra} />);

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

});

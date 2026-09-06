import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

import { desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import { Valores } from './Valores.tsx';

/**
 * La seccion «Valores», montada y pidiendo por HTTP (AC6, AC7, AC8).
 *
 * Lo que estas pruebas sujetan, y no es evidente, es **de donde puede salir cada celda**: no hay
 * ninguna operacion que publique la tabla de valores del ejercicio, asi que lo que se ve sale de
 * las determinaciones —que es donde el sistema las aplico— y lo que no llega sale como guion.
 * Ver el javadoc de `valores.ts`.
 */

beforeAll(() => {
  instalarProxyDeDatos();
});

afterAll(() => {
  desinstalarProxyDeDatos();
});

async function montar() {
  const usuario = userEvent.setup();
  render(<Valores ejercicio="2026" />);
  await screen.findByText('UIT 2026');
  return usuario;
}

function fila(texto: string): HTMLElement {
  const celda = screen.getByText(texto);
  const tr = celda.closest('tr');
  if (tr === null) {
    throw new Error(`«${texto}» no esta dentro de ninguna fila.`);
  }
  return tr;
}

function cifraDe(texto: string): string {
  const dinero = fila(texto).querySelector('.kr-importe__valor');
  if (dinero === null) {
    throw new Error(`La fila de «${texto}» no tiene ninguna celda de dinero.`);
  }
  return dinero.textContent ?? '';
}

const pestana = (rotulo: string) => screen.getByRole('tab', { name: rotulo });

describe('AC6 — la portada de `VAL`, con sus tres pestanas', () => {
  it('las tres estan, con el rotulo del artboard, y la primera es la activa', async () => {
    await montar();

    expect(screen.getAllByRole('tab').map((boton) => boton.textContent)).toEqual([
      'UIT y escala progresiva',
      'Arbitrios por servicio',
      'Intereses y reajustes',
    ]);
    expect(pestana('UIT y escala progresiva').getAttribute('aria-selected')).toBe('true');
  });

  it('cambiar de pestana cambia la nota, las columnas y el pie', async () => {
    const usuario = await montar();
    expect(screen.getByText(/La UIT del ejercicio y los tres tramos/)).toBeInTheDocument();
    expect(screen.getByText(/La escala es acumulativa/)).toBeInTheDocument();

    await usuario.click(pestana('Arbitrios por servicio'));

    expect(screen.getByText(/Tasa mensual por metro de frontis/)).toBeInTheDocument();
    expect(screen.getByText(/Los arbitrios se determinan por predio/)).toBeInTheDocument();
    expect(screen.getAllByRole('columnheader').map((th) => th.textContent)).toEqual([
      'Servicio',
      'Zona 1',
      'Zona 2',
      'Zona 3',
      'Zona 4',
      'Criterio',
    ]);
  });

  it('la escala trae las ocho filas del cuadro', async () => {
    await montar();

    const cuadro = screen.getByRole('table', { name: 'UIT y escala progresiva' });
    // Ocho filas de datos mas la de cabecera.
    expect(within(cuadro).getAllByRole('row')).toHaveLength(9);
  });
});

describe('AC6 — lo que la pestana ensena sale de las determinaciones', () => {
  it('la UIT, con el ejercicio de la respuesta en su propio rotulo', async () => {
    await montar();

    expect(cifraDe('UIT 2026')).toBe('S/ 5,350.00');
  });

  it('los tres tramos, con su base leida de la regla aplicada y su alicuota', async () => {
    await montar();

    expect(within(fila('Tramo 1 del predial')).getByText('Hasta 15 UIT')).toBeInTheDocument();
    expect(within(fila('Tramo 1 del predial')).getByText('0.2 %')).toBeInTheDocument();
    expect(cifraDe('Tramo 1 del predial')).toBe('S/ 80,250.00');
    expect(within(fila('Tramo 2 del predial')).getByText('De 15 a 60 UIT')).toBeInTheDocument();
    expect(cifraDe('Tramo 2 del predial')).toBe('S/ 321,000.00');
  });

  it('el ultimo tramo dice «sin tope», que no es un importe sino la falta de uno', async () => {
    await montar();

    const tercero = fila('Tramo 3 del predial');
    expect(within(tercero).getByText('sin tope')).toBeInTheDocument();
    expect(tercero.querySelector('.kr-importe__valor')).toBeNull();
  });

  it('los dos minimos imponibles, cada uno de la memoria que lo publica', async () => {
    await montar();

    expect(cifraDe('Mínimo imponible predial')).toBe('S/ 32.10');
    expect(
      within(fila('Mínimo imponible predial')).getByText('0.6 % de la UIT'),
    ).toBeInTheDocument();
    // Este sale de la memoria VEHICULAR, que publica el suyo.
    expect(cifraDe('Mínimo imponible vehicular')).toBe('S/ 80.25');
  });

  it('el derecho de emision', async () => {
    await montar();

    expect(cifraDe('Derecho de emisión')).toBe('S/ 4.50');
  });

  it('la deduccion de pensionista queda ENTERA sin dato, y no se rellena de otra fuente', async () => {
    await montar();

    // `GET /rentas/beneficios` publica el monto de la deduccion del contribuyente que la tiene
    // concedida. Eso es un acto administrativo de una persona, no la tabla del ejercicio.
    const pensionista = fila('Deducción de pensionista');
    expect(pensionista.querySelector('.kr-importe__valor')).toBeNull();
    expect(within(pensionista).getAllByText('—')).toHaveLength(3);
  });
});

describe('«Solo lectura» deja de ser un adorno: lo sostiene el sello', () => {
  it('la pastilla dice de que conjunto y de que version es, y que esta sellado', async () => {
    await montar();

    expect(await screen.findByText(/conjunto 3 v1, sellado/)).toBeInTheDocument();
  });
});

describe('las dos pestanas que este backend no puede llenar', () => {
  it('los arbitrios por zona ensenan el servicio y NADA de las cuatro zonas', async () => {
    const usuario = await montar();
    await usuario.click(pestana('Arbitrios por servicio'));

    const barrido = fila('Limpieza pública — barrido');
    // Cuatro zonas y el criterio: cinco celdas sin dato, y ni un importe.
    expect(within(barrido).getAllByText('—')).toHaveLength(5);
    expect(barrido.querySelector('.kr-importe__valor')).toBeNull();
  });

  it('«Intereses y reajustes» sale VACIA, y dice por que', async () => {
    const usuario = await montar();
    await usuario.click(pestana('Intereses y reajustes'));

    expect(
      await screen.findByText('«Intereses y reajustes» no la publica ninguna operación'),
    ).toBeInTheDocument();
    const cuadro = screen.getByRole('table', { name: 'Intereses y reajustes' });
    // Solo la cabecera: no hay ni una fila de datos.
    expect(within(cuadro).getAllByRole('row')).toHaveLength(1);
    // Y no se inventa la TIM: el «0.90 %» del artboard no aparece en ninguna parte.
    expect(screen.queryByText('0.90 %')).toBeNull();
  });
});

describe('AC7 y AC8 — la fecha viene de la respuesta y los datos del proxy', () => {
  it('cada importe del cuadro lleva la fecha a la que esta calculado', async () => {
    await montar();

    // `Importe` la exige por tipo, asi que basta con que haya importes; lo que esta prueba
    // sujeta es que la fecha no se escribio a mano en la pantalla.
    const { readFileSync } = await import('node:fs');
    const sinComentarios = (fuente: string) =>
      fuente.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*$/gm, '');
    for (const archivo of ['src/secciones/Valores.tsx', 'src/secciones/valores.ts']) {
      expect(sinComentarios(readFileSync(archivo, 'utf8')), archivo).not.toContain('2026-08-12');
    }
  });

  it('ni un valor tributario escrito en el codigo (regla 5)', async () => {
    const { readFileSync } = await import('node:fs');
    const sinComentarios = readFileSync('src/secciones/valores.ts', 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '')
      .replace(/\/\/.*$/gm, '');

    // Las cinco cifras que el artboard escribe en este cuadro. Ninguna puede estar en el
    // codigo: la UIT, los dos topes de tramo, el minimo y la deduccion salen de la respuesta.
    for (const cifra of ['5,350.00', '80,250.00', '321,000.00', '32.10', '267,500.00']) {
      expect(sinComentarios, cifra).not.toContain(cifra);
    }
  });

  it('la seccion no importa la captura del artboard', async () => {
    const { readFileSync } = await import('node:fs');
    for (const archivo of ['src/secciones/Valores.tsx', 'src/secciones/valores.ts']) {
      expect(readFileSync(archivo, 'utf8'), archivo).not.toContain("from '../datos/prototipo.ts'");
    }
  });

  it('sin proxy no se inventa ninguna cifra: se dice que no se pudo leer', async () => {
    desinstalarProxyDeDatos();
    try {
      render(<Valores ejercicio="2026" />);
      expect(
        await screen.findByText('No se pudieron leer los valores del ejercicio'),
      ).toBeInTheDocument();
      expect(screen.queryByText('S/ 5,350.00')).toBeNull();
    } finally {
      instalarProxyDeDatos();
    }
  });
});

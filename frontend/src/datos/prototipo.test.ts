import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import {
  DETERMINACIONES,
  DOCS,
  DOC_EN_USO,
  EXPEDIENTE,
  NODOS,
  PASOS,
  PREDIOS,
  VAL,
} from './prototipo.ts';

/**
 * La captura esta ENTERA, y el aviso de la regla 5 sigue escrito.
 *
 * Dos cosas que se caen en silencio y que nada mas vigila:
 *
 *   1. **Que alguien recorte la captura a una muestra.** «Con dos contribuyentes se ve igual»
 *      es cierto hasta que una pantalla necesita el que falta, y entonces la copia que
 *      quedaba ya no es la del artboard y nadie sabe que se perdio.
 *   2. **Que alguien borre el aviso de los literales tributarios.** Es lo unico que dice que
 *      la UIT y los tramos de este archivo NO se quedan; sin el, en seis meses son una tabla
 *      de constantes tributarias en el codigo, que es lo que la regla 5 prohibe.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));

describe('los datos del artboard estan copiados enteros, no una muestra', () => {
  it('los cinco contribuyentes del padron', () => {
    expect(PREDIOS).toHaveLength(5);
    expect(PREDIOS.map((c) => c.cod)).toEqual([
      '00000025673',
      '00000003541',
      '00000006550',
      '00000006551',
      '00000152614',
    ]);
  });

  it('las seis secciones del expediente, con sus tres tablas', () => {
    expect(PASOS.map((p) => p.id)).toEqual([
      'ident',
      'domicilio',
      'unidades',
      'beneficios',
      'cuenta',
      'obs',
    ]);
    expect(PASOS.filter((p) => p.tabla !== undefined).map((p) => p.id)).toEqual([
      'unidades',
      'beneficios',
      'cuenta',
    ]);
    // 11 + 13 + 4 + 6 + 4 + 3 campos: la seccion recortada es la que deja de dibujarse entera.
    expect(PASOS.map((p) => p.campos.length)).toEqual([11, 13, 4, 6, 4, 3]);
  });

  it('las seis determinaciones, con todas sus filas', () => {
    expect(NODOS).toHaveLength(6);
    expect(DETERMINACIONES.map((d) => d.titulo)).toEqual(NODOS.map((n) => n[0]));
    expect(DETERMINACIONES.map((d) => d.filas.length)).toEqual([9, 5, 5, 6, 7, 3]);
  });

  it('las tres tablas de valores del ejercicio', () => {
    expect(VAL.map((v) => v.label)).toEqual([
      'UIT y escala progresiva',
      'Arbitrios por servicio',
      'Intereses y reajustes',
    ]);
    expect(VAL.map((v) => v.filas.length)).toEqual([8, 4, 4]);
  });

  it('los tres documentos con su longitud, y el que el artboard usa como ya tomado', () => {
    expect(DOCS).toEqual({ DNI: 8, RUC: 11, 'Carnet de extranjería': 12 });
    // No es un dato suelto: es el DNI del segundo contribuyente del padron. Que este «en uso»
    // lo decide el padron, no esta constante — comprobar si un documento esta tomado es una
    // decision del servidor, y el proxy no la finge (AC8).
    expect(PREDIOS.map((c) => c.titular)).toContain(`DNI ${DOC_EN_USO} · persona natural`);
  });

  it('el expediente abierto, con sus 41 valores', () => {
    expect(Object.keys(EXPEDIENTE)).toHaveLength(41);
    // Las cuatro cifras de la cuenta corriente son las que hacen de esto un expediente y no
    // un formulario vacio.
    expect(EXPEDIENTE['deudaTotal']).toBe('3,455.24');
    expect(EXPEDIENTE['insoluto']).toBe('3,041.92');
    expect(EXPEDIENTE['interes']).toBe('413.32');
    expect(EXPEDIENTE['gastos']).toBe('96.00');
  });
});

describe('el aviso sobre los literales tributarios sigue escrito (regla 5)', () => {
  const fuente = readFileSync(join(AQUI, 'prototipo.ts'), 'utf8');

  it.each([
    ['la regla que los prohibe', 'regla 5'],
    ['las tres clases de cifra que trae la captura', 'UIT'],
    ['que llegan de la API el dia que conecte', 'llegan de la API'],
    ['y que este archivo se borra, no se actualiza', 'se borra entero'],
  ])('dice %s', (_que, texto) => {
    expect(
      fuente,
      'Se borro el aviso de la regla 5 del encabezado de prototipo.ts.\n' +
        'Es lo unico que dice que la UIT, los tramos y las alicuotas de este archivo NO se\n' +
        'quedan. Sin el, en seis meses son una tabla de constantes tributarias en el codigo.',
    ).toContain(texto);
  });

  it('y la captura efectivamente trae esas cifras, que es lo que hace falta el aviso', () => {
    const uit = VAL[0]?.filas.find((f) => f[0] === 'UIT 2026');
    expect(uit?.[3]).toBe('5,350.00');
    expect(VAL[0]?.filas.filter((f) => f[0]?.startsWith('Tramo'))).toHaveLength(3);
  });
});

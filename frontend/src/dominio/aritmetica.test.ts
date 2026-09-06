import { describe, expect, it } from 'vitest';

import { mismosCentimos, sumarImportes } from './aritmetica.ts';

/**
 * La suma exacta, y lo que la separa de sumar con `Number`.
 *
 * El caso que mas dice es el de los diecisiete digitos: en coma flotante esa suma pierde el
 * centimo y aqui no, que es la razon entera por la que este archivo existe (regla 1, RNF-055).
 */

describe('sumarImportes suma en centimos, no en coma flotante', () => {
  it('los tres tramos del artboard dan su insoluto', () => {
    expect(sumarImportes(['160.50', '426.94', '0.00'])).toBe('587.44');
  });

  it('el insoluto mas el derecho de emision da el total', () => {
    expect(sumarImportes(['587.44', '4.50'])).toBe('591.94');
  });

  it('0.10 + 0.20 da 0.30, que con `Number` no da', () => {
    expect(sumarImportes(['0.10', '0.20'])).toBe('0.30');
    // La prueba de que no es una casualidad de formato: por el camino prohibido sale otra cosa.
    expect(String(0.1 + 0.2)).not.toBe('0.3');
  });

  it('con diecisiete digitos el centimo sigue estando', () => {
    // 2^53 se queda corto mucho antes de esto: con `Number`, sumar un centimo aqui no cambia
    // nada. Con enteros de `BigInt`, si.
    expect(sumarImportes(['99999999999999.99', '0.01'])).toBe('100000000000000.00');
  });

  it('la suma de nada es cero, y no un fallo', () => {
    expect(sumarImportes([])).toBe('0.00');
  });

  it('acepta el decimal a medias y el negativo, que es como el backend los publica', () => {
    expect(sumarImportes(['1.5', '2'])).toBe('3.50');
    expect(sumarImportes(['10.00', '-2.50'])).toBe('7.50');
  });

  it('un importe con una forma que el backend no sirve revienta, y lo nombra', () => {
    expect(() => sumarImportes(['1,842.60'])).toThrowError(/«1,842.60»/);
    expect(() => sumarImportes(['587.444'])).toThrowError(/«587.444»/);
  });
});

describe('mismosCentimos compara la cifra, no el texto', () => {
  it('«587.4» y «587.40» son la misma cifra', () => {
    expect(mismosCentimos('587.4', '587.40')).toBe(true);
    // Y comparar el texto diria que no: es el falso rojo que esta funcion evita. Se comparan
    // dos variables y no dos literales, porque `tsc` rechaza comparar dos literales distintos.
    const conUnDecimal: string = '587.4';
    expect(conUnDecimal === '587.40').toBe(false);
  });

  it('un centimo de diferencia se ve', () => {
    expect(mismosCentimos('587.44', '587.45')).toBe(false);
  });

  it('«0» y «0.00» son cero los dos', () => {
    expect(mismosCentimos('0', '0.00')).toBe(true);
  });
});

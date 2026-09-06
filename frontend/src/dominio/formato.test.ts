import { describe, expect, it } from 'vitest';

import { formatearFecha, formatearImporte } from './formato.ts';

describe('un importe se escribe como el artboard lo escribe', () => {
  it.each([
    ['0', 'S/ 0.00'],
    ['0.5', 'S/ 0.50'],
    ['412.00', 'S/ 412.00'],
    ['1842.6', 'S/ 1,842.60'],
    ['170616.75', 'S/ 170,616.75'],
    ['412880.00', 'S/ 412,880.00'],
    ['1234567890.99', 'S/ 1,234,567,890.99'],
    ['-591.94', '-S/ 591.94'],
    ['  1842.60  ', 'S/ 1,842.60'],
    ['0007.5', 'S/ 7.50'],
  ])('«%s» -> «%s»', (servido, mostrado) => {
    expect(formatearImporte(servido)).toBe(mostrado);
  });

  it('un importe de quince digitos no pierde ni un centimo', () => {
    // La prueba de que no hay un `Number` escondido. Este valor no cabe entero en
    // un `double`: `Number('999999999999999.99')` da 1000000000000000, y el
    // resultado se veria como «S/ 1,000,000,000,000,000.00» — un millon de veces
    // el importe, sin un solo error.
    expect(formatearImporte('999999999999999.99')).toBe('S/ 999,999,999,999,999.99');
  });

  it.each([
    ['412880.005', 'tres decimales: recortar seria aritmetica sobre dinero'],
    ['1,842.60', 'ya viene agrupado: eso lo hace la pantalla, no el backend'],
    ['1842,60', 'coma decimal'],
    ['', 'vacio'],
    ['S/ 1842.60', 'con la moneda dentro'],
    ['1.8e3', 'notacion cientifica'],
  ])('se NIEGA a formatear «%s» (%s)', (servido) => {
    // No devuelve el texto tal cual ni lo recorta: falla. Un «412880.005» pintado
    // en una columna de importes no lo mira nadie dos veces, y un centimo que
    // desaparece al pintarlo no deja rastro en ningun sitio.
    expect(() => formatearImporte(servido)).toThrow(/no sirve/);
  });
});

describe('una fecha se escribe como el artboard la escribe', () => {
  it.each([
    ['2026-09-06', '06/09/2026'],
    ['2026-08-31', '31/08/2026'],
    ['2026-01-01', '01/01/2026'],
  ])('«%s» -> «%s»', (servida, mostrada) => {
    expect(formatearFecha(servida)).toBe(mostrada);
  });

  it('no pasa por un Date, asi que no se corre un dia con la zona horaria', () => {
    // `new Date('2026-01-01')` se interpreta en UTC y se imprime en local: en Lima
    // (UTC-5) sale el 31 de diciembre. Un estado de cuenta que cambia de dia segun
    // donde este el navegador no es un detalle de formato.
    expect(formatearFecha('2026-01-01')).toBe('01/01/2026');
    expect(formatearFecha('2026-01-01').startsWith('31')).toBe(false);
  });

  it.each(['06/09/2026', '2026-9-6', '2026-09-06T00:00:00Z', ''])(
    'se niega a formatear «%s»',
    (servida) => {
      expect(() => formatearFecha(servida)).toThrow(/no sirve/);
    },
  );
});

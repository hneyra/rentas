import { describe, expect, it } from 'vitest';

import {
  compararImportes,
  formatearFecha,
  formatearFechaEnPalabras,
  formatearImporte,
  formatearInstante,
} from './formato.ts';

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

describe('ordenar por importe se hace con texto, no con numeros (F-5)', () => {
  it.each([
    ['1842.60', '591.94'],
    ['9412.15', '1842.60'],
    ['100.00', '99.99'],
    ['0.10', '0.09'],
    ['1.00', '-1.00'],
    ['-1.00', '-2.00'],
  ])('«%s» pesa mas que «%s»', (mayor, menor) => {
    expect(compararImportes(mayor, menor)).toBeGreaterThan(0);
    expect(compararImportes(menor, mayor)).toBeLessThan(0);
  });

  it.each([
    ['412.00', '412.00'],
    ['412', '412.00'],
    ['0007.50', '7.5'],
  ])('«%s» y «%s» pesan igual', (uno, otro) => {
    expect(compararImportes(uno, otro)).toBe(0);
  });

  it('distingue un centimo donde la coma flotante ya no llega', () => {
    // Es el motivo de que exista: `Number('9007199254740993.00')` y
    // `Number('9007199254740992.00')` son el MISMO numero, asi que una comparacion
    // por resta diria «iguales» y el orden de la lista dependeria de por donde se
    // empezara a ordenar (regla 1, RNF-055).
    expect(Number('9007199254740993.00') - Number('9007199254740992.00')).toBe(0);
    expect(compararImportes('9007199254740993.00', '9007199254740992.00')).toBeGreaterThan(0);
  });

  it('ordena una lista entera, de mas a menos', () => {
    const importes = ['591.94', '9412.15', '0.00', '1842.60', '412.00'];
    expect([...importes].sort((a, b) => compararImportes(b, a))).toEqual([
      '9412.15',
      '1842.60',
      '591.94',
      '412.00',
      '0.00',
    ]);
  });

  it('se niega a ordenar por algo que no es un importe servido', () => {
    expect(() => compararImportes('S/ 1,842.60', '412.00')).toThrow(/no se puede ordenar/i);
  });
});

describe('la fecha en palabras, como el artboard escribe la de corte', () => {
  it.each([
    ['2026-08-31', '31 de agosto'],
    ['2026-01-01', '1 de enero'],
    ['2026-12-25', '25 de diciembre'],
    ['2026-09-06', '6 de septiembre'],
  ])('«%s» -> «%s»', (servida, mostrada) => {
    expect(formatearFechaEnPalabras(servida)).toBe(mostrada);
  });

  it('no pasa por un Date ni por Intl, asi que no se corre un dia', () => {
    expect(formatearFechaEnPalabras('2026-01-01')).toBe('1 de enero');
  });

  it.each(['2026-13-01', '31/08/2026', ''])('se niega con «%s»', (servida) => {
    expect(() => formatearFechaEnPalabras(servida)).toThrow();
  });
});

describe('un instante se escribe con su zona, y NO se mueve de ella (#181)', () => {
  it.each([
    ['2026-08-13T14:41:12Z', '13/08/2026 14:41 UTC'],
    // Sin segundos y con milisegundos: las dos formas que un `Instant` de Java puede tomar.
    ['2026-08-13T14:41Z', '13/08/2026 14:41 UTC'],
    ['2026-01-01T00:00:00.000Z', '01/01/2026 00:00 UTC'],
  ])('«%s» -> «%s»', (servido, mostrado) => {
    expect(formatearInstante(servido)).toBe(mostrado);
  });

  it('la misma medianoche NO se corre un dia, que es lo que haria un `Date`', () => {
    // `new Date("2026-01-01T00:00:00Z").toLocaleString()` en Lima da el 31 de diciembre. Este
    // archivo no construye ninguno, asi que da igual donde corra: la prueba pasa en cualquier
    // zona, que es lo que la hace valer algo.
    expect(formatearInstante('2026-01-01T00:00:00Z')).toContain('01/01/2026');
  });

  it('y dice la zona, porque la hora es de UTC y la pantalla no puede afirmar que es la de aqui', () => {
    // En una bitacora de auditoria, «a que hora se anulo ese recibo» es la pregunta. Pintar
    // 14:41 sin decir de donde es, en un sitio que esta a UTC-5, son cinco horas de diferencia
    // sin ningun sintoma.
    expect(formatearInstante('2026-08-13T14:41:12Z')).toMatch(/UTC$/);
  });

  it('se niega con lo que NO es un instante, en vez de escribirlo a medias', () => {
    // Una fecha sin hora es una `Fecha` y la escribe `formatearFecha`; una con desfase no la
    // publica ningun `Instant` de este backend, y admitirla seria admitir una hora sin zona fija.
    for (const raro of ['2026-08-13', '13/08/2026 14:41', '2026-08-13T14:41:12-05:00', '']) {
      expect(() => formatearInstante(raro), raro).toThrow(/no sirve/i);
    }
  });

  it('y `formatearFecha` sigue negandose a un instante: no son el mismo tipo', () => {
    // Es la mitad que hace que el tipo `Instante` sirva de algo. Sin ella, alguien pasaria el
    // instante por `formatearFecha` y lo que saldria seria un error de forma, no un dato malo —
    // pero solo porque la expresion regular lo caza por casualidad. Aqui queda escrito.
    expect(() => formatearFecha('2026-08-13T14:41:12Z')).toThrow();
  });
});

import { describe, expect, it } from 'vitest';

import {
  TIPOS_DE_DOCUMENTO,
  TIPO_POR_OMISION,
  documentoCompleto,
  longitudDe,
  soloDigitos,
} from './documento.ts';

/**
 * La regla del documento, sin montar nada (AC7).
 *
 * Que las tres longitudes sean las del artboard lo comprueba
 * `verificaciones/secciones-del-artboard.test.ts`, leyendo `const DOCS` del `.dc.html`. Lo que
 * se prueba aqui es el COMPORTAMIENTO: que un tipo desconocido reviente en vez de suponer ocho,
 * que la limpieza recorte a la longitud de su tipo y que «completo» sea exacto y no «al menos».
 */

describe('cuantos digitos tiene cada documento', () => {
  it('los tres del artboard, y el primero es el que sale por omision', () => {
    expect(TIPOS_DE_DOCUMENTO).toEqual(['DNI', 'RUC', 'Carnet de extranjería']);
    expect(TIPO_POR_OMISION).toBe('DNI');
    expect(TIPOS_DE_DOCUMENTO[0]).toBe(TIPO_POR_OMISION);
  });

  it('un tipo que no existe REVIENTA, y no supone la longitud del DNI', () => {
    // Suponer ocho aceptaria un carnet de extranjeria de ocho digitos y lo mandaria al padron.
    expect(() => longitudDe('Pasaporte')).toThrow(/no es un tipo de documento conocido/);
    expect(() => longitudDe('Pasaporte')).toThrow(/DNI, RUC, Carnet de extranjería/);
  });
});

describe('lo tecleado se limpia', () => {
  it('las letras no cuentan: no es un error que anunciar, es una tecla que no cuenta', () => {
    expect(soloDigitos('03a59b31c74', 8)).toBe('03593174');
  });

  it('se recorta a la longitud del tipo, y no a una fija', () => {
    expect(soloDigitos('205251184479999', 11)).toBe('20525118447');
    expect(soloDigitos('205251184479999', 8)).toBe('20525118');
  });

  it('un texto sin un solo digito da la cadena vacia', () => {
    expect(soloDigitos('   ', 8)).toBe('');
  });
});

describe('«completo» es exacto, ni uno menos ni uno mas', () => {
  it.each([
    ['DNI', '03593174', true],
    ['DNI', '0359317', false],
    ['RUC', '20525118447', true],
    ['RUC', '2052511844', false],
    ['Carnet de extranjería', '001234567890', true],
    ['Carnet de extranjería', '00123456789', false],
  ])('«%s» con «%s» → %s', (tipo, numero, esperado) => {
    expect(documentoCompleto(numero, tipo)).toBe(esperado);
  });

  it('un DNI de once digitos NO esta completo, aunque tenga los ocho primeros', () => {
    // La compuerta del artboard recorta al teclear, asi que este caso no llega desde la
    // pantalla; se comprueba igual porque la regla es «exactamente ocho», no «al menos ocho», y
    // un `>=` escrito aqui pasaria desapercibido.
    expect(documentoCompleto('03593174999', 'DNI')).toBe(false);
  });
});

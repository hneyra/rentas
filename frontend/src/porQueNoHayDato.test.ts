import { describe, expect, it } from 'vitest';

import { ARBOL, hojaDe } from './pantallas/arbol.ts';
import type { Hoja } from './pantallas/tipos.ts';
import { YA_SERVIDAS } from './datos/servidas.ts';
import {
  NADA_SERVIDO,
  SERVIDO_Y_SIN_PEDIR,
  SOLO_BASE,
  operacionesUtiles,
  porQueNoHayDato,
} from './porQueNoHayDato.ts';

/**
 * **Por que una pantalla no tiene datos** (#97).
 *
 * Lo que se comprueba aqui no es que las frases suenen bien: es que **los casos no se confundan**.
 * Meterlos en un «no hay datos» unico seria mentir por omision — «este modulo no esta conectado» y
 * «esta pantalla se conecta pero no se sabe el verbo de su ruta» son cosas distintas para quien
 * tenga que arreglarlas.
 */

// Anotada a proposito: `ARBOL` es un `as const` de diez modulos con hojas de tipos distintos, y
// sin la anotacion el compilador intenta unificar diez tuplas y se rinde.
const TODAS: readonly Hoja[] = ARBOL.flatMap((modulo) => [...modulo.hojas]);

describe('el cruce contra lo que el backend sirve', () => {
  it('EL CENTINELA: hay cuarenta hojas y catorce operaciones servidas', () => {
    // Sin esto, un arbol vacio o unas `YA_SERVIDAS` vacias dejarian todo lo de abajo pasando
    // sobre la nada — y la respuesta seria «ninguna pantalla tiene datos», que ademas parece
    // razonable.
    expect(TODAS).toHaveLength(40);
    expect(YA_SERVIDAS).toHaveLength(14);
  });

  it('cruza por RUTA, no por verbo: dos servidas las declara el artboard como `BASE`', () => {
    // `GET /rentas/contribuyentes` y `GET /rentas/beneficios` estan servidas y medidas, y el
    // artboard las declara `BASE`. Con el cruce estricto, `predios` y `valores` perderian dato
    // que existe.
    expect(operacionesUtiles(hojaDe('predios')).map((o) => o.ruta)).toContain(
      '/rentas/contribuyentes',
    );
    expect(operacionesUtiles(hojaDe('valores')).map((o) => o.ruta)).toContain('/rentas/beneficios');
  });

  it('y solo mira verbos de LECTURA: un PUT no pinta una pantalla', () => {
    // `seg-sis` declara `PUT /seguridad/sesion/ejercicio`, que ESTA servida. Y aun asi no tiene
    // con que dibujarse: un PUT no devuelve una pantalla.
    const utiles = operacionesUtiles(hojaDe('seg-sis'));
    expect(utiles.every((o) => o.verbo !== 'PUT')).toBe(true);
  });

  it('ocho hojas tienen alguna operacion util, y treinta y dos ninguna', () => {
    const con = TODAS.filter((hoja) => operacionesUtiles(hoja).length > 0);
    // `aut-cat` y `aut-panel` entran con #168, y las dos por la ruta que el ARTBOARD les
    // atribuye: `GET /licencias/ciiu` a la primera y `GET /licencias/funcionamiento` a la
    // segunda. **`aut-tram` no esta aqui y si tiene conector**, que es la unica pareja de este
    // tipo en las cuarenta: la operacion que dibuja sus cinco columnas es la que el artboard le
    // dio a `aut-panel`, y el arbol es la transcripcion del artboard. Esta funcion solo decide
    // que decir cuando NO hay conector, asi que la discrepancia no llega a ninguna pantalla —
    // pero se anota aqui, que es donde se ve.
    expect(con.map((h) => h.clave).sort()).toEqual(
      ['aut-cat', 'aut-panel', 'coa-panel', 'panel', 'predios', 'seg-acc', 'seg-panel', 'valores'].sort(),
    );
    expect(TODAS.length - con.length).toBe(32);
  });
});

describe('los cuatro casos no se confunden', () => {
  it('sin ninguna servida: «sin conectar», en tono informativo', () => {
    // `ini-panel` declara `GET /indicadores/recaudacion`, que no esta servida.
    expect(porQueNoHayDato(hojaDe('ini-panel'))).toBe(NADA_SERVIDO);
    expect(NADA_SERVIDO.tono).toBe('info');
  });

  it('todo en `BASE`: «sin verificar», y en tono de ATENCION', () => {
    // `tra-pap` tiene sus cuatro operaciones en `BASE`. Hay controlador y no se sabe llamarlo:
    // eso merece mas atencion que no tener nada, no menos.
    expect(porQueNoHayDato(hojaDe('tra-pap'))).toBe(SOLO_BASE);
    expect(SOLO_BASE.tono).toBe('atencion');
  });

  it('con servidas y sin pedirlas: lo dice, en vez de callar', () => {
    expect(porQueNoHayDato(hojaDe('seg-panel'))).toBe(SERVIDO_Y_SIN_PEDIR);
  });

  it('y las tres frases son DISTINTAS, que es lo que las hace servir de algo', () => {
    const frases = [NADA_SERVIDO, SOLO_BASE, SERVIDO_Y_SIN_PEDIR];
    expect(new Set(frases.map((a) => a.enElCampo)).size).toBe(3);
    expect(new Set(frases.map((a) => a.explicacion)).size).toBe(3);
  });

  it('NINGUNA dice un cero ni una cifra: no saber no es una afirmacion', () => {
    // Es la regla que motiva todo esto. Un «0» donde no se ha contado nada es indistinguible de
    // un cero real, y en recaudacion eso se lee como «no debe nada».
    for (const ausencia of [NADA_SERVIDO, SOLO_BASE, SERVIDO_Y_SIN_PEDIR]) {
      expect(ausencia.enElCampo, `«${ausencia.enElCampo}» lleva un digito`).not.toMatch(/\d/);
      expect(ausencia.enElCampo).not.toMatch(/^0|S\//);
    }
  });
});

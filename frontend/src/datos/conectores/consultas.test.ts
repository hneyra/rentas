import { describe, expect, it } from 'vitest';

import { coordenada, type DefinicionDePantalla as Pantalla } from '@kamayuk/ui';

import { PANTALLAS } from '../../pantallas/definiciones/index.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import { CON_DOC, CON_PANEL } from './consultas.ts';
import {
  CONSTANCIA_NEGADA,
  CONSTANCIA_QUE_PROCEDE,
  CON_CAMPANIA,
  FICHA,
  OTRA_FICHA,
  SIN_CAMPANIA,
} from './consultasDeMuestra.ts';

/**
 * **Lo que las dos hojas de Consultas sacan de su respuesta** (#169).
 *
 * <h2>Lo que se comprueba no es que el mapeo «funcione»</h2>
 *
 * Es que lo que se ve sea **el dato que llego** y no el de la definicion. Las cifras del artboard
 * —`S/ 3,041.92`, `S/ 413.32`, `S/ 3,563.24`— no viajan en el paquete desde #97, asi que un
 * conector que no leyera nada dejaria la pantalla en huecos y no en cifras falsas; lo que si puede
 * pasar, y es lo que estas pruebas cazan, es que un campo se quede leyendo **otro** campo de la
 * respuesta —`total` donde va `insoluto`— o que se quede sin decidir. Por eso cada caso compara
 * contra un valor que **solo** puede salir de la muestra, y la segunda muestra cambia todas las
 * cifras: si algo estuviera fijado, las dos darian lo mismo.
 */

describe('`con-panel` — la cuenta corriente, de dos operaciones', () => {
  const reparto = CON_PANEL.repartir([FICHA, SIN_CAMPANIA] as never);

  it('los seis campos que la ficha publica salen de la ficha, uno a uno', () => {
    expect(reparto.valores.get(coordenada(0, 0))).toBe('SULLON VILCHEZ-JOSE RAUL');
    expect(reparto.valores.get(coordenada(0, 1))).toBe('DNI 29614026');
    expect(reparto.valores.get(coordenada(0, 2))).toBe('12/09/2026');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('S/ 3,041.92');
    expect(reparto.valores.get(coordenada(0, 5))).toBe('S/ 108.00');
    expect(reparto.valores.get(coordenada(0, 6))).toBe('S/ 3,563.24');
  });

  it('«Interes y reajuste» dice «no publicado» en vez de sumar los dos que SI llegan', () => {
    // La operacion publica `interes` (400.00) y `reajuste` (13.32) y **no** su suma. Sumarlos
    // daria 413.32 —exacto, con `sumarImportes`— y seria una sexta cifra del resumen calculada
    // aqui: el dia que el servidor y la pantalla discreparan, nadie sabria a cual mirar.
    expect(reparto.noPublicados.get(coordenada(0, 4))).toBe(NO_PUBLICADO);
    expect(
      reparto.valores.get(coordenada(0, 4)),
      'Alguien escribio un valor en «Interes y reajuste». La operacion publica `interes` y\n' +
        '`reajuste` por separado y NO su suma: la sexta cifra del resumen no se calcula aqui\n' +
        '(conectores.ts, la regla de este archivo). Lo que falta es que el servidor la publique.',
    ).toBeUndefined();
    expect([...reparto.valores.values()]).not.toContain('S/ 413.32');
  });

  it('«Beneficio vigente» dice «no publicado» mientras la simulacion llegue nula', () => {
    expect(reparto.noPublicados.get(coordenada(0, 7))).toBe(NO_PUBLICADO);
  });

  it('y lo escribe en cuanto la operacion trae campana: la rama esta, no es teoria', () => {
    const conCampania = CON_PANEL.repartir([FICHA, CON_CAMPANIA] as never);

    expect(conCampania.valores.get(coordenada(0, 7))).toBe('Amnistia tributaria 2026');
    expect(conCampania.noPublicados.has(coordenada(0, 7))).toBe(false);
  });

  it('NINGUNO de sus siete campos de solo lectura se queda sin decidir', () => {
    for (const campo of [1, 2, 3, 4, 5, 6, 7]) {
      const coord = coordenada(0, campo);
      expect(
        reparto.valores.has(coord) || reparto.noPublicados.has(coord),
        `el campo ${String(campo)} no sale de ningun sitio`,
      ).toBe(true);
    }
    // Siete de solo lectura, y el octavo es el que se teclea. Sin este centinela, un artboard con
    // otro numero de campos dejaria el bucle de arriba midiendo de menos.
    expect(PANTALLAS['con-panel'].bloques[0]?.campos).toHaveLength(8);
  });

  it('EL DATO ES EL QUE LLEGO: otra respuesta pinta otra pantalla, campo por campo', () => {
    const otro = CON_PANEL.repartir([OTRA_FICHA, SIN_CAMPANIA] as never);

    // Es el AC3. Si algun campo estuviera fijado —del artboard, de la definicion o de una
    // constante—, estos seis darian lo mismo que los seis de arriba.
    expect(otro.valores.get(coordenada(0, 0))).toBe('CASTILLO PASCUALA-MARIA ELENA');
    expect(otro.valores.get(coordenada(0, 1))).toBe('DNI 44218937');
    expect(otro.valores.get(coordenada(0, 2))).toBe('31/01/2026');
    expect(otro.valores.get(coordenada(0, 3))).toBe('S/ 500.10');
    expect(otro.valores.get(coordenada(0, 5))).toBe('S/ 10.00');
    expect(otro.valores.get(coordenada(0, 6))).toBe('S/ 591.94');
  });

  it('y no pinta ninguna tabla, porque esta hoja no tiene ninguna', () => {
    // Es el motivo por el que `GET /consultas/cuenta-corriente/{codigo}` —una pagina de asientos—
    // no puede ser la operacion de esta pantalla, y por el que la declaracion estaba mal.
    expect(reparto.filas.size).toBe(0);
    // Anotado: `PANTALLAS` es un `as const satisfies` y su bloque literal no declara `tabla`. La
    // forma comun la da el `satisfies`, que es lo que garantiza que esta anotacion no miente.
    const bloque = PANTALLAS['con-panel'].bloques[0] as Pantalla['bloques'][number];
    expect(bloque.tabla).toBeUndefined();
  });
});

describe('`con-doc` — la constancia de no adeudo', () => {
  const negada = CON_DOC.repartir(CONSTANCIA_NEGADA as never);

  it('el «Resultado» sale de `seNiega`, y lleva la fecha de corte dentro', () => {
    expect(negada.valores.get(coordenada(0, 5))).toBe(
      'Con deuda al 12/09/2026: saldría constancia de deuda',
    );
    expect(negada.valores.get(coordenada(0, 0))).toBe('00000025673');
  });

  it('la tabla sale de `obligaciones`, con sus cinco columnas en orden', () => {
    const filas = negada.filas.get(0);

    expect(filas).toHaveLength(2);
    expect(filas?.[0]).toEqual(['2024', 'Impuesto predial', '1 a 4', 'S/ 2,067.04', 'Vencida']);
    // Una sola cuota se escribe «1» y no «1 a 1», como el artboard.
    expect(filas?.[1]).toEqual(['2024', 'Patrimonio vehicular', '1', 'S/ 892.44', 'En coactiva']);
    expect(PANTALLAS['con-doc'].bloques[0]?.tabla?.columnas).toHaveLength(5);
  });

  it('el total de cada fila es el que trae la obligacion, y no la suma de sus partidas', () => {
    // `deuda.total` viene del propio `ObligacionPublica#total()`: la suma se hace en un solo sitio
    // y no es este. Que 1800.00 + 67.04 + 200.00 + 0.00 de 2067.04 es cierto y no viene al caso.
    expect(negada.filas.get(0)?.[0]?.[3]).toBe('S/ 2,067.04');
  });

  it('EL DATO ES EL QUE LLEGO: una constancia que procede cambia la pantalla entera', () => {
    const procede = CON_DOC.repartir(CONSTANCIA_QUE_PROCEDE as never);

    expect(procede.valores.get(coordenada(0, 5))).toBe(
      'Sin deuda al 31/01/2026: procede la constancia de no adeudo',
    );
    expect(procede.valores.get(coordenada(0, 0))).toBe('00000003541');
    // Tabla VACIA y no ausente: la operacion contesto y no hay nada que impida la constancia. No
    // es lo mismo que no haber preguntado.
    expect(procede.filas.get(0)).toEqual([]);
  });

  it('y su unico campo de solo lectura queda decidido', () => {
    const soloLectura = PANTALLAS['con-doc'].bloques[0]?.campos.flatMap((campo, i) =>
      campo.tipo.startsWith('r') ? [i] : [],
    );

    expect(soloLectura).toEqual([5]);
    expect(negada.valores.has(coordenada(0, 5))).toBe(true);
  });
});

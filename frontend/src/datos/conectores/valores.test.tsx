import { describe, expect, it } from 'vitest';

import { coordenada } from '@kamayuk/ui';

import { PANTALLAS } from '../../pantallas/definiciones/index.ts';
import { tonoDe } from '../../pantallas/tono.ts';
import type { Paginado, PrescripcionDeclarada } from '../lecturas.ts';
import { RUTAS } from '../lecturas.ts';
import { VAL_TIP } from './valores.ts';

/**
 * **El reloj de prescripcion, celda por celda** (#230).
 *
 * Lo que se comprueba no es que el mapeo «funcione»: es que **ninguna celda afirme algo que la
 * operacion no publica**, y que lo que si publica —la fecha en que prescribe cada ejercicio—
 * llegue tal cual en vez de recomponerse aqui. Es la pantalla donde se decide que deuda ya no se
 * puede exigir: una fecha calculada en el navegador se leeria igual que una buena.
 */

/** Una declaracion con los QUINCE campos que el contrato declara. */
function declaracion(cambios: Partial<PrescripcionDeclarada> = {}): PrescripcionDeclarada {
  return {
    id: 41,
    codContribuyente: 'PR-0001',
    contribuyente: 'CHAVEZ IPANAQUE, MARIA',
    tributo: 'PREDIAL',
    ejercicioDesde: 2021,
    ejercicioHasta: 2022,
    fechaDePresentacion: '2026-03-02',
    plazoAplicable: 'DECLARACION_PRESENTADA',
    plazo: '4 ANIOS',
    resultado: 'PROCEDE_EN_PARTE',
    nDeResolucion: 'RES-0041-2026',
    ejerciciosPrescritos: [2021],
    ejercicios: [
      { ejercicio: 2021, prescribeEl: '2025-12-31', prescrita: true },
      { ejercicio: 2022, prescribeEl: '2026-12-31', prescrita: false },
    ],
    usuario: 'jperez',
    observacion: 'Solicitud del obligado',
    ...cambios,
  };
}

function bitacora(
  ...declaraciones: readonly PrescripcionDeclarada[]
): Paginado<PrescripcionDeclarada> {
  return {
    contenido: declaraciones,
    pagina: 0,
    tamano: 20,
    totalElementos: 48,
    totalPaginas: 3,
    hayMas: true,
  };
}

const repartoDe = (respuesta: Paginado<PrescripcionDeclarada>) =>
  VAL_TIP.repartir(respuesta as never);

const filasDe = (respuesta: Paginado<PrescripcionDeclarada>) =>
  (repartoDe(respuesta).tablas?.get('reloj-de-prescripcion')?.filas ?? []).map(
    (fila) => fila.celdas,
  );

describe('`val-tip` — el reloj de prescripcion', () => {
  it('pide la bitacora entera y sin filtrar: los tres mandos no llegan todavia', () => {
    // `prescripcionesDe` —la de `coa-cost`— si lleva `?tributo=`, y esta NO: alli acota a la
    // liquidacion que se dibuja al lado, y aqui elegir un tributo seria elegir por quien mira.
    expect(RUTAS.prescripciones).toBe('/coactiva/prescripcion?tamano=20');
    expect(VAL_TIP.clave).toEqual(['val-tip', 'reloj-de-prescripcion']);
  });

  it('UNA FILA POR EJERCICIO, y las de varias declaraciones se aplanan', () => {
    const filas = filasDe(
      bitacora(
        declaracion(),
        declaracion({
          id: 42,
          contribuyente: 'SERNAQUE CORREA, LUIS',
          ejercicioDesde: 2020,
          ejercicioHasta: 2020,
          ejerciciosPrescritos: [2020],
          ejercicios: [{ ejercicio: 2020, prescribeEl: '2026-12-31', prescrita: true }],
        }),
      ),
    );

    expect(filas, 'dos declaraciones de dos y un ejercicio dan TRES filas').toHaveLength(3);
    expect(filas[0]).toEqual(['CHAVEZ IPANAQUE, MARIA', '2021', '31/12/2025', 'Prescrito']);
    expect(filas[1]).toEqual(['CHAVEZ IPANAQUE, MARIA', '2022', '31/12/2026', 'Vigente']);
    expect(filas[2]).toEqual(['SERNAQUE CORREA, LUIS', '2020', '31/12/2026', 'Prescrito']);
  });

  it('«Prescribe el» sale de `prescribeEl` y no de `plazo`, que es un TEXTO', () => {
    // El defecto que #230 midio: `plazo` vale «4 ANIOS» y la columna quiere una fecha. Si alguien
    // volviera a componerla aqui —presentacion + plazo—, esta fila daria otro dia.
    const filas = filasDe(
      bitacora(
        declaracion({
          plazo: '6 ANIOS',
          ejercicios: [{ ejercicio: 2019, prescribeEl: '2024-07-15', prescrita: true }],
        }),
      ),
    );

    expect(filas[0]?.[2], 'la fecha que el backend publica, formateada y no calculada').toBe(
      '15/07/2024',
    );
  });

  it('«Situacion» tiene DOS valores, y los dos se los reconoce una regla de insignia', () => {
    // Es lo contrario de #218, donde la insignia sobraba porque le llegaba una frase. Aqui llega
    // un booleano publicado, y las dos palabras que produce estan en las listas de `tono.ts`.
    expect(tonoDe('Prescrito'), 'un ejercicio prescrito es deuda que ya no se puede exigir').toBe(
      'mal',
    );
    expect(tonoDe('Vigente')).toBe('ok');
  });

  it('sin nombre en el padron se escribe el CODIGO, y sin ninguno de los dos una raya', () => {
    const conCodigo = filasDe(bitacora(declaracion({ contribuyente: null })));
    expect(conCodigo[0]?.[0], 'la fila sale igual: es justo la que hay que revisar').toBe('PR-0001');

    const sinNada = filasDe(
      bitacora(declaracion({ contribuyente: null, codContribuyente: null })),
    );
    expect(sinNada[0]?.[0]).toBe('—');
  });

  it('«Declaraciones» es `totalElementos`, y NO las filas dibujadas', () => {
    // La operacion pagina DECLARACIONES y la tabla dibuja EJERCICIOS: contar las filas para
    // escribir este campo diria «3» donde la bitacora tiene 48.
    const reparto = repartoDe(bitacora(declaracion(), declaracion({ id: 42 })));

    expect(reparto.valores.get(coordenada(0, 3))).toBe('48');
  });

  it('la tabla NO publica `totalElementos`: cuenta declaraciones y sus filas son ejercicios', () => {
    // Publicarlo pondria «3 de 48» sobre filas que no son lo que ese 48 cuenta. El interprete
    // cuenta las que hay y no afirma ningun total.
    expect(repartoDe(bitacora(declaracion())).tablas?.get('reloj-de-prescripcion')?.totalElementos)
      .toBeUndefined();
  });

  it('ninguna celda queda sin decidir, y ningun campo dice «no publicado»', () => {
    const reparto = repartoDe(bitacora(declaracion()));
    const soloLectura = PANTALLAS['val-tip'].bloques.flatMap((bloque, b) =>
      bloque.campos.flatMap((campo, c) => (campo.tipo.startsWith('r') ? [coordenada(b, c)] : [])),
    );

    // El unico campo de solo lectura de esta hoja es «Declaraciones», y sale con dato: si alguna
    // vez entrara otro, esta prueba lo dice en vez de dejarlo en blanco.
    expect(soloLectura.filter((donde) => !reparto.valores.has(donde))).toEqual([]);
    expect(reparto.noPublicados.size, 'no hay hueco que nombrar en esta hoja').toBe(0);
  });

  it('con la bitacora vacia no se inventa ninguna fila, y el total sigue siendo el suyo', () => {
    const reparto = repartoDe({
      contenido: [],
      pagina: 0,
      tamano: 20,
      totalElementos: 0,
      totalPaginas: 0,
      hayMas: false,
    });

    expect(reparto.tablas?.get('reloj-de-prescripcion')?.filas).toEqual([]);
    expect(reparto.valores.get(coordenada(0, 3))).toBe('0');
  });
});

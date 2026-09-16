import { describe, expect, it } from 'vitest';

import { PANTALLAS } from '../../pantallas/definiciones/index.ts';
import { tonoDe } from '../../pantallas/tono.ts';
import type { MovimientoDeLaBitacora, Paginado } from '../lecturas.ts';
import { RUTAS } from '../lecturas.ts';
import { SEG_AUD, SIN_DATO, detalleDelMovimiento } from './seguridad.ts';

/**
 * **La bitacora, columna por columna** (#181).
 *
 * Lo que se comprueba no es que el mapeo «funcione»: es que **ninguna celda diga algo que la
 * operacion no publica**. En una pantalla que se presenta cuando alguien pregunta por una baja,
 * una celda plausible y falsa es peor que una raya.
 */

/** Un movimiento con los DOCE campos que el contrato declara. */
function movimiento(cambios: Partial<MovimientoDeLaBitacora> = {}): MovimientoDeLaBitacora {
  return {
    id: 41184,
    ejercicio: 2026,
    tabla: 'recibo',
    clave: '0003-0041184',
    operacion: 'ANULACION',
    usuario: 'jcardenas',
    origenEquipo: 'PC-CAJA-02',
    origenIp: '10.0.4.12',
    fecha: '2026-08-13T14:41:12Z',
    observacion: 'Anulado por duplicado a pedido del contribuyente',
    datosAnteriores: '{"estado":"VIGENTE"}',
    datosNuevos: '{"estado":"ANULADO"}',
    ...cambios,
  };
}

function pagina(...movimientos: readonly MovimientoDeLaBitacora[]): Paginado<MovimientoDeLaBitacora> {
  return {
    contenido: movimientos,
    pagina: 0,
    tamano: 20,
    totalElementos: 84182,
    totalPaginas: 4210,
    hayMas: true,
  };
}

const filasDe = (respuesta: Paginado<MovimientoDeLaBitacora>) =>
  SEG_AUD.repartir(respuesta as never).filas.get(0) ?? [];

describe('`seg-aud` — la bitacora de auditoria', () => {
  it('declara que exige EJERCICIO, que es lo que impide que se pida sin el', () => {
    // Es la bandera entera de este issue: `useDatosDeLaHoja` la mira para no mandar la peticion, y
    // sin ella el conector pediria con el `?? 0` de su `pedir` — un 422, o peor, otro ano.
    expect(SEG_AUD.exigeEjercicio).toBe(true);
    // Y NO exige sujeto: su obligatorio no va en la ruta, que es justo lo que la hace la tercera
    // forma y no un caso mas de #169.
    expect(SEG_AUD.exigeSujeto).toBeUndefined();
  });

  it('pide la ruta de SU ejercicio, y el ejercicio es el que se le da', async () => {
    const pedidas: string[] = [];
    // Un doble de la red y no un espia del gancho: lo que puede fallar es la URL que sale, y un
    // espia del gancho se saltaria justo la construccion de la URL.
    const original = globalThis.fetch;
    globalThis.fetch = ((entrada: RequestInfo | URL) => {
      pedidas.push(String(entrada));
      return Promise.resolve(
        new Response(JSON.stringify(pagina()), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }) as typeof fetch;
    try {
      await SEG_AUD.pedir(new AbortController().signal, null, 2025);
    } finally {
      globalThis.fetch = original;
    }

    expect(pedidas[0]).toContain(RUTAS.bitacoraDe(2025));
    expect(pedidas[0]).toContain('ejercicio=2025');
    // Y no el de otro ano, que es el modo de fallo caro: 2026 estaria en verde con un literal.
    expect(pedidas[0]).not.toContain('ejercicio=2026');
  });

  it('la hoja es una TABLA con filtros: cero campos de solo lectura, seis que escriben', () => {
    // Es lo que decide que el conector llene `filas` y no `valores`. Si algun dia esta pantalla
    // ganara un campo de solo lectura, esto sale rojo y obliga a decidir de donde sale.
    const campos = PANTALLAS['seg-aud'].bloques[0]?.campos ?? [];
    expect(campos.filter((c) => c.tipo.startsWith('r'))).toHaveLength(0);
    expect(campos).toHaveLength(6);

    const reparto = SEG_AUD.repartir(pagina(movimiento()) as never);
    expect(reparto.valores.size).toBe(0);
    expect(reparto.noPublicados.size).toBe(0);
  });

  it('cada fila trae CINCO celdas, que son las cinco columnas que la definicion declara', () => {
    // Una fila con cuatro no da error: el interprete dibuja cuatro celdas y la quinta columna se
    // queda sin nada, en silencio.
    expect(PANTALLAS['seg-aud'].bloques[0]?.tabla?.columnas).toHaveLength(5);
    expect(filasDe(pagina(movimiento()))[0]).toHaveLength(5);
  });

  it('la fecha se escribe con su zona, y NO se mueve a la hora del puesto', () => {
    // `fecha` es un `Instant`, o sea UTC. Moverlo exige un `Date` —que arrastra la zona de la
    // MAQUINA— o restar cinco horas a mano; las dos pintarian una hora distinta de la publicada
    // sin que nada lo dijera, en la pantalla que responde «a que hora se anulo ese recibo».
    expect(filasDe(pagina(movimiento()))[0]?.[0]).toBe('13/08/2026 14:41 UTC');
  });

  it('«Acto» dice la palabra que la bitacora guarda, no la frase del artboard', () => {
    // El artboard escribe «Anulacion de recibo». Componerla exigiria traducir `recibo` —un nombre
    // de tabla— a un sustantivo de negocio, con una tabla de equivalencias que nadie ha publicado.
    expect(filasDe(pagina(movimiento()))[0]?.[1]).toBe('jcardenas');
    expect(filasDe(pagina(movimiento()))[0]?.[2]).toBe('ANULACION');
  });

  it('«Detalle» junta los TRES campos publicados, y ninguno se deduce', () => {
    expect(filasDe(pagina(movimiento()))[0]?.[3]).toBe(
      'recibo · 0003-0041184 · Anulado por duplicado a pedido del contribuyente',
    );
  });

  it('y un campo vacio no deja un punto medio suelto delante', () => {
    // Una celda que empieza por «· » se lee como un dato roto, y lo que pasa es que ese campo
    // vino vacio.
    expect(detalleDelMovimiento(movimiento({ tabla: '', clave: '' }))).toBe(
      'Anulado por duplicado a pedido del contribuyente',
    );
    expect(detalleDelMovimiento(movimiento({ observacion: '' }))).toBe('recibo · 0003-0041184');
  });

  it('el volcado de `datosAnteriores` y `datosNuevos` NO entra en ninguna celda', () => {
    // Llegan en la respuesta —y estan declarados a proposito— y son el registro entero antes y
    // despues. Dos JSON dentro de una celda llenan la pantalla y no dicen mas que la observacion.
    const celdas = filasDe(pagina(movimiento())).flat();
    expect(celdas.join(' ')).not.toContain('estado');
  });

  it('«Riesgo» NO se deduce del acto: va la raya, y la columna entera nombra lo que falta', () => {
    // Deducirlo seria facil —una anulacion es alta, un acceso es bajo— y seria escribir aqui una
    // clasificacion de riesgo que nadie ha aprobado, en la pantalla que se presenta ante un
    // auditor. Ninguna de las operaciones del contrato publica un riesgo.
    const filas = filasDe(pagina(movimiento(), movimiento({ operacion: 'ACCESO' })));

    expect(filas.map((f) => f[4])).toEqual([SIN_DATO, SIN_DATO]);
    // Y da igual el acto: dos actos de «riesgo» distinto en el artboard dan la misma celda.
    expect(filas[0]?.[4]).toBe(filas[1]?.[4]);
    // Ninguna celda dice una de las tres palabras del desplegable, que es como se veria la
    // deduccion si alguien la escribiera.
    for (const palabra of ['Alto', 'Medio', 'Bajo']) {
      expect(filas.flat().join(' '), palabra).not.toContain(palabra);
    }
  });

  it('y la raya de «Riesgo» es la que el artboard ya usa, con el tono que no afirma nada', () => {
    // La columna 4 es la de insignia, asi que la celda se dibuja como insignia y su tono sale del
    // TEXTO. La raya no cae en ninguno de los dos grupos que piden accion: no pinta de rojo lo que
    // no se sabe. Es la misma celda que `coa-exp` ya dibuja donde su operacion no llena.
    expect(PANTALLAS['seg-aud'].bloques[0]?.tabla?.columnaDeInsignia).toBe(4);
    expect(tonoDe(SIN_DATO)).toBe('ok');
  });

  it('una bitacora vacia da una tabla vacia, no una fila inventada', () => {
    expect(filasDe(pagina())).toEqual([]);
  });
});

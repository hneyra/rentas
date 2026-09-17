import { describe, expect, it } from 'vitest';

import { PANTALLAS } from '../../pantallas/definiciones/index.ts';
import { tonoDe } from '../../pantallas/tono.ts';
import type { MovimientoDeLaBitacora, Paginado } from '../lecturas.ts';
import { RUTAS } from '../lecturas.ts';
import { SEG_AUD, SIN_RIESGO_PUBLICADO, detalleDelMovimiento } from './seguridad.ts';

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

/**
 * Las celdas de cada fila de «Movimientos».
 *
 * **Salen de `tablas` y no de `filas` desde #187**: esta tabla lleva `clave`, que es el unico
 * camino cuyas celdas pueden decir que no hay dato —y por que—. Por `filas` la celda es una cadena
 * y «Riesgo» solo podia ser una raya muda.
 */
const filasDe = (respuesta: Paginado<MovimientoDeLaBitacora>) =>
  (SEG_AUD.repartir(respuesta as never).tablas?.get('movimientos')?.filas ?? []).map(
    (fila) => fila.celdas,
  );

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
      await SEG_AUD.pedir({
        senal: new AbortController().signal,
        sujeto: null,
        ejercicio: 2025,
        enLaRuta: {},
      });
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

    // Y desde #187 la celda ademas dice POR QUE: `texto: null` es «aqui no hay dato» —la palabra
    // la pone la tabla en su `sinDato`, traducida— y la nota viaja con la celda hasta el `title`.
    // Con la raya suelta, el motivo vivia solo en el javadoc de este conector.
    expect(filas.map((f) => f[4])).toEqual([
      { texto: null, nota: SIN_RIESGO_PUBLICADO },
      { texto: null, nota: SIN_RIESGO_PUBLICADO },
    ]);
    // Y da igual el acto: dos actos de «riesgo» distinto en el artboard dan la misma celda.
    expect(filas[0]?.[4]).toEqual(filas[1]?.[4]);
    // Ninguna celda dice una de las tres palabras del desplegable, que es como se veria la
    // deduccion si alguien la escribiera.
    for (const palabra of ['Alto', 'Medio', 'Bajo']) {
  expect(JSON.stringify(filas), palabra).not.toContain(palabra);
    }
  });

  it('y la raya de «Riesgo» es la que el artboard ya usa, y la declara la TABLA', () => {
    // La columna 4 es la de insignia. Con la celda sin dato el interprete NO dibuja insignia: pinta
    // la palabra de `sinDato` con su `title` (`TablaDelBloque.tsx`), o sea que no pinta de ningun
    // color lo que no se sabe — que es mejor que el tono `info` que la raya tenia como cadena.
    expect(PANTALLAS['seg-aud'].bloques[0]?.tabla?.columnaDeInsignia).toBe(4);
    expect(PANTALLAS['seg-aud'].bloques[0]?.tabla?.sinDato?.texto).toBe('—');
    // Y la raya, si alguna vez se pinta como texto, sigue sin afirmar nada (#175).
    expect(tonoDe('—')).toBe('info');
  });

  it('entrega el TOTAL publicado y el `hayMas` del SERVIDOR, no cuentas de la pagina (#172, #187)', () => {
    const reparto = SEG_AUD.repartir(pagina(movimiento()) as never);

    // 84 182 movimientos: la pagina trae uno. Si esto fuera `contenido.length`, la pantalla diria
    // «1 de 1» sobre la bitacora entera.
    expect(reparto.tablas?.get('movimientos')?.totalElementos).toBe(84182);
    expect(reparto.nombrados?.get('movimientos.hayMas')).toBe(true);
    expect(reparto.nombrados?.get('movimientos.paginas')).toBe('4210');
  });

  it('la ventana sale de la RUTA, y un `ordenarPor` que la definicion no ofrece NO viaja', async () => {
    // La ruta la escribe cualquiera. Reenviar lo que traiga seria un 422 ORDEN_NO_ADMITIDO
    // dibujado como una averia de la pantalla.
    const pedidas: string[] = [];
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
      await SEG_AUD.pedir({
        senal: new AbortController().signal,
        sujeto: null,
        ejercicio: 2026,
        enLaRuta: { pagina: '3', ordenarPor: 'riesgo', direccion: 'DESCENDENTE' },
      });
    } finally {
      globalThis.fetch = original;
    }

    expect(pedidas[0]).toContain('pagina=3');
    expect(pedidas[0]).toContain('tamano=20');
    expect(pedidas[0]).not.toContain('ordenarPor');
    // Y el sentido sin campo tampoco: solo acompana a uno admitido.
    expect(pedidas[0]).not.toContain('direccion');
  });

  it('una bitacora vacia da una tabla vacia, no una fila inventada', () => {
    expect(filasDe(pagina())).toEqual([]);
  });
});

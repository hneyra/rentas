import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada, resolverInsignia } from '@kamayuk/ui';

import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import { PANTALLAS, pantallaDe } from '../../pantallas/definiciones/index.ts';
import { TONO_SIN_RECONOCER, tonoDe } from '../../pantallas/tono.ts';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';
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

/**
 * La segunda declaracion de la prueba del aplanado. Hasta #388 decia `prescribeEl: '2026-12-31'`
 * con `prescrita: true` y la presentacion del 02/03/2026: un estado imposible, porque
 * `ComputoDePrescripcion` resuelve `prescrita` como «la presentacion no es anterior al
 * vencimiento». Con `prescrita`, `prescribeEl` va ANTES de la presentacion.
 */
const SERNAQUE_2020 = declaracion({
  id: 42,
  contribuyente: 'SERNAQUE CORREA, LUIS',
  ejercicioDesde: 2020,
  ejercicioHasta: 2020,
  ejerciciosPrescritos: [2020],
  ejercicios: [{ ejercicio: 2020, prescribeEl: '2024-12-31', prescrita: true }],
});

/**
 * **El escenario del issue**: una solicitud presentada el 15/06/2025 sobre 2020 y 2021, las dos
 * resueltas como no prescritas A ESA FECHA. El reloj de 2020 vencia el 31/12/2025 —antes de hoy—,
 * y el de 2021 el 31/12/2027. Es la siembra que distingue: presentacion < `prescribeEl` < hoy.
 */
const PRESENTADA_ANTES_DE_VENCER = declaracion({
  id: 77,
  contribuyente: 'YARLEQUE NIZAMA, ROSA',
  ejercicioDesde: 2020,
  ejercicioHasta: 2021,
  fechaDePresentacion: '2025-06-15',
  resultado: 'NO_PROCEDE',
  ejerciciosPrescritos: [],
  ejercicios: [
    { ejercicio: 2020, prescribeEl: '2025-12-31', prescrita: false },
    { ejercicio: 2021, prescribeEl: '2027-12-31', prescrita: false },
  ],
});

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
        SERNAQUE_2020,
      ),
    );

    expect(filas, 'dos declaraciones de dos y un ejercicio dan TRES filas').toHaveLength(3);
    expect(filas[0]).toEqual(['CHAVEZ IPANAQUE, MARIA', '2021', '31/12/2025', '02/03/2026', 'Prescrito']);
    expect(filas[1]).toEqual(['CHAVEZ IPANAQUE, MARIA', '2022', '31/12/2026', '02/03/2026', 'Vigente']);
    expect(filas[2]).toEqual(['SERNAQUE CORREA, LUIS', '2020', '31/12/2024', '02/03/2026', 'Prescrito']);
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

  it('«Situacion al presentar» tiene DOS valores, y el tono lo decide la regla de SU columna (#388)', () => {
    // Es lo contrario de #218, donde la insignia sobraba porque le llegaba una frase. Aqui llega
    // un booleano publicado. Hasta #388 el tono salia de las listas de `tono.ts`, y «Vigente» —que
    // alli es CONFORME por el padron de licencias— se pintaba de verde sobre una situacion que es
    // la del dia de la solicitud, no la de hoy. Ahora lo decide la columna.
    const columna = PANTALLAS['val-tip'].bloques[0]?.tabla?.columnas[4];
    const regla = columna?.insignia;
    expect(regla, 'la columna de la situacion perdio su regla de insignia').toBeDefined();
    if (regla === undefined) return;
    const tono = (texto: string) => resolverInsignia(regla, texto, undefined, (t) => t)?.tono;

    expect(tono('Prescrito'), 'un ejercicio prescrito es deuda que ya no se puede exigir').toBe('mal');
    expect(tono('Vigente'), 'a la fecha de la solicitud, no hoy: el tono de «no se»').toBe(
      TONO_SIN_RECONOCER,
    );
    // Y el vocabulario global NO se toco: el `VIGENTE` del padron de licencias sigue siendo verde.
    expect(tonoDe('Vigente')).toBe('ok');
  });

  it('las muestras de este archivo guardan la relacion que el backend impone entre las tres cifras', () => {
    // `ComputoDePrescripcion`: `prescrita = !fechaDeResolucion.isBefore(vencimiento)`. Una
    // muestra que la viole —la de 2020 hasta #388— prueba un estado que el sistema no produce.
    const muestras = [
      declaracion(),
      PRESENTADA_ANTES_DE_VENCER,
      SERNAQUE_2020,
    ];
    for (const muestra of muestras) {
      for (const reloj of muestra.ejercicios) {
        expect(
          reloj.prescrita,
          `${String(muestra.id)} · ${String(reloj.ejercicio)}: presentada ${muestra.fechaDePresentacion}, prescribe ${reloj.prescribeEl}`,
        ).toBe(muestra.fechaDePresentacion >= reloj.prescribeEl);
      }
    }
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

/* ── #388: la situacion es la del dia de la solicitud, y la fila tiene que decirlo ─────────── */

/** La hoja tal como la monta la aplicacion, con un cliente de consultas propio por montaje. */
function arnes() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
}

function ValTipConectada() {
  return <PantallaDeRentas definicion={pantallaDe('val-tip')} datos={useDatosDeLaHoja('val-tip')} />;
}

/** Monta `val-tip` con esa bitacora como respuesta de `fetch`, y espera a que deje de pedir. */
async function pintar(respuesta: Paginado<PrescripcionDeclarada>) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response(JSON.stringify(respuesta), {
          status: 200,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    ),
  );
  const { container } = render(<ValTipConectada />, { wrapper: arnes() });
  await waitFor(() => {
    expect(screen.queryByText(/pidiendo/i)).toBeNull();
  });
  return container;
}

/** El tono con que el interprete PINTO una insignia, leido de sus clases: lo que se ve. */
function tonoPintado(insignia: Element | null | undefined): string | undefined {
  const clase = insignia?.getAttribute('class') ?? '';
  return /\bbg-(ok|atencion|mal|info)-fondo\b/.exec(clase)?.[1];
}

describe('`val-tip` — la situacion es la de la PRESENTACION, y lo dice (#388)', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('la fila lleva la fecha de la solicitud: «Presentada el», junto a «Situacion al presentar»', () => {
    // `prescrita` vale lo que valia a `fechaDePresentacion` (`PrescripcionEnListaResource`), y
    // hasta #388 esa fecha llegaba en cada declaracion y ninguna celda la escribia: la fila de
    // 2020 decia «31/12/2025 · Vigente» sin decir de que dia es ese «Vigente» (regla 9).
    expect(PANTALLAS['val-tip'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Contribuyente',
      'Ejercicio',
      'Prescribe el',
      'Presentada el',
      'Situación al presentar',
    ]);
    const filas = filasDe(bitacora(PRESENTADA_ANTES_DE_VENCER));
    expect(filas).toEqual([
      ['YARLEQUE NIZAMA, ROSA', '2020', '31/12/2025', '15/06/2025', 'Vigente'],
      ['YARLEQUE NIZAMA, ROSA', '2021', '31/12/2027', '15/06/2025', 'Vigente'],
    ]);
  });

  /** La fila de 2020 tal como se DIBUJO: la localiza su «Prescribe el», que es unico. */
  async function filaDe2020() {
    const container = await pintar(bitacora(PRESENTADA_ANTES_DE_VENCER));
    const fila = [...container.querySelectorAll('tr')].find((tr) =>
      tr.textContent.includes('31/12/2025'),
    );
    expect(fila, 'la fila de 2020 no se dibujo').toBeDefined();
    return fila;
  }

  it('LO QUE SE VE (1): la fila de 2020 dice de que dia es su situacion — 15/06/2025', async () => {
    const fila = await filaDe2020();
    expect(fila?.textContent, 'la fila no dice de que dia es su situacion').toContain('15/06/2025');
  });

  it('LO QUE SE VE (2): su «Vigente» NO sale en el verde de conforme', async () => {
    const fila = await filaDe2020();
    const insignia = [...(fila?.querySelectorAll('span') ?? [])].find(
      (span) => span.textContent === 'Vigente',
    );
    expect(tonoPintado(insignia), 'la insignia de «Vigente» no se pinto').toBeDefined();
    expect(
      tonoPintado(insignia),
      '«Vigente» es lo que se resolvio el 15/06/2025, no hoy: el verde de conforme sobre un\n' +
        '  ejercicio cuyo «Prescribe el» ya paso invita a seguir cobrando una deuda sin accion.',
    ).not.toBe('ok');
  });

  it('y un «Prescrito» se sigue pintando de rojo: la regla de la columna no apaga el juicio', async () => {
    const container = await pintar(bitacora(declaracion()));
    const insignia = [...container.querySelectorAll('span')].find(
      (span) => span.textContent === 'Prescrito',
    );
    expect(tonoPintado(insignia)).toBe('mal');
  });
});

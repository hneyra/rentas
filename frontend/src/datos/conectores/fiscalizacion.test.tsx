import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PANTALLAS, pantallaDe } from '../../pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';
import { coordenada } from '@kamayuk/ui';
import type { Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import { sinDato as sinDatoDeCoactiva } from './coactiva.ts';
import { SIN_CIFRAR as SIN_CIFRAR_DE_INICIO } from './inicio.ts';
import {
  FIS_ACTAS,
  FIS_PROG,
  FIS_RES,
  SIN_AREA_HALLADA,
  SIN_BASE_OMITIDA,
  SIN_CIFRAR,
  SIN_DECLARADO,
  SIN_DIFERENCIA_DEL_ACTA,
  SIN_DIFERENCIA_ESTIMADA,
  SIN_HALLAZGO,
  SIN_INTERES,
  SIN_TITULAR,
  contrasteDelActa,
  sinDato,
} from './fiscalizacion.ts';
import {
  ACTAS,
  ACTA_CON_USO,
  ACTA_SIN_USO,
  MUESTRA,
  PROGRAMAS,
  RESOLUCION_CIFRADA,
  RESOLUCION_SIN_CIFRAS,
  SIN_ACTAS,
  SIN_PROGRAMAS,
} from './fiscalizacionDeMuestra.ts';

/**
 * **Que las tres hojas de Fiscalizacion ensenen lo que LLEGO** (#179).
 *
 * <h2>Por que la mitad de este archivo MONTA la pantalla</h2>
 *
 * Por lo mismo que en #168 y #170: `repartir` se puede comprobar entero y aun asi la pantalla
 * puede estar **pareciendo** conectada. La unica forma de distinguirlo es cambiar la respuesta del
 * doble y mirar el DOM — si con dos respuestas distintas se lee lo mismo, no esta conectada.
 *
 * <h2>Y lo que este modulo obliga a comprobar y los otros no</h2>
 *
 * **Que «no publicado», «—» y «sin cifrar» no se confundan.** Son tres cosas y cuestan tres
 * arreglos distintos: publicar el campo, publicar la columna, y cerrar D-02a. Una pantalla que las
 * dijera con la misma palabra mandaria a quien mantiene el backend a buscar lo que ya esta.
 */

/** Un cliente por prueba: compartido, la respuesta de una se quedaria en la cache de la otra. */
function arnes() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
}

/** La pantalla tal como la monta la aplicacion. `sujeto` solo lo lleva la hoja que lo exige. */
function PantallaConectada({
  clave,
  sujeto = null,
}: {
  readonly clave: ClaveDeHoja;
  readonly sujeto?: string | null;
}) {
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave, { sujeto, parametros: {} })} />;
}

/**
 * Sustituye `fetch` por un doble que contesta **segun la ruta**.
 *
 * Hace falta y no vale el doble de una sola respuesta: `fis-prog` pide DOS operaciones seguidas y
 * la segunda no se puede pedir sin la primera. Un doble que contestara lo mismo a las dos daria
 * una muestra donde va la relacion de programas y lo taparia.
 */
function contestaPorRuta(rutas: Readonly<Record<string, unknown>>) {
  const doble = vi.fn<typeof fetch>((entrada) => {
    const url = String(entrada);
    const encaje = Object.keys(rutas).find((trozo) => url.includes(trozo));
    return Promise.resolve(
      new Response(JSON.stringify(encaje === undefined ? {} : rutas[encaje]), {
        status: encaje === undefined ? 404 : 200,
        headers: { 'content-type': 'application/json' },
      }),
    );
  });
  vi.stubGlobal('fetch', doble);
  return doble;
}

/** Monta la hoja con esas respuestas y espera a que deje de pedir. */
async function pintar(
  clave: ClaveDeHoja,
  rutas: Readonly<Record<string, unknown>>,
  sujeto: string | null = null,
) {
  const doble = contestaPorRuta(rutas);
  const { container } = render(<PantallaConectada clave={clave} sujeto={sujeto} />, {
    wrapper: arnes(),
  });
  await waitFor(() => {
    expect(screen.queryByText(/pidiendo/i)).toBeNull();
  });
  return { container, doble };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * Las celdas de cada fila de una tabla **con `clave`**.
 *
 * Las tres tablas de este modulo pasaron a ese camino en #195: por `filas` —el del indice de
 * bloque— la celda es una cadena, y una cadena no puede decir que no hay dato ni por que.
 */
const celdasDe = (reparto: Reparto, clave: string) =>
  (reparto.tablas?.get(clave)?.filas ?? []).map((fila) => fila.celdas);

describe('las tres formas del hueco no se confunden', () => {
  it('«no publicado», la celda sin dato y «sin cifrar» son TRES cosas distintas', () => {
    // Son tres causas con tres arreglos distintos —publicar el campo, publicar la columna, cerrar
    // D-02a— y una pantalla que las dijera igual mandaria a quien mantiene el backend a buscar lo
    // que ya esta publicado. Desde #195 la del medio ya no es una cadena: es `{ texto: null }`, y
    // eso la separa de las otras dos **en el tipo** y no solo en el valor.
    expect(sinDato('lo que sea')).toEqual({ texto: null, nota: 'lo que sea' });
    expect(new Set([NO_PUBLICADO, SIN_CIFRAR]).size).toBe(2);
  });

  it('y la celda sin dato es la MISMA forma que escribe Coactiva', () => {
    // Estan escritas dos veces a proposito —un conector no depende de otro modulo para una
    // palabra—, asi que lo que impide que se separen es esto y no el compilador.
    expect(sinDato('mismo motivo')).toEqual(sinDatoDeCoactiva('mismo motivo'));
    expect(SIN_CIFRAR).toBe(SIN_CIFRAR_DE_INICIO);
  });

  it('ninguna dice un cero ni una cifra', () => {
    for (const palabra of [NO_PUBLICADO, SIN_CIFRAR]) {
      expect(palabra, `«${palabra}» lleva un digito`).not.toMatch(/\d/);
    }
  });

  it('y los OCHO motivos de este modulo son ocho frases distintas, no una raya repetida', () => {
    // Es lo que #195 compra: hasta entonces las ocho celdas vacias de estas tres tablas decian la
    // misma raya, y sus seis motivos —que existian y estaban escritos— vivian solo en el javadoc
    // de este conector, donde no los lee quien mira la pantalla.
    const motivos = [
      SIN_TITULAR,
      SIN_DIFERENCIA_ESTIMADA,
      SIN_DECLARADO,
      SIN_DIFERENCIA_DEL_ACTA,
      SIN_HALLAZGO,
      SIN_AREA_HALLADA,
      SIN_BASE_OMITIDA,
      SIN_INTERES,
    ];
    expect(new Set(motivos).size).toBe(motivos.length);
    for (const motivo of motivos) expect(motivo.length, motivo).toBeGreaterThan(40);
  });
});

describe('`fis-prog` — la muestra sorteada de un programa', () => {
  const RUTAS_DE_PROG = {
    '/fiscalizacion/programas/14/muestra': MUESTRA,
    '/fiscalizacion/programas?': PROGRAMAS,
  };

  it('pide la relacion de programas ACOTADA, y con su `id` pide la muestra', async () => {
    const { doble } = await pintar('fis-prog', RUTAS_DE_PROG);
    const urls = doble.mock.calls.map((llamada) => String(llamada[0]));

    // La primera trae el `{id}`, que es lo unico que la segunda no puede inventarse.
    expect(urls[0]).toContain('/fiscalizacion/programas?tamano=1');
    expect(urls[1]).toContain('/fiscalizacion/programas/14/muestra');
  });

  it('las cinco columnas salen en el orden de la definicion, y cuatro traen dato', () => {
    const filas = celdasDe(FIS_PROG.repartir(MUESTRA as never), 'muestra-del-programa');

    expect(PANTALLAS['fis-prog'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Código predial',
      'Contribuyente',
      'Causa del cruce',
      'Diferencia estimada S/',
      'Estado',
    ]);
    expect(filas).toEqual([
      [
        '02-014-D-14-01',
        'Suc. Rufina Medina Medina',
        'SUBVALUADOR',
        sinDato(SIN_DIFERENCIA_ESTIMADA),
        'Inspeccionado',
      ],
      // Sin titular vigente: la celda dice que no hay dato **y por que**, y NO una palabra que
      // afirme que el predio no tiene dueno.
      [
        '04-021-B-07-00',
        sinDato(SIN_TITULAR),
        'OMISO',
        sinDato(SIN_DIFERENCIA_ESTIMADA),
        'Programado',
      ],
    ]);
  });

  it('«Diferencia estimada S/» dice la raya: la muestra no publica UN SOLO importe', () => {
    // Las tres magnitudes que publica son areas en m² —`areaCatastral`, `areaDeclarada` y
    // `diferenciaDeArea`—, y ninguna es dinero. Escribir `33.50` bajo un rotulo que dice «S/»
    // seria ensenar metros como soles.
    const filas = celdasDe(FIS_PROG.repartir(MUESTRA as never), 'muestra-del-programa');
    for (const fila of filas) expect(fila[3]).toEqual(sinDato(SIN_DIFERENCIA_ESTIMADA));
    expect(JSON.stringify(filas)).not.toContain('33.50');
  });

  it('no decide ningun campo: los ocho del bloque son mandos, no cifras', () => {
    const reparto = FIS_PROG.repartir(MUESTRA as never);

    expect(reparto.valores.size).toBe(0);
    expect(reparto.noPublicados.size).toBe(0);
    expect(PANTALLAS['fis-prog'].bloques[0]?.campos.filter((c) => c.tipo.startsWith('r'))).toEqual(
      [],
    );
  });

  it('sin ningun programa no pide la muestra, y la pantalla dice «sin datos»', async () => {
    const { container, doble } = await pintar('fis-prog', {
      '/fiscalizacion/programas?': SIN_PROGRAMAS,
    });

    expect(doble.mock.calls.map((l) => String(l[0])).some((u) => u.includes('/muestra'))).toBe(
      false,
    );
    expect(container.textContent).toContain('sin datos');
  });

  it('LA ROTURA DEL AC3: con otra muestra, la pantalla ensena otra cosa', async () => {
    const { container } = await pintar('fis-prog', RUTAS_DE_PROG);
    expect(container.textContent).toContain('02-014-D-14-01');

    const otra = {
      ...MUESTRA,
      contenido: [{ ...MUESTRA.contenido[0], codRefCatastral: '07-001-Z-99-00' }],
    };
    const segunda = await pintar('fis-prog', {
      '/fiscalizacion/programas/14/muestra': otra,
      '/fiscalizacion/programas?': PROGRAMAS,
    });

    expect(segunda.container.textContent).toContain('07-001-Z-99-00');
    expect(segunda.container.textContent).not.toContain('02-014-D-14-01');
  });
});

describe('`fis-actas` — el contraste de un acta de inspeccion', () => {
  it('pide UNA acta: la tabla contrasta conceptos, no es una relacion de actas', async () => {
    const { doble } = await pintar('fis-actas', { '/fiscalizacion/actas': ACTAS });

    expect(String(doble.mock.calls[0]?.[0])).toContain('/fiscalizacion/actas?tamano=1');
  });

  it('y NO exige sujeto, al contrario que `fis-res`: hay una primera acta que tomar', () => {
    // Es la diferencia que decide como se pide cada una de las tres. Aqui y en `fis-prog` existe
    // una relacion de la que tomar la primera —como `coa-exp`—; de resoluciones **no existe
    // ninguna**, y por eso esa hoja es la unica de las tres que espera un sujeto.
    expect(FIS_ACTAS.exigeSujeto).toBeUndefined();
    expect(FIS_PROG.exigeSujeto).toBeUndefined();
  });

  it('el lado DECLARADO y la diferencia dicen la raya: el acta no los publica', () => {
    const filas = contrasteDelActa(ACTA_CON_USO);

    expect(PANTALLAS['fis-actas'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Concepto',
      'Declarado',
      'Verificado',
      'Diferencia',
      'Situación',
    ]);
    expect(filas).toEqual([
      [
        'Área hallada (m²)',
        sinDato(SIN_DECLARADO),
        '198.00',
        sinDato(SIN_DIFERENCIA_DEL_ACTA),
        'SUBVALUADOR',
      ],
      [
        'Uso del predio',
        sinDato(SIN_DECLARADO),
        'COMERCIO',
        sinDato(SIN_DIFERENCIA_DEL_ACTA),
        'SUBVALUADOR',
      ],
    ]);
  });

  it('y la diferencia NO se resta de lo que llego: son dos magnitudes, no una cuenta', () => {
    // El acta ni siquiera publica el minuendo. Y aunque lo publicara: restar dos importes o dos
    // areas servidas para llenar una celda es calcular lo que nadie publico.
    const filas = contrasteDelActa(ACTA_CON_USO);
    for (const fila of filas) expect(fila[3]).toEqual(sinDato(SIN_DIFERENCIA_DEL_ACTA));
  });

  it('sin uso anotado, la fila del uso NO sale: nulo es «no se anoto»', () => {
    // El backend lo dice expresamente: `usoHallado` nulo **no** es «coincide con lo declarado».
    // Una fila con cuatro rayas de cinco no informaria de eso.
    const filas = contrasteDelActa(ACTA_SIN_USO);

    expect(filas).toHaveLength(1);
    expect(filas[0]?.[0]).toBe('Área hallada (m²)');
    // Sin hallazgo anotado, la situacion tambien dice que no hay dato — nunca «Conforme».
    expect(filas[0]?.[4]).toEqual(sinDato(SIN_HALLAZGO));
  });

  it('la «Situacion» es el HALLAZGO y no el estado del papel', () => {
    // `estado` es `ABIERTA`/`LIQUIDADA`/`ANULADA`: en que punto esta el acta, no que se encontro
    // en el predio. Pintarlo en esa columna diria que el predio esta «Abierta».
    const filas = contrasteDelActa(ACTA_CON_USO);

    expect(filas.map((f) => f[4])).toEqual(['SUBVALUADOR', 'SUBVALUADOR']);
    expect(JSON.stringify(filas)).not.toContain(ACTA_CON_USO.estado);
  });

  it('sin ninguna acta, la pantalla dice «sin datos» en vez de una tabla vacia', async () => {
    const { container } = await pintar('fis-actas', { '/fiscalizacion/actas': SIN_ACTAS });

    expect(container.textContent).toContain('sin datos');
  });

  it('LA ROTURA DEL AC3: con otra acta, la pantalla ensena otra cosa', async () => {
    const { container } = await pintar('fis-actas', { '/fiscalizacion/actas': ACTAS });
    expect(container.textContent).toContain('198.00');

    const otra = { ...ACTAS, contenido: [{ ...ACTA_CON_USO, areaHallada: '412.75' }] };
    const segunda = await pintar('fis-actas', { '/fiscalizacion/actas': otra });

    expect(segunda.container.textContent).toContain('412.75');
    expect(segunda.container.textContent).not.toContain('198.00');
  });
});

describe('`fis-res` — la resolucion de determinacion', () => {
  const NUMERO = 'RDF-2026-000001';
  const RUTA = { '/fiscalizacion/resoluciones/': RESOLUCION_SIN_CIFRAS };

  it('EXIGE sujeto, y el numero viaja en la RUTA', async () => {
    const { doble } = await pintar('fis-res', RUTA, NUMERO);

    expect(FIS_RES.exigeSujeto).toBe(true);
    expect(String(doble.mock.calls[0]?.[0])).toContain(`/fiscalizacion/resoluciones/${NUMERO}`);
    // Sin `?formato=`: con el, la misma ruta contesta el PDF y no el JSON que se pinta.
    expect(String(doble.mock.calls[0]?.[0])).not.toContain('formato');
  });

  it('y sin numero no pide NADA: lo dice en vez de inventarse uno', async () => {
    const { container, doble } = await pintar('fis-res', RUTA, null);

    expect(doble.mock.calls.map((l) => String(l[0]))).toEqual([]);
    expect(container.textContent).toContain('falta el contribuyente');
  });

  it('de los SEIS campos sale uno, y los otros cinco dicen «no publicado»', () => {
    const reparto = FIS_RES.repartir(RESOLUCION_SIN_CIFRAS as never);

    expect(reparto.valores.get(coordenada(0, 1))).toBe('Suc. Rufina Medina Medina');
    expect([...reparto.noPublicados.keys()].sort()).toEqual(
      [coordenada(0, 0), coordenada(0, 3), coordenada(0, 4), coordenada(0, 5), coordenada(0, 7)]
        .slice()
        .sort(),
    );
  });

  it('NO suma la tabla para llenar «Total liquidado», «Insoluto omitido» ni «Multa»', () => {
    // Es la regla que este archivo no negocia. Con la resolucion cifrada la suma saldria al
    // centimo —290.20— y seria indistinguible de una liquidada de verdad, sobre el papel que
    // vuelve una diferencia deuda exigible.
    const reparto = FIS_RES.repartir(RESOLUCION_CIFRADA as never);
    const escritos = [...reparto.valores.values()];

    for (const campo of [coordenada(0, 3), coordenada(0, 5), coordenada(0, 7)]) {
      expect(
        reparto.valores.has(campo),
        `El campo ${campo} de «fis-res» trae un valor, y la operacion no publica ningun total.\n` +
          'Sumarlo aqui pone una cifra al centimo en el papel que vuelve una diferencia deuda\n' +
          'exigible, y nadie podria distinguirla de una liquidada de verdad.',
      ).toBe(false);
      expect(reparto.noPublicados.get(campo)).toBe(NO_PUBLICADO);
    }
    expect(escritos).not.toContain('S/ 290.20');
    expect(escritos).not.toContain('290.20');
  });

  it('la tabla: «Base omitida» e «Interes» dicen su motivo, y las otras tres traen dato', () => {
    const filas = celdasDe(
      FIS_RES.repartir(RESOLUCION_CIFRADA as never),
      'detalle-por-ejercicio',
    );

    expect(PANTALLAS['fis-res'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Ejercicio',
      'Base omitida S/',
      'Insoluto S/',
      'Interés S/',
      'Total S/',
    ]);
    // «Base omitida» seria `determinado − declarado` —33 500 menos 27 400— y no se resta aqui;
    // «Interes» no lo publica NINGUNA de las dieciseis operaciones de fiscalizacion.
    expect(filas).toEqual([
      ['2024', sinDato(SIN_BASE_OMITIDA), '201.00', sinDato(SIN_INTERES), '290.20'],
    ]);
  });

  it('con los importes nulos dice «sin cifrar», que NO es la raya ni un cero (D-02a)', () => {
    const filas = celdasDe(
      FIS_RES.repartir(RESOLUCION_SIN_CIFRAS as never),
      'detalle-por-ejercicio',
    );

    expect(filas).toEqual([
      ['2024', sinDato(SIN_BASE_OMITIDA), SIN_CIFRAR, sinDato(SIN_INTERES), SIN_CIFRAR],
      ['2025', sinDato(SIN_BASE_OMITIDA), SIN_CIFRAR, sinDato(SIN_INTERES), SIN_CIFRAR],
    ]);
    // El campo existe y llego vacio: decirlo con la celda sin dato pediria publicar lo que ya esta
    // publicado, y con un `0.00` diria que no se debe nada. Son DOS huecos distintos y se ven.
    expect(JSON.stringify(filas)).not.toContain('0.00');
  });

  it('LA ROTURA DEL AC3: con otra resolucion, la pantalla ensena otro contribuyente', async () => {
    const { container } = await pintar('fis-res', RUTA, NUMERO);
    expect(container.textContent).toContain('Suc. Rufina Medina Medina');

    const otra = { ...RESOLUCION_SIN_CIFRAS, contribuyente: 'Inversiones del Norte S.A.C.' };
    const segunda = await pintar('fis-res', { '/fiscalizacion/resoluciones/': otra }, 'RDF-2026-000002');

    expect(segunda.container.textContent).toContain('Inversiones del Norte S.A.C.');
    expect(segunda.container.textContent).not.toContain('Suc. Rufina Medina Medina');
  });
});

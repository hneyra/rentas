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
  FIS_PANEL,
  FIS_PROG,
  FIS_RES,
  NO_CONSTA_LO_DECLARADO,
  SIN_AREA_HALLADA,
  SIN_CIFRAR,
  SIN_DIFERENCIA_DE_UN_USO,
  SIN_DIFERENCIA_ESTIMADA,
  SIN_HALLAZGO,
  SIN_INTERES,
  SIN_PARAMETROS_DEL_SORTEO,
  SIN_TITULAR,
  contrasteDelActa,
  sinDato,
} from './fiscalizacion.ts';
import {
  ACTAS,
  ACTA_CON_USO,
  ACTA_SIN_PADRON,
  ACTA_SIN_USO,
  ACTA_VEHICULAR,
  EMBUDO,
  EMBUDO_SIN_PARAMETROS,
  MUESTRA,
  PROGRAMAS,
  RESOLUCIONES,
  RESOLUCION_CIFRADA,
  RESOLUCION_SIN_CIFRAS,
  SIN_ACTAS,
  SIN_PROGRAMAS,
  SIN_RESOLUCIONES,
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
  parametros = {},
}: {
  readonly clave: ClaveDeHoja;
  readonly sujeto?: string | null;
  /** Lo que la hoja lleva en su ruta: la pagina y el orden que el interprete escribe (#228). */
  readonly parametros?: Readonly<Record<string, string>>;
}) {
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave, { sujeto, parametros })} />;
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
  parametros: Readonly<Record<string, string>> = {},
) {
  const doble = contestaPorRuta(rutas);
  const { container } = render(
    <PantallaConectada clave={clave} sujeto={sujeto} parametros={parametros} />,
    { wrapper: arnes() },
  );
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

  it('y los SIETE motivos de este modulo son siete frases distintas, no una raya repetida', () => {
    // Es lo que #195 compra: hasta entonces las celdas vacias de estas tablas decian la misma raya,
    // y sus motivos —que existian y estaban escritos— vivian solo en el javadoc de este conector,
    // donde no los lee quien mira la pantalla.
    //
    // **Siguen siendo ocho y no son los mismos ocho** (#215). Salen dos, porque el backend publico
    // lo que faltaba: `SIN_DECLARADO` —el acta ya publica su lado declarado (#191)— y
    // `SIN_BASE_OMITIDA` —la resolucion ya la resta (#193)—. Entran dos que la ola nueva hace
    // visibles: `NO_CONSTA_LO_DECLARADO`, que es «no consta» y no «no publicado», y
    // `SIN_DIFERENCIA_DE_UN_USO`, que se queda **con todo publicado** porque la diferencia de un
    // uso no es un numero. Y `SIN_PARAMETROS_DEL_SORTEO` es de `fis-panel`, que no existia.
    //
    // **Y desde #241 son SIETE.** Sale `SIN_ACTA_CERRADA`, que no lo retira una operacion nueva
    // sino el ROTULO: aquella celda no estaba vacia porque faltara el dato —`conActa` estaba
    // publicado desde #217—, sino porque decia «Con acta cerrada» y `conActa` es otra cosa. Con el
    // rotulo corregido a «Con acta levantada» la celda se llena con lo que siempre hubo.
    const motivos = [
      SIN_TITULAR,
      SIN_DIFERENCIA_ESTIMADA,
      NO_CONSTA_LO_DECLARADO,
      SIN_DIFERENCIA_DE_UN_USO,
      SIN_HALLAZGO,
      SIN_AREA_HALLADA,
      SIN_INTERES,
          SIN_PARAMETROS_DEL_SORTEO,
    ];
    expect(new Set(motivos).size).toBe(motivos.length);
    for (const motivo of motivos) expect(motivo.length, motivo).toBeGreaterThan(40);
  });
});

describe('`fis-panel` — el embudo del programa (#196)', () => {
  const RUTAS_DE_PANEL = {
    '/fiscalizacion/programas/14/embudo': EMBUDO,
    '/fiscalizacion/programas?': PROGRAMAS,
  };

  it('pide la relacion ACOTADA, y con su `id` pide el embudo: UNA lectura y no cuatro', async () => {
    const { doble } = await pintar('fis-panel', RUTAS_DE_PANEL);
    const urls = doble.mock.calls.map((llamada) => String(llamada[0]));

    expect(urls[0]).toContain('/fiscalizacion/programas?tamano=1');
    expect(urls[1]).toContain('/fiscalizacion/programas/14/embudo');
    // Y NO se compone con el `totalElementos` de cuatro operaciones, que es lo que
    // `conectores.ts` prohibe: serian cuatro numeros que ninguna operacion afirma que signifiquen
    // las cuatro etapas, y tres habria que acotarlas a un programa que la pantalla no elige.
    expect(urls).toHaveLength(2);
    expect(urls.some((u) => u.includes('/omisos'))).toBe(false);
    expect(urls.some((u) => u.includes('/resultados'))).toBe(false);
  });

  it('las CUATRO cifras que la operacion publica salen, y ninguna celda queda sin llenar', () => {
    const reparto = FIS_PANEL.repartir(EMBUDO as never);

    expect(reparto.valores.get(coordenada(0, 0))).toBe('2026');
    expect(reparto.valores.get(coordenada(0, 1))).toBe('PF-2026-014');
    expect(reparto.valores.get(coordenada(0, 2))).toBe('3418');
    expect(reparto.valores.get(coordenada(0, 3))).toBe('96');
    expect(reparto.valores.get(coordenada(0, 4))).toBe('84');
    expect(reparto.valores.get(coordenada(0, 5))).toBe('61');
    // Un embudo con todos sus parametros no deja ni un hueco: es la diferencia que #241 compra.
    expect(reparto.noPublicados.size).toBe(0);
  });

  it('«Con acta levantada» SE llena con `conActa`, y el rotulo es el que se corrigio (#241)', () => {
    // Lo contrario de lo que esta prueba exigia hasta #241, y el cambio no esta en el dato:
    // `conActa` se publica desde #217. Lo que cambio es el ROTULO. Aquella celda decia «Con acta
    // cerrada», y «cerrada» no es un estado de un acta en este sistema —`EstadoDeActa` declara
    // ABIERTA y ANULADA desde #214, y la unica transicion escrita es anular—, asi que pintar ahi
    // las actas VIVAS habria dicho otra cosa.
    //
    // Lo que decidio en que direccion se corrige son DOS frases del propio artboard, no una
    // opinion sobre el rotulo:
    //
    //   1. la nota de esta misma hoja —«Lo detectado, lo INSPECCIONADO y lo que sostiene una
    //      determinacion»— nombra tres cosas para cuatro cifras, y la tercera es la inspeccion;
    //   2. la nota de `fis-actas` situaba el cierre ANTES de liquidar —«sin acta cerrada no se
    //      puede liquidar»—, que es lo contrario de lo que #214 llamo «cerrada»: que el acta TENGA
    //      liquidacion. Con aquella definicion la frase se leia «sin liquidacion no se puede
    //      liquidar», asi que las dos «cerrada» no podian ser la misma palabra.
    //
    // La cifra es la etapa que `ActasController` llama «Inspeccionados», y ahora el rotulo lo dice.
    const reparto = FIS_PANEL.repartir(EMBUDO as never);

    expect(reparto.valores.get(coordenada(0, 4))).toBe(String(EMBUDO.conActa));
    expect(reparto.noPublicados.has(coordenada(0, 4))).toBe(false);
    expect(PANTALLAS['fis-panel'].bloques[0]?.campos[4]?.etiqueta).toBe('Con acta levantada');
    // Y la palabra que prometia un cierre no vuelve por ninguna de las dos hojas.
    expect(PANTALLAS['fis-panel'].bloques[0]?.campos[4]?.etiqueta).not.toContain('cerrada');
    expect(PANTALLAS['fis-actas'].bloques[0]?.tabla?.nota).not.toContain('acta cerrada');
  });

  it('sin parametros de sorteo, «Detectados por cruce» dice su causa y NO un cero', () => {
    const reparto = FIS_PANEL.repartir(EMBUDO_SIN_PARAMETROS as never);

    expect(reparto.valores.has(coordenada(0, 2))).toBe(false);
    expect(reparto.noPublicados.get(coordenada(0, 2))).toBe(SIN_PARAMETROS_DEL_SORTEO);
    // Cero seria «el cruce no senalo a nadie», que es lo contrario de «el cruce no se pudo hacer».
    expect([...reparto.valores.values()]).not.toContain('0');
    // Y las otras tres etapas siguen saliendo: lo que falta es una, no el embudo.
    expect(reparto.valores.get(coordenada(0, 3))).toBe('96');
  });

  it('la hoja dice DE CUANDO son sus cifras, y la frase la arma quien tiene `t()` (regla 9)', async () => {
    // Las tres ultimas etapas estan congeladas y la primera se resuelve contra el padron de HOY,
    // asi que dos aperturas del mismo programa en dos dias pueden dar embudos distintos sin que
    // nada haya fallado. Lo que viaja por el conector es la fecha CRUDA: un «al» escrito en un
    // archivo de datos llegaria al DOM en castellano en cualquier idioma (#103).
    expect(FIS_PANEL.repartir(EMBUDO as never).aLaFecha).toBe('2026-09-17');

    const { container } = await pintar('fis-panel', RUTAS_DE_PANEL);
    expect(container.textContent).toContain('17/09/2026');
  });

  it('sin ningun programa no pide el embudo, y la pantalla dice «sin datos»', async () => {
    const { container, doble } = await pintar('fis-panel', {
      '/fiscalizacion/programas?': SIN_PROGRAMAS,
    });

    expect(doble.mock.calls.map((l) => String(l[0])).some((u) => u.includes('/embudo'))).toBe(false);
    expect(container.textContent).toContain('sin datos');
  });

  it('LA ROTURA DEL AC3: con otro embudo, la pantalla ensena otras cifras', async () => {
    const { container } = await pintar('fis-panel', RUTAS_DE_PANEL);
    expect(container.textContent).toContain('3418');

    const otro = { ...EMBUDO, detectadosPorCruce: 777, programados: 12 };
    const segunda = await pintar('fis-panel', {
      '/fiscalizacion/programas/14/embudo': otro,
      '/fiscalizacion/programas?': PROGRAMAS,
    });

    expect(segunda.container.textContent).toContain('777');
    expect(segunda.container.textContent).not.toContain('3418');
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

  it('LA VENTANA viaja a la MUESTRA y no a la relacion de programas (#228)', async () => {
    const { doble } = await pintar('fis-prog', RUTAS_DE_PROG, null, {
      pagina: '2',
      ordenarPor: 'condicion',
      sentido: 'DESCENDENTE',
    });
    const urls = doble.mock.calls.map((llamada) => String(llamada[0]));

    // La relacion sigue con `?tamano=1`: paginarla cambiaria CUAL programa se dibuja, no que
    // trozo de su muestra se ve. Son dos lecturas, y el mando es de la segunda.
    expect(urls[0]).toBe('/rentas/api/v1/fiscalizacion/programas?tamano=1');
    expect(urls[0]).not.toContain('pagina=');
    // Y el tamano NO esta escrito en la ruta: sale de `paginacion.tamano` de su tabla.
    expect(urls[1]).toContain('/fiscalizacion/programas/14/muestra?tamano=20');
    expect(urls[1]).toContain('pagina=2');
    expect(urls[1]).toContain('ordenarPor=condicion');
    expect(urls[1]).toContain('sentido=DESCENDENTE');
  });

  it('un `ordenarPor` que la definicion NO ofrece no viaja: lo escribio quien pasaba por ahi', async () => {
    // La ruta la teclea cualquiera, y `sectorCodigo` es justamente el nombre que
    // `OrdenSeguro.publicandoComo` RETIRA: reenviarlo seria un 422 dicho como averia de la
    // pantalla. Sin campo admitido ordena el backend por el suyo, que es lo que la barra anuncia.
    const { doble } = await pintar('fis-prog', RUTAS_DE_PROG, null, {
      ordenarPor: 'sectorCodigo',
      sentido: 'DESCENDENTE',
    });
    const muestra = doble.mock.calls.map((l) => String(l[0])).find((u) => u.includes('/muestra'));

    expect(muestra).not.toContain('ordenarPor');
    // Y el sentido tampoco: solo acompana a un campo admitido.
    expect(muestra).not.toContain('sentido');
  });

  it('`hayMas` y `paginas` los dice el SERVIDOR, y no se cuentan las filas recibidas', () => {
    // El caso que importa: dos filas de una muestra de 84, y el envoltorio dice que hay mas.
    // Contando las dos que llegaron, «Siguiente» saldria impedido sobre 82 predios sin mirar.
    const conMas = { ...MUESTRA, totalElementos: 84, totalPaginas: 42, hayMas: true };
    const nombrados = FIS_PROG.repartir(conMas as never).nombrados;

    expect(nombrados?.get('muestra-del-programa.hayMas')).toBe(true);
    expect(nombrados?.get('muestra-del-programa.paginas')).toBe('42');
    expect(FIS_PROG.repartir(conMas as never).tablas?.get('muestra-del-programa')?.totalElementos)
      .toBe(84);
  });

  it('y declara los CUATRO sitios de la ventana, para la operacion de la muestra', () => {
    // Sin esto el marco tira con aviso lo que el destino no declara: el mando escribiria
    // `?pagina=2`, el conector no lo veria y la tabla dibujaria la pagina 0 con el rotulo de la 3.
    expect(FIS_PROG.parametros?.map((p) => p.nombre)).toEqual([
      'pagina',
      'tamano',
      'ordenarPor',
      'sentido',
    ]);
    expect(new Set(FIS_PROG.parametros?.map((p) => p.operacion))).toEqual(
      new Set(['GET /fiscalizacion/programas/{id}/muestra']),
    );
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

  it('LAS DOS MITADES del contraste salen, y la diferencia COPIADA (#191)', () => {
    const filas = contrasteDelActa(ACTA_CON_USO);

    expect(PANTALLAS['fis-actas'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Concepto',
      'Declarado',
      'Verificado',
      'Diferencia',
      'Situación',
    ]);
    // Eran 4 celdas en raya de 10 —«Declarado» y «Diferencia» de las dos filas— y son **una**:
    // la diferencia de un uso, que no es un numero ni con todo publicado.
    expect(filas).toEqual([
      ['Área hallada (m²)', '164.50', '198.00', '33.50', 'SUBVALUADOR'],
      ['Uso del predio', 'CASA_HABITACION', 'COMERCIO', sinDato(SIN_DIFERENCIA_DE_UN_USO), 'SUBVALUADOR'],
    ]);
  });

  it('y la diferencia NO se RESTA, aunque ahora esten los dos lados delante (#191)', () => {
    // La publica el backend «nunca negativa, nula si falta un lado». Con los dos lados publicados,
    // restarlos aqui daria la misma cifra y seria calcular lo que nadie publico — y esta es la
    // columna que sostiene la determinacion. Se comprueba cambiando SOLO la diferencia servida:
    // si se restara, la celda seguiria diciendo 33.50.
    const conOtraDiferencia = { ...ACTA_CON_USO, diferenciaDeArea: '7.25' };

    expect(contrasteDelActa(conOtraDiferencia)[0]?.[3]).toBe('7.25');
  });

  it('con el lado declarado en NULO dice «no consta», que no es «no publicado» (#191)', () => {
    // Un acta vehicular —un vehiculo no tiene area ni uso declarados— y una predial de un predio
    // sin ficha a la fecha de la visita. Se cierra con una ficha, no publicando un campo, asi que
    // la celda no puede decir la palabra que manda a buscar lo que ya esta publicado.
    const filas = contrasteDelActa(ACTA_VEHICULAR);

    expect(filas).toHaveLength(1);
    expect(filas[0]?.[1]).toEqual(sinDato(NO_CONSTA_LO_DECLARADO));
    expect(filas[0]?.[2]).toEqual(sinDato(SIN_AREA_HALLADA));
    expect(filas[0]?.[3]).toEqual(sinDato(NO_CONSTA_LO_DECLARADO));
    expect(NO_CONSTA_LO_DECLARADO).not.toBe(NO_PUBLICADO);
  });

  it('sin uso anotado, la fila del uso NO sale: nulo es «no se anoto»', () => {
    // El backend lo dice expresamente: `usoHallado` nulo **no** es «coincide con lo declarado».
    // Una fila con cuatro rayas de cinco no informaria de eso.
    const filas = contrasteDelActa(ACTA_SIN_USO);

    expect(filas).toHaveLength(1);
    expect(filas[0]?.[0]).toBe('Área hallada (m²)');
    // Sin hallazgo anotado, la situacion tambien dice que no hay dato — nunca «Conforme».
    expect(filas[0]?.[4]).toEqual(sinDato(SIN_HALLAZGO));
    // Y la fila del area sigue trayendo sus dos mitades: lo que falta es el uso, no lo declarado.
    expect(filas[0]?.[1]).toBe('164.50');
    expect(filas[0]?.[3]).toBe('33.50');
  });

  it('la «Situacion» es el HALLAZGO y no el estado del papel', () => {
    // `estado` es `ABIERTA`/`LIQUIDADA`/`ANULADA`: en que punto esta el acta, no que se encontro
    // en el predio. Pintarlo en esa columna diria que el predio esta «Abierta».
    const filas = contrasteDelActa(ACTA_CON_USO);

    expect(filas.map((f) => f[4])).toEqual(['SUBVALUADOR', 'SUBVALUADOR']);
    expect(JSON.stringify(filas)).not.toContain(ACTA_CON_USO.estado);
  });

  it('LA HOJA DICE DE QUIEN ES EL ACTA que dibuja (#239)', async () => {
    // Tomaba «la primera de la relacion» y **no decia cual**. Un contraste de areas que no nombra
    // al obligado se lee como si fuera del contribuyente que uno tenia en la cabeza.
    const { container } = await pintar('fis-actas', { '/fiscalizacion/actas': ACTAS });

    expect(container.textContent).toContain('MEDINA SILVA, RUFINA');
    expect(container.textContent).toContain('C-00025673');
  });

  it('y el nombre NO entra en el mando «Contribuyente»: ese campo es de entrada (#239)', () => {
    // Es la decision que #239 dejaba abierta. El sitio que el artboard le da al titular es de tipo
    // `1` —un control de entrada— y `Reparto.valores` son «los campos de solo lectura que si salen
    // de lo que llego»: escribir dentro el nombre de un acta ya registrada convierte el formulario
    // con que se registra una inspeccion en algo que parece estar editandola. Va por la frase de
    // pantalla, que es lo que #196 ya decidio para la fecha.
    const campos = PANTALLAS['fis-actas'].bloques[0]?.campos ?? [];
    const titular = campos.findIndex((campo) => campo.etiqueta === 'Contribuyente');

    expect(campos[titular]?.tipo).toBe('1');
    const reparto = FIS_ACTAS.repartir(ACTA_CON_USO as never);
    expect(reparto.valores.size).toBe(0);
    expect(reparto.valores.get(coordenada(0, titular))).toBeUndefined();
    expect(reparto.deQuienEs).toEqual({
      nombre: 'MEDINA SILVA, RUFINA',
      codigo: 'C-00025673',
    });
  });

  it('con el titular dado de baja lo dice ASI, y no con la palabra de un hueco (#239, #216)', async () => {
    // Los dos campos llegan nulos **a la vez** y eso no es un hueco del contrato: el acta sale
    // igual porque ocultarla esconderia justo el caso que hay que revisar. Con la frase de arriba
    // se leeria «es de undefined (undefined)»; con «no publicado» se mandaria a arreglar un
    // backend que no tiene nada que arreglar.
    const { container } = await pintar('fis-actas', {
      '/fiscalizacion/actas': { ...ACTAS, contenido: [ACTA_SIN_PADRON] },
    });

    expect(container.textContent).toContain('ya no esta en el padron');
    // Y NO la otra frase con los huecos vacios. `not.toContain('undefined')` no medía nada: la
    // interpolacion de i18next sobre un nulo no escribe «undefined», escribe **nada** — medido al
    // romperlo, la pantalla decia «Lo que se dibuja es de  ().», que es peor porque no parece un
    // defecto.
    expect(container.textContent).not.toContain('es de  (');
    expect(container.textContent).not.toContain('undefined');
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
  /** Las dos lecturas: la relacion con `?tamano=1` y el detalle de la que salga de ella. */
  const RUTAS_DE_RES = {
    '/fiscalizacion/resoluciones?': RESOLUCIONES,
    '/fiscalizacion/resoluciones/': RESOLUCION_SIN_CIFRAS,
  };

  it('ADMITE sujeto y ya no lo exige: con numero en la ruta se pide ESE (#192, #215)', async () => {
    const { doble } = await pintar('fis-res', RUTA, NUMERO);

    expect(FIS_RES.admiteSujeto).toBe(true);
    expect(FIS_RES.exigeSujeto).toBeUndefined();
    // Y la relacion NO se pide: seria una ida de mas para elegir lo que ya esta elegido.
    expect(doble.mock.calls.map((l) => String(l[0]))).toHaveLength(1);
    expect(String(doble.mock.calls[0]?.[0])).toContain(`/fiscalizacion/resoluciones/${NUMERO}`);
    // Sin `?formato=`: con el, la misma ruta contesta el PDF y no el JSON que se pinta.
    expect(String(doble.mock.calls[0]?.[0])).not.toContain('formato');
  });

  it('y SIN numero toma la primera de la relacion: abierta desde el menu ya ensena una', async () => {
    // Es la mitad de #215 que mas se nota. Hasta #192 no habia relacion, asi que esta pantalla
    // abierta desde el menu no ensenaba una resolucion NUNCA.
    const { container, doble } = await pintar('fis-res', RUTAS_DE_RES, null);
    const urls = doble.mock.calls.map((l) => String(l[0]));

    expect(urls[0]).toContain('/fiscalizacion/resoluciones?tamano=1');
    // Sin `?contribuyente=`: acotarla exige haber elegido a alguien, y elegirlo aqui seria decidir
    // por quien atiende de quien es la resolucion que se mira.
    expect(urls[0]).not.toContain('contribuyente');
    expect(urls[1]).toContain(`/fiscalizacion/resoluciones/${NUMERO}`);
    expect(container.textContent).toContain('Suc. Rufina Medina Medina');
    expect(container.textContent).not.toContain('falta el contribuyente');
  });

  it('y sin NINGUNA transferida dice «sin datos», que no es una averia', async () => {
    const { container } = await pintar(
      'fis-res',
      { '/fiscalizacion/resoluciones?': SIN_RESOLUCIONES },
      null,
    );

    expect(container.textContent).toContain('sin datos');
  });

  it('el catalogo DERIVA el sitio del sujeto de `admiteSujeto`, o el marco lo tiraria', async () => {
    // Sin esta derivacion el marco ignora con aviso el numero de la direccion «porque la hoja no
    // lo declara», y `#/fis-res/RDF-2026-000001` abriria siempre la PRIMERA del padron. Es la
    // capacidad que retirar `exigeSujeto` a secas habria costado.
    const { CATALOGO } = await import('../../catalogo.ts');
    const destino = CATALOGO.flatMap((m) => m.destinos).find((d) => d.clave === 'fis-res');

    expect(destino?.enLaRuta?.sujeto).toBe(true);
  });

  it('de los SEIS campos salen CUATRO, y los otros dos dicen «no publicado» (#193)', () => {
    const reparto = FIS_RES.repartir(RESOLUCION_CIFRADA as never);

    expect(reparto.valores.get(coordenada(0, 1))).toBe('Suc. Rufina Medina Medina');
    // Los tres totales, COPIADOS del backend. Eran «no publicado» hasta #193.
    expect(reparto.valores.get(coordenada(0, 3))).toBe('S/ 201.00');
    expect(reparto.valores.get(coordenada(0, 5))).toBe('S/ 89.20');
    expect(reparto.valores.get(coordenada(0, 7))).toBe('S/ 290.20');
    // «Nº de acta» y «Interes» siguen sin poderse llenar, y por dos motivos distintos.
    expect([...reparto.noPublicados.keys()].sort()).toEqual(
      [coordenada(0, 0), coordenada(0, 4)].slice().sort(),
    );
  });

  it('SIGUE sin sumar la tabla: los totales los COPIA, y con otros llegan otros', () => {
    // La regla que este archivo no negocia. Con la resolucion cifrada la suma de las lineas sale
    // al centimo —201.00 + 89.20 = 290.20— y seria indistinguible de la publicada; lo que separa
    // las dos es de donde viene el numero. Se comprueba cambiando SOLO los totales servidos: si se
    // sumaran las lineas, los campos seguirian diciendo 290.20.
    const conOtrosTotales = {
      ...RESOLUCION_CIFRADA,
      insolutoOmitido: '1.00',
      multaTributaria: '2.00',
      totalLiquidado: '3.00',
    };
    const reparto = FIS_RES.repartir(conOtrosTotales as never);

    expect(reparto.valores.get(coordenada(0, 3))).toBe('S/ 1.00');
    expect(reparto.valores.get(coordenada(0, 7))).toBe('S/ 3.00');
    expect([...reparto.valores.values()]).not.toContain('S/ 290.20');
  });

  it('con los totales nulos dice «sin cifrar» EN EL HUECO, y nunca un cero (D-02a)', () => {
    const reparto = FIS_RES.repartir(RESOLUCION_SIN_CIFRAS as never);

    // Al hueco y no a `valores`: un valor con «sin cifrar» dentro se pintaria como si fuera el
    // importe, sin el tono ni el `title` con que el interprete dibuja una ausencia.
    for (const campo of [coordenada(0, 3), coordenada(0, 5), coordenada(0, 7)]) {
      expect(reparto.valores.has(campo)).toBe(false);
      expect(reparto.noPublicados.get(campo)).toBe(SIN_CIFRAR);
    }
    expect([...reparto.noPublicados.values()]).not.toContain('0.00');
  });

  it('y si el backend dice que NO espera y aun asi no manda cifra, eso es «no publicado»', () => {
    // El tercer hueco, y el que separa un defecto del backend de una decision de negocio abierta.
    // Con `esperaSusCifras: false` y los totales nulos, decir «sin cifrar» culparia a D-02a de algo
    // que D-02a ya no explica.
    const contradictoria = { ...RESOLUCION_SIN_CIFRAS, esperaSusCifras: false };
    const reparto = FIS_RES.repartir(contradictoria as never);

    expect(reparto.noPublicados.get(coordenada(0, 3))).toBe(NO_PUBLICADO);
    expect(NO_PUBLICADO).not.toBe(SIN_CIFRAR);
  });

  it('«Nº de acta» sigue sin llenarse AUNQUE `actaId` llegue: es un identificador interno', () => {
    // Decidido con el artboard delante (#215): ese campo dibuja «ACT-2026-00418», o sea el numero
    // de un DOCUMENTO, y `actaId` es lo que la base asigna —«un acta no se numera»—. Escribirlo
    // crudo pondria un identificador donde el usuario espera un papel.
    const reparto = FIS_RES.repartir(RESOLUCION_CIFRADA as never);

    expect(reparto.noPublicados.get(coordenada(0, 0))).toBe(NO_PUBLICADO);
    expect([...reparto.valores.values()]).not.toContain(String(RESOLUCION_CIFRADA.actaId));
    // Y tampoco el `documentoSustento`, que se PARECE —dice «ACT-2026-00418»— y es texto libre del
    // cuerpo de la transferencia: lo teclea quien transfiere.
    expect([...reparto.valores.values()]).not.toContain(RESOLUCION_CIFRADA.documentoSustento);
  });

  it('la tabla: «Base omitida» DEJA de ser la raya, y sigue sin restarse aqui (#193)', () => {
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
    // 6 100.00 lo resta el BACKEND —33 500 menos 27 400—, y aqui se copia: restarlo seria
    // aritmetica sobre dinero en el navegador. «Interes» no lo publica NINGUNA de las dieciseis
    // operaciones de fiscalizacion, y sigue diciendo su motivo (#213).
    expect(filas).toEqual([
      ['2024', '6,100.00', '201.00', sinDato(SIN_INTERES), '290.20'],
    ]);
  });

  it('y la base omitida se COPIA: con otra servida, la celda dice otra', () => {
    // Si se restara `determinado − declarado`, cambiar solo `baseOmitida` no cambiaria la celda.
    const linea = RESOLUCION_CIFRADA.lineas[0];
    const otra = {
      ...RESOLUCION_CIFRADA,
      lineas: [{ ...linea, baseOmitida: '11.11' }],
    };

    expect(celdasDe(FIS_RES.repartir(otra as never), 'detalle-por-ejercicio')[0]?.[1]).toBe('11.11');
  });

  it('con los importes nulos dice «sin cifrar», que NO es la raya ni un cero (D-02a)', () => {
    const filas = celdasDe(
      FIS_RES.repartir(RESOLUCION_SIN_CIFRAS as never),
      'detalle-por-ejercicio',
    );

    expect(filas).toEqual([
      ['2024', SIN_CIFRAR, SIN_CIFRAR, sinDato(SIN_INTERES), SIN_CIFRAR],
      ['2025', SIN_CIFRAR, SIN_CIFRAR, sinDato(SIN_INTERES), SIN_CIFRAR],
    ]);
    // El campo existe y llego vacio: decirlo con la celda sin dato pediria publicar lo que ya esta
    // publicado, y con un `0.00` diria que no se debe nada. Son DOS huecos distintos y se ven.
    expect(JSON.stringify(filas)).not.toContain('0.00');
  });

  it('y dice A QUE DIA estan sus cifras, que aqui es dinero notificable (regla 9)', async () => {
    expect(FIS_RES.repartir(RESOLUCION_CIFRADA as never).aLaFecha).toBe('2026-06-30');

    const { container } = await pintar('fis-res', RUTA, NUMERO);
    expect(container.textContent).toContain('30/06/2026');
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

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { PANTALLAS } from '../../pantallas/definiciones/index.ts';
import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import { pantallaDe } from '../../pantallas/definiciones/index.ts';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';
import type { GiroCiiu, LicenciaDeFuncionamiento, Paginado } from '../lecturas.ts';
import { AUT_CAT, AUT_TRAM, giroQueSeEnsena } from './licencias.ts';

/**
 * **Que `aut-cat` y `aut-tram` ensenen lo que LLEGO, y no lo que dibuja su definicion** (#168, AC3).
 *
 * <h2>Por que la mitad de este archivo MONTA la pantalla en vez de llamar a `repartir`</h2>
 *
 * Porque `repartir` se puede comprobar entero y aun asi la pantalla puede estar **pareciendo**
 * conectada: bastaria con que el interprete dibujase otra cosa —las filas de la definicion, un
 * estado cacheado, nada— para que el usuario viera siempre lo mismo mientras la funcion pasa en
 * verde. La unica forma de distinguir «conectada» de «parece conectada» es cambiar la respuesta
 * del doble y **mirar el DOM**: si con dos respuestas distintas se lee lo mismo, no esta conectada.
 *
 * Por eso cada pantalla se monta dos veces, con dos paginas que no comparten ni una celda.
 *
 * <h2>Y por que se comprueba ademas que NO sale la fila del artboard</h2>
 *
 * Es la otra direccion del mismo riesgo. El artboard dibuja `aut-cat` con «G-5211-01 · Venta al
 * por menor en almacenes no especializados» y `aut-tram` con «2026-006549 · Eleodoro Quiroga
 * Ramos», y esas cadenas son las que estarian ahi si alguien devolviera las cifras de ejemplo a
 * las definiciones (la historia de `sin-cifras-inventadas.test.ts`). Una pantalla que ensena la
 * fila del artboard con el backend contestando otra cosa es exactamente el defecto que este
 * issue viene a evitar, y no lo caza ninguna de las dos direcciones por separado.
 */

/** Una pagina del backend, con el envoltorio entero: lo que `pedirPagina` devuelve. */
function pagina<T>(contenido: readonly T[], totalElementos: number): Paginado<T> {
  return {
    contenido,
    pagina: 0,
    tamano: 20,
    totalElementos,
    totalPaginas: Math.ceil(totalElementos / 20),
    hayMas: totalElementos > contenido.length,
  };
}

/** Un giro del catalogo, con los ocho campos que el contrato declara. */
function giro(campos: Partial<GiroCiiu> & Pick<GiroCiiu, 'codigo' | 'descripcion'>): GiroCiiu {
  return {
    seccion: 'G',
    riesgoItse: 'Bajo',
    zonificacionCompatible: 'CZ',
    requiereSectorial: false,
    extendido: false,
    activo: true,
    ...campos,
  };
}

/** Una licencia, con los veintiun campos que el contrato declara. */
function licencia(
  campos: Partial<LicenciaDeFuncionamiento> & Pick<LicenciaDeFuncionamiento, 'nroLicencia'>,
): LicenciaDeFuncionamiento {
  return {
    est: 'A',
    estado: 'Activa',
    estadoALaFecha: '13/08/2026',
    contribuyente: 'SIN TITULAR',
    codContribuyente: '00000000000',
    denominacionComercial: 'SIN DENOMINACION',
    direccion: 'SIN DIRECCION',
    tipoDeLicencia: 'Definitiva',
    areaDelEstablecimiento: '0.00',
    zonificacion: 'CZ',
    zonaDelTerritorio: '',
    ordenanzaDeLaZona: '',
    zonaOrigen: '',
    comprobacionDelTerritorio: '',
    aforo: 0,
    fechaDeEmision: '02/01/2026',
    fechaDeVencimiento: '31/12/2026',
    nExpediente: '2026-0000',
    fechaDeExpediente: '02/01/2026',
    fichaEconomica: 0,
    giros: [],
    historial: [],
    duplicados: [],
    ...campos,
  };
}

// ── Las dos paginas de `aut-cat`, que no comparten una sola celda ───────────────────────────

const CIIU_A = pagina<GiroCiiu>(
  [
    giro({ codigo: 'A-0111-01', descripcion: 'Cultivo de cereales', seccion: 'A', riesgoItse: 'Bajo' }),
    giro({ codigo: 'C-1071-02', descripcion: 'Elaboracion de panes', seccion: 'C', riesgoItse: 'Medio' }),
  ],
  1842,
);

const CIIU_B = pagina<GiroCiiu>(
  [
    giro({ codigo: 'H-4923-04', descripcion: 'Mudanzas interprovinciales', seccion: 'H', riesgoItse: 'Alto' }),
  ],
  17,
);

// ── Las dos paginas de `aut-tram` ───────────────────────────────────────────────────────────

const PADRON_A = pagina<LicenciaDeFuncionamiento>(
  [
    licencia({
      nroLicencia: '2019-000123',
      contribuyente: 'ALVARADO CHERRE-MANUEL',
      denominacionComercial: 'BOTICA SAN JUDAS',
      estado: 'Activa',
      giros: [
        { codigo: 'G-5231-01', descripcion: 'Venta de productos farmaceuticos', principal: true, activo: true },
        { codigo: 'G-5211-01', descripcion: 'Venta al por menor', principal: false, activo: true },
      ],
    }),
  ],
  6418,
);

const PADRON_B = pagina<LicenciaDeFuncionamiento>(
  [
    licencia({
      nroLicencia: '2024-007788',
      contribuyente: 'RUIZ SANDOVAL-TERESA',
      denominacionComercial: 'FERRETERIA EL CLAVO',
      estado: 'Vencida',
      giros: [{ codigo: 'G-5234-01', descripcion: 'Venta de materiales de construccion', principal: false, activo: true }],
    }),
  ],
  2,
);

// ── Lo que el ARTBOARD dibuja, y que por tanto no puede salir de una respuesta ──────────────

const FILA_DEL_ARTBOARD = {
  'aut-cat': ['G-5211-01', 'Venta al por menor en almacenes no especializados'],
  'aut-tram': ['2026-006549', 'Eleodoro Quiroga Ramos', 'Bodega El Sol'],
} as const;

function arnes() {
  // Un cliente por prueba: compartido, la respuesta de la primera se quedaria en la cache de la
  // segunda y las dos leerian lo mismo — que es justo lo que este archivo intenta distinguir.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
}

/** La pantalla tal como la monta la aplicacion: su definicion, y lo que se sepa de sus datos. */
function PantallaConectada({ clave }: { readonly clave: ClaveDeHoja }) {
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave)} />;
}

/** Sustituye `fetch` por una pagina fija, y dice a que URL se llamo. */
function contesta(cuerpo: unknown) {
  const doble = vi.fn<typeof fetch>(() =>
    Promise.resolve(
      new Response(JSON.stringify(cuerpo), {
        status: 200,
        headers: { 'content-type': 'application/json' },
      }),
    ),
  );
  vi.stubGlobal('fetch', doble);
  return doble;
}

/** Monta la hoja con esa respuesta y espera a que deje de pedir. */
async function pintar(clave: ClaveDeHoja, cuerpo: unknown) {
  const doble = contesta(cuerpo);
  const { container } = render(<PantallaConectada clave={clave} />, { wrapper: arnes() });
  await waitFor(() => {
    expect(screen.queryByText(/pidiendo/i)).toBeNull();
  });
  return { container, doble };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`aut-cat` — el catalogo de giros CIIU', () => {
  it('pide `/licencias/ciiu`, y lo pide ACOTADO: 1 842 giros no caben en una tabla', async () => {
    const { doble } = await pintar('aut-cat', CIIU_A);
    const url = String(doble.mock.calls[0]?.[0]);

    expect(url).toContain('/licencias/ciiu');
    // El parametro que el contrato publica y que dice cuantos viajan. Sin el —o con `tamano=1842`—
    // la pantalla traeria el catalogo entero para ensenar cuatro filas.
    expect(url).toContain('tamano=20');
    expect(url).not.toContain('tamano=1842');
  });

  it('las cuatro columnas salen de los cuatro campos, en el orden de la definicion', () => {
    const filas = AUT_CAT.repartir(CIIU_A as never).filas.get(0);

    expect(PANTALLAS['aut-cat'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Código CIIU',
      'Actividad',
      'Materia',
      'Riesgo',
    ]);
    expect(filas).toEqual([
      ['A-0111-01', 'Cultivo de cereales', 'A', 'Bajo'],
      ['C-1071-02', 'Elaboracion de panes', 'C', 'Medio'],
    ]);
  });

  it('no decide ningun campo: los tres del bloque son filtros, no cifras', () => {
    // Si algun dia esta pantalla gana un campo de solo lectura, esto se pone rojo y obliga a
    // decidirlo — con dato o con «no publicado»— en vez de dejarlo cayendo en el motivo de la
    // pantalla, que en una conectada dice que SI esta conectada.
    const reparto = AUT_CAT.repartir(CIIU_A as never);
    expect(reparto.valores.size).toBe(0);
    expect(reparto.noPublicados.size).toBe(0);
    expect(PANTALLAS['aut-cat'].bloques[0]?.campos.filter((c) => c.tipo.startsWith('r'))).toEqual([]);
  });

  it('LA ROTURA DEL AC3: con otra respuesta, la pantalla ensena otra cosa', async () => {
    const { container } = await pintar('aut-cat', CIIU_A);
    expect(container.textContent).toContain('Cultivo de cereales');
    expect(container.textContent).toContain('Elaboracion de panes');

    // Segundo montaje, otra pagina. Si la pantalla dibujara lo suyo —o una cache— esto seria
    // identico a lo de arriba y nadie lo notaria: la pantalla se veria llena y correcta.
    const otra = await pintar('aut-cat', CIIU_B);
    expect(otra.container.textContent).toContain('Mudanzas interprovinciales');
    expect(otra.container.textContent).not.toContain('Cultivo de cereales');
  });

  it('y NUNCA ensena la fila del artboard, que es la otra direccion del mismo defecto', async () => {
    const { container } = await pintar('aut-cat', CIIU_A);
    for (const celda of FILA_DEL_ARTBOARD['aut-cat']) {
      expect(container.textContent, `«${celda}» es del artboard, no del backend`).not.toContain(celda);
    }
  });
});

describe('`aut-tram` — el padron de licencias de funcionamiento', () => {
  it('pide `/licencias/funcionamiento`, y la pide PELADA', async () => {
    const { doble } = await pintar('aut-tram', PADRON_A);
    const url = String(doble.mock.calls[0]?.[0]);

    expect(url).toContain('/licencias/funcionamiento');
    // Los seis mandos que la pantalla dibuja NO son parametros de esta operacion. Mandar uno
    // seria inventarse un nombre que el contrato no declara, y el backend lo ignoraria en
    // silencio: la tabla saldria sin filtrar y con aspecto de filtrada.
    for (const inventado of ['ejercicio=', 'estado=', 'desde=', 'hasta=', 'tipoDeLicencia=', 'agrupado']) {
      expect(url, `se mando «${inventado}», que esta operacion no admite`).not.toContain(inventado);
    }
  });

  it('las cinco columnas salen de la respuesta, y «Giro» del giro PRINCIPAL', () => {
    const filas = AUT_TRAM.repartir(PADRON_A as never).filas.get(0);

    expect(PANTALLAS['aut-tram'].bloques[0]?.tabla?.columnas.map((c) => c.rotulo)).toEqual([
      'Nº licencia',
      'Titular',
      'Denominación',
      'Giro',
      'Estado',
    ]);
    // El segundo giro de esa licencia es «Venta al por menor», y no es el que sale: la licencia
    // misma dice cual es el principal, y elegir el primero del arreglo seria elegirlo por el
    // orden con que vino la respuesta.
    expect(filas).toEqual([
      [
        '2019-000123',
        'ALVARADO CHERRE-MANUEL',
        'BOTICA SAN JUDAS',
        'Venta de productos farmaceuticos',
        'Activa',
      ],
    ]);
  });

  it('sin ninguno marcado principal cae en el primero, y sin giros deja la celda en blanco', () => {
    expect(giroQueSeEnsena(PADRON_B.contenido[0] as LicenciaDeFuncionamiento)).toBe(
      'Venta de materiales de construccion',
    );
    // En blanco y no «ninguno»: lo que se sabe es que la respuesta no trajo giros, no que la
    // licencia no tenga.
    expect(giroQueSeEnsena(licencia({ nroLicencia: '2020-1' }))).toBe('');
  });

  it('no decide ningun campo: los seis del bloque son mandos de filtro', () => {
    const reparto = AUT_TRAM.repartir(PADRON_A as never);
    expect(reparto.valores.size).toBe(0);
    expect(reparto.noPublicados.size).toBe(0);
    expect(PANTALLAS['aut-tram'].bloques[0]?.campos.filter((c) => c.tipo.startsWith('r'))).toEqual([]);
  });

  it('LA ROTURA DEL AC3: con otra respuesta, la pantalla ensena otra cosa', async () => {
    const { container } = await pintar('aut-tram', PADRON_A);
    expect(container.textContent).toContain('BOTICA SAN JUDAS');
    expect(container.textContent).toContain('2019-000123');

    const otra = await pintar('aut-tram', PADRON_B);
    expect(otra.container.textContent).toContain('FERRETERIA EL CLAVO');
    expect(otra.container.textContent).toContain('Vencida');
    expect(otra.container.textContent).not.toContain('BOTICA SAN JUDAS');
  });

  it('y NUNCA ensena la fila del artboard', async () => {
    const { container } = await pintar('aut-tram', PADRON_A);
    for (const celda of FILA_DEL_ARTBOARD['aut-tram']) {
      expect(container.textContent, `«${celda}» es del artboard, no del backend`).not.toContain(celda);
    }
  });
});

describe('las otras dos hojas del modulo NO se conectan, y esta es la comprobacion', () => {
  it('`aut-panel` y `aut-sol` se quedan fuera: su motivo esta en el javadoc de este modulo', async () => {
    // No es una omision que haya que recordar: `aut-panel` pide un resumen POR ESTADO DE TRAMITE
    // que ningun reporte publica —los dos que hay agregan por otra cosa— y `aut-sol` pide los
    // requisitos del TUPA, que `GET /licencias/funcionamiento` no trae. Conectarlas exigiria
    // contar sobre la pagina o pintar los requisitos de otro tramite.
    const { CONECTORES } = await import('../conectores.ts');
    expect(Object.keys(CONECTORES)).not.toContain('aut-panel');
    expect(Object.keys(CONECTORES)).not.toContain('aut-sol');
  });
});

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import type { CambioDeLaRuta, HojaDelMarco, RutaDeLaHoja } from '@kamayuk/ui';
import type { ClaveDeHoja } from '../../pantallas/arbol.ts';
import { pantallaDe } from '../../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../../pantallas/PantallaDeRentas.tsx';
import type {
  InternamientoEnDeposito,
  MovimientoDeLaBitacora,
  Paginado,
  SesionDeLaVentanilla,
  VehiculoServido,
} from '../lecturas.ts';
import { SESION_MEDIDA } from '../sesionMedida.ts';
import { useDatosDeLaHoja } from '../useDatosDeLaHoja.ts';

/**
 * **Lo elegido en un campo va a la ruta de la hoja, y de la ruta a la peticion** (#629, #172 por el
 * lado de `seg-aud` y `tra-veh`; el mecanismo es `kamayuk-lib`#97).
 *
 * Tres cosas, y ninguna es la misma:
 *
 * · **La ruta llega a la peticion.** `#/seg-aud?desde=…&hasta=…` pide la bitacora con los dos
 *   extremos, y `#/tra-veh/<placa>` pide la ficha de esa placa. Es lo que hace que recargar la
 *   direccion vuelva a pedir lo mismo.
 * · **La ruta vuelve al campo.** Con la direccion puesta, el campo ENSENA lo elegido: un filtro que
 *   acota la lista sin decirlo en su caja es una lista acotada sin ninguna senal de por que.
 * · **El campo escribe la ruta.** Teclear la placa y salir del campo mueve el SUJETO. El gesto de
 *   las fechas no se mide aqui: elegir un dia abre la capa del calendario, y bajo jsdom eso no cabe
 *   —lo dejo medido `kamayuk-lib`#97—; lo recorre `e2e/lo-elegido-va-en-la-ruta.spec.ts` en Chromium.
 *
 * Se monta **la pantalla con su `hoja`** y no la aplicacion entera, y a proposito: es lo que ata la
 * ruta al interprete y al conector, y montar el armazon encima solo anade CPU —que es lo que hacia
 * caducar a `MandoDelEjercicio.test.tsx` (#629)—. Y se escribe con `fireEvent`, que es un evento y
 * no cinco por tecla.
 */

/** El ejercicio de la sesion. Cualquiera: lo que se mide aqui son los extremos y el sujeto. */
const EJERCICIO = 2025;

const MOVIMIENTO: MovimientoDeLaBitacora = {
  id: 41184,
  ejercicio: EJERCICIO,
  tabla: 'recibo',
  clave: '0003-0041184',
  operacion: 'ANULACION',
  usuario: 'administrador',
  origenEquipo: null,
  origenIp: null,
  fecha: '2025-03-13T09:41:12-05:00',
  observacion: 'Anulado por duplicado',
  datosAnteriores: null,
  datosNuevos: null,
};

const FICHA: VehiculoServido = {
  id: 7,
  placa: 'T2G-418',
  contribuyenteId: 25673,
  marca: 'TOYOTA',
  modelo: 'YARIS',
  categoria: 'M1',
  anioFabricacion: 2014,
  anioInscripcion: 2015,
  numeroMotor: '2NZ-1188412',
  numeroSerie: 'JTDBT923771118841',
  estado: 'ACTIVO',
  historialDePlacas: [],
};

const INTERNADO: InternamientoEnDeposito = {
  id: 1,
  placa: 'T2G-418',
  clase: 'AUTOMOVIL',
  papeleta: '0041182',
  deposito: 'DEPOSITO MUNICIPAL 1',
  fechaDeIngreso: '2026-07-18',
  fechaDeSalida: null,
  dias: 54,
  calculadoA: '2026-09-10',
  estado: 'INTERNADO',
  tasaDeCustodia: 'TUPA-2.14 CUSTODIA DIARIA',
  acta: 'ACTA-2026-0311',
};

function pagina<T>(contenido: readonly T[]): Paginado<T> {
  return { contenido, pagina: 0, tamano: 20, totalElementos: contenido.length, totalPaginas: 1, hayMas: false };
}

/** Sustituye `fetch` por un doble que contesta por el trozo de la ruta, y dice que se pidio. */
function contesta(porRuta: readonly (readonly [string, unknown])[]): string[] {
  const pedidas: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      pedidas.push(url);
      const casa = porRuta.find(([trozo]) => url.includes(trozo));
      return Promise.resolve(
        new Response(JSON.stringify(casa?.[1] ?? {}), {
          status: casa === undefined ? 404 : 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }),
  );
  return pedidas;
}

/** Una `hoja` como la del marco, con la ruta dada, que anota cada movimiento que se le pide. */
function hojaCon(ruta: RutaDeLaHoja): { readonly hoja: HojaDelMarco; readonly movimientos: CambioDeLaRuta[] } {
  const movimientos: CambioDeLaRuta[] = [];
  return {
    hoja: {
      ruta,
      moverLaRuta: (cambio) => {
        movimientos.push(cambio);
      },
    },
    movimientos,
  };
}

/** La pantalla como la monta `aplicacion.tsx`: definicion, datos pedidos con SU ruta, y la hoja. */
function PantallaConSuRuta({ clave, hoja }: { readonly clave: ClaveDeHoja; readonly hoja: HojaDelMarco }) {
  return <PantallaDeRentas definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave, hoja.ruta)} hoja={hoja} />;
}

function montar(clave: ClaveDeHoja, hoja: HojaDelMarco) {
  // Un cliente por prueba: compartido, la respuesta de una se quedaria en la cache de la otra.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const envoltorio = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  return render(<PantallaConSuRuta clave={clave} hoja={hoja} />, { wrapper: envoltorio });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('`seg-aud` — «Desde» y «Hasta» viven en la ruta (#629)', () => {
  const SESION: SesionDeLaVentanilla = { ...SESION_MEDIDA, ejercicioDeTrabajo: EJERCICIO };
  const bitacoras = (pedidas: readonly string[]) => pedidas.filter((url) => url.includes('/seguridad/auditoria'));

  it('EL CENTINELA: sin nada en la ruta, la bitacora se pide SIN extremos', async () => {
    // Sin esto, la de abajo pasaria con un conector que mandase `desde` y `hasta` siempre, de donde
    // fuera: lo que distingue es que la ruta los trae o no.
    const pedidas = contesta([
      ['/seguridad/auditoria', pagina([MOVIMIENTO])],
      ['/seguridad/sesion', SESION],
    ]);
    montar('seg-aud', hojaCon({ sujeto: null, parametros: {} }).hoja);

    await waitFor(() => {
      expect(bitacoras(pedidas)).toHaveLength(1);
    });
    const url = new URL(bitacoras(pedidas)[0] ?? '', 'http://localhost');
    expect(url.searchParams.get('ejercicio')).toBe(String(EJERCICIO));
    expect(url.searchParams.has('desde')).toBe(false);
    expect(url.searchParams.has('hasta')).toBe(false);
  });

  it('con `?desde=` y `?hasta=` en la ruta, la bitacora se pide con los dos, y los campos los ENSENAN', async () => {
    const pedidas = contesta([
      ['/seguridad/auditoria', pagina([MOVIMIENTO])],
      ['/seguridad/sesion', SESION],
    ]);
    montar('seg-aud', hojaCon({ sujeto: null, parametros: { desde: '2025-03-01', hasta: '2025-03-31' } }).hoja);

    await waitFor(() => {
      expect(bitacoras(pedidas)).toHaveLength(1);
    });
    const url = new URL(bitacoras(pedidas)[0] ?? '', 'http://localhost');
    // Tal cual los deja el calendario, en ISO: es lo que la operacion lee como `LocalDate`.
    expect(url.searchParams.get('desde')).toBe('2025-03-01');
    expect(url.searchParams.get('hasta')).toBe('2025-03-31');
    // Y el ejercicio sigue saliendo de la SESION: los extremos se suman, no lo sustituyen.
    expect(url.searchParams.get('ejercicio')).toBe(String(EJERCICIO));

    // Lo que se LEE es `dd/mm/aaaa`: recargar la direccion vuelve a ensenar el periodo acotado.
    expect(screen.getByText('01/03/2025')).toBeTruthy();
    expect(screen.getByText('31/03/2025')).toBeTruthy();
  });
});

describe('`tra-veh` — la placa del campo ES el sujeto de la ruta (#629)', () => {
  const comoTraVeh = [
    ['/rentas/vehiculos/', FICHA],
    ['/transito/internamientos', pagina([INTERNADO])],
  ] as const;

  it('con `#/tra-veh/T2G-418`, el campo dice esa placa ANTES de que conteste nada, y la ficha que se pide es la suya', async () => {
    // Un backend que no contesta: lo que el campo ensene no puede venir de la respuesta, solo de la
    // RUTA. Con uno que contestara, la ficha pintaria la misma placa y la prueba no distinguiria
    // un campo que lee la ruta de uno que espera a los datos.
    const pedidas: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>((entrada) => {
        pedidas.push(String(entrada));
        return new Promise<Response>(() => {});
      }),
    );
    montar('tra-veh', hojaCon({ sujeto: 'T2G-418', parametros: {} }).hoja);

    expect((screen.getByLabelText('Placa') as HTMLInputElement).value).toBe('T2G-418');
    await waitFor(() => {
      expect(pedidas.some((url) => url.includes('/rentas/vehiculos/T2G-418'))).toBe(true);
    });
  });

  it('teclear otra placa y salir del campo mueve el SUJETO —una vez, al salir— y vuelve a la primera pagina', async () => {
    const pedidas = contesta(comoTraVeh);
    const { hoja, movimientos } = hojaCon({ sujeto: 'T2G-418', parametros: { pagina: '3' } });
    montar('tra-veh', hoja);
    await waitFor(() => {
      expect(pedidas.some((url) => url.includes('/rentas/vehiculos/T2G-418'))).toBe(true);
    });

    const placa = screen.getByLabelText('Placa');
    fireEvent.change(placa, { target: { value: 'M4J-118' } });
    // Mientras se escribe, la ruta no se mueve: una direccion por tecla serian lecturas que nadie
    // pidio y entradas de mas en el boton de atras (`kamayuk-lib`#97).
    expect(movimientos).toEqual([]);

    fireEvent.blur(placa);
    // El sujeto nuevo, y la tabla del deposito —que es del mismo bloque— vuelve a su primera pagina
    // en el MISMO movimiento: `cambioAlElegir` de `@kamayuk/ui`.
    expect(movimientos).toEqual([{ sujeto: 'M4J-118', parametros: { pagina: '0' } }]);
  });

  it('y con Intro, igual que al salir', async () => {
    const pedidas = contesta(comoTraVeh);
    const { hoja, movimientos } = hojaCon({ sujeto: null, parametros: {} });
    montar('tra-veh', hoja);
    // Sin placa no se pide nada —la hoja lo dice—, pero el campo ya esta para teclearla.
    const placa = screen.getByLabelText('Placa');
    expect(pedidas.filter((url) => url.includes('/rentas/vehiculos/'))).toEqual([]);

    fireEvent.change(placa, { target: { value: 'T2G-418' } });
    fireEvent.keyDown(placa, { key: 'Enter' });
    expect(movimientos).toEqual([{ sujeto: 'T2G-418', parametros: { pagina: '0' } }]);
  });
});

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { pantallaDe } from '../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../pantallas/PantallaDeRentas.tsx';
import { conLoQueHace, useActosDeLaHoja } from './actos.ts';
import type { DiligenciaDeUnaResolucion, PermisosDeLaSesion } from './lecturas.ts';
import { PERMISOS_MEDIDOS } from './seguridadMedida.ts';
import { useDatosDeLaHoja } from './useDatosDeLaHoja.ts';

/**
 * **La resolucion de un recurso se notifica desde `tra-pap`, y es un acto con su observacion**
 * (#638).
 *
 * #629 publico `POST /transito/descargos/{nDeExpediente}/resolucion/notificacion` —la diligencia
 * de la resolucion que resolvio un recurso, que abre el plazo para impugnarla— y ninguna pantalla
 * la ofrecia. Aqui se monta `tra-pap` como la monta la aplicacion —su definicion, sus datos y **sus
 * actos**— y se mide lo mismo que `actos.test.tsx` mide de la anulacion de #455, mas el 404:
 *
 * · **sale a la red lo que se escribio**, a la ruta DEL RECURSO, con los cuatro que la peticion
 *   exige, la forma y el resultado **en el vocabulario del backend**, los opcionales escritos y ni
 *   uno de los que se dejaron en blanco;
 * · **sin observacion no se manda nada** (regla 10), y el primario dice por que;
 * · **sin el privilegio, el boton sale impedido con su motivo** —`aria-disabled`, nunca `disabled`—;
 * · y **un 404 —el recurso sin resolver— se dice con sus palabras**: lo que dijo el backend, y un
 *   remedio que habla del RECURSO. El peldano `no-encontrado` de la escalera no es una averia, pero
 *   su remedio habla de la CUENTA —«puede ser valida en el emisor de identidad y no estar dada de
 *   alta en esta municipalidad»—, que es el 404 de la cadena de identidad y no este (#237).
 *
 * Con `fireEvent` y la pantalla sola, como `actos.test.tsx`: un evento y no cinco por tecla. La
 * fecha se elige en el calendario de `@kamayuk/ui`, que es una capa de Radix colocada por
 * floating-ui, y eso pide a jsdom un remiendo mas que la confirmacion: ver `SIN_CAPA_SUPERIOR`.
 *
 * **Y lleva el plazo de #504** (`ESPERAR` y `PLAZO_DE_LA_PRUEBA`, como `MandoDelEjercicio.test.tsx`
 * desde #629): el acto abre tres capas —el calendario y los dos desplegables— y la confirmacion, y
 * con el puesto cargado (carga 25 en cuatro nucleos) la del 404 caduco a los cinco segundos de
 * Vitest —`Test timed out in 5000ms`— con las otras tres en verde; y dentro de `yarn verificar`
 * entero, ya con el plazo, la primera tardo 5 844 ms. En verde no cuesta nada: `waitFor` vuelve en
 * cuanto el hecho se cumple.
 */

beforeAll(() => {
  // Lo que Radix pide a jsdom para abrir la confirmacion y el calendario: los remiendos de la
  // libreria, los mismos que `actos.test.tsx`.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.releasePointerCapture ??= () => {};
  Element.prototype.scrollIntoView ??= () => {};
  // Y uno propio, por la capa del calendario: ver `SIN_CAPA_SUPERIOR`.
  Element.prototype.matches = function (this: Element, selector: string): boolean {
    return SIN_CAPA_SUPERIOR.has(selector) ? false : DE_JSDOM.call(this, selector);
  };
});

afterAll(() => {
  // El remiendo es de este archivo: el `matches` de jsdom vuelve a su sitio.
  Element.prototype.matches = DE_JSDOM;
});

/**
 * **Los dos selectores de la capa superior, que bajo jsdom no se preguntan**.
 *
 * La capa del calendario la coloca floating-ui, que por cada antepasado pregunta
 * `element.matches(':popover-open')` y `element.matches(':modal')` (`isTopLayer`). Con el
 * `nwsapi` 2.2.27 de este jsdom, `:modal` no se resuelve: `isModal` vuelve a llamar al `matches`
 * nativo, que es el propio `nwsapi`, hasta desbordar la pila, y floating-ui se traga la excepcion.
 * Medido: **587 ms por llamada** sobre un documento de tres nodos, y con `tra-pap` montada, 46 s de
 * CPU en `_matches` → `isModal` → `isFullscreen` en el perfil, y un `setTimeout(0)` que volvia a los
 * 38 s. jsdom no tiene capa superior —ni popovers ni dialogos modales nativos—, asi que la respuesta
 * correcta aqui es `false`, y es la que se da sin preguntar.
 */
const SIN_CAPA_SUPERIOR: ReadonlySet<string> = new Set([':popover-open', ':modal']);

/** El `matches` de jsdom, para devolverlo al terminar. */
const DE_JSDOM = Element.prototype.matches;

/**
 * **Se espera al hecho, no al reloj** (#504, #629): lo que se espera tras confirmar es una cadena
 * entera —el `POST`, el rechazo o lo hecho, y su repintado—. Ver el javadoc de arriba.
 */
const ESPERAR = { timeout: 10_000 };

/** Por encima de la suma de las esperas de una prueba: ver `ESPERAR`. */
const PLAZO_DE_LA_PRUEBA = { timeout: 60_000 };

afterEach(() => {
  vi.unstubAllGlobals();
});

const EXPEDIENTE = 'REC-2026-000031';
const NOTIFICADOR = 'Vilchez Rojas, Andres';
const RECIBIDO_POR = 'Pena Sandoval, Luis';
const OBSERVACION = 'Cedula entregada en el domicilio fiscal del infractor';

/** Lo que el backend contesta a una diligencia: `DiligenciaResource`, con sus trece campos. */
const DILIGENCIADA: DiligenciaDeUnaResolucion = {
  id: 7,
  resolucion: 'RGR-2026-000014',
  numero: 'RGR-2026-000014/1',
  intento: 1,
  fechaDeNotificacion: '2026-09-01',
  modalidad: 'NEGATIVA',
  resultado: 'RECHAZADO',
  notificador: NOTIFICADOR,
  direccion: 'Av. Jose de Lama 812, Sullana',
  recibidoPor: RECIBIDO_POR,
  acuse: null,
  exigibleDesde: '2026-09-23',
  abreElPlazoDeLaSancionadora: false,
};

/** Lo que el backend contesta a un recurso que no se resolvio por su ruta: 404 `NO_ENCONTRADO`. */
const SIN_RESOLVER = `El recurso '${EXPEDIENTE}' no tiene resolucion de recurso que notificar: o no existe, o todavia no se resolvio por su ruta`;

interface Peticion {
  readonly metodo: string;
  readonly url: string;
  readonly cuerpo: unknown;
}

/** El doble de la red: los permisos que se le den, y la diligencia que conteste lo que se le diga. */
function contesta(permisos: PermisosDeLaSesion, diligencia: 'acepta' | 'sin-resolver'): Peticion[] {
  const peticiones: Peticion[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada, init) => {
      const url = String(entrada);
      const metodo = init?.method ?? 'GET';
      const cuerpo: unknown = typeof init?.body === 'string' ? JSON.parse(init.body) : undefined;
      peticiones.push({ metodo, url, cuerpo });
      const json = (contenido: unknown, estado: number, tipo = 'application/json') =>
        Promise.resolve(
          new Response(JSON.stringify(contenido), { status: estado, headers: { 'content-type': tipo } }),
        );
      if (url.includes('/seguridad/sesion/permisos')) return json(permisos, 200);
      if (url.includes('/resolucion/notificacion') && metodo === 'POST') {
        return diligencia === 'acepta'
          ? json(DILIGENCIADA, 201)
          : json(
              { title: SIN_RESOLVER, status: 404, codigo: 'NO_ENCONTRADO', mensaje: SIN_RESOLVER },
              404,
              'application/problem+json',
            );
      }
      // La relacion de papeletas vacia: la tabla dice «sin datos», y el acto no la necesita.
      return json({ contenido: [], pagina: 0, tamano: 1, totalElementos: 0, totalPaginas: 0, hayMas: false }, 200);
    }),
  );
  return peticiones;
}

/** `tra-pap` como la monta `CuerpoDeLaPantalla`: definicion, datos y actos. */
function TraPap() {
  const hace = useActosDeLaHoja('tra-pap');
  return (
    <PantallaDeRentas
      definicion={pantallaDe('tra-pap')}
      datos={conLoQueHace(useDatosDeLaHoja('tra-pap', { sujeto: null, parametros: {} }), hace)}
      actos={hace.actos}
    />
  );
}

function montar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const envoltorio = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  return render(<TraPap />, { wrapper: envoltorio });
}

/** El boton del bloque, que ABRE el acto. */
const elQueAbre = () => screen.getByRole('button', { name: 'Notificar la resolución de un recurso' });
/** El primario del acto, que se llama como el acto. */
const elQueNotifica = () => screen.getByRole('button', { name: 'Registrar la diligencia' });

/** El texto al que apunta `aria-describedby`: lo que un lector de pantalla lee al enfocarlo. */
function descripcionDe(elemento: HTMLElement): string {
  return (elemento.getAttribute('aria-describedby') ?? '')
    .split(' ')
    .filter((id) => id !== '')
    .map((id) => document.getElementById(id)?.textContent ?? '')
    .join(' ');
}

/** Espera a que lleguen los permisos: hasta entonces el boton sale impedido, que es lo prudente. */
async function conLosPermisos(): Promise<void> {
  await waitFor(() => {
    expect(elQueAbre().getAttribute('aria-disabled')).toBeNull();
  });
}

/** El dia 1 del mes en curso, que es el que el calendario abre: lo que VIAJA es ISO. */
function elPrimeroDeEsteMes(): string {
  const hoy = new Date();
  return `${String(hoy.getFullYear())}-${String(hoy.getMonth() + 1).padStart(2, '0')}-01`;
}

/** El formulario del acto: el bloque de la hoja tiene sus propios campos, y uno es otra fecha. */
function elActo(): HTMLElement {
  const formulario = screen.getByLabelText('Expediente del recurso').closest('form');
  expect(formulario, 'el acto no se dibuja dentro de un formulario').not.toBeNull();
  return formulario as HTMLElement;
}

/**
 * **La fecha se elige en el calendario**: el disparador se llama como lo que ensena —el marcador
 * vacio— y no como su etiqueta (el hueco de `@kamayuk/ui` que `lo-elegido-va-en-la-ruta.spec.ts`
 * dejo anotado). El bloque de la hoja tiene otra fecha, asi que se busca dentro del acto, que tiene
 * una sola. La capa sale dentro del `act` del propio clic: no hay nada que esperar.
 */
function elegirElPrimeroDelMes(): void {
  fireEvent.click(within(elActo()).getByRole('button', { name: 'dd/mm/aaaa' }));
  const calendario = document.querySelector('[data-slot="calendario"]');
  expect(calendario, 'el calendario no se abrio').not.toBeNull();
  const dias = [...(calendario?.querySelectorAll('td:not([data-outside]) button') ?? [])];
  const primero = dias.find((dia) => dia.textContent === '1');
  expect(primero, 'el calendario no ofrece el dia 1 del mes').toBeDefined();
  fireEvent.click(primero as HTMLElement);
}

/** El `<select>` nativo que Radix pone junto al disparador dentro de un formulario. */
function elegirEn(etiqueta: string, opcion: string): void {
  const disparador = within(elActo()).getByRole('combobox', { name: etiqueta });
  const nativo = disparador.parentElement?.querySelector('select');
  expect(nativo, `«${etiqueta}» no tiene el select nativo de Radix`).toBeTruthy();
  fireEvent.change(nativo as HTMLSelectElement, { target: { value: opcion } });
}

function escribirElActo(observacion: string): void {
  fireEvent.change(screen.getByLabelText('Expediente del recurso'), { target: { value: EXPEDIENTE } });
  elegirElPrimeroDelMes();
  elegirEn('Forma de notificación', 'Con certificación de negativa');
  elegirEn('Resultado', 'Rechazado');
  fireEvent.change(screen.getByLabelText('Notificador'), { target: { value: NOTIFICADOR } });
  fireEvent.change(screen.getByLabelText(/^Recibido por/), { target: { value: RECIBIDO_POR } });
  fireEvent.change(screen.getByLabelText('Observación'), { target: { value: observacion } });
}

const diligencias = (peticiones: readonly Peticion[]) =>
  peticiones.filter((p) => p.metodo === 'POST' && p.url.includes('/resolucion/notificacion'));

describe('#638 — `tra-pap` ofrece notificar la resolucion de un recurso', () => {
  it('con el privilegio, el acto manda a la ruta DEL RECURSO la diligencia y la observacion, tras confirmar', PLAZO_DE_LA_PRUEBA, async () => {
    const peticiones = contesta(PERMISOS_MEDIDOS, 'acepta');
    montar();
    await conLosPermisos();

    fireEvent.click(elQueAbre());
    escribirElActo(OBSERVACION);
    fireEvent.click(elQueNotifica());

    // No se corrige ni se borra: el primario abre la confirmacion y NO manda nada todavia.
    const confirmacion = await screen.findByRole('alertdialog', {}, ESPERAR);
    expect(confirmacion.textContent).toContain('La diligencia no se corrige');
    expect(diligencias(peticiones)).toEqual([]);
    fireEvent.click(within(confirmacion).getByRole('button', { name: 'Si, confirmar' }));

    await waitFor(() => {
      expect(diligencias(peticiones)).toHaveLength(1);
    }, ESPERAR);
    const [enviada] = diligencias(peticiones);
    expect(new URL(enviada?.url ?? '', 'http://localhost').pathname).toMatch(
      new RegExp(`/transito/descargos/${EXPEDIENTE}/resolucion/notificacion$`),
    );
    // Los cuatro que la peticion exige —la forma y el resultado con el NOMBRE del enumerado, no con
    // el rotulo que se lee—, el opcional que se escribio, y ninguno de los que se dejaron en blanco:
    // sin `direccion`, el backend diligencia en el domicilio fiscal vigente del obligado.
    expect(enviada?.cuerpo).toEqual({
      fechaDeNotificacion: elPrimeroDeEsteMes(),
      modalidad: 'NEGATIVA',
      resultado: 'RECHAZADO',
      notificador: NOTIFICADOR,
      recibidoPor: RECIBIDO_POR,
      observacion: OBSERVACION,
    });

    // Y lo hecho dice la diligencia que quedo, con lo que el backend CONTESTO. La negativa a recibir
    // SURTE efecto (art. 104 a del TUO del Codigo Tributario): el titulo sale del `resultado` que
    // contesto el backend, y dice si el plazo corre.
    await screen.findByText('Negativa a recibir certificada: corre el plazo para impugnarla', {}, ESPERAR);
    expect(
      screen.getByText(
        `Diligencia ${DILIGENCIADA.numero} de la resolución ${DILIGENCIADA.resolucion}, en ${DILIGENCIADA.direccion}.`,
      ),
    ).toBeTruthy();
  });

  it('sin observacion NO se manda nada: el primario sale impedido y dice por que (regla 10)', PLAZO_DE_LA_PRUEBA, async () => {
    const peticiones = contesta(PERMISOS_MEDIDOS, 'acepta');
    montar();
    await conLosPermisos();

    fireEvent.click(elQueAbre());
    escribirElActo('ok');

    const primario = elQueNotifica();
    expect(primario.getAttribute('aria-disabled')).toBe('true');
    // Impedido, nunca `disabled`: sigue en el tabulador y dice por que.
    expect((primario as HTMLButtonElement).disabled).toBe(false);
    expect(descripcionDe(primario)).toBe('La observacion tiene 2 de los 5 caracteres que necesita como minimo.');
    fireEvent.click(primario);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(diligencias(peticiones)).toEqual([]);
  });

  it('sin el privilegio de registro sobre los descargos, el boton que abre sale IMPEDIDO y nombra la opcion que falta', PLAZO_DE_LA_PRUEBA, async () => {
    const sinRegistro: PermisosDeLaSesion = {
      ...PERMISOS_MEDIDOS,
      transito_descargos: (PERMISOS_MEDIDOS['transito_descargos'] ?? []).filter((p) => p !== 'registro'),
    };
    const peticiones = contesta(sinRegistro, 'acepta');
    montar();
    // Los permisos llegaron —se pidieron— y aun asi sigue impedido: no es la espera, es la cuenta.
    await waitFor(() => {
      expect(peticiones.some((p) => p.url.includes('/seguridad/sesion/permisos'))).toBe(true);
    });

    const boton = elQueAbre();
    await waitFor(() => {
      expect(descripcionDe(boton)).toBe(
        'Notificar la resolución de un recurso pide el privilegio de registro sobre «Descargos y reclamos de papeletas», que esta cuenta no tiene.',
      );
    });
    expect(boton.getAttribute('aria-disabled')).toBe('true');
    expect((boton as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(boton);
    expect(screen.queryByLabelText('Expediente del recurso')).toBeNull();
  });

  it('el 404 de un recurso sin resolver dice el RECURSO y no la cuenta, sin mandar a soporte, y lo escrito se queda', PLAZO_DE_LA_PRUEBA, async () => {
    const peticiones = contesta(PERMISOS_MEDIDOS, 'sin-resolver');
    montar();
    await conLosPermisos();

    fireEvent.click(elQueAbre());
    escribirElActo(OBSERVACION);
    fireEvent.click(elQueNotifica());
    fireEvent.click(
      within(await screen.findByRole('alertdialog', {}, ESPERAR)).getByRole('button', { name: 'Si, confirmar' }),
    );

    // Lo que dijo el backend, tal cual —nombra el expediente y las dos razones posibles—, con el
    // titulo y el remedio del RECURSO.
    await screen.findByText(SIN_RESOLVER, { exact: false }, ESPERAR);
    expect(diligencias(peticiones)).toHaveLength(1);
    expect(screen.getByText('Ese recurso no tiene resolución que notificar', { exact: false })).toBeTruthy();
    const dicho = document.body.textContent ?? '';
    // No es una averia —reintentar no lo cambia— y no es la cuenta: es el recurso.
    expect(dicho).not.toContain('avise a soporte');
    expect(dicho).not.toContain('Reintente');
    expect(dicho).not.toContain('Revise con que cuenta esta entrando');
    // Lo escrito sigue ahi: la diligencia no se registro, y cerrar obligaria a escribirla otra vez.
    expect((screen.getByLabelText('Notificador') as HTMLInputElement).value).toBe(NOTIFICADOR);
    expect(screen.queryByText('Negativa a recibir certificada: corre el plazo para impugnarla')).toBeNull();
  });
});

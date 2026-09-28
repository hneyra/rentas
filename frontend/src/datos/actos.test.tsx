import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';

import { pantallaDe } from '../pantallas/definiciones/index.ts';
import { PantallaDeRentas } from '../pantallas/PantallaDeRentas.tsx';
import { conLoQueHace, useActosDeLaHoja } from './actos.ts';
import type { ActoDeEdificacion, PermisosDeLaSesion } from './lecturas.ts';
import { PERMISOS_MEDIDOS } from './seguridadMedida.ts';
import { useDatosDeLaHoja } from './useDatosDeLaHoja.ts';

/**
 * **La anulacion de la licencia de edificacion tiene boton, y es un acto con su observacion**
 * (#455, #629).
 *
 * El backend sirve `POST /licencias/edificacion/{expediente}/anulacion` desde #455 y ninguna
 * pantalla la ofrecia. Aqui se monta `aut-sol` como la monta la aplicacion —su definicion, sus datos
 * y **sus actos**— y se mide lo que el issue pide y las tres reglas que lo rodean:
 *
 * · **sale a la red lo que se escribio**, a la ruta del expediente, con el motivo y la observacion;
 * · **sin observacion no se manda nada** (regla 10), y el primario dice por que;
 * · **lo que no se deshace se confirma** antes de mandar;
 * · **sin el privilegio, el boton sale impedido con su motivo** —`aria-disabled`, nunca `disabled`—;
 * · y **un 409 se dice con las palabras del backend**, sin mandar a soporte ni borrar lo escrito.
 *
 * Con `fireEvent` y la pantalla sola: un evento y no cinco por tecla, y sin el armazon encima, que
 * es lo que hacia caducar a `MandoDelEjercicio.test.tsx` (#629).
 */

beforeAll(() => {
  // Lo que Radix pide a jsdom para abrir la confirmacion: los remiendos de la libreria.
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
  Element.prototype.hasPointerCapture ??= () => false;
  Element.prototype.releasePointerCapture ??= () => {};
  Element.prototype.scrollIntoView ??= () => {};
});

afterEach(() => {
  vi.unstubAllGlobals();
});

const EXPEDIENTE = 'EXP-2026-0042';
const MOTIVO = 'Se aprobo sobre planos que no correspondian al predio';
const OBSERVACION = 'Anulacion ordenada por la gerencia de desarrollo urbano';

/** Lo que el backend contesta a una anulacion: `ActoDeEdificacionResource`, con sus siete campos. */
const ANULADA: ActoDeEdificacion = {
  nroExpediente: EXPEDIENTE,
  nroLicencia: 'LE-2026-0017',
  acto: 'ANULACION',
  fecha: '2026-09-28',
  resolucion: { numero: 'RES-AE-2026-0003', formato: 'PDF', resumen: 'a1b2', bytes: 18_422, reimpresiones: 0 },
  vigencias: [],
  valorDeObraNoDisponible: null,
};

/** Lo que el backend contesta a la segunda: 409 `CONFLICTO`, con su frase (#436). */
const YA_ANULADA = `La licencia del expediente ${EXPEDIENTE} ya estaba anulada`;

interface Peticion {
  readonly metodo: string;
  readonly url: string;
  readonly cuerpo: unknown;
}

/** El doble de la red: los permisos que se le den, y la anulacion que conteste lo que se le diga. */
function contesta(permisos: PermisosDeLaSesion, anulacion: 'acepta' | 'ya-estaba'): Peticion[] {
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
      if (url.includes('/anulacion') && metodo === 'POST') {
        return anulacion === 'acepta'
          ? json(ANULADA, 201)
          : json(
              { title: YA_ANULADA, status: 409, codigo: 'CONFLICTO', mensaje: YA_ANULADA },
              409,
              'application/problem+json',
            );
      }
      return json({}, 404);
    }),
  );
  return peticiones;
}

/** `aut-sol` como la monta `CuerpoDeLaPantalla`: definicion, datos y actos. */
function AutSol() {
  const hace = useActosDeLaHoja('aut-sol');
  return (
    <PantallaDeRentas
      definicion={pantallaDe('aut-sol')}
      datos={conLoQueHace(useDatosDeLaHoja('aut-sol', { sujeto: null, parametros: {} }), hace)}
      actos={hace.actos}
    />
  );
}

function montar() {
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const envoltorio = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  return render(<AutSol />, { wrapper: envoltorio });
}

/** El boton del bloque, que ABRE el acto. */
const elQueAbre = () => screen.getByRole('button', { name: 'Anular una licencia de edificación' });
/** El primario del acto, que se llama como el acto. */
const elQueAnula = () => screen.getByRole('button', { name: 'Anular la licencia' });

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

function escribirElActo(observacion: string): void {
  fireEvent.change(screen.getByLabelText('Expediente del FUE'), { target: { value: EXPEDIENTE } });
  fireEvent.change(screen.getByLabelText('Motivo de la anulación'), { target: { value: MOTIVO } });
  fireEvent.change(screen.getByLabelText('Observación'), { target: { value: observacion } });
}

const anulaciones = (peticiones: readonly Peticion[]) =>
  peticiones.filter((p) => p.metodo === 'POST' && p.url.includes('/anulacion'));

describe('#629 — `aut-sol` ofrece anular la licencia de edificacion (#455)', () => {
  it('con el privilegio, el acto manda a la ruta DEL EXPEDIENTE el motivo y la observacion, tras confirmar', async () => {
    const peticiones = contesta(PERMISOS_MEDIDOS, 'acepta');
    montar();
    await conLosPermisos();

    fireEvent.click(elQueAbre());
    escribirElActo(OBSERVACION);
    fireEvent.click(elQueAnula());

    // No se deshace: el primario abre la confirmacion y NO manda nada todavia.
    const confirmacion = await screen.findByRole('alertdialog');
    expect(confirmacion.textContent).toContain('La anulación no se deshace');
    expect(anulaciones(peticiones)).toEqual([]);
    fireEvent.click(within(confirmacion).getByRole('button', { name: 'Si, confirmar' }));

    await waitFor(() => {
      expect(anulaciones(peticiones)).toHaveLength(1);
    });
    const [enviada] = anulaciones(peticiones);
    expect(new URL(enviada?.url ?? '', 'http://localhost').pathname).toMatch(
      new RegExp(`/licencias/edificacion/${EXPEDIENTE}/anulacion$`),
    );
    // Lo escrito, y nada que no se escribiera: sin `fecha` ni `formato` el backend usa hoy y PDF.
    expect(enviada?.cuerpo).toEqual({ motivo: MOTIVO, observacion: OBSERVACION });

    // Y lo hecho dice el papel que salio, con lo que el backend CONTESTO.
    await screen.findByText('Licencia anulada');
    expect(
      screen.getByText(
        `Resolución ${ANULADA.resolucion.numero}: la licencia ${ANULADA.nroLicencia} del expediente ${EXPEDIENTE} queda sin efecto.`,
      ),
    ).toBeTruthy();
  });

  it('sin observacion NO se manda nada: el primario sale impedido y dice por que (regla 10)', async () => {
    const peticiones = contesta(PERMISOS_MEDIDOS, 'acepta');
    montar();
    await conLosPermisos();

    fireEvent.click(elQueAbre());
    escribirElActo('ok');

    const primario = elQueAnula();
    expect(primario.getAttribute('aria-disabled')).toBe('true');
    // Impedido, nunca `disabled`: sigue en el tabulador y dice por que.
    expect((primario as HTMLButtonElement).disabled).toBe(false);
    expect(descripcionDe(primario)).toBe('La observacion tiene 2 de los 5 caracteres que necesita como minimo.');
    fireEvent.click(primario);
    expect(screen.queryByRole('alertdialog')).toBeNull();
    expect(anulaciones(peticiones)).toEqual([]);
  });

  it('sin el privilegio de registro sobre el FUE, el boton que abre sale IMPEDIDO y nombra la opcion que falta', async () => {
    const sinRegistro: PermisosDeLaSesion = {
      ...PERMISOS_MEDIDOS,
      fue_edificacion: (PERMISOS_MEDIDOS['fue_edificacion'] ?? []).filter((p) => p !== 'registro'),
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
        'Anular una licencia de edificación pide el privilegio de registro sobre «Formulario único de edificación (FUE)», que esta cuenta no tiene.',
      );
    });
    expect(boton.getAttribute('aria-disabled')).toBe('true');
    expect((boton as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(boton);
    expect(screen.queryByLabelText('Expediente del FUE')).toBeNull();
  });

  it('el 409 de una licencia ya anulada se dice con las palabras del backend, sin mandar a soporte, y lo escrito se queda', async () => {
    const peticiones = contesta(PERMISOS_MEDIDOS, 'ya-estaba');
    montar();
    await conLosPermisos();

    fireEvent.click(elQueAbre());
    escribirElActo(OBSERVACION);
    fireEvent.click(elQueAnula());
    fireEvent.click(within(await screen.findByRole('alertdialog')).getByRole('button', { name: 'Si, confirmar' }));

    await screen.findByText(YA_ANULADA, { exact: false });
    expect(anulaciones(peticiones)).toHaveLength(1);
    // El peldano del 409 (#629): no es una averia, y repetir da lo mismo.
    expect(screen.getByText('El estado actual no admite la operacion', { exact: false })).toBeTruthy();
    expect(document.body.textContent).not.toContain('avise a soporte');
    // Lo escrito sigue ahi: el acto no se hizo, y cerrar obligaria a escribirlo otra vez.
    expect((screen.getByLabelText('Motivo de la anulación') as HTMLInputElement).value).toBe(MOTIVO);
    expect(screen.queryByText('Licencia anulada')).toBeNull();
  });
});

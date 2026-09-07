import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fijarToken } from '../api/identidad.ts';
import { RAIZ, desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import { contestaLaInstalacion } from '../datos/backendMedido.ts';
import { Contribuyentes } from './Contribuyentes.tsx';
import { Panel } from './Panel.tsx';
import { PADRON_AL_EMPEZAR, type EstadoDelPadron } from './estadoDelPadron.ts';

/**
 * Las pantallas mandan lo que el backend EXIGE (#26, AC-4).
 *
 * <h2>Por que hace falta un archivo aparte, y esta medido</h2>
 *
 * Se probo primero sin el: se devolvio `Expediente` a pedir `'/rentas/predios'` pelada —el
 * estado anterior a #26— y **las 1 091 pruebas de esta interfaz pasaron en verde**, las 41
 * archivos. Ninguna miraba la URL que sale de una pantalla servida por el proxy: `backendMedido`
 * espia el `fetch` que el proxy guarda al instalarse, o sea **solo lo que sale a la red**, y las
 * dos lecturas del expediente las contesta el proxy. El 422 que ahora devuelve se dibuja como un
 * aviso de error dentro de una pestaña que ninguna prueba abre.
 *
 * O sea que el arreglo del AC-4 no tenia guarda: se podia deshacer entero sin un solo rojo. Esto
 * es esa guarda.
 *
 * <h2>Como mide</h2>
 *
 * Envolviendo `globalThis.fetch` **despues** de instalar el proxy —el proxy SUSTITUYE `fetch`,
 * asi que un doble puesto antes no ve lo que el proxy atiende—, y comparando cada URL que sale
 * contra `docs/50-api/parametros-de-la-api.json`, que genera `ParametrosDeLaApiTest` de la firma
 * del controlador. Nada de esto se escribe a mano aqui: si el backend deja de exigir un
 * parametro, esta prueba deja de exigirlo sola.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));

interface ParametrosDeUnaOperacion {
  readonly obligatorios: readonly string[];
  readonly algunoDeEstos: readonly (readonly string[])[];
}

const EXIGIDOS = JSON.parse(
  readFileSync(join(AQUI, '../../../docs/50-api/parametros-de-la-api.json'), 'utf8'),
) as Record<string, ParametrosDeUnaOperacion>;

/** Las rutas con parametro, para poder casar `/rentas/contribuyentes/{id}/ficha`. */
function patronDe(ruta: string): RegExp {
  const escapado = ruta
    .split(/(\{\w+\})/)
    .map((trozo) =>
      /^\{\w+\}$/.test(trozo) ? '[^/]+' : trozo.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'),
    )
    .join('');
  return new RegExp(`^${escapado}$`);
}

/** La operacion del contrato que atiende esa peticion, o `null`. */
function operacionDe(metodo: string, ruta: string): string | null {
  return (
    Object.keys(EXIGIDOS).find((clave) => {
      const separador = clave.indexOf(' ');
      return (
        clave.slice(0, separador) === metodo && patronDe(clave.slice(separador + 1)).test(ruta)
      );
    }) ?? null
  );
}

/** Los grupos que esa operacion exige, con los obligatorios como grupos de uno. */
function gruposExigidos(operacion: string): readonly (readonly string[])[] {
  const declarado = EXIGIDOS[operacion];
  if (declarado === undefined) return [];
  return [...declarado.obligatorios.map((nombre) => [nombre]), ...declarado.algunoDeEstos];
}

interface Salida {
  readonly operacion: string;
  readonly url: string;
  readonly faltan: readonly (readonly string[])[];
}

/** Lo que salio de la aplicacion, ya cruzado con lo que el backend exige. */
const salidas: Salida[] = [];

function transporte() {
  // Primero el doble de red, luego el proxy: el proxy delega a lo que encuentra al
  // instalarse, y al reves delegaria al `fetch` de jsdom (lo dice `Contribuyentes.test.tsx`).
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const cuerpo = contestaLaInstalacion(String(entrada));
      return Promise.resolve(
        cuerpo === null ? new Response('{}', { status: 404 }) : Response.json(cuerpo),
      );
    }),
  );
  instalarProxyDeDatos();

  // Y AHORA, encima del proxy: lo que se quiere ver es lo que la aplicacion pide, no lo que
  // el proxy deja pasar.
  const delProxy = globalThis.fetch;
  globalThis.fetch = ((entrada: RequestInfo | URL, opciones?: RequestInit) => {
    const url = new URL(
      typeof entrada === 'string' ? entrada : entrada instanceof URL ? entrada.href : entrada.url,
      'http://localhost',
    );
    if (url.pathname.startsWith(RAIZ)) {
      const metodo = (opciones?.method ?? 'GET').toUpperCase();
      const operacion = operacionDe(metodo, url.pathname.slice(RAIZ.length));
      if (operacion !== null) {
        const faltan = gruposExigidos(operacion).filter(
          (grupo) => !grupo.some((n) => (url.searchParams.get(n) ?? '').trim() !== ''),
        );
        salidas.push({ operacion, url: url.pathname + url.search, faltan });
      }
    }
    return delProxy(entrada, opciones);
  }) as typeof fetch;
}

beforeEach(() => {
  salidas.length = 0;
  fijarToken('un-token-de-prueba');
  transporte();
});

afterEach(() => {
  desinstalarProxyDeDatos();
  vi.unstubAllGlobals();
  fijarToken(null);
});

/** Lo que salio sin lo que el backend exige. */
function sinLoExigido(): string[] {
  return salidas
    .filter((s) => s.faltan.length > 0)
    .map((s) => `${s.operacion}: salio «${s.url}» y falta ${JSON.stringify(s.faltan)}`);
}

function Contenedor() {
  const [estado, fijar] = useState<EstadoDelPadron>(PADRON_AL_EMPEZAR);
  return (
    <Contribuyentes
      estado={estado}
      alCambiar={(cambio) => {
        fijar((actual) => ({ ...actual, ...cambio }));
      }}
      alEnsuciar={vi.fn()}
      alAvisar={vi.fn()}
    />
  );
}

describe('las pantallas mandan lo que el backend exige (#26 AC-4)', () => {
  it('el expediente: `codContribuyente` en los predios y en la deuda', async () => {
    const usuario = userEvent.setup();
    render(<Contenedor />);
    await screen.findByText('SULLON VILCHEZ-JOSE RAUL');
    await usuario.click(screen.getByRole('button', { name: /SULLON VILCHEZ-JOSE RAUL/ }));
    await screen.findByDisplayValue('NATURAL');

    // El contraste, y no sobra: si el expediente dejara de pedirlas, la lista de salidas se
    // quedaria vacia y «ninguna sale sin su parametro» seria cierta sobre el conjunto vacio.
    const pedidas = salidas.map((s) => s.operacion);
    expect(pedidas).toContain('GET /rentas/predios');
    expect(pedidas).toContain('GET /consultas/deuda');

    expect(
      sinLoExigido(),
      'Una pantalla pide una operacion sin el parametro que el backend EXIGE. El proxy la\n' +
        'rechaza con 422 igual que el backend, y el aviso queda dentro de una pestaña: se ve\n' +
        'como una tabla que no carga y no como el defecto que es (#26).',
    ).toEqual([]);
  });

  it('el panel: `ejercicio` en la bitacora', async () => {
    render(<Panel alIrAlPadron={vi.fn()} alAbrirContribuyente={vi.fn()} ejercicio="2026" />);
    await screen.findByText('Emitido del ejercicio');

    expect(salidas.map((s) => s.operacion)).toContain('GET /seguridad/auditoria');
    expect(sinLoExigido()).toEqual([]);
  });

  it('y sin ejercicio la bitacora NO se pide, en vez de pedirse mal', async () => {
    // La sesion todavia no ha contestado: pedir sin `ejercicio` seria un 422 dibujado como
    // «no se pudo leer la bitacora», que manda a mirar el servidor.
    render(<Panel alIrAlPadron={vi.fn()} alAbrirContribuyente={vi.fn()} ejercicio={null} />);
    await screen.findByText('Emitido del ejercicio');

    expect(salidas.map((s) => s.operacion)).not.toContain('GET /seguridad/auditoria');
  });
});

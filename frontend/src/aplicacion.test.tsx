import { render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion } from './aplicacion.tsx';
import { fijarToken } from './api/identidad.ts';
import { desinstalarProxyDeDatos, instalarProxyDeDatos } from './api/proxy.ts';

/**
 * El casco: **la escalera de identidad, peldano a peldano, y la barra que deja de mentir**.
 *
 * <h2>Como se monta esta prueba, y por que en ese orden</h2>
 *
 * El `fetch` se sustituye **antes** de instalar el proxy. No es un detalle: el proxy guarda el
 * `fetch` que encuentra al instalarse y es a ese al que delega lo que esta en `YA_SERVIDAS`. Con
 * el orden al reves, el proxy delegaria al `fetch` de jsdom y estas pruebas medirian una
 * peticion de verdad contra un servidor que no existe.
 *
 * Puesto asi, lo que se ejercita es **el mecanismo entero de F-4 con la lista de I-1 dentro**:
 * las dos rutas de sesion salen por `YA_SERVIDAS` hasta el doble, y las otras dieciocho las
 * sigue contestando el proxy. Si alguien quitara una de las dos de `servidas.ts`, el doble no
 * recibiria la peticion y estas pruebas lo dirian.
 *
 * <h2>Los cuerpos son los que contesta la instalacion, copiados de un `curl`</h2>
 *
 * Y traen **cuatro miembros y no seis**: `status`, `title`, `codigo` y `mensaje`. La cadena de
 * identidad no manda `type` ni `detail`, y darlos por hechos dejaria la explicacion de la
 * pantalla en `undefined` justo en el peldano mas comun. Ver `api/cliente.ts`.
 */

const SESION = '/rentas/api/v1/seguridad/sesion';
const MUNICIPALIDAD = '/rentas/api/v1/seguridad/sesion/municipalidad';

/** Lo que devuelve la instalacion a la cuenta `administrador`, medido el 2026-09-06. */
const CUERPO_DE_SESION = {
  usuarioId: 2,
  cuenta: 'administrador',
  nombre: 'Administrador del Sistema',
  ejercicioDeTrabajo: null,
};
const CUERPO_DE_MUNICIPALIDAD = {
  id: 9,
  ubigeo: '200105',
  nombre: 'Municipalidad Distrital de Catacaos',
  tipo: 'DISTRITAL',
};

function problema(estado: number, codigo: string, mensaje: string): Response {
  return new Response(JSON.stringify({ status: estado, title: mensaje, codigo, mensaje }), {
    status: estado,
    headers: { 'content-type': 'application/problem+json' },
  });
}

/** Un doble que contesta lo mismo a las dos rutas de sesion, y 404 a nada mas. */
function backendQueContesta(deSesion: (ruta: string) => Response) {
  const espia = vi.fn<typeof fetch>((entrada) => {
    const url = typeof entrada === 'string' ? entrada : String(entrada);
    return Promise.resolve(deSesion(url));
  });
  vi.stubGlobal('fetch', espia);
  instalarProxyDeDatos();
  return espia;
}

/** El doble contento: las dos lecturas contestan lo que contesta la instalacion. */
function backendQueIdentifica(sesion: object = CUERPO_DE_SESION) {
  return backendQueContesta((url) =>
    url.endsWith('/municipalidad')
      ? Response.json(CUERPO_DE_MUNICIPALIDAD)
      : Response.json(sesion),
  );
}

beforeEach(() => {
  fijarToken('un-token-de-prueba');
});

afterEach(() => {
  desinstalarProxyDeDatos();
  vi.unstubAllGlobals();
  fijarToken(null);
  window.history.replaceState(null, '', '/');
});

describe('AC7 — la barra ensena lo que contesta el backend, no lo que dibujaba el artboard', () => {
  it('el nombre de la entidad sale de GET /seguridad/sesion/municipalidad', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    // Coincide con la constante que habia, y esa coincidencia no prueba nada por si sola: lo
    // que la hace una afirmacion es la prueba de al lado, con OTRA municipalidad.
    expect(await screen.findByText('Municipalidad Distrital de Catacaos')).toBeInTheDocument();
  });

  it('con otra municipalidad dice la otra, que es lo que la constante no podia hacer', async () => {
    backendQueContesta((url) =>
      url.endsWith('/municipalidad')
        ? Response.json({ ...CUERPO_DE_MUNICIPALIDAD, id: 1, nombre: 'Municipalidad Provincial de Sullana' })
        : Response.json(CUERPO_DE_SESION),
    );

    render(<Aplicacion />);

    expect(await screen.findByText('Municipalidad Provincial de Sullana')).toBeInTheDocument();
    expect(screen.queryByText('Municipalidad Distrital de Catacaos')).not.toBeInTheDocument();
  });

  it('el usuario sale de GET /seguridad/sesion, con sus iniciales y su cuenta', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    expect(
      await screen.findByRole('button', { name: 'Sesión de Administrador del Sistema' }),
    ).toBeInTheDocument();
    // «AS», de las dos primeras palabras. Y la CUENTA debajo, no un papel: el contrato no
    // publica ninguno, y «Rentas · ventanilla» era del artboard.
    expect(screen.getAllByText('AS').length).toBeGreaterThan(0);
    expect(screen.getAllByText('administrador').length).toBeGreaterThan(0);
  });

  it('«J. Cárdenas Vega» y su papel ya no salen en ninguna parte', async () => {
    backendQueIdentifica();
    render(<Aplicacion />);
    await screen.findByRole('banner');

    expect(screen.queryByText(/Cárdenas/)).not.toBeInTheDocument();
    expect(screen.queryByText(/ventanilla/)).not.toBeInTheDocument();
  });

  it('las dos rutas salieron POR LA RED, que es lo que YA_SERVIDAS declara', async () => {
    const espia = backendQueIdentifica();

    render(<Aplicacion />);
    await screen.findByRole('banner');

    const pedidas = espia.mock.calls.map((llamada) => String(llamada[0]));
    expect(pedidas).toContain(SESION);
    expect(pedidas).toContain(MUNICIPALIDAD);
  });
});

describe('AC8 — con ejercicioDeTrabajo nulo, la barra no miente', () => {
  it('el selector dice «Sin fijar» y no un ano cualquiera', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    const selector = await screen.findByLabelText('Ejercicio de trabajo');
    expect(selector).toHaveValue('');
    expect(screen.getByRole('option', { name: 'Sin fijar' })).toBeInTheDocument();
  });

  it('y el subtitulo del panel lo dice, en vez de escribir «Ejercicio 2026»', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    expect(await screen.findByText('Sin ejercicio de trabajo fijado')).toBeInTheDocument();
    expect(screen.queryByText('Ejercicio 2026')).not.toBeInTheDocument();
  });

  it('cuando SI lo fija, lo dice: la misma barra con el mismo codigo', async () => {
    backendQueIdentifica({ ...CUERPO_DE_SESION, ejercicioDeTrabajo: 2026 });

    render(<Aplicacion />);

    expect(await screen.findByText('Ejercicio 2026')).toBeInTheDocument();
    expect(screen.queryByRole('option', { name: 'Sin fijar' })).not.toBeInTheDocument();
    expect(await screen.findByLabelText('Ejercicio de trabajo')).toHaveValue('2026');
  });
});

describe('AC6 — los cuatro peldanos de la escalera se distinguen en la pantalla', () => {
  it('401: vuelve a pedir identidad, y ofrece el boton que lo arregla', async () => {
    backendQueContesta(() =>
      problema(401, 'NO_AUTENTICADO', 'La peticion no trae un token valido'),
    );

    render(<Aplicacion />);

    expect(await screen.findByText('Hay que volver a identificarse')).toBeInTheDocument();
    expect(screen.getByText('La peticion no trae un token valido')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Volver a identificarse' })).toBeInTheDocument();
  });

  it('403 SIN_MUNICIPALIDAD: lo dice, y NO ofrece volver a entrar', async () => {
    backendQueContesta(() =>
      problema(403, 'SIN_MUNICIPALIDAD', 'El token no identifica una municipalidad'),
    );

    render(<Aplicacion />);

    expect(
      await screen.findByText('Esta cuenta no tiene municipalidad asignada'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Lo asigna el administrador del sistema/)).toBeInTheDocument();
    // Entrar otra vez con la misma cuenta trae el mismo token, sin el mismo claim, y el mismo
    // 403. Ofrecerlo seria mandar a dar vueltas a quien tiene que llamar al administrador.
    expect(
      screen.queryByRole('button', { name: 'Volver a identificarse' }),
    ).not.toBeInTheDocument();
  });

  it('403 SIN_PRIVILEGIO: falta un permiso, y se dice que NO es una averia', async () => {
    backendQueContesta(() =>
      problema(403, 'SIN_PRIVILEGIO', 'No tiene el privilegio LECTURA sobre consulta_deuda'),
    );

    render(<Aplicacion />);

    expect(await screen.findByText('Falta un permiso para esta operacion')).toBeInTheDocument();
    expect(
      screen.getByText('No tiene el privilegio LECTURA sobre consulta_deuda'),
    ).toBeInTheDocument();
    expect(screen.getByText(/No es una averia/)).toBeInTheDocument();
    // Reintentar una falta de permiso da la misma falta de permiso.
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument();
  });

  it('404: el detalle del backend se ensena TAL CUAL, porque nombra la cuenta', async () => {
    backendQueContesta(() =>
      problema(
        404,
        'NO_ENCONTRADO',
        "El token identifica a 'administrador', que no es un usuario de esta municipalidad",
      ),
    );

    render(<Aplicacion />);

    expect(await screen.findByText('No se encontro lo solicitado')).toBeInTheDocument();
    // Sin esa frase no hay forma de saber que cuenta hay que dar de alta, que es lo unico que
    // arregla este peldano. Resumirla borraria el dato.
    expect(
      screen.getByText(
        "El token identifica a 'administrador', que no es un usuario de esta municipalidad",
      ),
    ).toBeInTheDocument();
  });

  /**
   * **Se comparan el titulo y el remedio, y NO el texto entero del aviso.** La primera version
   * de esta prueba metia en el conjunto el `textContent` del `role="alert"`, y con eso no media
   * nada: el aviso incluye el `detalle`, que es **lo que dijo el backend**, y el arnes le da
   * cuatro mensajes distintos. Medido — colapsando a proposito los dos 403 en `escalera.ts`,
   * esta prueba se quedaba **VERDE** mientras las otras cuatro se ponian rojas.
   *
   * Lo que hay que comparar es lo que decide el PORT: el titulo y el remedio. Con eso, la misma
   * rotura la caza.
   */
  it('y los cuatro son CUATRO remedios distintos, no el mismo «no se pudo»', async () => {
    const decididos = new Set<string>();
    const peldanos: readonly [number, string, string][] = [
      [401, 'NO_AUTENTICADO', 'sin token'],
      [403, 'SIN_MUNICIPALIDAD', 'sin claim'],
      [403, 'SIN_PRIVILEGIO', 'sin privilegio'],
      [404, 'NO_ENCONTRADO', 'no es usuario de esta municipalidad'],
    ];

    for (const [estado, codigo, mensaje] of peldanos) {
      backendQueContesta(() => problema(estado, codigo, mensaje));
      const { container, unmount } = render(<Aplicacion />);
      await screen.findByRole('alert');
      const titulo = container.querySelector('.kr-aviso__titulo')?.textContent ?? '';
      const remedio = container.querySelector('.kr-puerta__remedio')?.textContent ?? '';
      decididos.add(`${titulo} || ${remedio}`);
      unmount();
      desinstalarProxyDeDatos();
      vi.unstubAllGlobals();
    }

    // Cuatro y no menos. Si dos peldanos colapsaran en el mismo remedio, este conjunto lo diria
    // — y es la unica de las cinco que lo diria sin depender de que el backend mande textos
    // distintos, que es algo que este lado no controla.
    expect(decididos.size).toBe(4);
  });
});

describe('el casco monta el marco cuando la sesion se pudo leer', () => {
  it('monta el marco V6: su barra, su arbol y su barra de pestanas', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    expect(await screen.findByRole('banner')).toBeInTheDocument();
    expect(screen.getByRole('complementary', { name: 'Módulos y submódulos' })).toBeInTheDocument();
    expect(screen.getByRole('group', { name: 'Pestañas abiertas' })).toBeInTheDocument();
  });

  it('y mientras tanto no monta el marco: dice que esta identificando', () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    // El primer fotograma. Sin esto, la aplicacion dibujaria el marco con las cuatro secciones
    // pidiendo datos sin token — cuatro avisos de identidad donde deberia haber uno.
    expect(screen.getByText('Identificando la sesión…')).toBeInTheDocument();
    expect(screen.queryByRole('banner')).not.toBeInTheDocument();
  });
});

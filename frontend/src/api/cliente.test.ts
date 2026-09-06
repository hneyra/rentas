import { afterEach, describe, expect, it, vi } from 'vitest';

import { ErrorDeLaApi, solicitar } from './cliente.ts';
import { fijarToken } from './identidad.ts';

/**
 * Sustituye `fetch` por uno que contesta lo que se le diga, y devuelve el espia.
 *
 * **Clona en cada llamada.** Un `Response` solo se puede leer una vez, asi que devolver el mismo
 * objeto dos veces hace que la segunda peticion muera con «Body has already been read» — un rojo
 * que habla del arnes y no de lo que se estaba midiendo.
 */
function fetchQueContesta(respuesta: Response) {
  const espia = vi.fn<typeof fetch>(() => Promise.resolve(respuesta.clone()));
  vi.stubGlobal('fetch', espia);
  return espia;
}

/** Un `problem+json` con la forma que publica la cadena de identidad: CUATRO miembros. */
function problema(estado: number, codigo: string, mensaje: string): Response {
  return new Response(JSON.stringify({ status: estado, title: mensaje, codigo, mensaje }), {
    status: estado,
    headers: { 'content-type': 'application/problem+json' },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
  fijarToken(null);
});

describe('el cliente de la API de rentas', () => {
  it('cuelga la ruta del prefijo del sistema, porque la ruta dice quien responde', async () => {
    const espia = fetchQueContesta(Response.json({ ok: true }));

    await solicitar('/contribuyentes');

    expect(espia.mock.calls[0]?.[0]).toBe('/rentas/api/v1/contribuyentes');
  });

  it('convierte una respuesta de error en ErrorDeLaApi, con su estado', async () => {
    fetchQueContesta(new Response('', { status: 403 }));

    await expect(solicitar('/contribuyentes')).rejects.toBeInstanceOf(ErrorDeLaApi);
    await expect(solicitar('/contribuyentes')).rejects.toMatchObject({ estado: 403 });
  });
});

describe('AC2 — «solicitar» manda el token, y es el unico que lo hace', () => {
  it('con token, manda Authorization: Bearer', async () => {
    fijarToken('un-token-de-prueba');
    const espia = fetchQueContesta(Response.json({ ok: true }));

    await solicitar('/seguridad/sesion');

    const cabeceras = espia.mock.calls[0]?.[1]?.headers as Record<string, string>;
    expect(cabeceras['Authorization']).toBe('Bearer un-token-de-prueba');
  });

  it('sin token NO manda la cabecera, en vez de mandar «Bearer null»', async () => {
    const espia = fetchQueContesta(Response.json({ ok: true }));

    await solicitar('/seguridad/sesion');

    const cabeceras = espia.mock.calls[0]?.[1]?.headers as Record<string, string>;
    // Un «Bearer null» es un token invalido, y el backend contestaria 401 igual — pero ese 401
    // diria «el token no vale» donde la verdad es «no hay token». Son dos peldanos distintos de
    // la escalera, y este es el unico sitio donde se pueden separar sin adivinar.
    expect(cabeceras['Authorization']).toBeUndefined();
  });

  it('el token se lee en CADA peticion, no se congela al cargar el modulo', async () => {
    const espia = fetchQueContesta(Response.json({ ok: true }));

    await solicitar('/seguridad/sesion');
    fijarToken('el-de-despues');
    await solicitar('/seguridad/sesion');

    // Si el token se leyera una sola vez, renovar la sesion dejaria a la aplicacion mandando
    // para siempre el token caducado, y el sintoma seria un 401 que no se arregla entrando.
    const segunda = espia.mock.calls[1]?.[1]?.headers as Record<string, string>;
    expect(segunda['Authorization']).toBe('Bearer el-de-despues');
  });
});

describe('AC3 — el cliente jamas manda municipalidadId (regla 2, ADR-0005)', () => {
  const RUTAS = [
    '/seguridad/sesion',
    '/seguridad/sesion/municipalidad',
    '/rentas/contribuyentes',
    '/coactiva/deudas',
  ];

  it.each(RUTAS)('ni en la ruta ni en la consulta: %s', async (ruta) => {
    fijarToken('un-token');
    const espia = fetchQueContesta(Response.json({ ok: true }));

    await solicitar(ruta);

    const url = String(espia.mock.calls[0]?.[0]);
    expect(url.toLowerCase()).not.toContain('municipalidadid');
    expect(url.toLowerCase()).not.toContain('municipalidad_id');
  });

  it('ni en las cabeceras, ni en un cuerpo que el cliente componga', async () => {
    fijarToken('un-token');
    const espia = fetchQueContesta(Response.json({ ok: true }));

    await solicitar('/rentas/contribuyentes', { metodo: 'POST', cuerpo: { nombre: 'Rosa' } });

    const opciones = espia.mock.calls[0]?.[1];
    const todo = JSON.stringify(opciones?.headers) + String(opciones?.body ?? '');
    expect(todo.toLowerCase()).not.toContain('municipalidad');
    // Y la propiedad de fondo: el cuerpo que sale es EXACTAMENTE el que le dieron. Esta
    // funcion no compone nada, asi que no tiene donde meter el inquilino aunque quisiera.
    expect(String(opciones?.body)).toBe(JSON.stringify({ nombre: 'Rosa' }));
  });

  it('la ruta de la municipalidad NO es una excepcion: dice de cual es la SESION', async () => {
    fijarToken('un-token');
    const espia = fetchQueContesta(Response.json({ id: 9 }));

    await solicitar('/seguridad/sesion/municipalidad');

    // La palabra esta en la ruta y eso no es enviar el inquilino: es preguntar por el que el
    // backend ya fijo desde el token. Lo prohibido es DECIRLE cual, no preguntarselo.
    expect(espia.mock.calls[0]?.[0]).toBe('/rentas/api/v1/seguridad/sesion/municipalidad');
    expect(String(espia.mock.calls[0]?.[0]).toLowerCase()).not.toContain('municipalidadid');
  });
});

describe('AC5 — ErrorDeLaApi conserva codigo y mensaje del problem+json', () => {
  it('el 401 de la cadena de identidad llega entero', async () => {
    fetchQueContesta(problema(401, 'NO_AUTENTICADO', 'La peticion no trae un token valido'));

    await expect(solicitar('/seguridad/sesion')).rejects.toMatchObject({
      estado: 401,
      codigo: 'NO_AUTENTICADO',
      mensaje: 'La peticion no trae un token valido',
      titulo: 'La peticion no trae un token valido',
    });
  });

  it('los dos 403 se distinguen por su codigo, que es lo que antes se tiraba', async () => {
    fetchQueContesta(problema(403, 'SIN_MUNICIPALIDAD', 'El token no identifica una municipalidad'));
    await expect(solicitar('/seguridad/sesion')).rejects.toMatchObject({
      estado: 403,
      codigo: 'SIN_MUNICIPALIDAD',
    });

    vi.unstubAllGlobals();
    fetchQueContesta(problema(403, 'SIN_PRIVILEGIO', 'No tiene el privilegio necesario'));
    await expect(solicitar('/consultas/deuda')).rejects.toMatchObject({
      estado: 403,
      codigo: 'SIN_PRIVILEGIO',
    });
  });

  it('el mensaje del error es lo que dijo el backend, no «GET /ruta»', async () => {
    fetchQueContesta(
      problema(404, 'NO_ENCONTRADO', "El token identifica a 'x', que no es un usuario"),
    );

    await expect(solicitar('/seguridad/sesion')).rejects.toThrow(
      "El token identifica a 'x', que no es un usuario",
    );
  });

  it('con `detail` y `type` puestos —el 404 de una ruta que no existe— tambien', async () => {
    fetchQueContesta(
      new Response(
        JSON.stringify({
          type: 'https://sgtm.gob.pe/errores/no_encontrado',
          title: 'No se encontro lo solicitado',
          status: 404,
          detail: 'No se encontro lo solicitado',
          instance: '/rentas/api/v1/no-existe',
          codigo: 'NO_ENCONTRADO',
          mensaje: 'No se encontro lo solicitado',
        }),
        { status: 404, headers: { 'content-type': 'application/problem+json' } },
      ),
    );

    await expect(solicitar('/no-existe')).rejects.toMatchObject({
      codigo: 'NO_ENCONTRADO',
      detalle: 'No se encontro lo solicitado',
    });
  });

  it('un cuerpo que no es JSON no tapa el error: se queda con lo que se pidio', async () => {
    // Es lo que devuelve el servidor de Vite sin `server.proxy`: el `index.html` de la
    // aplicacion. Un `await respuesta.json()` sin proteger lanzaria «Unexpected token <» y esa
    // excepcion SUSTITUIRIA al ErrorDeLaApi — la pantalla acabaria ensenando un fallo de
    // parseo en lugar de «no tienes permiso».
    fetchQueContesta(new Response('<!doctype html><html></html>', { status: 200 }));
    await expect(solicitar('/seguridad/sesion')).rejects.toBeInstanceOf(SyntaxError);

    vi.unstubAllGlobals();
    fetchQueContesta(new Response('<!doctype html><html></html>', { status: 500 }));
    await expect(solicitar('/seguridad/sesion')).rejects.toMatchObject({
      estado: 500,
      codigo: null,
      mensaje: null,
      operacion: 'GET /seguridad/sesion',
    });
  });
});

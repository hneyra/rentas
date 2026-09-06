import { afterEach, describe, expect, it, vi } from 'vitest';

import { solicitar, ErrorDeLaApi } from './cliente.ts';
import {
  desinstalarProxyDeDatos,
  instalarProxyDeDatos,
  proxyDeDatosInstalado,
  RAIZ,
} from './proxy.ts';
import { OPERACIONES } from '../datos/operaciones.ts';

/**
 * El proxy intercepta el TRANSPORTE, y solo lo suyo.
 *
 * Lo que estas pruebas sostienen es que la aplicacion no tiene manera de notar quien contesta:
 * pide con `solicitar()`, el cliente de verdad, y recibe un `Response` de verdad. Si el proxy
 * interceptara en la frontera de la aplicacion —una constante importada, un modulo sustituido—
 * nada de esto se podria escribir, porque no habria peticion que mirar.
 */

afterEach(() => {
  desinstalarProxyDeDatos();
  vi.unstubAllGlobals();
});

describe('la instalacion y su desinstalador', () => {
  it('sustituye globalThis.fetch, y devuelve la funcion que lo restaura', () => {
    const antes = globalThis.fetch;

    const desinstalar = instalarProxyDeDatos();

    expect(globalThis.fetch).not.toBe(antes);
    expect(proxyDeDatosInstalado()).toBe(true);

    desinstalar();

    expect(globalThis.fetch).toBe(antes);
    expect(proxyDeDatosInstalado()).toBe(false);
  });

  it('instalar dos veces no apila dos capas: al desinstalar queda el fetch original', () => {
    const antes = globalThis.fetch;

    instalarProxyDeDatos();
    instalarProxyDeDatos();
    desinstalarProxyDeDatos();

    // Si la segunda instalacion guardara como «original» el envoltorio de la primera, esto
    // dejaria una capa pegada — y cada ciclo de instalar y desinstalar dejaria otra.
    expect(globalThis.fetch).toBe(antes);
  });
});

describe('la frontera: que intercepta y que no', () => {
  it('atiende lo que cuelga de la raiz del sistema', async () => {
    instalarProxyDeDatos();

    const respuesta = await fetch(`${RAIZ}/rentas/contribuyentes`);

    expect(respuesta.status).toBe(200);
    expect(respuesta.headers.get('content-type')).toBe('application/json');
  });

  it('la raiz es la del backend: /rentas/api/v1, y no /api/v1 (ADR-0030 §2)', () => {
    expect(RAIZ).toBe('/rentas/api/v1');
  });

  it('el cliente de la aplicacion habla con el proxy sin saberlo', async () => {
    instalarProxyDeDatos();

    // `solicitar()` compone la URL con SU prefijo. Que esto conteste demuestra que los dos
    // prefijos coinciden: si `cliente.ts` dijera `/api/v1`, la peticion se iria al fetch real.
    const contribuyentes = await solicitar<{ contenido: unknown[] }>('/rentas/contribuyentes');

    expect(contribuyentes.contenido).toHaveLength(5);
  });

  it.each([
    ['una fuente tipografica', 'https://fonts.gstatic.com/s/x.woff2'],
    ['un recurso de la propia aplicacion', '/rentas/assets/logo.svg'],
    ['la API de otro sistema', '/catastro/api/v1/predios'],
    ['la API de caja', '/caja/api/v1/recibos'],
  ])('deja pasar %s al fetch de verdad', async (_que, url) => {
    const real = vi.fn<typeof fetch>(() => Promise.resolve(new Response('pasa')));
    vi.stubGlobal('fetch', real);
    instalarProxyDeDatos();

    await fetch(url);

    expect(real).toHaveBeenCalledOnce();
  });
});

describe('una ruta sin respuesta no deja la pantalla en blanco', () => {
  it('contesta 404 en application/problem+json, con el codigo del catalogo', async () => {
    instalarProxyDeDatos();

    const respuesta = await fetch(`${RAIZ}/rentas/no-existe`);

    expect(respuesta.status).toBe(404);
    expect(respuesta.headers.get('content-type')).toBe('application/problem+json');

    const cuerpo = (await respuesta.json()) as Record<string, unknown>;
    // Los cuatro miembros de RFC 9457 mas las dos extensiones del contrato. La interfaz
    // reacciona al `codigo`, que es estable; el texto en castellano se reescribe.
    expect(Object.keys(cuerpo).sort()).toEqual([
      'codigo',
      'detail',
      'mensaje',
      'status',
      'title',
      'type',
    ]);
    expect(cuerpo['codigo']).toBe('NO_ENCONTRADO');
    expect(cuerpo['status']).toBe(404);
    expect(cuerpo['detail']).toContain('GET /rentas/no-existe');
  });

  it('el cliente lo convierte en ErrorDeLaApi con su estado, no en un undefined', async () => {
    instalarProxyDeDatos();

    await expect(solicitar('/rentas/no-existe')).rejects.toBeInstanceOf(ErrorDeLaApi);
    await expect(solicitar('/rentas/no-existe')).rejects.toMatchObject({ estado: 404 });
  });

  it('la ruta que existe con otro verbo da 405 y dice cuales admite (#556)', async () => {
    instalarProxyDeDatos();

    const respuesta = await fetch(`${RAIZ}/rentas/contribuyentes`, { method: 'DELETE' });

    expect(respuesta.status).toBe(405);
    expect(respuesta.headers.get('allow')).toBe('GET');
    expect((await respuesta.json()) as Record<string, unknown>).toMatchObject({
      codigo: 'METODO_NO_ADMITIDO',
    });
  });
});

describe('el proxy no finge semantica (AC8)', () => {
  it('no filtra ni ordena: la cadena de consulta no cambia una coma de la respuesta', async () => {
    instalarProxyDeDatos();

    const sinFiltro = await (await fetch(`${RAIZ}/rentas/predios`)).text();
    const conFiltro = await (
      await fetch(`${RAIZ}/rentas/predios?uso=Comercio&orden=direccion&pagina=3&tamano=1`)
    ).text();

    // Identicas al byte. Fingir el filtro seria inventar una decision que el backend no ha
    // tomado, y la interfaz se acabaria construyendo contra esa invencion.
    expect(conFiltro).toBe(sinFiltro);
  });

  it('no pagina: publica el envoltorio del backend con el conjunto entero', async () => {
    instalarProxyDeDatos();

    const pagina = (await (await fetch(`${RAIZ}/rentas/contribuyentes?pagina=2`)).json()) as {
      contenido: unknown[];
      pagina: number;
      tamano: number;
      totalElementos: number;
      totalPaginas: number;
      hayMas: boolean;
    };

    expect(pagina.pagina).toBe(0);
    expect(pagina.tamano).toBe(pagina.contenido.length);
    expect(pagina.totalElementos).toBe(pagina.contenido.length);
    expect(pagina.totalPaginas).toBe(1);
    expect(pagina.hayMas).toBe(false);
  });

  it('no persiste: dos escrituras iguales devuelven lo mismo, y no dejan rastro', async () => {
    instalarProxyDeDatos();

    const antes = await (await fetch(`${RAIZ}/rentas/contribuyentes`)).text();
    const primera = await (
      await fetch(`${RAIZ}/rentas/predial/calculo-individual`, {
        method: 'POST',
        body: JSON.stringify({ codContribuyente: '00000006550' }),
      })
    ).text();
    const segunda = await (
      await fetch(`${RAIZ}/rentas/predial/calculo-individual`, { method: 'POST', body: '{}' })
    ).text();
    const despues = await (await fetch(`${RAIZ}/rentas/contribuyentes`)).text();

    // El cuerpo enviado tampoco se mira: las dos escrituras piden cosas distintas y contestan
    // lo mismo. Simular persistencia sin reglas de negocio produce un sistema que acepta lo
    // que el backend rechazara.
    expect(segunda).toBe(primera);
    expect(despues).toBe(antes);
  });

  it('una escritura contesta 201 y una lectura 200', async () => {
    instalarProxyDeDatos();

    const lectura = await fetch(`${RAIZ}/rentas/contribuyentes`);
    const escritura = await fetch(`${RAIZ}/rentas/alcabala`, { method: 'POST' });

    expect(lectura.status).toBe(200);
    expect(escritura.status).toBe(201);
  });

  it('los constructores no reciben la peticion: no pueden mirarla', () => {
    // No es un comentario pidiendo que no se filtre: la firma lo impide. Un constructor sin
    // argumentos no tiene con que decidir.
    for (const operacion of OPERACIONES) {
      expect(operacion.cuerpo, `«${operacion.metodo} ${operacion.ruta}»`).toHaveLength(0);
    }
  });
});

describe('la lista de operaciones ya servidas por el backend', () => {
  it('con la lista vacia —la de hoy— nada sale a la red', async () => {
    const real = vi.fn<typeof fetch>(() => Promise.resolve(new Response('{}')));
    vi.stubGlobal('fetch', real);
    instalarProxyDeDatos();

    await fetch(`${RAIZ}/rentas/contribuyentes`);

    expect(real).not.toHaveBeenCalled();
  });

  it('una ruta declarada como servida sale al backend de verdad', async () => {
    const real = vi.fn<typeof fetch>(() => Promise.resolve(Response.json({ delBackend: true })));
    vi.stubGlobal('fetch', real);
    instalarProxyDeDatos({ yaServidas: [{ metodo: 'GET', ruta: '/rentas/contribuyentes' }] });

    const respuesta = await fetch(`${RAIZ}/rentas/contribuyentes`);

    expect(real).toHaveBeenCalledOnce();
    expect(await respuesta.json()).toEqual({ delBackend: true });
  });

  it('y si el backend dice que no la implementa, suena en vez de caer al proxy en silencio', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() => Promise.resolve(new Response('', { status: 501 }))),
    );
    instalarProxyDeDatos({ yaServidas: [{ metodo: 'GET', ruta: '/rentas/contribuyentes' }] });

    const respuesta = await fetch(`${RAIZ}/rentas/contribuyentes`);

    // 502 y no los datos del proxy: que la ruta de la lista y la del backend no cuadren es
    // exactamente lo que se quiere ver, y replegarse en silencio lo esconderia.
    expect(respuesta.status).toBe(502);
    expect((await respuesta.json()) as Record<string, unknown>).toMatchObject({
      detail: expect.stringContaining('src/datos/servidas.ts'),
    });
  });

  /**
   * **El 404 NO se convierte, y hasta I-1 si se convertia.** Es el hallazgo de este issue por el
   * lado del proxy: la conversion daba por hecho que un 404 de una ruta declarada significaba
   * «esa ruta no esta publicada», y no lo significa. El cuarto peldano de la escalera de
   * identidad —«El token identifica a 'X', que no es un usuario de esta municipalidad»— es un
   * 404 legitimo de `GET /seguridad/sesion`, que si existe.
   *
   * Con la conversion puesta, ese peldano no llegaba nunca a la pantalla: se convertia en un 502
   * que acusa a `servidas.ts` de un desajuste que no hay, y mandaba a mirar el archivo
   * equivocado. Y los dos 404 son indistinguibles en el cable, medido con `curl`: los dos traen
   * `codigo: "NO_ENCONTRADO"`.
   *
   * Lo que la conversion protegia lo protege ahora `verificaciones/camino-a-la-api.test.ts`, que
   * exige que cada ruta de `YA_SERVIDAS` sea una clave del contrato. Es estatico y no necesita
   * que nadie levante un backend para decirlo.
   */
  it('un 404 del backend pasa TAL CUAL: es una respuesta, no un desajuste de la lista', async () => {
    const cuerpo = {
      status: 404,
      title: 'No se encontro lo solicitado',
      codigo: 'NO_ENCONTRADO',
      mensaje: "El token identifica a 'administrador', que no es un usuario de esta municipalidad",
    };
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>(() =>
        Promise.resolve(
          new Response(JSON.stringify(cuerpo), {
            status: 404,
            headers: { 'content-type': 'application/problem+json' },
          }),
        ),
      ),
    );
    instalarProxyDeDatos({ yaServidas: [{ metodo: 'GET', ruta: '/seguridad/sesion' }] });

    const respuesta = await fetch(`${RAIZ}/seguridad/sesion`);

    expect(respuesta.status).toBe(404);
    expect((await respuesta.json()) as Record<string, unknown>).toMatchObject({
      codigo: 'NO_ENCONTRADO',
      mensaje: expect.stringContaining('no es un usuario de esta municipalidad'),
    });
  });

  it('las rutas con parametro tambien se reconocen en la lista', async () => {
    const real = vi.fn<typeof fetch>(() => Promise.resolve(Response.json({ delBackend: true })));
    vi.stubGlobal('fetch', real);
    instalarProxyDeDatos({
      yaServidas: [{ metodo: 'GET', ruta: '/rentas/contribuyentes/{id}/ficha' }],
    });

    await fetch(`${RAIZ}/rentas/contribuyentes/00000025673/ficha`);

    expect(real).toHaveBeenCalledOnce();
  });
});

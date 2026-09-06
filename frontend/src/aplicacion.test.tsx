import { act, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion } from './aplicacion.tsx';
import { fijarToken } from './api/identidad.ts';
import { desinstalarProxyDeDatos, instalarProxyDeDatos } from './api/proxy.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
  sinElPrivilegioEspecial,
  sinLosAccesosDe,
} from './marco/seguridadMedida.ts';

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

/** Un doble que contesta lo mismo a todas las rutas de `YA_SERVIDAS`. */
function backendQueContesta(deSeguridad: (ruta: string) => Response) {
  const espia = vi.fn<typeof fetch>((entrada) => {
    const url = typeof entrada === 'string' ? entrada : String(entrada);
    return Promise.resolve(deSeguridad(url));
  });
  vi.stubGlobal('fetch', espia);
  instalarProxyDeDatos();
  return espia;
}

/** El envoltorio paginado con que el backend publica los dos catalogos. */
function paginado(contenido: readonly object[]): Response {
  return Response.json({
    contenido,
    pagina: 0,
    tamano: 200,
    totalElementos: contenido.length,
    totalPaginas: 1,
    hayMas: false,
  });
}

/**
 * El doble contento: **las CINCO lecturas** contestan lo que contesta la instalacion.
 *
 * Eran dos hasta I-1 y son cinco desde I-3, y las tres nuevas no son opcionales: sin ellas el
 * casco no compone ningun arbol y no monta ningun marco —negacion por omision, ADR-0013—, asi
 * que **todas las pruebas de la barra se quedarian midiendo la pantalla de «no se pudo leer el
 * arbol»** sin decir que les falta. Se contestan desde `seguridadMedida.ts` para que lo que el
 * doble devuelve sea lo mismo que devuelve la instalacion.
 */
function backendQueIdentifica(sesion: object = CUERPO_DE_SESION) {
  return backendQueContesta((url) => deSeguridadMedida(url) ?? Response.json(sesion));
}

/** Lo que la instalacion contesta en las cuatro rutas que no son `GET /seguridad/sesion`. */
function deSeguridadMedida(url: string): Response | null {
  if (url.endsWith('/municipalidad')) return Response.json(CUERPO_DE_MUNICIPALIDAD);
  if (url.includes('/seguridad/modulos')) return paginado(MODULOS_MEDIDOS);
  if (url.includes('/seguridad/accesos')) return paginado(ACCESOS_MEDIDOS);
  if (url.includes('/seguridad/sesion/permisos')) return Response.json(PERMISOS_MEDIDOS);
  return null;
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
        ? Response.json({
            ...CUERPO_DE_MUNICIPALIDAD,
            id: 1,
            nombre: 'Municipalidad Provincial de Sullana',
          })
        : // Las otras cuatro, las medidas: sin ellas no se compone arbol y no hay barra que
          // mirar. Ver `backendQueIdentifica`.
          (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
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

describe('AC4 de I-3 — con ejercicioDeTrabajo nulo, la barra sigue sin mentir', () => {
  it('el mando dice «Sin fijar» y no un ano cualquiera', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);

    // Era un `<select>` con `value=''` y una opcion «Sin fijar»; desde I-3 es el boton que
    // abre el acto. Lo que se afirma es lo mismo y es lo que el AC pide: **que no diga un
    // ano**. Las dos cuentas de la instalacion arrancan asi, medido.
    expect(await screen.findByRole('button', { name: 'Sin fijar' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '2026' })).not.toBeInTheDocument();
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
    expect(await screen.findByRole('button', { name: '2026' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sin fijar' })).not.toBeInTheDocument();
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

describe('AC6 de I-3 — las cuatro rutas nuevas salen POR LA RED, no por el proxy', () => {
  it('las cinco lecturas de seguridad se piden de verdad', async () => {
    const espia = backendQueIdentifica();

    render(<Aplicacion />);
    await screen.findByRole('banner');

    const pedidas = espia.mock.calls.map((llamada) => String(llamada[0]));
    expect(pedidas).toContain(SESION);
    expect(pedidas).toContain(MUNICIPALIDAD);
    expect(pedidas).toContain('/rentas/api/v1/seguridad/modulos');
    expect(pedidas).toContain('/rentas/api/v1/seguridad/sesion/permisos');
    // Con su `tamano`: el catalogo tiene 134 accesos y el tamano por omision es 20. Medido
    // contra la instalacion, sin el llegan veinte —siete paginas— y **cinco de los diez
    // modulos se caen del arbol**: Inicio, Fiscalización, Tránsito, Consultas y Valores. Un
    // panel con la mitad, y ningun error. Ver `RUTAS.accesos`.
    expect(pedidas).toContain('/rentas/api/v1/seguridad/accesos?tamano=200');
  });
});

describe('AC2 de I-3 — lo que la cuenta no puede abrir no llega a la pantalla', () => {
  it('con la matriz entera, el panel ensena los diez modulos', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);
    const panel = await screen.findByRole('complementary', { name: 'Módulos y submódulos' });

    expect(within(panel).getByRole('button', { name: /^Coactiva/ })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: /^Rentas · Registro/ })).toBeInTheDocument();
  });

  it('sin los accesos de Coactiva, Coactiva no esta en el panel — y los demas si', async () => {
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/permisos')
        ? Response.json(sinLosAccesosDe('COACTIVA'))
        : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );

    render(<Aplicacion />);
    const panel = await screen.findByRole('complementary', { name: 'Módulos y submódulos' });

    expect(within(panel).queryByRole('button', { name: /^Coactiva/ })).toBeNull();
    // La otra direccion, que el AC2 pide explicitamente: un arbol vacio pasaria la de arriba.
    expect(within(panel).getByRole('button', { name: /^Rentas · Registro/ })).toBeInTheDocument();
    expect(within(panel).getByRole('button', { name: /^Fiscalización/ })).toBeInTheDocument();
  });

  it('y el hash a un destino escondido NO abre su pestana', async () => {
    // La puerta trasera: el panel, el lanzador y la paleta ya ofrecen solo lo compuesto, pero
    // un enlace guardado —o la barra de direcciones— no pasa por ninguno de los tres.
    window.history.replaceState(null, '', '#coa-exp');
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/permisos')
        ? Response.json(sinLosAccesosDe('COACTIVA'))
        : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );

    render(<Aplicacion />);
    const barra = await screen.findByRole('group', { name: 'Pestañas abiertas' });

    expect(within(barra).queryByText('Expedientes')).toBeNull();
    expect(within(barra).getByText('Panel')).toBeInTheDocument();
  });

  it('pero el hash a un destino que SI puede abrir, lo abre', async () => {
    window.history.replaceState(null, '', '#coa-exp');
    backendQueIdentifica();

    render(<Aplicacion />);
    const barra = await screen.findByRole('group', { name: 'Pestañas abiertas' });

    // Sin este caso, «no abre nada nunca» pasaria la prueba de arriba.
    expect(within(barra).getByText('Expedientes')).toBeInTheDocument();
  });

  /**
   * **El hash tiene DOS caminos, y esta prueba existe porque una rotura salio entera en verde.**
   *
   * Al arrancar, la pestana no se abre llamando a `abrir`: la crea el inicializador del
   * `useReducer`. Asi que las dos pruebas de arriba —que ponen el hash ANTES de montar— cruzan
   * la validacion del inicializador y **no tocan la guarda de `abrir`**. Medido: quitando esa
   * guarda, las 71 pruebas de estos dos archivos seguian en VERDE.
   *
   * El segundo camino es este: cambiar el hash con la aplicacion ya abierta, que es lo que pasa
   * al pegar un enlace en la barra de direcciones sin recargar. Las dos mitades hacen falta y
   * ninguna cubre a la otra.
   */
  it('y cambiar el hash CON LA APLICACION ABIERTA tampoco abre lo escondido', async () => {
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/permisos')
        ? Response.json(sinLosAccesosDe('COACTIVA'))
        : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );

    render(<Aplicacion />);
    const barra = await screen.findByRole('group', { name: 'Pestañas abiertas' });

    window.location.hash = '#coa-exp';
    await act(async () => {
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });

    expect(within(barra).queryByText('Expedientes')).toBeNull();
  });

  it('y con permiso, el mismo gesto SI la abre', async () => {
    backendQueIdentifica();

    render(<Aplicacion />);
    const barra = await screen.findByRole('group', { name: 'Pestañas abiertas' });

    window.location.hash = '#coa-exp';
    await act(async () => {
      window.dispatchEvent(new HashChangeEvent('hashchange'));
    });

    expect(within(barra).getByText('Expedientes')).toBeInTheDocument();
  });

  /**
   * **Estas dos las escribio una rotura que salio casi entera en verde, y son el hallazgo.**
   *
   * Devolviendo la paleta a aplanar el catalogo del artboard —los cuarenta destinos, calculados
   * una vez al cargar el modulo, que es como estaba antes de I-3— el arbol de pruebas se quedo
   * en **955 de 956**. El unico rojo era `Marco.test.tsx > AC8 — el teclado`, una prueba de F-3
   * sobre las flechas, y caia **de rebote**: porque el orden del artboard y el del backend no
   * coinciden, no porque nadie estuviera mirando los permisos.
   *
   * O sea que la puerta trasera de la paleta **no la cerraba ninguna prueba de este issue**. El
   * panel escondia Coactiva y `Ctrl+K` la seguia ofreciendo; el destino no llegaba a abrirse
   * —la guarda de `abrir` lo para—, lo que deja algo peor que un modulo de mas: un resultado
   * que se ve, se marca, se pulsa y no hace nada.
   */
  it('la paleta tampoco ofrece lo escondido: es la puerta trasera de `Ctrl+K`', async () => {
    const usuario = userEvent.setup();
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/permisos')
        ? Response.json(sinLosAccesosDe('COACTIVA'))
        : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );

    render(<Aplicacion />);
    await screen.findByRole('complementary', { name: 'Módulos y submódulos' });
    await usuario.keyboard('{Control>}k{/Control}');
    await usuario.type(screen.getByLabelText('Buscar un destino'), 'cartera');

    const paleta = screen.getByRole('dialog', { name: 'Buscar' });
    // «Cartera y lotes» es de Valores y se puede; «Cartera y medidas» es de Coactiva y no.
    expect(within(paleta).queryByText('Cartera y medidas')).toBeNull();
    expect(within(paleta).getByText('1 resultado')).toBeInTheDocument();
  });

  it('y con los permisos enteros la paleta SI las ofrece las dos', async () => {
    const usuario = userEvent.setup();
    backendQueIdentifica();

    render(<Aplicacion />);
    await screen.findByRole('complementary', { name: 'Módulos y submódulos' });
    await usuario.keyboard('{Control>}k{/Control}');
    await usuario.type(screen.getByLabelText('Buscar un destino'), 'cartera');

    // La otra direccion. Sin esta, una paleta que no ofreciera nunca nada pasaria la de arriba.
    const paleta = screen.getByRole('dialog', { name: 'Buscar' });
    expect(within(paleta).getByText('Cartera y medidas')).toBeInTheDocument();
    expect(within(paleta).getByText('2 resultados')).toBeInTheDocument();
  });

  /**
   * Y la misma puerta por el lanzador de modulos, que es la tercera lista que hay.
   *
   * Tres listas ofrecen destinos —el panel, la paleta y el lanzador— y las tres tienen que salir
   * del mismo sitio. La del lanzador la habria dejado abierta el mismo descuido: recorria
   * `ARBOL` hasta I-3.
   */
  it('el lanzador de modulos tampoco ofrece el que la cuenta no puede abrir', async () => {
    const usuario = userEvent.setup();
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/permisos')
        ? Response.json(sinLosAccesosDe('COACTIVA'))
        : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );

    render(<Aplicacion />);
    await screen.findByRole('complementary', { name: 'Módulos y submódulos' });
    await usuario.click(screen.getByRole('button', { name: 'Ver todos los módulos' }));

    const lanzador = screen.getByRole('dialog', { name: 'Módulos del sistema' });
    expect(within(lanzador).queryByText('Coactiva')).toBeNull();
    expect(within(lanzador).getByText('Fiscalización')).toBeInTheDocument();
    // Y la nota cuenta lo que hay, en vez de decir «los diez» siempre.
    expect(within(lanzador).getByText('Los 9 comparten este marco')).toBeInTheDocument();
  });
});

describe('AC3 de I-3 — el acto entero, del boton al PUT y de vuelta a la barra', () => {
  /** El doble que ademas acepta el `PUT` y contesta la sesion que quiera. */
  function backendQueAceptaElCambio(fijado: number | null) {
    return backendQueContesta((url) => {
      if (url.includes('/seguridad/sesion/ejercicio')) {
        // La forma de `SesionResource`: id, usuarioId, inicio y ejercicioDeTrabajo. **No trae
        // ni la cuenta ni el nombre**, y por eso de aqui solo se toma el ejercicio.
        return Response.json({
          id: 2,
          usuarioId: 2,
          inicio: '2026-09-06T22:01:48.190388Z',
          ejercicioDeTrabajo: fijado,
        });
      }
      return deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION);
    });
  }

  const hacerElActo = async (
    usuario: ReturnType<typeof userEvent.setup>,
    anio: string,
    observacion: string,
  ) => {
    await usuario.click(await screen.findByRole('button', { name: 'Sin fijar' }));
    await usuario.type(screen.getByLabelText('Ejercicio'), anio);
    await usuario.type(screen.getByLabelText('Observación'), observacion);
    await usuario.click(screen.getByRole('button', { name: 'Cambiar el ejercicio' }));
  };

  it('manda un PUT con el ejercicio y la observacion en el cuerpo', async () => {
    const usuario = userEvent.setup();
    const espia = backendQueAceptaElCambio(2026);

    render(<Aplicacion />);
    await hacerElActo(usuario, '2026', 'Apertura del ejercicio');

    const llamada = espia.mock.calls.find((c) => String(c[0]).includes('/sesion/ejercicio'));
    expect(llamada).toBeDefined();
    expect(llamada?.[1]?.method).toBe('PUT');
    expect(JSON.parse(String(llamada?.[1]?.body))).toEqual({
      ejercicio: 2026,
      observacion: 'Apertura del ejercicio',
    });
  });

  /**
   * **Esta prueba existe porque su ausencia dejo una rotura entera en verde.**
   *
   * Devolviendo desde el casco el ejercicio que se TECLEO en vez del que contesto el backend
   * —`return ejercicio` en lugar de `return sesionNueva.ejercicioDeTrabajo`—, las 73 pruebas de
   * `aplicacion` y `Marco` seguian pasando. Y es que en el caso normal las dos cifras son la
   * misma, asi que el defecto y el acierto son indistinguibles: la unica manera de separarlos
   * es hacer que el backend conteste **otra**.
   *
   * No es un caso de laboratorio. El backend abre la sesion si no habia y fija el ejercicio; el
   * dia que decida normalizarlo, rechazarlo a medias o devolver el que ya regia, una barra que
   * dice lo que se tecleo estaria afirmando un ejercicio que la sesion no tiene — y todas las
   * cifras de todas las pantallas se leerian como suyas.
   */
  it('la barra pasa a decir el que contesto el BACKEND, no el que se tecleo', async () => {
    const usuario = userEvent.setup();
    backendQueAceptaElCambio(2025);

    render(<Aplicacion />);
    await hacerElActo(usuario, '2026', 'Apertura del ejercicio');

    expect(await screen.findByRole('button', { name: '2025' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '2026' })).toBeNull();
  });

  it('y si el backend contesta que no quedo ninguno, la barra vuelve a «Sin fijar»', async () => {
    const usuario = userEvent.setup();
    backendQueAceptaElCambio(null);

    render(<Aplicacion />);
    await hacerElActo(usuario, '2026', 'Apertura del ejercicio');

    // `ejercicioDeTrabajo` es `@Nullable` en el `record`, asi que esta respuesta es posible y
    // la barra tiene que saber decirla. Inventar un 2026 aqui seria justo el AC4 al reves.
    expect(await screen.findByRole('button', { name: 'Sin fijar' })).toBeInTheDocument();
  });

  it('el 422 del backend llega a la pantalla con sus palabras, y no como una averia', async () => {
    const usuario = userEvent.setup();
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/ejercicio')
        ? problema(
            422,
            'VALIDACION',
            'La observacion debe explicar el cambio: al menos 5 caracteres, y no espacios en blanco (ADR-0008)',
          )
        : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );

    render(<Aplicacion />);
    await hacerElActo(usuario, '2026', 'corto');

    expect(
      await screen.findByText(/al menos 5 caracteres, y no espacios en blanco/),
    ).toBeInTheDocument();
    // Y la barra NO cambia: no se fijo nada, asi que decir 2026 seria mentir.
    expect(screen.getByRole('button', { name: 'Sin fijar' })).toBeInTheDocument();
  });
});

describe('AC5 de I-3 — sin `especial` sobre `cambiar_anio` no hay mando en la barra', () => {
  it('la cuenta sin el privilegio ve el ejercicio y no puede cambiarlo', async () => {
    backendQueContesta((url) =>
      url.includes('/seguridad/sesion/permisos')
        ? Response.json(sinElPrivilegioEspecial())
        : (deSeguridadMedida(url) ?? Response.json({ ...CUERPO_DE_SESION, ejercicioDeTrabajo: 2026 })),
    );

    render(<Aplicacion />);
    await screen.findByRole('banner');

    expect(screen.getByText('2026')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '2026' })).toBeNull();
  });

  it('y con el privilegio, si', async () => {
    // La otra direccion: esconderlo siempre pasaria la prueba de arriba.
    backendQueIdentifica({ ...CUERPO_DE_SESION, ejercicioDeTrabajo: 2026 });

    render(<Aplicacion />);

    expect(await screen.findByRole('button', { name: '2026' })).toBeInTheDocument();
  });
});

describe('AC7 — si el arbol no se puede leer, se dice y se ofrece reintentar', () => {
  /** El doble que identifica bien y falla SOLO en una de las tres lecturas del arbol. */
  function backendSinArbol(cual: string, respuesta: Response) {
    return backendQueContesta((url) =>
      url.includes(cual) ? respuesta : (deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION)),
    );
  }

  it.each([
    ['/seguridad/modulos'],
    ['/seguridad/accesos'],
    ['/seguridad/sesion/permisos'],
  ])('un 403 SIN_PRIVILEGIO en «%s» no deja un marco vacio', async (ruta) => {
    backendSinArbol(
      ruta,
      problema(403, 'SIN_PRIVILEGIO', `No tiene el privilegio LECTURA sobre ${ruta}`),
    );

    render(<Aplicacion />);

    expect(await screen.findByText('No se pudo leer el árbol de módulos')).toBeInTheDocument();
    // Y NO se dibuja el marco. Un marco sin arbol se ve exactamente igual que una cuenta sin
    // modulos, y las dos se arreglan en sitios distintos.
    expect(screen.queryByRole('complementary', { name: 'Módulos y submódulos' })).toBeNull();
  });

  it('nombra las dos opciones de administracion que las dos lecturas piden', async () => {
    backendSinArbol(
      '/seguridad/modulos',
      problema(403, 'SIN_PRIVILEGIO', 'No tiene el privilegio LECTURA sobre modulos'),
    );

    render(<Aplicacion />);
    await screen.findByText('No se pudo leer el árbol de módulos');

    // Es el unico dato con el que se arregla: `GET /seguridad/modulos` declara
    // `@RequiereAcceso(acceso = "modulos")` y `GET /seguridad/accesos`, `acceso = "accesos"`.
    // Una cuenta de ventanilla no tiene por que tenerlas — y sin ellas se queda sin arbol.
    expect(screen.getByText(/«Módulos del sistema» y «Accesos y políticas»/)).toBeInTheDocument();
  });

  it('ofrece reintentar AUNQUE no sea una averia, y reintentar vuelve a pedirlo todo', async () => {
    // `escalera.ts` argumenta que reintentar una falta de permiso da la misma falta de permiso.
    // Aqui la regla es otra y el motivo esta escrito en `SinArbol.tsx`: ADR-0013 dice que la
    // matriz se vuelve a pedir «asi un cambio de permisos entra sin que el usuario cierre
    // sesion», o sea que el remedio surte efecto DURANTE la sesion y esto es el gesto con el
    // que entra.
    const usuario = userEvent.setup();
    let concedido = false;
    const espia = backendQueContesta((url) => {
      if (url.includes('/seguridad/modulos') && !concedido) {
        return problema(403, 'SIN_PRIVILEGIO', 'No tiene el privilegio LECTURA sobre modulos');
      }
      return deSeguridadMedida(url) ?? Response.json(CUERPO_DE_SESION);
    });

    render(<Aplicacion />);
    await screen.findByText('No se pudo leer el árbol de módulos');
    const antes = espia.mock.calls.length;

    concedido = true;
    await usuario.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(
      await screen.findByRole('complementary', { name: 'Módulos y submódulos' }),
    ).toBeInTheDocument();
    expect(espia.mock.calls.length).toBeGreaterThan(antes);
  });

  it('un 401 en el arbol manda a identificarse, y no a pedir un permiso', async () => {
    backendSinArbol(
      '/seguridad/modulos',
      problema(401, 'NO_AUTENTICADO', 'La peticion no trae un token valido'),
    );

    render(<Aplicacion />);
    await screen.findByText('No se pudo leer el árbol de módulos');

    expect(screen.getByRole('button', { name: 'Volver a identificarse' })).toBeInTheDocument();
  });

  it('y un fallo de IDENTIDAD gana al del arbol: primero quien eres', async () => {
    // Las cinco lecturas pasan por la misma cadena de identidad, asi que un token caducado las
    // tumba todas a la vez. Mirar el arbol primero diria «no se pudo leer el árbol de módulos»
    // cuando lo que hay que hacer es volver a entrar.
    backendQueContesta(() =>
      problema(401, 'NO_AUTENTICADO', 'La peticion no trae un token valido'),
    );

    render(<Aplicacion />);

    expect(await screen.findByText('Hay que volver a identificarse')).toBeInTheDocument();
    expect(screen.queryByText('No se pudo leer el árbol de módulos')).toBeNull();
  });
});

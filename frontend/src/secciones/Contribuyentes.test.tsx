import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { fijarToken } from '../api/identidad.ts';
import { desinstalarProxyDeDatos, instalarProxyDeDatos } from '../api/proxy.ts';
import {
  BUSQUEDA_APROXIMADA,
  ORDENADO_POR_NOMBRE_FILAS,
  PAGINA_0_FILAS,
  PAGINA_1_FILAS,
  POR_NOMBRE_APROXIMADO_FILAS,
  TOTAL_DEL_PADRON,
  TOTAL_DE_LA_BUSQUEDA,
  contestaLaInstalacion,
  envolver,
} from '../datos/backendMedido.ts';
import { Marco } from '../marco/Marco.tsx';
import { MUNICIPALIDAD_MEDIDA, conEjercicio } from '../marco/sesionMedida.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
} from '../marco/seguridadMedida.ts';
import { componerArbol } from '../marco/composicion.ts';
import { Contribuyentes } from './Contribuyentes.tsx';
import { NUEVO, PADRON_AL_EMPEZAR, type EstadoDelPadron } from './estadoDelPadron.ts';
import { SECCIONES_DEL_EXPEDIENTE } from './expediente.ts';

/**
 * El padron y el expediente, **contra las respuestas de la instalacion** (I-4).
 *
 * <h2>Como se monta, y por que en ese orden</h2>
 *
 * El `fetch` se sustituye **antes** de instalar el proxy, como en `aplicacion.test.tsx`: el proxy
 * guarda el `fetch` que encuentra al instalarse y es a ese al que delega lo que esta en
 * `YA_SERVIDAS`. Con el orden al reves, el proxy delegaria al `fetch` de jsdom y estas pruebas
 * medirian una peticion de verdad contra un servidor que no existe.
 *
 * Puesto asi, lo que se ejercita es el mecanismo entero: **las seis rutas de I-4 salen por
 * `YA_SERVIDAS` hasta el doble** —con su cadena de consulta, que es lo que se comprueba— y las
 * dos del expediente que este issue no enciende las sigue contestando el proxy. Si alguien
 * quitara una de las seis de `servidas.ts`, el doble no recibiria su peticion y varias de estas
 * pruebas lo dirian por su nombre.
 *
 * <h2>Y lo que contesta el doble esta medido, no inventado</h2>
 *
 * Sale de `datos/backendMedido.ts`, que es `curl` contra la instalacion del 2026-09-07. El caso
 * que decide la mitad de este archivo es la busqueda: `?nombreRazonSocial=sulon vilchez` devuelve
 * **74** elementos cuyos tres primeros nombres **no contienen la cadena tecleada**. Un filtro del
 * cliente devolveria cero; el servidor devuelve esos tres. No hace falta espiar nada para
 * distinguirlos — basta mirar la pantalla.
 */

const IDENTIDAD = {
  sesion: conEjercicio(2026),
  municipalidad: MUNICIPALIDAD_MEDIDA,
  // El arbol compuesto de la captura de la instalacion: diez modulos, los mismos que estas
  // pruebas daban por hecho cuando `ARBOL` era la navegacion entera (I-3).
  arbol: componerArbol(MODULOS_MEDIDOS, ACCESOS_MEDIDOS, PERMISOS_MEDIDOS).modulos,
  permisos: PERMISOS_MEDIDOS,
  alCambiarEjercicio: () => Promise.resolve(2026),
  alSalir: vi.fn(),
};

const ensuciada = vi.fn();
const avisada = vi.fn();

/** Un problema en `problem+json`, con la forma que publica `ManejadorDeErrores`. */
function problema(estado: number, codigo: string, mensaje: string): Response {
  return new Response(JSON.stringify({ status: estado, title: mensaje, codigo, mensaje }), {
    status: estado,
    headers: { 'content-type': 'application/problem+json' },
  });
}

/**
 * El doble: contesta lo que contesta la instalacion, y deja anular una ruta.
 *
 * `anular` sirve para los dos casos que no se pueden medir con el backend sano —porque hoy
 * contesta bien— y que son justo los que hay que distinguir de «no hay»: que una lectura falle,
 * y que una lista llegue recortada.
 */
function backendMedido(anular?: (url: string) => Response | null) {
  const espia = vi.fn<typeof fetch>((entrada) => {
    const url = typeof entrada === 'string' ? entrada : String(entrada);
    const propia = anular?.(url) ?? null;
    if (propia !== null) {
      return Promise.resolve(propia);
    }
    const cuerpo = contestaLaInstalacion(url);
    return Promise.resolve(
      cuerpo === null
        ? problema(404, 'NO_ENCONTRADO', `El doble no mide «${url}».`)
        : Response.json(cuerpo),
    );
  });
  vi.stubGlobal('fetch', espia);
  instalarProxyDeDatos();
  return espia;
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

/** El contenedor que hace lo que hace el marco: guardar el estado de la seccion. */
function Contenedor({ inicial }: { readonly inicial?: Partial<EstadoDelPadron> }) {
  const [estado, fijar] = useState<EstadoDelPadron>({ ...PADRON_AL_EMPEZAR, ...inicial });
  return (
    <Contribuyentes
      estado={estado}
      alCambiar={(cambio) => {
        fijar((actual) => ({ ...actual, ...cambio }));
      }}
      alEnsuciar={ensuciada}
      alAvisar={avisada}
    />
  );
}

/** Monta el padron y espera a que la primera pagina este servida. */
async function montar(inicial?: Partial<EstadoDelPadron>) {
  const usuario = userEvent.setup();
  render(<Contenedor inicial={inicial} />);
  await screen.findByText('SULLON VILCHEZ-JOSE RAUL');
  return usuario;
}

/** Los nombres de los contribuyentes que se ven, en su orden. */
function listados(): string[] {
  return screen
    .getAllByRole('button')
    .filter((boton) => boton.className.includes('kr-padron__fila'))
    .map((boton) => boton.querySelector('.kr-padron__nombre')?.textContent ?? '');
}

/**
 * Un alta con **todos los obligatorios llenos menos el documento**.
 *
 * Existe porque sin ella la compuerta no se puede medir: mientras quede un obligatorio vacio,
 * `puedeCrear` es falso por ESO, y una prueba que teclee solo el documento y compruebe que no
 * se puede crear pasa igual aunque la compuerta no mire nada. Se compone de la propia definicion
 * del formulario, para que anadir un campo obligatorio no la deje coja en silencio.
 */
function conTodoLlenoMenosElDocumento(docTipo: string, docNumero: string) {
  const vals: Record<string, string> = {};
  for (const seccion of SECCIONES_DEL_EXPEDIENTE) {
    for (const campo of seccion.campos) {
      if (campo.opcional !== true && campo.tipo !== 'ro' && campo.tipo !== 'chk') {
        vals[campo.clave] = 'x';
      }
    }
  }
  return { elegido: NUEVO, paso: SECCIONES_DEL_EXPEDIENTE.length - 1, vals: { ...vals, docTipo, docNumero } };
}

const buscador = () => screen.getByRole('textbox', { name: 'Buscar en el padrón' });
const criterio = () => screen.getByRole('combobox', { name: 'Buscar por' });
const chip = (rotulo: string) => screen.getByRole('button', { name: rotulo, pressed: false });
const pedidas = (espia: ReturnType<typeof backendMedido>) =>
  espia.mock.calls.map((llamada) => String(llamada[0]));

describe('AC1 — el padron se llena del envoltorio paginado, leido tal cual', () => {
  it('las filas son las que contesto `GET /rentas/contribuyentes`', async () => {
    backendMedido();

    await montar();

    expect(listados()).toEqual(PAGINA_0_FILAS.map((quien) => quien.nombreRazonSocial));
  });

  it('cada fila lleva su documento y su tipo de persona', async () => {
    backendMedido();
    await montar();

    expect(screen.getByText('DNI 29614026 · NATURAL')).toBeInTheDocument();
    expect(screen.getByText('00000000008')).toBeInTheDocument();
  });

  it('la cuenta es `totalElementos`, y NO el largo de lo que llego', async () => {
    // **Es el AC1 y el AC2 a la vez.** `contenido` trae cinco filas y el padron tiene 10 603:
    // una pantalla que contara sus filas diria «5» y estaria diciendo que el padron de Catacaos
    // tiene cinco contribuyentes.
    backendMedido();
    await montar();

    expect(screen.getByText('10,603 contribuyentes')).toBeInTheDocument();
    expect(screen.queryByText(/^5 /)).toBeNull();
  });

  it('la peticion sale con `pagina` y `tamano`, que es lo que hace que sea una ventana', async () => {
    const espia = backendMedido();

    await montar();

    expect(pedidas(espia)).toContain('/rentas/api/v1/rentas/contribuyentes?pagina=0&tamano=20&ordenarPor=codigoContribuyente');
  });
});

describe('AC2 — la paginacion funciona sobre 10 603 filas', () => {
  it('dice en que pagina se esta y cuantas hay, con las cifras del backend', async () => {
    backendMedido();
    await montar();

    // 531 paginas de veinte. Sale de `totalPaginas`, que llega en el envoltorio.
    expect(screen.getByText('Página 1 de 531')).toBeInTheDocument();
  });

  it('«Siguiente» pide la pagina 1 y ensena OTRAS filas', async () => {
    const espia = backendMedido();
    const usuario = await montar();

    await usuario.click(screen.getByRole('button', { name: 'Página siguiente' }));

    expect(await screen.findByText('CORTEZ DE IPANAQUE-MARIA ANTONIETA')).toBeInTheDocument();
    expect(pedidas(espia).some((url) => url.includes('pagina=1'))).toBe(true);
    // Ninguna de la pagina 0 sigue ahi: es una ventana, no un «cargar mas».
    expect(listados()).toEqual(PAGINA_1_FILAS.map((quien) => quien.nombreRazonSocial));
    expect(screen.getByText('Página 2 de 531')).toBeInTheDocument();
  });

  it('«Página anterior» vuelve, y en la primera no lleva a ninguna parte', async () => {
    backendMedido();
    const usuario = await montar();

    expect(screen.getByRole('button', { name: 'Página anterior' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );

    await usuario.click(screen.getByRole('button', { name: 'Página siguiente' }));
    await screen.findByText('CORTEZ DE IPANAQUE-MARIA ANTONIETA');
    await usuario.click(screen.getByRole('button', { name: 'Página anterior' }));

    expect(await screen.findByText('SULLON VILCHEZ-JOSE RAUL')).toBeInTheDocument();
    expect(screen.getByText('Página 1 de 531')).toBeInTheDocument();
  });

  it('la cuenta NO se recalcula al pasar de pagina: la dice el backend', async () => {
    backendMedido();
    const usuario = await montar();

    await usuario.click(screen.getByRole('button', { name: 'Página siguiente' }));
    await screen.findByText('CORTEZ DE IPANAQUE-MARIA ANTONIETA');

    expect(screen.getByText('10,603 contribuyentes')).toBeInTheDocument();
  });
});

describe('AC3 — la busqueda la resuelve el backend, no un filtro sobre la pagina', () => {
  it('lo tecleado viaja como `?nombreRazonSocial=`', async () => {
    const espia = backendMedido();
    const usuario = await montar();

    await usuario.type(buscador(), BUSQUEDA_APROXIMADA);
    await screen.findByText('VILCHEZ SULLON-LUIS');

    expect(
      pedidas(espia).some((url) => url.includes('nombreRazonSocial=sulon%20vilchez')),
    ).toBe(true);
  });

  it('y salen nombres que NO contienen lo tecleado, que es lo que un filtro no puede hacer', async () => {
    // **Es la prueba del AC3.** «sulon vilchez» no esta dentro de «VILCHEZ SULLON-LUIS»: un
    // `includes()` sobre las filas cargadas devolveria CERO. Lo que se ve es lo que contesto el
    // servidor, que busca por parecido de trigramas.
    backendMedido();
    const usuario = await montar();

    await usuario.type(buscador(), BUSQUEDA_APROXIMADA);

    expect(await screen.findByText('VILCHEZ SULLON-LUIS')).toBeInTheDocument();
    expect(listados()).toEqual(POR_NOMBRE_APROXIMADO_FILAS.map((q) => q.nombreRazonSocial));
    listados().forEach((nombre) => {
      expect(nombre.toLowerCase()).not.toContain(BUSQUEDA_APROXIMADA);
    });
  });

  it('la cuenta de la busqueda tambien es la del backend', async () => {
    backendMedido();
    const usuario = await montar();

    await usuario.type(buscador(), BUSQUEDA_APROXIMADA);
    await screen.findByText('VILCHEZ SULLON-LUIS');

    // 74, no 5: de lo que casa, llegaron cinco.
    expect(screen.getByText(`${String(TOTAL_DE_LA_BUSQUEDA)} contribuyentes`)).toBeInTheDocument();
    expect(TOTAL_DE_LA_BUSQUEDA).toBeLessThan(TOTAL_DEL_PADRON);
  });

  it('cambiar de criterio cambia el parametro, y vuelve a la primera pagina', async () => {
    const espia = backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Página siguiente' }));
    await screen.findByText('CORTEZ DE IPANAQUE-MARIA ANTONIETA');

    await usuario.selectOptions(criterio(), 'DNI');
    await usuario.type(buscador(), '29614026');
    // Se espera a la CUENTA y no al nombre: ese nombre esta tambien en la pagina 0, que llega
    // antes por el cambio de criterio, asi que esperarlo resolveria antes de la busqueda.
    await screen.findByText('1 contribuyente');

    const suyas = pedidas(espia).filter((url) => url.includes('dNI=29614026'));
    expect(suyas).not.toEqual([]);
    // Y con `pagina=0`: quedarse en la 27 de una busqueda nueva ensena un vacio que no lo es.
    expect(suyas.every((url) => url.includes('pagina=0'))).toBe(true);
  });

  it('no se pregunta una vez por tecla: se espera a que la mano pare', async () => {
    // Con la busqueda del cliente esto no costaba nada porque no habia peticion. Con la del
    // servidor, cada tecla es una consulta por trigramas sobre 10 603 filas — «MEDINA» serian
    // seis, y las cinco primeras se descartan al llegar. Se mide en peticiones y no en
    // milisegundos: lo que importa no es cuanto se espera, es cuantas veces se pregunta.
    const espia = backendMedido();
    const usuario = await montar();
    const antes = pedidas(espia).filter((url) => url.includes('nombreRazonSocial')).length;

    await usuario.type(buscador(), BUSQUEDA_APROXIMADA);
    await screen.findByText('VILCHEZ SULLON-LUIS');

    const consultas = pedidas(espia).filter((url) => url.includes('nombreRazonSocial')).length;
    // **El limite es DOS y no «menos que las teclas», y la diferencia importa**: medido, sin
    // espera las trece teclas dan **12** consultas, y «12 < 13» pasaba — la prueba estaba en
    // verde con el defecto puesto. Con espera son **1**. Un margen de dos deja sitio a que la
    // ultima tecla y el asentado se solapen, y sigue estando a diez de distancia del defecto.
    expect(consultas - antes).toBeLessThanOrEqual(2);
    expect(consultas - antes).toBeGreaterThan(0);
  });

  it('el aspa limpia la busqueda y vuelve el padron entero', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.type(buscador(), BUSQUEDA_APROXIMADA);
    await screen.findByText('VILCHEZ SULLON-LUIS');

    await usuario.click(screen.getByRole('button', { name: 'Limpiar la búsqueda' }));

    expect(await screen.findByText('10,603 contribuyentes')).toBeInTheDocument();
  });
});

describe('AC3 — los criterios que la operacion no admite NO se ofrecen, y se dice cual', () => {
  it('el selector ofrece exactamente los cuatro que el backend publica', async () => {
    backendMedido();
    await montar();

    expect(
      within(criterio())
        .getAllByRole('option')
        .map((una) => una.textContent),
    ).toEqual(['Nombre', 'Código', 'DNI', 'RUC']);
  });

  it('«Código» avisa de que se compara por igualdad, porque asi es el SQL', async () => {
    backendMedido();
    const usuario = await montar();

    await usuario.selectOptions(criterio(), 'Código');

    // Medido: `?codigo=000000000` devuelve 0 sobre un padron de codigos que empiezan por ceros.
    // Sin este aviso, quien teclee medio codigo concluye que ese contribuyente no existe.
    expect(
      screen.getByText('«Código» se busca completo: el backend compara por igualdad.'),
    ).toBeInTheDocument();
  });

  it('y «Nombre» no lo avisa, porque ahi si vale un trozo', async () => {
    backendMedido();
    await montar();

    expect(screen.queryByText(/se busca completo/)).toBeNull();
  });

  it('el orden ofrece DOS, y «Deuda» no es uno de ellos', async () => {
    // El backend contesta 422 `ORDEN_NO_ADMITIDO` a `?ordenarPor=deuda`, porque esta operacion
    // no publica la deuda. Ofrecerlo y ordenar aqui pondria delante «al que mas debe» de la
    // pagina que se este mirando, que sobre 531 paginas no quiere decir nada.
    backendMedido();
    await montar();

    const orden = screen.getByRole('combobox', { name: 'Ordenar la lista' });
    expect(
      within(orden)
        .getAllByRole('option')
        .map((una) => una.textContent),
    ).toEqual(['Código', 'Nombre']);
  });

  it('elegir «Nombre» manda `?ordenarPor=nombreRazonSocial` y ensena lo que devuelve', async () => {
    const espia = backendMedido();
    const usuario = await montar();

    await usuario.selectOptions(screen.getByRole('combobox', { name: 'Ordenar la lista' }), 'Nombre');

    expect(await screen.findByText('3D PHARMACEUTICAL SAC')).toBeInTheDocument();
    expect(listados()).toEqual(ORDENADO_POR_NOMBRE_FILAS.map((q) => q.nombreRazonSocial));
    expect(pedidas(espia).some((url) => url.includes('ordenarPor=nombreRazonSocial'))).toBe(true);
  });
});

describe('AC5 — los chips son listas, y su vacio es un dato y no una averia', () => {
  it('«En coactiva» sale vacio CON EL BACKEND SANO, y lo dice como tal', async () => {
    // `GET /coactiva/deudas` contesta 200 con lista vacia en las dos municipalidades, medido.
    // No hay nadie en coactiva; eso no es que no se haya podido leer.
    backendMedido();
    const usuario = await montar();

    await usuario.click(chip('En coactiva'));

    expect(await screen.findByText('Ningún expediente coactivo abierto')).toBeInTheDocument();
    expect(
      screen.getByText(
        'La consulta de cobranza coactiva respondió, y no hay ninguno en esta municipalidad.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/No se pudo leer/)).toBeNull();
    expect(screen.getByText('0 contribuyentes')).toBeInTheDocument();
  });

  it('y cuando SI falla dice otra cosa, que es lo que separa las dos', async () => {
    backendMedido((url) =>
      url.includes('/coactiva/deudas')
        ? problema(503, 'ERROR_INTERNO', 'El servicio de coactiva no responde')
        : null,
    );
    const usuario = await montar();

    await usuario.click(chip('En coactiva'));

    expect(await screen.findByText('No se pudo leer la lista')).toBeInTheDocument();
    expect(screen.queryByText('Ningún expediente coactivo abierto')).toBeNull();
  });

  it('«Observado» sale vacio igual, y con su propia frase', async () => {
    backendMedido();
    const usuario = await montar();

    await usuario.click(chip('Observado'));

    expect(await screen.findByText('Ningún contribuyente observado')).toBeInTheDocument();
    expect(
      screen.getByText('La última corrida de emisión respondió, y no dejó a nadie fuera.'),
    ).toBeInTheDocument();
  });

  it('«Con deuda» sale vacio porque NADIE lo publica, y esa es una tercera frase', async () => {
    // El hallazgo de F-5, ahora comprobado contra el backend: `GET /rentas/contribuyentes`
    // publica identidad y nada mas. El chip no filtra ni pide: dice que no tiene fuente.
    backendMedido();
    const usuario = await montar();

    await usuario.click(chip('Con deuda'));

    expect(await screen.findByText('Nadie puede salir por «Con deuda»')).toBeInTheDocument();
    expect(
      screen.getByText(/Ninguna operación de este backend publica la deuda del padrón/),
    ).toBeInTheDocument();
  });

  it('con una lista de estado RECORTADA, la insignia deja de afirmarse', async () => {
    // No aparecer en la primera pagina de coactiva no es no estar en coactiva. Sin esto, una
    // fila con expediente abierto se dibujaria «Activo» sin que nada lo dijera.
    backendMedido((url) =>
      url.includes('/coactiva/deudas')
        ? Response.json(
            envolver(
              [
                {
                  expediente: '2026-0418',
                  ano: 2026,
                  codContribuyente: 'no-es-de-esta-pagina',
                  contribuyente: 'Alguien',
                  deudaS: '10.00',
                  costasS: '0.00',
                  totalS: '10.00',
                  aLaFecha: '2026-08-12',
                  estado: 'En coactiva',
                },
              ],
              0,
              400,
            ),
          )
        : null,
    );

    await montar();

    expect(
      await screen.findByText('El estado de cobranza se calculó sobre una parte'),
    ).toBeInTheDocument();
  });
});

describe('AC9 — la busqueda sin resultados es un estado, no un error', () => {
  it('dice el texto del artboard y ofrece crear', async () => {
    backendMedido((url) =>
      url.includes('codigo=99999999999') ? Response.json(envolver([], 0, 0)) : null,
    );
    const usuario = await montar();

    await usuario.selectOptions(criterio(), 'Código');
    await usuario.type(buscador(), '99999999999');

    expect(await screen.findByText('Ningún contribuyente coincide')).toBeInTheDocument();
    expect(
      screen.getByText(
        'Puede estar con el código antiguo o con otro documento. Si viene a declarar por primera vez, créelo aquí mismo.',
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText('No se pudo leer la lista')).toBeNull();
  });
});

describe('AC6 — el expediente abre sobre un contribuyente de verdad', () => {
  it('elegirlo pide su ficha POR SU IDENTIFICADOR', async () => {
    const espia = backendMedido();
    const usuario = await montar();

    await usuario.click(screen.getByRole('button', { name: /SULLON VILCHEZ-JOSE RAUL/ }));

    // El 17 es el `id` que trajo la fila, no un numero de esta prueba.
    expect(pedidas(espia)).toContain('/rentas/api/v1/rentas/contribuyentes/17/ficha');
    expect(
      await screen.findByText('00000000008', { selector: '.kr-ficha__codigo' }),
    ).toBeInTheDocument();
    // Y la lista sigue ahi: no se navego a ninguna parte.
    expect(listados()).toHaveLength(5);
  });

  it('lo que la ficha NO publica queda vacio, y no dice «null»', async () => {
    // Medido: `datosPersonales` llega con sus tres campos nulos y `domicilioFiscal` tambien,
    // porque la tabla `domicilio` esta vacia en el origen.
    backendMedido();
    const usuario = await montar();

    await usuario.click(screen.getByRole('button', { name: /SULLON VILCHEZ-JOSE RAUL/ }));
    await screen.findByDisplayValue('NATURAL');

    expect(screen.getByLabelText('Fecha de nacimiento')).toHaveValue('');
    expect(screen.getByLabelText('Estado civil')).toHaveValue('');
  });

  it('elegir a OTRO cambia la ficha, y la pide con su id', async () => {
    const espia = backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: /SULLON VILCHEZ-JOSE RAUL/ }));
    await screen.findByText('00000000008', { selector: '.kr-ficha__codigo' });

    await usuario.click(screen.getByRole('button', { name: /ROMAN GARCIA-PABLO/ }));

    expect(
      await screen.findByText('00000000050', { selector: '.kr-ficha__codigo' }),
    ).toBeInTheDocument();
    expect(pedidas(espia)).toContain('/rentas/api/v1/rentas/contribuyentes/20/ficha');
  });

  it('los beneficios se piden acotados a ESE contribuyente, y su vacio se dice (AC9)', async () => {
    const espia = backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: /SULLON VILCHEZ-JOSE RAUL/ }));
    await screen.findByDisplayValue('NATURAL');

    await usuario.click(screen.getByRole('tab', { name: 'Beneficios' }));

    expect(pedidas(espia)).toContain(
      '/rentas/api/v1/rentas/beneficios?contribuyente=00000000008',
    );
    expect(
      await screen.findByText('Este contribuyente no tiene ningún beneficio registrado.'),
    ).toBeInTheDocument();
  });

  it('las dos operaciones que este issue NO enciende las sigue contestando el proxy', async () => {
    // `GET /rentas/predios` y `GET /consultas/deuda` exigen `?codContribuyente=`, que el
    // contrato no publica (#26). Siguen en el proxy, y la pantalla sigue abriendo.
    const espia = backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: /SULLON VILCHEZ-JOSE RAUL/ }));
    await screen.findByDisplayValue('NATURAL');

    await usuario.click(screen.getByRole('tab', { name: 'Cuenta corriente' }));

    const tabla = await screen.findByRole('table');
    expect(within(tabla).getAllByText('Impuesto predial').length).toBeGreaterThan(0);
    // No salieron a la red: el doble no las vio.
    expect(pedidas(espia).some((url) => url.includes('/consultas/deuda'))).toBe(false);
  });
});

describe('AC7 — todo importe que se ensena lleva su fecha', () => {
  it('el unico de la lista sale de coactiva, con su `aLaFecha`', async () => {
    backendMedido((url) =>
      url.includes('/coactiva/deudas')
        ? Response.json(
            envolver(
              [
                {
                  expediente: '2026-0418',
                  ano: 2026,
                  codContribuyente: PAGINA_0_FILAS[0]?.codigo ?? '',
                  contribuyente: 'SULLON VILCHEZ-JOSE RAUL',
                  deudaS: '9412.15',
                  costasS: '0.00',
                  totalS: '9412.15',
                  aLaFecha: '2026-08-12',
                  estado: 'En coactiva',
                },
              ],
              0,
              1,
            ),
          )
        : null,
    );

    await montar();

    // El importe es texto y se formatea, nunca se convierte ni se suma (reglas 1 y 9).
    expect(await screen.findByText('S/ 9,412.15')).toBeInTheDocument();
    expect(await screen.findByText('En coactiva', { selector: '.kr-insignia' })).toBeInTheDocument();
  });
});

describe('AC8 — la compuerta del alta le pregunta al PADRON, no a la pagina cargada', () => {
  const numero = () => screen.getByRole('textbox', { name: 'Número' });

  it('un DNI ya registrado se consulta por `?dNI=` y el aviso dice de quien es', async () => {
    const espia = backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(numero(), '29614026');

    expect(await screen.findByText('Documento ya registrado')).toBeInTheDocument();
    expect(
      screen.getByText(/a nombre de SULLON VILCHEZ-JOSE RAUL \(00000000008\)/),
    ).toBeInTheDocument();
    expect(pedidas(espia).some((url) => url.includes('dNI=29614026'))).toBe(true);
  });

  it('un DNI libre no bloquea, y la respuesta vacia es la que lo dice', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(numero(), '99999999');

    expect(await screen.findByText('Documento válido')).toBeInTheDocument();
  });

  it('con todo lleno y el documento LIBRE, se puede crear', async () => {
    // Es la mitad que hace falta para que la de abajo diga algo: sin ella, «no se puede crear»
    // se cumpliria tambien con la compuerta rota, porque hay obligatorios vacios.
    backendMedido();
    await montar(conTodoLlenoMenosElDocumento('DNI', '99999999'));
    await screen.findByText('Documento válido');

    expect(screen.getByRole('button', { name: 'Crear el contribuyente' })).toHaveAttribute(
      'aria-disabled',
      'false',
    );
  });

  it('si la consulta FALLA no se crea: no saber no es saber que esta libre', async () => {
    backendMedido((url) =>
      url.includes('dNI=99999999')
        ? problema(503, 'ERROR_INTERNO', 'El padrón no responde')
        : null,
    );
    await montar(conTodoLlenoMenosElDocumento('DNI', '99999999'));

    expect(await screen.findByText('No se pudo comprobar')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Crear el contribuyente' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('un carne de extranjeria NO se puede comprobar, y la pantalla lo dice en vez de fingir', async () => {
    // `ContribuyenteController.buscar` publica `dNI` y `rUC` y nada para los demas tipos.
    const espia = backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.selectOptions(
      screen.getByRole('combobox', { name: 'Tipo' }),
      'Carnet de extranjería',
    );
    await usuario.type(numero(), '001234567890');

    expect(await screen.findByText('Sin comprobar en el padrón')).toBeInTheDocument();
    expect(screen.getByText(/sólo se puede consultar por DNI y por RUC/)).toBeInTheDocument();
    // Y no se pregunta nada: una consulta sin filtro devolveria el padron entero.
    expect(pedidas(espia).some((url) => url.includes('001234567890'))).toBe(false);
  });
});

describe('AC7 de F-5 — la longitud del documento sigue decidiendo', () => {
  const numero = () => screen.getByRole('textbox', { name: 'Número' });

  it('un DNI incompleto se cuenta en pantalla y no deja crear', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(numero(), '0359317');

    expect(screen.getByText('7 de 8 dígitos')).toBeInTheDocument();
    expect(screen.getByText(/El DNI tiene 8 dígitos/)).toBeInTheDocument();
  });

  it('el carnet de extranjeria son DOCE, y once no bastan', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.selectOptions(
      screen.getByRole('combobox', { name: 'Tipo' }),
      'Carnet de extranjería',
    );
    await usuario.type(numero(), '00123456789');

    expect(screen.getByText('11 de 12 dígitos')).toBeInTheDocument();
  });

  it('las letras no entran, y el numero no pasa de su longitud', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(numero(), '03a59b31c74999');

    expect(numero()).toHaveValue('03593174');
  });
});

describe('AC6 de F-5 — el alta guiada y sus seis secciones', () => {
  it('empieza en la primera, y «Anterior» esta deshabilitado', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    expect(screen.getByRole('tab', { name: /^Identificación/ })).toHaveAttribute(
      'aria-selected',
      'true',
    );
    expect(screen.getByRole('button', { name: 'Anterior' })).toHaveAttribute(
      'aria-disabled',
      'true',
    );
  });

  it('la ULTIMA seccion es «Lo que se va a registrar», ANTES de confirmar', async () => {
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    expect(screen.queryByText('Lo que se va a registrar')).toBeNull();

    await usuario.click(screen.getByRole('tab', { name: /^Observaciones/ }));

    expect(screen.getByText('Lo que se va a registrar')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Crear el contribuyente' })).toBeInTheDocument();
  });
});

describe('AC5 de F-5 — el estado vacio de la derecha', () => {
  it('sin nadie elegido, dice que elija y explica que el expediente se abre al lado', async () => {
    backendMedido();
    await montar();

    expect(screen.getByText('Elija un contribuyente de la lista')).toBeInTheDocument();
  });
});

describe('AC9 de F-5 — escribir en el alta marca sucia la pestana', () => {
  it('teclear un campo del alta avisa al marco', async () => {
    ensuciada.mockClear();
    backendMedido();
    const usuario = await montar();
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));

    await usuario.type(screen.getByLabelText('Nombres'), 'Rosa');

    expect(ensuciada).toHaveBeenCalled();
  });

  it('montado en el marco, pone el asterisco y cerrar pregunta', async () => {
    backendMedido();
    const usuario = userEvent.setup();
    render(<Marco {...IDENTIDAD} />);
    await usuario.click(
      within(screen.getByRole('complementary', { name: 'Módulos y submódulos' })).getByRole(
        'button',
        { name: /^Contribuyentes/ },
      ),
    );
    await screen.findByText('SULLON VILCHEZ-JOSE RAUL');

    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));
    await usuario.type(screen.getByLabelText('Nombres'), 'Rosa');

    const pestanas = screen.getByRole('group', { name: 'Pestañas abiertas' });
    expect(within(pestanas).getByRole('button', { name: /Contribuyentes \*/ })).toBeInTheDocument();

    await usuario.click(
      screen.getByRole('button', { name: 'Cerrar Contribuyentes — tiene cambios sin guardar' }),
    );

    expect(
      screen.getByRole('dialog', { name: 'Cerrar con cambios sin guardar' }),
    ).toBeInTheDocument();
  });

  it('lo tecleado sobrevive a irse al panel y volver', async () => {
    backendMedido();
    const usuario = userEvent.setup();
    render(<Marco {...IDENTIDAD} />);
    const arbol = screen.getByRole('complementary', { name: 'Módulos y submódulos' });
    await usuario.click(within(arbol).getByRole('button', { name: /^Contribuyentes/ }));
    await screen.findByText('SULLON VILCHEZ-JOSE RAUL');
    await usuario.click(screen.getByRole('button', { name: 'Nuevo contribuyente' }));
    await usuario.type(screen.getByLabelText('Nombres'), 'Rosa');

    const pestanas = screen.getByRole('group', { name: 'Pestañas abiertas' });
    await usuario.click(within(pestanas).getByRole('button', { name: /^Panel/ }));
    await usuario.click(within(pestanas).getByRole('button', { name: /^Contribuyentes/ }));

    expect(await screen.findByLabelText('Nombres')).toHaveValue('Rosa');
  });
});

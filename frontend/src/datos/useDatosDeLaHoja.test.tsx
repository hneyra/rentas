import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { coordenada } from '@kamayuk/ui';
import {
  CONSTANCIA_NEGADA,
  FICHA,
  OTRA_FICHA,
  SIN_CAMPANIA,
} from './conectores/consultasDeMuestra.ts';
import type { CorridaDelPredial } from './lecturas.ts';
import { useDatosDeLaHoja } from './useDatosDeLaHoja.ts';

/**
 * **Los cuatro estados de una pantalla que pide** (#97).
 *
 * Se prueban con `fetch` sustituido y no con un doble del gancho, a proposito: lo que puede
 * fallar aqui es como se traduce **una respuesta de verdad** —o su ausencia— a lo que la pantalla
 * ensena, y un doble del gancho se saltaria justo esa traduccion.
 */

function arnes() {
  // Un cliente por prueba: compartido, la respuesta de una se quedaria en la cache de la
  // siguiente y el estado «cargando» no se veria nunca.
  //
  // Devuelve tambien el cliente —y no solo el envoltorio— desde #181: hay una prueba que mira las
  // CLAVES con que quedaron las consultas, que es donde se ve si el ejercicio va dentro. Quien
  // solo quiera montar usa `arnes().wrapper`, o `arnes()` desestructurado.
  const cliente = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const wrapper = ({ children }: { readonly children: ReactNode }) => (
    <QueryClientProvider client={cliente}>{children}</QueryClientProvider>
  );
  return { cliente, wrapper };
}

/** Sustituye `fetch` por una respuesta fija. */
function contesta(cuerpo: unknown, estado = 200) {
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>(() =>
      Promise.resolve(
        new Response(JSON.stringify(cuerpo), {
          status: estado,
          headers: { 'content-type': 'application/json' },
        }),
      ),
    ),
  );
}

/**
 * La ultima corrida, **con su tipo delante** (#271).
 *
 * Sin el `: CorridaDelPredial` esta constante era un objeto suelto que el compilador no comparaba
 * con nada, asi que cuando la operacion publico `determinados` y `montoEmitido` el fixture se
 * quedo corto **en verde**: `tsc` no dijo nada y el rojo llego al montar la hoja, desde dentro de
 * `formatearEntero`. Con el tipo, el que falte un campo no compila.
 *
 * **Y es una corrida ANTERIOR a `V23`** (#312): las dos columnas del sello en nulo. Se elige asi
 * porque es la unica que deja algo que medir aqui —un campo con su propia palabra en el hueco—, y
 * porque esa palabra es la que distingue que el hueco viaja POR CAMPO: la de la pantalla seria
 * «no publicado».
 */
const CORRIDA: CorridaDelPredial = {
  id: 1,
  ejercicio: '2026',
  alcance: 'PADRON',
  sector: null,
  simulacion: false,
  conjunto: 'V3',
  conjuntoId: null,
  fechaCalculo: '28/01/2026 02:14',
  determinados: 58412,
  montoEmitido: '8772431.05',
  derechoDeEmision: null,
  observados: 534,
  etapas: [
    { etapa: 'Lectura del padron', registros: 62418, monto: '—', observados: 0, estado: 'Conforme' },
  ],
};

/**
 * La sesion tal como la contesta la instalacion: **sin ejercicio de trabajo** (#181).
 *
 * No es una eleccion de esta prueba, es lo que contesta el backend — `sesionMedida.ts` lo tiene
 * copiado de un `curl`, y `camino-a-la-api.test.ts` comprueba que sigue siendo `null`. Se escribe
 * aqui y no se importa porque la captura es de las pruebas del marco y esta prueba no es del
 * marco; lo que hace falta de ella es el caso, no los bytes.
 */
const SIN_EJERCICIO_FIJADO = {
  usuarioId: 2,
  cuenta: 'administrador',
  nombre: 'Administrador del Sistema',
  ejercicioDeTrabajo: null,
};

/** Una pagina de la bitacora, con los doce campos que el contrato declara. */
const BITACORA = {
  contenido: [
    {
      id: 41184,
      ejercicio: 2024,
      tabla: 'recibo',
      clave: '0003-0041184',
      operacion: 'ANULACION',
      usuario: 'jcardenas',
      origenEquipo: 'PC-CAJA-02',
      origenIp: '10.0.4.12',
      fecha: '2026-08-13T09:41:12-05:00',
      observacion: 'Anulado por duplicado',
      datosAnteriores: null,
      datosNuevos: null,
    },
  ],
  pagina: 0,
  tamano: 20,
  totalElementos: 84182,
  totalPaginas: 4210,
  hayMas: true,
};

/** La misma forma, otro ejercicio y otro usuario: es lo que distingue una bitacora de la otra. */
const OTRA_BITACORA = {
  ...BITACORA,
  contenido: [
    { ...BITACORA.contenido[0], id: 30112, ejercicio: 2025, usuario: 'mrios', operacion: 'BAJA' },
  ],
};

afterEach(() => {
  vi.unstubAllGlobals();
});

/** Sustituye `fetch` por un doble que contesta segun la ruta pedida. Devuelve lo que se pidio. */
function contestaSegunLaRuta(porRuta: Readonly<Record<string, unknown>>): readonly string[] {
  const pedidas: string[] = [];
  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada) => {
      const url = String(entrada);
      pedidas.push(url);
      const clave = Object.keys(porRuta).find((trozo) => url.includes(trozo));
      return Promise.resolve(
        new Response(JSON.stringify(clave === undefined ? {} : porRuta[clave]), {
          status: clave === undefined ? 404 : 200,
          headers: { 'content-type': 'application/json' },
        }),
      );
    }),
  );
  return pedidas;
}

describe('una pantalla SIN conector no toca la red', () => {
  it('no pide nada, y dice por que no hay dato', () => {
    const pedir = vi.fn<typeof fetch>();
    vi.stubGlobal('fetch', pedir);

    // **Era `fis-panel` hasta #215**, y dejo de valer porque #196 le publico su embudo: ahora
    // tiene conector, y una hoja conectada no sirve de ejemplo de lo que hace una que no lo esta.
    // `coa-cart` no declara ninguna operacion servida de lectura —la unica que publica sus ocho
    // campos es `POST /coactiva/convenios`, que **crea un convenio de fraccionamiento**—, asi que
    // es el caso entero: ni conector, ni nada que pedir.
    const { result } = renderHook(() => useDatosDeLaHoja('coa-cart'), { wrapper: arnes().wrapper });

    // Es lo que hace que 21 de las 40 pantallas no manden una sola peticion: sin conector, la
    // consulta no se habilita. Sin esto, abrir el arbol entero serian cuarenta idas a la red
    // contra rutas que nadie sirve — cuarenta 404 y cuarenta huecos identicos.
    expect(pedir).not.toHaveBeenCalled();
    expect(result.current.ausencia.enElCampo).toBe('sin conectar');
    expect(result.current.valores).toBeUndefined();
  });
});

describe('una pantalla CON conector recorre sus estados', () => {
  it('primero dice que esta pidiendo, y no finge un hueco', () => {
    contesta(CORRIDA);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    // Sin este estado, una pantalla llena de huecos durante dos segundos es indistinguible de una
    // pantalla sin backend — y para entonces el usuario ya se fue.
    expect(result.current.ausencia.enElCampo).toBe('pidiendo…');
  });

  it('y cuando llega, reparte lo que trae y marca lo que NO trae', async () => {
    contesta(CORRIDA);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.valores?.size).toBeGreaterThan(0);
    });
    expect(result.current.valores?.get(coordenada(0, 1))).toBe('28/01/2026 02:14');
    expect(result.current.filas?.get(0)).toHaveLength(1);
    // Y el que la operacion no publica va marcado campo a campo, no con el motivo de la pantalla:
    // la pantalla SI esta conectada, y decir lo contrario ahi seria falso.
    //
    // Eran TRES hasta #271, cuando la corrida publico `determinados` y `montoEmitido` como campos.
    // Queda «Derecho de emision» —`0|5`—, y desde #312 **por otro motivo**: la operacion SI lo
    // publica, y esta corrida —anterior a `V23`— lo trae nulo. Por eso la palabra no es «no
    // publicado»: es la de «esa corrida no lo guardo».
    //
    // **Y justo esa diferencia es lo que hace que esta asercion muerda.** «No publicado» es lo que
    // `useDatosDeLaHoja` pone en TODO campo que el reparto no llene, asi que afirmarlo pasaria
    // igual con la declaracion del conector y sin ella. Esta palabra solo puede venir del conector.
    expect(result.current.ausenciaPorCampo?.get(coordenada(0, 5))).toBe('no consta en la corrida');
  });

  it('un 401 se dice como lo que es —vuelva a identificarse—, no como «fallo la red»', async () => {
    contesta({ codigo: 'NO_AUTENTICADO', title: 'No autorizado' }, 401);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('sin sesion');
    });
    // La diferencia decide que hace el usuario: recargar no arregla una sesion caducada.
    expect(result.current.ausencia.explicacion).toMatch(/Vuelva a identificarse/);
    // Y NO va en tono de averia: la sesion caducada es el sistema funcionando, y pintarla de
    // «algo se rompio» manda a mirar un despliegue. Hasta #283 los seis peldanos que no son una
    // averia salian en `atencion`, o sea con el mismo color que un 500.
    expect(result.current.ausencia.tono).toBe('info');
  });

  it('y otro error dice su codigo, para que se pueda buscar', async () => {
    contesta({ estado: 500 }, 500);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('fallo');
    });
    expect(result.current.ausencia.explicacion).toContain('500');
    // Esto SI es una averia, y es el unico caso en que el tono lo dice.
    expect(result.current.ausencia.tono).toBe('atencion');
  });
});

/**
 * **Los dos 403 no se leen igual, y el 422 no se lee como una averia** (#283, #262).
 *
 * Es lo que `api/escalera.ts` tenia medido con `curl` desde I-1 y ninguna pantalla dibujaba. La
 * escalera corta que habia aqui contestaba `SIN_PERMISO` a **cualquier** 403 y «fallo (422)» al
 * 422, o sea que mandaba a pedir un permiso a quien tenia que llamar al administrador, y a
 * soporte a quien habia escrito una observacion de tres letras.
 */
describe('la escalera de identidad, dibujada', () => {
  it('403 SIN_MUNICIPALIDAD manda al administrador del emisor, y no a pedir un permiso', async () => {
    contesta(
      { codigo: 'SIN_MUNICIPALIDAD', mensaje: 'El token no identifica una municipalidad' },
      403,
    );
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('sin municipalidad');
    });
    // Lo que el backend dijo viaja entero: es el unico dato con el que se arregla.
    expect(result.current.ausencia.explicacion).toContain(
      'El token no identifica una municipalidad',
    );
    expect(result.current.ausencia.explicacion).toMatch(/administrador del sistema/);
    expect(result.current.ausencia.tono).toBe('info');
  });

  it('403 SIN_PRIVILEGIO manda a pedir el permiso, y NO dice lo mismo que el otro 403', async () => {
    contesta(
      { codigo: 'SIN_PRIVILEGIO', mensaje: 'No tiene el privilegio LECTURA sobre corridas' },
      403,
    );
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('sin permiso');
    });
    // El mensaje del backend nombra el privilegio y la opcion: es lo que hay que pedir.
    expect(result.current.ausencia.explicacion).toContain(
      'No tiene el privilegio LECTURA sobre corridas',
    );
    expect(result.current.ausencia.explicacion).toMatch(/perfiles/);
  });

  it('un 403 SIN codigo no se hace pasar por ninguno de los dos', async () => {
    // Adivinar mandaria a la mitad de los casos a pedir un permiso que no falta.
    contesta({ title: 'Forbidden' }, 403);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('sin acceso');
    });
  });

  it('el 422 NO se lee como una averia: es una regla del dominio, con su cifra dentro', async () => {
    contesta(
      {
        codigo: 'VALIDACION',
        mensaje: 'Ejercicio fuera de rango: 1800. Se admite de 1990 a 2100',
      },
      422,
    );
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('dato rechazado');
    });
    // Hasta #283 esto salia como «No se pudieron pedir los datos de esta pantalla (422)», o sea
    // como si algo se hubiera roto, y el remedio era avisar a soporte. La regla y su cifra —lo
    // unico con lo que quien esta delante corrige lo que escribio— no llegaban a la pantalla.
    expect(result.current.ausencia.explicacion).toContain('Se admite de 1990 a 2100');
    expect(result.current.ausencia.explicacion).toMatch(/Corrija/);
    expect(result.current.ausencia.tono).toBe('info');
  });

  it('y el estado va en la frase, aparte del texto, para dictarlo a soporte', async () => {
    contesta({ title: 'Forbidden' }, 403);
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.explicacion).toContain('(403)');
    });
  });
});

/**
 * **Una hoja de un contribuyente concreto** (#169).
 *
 * Las tres operaciones de Consultas contestan 422 sin el codigo del contribuyente, asi que la
 * pregunta que decide esta pantalla no es «llego la respuesta» sino **de quien es**. El codigo
 * viaja en la direccion y llega aqui por `useHoja().ruta.sujeto`.
 */
describe('una pantalla que es de un contribuyente', () => {
  it('sin sujeto NO pide nada, y lo dice con su propia frase', () => {
    const pedidas = contestaSegunLaRuta({});
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel'), { wrapper: arnes().wrapper });

    // Lo contrario seria mandar la peticion sin el parametro y ensenar el 422 del backend como si
    // fuera una averia de la pantalla. Y lo OTRO contrario —elegir un contribuyente aqui— pintaria
    // la cuenta de una persona de verdad a quien nadie pregunto.
    expect(pedidas).toEqual([]);
    expect(result.current.ausencia.enElCampo).toBe('falta el contribuyente');
    expect(result.current.valores).toBeUndefined();
  });

  it('con sujeto pide SUS DOS operaciones, con el codigo dentro', async () => {
    const pedidas = contestaSegunLaRuta({
      '/consultas/unificada': FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel', { sujeto: '00000025673', parametros: {} }), {
      wrapper: arnes().wrapper,
    });

    await waitFor(() => {
      expect(result.current.valores?.size).toBeGreaterThan(0);
    });
    expect(pedidas).toHaveLength(2);
    expect(pedidas[0]).toContain('/consultas/unificada?contribuyente=00000025673');
    expect(pedidas[1]).toContain('/consultas/deudas-con-beneficio?contribuyente=00000025673');
  });

  it('y lo que pinta es lo que CONTESTO el doble, no lo que dice su definicion', async () => {
    contestaSegunLaRuta({
      '/consultas/unificada': FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel', { sujeto: '00000025673', parametros: {} }), {
      wrapper: arnes().wrapper,
    });

    await waitFor(() => {
      expect(result.current.valores?.get(coordenada(0, 6))).toBe('S/ 3,563.24');
    });
    expect(result.current.valores?.get(coordenada(0, 1))).toBe('DNI 29614026');
    expect(result.current.ausenciaPorCampo?.get(coordenada(0, 4))).toBe('no publicado');
  });

  it('CAMBIADA la respuesta del doble, cambia la pantalla — y es el AC3', async () => {
    contestaSegunLaRuta({
      '/consultas/unificada': OTRA_FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('con-panel', { sujeto: '00000003541', parametros: {} }), {
      wrapper: arnes().wrapper,
    });

    await waitFor(() => {
      expect(result.current.valores?.get(coordenada(0, 6))).toBe('S/ 591.94');
    });
    // La misma hoja, la misma definicion, otro contribuyente: si algo saliera de la definicion,
    // este caso y el de arriba darian lo mismo.
    expect(result.current.valores?.get(coordenada(0, 1))).toBe('DNI 44218937');
    expect(result.current.valores?.get(coordenada(0, 2))).toBe('31/01/2026');
  });

  it('la constancia pide con `codContribuyente`, que es el nombre que ESA operacion admite', async () => {
    const pedidas = contestaSegunLaRuta({ '/consultas/constancias': CONSTANCIA_NEGADA });
    const { result } = renderHook(() => useDatosDeLaHoja('con-doc', { sujeto: '00000025673', parametros: {} }), {
      wrapper: arnes().wrapper,
    });

    await waitFor(() => {
      expect(result.current.filas?.get(0)).toHaveLength(2);
    });
    expect(pedidas[0]).toContain(
      '/consultas/constancias/no-adeudo?codContribuyente=00000025673',
    );
    expect(result.current.valores?.get(coordenada(0, 5))).toContain('Con deuda al 12/09/2026');
  });

  it('dos contribuyentes de la misma hoja NO comparten cache', async () => {
    contestaSegunLaRuta({
      '/consultas/unificada': FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    // Un solo cliente para los dos, que es lo que hay en la aplicacion: la cache es de modulo.
    const { wrapper } = arnes();
    const uno = renderHook(() => useDatosDeLaHoja('con-panel', { sujeto: '00000025673', parametros: {} }), { wrapper });
    await waitFor(() => {
      expect(uno.result.current.valores?.get(coordenada(0, 6))).toBe('S/ 3,563.24');
    });

    contestaSegunLaRuta({
      '/consultas/unificada': OTRA_FICHA,
      '/consultas/deudas-con-beneficio': SIN_CAMPANIA,
    });
    const otro = renderHook(() => useDatosDeLaHoja('con-panel', { sujeto: '00000003541', parametros: {} }), { wrapper });

    // **En la PRIMERA pintada**, que es donde esta el defecto: sin el sujeto en la clave, el
    // segundo contribuyente abre con lo que cacheo el primero —«S/ 3,563.24»— mientras llega lo
    // suyo, y despues lo sustituye. Esperar a que acabe no ve nada; en una ventanilla, ese
    // parpadeo es ensenarle a alguien la deuda de otro.
    expect(otro.result.current.valores?.get(coordenada(0, 6))).not.toBe('S/ 3,563.24');
    expect(otro.result.current.ausencia.enElCampo).toBe('pidiendo…');

    await waitFor(() => {
      expect(otro.result.current.valores?.get(coordenada(0, 6))).toBe('S/ 591.94');
    });
  });
});

/**
 * **La hoja cuyo obligatorio sale de la SESION** (#181, AC2 y AC6).
 *
 * `GET /seguridad/auditoria` declara `ejercicio` obligatorio y **no va en la ruta**. Asi que la
 * pregunta que decide esta pantalla no es «llego la respuesta» ni «de quien es», sino **de que
 * ano**: sin ejercicio la peticion no se manda, y con el equivocado contesta igual de bien.
 *
 * Es el modo de fallo caro y por eso se mide contando **las URL que salieron**, no mirando la
 * pantalla: un conector que pidiera `?ejercicio=null`, o uno que escribiera un `2026` literal,
 * dibujarian una tabla perfecta en los dos casos.
 */
describe('una pantalla que es de un ejercicio de la sesion', () => {
  it('sin ejercicio en la sesion NO manda la peticion, y lo dice con su propia frase', async () => {
    // La sesion contesta —200— y lo que no trae es ejercicio. Es el caso MEDIDO de la
    // instalacion: `administrador` tiene hoy `ejercicioDeTrabajo: null` (ver `sesionMedida.ts`).
    const pedidas = contestaSegunLaRuta({ '/seguridad/sesion': SIN_EJERCICIO_FIJADO });
    const { result } = renderHook(() => useDatosDeLaHoja('seg-aud'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('falta el ejercicio');
    });
    // **Ni una ida a la bitacora.** Lo contrario seria mandarla sin el parametro y ensenar el 422
    // como si fuera una averia; y lo OTRO contrario —poner el ano de hoy— seria ensenar la
    // bitacora de un ejercicio que nadie eligio, con cara de ser la buena.
    expect(pedidas.filter((url) => url.includes('/seguridad/auditoria'))).toEqual([]);
    expect(result.current.tablas).toBeUndefined();
  });

  it('con ejercicio en la sesion pide la bitacora DE ESE ano', async () => {
    const pedidas = contestaSegunLaRuta({
      '/seguridad/sesion': { ...SIN_EJERCICIO_FIJADO, ejercicioDeTrabajo: 2024 },
      '/seguridad/auditoria': BITACORA,
    });
    const { result } = renderHook(() => useDatosDeLaHoja('seg-aud'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.tablas?.get('movimientos')?.filas).toHaveLength(1);
    });
    const deLaBitacora = pedidas.filter((url) => url.includes('/seguridad/auditoria'));
    expect(deLaBitacora).toHaveLength(1);
    expect(deLaBitacora[0]).toContain('ejercicio=2024');
    // Y no el del reloj del puesto, que es la otra forma de inventarlo.
    expect(deLaBitacora[0]).not.toContain(String(new Date().getFullYear()));
  });

  it('y CAMBIADO el ejercicio de la sesion, cambia lo que se pide — y la clave de cache', async () => {
    // Sin el ejercicio en la clave de consulta, el segundo ano abriria con las filas del primero
    // mientras llega lo suyo. En una bitacora de auditoria eso es ensenar los actos de un
    // ejercicio bajo el rotulo de otro, que es exactamente el modo de fallo del AC2.
    contestaSegunLaRuta({
      '/seguridad/sesion': { ...SIN_EJERCICIO_FIJADO, ejercicioDeTrabajo: 2024 },
      '/seguridad/auditoria': BITACORA,
    });
    const primero = arnes();
    const uno = renderHook(() => useDatosDeLaHoja('seg-aud'), { wrapper: primero.wrapper });
    await waitFor(() => {
      expect(uno.result.current.tablas?.get('movimientos')?.filas[0]?.celdas[1]).toBe(
        'jcardenas',
      );
    });

    // Otro cliente y no `clear()`: lo que se mide es que la clave LLEVA el ejercicio, y con la
    // cache tirada la clave daria igual. Se comprueba mirando la clave, abajo.
    const pedidas = contestaSegunLaRuta({
      '/seguridad/sesion': { ...SIN_EJERCICIO_FIJADO, ejercicioDeTrabajo: 2025 },
      '/seguridad/auditoria': OTRA_BITACORA,
    });
    const otro = renderHook(() => useDatosDeLaHoja('seg-aud'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(otro.result.current.tablas?.get('movimientos')?.filas[0]?.celdas[1]).toBe('mrios');
    });
    expect(pedidas.some((url) => url.includes('ejercicio=2025'))).toBe(true);

    // Y la clave de la consulta lleva el ejercicio: dos ejercicios de la misma hoja no comparten
    // cache, igual que dos contribuyentes no la comparten desde #169. Desde #172 lleva ademas lo
    // que la hoja trae en su ruta —la pagina y el orden—, por lo mismo: cambiar de pagina tiene
    // que traer OTRA respuesta, y con la clave sin ello el mando moveria la direccion y la tabla
    // seguiria dibujando la pagina 0.
    const claves = primero.cliente
      .getQueryCache()
      .getAll()
      .map((consulta) => consulta.queryKey.join('/'));
    expect(claves).toContain('seg-aud/auditoria//2024/{}');
  });

  it('si la SESION falla, lo dice como fallo y no como «fije usted el ejercicio»', async () => {
    // Sin esto, un 401 se leeria como «esta pantalla necesita que elija un ano» y mandaria a
    // arreglar lo que no esta roto. Es el orden de las tres ramas de `useDatosDeLaHoja`.
    contesta({ estado: 401 }, 401);
    const { result } = renderHook(() => useDatosDeLaHoja('seg-aud'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('sin sesion');
    });
    expect(result.current.ausencia.explicacion).toMatch(/Vuelva a identificarse/);
  });

  it('y las 38 hojas restantes NO piden la sesion: solo la piden quien la exige', async () => {
    // Eran 39 hasta #237: `territorio` es la segunda que lo exige, y ademas la primera que exige
    // sujeto Y ejercicio a la vez.
    //
    // `enabled` acotado a `exigeEjercicio`. Sin eso, abrir cualquier destino sumaria una ida a
    // `/seguridad/sesion`, y la siembra de #114 —que afirma CERO peticiones a `/seguridad/` con el
    // catalogo sembrado— saldria roja por una lectura que esa pantalla no necesita.
    const pedidas = contestaSegunLaRuta({ '/rentas/predial/corridas/ultima': CORRIDA });
    const { result } = renderHook(() => useDatosDeLaHoja('panel'), { wrapper: arnes().wrapper });

    await waitFor(() => {
      expect(result.current.valores?.size).toBeGreaterThan(0);
    });
    expect(pedidas.filter((url) => url.includes('/seguridad/sesion'))).toEqual([]);
  });
});

/**
 * **Los DOS vacios de la determinacion predial, que no son el mismo** (#237, #207).
 *
 * El backend los publica distintos porque #546 midio el dano de confundirlos: **404** es «ese
 * codigo no esta en el padron» —la pregunta no tiene sujeto— y **204** es «existe y todavia no se
 * le ha determinado este ejercicio». Se arreglan de forma distinta: uno escribiendo otro codigo y
 * el otro determinando.
 *
 * Y la mitad que esta prueba cubre y las de `conectores.test.ts` no: que el GANCHO los MIRE. Las de
 * alli comprueban que el conector declara las dos frases, y con esa comprobacion sola se puede
 * borrar entera la rama de `useDatosDeLaHoja` que las lee **sin que nada se ponga rojo** — el 404
 * volveria a decir «fallo (404)» y el 204 «sin datos», que es el estado anterior a este issue.
 */
describe('`territorio` — el 404 y el 204 no dicen lo mismo (#237)', () => {
  /** Contesta la sesion con su ejercicio, y la determinacion con el estado que se le diga. */
  function conLaDeterminacionEn(estado: number): readonly string[] {
    const pedidas: string[] = [];
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>((entrada) => {
        const url = String(entrada);
        pedidas.push(url);
        if (url.includes('/seguridad/sesion')) {
          return Promise.resolve(
            new Response(JSON.stringify({ ...SIN_EJERCICIO_FIJADO, ejercicioDeTrabajo: 2026 }), {
              status: 200,
              headers: { 'content-type': 'application/json' },
            }),
          );
        }
        // 204 va **sin cuerpo**, que es como lo manda el backend: con uno inventado esta prueba no
        // mediria nada —`json()` sobre `""` es lo que reventaba—.
        if (estado === 204) return Promise.resolve(new Response(null, { status: 204 }));
        return Promise.resolve(
          new Response(JSON.stringify({ codigo: 'NO_ENCONTRADO' }), {
            status: estado,
            headers: { 'content-type': 'application/problem+json' },
          }),
        );
      }),
    );
    return pedidas;
  }

  it('404 dice que el codigo no esta en el padron, y NO «fallo (404)»', async () => {
    const pedidas = conLaDeterminacionEn(404);
    const { result } = renderHook(
      () => useDatosDeLaHoja('territorio', { sujeto: 'NO-EXISTE', parametros: {} }),
      { wrapper: arnes().wrapper },
    );

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('no esta en el padron');
    });
    expect(result.current.ausencia.enElCampo).not.toBe('fallo');
    expect(result.current.ausencia.explicacion).not.toMatch(/404/);
    expect(pedidas.some((url) => url.includes('codContribuyente=NO-EXISTE'))).toBe(true);
  });

  it('204 dice que todavia no se le ha determinado, y NO «sin datos» ni «fallo»', async () => {
    // Las dos mitades. Que **no reviente** —hasta #237, `json()` sobre un 204 lanzaba
    // `SyntaxError: Unexpected end of JSON input`, que no es un `ErrorDeLaApi`, asi que la pantalla
    // decia «fallo» sin codigo—, y que lo diga con la frase de ESTA hoja y no con la generica.
    conLaDeterminacionEn(204);
    const { result } = renderHook(
      () => useDatosDeLaHoja('territorio', { sujeto: '00000000008', parametros: {} }),
      { wrapper: arnes().wrapper },
    );

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('sin determinar');
    });
    expect(result.current.ausencia.enElCampo).not.toBe('sin datos');
    expect(result.current.ausencia.enElCampo).not.toBe('fallo');
    expect(result.current.ausencia.explicacion).toMatch(/no se ha asentado/);
  });

  it('sin sujeto en la direccion no pide la determinacion, y lo dice', async () => {
    const pedidas = conLaDeterminacionEn(200);
    const { result } = renderHook(() => useDatosDeLaHoja('territorio'), {
      wrapper: arnes().wrapper,
    });

    await waitFor(() => {
      expect(result.current.ausencia.enElCampo).toBe('falta el contribuyente');
    });
    expect(pedidas.filter((url) => url.includes('/rentas/predial/determinaciones'))).toEqual([]);
  });
});

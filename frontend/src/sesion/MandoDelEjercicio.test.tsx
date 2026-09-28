import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

import { Aplicacion, CONSULTAS } from '../aplicacion.tsx';
import type {
  MovimientoDeLaBitacora,
  PermisosDeLaSesion,
  SesionDeLaVentanilla,
  SesionTrasElCambio,
} from '../datos/lecturas.ts';
import {
  ACCESOS_MEDIDOS,
  MODULOS_MEDIDOS,
  PERMISOS_MEDIDOS,
  sinElPrivilegioEspecial,
} from '../datos/seguridadMedida.ts';
import { MUNICIPALIDAD_MEDIDA, SESION_MEDIDA } from '../datos/sesionMedida.ts';

/**
 * **El ejercicio de trabajo se fija desde la barra, y las hojas que lo exigen vuelven a pedir** (#391).
 *
 * <h2>De que defecto viene</h2>
 *
 * `seg-aud` y `territorio` declaran `exigeEjercicio`, y la cuenta medida —`administrador`— contesta
 * `ejercicioDeTrabajo: null`. Las dos decian «falta el ejercicio» y mandaban a fijarlo en
 * «Seguridad · Sistema», que es una definicion interpretada **sin conector**: su desplegable y su
 * motivo no mandan nada. `cambiarElEjercicio` —el `PUT /seguridad/sesion/ejercicio` que el backend
 * sirve— no tenia ni un consumidor desde que `623a968` (#90) borro el mando de la V6. Dos hojas
 * cuya lectura se sirve no se podian abrir por ningun camino de la interfaz.
 *
 * <h2>La siembra que distingue: un ejercicio que NO es el del reloj</h2>
 *
 * El reloj del puesto va fijo en **2026** y lo que se elige es **2024**. Con 2026, un mando que
 * leyera `new Date().getFullYear()` —o una hoja que pidiera el ano de hoy en vez del de la
 * sesion— saldria verde, porque las dos cifras coincidirian. Lo comprueba el centinela.
 *
 * <h2>El servidor lleva su propio estado</h2>
 *
 * El doble recuerda el ejercicio que le fijan y lo contesta en los `GET /seguridad/sesion` que
 * vengan despues, como la instalacion. Asi la prueba no decide COMO se entera la cache —escribirla
 * con la respuesta del `PUT` o invalidarla y volver a pedir—: exige que se entere. Sin ninguna de
 * las dos, la sesion cacheada sigue diciendo `null` y la hoja sigue en «falta el ejercicio».
 *
 * <h2>Por que llevan plazo propio, y por que eso no es subir el tope a ciegas (#629)</h2>
 *
 * Con `yarn verificar` corriendo las ochenta suites a la vez sobre un puesto cargado, la primera
 * prueba que monta la aplicacion caducaba en el tope de 5 s de Vitest —5 373 ms, medido— y sola
 * pasaba en menos de uno. **No espera nada**: ni un reloj, ni un `findBy` que agote su plazo. Es
 * trabajo de CPU, y se midio en que:
 *
 * · con un perfil de V8 sobre el cuerpo de la prueba, **el 58 %** es React montando y repintando
 *   la aplicacion entera en su compilacion de desarrollo —el armazon, el arbol de diez modulos, la
 *   paleta de cuarenta destinos, la hoja con sus tres desplegables y su tabla, y el cajon—, repartido
 *   entre decenas de componentes sin ninguno que destaque; un 19 % son los eventos de `userEvent`,
 *   que arrastran los repintados que disparan, y un 12 % las consultas por rol;
 * · con `process.cpuUsage()` alrededor de cada prueba, **1 405 ms de CPU** la de `seg-aud` —que
 *   ademas paga la primera compilacion de todos los componentes— y **795** la de `territorio`.
 *
 * Lo que sobraba se quito: la observacion tecleada letra a letra (ver `escribirLaObservacion`), que
 * baja esas dos a **1 098** y **614** ms —cuatro corridas alternas de cada version, en el mismo
 * puesto—. Lo que queda **es lo que estas pruebas existen para medir**: que el mando de la barra y
 * la hoja se enteren a traves de la cache de la aplicacion montada, y eso no se prueba montando
 * menos. Con la maquina cargada el reloj de pared llego a cuatro veces la CPU, asi que 1,1 s de CPU
 * no caben con holgura en 5 s de pared.
 *
 * Por eso el plazo se escribe aqui, con la forma de #504 (`una-hoja-que-revienta-no-se-lleva-el-
 * armazon.test.tsx`): las esperas de una cadena —el `PUT`, la cache, la lectura nueva y su
 * repintado— esperan al HECHO con `ESPERAR`, y la prueba entera lleva `PLAZO_DE_LA_PRUEBA`. En
 * verde no cuesta nada: `waitFor` vuelve en cuanto el hecho se cumple. Solo pesa cuando el hecho no
 * llega, y entonces el rojo es el mismo, un rato despues.
 */

/**
 * **Se espera al hecho, no al reloj** (#629, como #504). `waitFor` sin opciones se rinde al segundo,
 * y lo que se espera tras pulsar es una cadena entera: ver el javadoc de arriba.
 */
const ESPERAR = { timeout: 10_000 };

/** Por encima de la suma de las esperas de una prueba que monta la aplicacion: ver `ESPERAR`. */
const PLAZO_DE_LA_PRUEBA = { timeout: 60_000 };

/** El ano del reloj del puesto durante estas pruebas. */
const ANO_DEL_RELOJ = 2026;
/** El que se elige. DISTINTO del reloj: ver el javadoc. */
const ELEGIDO = 2024;
/** Una observacion que el backend admite (al menos cinco caracteres, ADR-0008). */
const MOTIVO = 'Revisar la bitacora del ejercicio 2024';

/** Lo que el backend contesta a una observacion corta, medido (ver `cambiarElEjercicio`). */
const OBSERVACION_CORTA =
  'La observacion debe explicar el cambio: al menos 5 caracteres, y no espacios en blanco (ADR-0008)';

/** Una fila de la bitacora del ejercicio que se pida. */
function movimientoDe(ejercicio: number): MovimientoDeLaBitacora {
  return {
    id: 7,
    ejercicio,
    tabla: 'sesion',
    clave: '2',
    operacion: 'MODIFICACION',
    usuario: 'administrador',
    origenEquipo: null,
    origenIp: null,
    fecha: `${String(ejercicio)}-08-13T09:41:12-05:00`,
    observacion: 'cambio de ejercicio',
    datosAnteriores: null,
    datosNuevos: null,
  };
}

interface Peticion {
  readonly metodo: string;
  readonly ruta: string;
  readonly consulta: URLSearchParams;
  readonly cuerpo: unknown;
}

/** Lo que salio a la red en cada prueba, en orden. */
let peticiones: Peticion[] = [];
/** El ejercicio que el SERVIDOR tiene fijado. Arranca como la instalacion: sin ninguno. */
let ejercicioDelServidor: number | null = null;
/** La matriz de permisos que contesta `/sesion/permisos`. */
let permisos: PermisosDeLaSesion = PERMISOS_MEDIDOS;

beforeAll(() => {
  // Lo que jsdom no trae y las piezas del armazon piden. Sus motivos, en `@kamayuk/shell`.
  Element.prototype.scrollIntoView = () => {};
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.releasePointerCapture = () => {};
  globalThis.matchMedia ??= ((consulta: string) => ({
    matches: false,
    media: consulta,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as typeof matchMedia;
});

beforeEach(() => {
  // Solo el reloj de pared: los temporizadores siguen siendo los de verdad, que son los que usan
  // `waitFor` y React Query.
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(`${String(ANO_DEL_RELOJ)}-09-26T10:00:00-05:00`));

  // La cache es de MODULO y sobrevive a cada `render`: ver `aplicacion.tsx`.
  CONSULTAS.clear();
  peticiones = [];
  ejercicioDelServidor = null;
  permisos = PERMISOS_MEDIDOS;

  vi.stubGlobal(
    'fetch',
    vi.fn<typeof fetch>((entrada, init) => {
      const url = new URL(String(entrada), 'http://localhost');
      const ruta = url.pathname;
      const metodo = init?.method ?? 'GET';
      const cuerpo: unknown =
        typeof init?.body === 'string' ? (JSON.parse(init.body) as unknown) : undefined;
      peticiones.push({ metodo, ruta, consulta: url.searchParams, cuerpo });

      const json = (contenido: unknown, estado = 200) =>
        Promise.resolve(
          new Response(JSON.stringify(contenido), {
            status: estado,
            headers: { 'content-type': 'application/json' },
          }),
        );
      const pagina = (contenido: readonly unknown[]) =>
        json({
          contenido,
          pagina: 0,
          tamano: 20,
          totalElementos: contenido.length,
          totalPaginas: 1,
          hayMas: false,
        });

      if (ruta.endsWith('/seguridad/modulos')) return pagina(MODULOS_MEDIDOS);
      if (ruta.endsWith('/seguridad/accesos')) return pagina(ACCESOS_MEDIDOS);
      if (ruta.endsWith('/seguridad/sesion/permisos')) return json(permisos);
      if (ruta.endsWith('/seguridad/sesion/municipalidad')) return json(MUNICIPALIDAD_MEDIDA);

      if (ruta.endsWith('/seguridad/sesion/ejercicio') && metodo === 'PUT') {
        const { ejercicio, observacion } = cuerpo as { ejercicio: number; observacion?: string };
        // La regla la sostiene el backend, y aqui el doble contesta lo que ella contesta.
        if (typeof observacion !== 'string' || observacion.trim().length < 5) {
          return json(
            { title: 'Validacion', status: 422, codigo: 'VALIDACION', mensaje: OBSERVACION_CORTA },
            422,
          );
        }
        ejercicioDelServidor = ejercicio;
        const tras: SesionTrasElCambio = {
          id: 31,
          usuarioId: SESION_MEDIDA.usuarioId,
          inicio: '2026-09-26T08:00:00-05:00',
          ejercicioDeTrabajo: ejercicio,
        };
        return json(tras);
      }

      if (ruta.endsWith('/seguridad/sesion')) {
        const sesion: SesionDeLaVentanilla = {
          ...SESION_MEDIDA,
          ejercicioDeTrabajo: ejercicioDelServidor,
        };
        return json(sesion);
      }

      if (ruta.endsWith('/seguridad/auditoria')) {
        return pagina([movimientoDe(Number(url.searchParams.get('ejercicio')))]);
      }
      // La determinacion de `territorio`: el 204 de «todavia no se le ha determinado» basta, lo que
      // se mide es con que ejercicio se pide.
      if (ruta.endsWith('/rentas/predial/determinaciones')) {
        return Promise.resolve(new Response(null, { status: 204 }));
      }

      return json({}, 404);
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  window.location.hash = '';
});

/** Monta la aplicacion en `destino` y devuelve la barra global, que no existe hasta que hay arbol. */
async function montarEn(destino: string): Promise<HTMLElement> {
  window.location.hash = `#/${destino}`;
  render(<Aplicacion />);
  let barra: HTMLElement | null = null;
  await waitFor(() => {
    barra = document.querySelector<HTMLElement>('[data-slot="barra-global"]');
    expect(barra).not.toBeNull();
  }, ESPERAR);
  return barra as unknown as HTMLElement;
}

/** Las veces que se pidio la bitacora, por el ejercicio con que se pidio. */
function bitacorasPedidas(): readonly (string | null)[] {
  return peticiones
    .filter((p) => p.metodo === 'GET' && p.ruta.endsWith('/seguridad/auditoria'))
    .map((p) => p.consulta.get('ejercicio'));
}

/** Las escrituras del ejercicio que salieron. */
function cambiosMandados(): readonly Peticion[] {
  return peticiones.filter((p) => p.metodo === 'PUT' && p.ruta.endsWith('/seguridad/sesion/ejercicio'));
}

/**
 * **La observacion se PEGA, no se teclea letra a letra** (#629).
 *
 * `usuario.type` con las 38 letras de `MOTIVO` son 38 vueltas de cinco eventos —`keydown`,
 * `keypress`, `beforeinput`, `input` y `keyup`— por jsdom, y 38 pasadas de React sobre el mando y su
 * cajon: medido con `process.cpuUsage()` alrededor de cada prueba, **algo mas de una quinta parte
 * de la CPU** de las dos que montan la aplicacion para escribir —de 1 405 a 1 098 ms la de
 * `seg-aud`, de 795 a 614 la de `territorio`—. Ninguna afirmacion de este archivo mira el camino
 * letra a letra: el mando lee la observacion de su `onChange`, que es el mismo para una tecla que
 * para un pegado. Lo que si se mira es que el `PUT` lleve **lo escrito**, y un pegado lo escribe
 * igual.
 *
 * El ejercicio se sigue TECLEANDO: son cuatro teclas, y es donde el orden importa —un campo que
 * arrancase en el ano del reloj daria `20262024`, que es la rotura R4 de #391—.
 */
async function escribirLaObservacion(
  usuario: ReturnType<typeof userEvent.setup>,
  dialogo: HTMLElement,
  texto: string,
): Promise<void> {
  await usuario.click(within(dialogo).getByLabelText('Observacion'));
  await usuario.paste(texto);
}

describe('#391 — el ejercicio de trabajo se fija desde la barra', () => {
  it('EL CENTINELA: el ejercicio elegido NO es el del reloj del puesto', () => {
    // Sin esto, cambiar la siembra por el ano de hoy dejaria la prueba de abajo verde con un mando
    // que leyera el reloj: las dos cifras dirian lo mismo.
    expect(new Date().getFullYear()).toBe(ANO_DEL_RELOJ);
    expect(ELEGIDO).not.toBe(ANO_DEL_RELOJ);
    expect(SESION_MEDIDA.ejercicioDeTrabajo).toBeNull();
  });

  it('desde «falta el ejercicio», el mando manda el PUT con su observacion y seg-aud pide la bitacora DE ESE ejercicio', PLAZO_DE_LA_PRUEBA, async () => {
    const usuario = userEvent.setup();
    const barra = await montarEn('seg-aud');

    // El punto de partida es el de la instalacion: sin ejercicio, la hoja lo dice y no pide nada.
    await waitFor(() => {
      expect(screen.getAllByText('falta el ejercicio').length).toBeGreaterThan(0);
    }, ESPERAR);
    expect(bitacorasPedidas()).toEqual([]);

    await usuario.click(within(barra).getByRole('button', { name: /ejercicio de trabajo/i }));
    const dialogo = await screen.findByRole('dialog', { name: 'Cambiar el ejercicio de trabajo' }, ESPERAR);

    // El campo arranca VACIO: ni el ano del reloj ni una lista que salga de el (AC2 de #181).
    const anio = within(dialogo).getByLabelText('Ejercicio');
    expect((anio as HTMLInputElement).value).toBe('');
    expect(dialogo.textContent).not.toContain(String(ANO_DEL_RELOJ));

    await usuario.type(anio, String(ELEGIDO));
    await escribirLaObservacion(usuario, dialogo, MOTIVO);
    await usuario.click(within(dialogo).getByRole('button', { name: 'Cambiar el ejercicio' }));

    // (a) Salio UN PUT, con el ejercicio elegido y la observacion tecleada.
    await waitFor(() => {
      expect(cambiosMandados()).toHaveLength(1);
    }, ESPERAR);
    expect(cambiosMandados()[0]?.cuerpo).toEqual({ ejercicio: ELEGIDO, observacion: MOTIVO });

    // (b) Y la hoja volvio a pedir, con ESE ejercicio y con ningun otro.
    await waitFor(() => {
      expect(bitacorasPedidas()).toContain(String(ELEGIDO));
    }, ESPERAR);
    expect(bitacorasPedidas()).not.toContain(String(ANO_DEL_RELOJ));
    await waitFor(() => {
      expect(screen.queryAllByText('falta el ejercicio')).toEqual([]);
    }, ESPERAR);
    // Y la barra dice el ejercicio nuevo.
    expect(within(barra).getByRole('button', { name: /ejercicio de trabajo/i }).textContent).toContain(
      String(ELEGIDO),
    );
  });

  it('y `territorio`, la otra hoja que lo exige, tambien vuelve a pedir con ESE ejercicio', PLAZO_DE_LA_PRUEBA, async () => {
    const usuario = userEvent.setup();
    const barra = await montarEn('territorio/00000025673');
    const determinaciones = () =>
      peticiones
        .filter((p) => p.metodo === 'GET' && p.ruta.endsWith('/rentas/predial/determinaciones'))
        .map((p) => p.consulta.get('ejercicio'));

    await waitFor(() => {
      expect(screen.getAllByText('falta el ejercicio').length).toBeGreaterThan(0);
    }, ESPERAR);
    expect(determinaciones()).toEqual([]);

    await usuario.click(within(barra).getByRole('button', { name: /ejercicio de trabajo/i }));
    const dialogo = await screen.findByRole('dialog', { name: 'Cambiar el ejercicio de trabajo' }, ESPERAR);
    await usuario.type(within(dialogo).getByLabelText('Ejercicio'), String(ELEGIDO));
    await escribirLaObservacion(usuario, dialogo, MOTIVO);
    await usuario.click(within(dialogo).getByRole('button', { name: 'Cambiar el ejercicio' }));

    await waitFor(() => {
      expect(determinaciones()).toEqual([String(ELEGIDO)]);
    }, ESPERAR);
  });

  it('el 422 del backend se ensena con SUS palabras, y el cajon se queda abierto con lo tecleado', PLAZO_DE_LA_PRUEBA, async () => {
    const usuario = userEvent.setup();
    const barra = await montarEn('seg-aud');

    await usuario.click(within(barra).getByRole('button', { name: /ejercicio de trabajo/i }));
    const dialogo = await screen.findByRole('dialog', { name: 'Cambiar el ejercicio de trabajo' }, ESPERAR);
    await usuario.type(within(dialogo).getByLabelText('Ejercicio'), String(ELEGIDO));
    await usuario.type(within(dialogo).getByLabelText('Observacion'), 'ok');
    await usuario.click(within(dialogo).getByRole('button', { name: 'Cambiar el ejercicio' }));

    await waitFor(() => {
      expect(within(dialogo).getByText(OBSERVACION_CORTA, { exact: false })).toBeTruthy();
    }, ESPERAR);
    expect(cambiosMandados()).toHaveLength(1);
    // Lo tecleado sigue ahi: el 422 mas probable es «la observacion es corta», y cerrar obligaria
    // a teclearlo todo otra vez.
    expect((within(dialogo).getByLabelText('Ejercicio') as HTMLInputElement).value).toBe(
      String(ELEGIDO),
    );
    // Y como no se fijo nada, la hoja sigue sin pedir.
    expect(bitacorasPedidas()).toEqual([]);
  });

  it('sin observacion NO se manda nada: el boton no se puede pulsar', PLAZO_DE_LA_PRUEBA, async () => {
    const usuario = userEvent.setup();
    const barra = await montarEn('seg-aud');

    await usuario.click(within(barra).getByRole('button', { name: /ejercicio de trabajo/i }));
    const dialogo = await screen.findByRole('dialog', { name: 'Cambiar el ejercicio de trabajo' }, ESPERAR);
    await usuario.type(within(dialogo).getByLabelText('Ejercicio'), String(ELEGIDO));

    const cambiar = within(dialogo).getByRole('button', { name: 'Cambiar el ejercicio' });
    expect((cambiar as HTMLButtonElement).disabled).toBe(true);
    await usuario.click(cambiar);
    expect(cambiosMandados()).toEqual([]);
  });

  it('a la cuenta sin «especial» sobre cambiar_anio no se le ofrece el mando: solo el valor', PLAZO_DE_LA_PRUEBA, async () => {
    permisos = sinElPrivilegioEspecial();
    const barra = await montarEn('seg-aud');

    await waitFor(() => {
      expect(within(barra).getByText(/ejercicio/i)).toBeTruthy();
    }, ESPERAR);
    expect(within(barra).queryByRole('button', { name: /ejercicio de trabajo/i })).toBeNull();
  });
});

import type { APIRequestContext } from '@playwright/test';

/**
 * Las senas de la instalacion contra la que corre el arnes, y la comprobacion previa del AC6.
 *
 * <h2>Por que este archivo existe aparte de `playwright.config.ts`</h2>
 *
 * La configuracion la lee Playwright una vez, al arrancar. La comprobacion previa la leen los
 * caminos, cada uno antes de su primera pagina. Si las dos cosas vivieran en el mismo archivo,
 * importar la configuracion desde un `.spec.ts` arrastraria el `webServer` y los proyectos a un
 * sitio donde no pintan nada.
 */

/**
 * El puerto de Vite, y **no es una preferencia: es el contrato de identidad**.
 *
 * `src/api/identidad.ts` compone la URI de retorno como `window.location.origin + '/'`, o sea
 * que el ORIGEN DE LA PAGINA es lo que viaja como `redirect_uri` en la ida a Keycloak y otra
 * vez en el canje. El cliente `kamayuk-backoffice` del realm admite exactamente
 * `http://localhost:5173/*`, asi que servir la aplicacion en cualquier otro puerto no hace que
 * la prueba sea menos comoda: hace que Keycloak conteste `invalid_redirect_uri` y que no haya
 * canje que medir.
 *
 * Ensanchar `redirectUris` en el realm seria la otra salida, y **no se toma**: el realm lo
 * describe `infrastructure`, que es otro repositorio, y un arnes que necesita que le cambien la
 * configuracion de identidad para pasar estaria midiendo un redirect que la instalacion no
 * admite y que produccion no usa.
 */
export const PUERTO = 5173;

/** El origen de la aplicacion. Es el que Keycloak tiene registrado, letra por letra. */
export const ORIGEN = `http://localhost:${String(PUERTO)}`;

/**
 * La pagina de entrada. **Con `/rentas/` y no `/`**: `vite.config.ts` declara
 * `base: '/rentas/'` (ADR-0030 §2), asi que el servidor de desarrollo contesta `302` en la raiz.
 *
 * Medido, porque de esto depende que el canje funcione y no se puede suponer: el `302` de la
 * base **conserva la cadena de consulta** —`/?code=A&state=B` da `Location: /rentas/?code=A&state=B`—,
 * que es justo lo que hace falta para que la vuelta de Keycloak a `http://localhost:5173/`
 * llegue con su codigo al sitio donde la aplicacion lo lee.
 */
export const ENTRADA = `${ORIGEN}/rentas/`;

/** Donde esta el backend. La misma variable que lee `vite.config.ts`, y el mismo valor. */
export const BACKEND = process.env.KAMAYUK_BACKEND ?? 'http://localhost:8082';

/** El emisor de identidad. La misma variable que lee `src/api/identidad.ts`. */
export const EMISOR = process.env.KAMAYUK_OIDC_REALM ?? 'http://localhost:8181/realms/kamayuk';

/**
 * La sonda del backend, y **por que es una ruta protegida y no una de salud**.
 *
 * Medido: `GET /rentas/actuator/health` a traves de Traefik contesta **401**, no un estado de
 * salud —la cadena de identidad se pone delante de todo—, asi que no hay endpoint publico que
 * preguntar. Y resulta que la ruta protegida es MEJOR sonda que uno de salud: un **401 sin
 * token** demuestra tres cosas de una vez —que el backend esta en pie, que Traefik enruta
 * `PathPrefix(/rentas)` y que la cadena de identidad esta montada—, y la tercera es la que un
 * `200 {"status":"UP"}` no demostraria.
 *
 * Se pide **sin cabecera de identidad a proposito**: la sonda no necesita credencial, y pedirle
 * una la haria fallar por un motivo que no es el que se quiere distinguir.
 */
export const SONDA_DEL_BACKEND = `${BACKEND}/rentas/api/v1/seguridad/sesion`;

/** La sonda del emisor: el documento de descubrimiento, que si es publico y contesta 200. */
export const SONDA_DEL_EMISOR = `${EMISOR}/.well-known/openid-configuration`;

/** Lo que hay que teclear para levantar la instalacion, tal cual. */
export const ORDEN_PARA_LEVANTARLA =
  'cd ../../infrastructure && docker compose -f despliegue/plataforma.compose.yaml up -d --wait';

/**
 * Si la ausencia de instalacion es un fallo en vez de un salto.
 *
 * <h2>Las dos mitades del AC6, que se contradicen si se leen a medias</h2>
 *
 * «Las pruebas se saltan solas, y lo dicen, si no hay instalacion» y «lo que no puede hacer es
 * pasar en verde sin haber probado nada» no piden lo mismo. Un salto es comodo en el escritorio
 * de quien no ha levantado nada; en un sitio donde la instalacion TENIA que estar, el mismo
 * salto es la forma silenciosa de no verificar.
 *
 * Asi que la ausencia se salta por omision y **falla cuando alguien lo exige**. Quien lo exige
 * es CI, que la levanta y por tanto sabe que tiene que estar.
 */
export const SE_EXIGE_LA_INSTALACION = process.env.KAMAYUK_E2E_EXIGIR === '1';

/**
 * La clave con la que el arnes entra. **Nunca se escribe en el repositorio** (AC2).
 *
 * En la instalacion local las tres cuentas comparten `KAMAYUK_CLAVE_VERIFICACION`, de
 * `infrastructure/despliegue/.env`. Se admite una por cuenta por si dejan de compartirla.
 */
export function claveDe(usuario: string): string | undefined {
  const propia = process.env[`KAMAYUK_E2E_CLAVE_${usuario.toUpperCase().replace(/-/g, '_')}`];
  return propia ?? process.env.KAMAYUK_E2E_CLAVE;
}

/** Una cuenta de la instalacion, con el archivo donde su estado de acceso se guarda. */
export interface CuentaDeLaInstalacion {
  /** El usuario tal como lo teclea el formulario de Keycloak. */
  readonly usuario: string;
  /** Para que sirve esta cuenta y no otra. */
  readonly paraQue: string;
  /** El archivo de `storageState`. Va fuera del arbol versionado. */
  readonly estado: string;
  /**
   * Donde tiene que acabar ESTA cuenta despues de entrar, y **por que se declara por cuenta**.
   *
   * La primera version del arnes esperaba «el marco o un aviso», que es lo que se ve entrar
   * bien y lo que se ve entrar mal. Medido: con el `redirect_uri` roto, la aplicacion rebota
   * tres veces, se para y ensena el aviso del 401 — y esa espera **la daba por buena**. Un
   * canje fallido y `sin-municipalidad` se veian igual, asi que la unica manera de separarlos
   * es que cada cuenta diga a que tiene derecho.
   */
  readonly acaba: 'marco' | 'sin-municipalidad';
}

/**
 * Las tres cuentas, y **por que hacen falta tres**.
 *
 * Ninguna municipalidad de esta instalacion tiene a la vez padron grande y libro con
 * movimiento, y ninguna de las dos que trabajan puede ensenar el peldano del 403: una cuenta
 * sin municipalidad no es una cuenta rota, es la que demuestra que la escalera distingue «no
 * entraste» de «entraste y no dices de donde eres».
 */
export const CUENTAS = {
  escala: {
    usuario: 'administrador',
    paraQue: 'la escala: municipalidad 9 (Catacaos), con 10 603 contribuyentes',
    estado: 'e2e/.estado/administrador.json',
    acaba: 'marco',
  },
  movimiento: {
    usuario: 'jperez',
    paraQue: 'el movimiento: municipalidad 1 (Sullana), con corridas y asientos',
    estado: 'e2e/.estado/jperez.json',
    acaba: 'marco',
  },
  sinMunicipalidad: {
    usuario: 'sin-municipalidad',
    paraQue: 'el peldano del 403 SIN_MUNICIPALIDAD, que las otras dos no pueden ensenar',
    estado: 'e2e/.estado/sin-municipalidad.json',
    acaba: 'sin-municipalidad',
  },
} as const satisfies Record<string, CuentaDeLaInstalacion>;

/** Lo que la comprobacion previa encontro. Sin quejas, la instalacion sirve. */
export interface Diagnostico {
  readonly sirve: boolean;
  readonly motivo: string;
}

/**
 * La comprobacion previa del AC6: **las dos piezas, y el mensaje dice cual falta**.
 *
 * Sin backend no hay dato que leer; sin emisor no hay forma de entrar. Son dos averias
 * distintas y se arreglan igual —levantando la instalacion—, pero saber cual de las dos esta
 * caida es la diferencia entre mirar el contenedor que toca y mirar los cuatro.
 *
 * <h2>Un 200 en la sonda del backend seria PEOR que un 401</h2>
 *
 * Se comprueba contra Traefik y no contra el servidor de Vite a proposito, pero conviene saber
 * lo que significaria un `200` aqui: que quien contesta es Vite con su `index.html`, o sea que
 * `server.proxy` no esta puesto. Es el modo de fallo que `src/datos/servidas.ts` lleva escrito
 * desde F-4 —«un 200 con HTML donde la pantalla espera JSON»—, y por eso la sonda no se
 * conforma con «contesto algo»: exige el 401 de la cadena de identidad.
 *
 * @param peticion el contexto de peticiones de Playwright. **No se usa `fetch`**: la
 *   prohibicion `fetch-fuera-del-cliente` vale en todo el arbol menos en `src/api/`, y el arnes
 *   no es una excepcion de nada. Playwright trae su propio cliente y se usa ese.
 */
export async function comprobarLaInstalacion(peticion: APIRequestContext): Promise<Diagnostico> {
  const quejas: string[] = [];

  try {
    const respuesta = await peticion.get(SONDA_DEL_BACKEND, {
      failOnStatusCode: false,
      timeout: 10_000,
    });
    if (respuesta.status() !== 401) {
      quejas.push(
        `el backend contesto ${String(respuesta.status())} en «${SONDA_DEL_BACKEND}» y se ` +
          'esperaba 401. Un 200 aqui significa que contesta Vite con su index.html y que ' +
          'falta «server.proxy»; cualquier otro codigo, que la cadena de identidad no esta ' +
          'montada delante de la API.',
      );
    }
  } catch {
    quejas.push(`el backend no contesto en «${SONDA_DEL_BACKEND}»`);
  }

  try {
    const respuesta = await peticion.get(SONDA_DEL_EMISOR, {
      failOnStatusCode: false,
      timeout: 10_000,
    });
    if (respuesta.status() !== 200) {
      quejas.push(
        `el emisor de identidad contesto ${String(respuesta.status())} en ` +
          `«${SONDA_DEL_EMISOR}» y se esperaba 200`,
      );
    }
  } catch {
    quejas.push(`el emisor de identidad no contesto en «${SONDA_DEL_EMISOR}»`);
  }

  if (quejas.length === 0) {
    return { sirve: true, motivo: '' };
  }

  return {
    sirve: false,
    motivo:
      `La instalacion no esta: ${quejas.join('; ')}.\n` +
      `Levantala con:\n  ${ORDEN_PARA_LEVANTARLA}\n` +
      'Y para que la ausencia FALLE en vez de saltarse —que es lo que CI necesita—, ' +
      'corre con KAMAYUK_E2E_EXIGIR=1.',
  };
}

/**
 * La clave que falta, dicha con la variable que hay que poner.
 *
 * Se separa del diagnostico de la instalacion porque son dos ausencias distintas: una es que no
 * hay nada levantado y la otra es que si lo hay y no se sabe entrar. Confundirlas manda a
 * levantar contenedores a quien solo tenia que exportar una variable.
 */
export function faltaLaClave(usuario: string): string {
  return (
    `No hay clave para «${usuario}». El arnes NO la lleva escrita (AC2): ponla en el entorno.\n` +
    '  export KAMAYUK_E2E_CLAVE="$(grep -E \'^KAMAYUK_CLAVE_VERIFICACION=\' ' +
    "../../infrastructure/despliegue/.env | cut -d= -f2-)\"\n" +
    `O una por cuenta: KAMAYUK_E2E_CLAVE_${usuario.toUpperCase().replace(/-/g, '_')}`
  );
}

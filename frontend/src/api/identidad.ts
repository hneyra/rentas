/**
 * La puerta de identidad: codigo de autorizacion con PKCE S256 contra Keycloak.
 *
 * <h2>El token vive en memoria, y esa es la decision de este archivo</h2>
 *
 * `../sgtm/frontend/src/api/sesion.ts` hace este mismo flujo contra este mismo realm y guarda
 * lo que canjea en `localStorage.setItem('sgtm.token', …)`. Aqui eso esta **prohibido** —la
 * prohibicion `token-en-almacenamiento` de `eslint.prohibiciones.mjs`, con su muestra que la
 * viola— y el motivo no es purismo: en una PC de ventanilla que tres turnos comparten, un token
 * persistido sobrevive al cierre del navegador, y el del turno de la manana sigue sirviendo por
 * la tarde. Asi que el token es una **variable de modulo**: se muere con la pestana, que es
 * exactamente lo que se quiere.
 *
 * Lo que si sobrevive al rebote es el **verificador PKCE**, y tiene que sobrevivir: el navegador
 * se va a Keycloak y vuelve, y sin el no hay canje. No es una credencial —es el secreto de un
 * solo uso que demuestra que quien canja es quien pidio—, asi que va en `sessionStorage`. Su
 * clave no lleva ninguna de las palabras que la prohibicion vigila, y **no por esquivarla**:
 * llamarlo `sgtm.token.verificador` seria pedirle a quien lea el codigo dentro de seis meses que
 * distinga dos cosas que se llaman igual.
 *
 * <h2>Lo que cuesta no guardar el token: nada, porque hay SSO</h2>
 *
 * Un token dura minutos. En vez de guardar un `refresh_token` —que es una credencial de vida
 * larga, y el problema de arriba otra vez— se vuelve a pedir un codigo: con la sesion de
 * Keycloak viva el navegador va y vuelve sin ensenar nada, y si no lo esta, se ve el formulario,
 * que es lo que hay que ver. La renovacion silenciosa sale gratis de tener SSO.
 *
 * <h2>Aqui SI se sale a la puerta desde localhost, y en `sgtm` no</h2>
 *
 * `sesion.ts:66` se salta la puerta en `localhost` porque el puerto de su vista previa no estaba
 * entre las URI de retorno del cliente y el rebote acababa en «Invalid parameter: redirect_uri».
 * Aqui esta medido que si esta: `kamayuk-backoffice` declara `http://localhost:5173/*`, que es donde
 * sirve `yarn dev`. Y `crypto.subtle` existe: `http://localhost` es un origen seguro para el
 * navegador, asi que S256 se puede calcular. Saltarse la puerta aqui seria dejar el unico camino
 * que este issue viene a abrir sin recorrer ni una vez.
 */

import { configuracion } from './configuracion.ts';

/**
 * El realm. Se configura por ambiente: el emisor no es el mismo en el cluster que aqui.
 *
 * <h2>Por que esto es una FUNCION y no una constante de modulo (#44)</h2>
 *
 * Hasta #44 estas tres eran `const` que leian `import.meta.env`, y eso hacia lo unico que no se
 * podia hacer: **hornear la URL del emisor dentro de la imagen**. Vite sustituye
 * `import.meta.env.VITE_*` al construir, asi que una imagen etiquetada con el `sha` del
 * repositorio solo habria servido para el ambiente en que se construyo.
 *
 * Ahora salen de `configuracion()`, que las resuelve **al llamar** con los tres escalones que
 * `configuracion.ts` documenta. Y son funciones —no constantes evaluadas al importar— porque una
 * constante de modulo se fija en el orden de carga de los modulos: si este archivo se importara
 * antes de que `configuracion.js` hubiera corrido, la constante congelaria el valor por omision
 * y el ambiente no entraria nunca. El orden se sujeta en `index.html`, pero atarlo ademas al
 * orden de importacion seria una segunda condicion que nadie comprueba.
 */
const realm = () => configuracion('oidcRealm');

/** El cliente publico de la SPA. Sin secreto: un secreto en un bundle no es un secreto. */
const cliente = () => configuracion('oidcCliente');

const alcance = () => configuracion('oidcAlcance');

const autorizacion = () => `${realm()}/protocol/openid-connect/auth`;
const canje = () => `${realm()}/protocol/openid-connect/token`;
const fin = () => `${realm()}/protocol/openid-connect/logout`;

/**
 * Las cuatro claves del rebote.
 *
 * Ninguna lleva `token`, `jwt`, `bearer`, `credencial`, `contrasena`, `acceso` ni `sesion`: lo
 * que se guarda aqui no es ninguna de esas cosas.
 */
const VERIFICADOR = 'kamayuk.pkce.verificador';
const ESTADO = 'kamayuk.pkce.estado';
const DESTINO = 'kamayuk.pkce.destino';
const IDAS = 'kamayuk.pkce.idas';
const SALIDA = 'kamayuk.pkce.salida';

/**
 * Cuantas idas seguidas a la puerta se admiten antes de parar y explicarse.
 *
 * Tres idas sin canjear son un bucle, no mala suerte. Sin tope, el arranque rebota sin fin:
 * pagina en blanco parpadeando, ninguna traza, y el emisor recibiendo la rafaga.
 */
const TOPE_DE_IDAS = 3;

/**
 * El token. En memoria y en ningun otro sitio.
 *
 * `let` de modulo y no un `localStorage`: al cerrar la pestana desaparece. Ver la cabecera.
 */
let enMemoria: string | null = null;

/**
 * El `id_token`, tambien en memoria. Solo se usa para `id_token_hint` al salir.
 *
 * Sin el, cerrar sesion deja viva la sesion del emisor y el siguiente arranque entra solo con la
 * misma cuenta sin que nadie haya tecleado nada — que es lo que `sesion.ts` documenta haber
 * sufrido.
 */
let identidadEnMemoria: string | null = null;

/** El token de esta pestana, o `null` si todavia no hay. */
export function token(): string | null {
  return enMemoria;
}

/**
 * Fija el token a mano.
 *
 * Existe para las pruebas y para pegar un token de `kamayuk-verificacion` en desarrollo sin montar
 * el rebote entero. No lo persiste: eso es justo lo que este archivo no hace.
 */
export function fijarToken(nuevo: string | null, identidad: string | null = null): void {
  enMemoria = nuevo;
  identidadEnMemoria = identidad;
}

/** Sin `crypto.subtle` no hay S256, y el navegador no lo expone fuera de un origen seguro. */
export function hayPuerta(): boolean {
  return typeof crypto !== 'undefined' && crypto.subtle !== undefined;
}

function idas(): number {
  return Number(sessionStorage.getItem(IDAS) ?? 0);
}

/** Si se puede volver a la puerta, o hay que pararse y explicarse. Ver `TOPE_DE_IDAS`. */
export function puedeIrALaPuerta(): boolean {
  return idas() < TOPE_DE_IDAS;
}

/** Se acaba de cerrar sesion: el arranque NO debe volver a entrar solo. */
export function vieneDeSalir(): boolean {
  return sessionStorage.getItem(SALIDA) === '1';
}

/** Vuelve a permitir la ida a la puerta. Es el «Volver a identificarse» de la pantalla parada. */
export function olvidarLaParada(): void {
  sessionStorage.removeItem(IDAS);
  sessionStorage.removeItem(SALIDA);
}

/**
 * Por que no se pudo ni mandar a la puerta: quien no contesto, a que URL, y con que palabras.
 *
 * Es lo que se ensena en pantalla, asi que lleva las tres cosas que hacen falta para arreglarlo
 * y ninguna mas. El `motivo` va **en palabras del navegador** —«Failed to fetch»,
 * «TimeoutError»— porque son las que se pueden buscar y las que aparecen en su consola.
 */
export interface FallaDeLaPuerta {
  /** El emisor, tal como lo resuelve `configuracion()`. Lo primero que hay que mirar. */
  readonly emisor: string;
  /** La URL exacta que se pidio para saber si estaba. */
  readonly url: string;
  /** Lo que dijo el navegador, o que se agoto la espera. */
  readonly motivo: string;
}

/**
 * Lo que se espera al emisor antes de darlo por caido.
 *
 * Ocho segundos y no tres: una municipalidad con la plataforma al otro lado de un enlace lento
 * tarda, y dar por caido lo que solo iba despacio manda a la pantalla de error a quien si podia
 * entrar. Y no treinta: mas alla de unos segundos, quien mira ya cree que la pagina esta rota.
 */
const ESPERA_DE_LA_SONDA = 8_000;

/**
 * **Si el emisor esta, ANTES de mandarle el navegador entero** (#112).
 *
 * <h2>El defecto que esto cierra</h2>
 *
 * `entrar()` termina en `location.assign(...)`, y quien la llama no monta nada despues **a
 * proposito**: la pagina se va. Pero cuando la navegacion se RECHAZA —el emisor apagado, un DNS
 * que no resuelve, un cortafuegos que traga— no hay documento nuevo *ni* aplicacion. Medido con
 * `yarn dev` y nada mas levantado: `body.innerText` vacio, `body.innerHTML` vacio y la consola
 * con dos lineas de Vite, ni un error. Nada que leer en ninguna parte.
 *
 * <h2>Por que una sonda y no un tiempo de espera despues de navegar</h2>
 *
 * Porque despues de `assign` ya es tarde: Chromium **cambia de documento** —se midio el marco
 * principal navegando a `chrome-error://chromewebdata/`—, asi que un `setTimeout` que montara la
 * aplicacion correria sobre un documento que el navegador acaba de tirar. Y en el camino bueno
 * haria lo contrario de lo que se quiere: pintar la pantalla justo antes de que la navegacion
 * buena se la lleve, o sea un parpadeo.
 *
 * Preguntando ANTES, el camino bueno no cambia en nada: `assign` sigue siendo lo ultimo que pasa.
 *
 * <h2>Se pregunta al documento de descubrimiento, y NO se lee</h2>
 *
 * `/.well-known/openid-configuration` es publico, barato y no abre ninguna sesion; pedir el
 * `authorization_endpoint` como sonda seria abrir una peticion de autorizacion de verdad —con su
 * rastro en el emisor— para tirarla.
 *
 * Y va con `mode: 'no-cors'` **a proposito**: la respuesta no se lee. La pregunta no es «que
 * contesta el emisor» sino «llega el navegador hasta el», que es exactamente lo que decide si
 * `assign` va a aterrizar. Leyendo el cuerpo haria falta que el emisor publicara CORS, y un
 * intermediario que no lo publique convertiria un emisor VIVO en esta pantalla de error.
 */
async function laPuertaContesta(): Promise<FallaDeLaPuerta | null> {
  const url = `${realm()}/.well-known/openid-configuration`;
  try {
    await fetch(url, {
      mode: 'no-cors',
      // Sin cache: una respuesta guardada diria que el emisor esta cuando ya no.
      cache: 'no-store',
      signal: AbortSignal.timeout(ESPERA_DE_LA_SONDA),
    });
    return null;
  } catch (falla) {
    return { emisor: realm(), url, motivo: enPalabrasDelNavegador(falla) };
  }
}

/** Lo que paso, dicho como el navegador lo dice. Ver `FallaDeLaPuerta.motivo`. */
function enPalabrasDelNavegador(falla: unknown): string {
  if (!(falla instanceof Error)) return 'la peticion no llego a completarse';
  if (falla.name === 'TimeoutError') {
    return `no contesto en ${String(ESPERA_DE_LA_SONDA / 1000)} s`;
  }
  return falla.message === '' ? falla.name : falla.message;
}

/**
 * Manda al formulario de Keycloak, guardando a donde habia que volver.
 *
 * Devuelve `null` cuando el navegador se va —que es el caso de siempre— y **la falla cuando no se
 * pudo ni llegar al emisor**, para que quien llama monte y la explique en vez de dejar la pagina
 * en blanco. Ver `laPuertaContesta()`.
 *
 * La sonda va antes de tocar `sessionStorage`: una ida que no llego a ocurrir no es una ida, y
 * contarla en el tope gastaria los tres intentos contra un emisor que nunca los recibio.
 */
export async function entrar(): Promise<FallaDeLaPuerta | null> {
  const falla = await laPuertaContesta();
  if (falla !== null) return falla;

  const verificador = aleatorio(64);
  const estado = aleatorio(24);
  sessionStorage.setItem(VERIFICADOR, verificador);
  sessionStorage.setItem(ESTADO, estado);
  sessionStorage.setItem(DESTINO, window.location.hash || '#panel');
  sessionStorage.setItem(IDAS, String(idas() + 1));
  sessionStorage.removeItem(SALIDA);

  const parametros = new URLSearchParams({
    response_type: 'code',
    client_id: cliente(),
    redirect_uri: retorno(),
    scope: alcance(),
    state: estado,
    code_challenge: await reto(verificador),
    code_challenge_method: 'S256',
  });
  window.location.assign(`${autorizacion()}?${parametros.toString()}`);
  return null;
}

/** Lo que paso al volver de Keycloak. */
export type Vuelta =
  | { readonly estado: 'sin-vuelta' }
  | { readonly estado: 'canjeado' }
  | { readonly estado: 'fallo'; readonly motivo: string; readonly detalle: string };

/**
 * Si venimos de Keycloak, canjea el codigo por un token.
 *
 * Devuelve **por que** no se pudo, y no un `false` mudo. Quien la llama tiene que decidir entre
 * volver a la puerta y pararse a explicarse, y con un `false` para todo un `?error=` del emisor
 * se trataria igual que «esta URL no traia codigo»: el arranque volveria a la puerta, que
 * devolveria el mismo error, sin fin.
 */
export async function canjearSiVuelve(): Promise<Vuelta> {
  const url = new URL(window.location.href);
  const codigo = url.searchParams.get('code');
  const fallo = url.searchParams.get('error');

  if (codigo === null && fallo === null) return { estado: 'sin-vuelta' };

  const verificador = sessionStorage.getItem(VERIFICADOR);
  const esperado = sessionStorage.getItem(ESTADO);
  const destino = sessionStorage.getItem(DESTINO) ?? '#panel';
  sessionStorage.removeItem(VERIFICADOR);
  sessionStorage.removeItem(ESTADO);
  sessionStorage.removeItem(DESTINO);

  // La URL se limpia SIEMPRE, saliera bien o mal: un codigo ya usado no vale dos veces, y
  // dejarlo en la barra hace que recargar de un error que no tiene nada que ver con lo que paso.
  const limpiar = () => {
    window.history.replaceState(null, '', url.pathname + destino);
  };

  if (fallo !== null) {
    limpiar();
    return {
      estado: 'fallo',
      motivo: motivoDelEmisor(fallo),
      detalle: url.searchParams.get('error_description') ?? `El emisor contesto «${fallo}».`,
    };
  }

  // El estado es lo unico que distingue nuestra vuelta de un codigo que alguien nos hizo
  // llegar. Sin comprobarlo, la puerta acepta cualquier codigo.
  if (codigo === null || verificador === null || esperado === null || url.searchParams.get('state') !== esperado) {
    limpiar();
    return {
      estado: 'fallo',
      motivo: 'La vuelta no cuadra con la ida',
      detalle:
        'El codigo llego sin el estado que se guardo al salir. Suele pasar al abrir un enlace ' +
        'de vuelta antiguo o en otra pestana; tambien es lo que se ve si alguien intenta colar ' +
        'un codigo ajeno.',
    };
  }

  let respuesta: Response;
  try {
    // Con tope. Sin el, un emisor que no contesta deja la aplicacion SIN DIBUJAR NADA para
    // siempre —ni un error ni un esqueleto—, porque el arranque espera aqui antes de montar.
    respuesta = await fetch(canje(), {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: cliente(),
        code: codigo,
        redirect_uri: retorno(),
        code_verifier: verificador,
      }).toString(),
      signal: AbortSignal.timeout(15_000),
    });
  } catch {
    limpiar();
    return {
      estado: 'fallo',
      motivo: 'El emisor no contesto',
      detalle:
        'La peticion del canje no llego a completarse. El emisor puede estar apagado o no ser ' +
        'alcanzable desde este puesto.',
    };
  }

  limpiar();
  if (!respuesta.ok) {
    return {
      estado: 'fallo',
      motivo: 'El emisor rechazo el canje',
      detalle:
        `La peticion del canje volvio con ${String(respuesta.status)}. Suele ser la URI de ` +
        'retorno o el cliente.',
    };
  }

  const cuerpo = (await respuesta.json().catch(() => ({}))) as {
    access_token?: string;
    id_token?: string;
  };
  if (cuerpo.access_token === undefined) {
    return {
      estado: 'fallo',
      motivo: 'El emisor no devolvio ningun token',
      detalle: 'La respuesta del canje no trae «access_token».',
    };
  }

  fijarToken(cuerpo.access_token, cuerpo.id_token ?? null);
  // Salio bien: la cuenta de idas vuelve a cero, para que el tope proteja de una racha de
  // fallos y no de haber entrado muchas veces en el dia.
  sessionStorage.removeItem(IDAS);
  return { estado: 'canjeado' };
}

/** Cierra la sesion aqui y en Keycloak. */
export function salir(): void {
  const identidad = identidadEnMemoria;
  fijarToken(null);
  sessionStorage.removeItem(IDAS);
  // La marca es lo que impide volver a entrar solo al instante: `post_logout_redirect_uri` trae
  // de vuelta sin token, y el arranque veia eso y llamaba a `entrar()` — con la sesion del
  // emisor viva, el usuario acababa DENTRO OTRA VEZ con la misma cuenta sin haber hecho nada.
  sessionStorage.setItem(SALIDA, '1');

  if (!hayPuerta()) {
    window.location.reload();
    return;
  }
  const parametros = new URLSearchParams({ post_logout_redirect_uri: retorno() });
  if (identidad !== null) parametros.set('id_token_hint', identidad);
  window.location.assign(`${fin()}?${parametros.toString()}`);
}

/**
 * **Las dos paginas de la cuenta, que NO son de este sistema** (#115).
 *
 * <h2>Por que salen de aqui y no de una pantalla de Rentas</h2>
 *
 * Porque ni el perfil ni la contrasena son de `rentas`. La autorizacion es de `identidad` desde
 * ADR-0039 —aqui no se da de alta un usuario, no se afilia a nadie y no se fija un permiso— y la
 * contrasena **nunca llega a este sistema**: la guarda Keycloak, que es quien la pide en su
 * formulario. Dibujar aqui un formulario de perfil o de clave seria prometer una escritura que
 * ningun backend de este repositorio puede atender.
 *
 * <h2>La URL se DERIVA del emisor, no se escribe</h2>
 *
 * Sale de `realm()`, o sea de `configuracion('oidcRealm')`, que es exactamente de donde salen
 * `autorizacion()`, `canje()` y `fin()`. Y eso trae la garantia que hace honesto mandar ahi: **si
 * ese origen no fuera alcanzable desde el navegador, nadie habria entrado a Rentas**, porque el
 * formulario de identificacion se sirve del mismo sitio. No es una URL mas que pueda estar mal
 * puesta: es la misma que ya funciono.
 *
 * <h2>Las dos rutas, medidas contra el Keycloak que la plataforma fija</h2>
 *
 * `despliegue/plataforma.compose.yaml` fija `quay.io/keycloak/keycloak:26.0`. Contra el codigo de
 * esa version:
 *
 *   · `RealmsResource.java:191` — `@Path("{realm}/account")`: la consola de cuenta cuelga del
 *     realm, asi que basta con anadir un segmento al emisor que ya se lee;
 *   · `AccountConsole.java:119` — el `baseUrl` que el servidor le pasa a la consola es esa misma
 *     ruta **con barra final**, y `AccountConsole.getMainPage()` esta en `@Path("{any:.*}")`: la
 *     consola se sirve para cualquier sub-ruta, o sea que un enlace profundo entra;
 *   · `js/apps/account-ui/src/routes.tsx` — `PersonalInfoRoute` es la ruta **indice** (de ahi la
 *     barra final para «Mi perfil») y `SigningInRoute` es `account-security/signing-in`, que es
 *     donde se cambia la clave. Y `main.tsx` monta un `createBrowserRouter`: las rutas son de
 *     camino y no de `#`, asi que el enlace profundo es el que se escribe abajo.
 *   · `RealmManager.java:558` — `if (!hasAccountManagementClient(rep)) setupAccountManagement(realm)`
 *     al importar: `realm-kamayuk.json` declara dos clientes —`kamayuk-backoffice` y
 *     `kamayuk-verificacion`— y **ninguno** es `account`, asi que Keycloak los crea al sembrar el
 *     realm. La consola no se queda sin su cliente por no estar en el volcado.
 *
 * **Lo que NO se pudo medir, y se dice**: que una instalacion levantada las sirva. Este puesto no
 * tiene motor de contenedores, asi que la plataforma no se pudo levantar y ninguna de las dos URL
 * se pidio de verdad. Lo comprobado es el codigo de la version que el compose fija, no un 200.
 */
export type PaginaDeLaCuenta = 'perfil' | 'contrasena';

/** Lo que se le anade al emisor para llegar a cada una. Ver el javadoc de arriba. */
const RUTA_DE_LA_CUENTA: Readonly<Record<PaginaDeLaCuenta, string>> = {
  // Con barra final: es la ruta indice de la consola, y la misma que el servidor le pasa como
  // `baseUrl`. Sin ella el camino que el enrutador compara no es el que le dijeron que era.
  perfil: 'account/',
  contrasena: 'account/account-security/signing-in',
};

/** A donde lleva cada opcion del menu de sesion. Se exporta para poder comprobarla. */
export function urlDeLaCuenta(pagina: PaginaDeLaCuenta): string {
  return `${realm()}/${RUTA_DE_LA_CUENTA[pagina]}`;
}

/**
 * Abre la pagina de la cuenta **en otra pestana**, y si no se puede, va en esta.
 *
 * <h2>Por que otra pestana</h2>
 *
 * Porque el token vive EN MEMORIA —es la decision de la cabecera de este archivo— y se muere con
 * el documento. Irse a Keycloak en esta misma pestana tiraria la sesion de trabajo: al volver,
 * el arranque tendria que rebotar otra vez por la puerta. Con una pestana nueva, quien mira el
 * perfil vuelve a Rentas y sigue donde estaba.
 *
 * <h2>Por que se mira lo que devuelve, y por que NO lleva «noopener» en las opciones</h2>
 *
 * Porque el sintoma que este issue viene a quitar es **que no pase nada**. Un bloqueador de
 * ventanas emergentes puede negar la pestana, y entonces `window.open` devuelve `null`: sin mirarlo,
 * el boton volveria a ser el `al: () => {}` de antes, esta vez sin que se vea en el codigo. Con el
 * `null` mirado, el peor caso es irse en esta pestana, que es feo y es visible.
 *
 * Y por eso mismo `noopener` **no** puede ir en la cadena de opciones: HTML manda devolver `null`
 * cuando se pide, asi que la comprobacion de arriba daria siempre positivo y la pestana nueva no
 * se usaria nunca. Se consigue lo mismo soltando el `opener` despues.
 */
export function abrirLaCuenta(pagina: PaginaDeLaCuenta): void {
  const url = urlDeLaCuenta(pagina);
  const otra = window.open(url, '_blank');
  if (otra === null) {
    window.location.assign(url);
    return;
  }
  // La pestana nueva no necesita poder tocar esta. Ver el javadoc: aqui y no en las opciones.
  otra.opener = null;
}

function motivoDelEmisor(error: string): string {
  switch (error) {
    case 'access_denied':
      return 'No se completo la entrada';
    case 'invalid_scope':
      return 'El alcance que se pide no existe en el emisor';
    case 'unauthorized_client':
    case 'invalid_client':
      return 'El emisor no reconoce a este cliente';
    case 'temporarily_unavailable':
    case 'server_error':
      return 'El emisor tuvo un problema';
    default:
      return 'El emisor no dejo entrar';
  }
}

/**
 * Siempre la raiz DE LA APLICACION, aunque se entrara por una ruta profunda.
 *
 * Es una sola URI de retorno que declarar en el cliente, y el destino viaja aparte en
 * `sessionStorage`. Declarar una por pantalla seria una lista que hay que ampliar cada vez que
 * nace una seccion, y el sintoma de olvidarse es «Invalid parameter: redirect_uri».
 *
 * **La raiz de la aplicacion no es la del sitio, y confundirlas costo el acceso a `prod`.**
 * Esto devolvia `origin + '/'`, que es correcto para una aplicacion servida en la raiz; esta
 * se sirve bajo `/rentas/` (`vite.config.ts`, `base`), porque ADR-0030 §2 pone el sistema
 * delante de la ruta y el mismo Traefik sirve las cuatro interfaces. Medido el 2026-09-12:
 * quien se autenticaba volvia a `https://<dominio>/` y recibia un **404**, con el `code` y el
 * `iss` correctos — o sea que la autenticacion funcionaba y el retorno no.
 *
 * `BASE_URL` es de donde ya salen los activos del paquete, asi que no hay un segundo sitio
 * que mantener: si la base cambia, esto la sigue.
 */
function retorno(): string {
  return window.location.origin + import.meta.env.BASE_URL;
}

function aleatorio(largo: number): string {
  const bytes = new Uint8Array(largo);
  crypto.getRandomValues(bytes);
  return base64url(bytes);
}

/** El reto S256: `BASE64URL(SHA256(ASCII(verificador)))`, tal cual lo pide RFC 7636 §4.2. */
async function reto(verificador: string): Promise<string> {
  const resumen = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verificador));
  return base64url(new Uint8Array(resumen));
}

function base64url(bytes: Uint8Array): string {
  let texto = '';
  bytes.forEach((b) => (texto += String.fromCharCode(b)));
  return btoa(texto).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

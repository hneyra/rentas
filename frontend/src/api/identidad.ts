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
 * Aqui esta medido que si esta: `sgtm-backoffice` declara `http://localhost:5173/*`, que es donde
 * sirve `yarn dev`. Y `crypto.subtle` existe: `http://localhost` es un origen seguro para el
 * navegador, asi que S256 se puede calcular. Saltarse la puerta aqui seria dejar el unico camino
 * que este issue viene a abrir sin recorrer ni una vez.
 */

/** El realm. Se configura por ambiente: el emisor no es el mismo en el cluster que aqui. */
const REALM = import.meta.env.VITE_KAMAYUK_OIDC_REALM ?? 'http://localhost:8181/realms/sgtm';

/** El cliente publico de la SPA. Sin secreto: un secreto en un bundle no es un secreto. */
const CLIENTE = import.meta.env.VITE_KAMAYUK_OIDC_CLIENTE ?? 'sgtm-backoffice';

const ALCANCE = import.meta.env.VITE_KAMAYUK_OIDC_ALCANCE ?? 'openid profile';

const AUTORIZACION = `${REALM}/protocol/openid-connect/auth`;
const CANJE = `${REALM}/protocol/openid-connect/token`;
const FIN = `${REALM}/protocol/openid-connect/logout`;

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
 * Existe para las pruebas y para pegar un token de `sgtm-verificacion` en desarrollo sin montar
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

/** Manda al formulario de Keycloak, guardando a donde habia que volver. */
export async function entrar(): Promise<void> {
  const verificador = aleatorio(64);
  const estado = aleatorio(24);
  sessionStorage.setItem(VERIFICADOR, verificador);
  sessionStorage.setItem(ESTADO, estado);
  sessionStorage.setItem(DESTINO, window.location.hash || '#panel');
  sessionStorage.setItem(IDAS, String(idas() + 1));
  sessionStorage.removeItem(SALIDA);

  const parametros = new URLSearchParams({
    response_type: 'code',
    client_id: CLIENTE,
    redirect_uri: retorno(),
    scope: ALCANCE,
    state: estado,
    code_challenge: await reto(verificador),
    code_challenge_method: 'S256',
  });
  window.location.assign(`${AUTORIZACION}?${parametros.toString()}`);
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
    respuesta = await fetch(CANJE, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'authorization_code',
        client_id: CLIENTE,
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
  window.location.assign(`${FIN}?${parametros.toString()}`);
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
 * Siempre la raiz, aunque se entrara por una ruta profunda.
 *
 * Es una sola URI de retorno que declarar en el cliente, y el destino viaja aparte en
 * `sessionStorage`. Declarar una por pantalla seria una lista que hay que ampliar cada vez que
 * nace una seccion, y el sintoma de olvidarse es «Invalid parameter: redirect_uri».
 */
function retorno(): string {
  return window.location.origin + '/';
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

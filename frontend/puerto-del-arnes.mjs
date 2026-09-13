import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { readFileSync, readlinkSync } from 'node:fs';
import { request } from 'node:http';
import { createServer } from 'node:net';
import { dirname, join } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

/**
 * **El puerto del arnes sale del ARBOL, no de una constante** (#148).
 *
 * <h2>Lo que pasaba, y esta medido</h2>
 *
 * `playwright.config.ts` fijaba el 4173 en tres sitios —`baseURL`, `webServer.url` y el
 * `--port`—. Con seis worktrees del repositorio trabajando a la vez, el arnes de una rama
 * mide el bundle de otra. Ocurrio al cerrar #140: la corrida midio el `dist/` del arbol
 * vecino y dio `Received: 0`, un rojo que no dice nada del puerto y se parece a un defecto
 * propio. Mientras se escribia esto, `ss -ltnp` decia:
 *
 *     LISTEN [::1]:4173  users:(("node",pid=889605))
 *       → /home/jorge/ws/rentas-143/frontend  vite preview --port 4173 --strictPort
 *
 * O sea: el 4173 no estaba libre para nadie mas, y el arnes de este arbol habria medido
 * aquel bundle.
 *
 * <h2>Por que el mecanismo es peor que una molestia</h2>
 *
 * Playwright comprueba el puerto UNA vez, antes de lanzar el comando
 * (`_startProcess` → `isAlreadyAvailable`), y despues **carrera** el fin del proceso contra
 * la disponibilidad de la URL (`_waitForProcess` → `Promise.race`). Entre las dos cosas hay
 * un `yarn build` entero. Si el servidor ajeno aparece ahi dentro, el `vite preview` de esta
 * rama muere por `--strictPort`... y la URL contesta igual, porque contesta el otro. La
 * corrida sigue, y mide el bundle equivocado. Verde, midiendo otra cosa.
 *
 * <h2>Las dos formas que se midieron</h2>
 *
 * 1. **Un puerto libre de verdad** (`listen(0)` y quedarse con el que de el kernel). Diez
 *    sondeos dieron diez puertos distintos —37645 38517 43127 43751 46673 46399 44249 40841
 *    34155 36687—, o sea que **no se repite entre corridas**: un `vite preview` que quede
 *    vivo de una corrida interrumpida no vuelve a estorbar a nadie, y por eso nunca se ve.
 *    En esta maquina habia **tres** colgados —4111, 4173 y 4199—, y uno de ellos desde un
 *    worktree que ya no existe (`/home/jorge/ws/rentas-111/frontend (deleted)`). Ademas el
 *    kernel los da de su rango efimero —32768-60999, medido en
 *    `/proc/sys/net/ipv4/ip_local_port_range`—, que es de donde salen tambien los puertos de
 *    origen de las conexiones salientes.
 * 2. **Uno derivado de la ruta del arbol**, que es lo que hay aqui. Las ocho rutas de esta
 *    maquina —los seis worktrees, el gemelo de /tmp y la de CI— dieron ocho puertos
 *    distintos, y el mismo arbol da el mismo puerto siempre. Eso es lo que compra lo que la
 *    otra forma no puede: **el puerto se puede nombrar antes de correr**, la corrida de ayer
 *    y la de hoy chocan con el mismo obstaculo, y un `preview` colgado de ESTE arbol lo caza
 *    la comprobacion de abajo en vez de acumularse en silencio.
 *
 * Lo que NO se midio y por tanto no se afirma: que una conexion saliente robe el puerto
 * sondeado. Se intentaron 25 338 conexiones en 20 s contra un puerto recien liberado y no
 * ocurrio ni una vez.
 *
 * <h2>`--strictPort` se queda</h2>
 *
 * Sin el, Vite se mueve de puerto en silencio y el `baseURL` se queda donde estaba — que es
 * exactamente «medir el servidor del otro». El problema nunca fue la rigidez: era que el
 * puerto fuese fijo.
 */

/** Este directorio: la raiz del frontend. El arbol al que pertenece este arnes. */
const RAIZ = dirname(fileURLToPath(import.meta.url));

/**
 * El puerto de siempre, y el que se usa **en CI**.
 *
 * Ahi hay un solo arbol y ningun vecino, asi que el comportamiento no cambia: mismo puerto
 * que antes de #148, y una variable menos de la que dudar cuando un arnes falle en la nube.
 */
const PUERTO_DE_CI = 4173;

/**
 * La banda de la que sale el puerto de un arbol de trabajo: **entre los dos puertos de Vite**.
 *
 * Empieza en el siguiente al de `vite preview` (4173) y termina en el anterior al de
 * `vite dev` (5173), asi que ni el arnes de CI ni un `yarn dev` abierto caen nunca dentro.
 * Son 999 puertos: con los seis arboles de esta maquina, la probabilidad de que dos caigan
 * en el mismo es del 1,5 %, y si cae, lo dice `comprobarQueElPuertoEstaLibre`.
 */
const PRIMERO = 4174;
const ULTIMO = 5172;

/** La variable con que se pide otro puerto a mano, cuando el derivado esta ocupado por algo fijo. */
export const VARIABLE = 'KAMAYUK_E2E_PUERTO';

/**
 * El puerto de un arbol, derivado de su ruta absoluta.
 *
 * SHA-256 y no una suma casera: `rentas-136` y `rentas-137` se diferencian en un caracter, y
 * un hash debil los manda al mismo sitio. Medido con las rutas de verdad: ocho arboles, ocho
 * puertos.
 *
 * @param {string} raiz Ruta absoluta del arbol.
 * @returns {number}
 */
export function puertoDerivadoDe(raiz) {
  const revuelto = createHash('sha256').update(raiz).digest().readUInt32BE(0);
  return PRIMERO + (revuelto % (ULTIMO - PRIMERO + 1));
}

/**
 * De donde sale el puerto de esta corrida. Puro: la prueba lo llama con entornos de mentira.
 *
 * @param {{ raiz: string, ci: boolean, pedido?: string | undefined }} entorno
 * @returns {{ puerto: number, motivo: string }}
 */
export function elegirElPuerto({ raiz, ci, pedido }) {
  if (pedido !== undefined && pedido !== '') {
    const puerto = Number(pedido);
    if (!Number.isInteger(puerto) || puerto < 1 || puerto > 65535)
      throw new Error(`«${VARIABLE}=${pedido}» no es un puerto: tiene que ser un entero de 1 a 65535.`);
    return { puerto, motivo: `pedido por ${VARIABLE}` };
  }
  // En CI hay un solo arbol: el de siempre, para que un arnes que falle alli no obligue a
  // averiguar antes en que puerto corrio.
  if (ci) return { puerto: PUERTO_DE_CI, motivo: 'el de siempre, porque esto es CI' };
  return { puerto: puertoDerivadoDe(raiz), motivo: `derivado de «${raiz}»` };
}

const ELEGIDO = elegirElPuerto({
  raiz: RAIZ,
  ci: process.env.CI !== undefined,
  pedido: process.env[VARIABLE],
});

/** El puerto en que sirve —y solo en el que sirve— el arnes de ESTE arbol. */
export const PUERTO = ELEGIDO.puerto;

/** Como se eligio, en una frase, para poder decirlo en los mensajes. */
export const MOTIVO = ELEGIDO.motivo;

/**
 * La raiz de la aplicacion servida.
 *
 * `/rentas/` es la `base` de `vite.config.ts`: ADR-0030 §2 pone el sistema delante de la ruta.
 * Con otra, `vite preview` contesta 404 en la raiz y el arnes esperaria 120 s a un servidor
 * que si esta.
 */
export const URL_DEL_ARNES = `http://localhost:${PUERTO}/rentas/`;

/** Las dos caras de «localhost». Vite escucha en `::1`; un servidor ajeno puede estar en cualquiera. */
const LOOPBACK = ['127.0.0.1', '::1'];

/**
 * Si el puerto esta ocupado en alguna de las dos caras de `localhost`.
 *
 * Se intenta ABRIR y no conectar, a proposito: conectar solo ve servidores que aceptan, y lo
 * que le importa al arnes es si `vite preview` va a poder escuchar. Medido: `vite preview`
 * abre en `[::1]` y no en `127.0.0.1`, asi que mirar una sola cara dice «libre» de un puerto
 * que no lo esta.
 *
 * @param {number} puerto
 * @returns {Promise<boolean>}
 */
export async function estaOcupado(puerto) {
  for (const host of LOOPBACK) {
    const ocupado = await new Promise((resolver) => {
      const servidor = createServer();
      servidor.once('error', (/** @type {NodeJS.ErrnoException} */ error) => {
        // `EADDRNOTAVAIL`: esta maquina no tiene esa cara de localhost. No es ocupacion.
        resolver(error.code === 'EADDRINUSE');
      });
      servidor.listen(puerto, host, () => servidor.close(() => resolver(false)));
    });
    if (ocupado) return true;
  }
  return false;
}

/**
 * Quien tiene el puerto, con su nombre y su directorio si se puede saber.
 *
 * Es lo que convierte «el 4173 esta ocupado» en algo accionable: **dice si lo tiene el arnes
 * de otro arbol o cualquier otra cosa**, que es la pregunta que uno se hace al leerlo. La
 * derivacion ya separa los arboles, asi que un puerto ocupado casi siempre es (a) un
 * `vite preview` propio que quedo vivo —se mata— o (b) un servicio ajeno —se pide otro
 * puerto—; y los dos se distinguen leyendo el `cmdline` del proceso.
 *
 * Mejor esfuerzo: si no hay `ss`, ni `lsof`, ni `/proc`, se devuelve `null` y el mensaje
 * sigue nombrando el puerto, que es lo que no puede faltar.
 *
 * @param {number} puerto
 * @returns {{ pid: number, descripcion: string } | null}
 */
export function quienLoTiene(puerto) {
  const pid = pidDelQueEscucha(puerto);
  if (pid === null) return null;
  const orden = leerDeProc(pid, 'cmdline')?.replaceAll('\0', ' ').trim();
  const donde = leerDeProc(pid, 'cwd');
  const partes = [`pid ${pid}`];
  if (orden !== undefined && orden !== '') partes.push(orden);
  if (donde !== undefined) partes.push(`desde «${donde}»`);
  return { pid, descripcion: partes.join('\n           ') };
}

/** Lo que se lee cuando no hay forma de saber quien escucha. */
const NADIE = 'otro proceso (ni «ss» ni «lsof» dijeron cual)';

/**
 * El pid del que escucha en un puerto, por `ss` y si no por `lsof`.
 *
 * @param {number} puerto
 * @returns {number | null}
 */
function pidDelQueEscucha(puerto) {
  const ss = spawnSync('ss', ['-ltnp'], { encoding: 'utf8' });
  if (ss.status === 0) {
    // La cuarta columna es la direccion local: `[::1]:4173` o `127.0.0.1:4173`. Se compara el
    // final y no la linea entera, que tambien lleva el puerto del par y el pid.
    const fila = ss.stdout
      .split('\n')
      .find((linea) => linea.split(/\s+/)[3]?.endsWith(`:${puerto}`) === true);
    const pid = fila?.match(/pid=(\d+)/)?.[1];
    if (pid !== undefined) return Number(pid);
  }
  const lsof = spawnSync('lsof', ['-nP', '-t', `-iTCP:${puerto}`, '-sTCP:LISTEN'], {
    encoding: 'utf8',
  });
  const primero = lsof.stdout?.trim().split('\n')[0];
  return primero !== undefined && /^\d+$/.test(primero) ? Number(primero) : null;
}

/**
 * Lo que `/proc` sabe de un proceso, o nada. En Linux; en otro sistema devuelve `undefined` y
 * el mensaje se queda con el pid, que ya es accionable.
 *
 * @param {number} pid
 * @param {'cmdline' | 'cwd'} que
 * @returns {string | undefined}
 */
function leerDeProc(pid, que) {
  try {
    // `cwd` es un enlace simbolico: se lee con `readlinkSync`, no con `readFileSync`.
    return que === 'cwd'
      ? readlinkSync(`/proc/${pid}/cwd`)
      : readFileSync(`/proc/${pid}/cmdline`, 'utf8');
  } catch {
    return undefined;
  }
}

/**
 * **La comprobacion que corre ANTES de construir** (AC2 de #148).
 *
 * Si el puerto esta ocupado, esto falla nombrandolo. Lo que se evita no es el fallo —el
 * `--strictPort` tambien falla— sino el fallo MUDO: sin esto, la corrida se pasa dos minutos
 * construyendo, el `preview` muere, la URL la contesta el intruso y los caminos miden lo que
 * ese intruso sirva.
 *
 * @returns {Promise<void>}
 */
export async function comprobarQueElPuertoEstaLibre() {
  if (!(await estaOcupado(PUERTO))) return;
  const dueno = quienLoTiene(PUERTO);
  throw new Error(
    `El puerto ${PUERTO} ya esta ocupado, y el arnes no puede medir su propio bundle en el.\n\n` +
      `  puerto   ${PUERTO}, ${MOTIVO}\n` +
      `  lo tiene ${dueno?.descripcion ?? NADIE}\n\n` +
      'El arnes NO se mueve de puerto: con `--strictPort` y un `baseURL` fijo, servir en otro\n' +
      'seria medir el bundle de quien tenga este (#148). Asi que: o se libera el puerto\n' +
      '—si es un `vite preview` que quedo vivo de una corrida anterior, se mata—\n' +
      (dueno === null ? '' : `\n    kill ${dueno.pid}\n`) +
      '\n...o se dice cual usar:\n\n' +
      `    ${VARIABLE}=${PUERTO === ULTIMO ? PRIMERO : PUERTO + 1} yarn e2e\n`,
  );
}

/**
 * **Y la que corre DESPUES de levantar: que lo servido sea lo que este arbol acaba de construir.**
 *
 * Es la otra mitad, y la que de verdad asusta: un puerto libre al empezar puede dejar de
 * serlo durante el `yarn build`, y entonces Playwright ve la URL contestar —la contesta el
 * intruso— y sigue adelante. Aqui se compara byte a byte el `index.html` servido con el
 * `dist/index.html` recien construido. Si no son el mismo, no hay corrida: los nombres de los
 * ficheros de `assets/` llevan el hash del contenido, asi que dos ramas distintas no pueden
 * dar el mismo `index.html`... y si lo dan, es que el bundle es identico y medirlo da igual.
 *
 * @returns {Promise<void>}
 */
export async function comprobarQueElBundleServidoEsElMio() {
  const construido = readFileSync(join(RAIZ, 'dist', 'index.html'), 'utf8');
  const servido = await pedirElIndice();
  if (servido === construido) return;
  throw new Error(
    `Lo que se sirve en ${URL_DEL_ARNES} NO es el bundle de este arbol.\n\n` +
      `  puerto   ${PUERTO}, ${MOTIVO}\n` +
      `  servido  ${huella(servido)}\n` +
      `  propio   ${huella(construido)} (${join(RAIZ, 'dist', 'index.html')})\n` +
      `  lo tiene ${quienLoTiene(PUERTO)?.descripcion ?? NADIE}\n\n` +
      'Alguien ocupo el puerto mientras este arbol construia. Medir eso es lo que #148 cierra:\n' +
      'un arnes en verde sobre el bundle de otra rama es peor que uno en rojo.\n',
  );
}

/** Las ocho primeras del SHA-256, que es todo lo que hace falta para decir «no es el mismo». */
function huella(/** @type {string} */ texto) {
  return `sha256:${createHash('sha256').update(texto).digest('hex').slice(0, 8)}`;
}

/**
 * El `index.html` que sirve el servidor, sin comprimir.
 *
 * `identity`: `vite preview` comprime por omision, y un cuerpo en gzip nunca seria igual al
 * fichero del disco. Por `node:http` y no por `fetch`, que fuera de `src/api/` esta prohibido
 * y con razon (ADR-0030 §3).
 *
 * @returns {Promise<string>}
 */
function pedirElIndice() {
  return new Promise((resolver, rechazar) => {
    const peticion = request(
      URL_DEL_ARNES,
      { headers: { 'accept-encoding': 'identity' } },
      (respuesta) => {
        if (respuesta.statusCode !== 200) {
          respuesta.resume();
          rechazar(new Error(`${URL_DEL_ARNES} contesto ${respuesta.statusCode}, no 200.`));
          return;
        }
        let cuerpo = '';
        respuesta.setEncoding('utf8');
        respuesta.on('data', (trozo) => (cuerpo += trozo));
        respuesta.on('end', () => resolver(cuerpo));
      },
    );
    peticion.on('error', rechazar);
    peticion.end();
  });
}

/**
 * Como CLI: la comprobacion previa, delante del `yarn build` de `webServer.command`.
 *
 * El puerto se anuncia SIEMPRE, y por `stderr` porque es el unico canal que Playwright
 * muestra de un `webServer` (su `stdout` va a `ignore` por omision). Es una linea, y es la
 * que faltaba en #140: sin ella, una corrida no deja escrito en que puerto midio.
 */
if (process.argv[1] === fileURLToPath(import.meta.url)) {
  process.stderr.write(`El arnes de «${RAIZ}» sirve en ${URL_DEL_ARNES} — ${MOTIVO}.\n`);
  try {
    await comprobarQueElPuertoEstaLibre();
  } catch (error) {
    process.stderr.write(`\n${error instanceof Error ? error.message : String(error)}\n`);
    process.exit(1);
  }
}

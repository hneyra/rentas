import { existsSync, readFileSync } from 'node:fs';
import { dirname, isAbsolute, join, resolve } from 'node:path';

/**
 * La comprobacion del enlace con el clon hermano, como funcion pura sobre una raiz.
 *
 * Vive aparte de su prueba para que la prueba pueda ejercerla sobre una raiz INVENTADA —una
 * donde el hermano no esta— y demostrar que muerde sin tocar el disco de verdad. Es el
 * equivalente de una `muestra/` para una guarda que no mira codigo sino disposicion.
 */

/** El prefijo con el que yarn declara una dependencia enlazada a un directorio. */
const ENLACE = 'link:';

export interface Enlace {
  /** El nombre del paquete, tal como lo escribe quien lo importa: `@kamayuk/formato`. */
  readonly paquete: string;
  /** La ruta relativa declarada en el `package.json`. */
  readonly declarada: string;
}

export interface Problema {
  readonly paquete: string;
  readonly que: string;
  readonly remedio: string;
}

/** Los `link:` que declara un `package.json` ya leido. */
export function enlacesDeclarados(contenido: string): Enlace[] {
  const manifiesto = JSON.parse(contenido) as {
    dependencies?: Record<string, string>;
    devDependencies?: Record<string, string>;
  };
  const todas = { ...manifiesto.devDependencies, ...manifiesto.dependencies };
  return Object.entries(todas)
    .filter(([, version]) => version.startsWith(ENLACE))
    .map(([paquete, version]) => ({ paquete, declarada: version.slice(ENLACE.length) }));
}

/**
 * De un `link:` a la raiz del clon hermano que da por puesta.
 *
 * `../../kamayuk-lib/paquetes/formato` -> `../../kamayuk-lib`. Se deriva de la ruta en vez de
 * escribirse: un mensaje escrito a mano nombra el repositorio de ayer.
 */
function raizDelClon(declarada: string): string | null {
  const partes = declarada.split('/');
  const hasta = partes.findIndex((parte) => parte !== '..' && parte !== '.');
  return hasta === -1 ? null : partes.slice(0, hasta + 1).join('/');
}

/** Y de ahi, el nombre del repositorio: `../../kamayuk-lib` -> `kamayuk-lib`. */
function clonDe(declarada: string): string | null {
  const raiz = raizDelClon(declarada);
  return raiz === null ? null : (raiz.split('/').at(-1) ?? null);
}

/** Donde tiene que estar en el disco lo que un `link:` declara, ya resuelto. */
export function destinoDelEnlace(declarada: string, raizDelFrontend: string): string {
  return isAbsolute(declarada) ? declarada : resolve(raizDelFrontend, declarada);
}

/**
 * Que hacer cuando un `link:` no esta puesto, nombrando el `git clone` que lo haria existir.
 *
 * Se separo de `problemasDelEnlace` porque hace falta en dos sitios y el segundo no mira el
 * disco: `resolucion.ts` lo necesita cuando `require.resolve` revienta, que es DOS pasos antes
 * de que nadie llegue a preguntar por este directorio (#113).
 */
export function remedioDelEnlace(paquete: string, declarada: string): string {
  const clon = clonDe(declarada);
  return clon === null
    ? `Revisa la ruta declarada para «${paquete}».`
    : `Este frontend NO funciona sin «${clon}» clonado al lado de «rentas»:\n` +
        `    git clone https://github.com/hneyra/${clon} ${raizDelClon(declarada) ?? ''}\n` +
        '  Y no basta con que yarn haya salido en verde: un `link:` a un directorio que no ' +
        'existe se instala con codigo 0 y sin avisar.';
}

/** Lo poco que hace falta de `require` para resolver un enlace. Un objeto, y por tanto fingible. */
export interface Requeridor {
  resolve(peticion: string): string;
}

/**
 * **Lo que un paquete enlazado pide por `peerDependencies`, o un rojo que dice donde mirar** (#113).
 *
 * <h2>El defecto que esto cierra</h2>
 *
 * `resolucion.ts` termina con un `throw` que nombra el `git clone` exacto... y **no llegaba a
 * salir**. Con el clon hermano ausente, `require.resolve('@kamayuk/api')` revienta doce lineas
 * antes, en la primera vuelta del bucle, y lo que se lee es:
 *
 *     failed to load config from …/frontend/vite.config.ts
 *     Error: Cannot find module '@kamayuk/api'
 *     Require stack:
 *     - …/frontend/resolucion.ts
 *
 * Un mensaje sobre un modulo, dentro de un archivo de configuracion de Vite, para quien acaba de
 * clonar el repositorio y no sabe que hay un hermano. **Y es el caso mas comun**, porque
 * `yarn install` con el hermano ausente sale con codigo 0 y no enlaza nada: el primer sintoma
 * aparece dos pasos despues y no se parece a su causa.
 *
 * `enlace-con-kamayuk-lib.test.ts` si lo dice — pero vive en `yarn verificar`, que es el paso
 * SIGUIENTE. Quien clona y arranca `yarn dev` no pasa por el.
 *
 * <h2>Por que el paquete se nombra, y no «uno de ellos»</h2>
 *
 * Son cinco enlaces. Un rojo que dijera «algun `@kamayuk/*` no resolvio» obliga a probarlos a
 * mano, y los dos remedios son distintos: si el destino no esta, falta el `git clone`; si esta,
 * lo que falta es el `yarn install` que escribe el symlink.
 */
export function loQuePideElEnlace(
  requerir: Requeridor,
  enlace: Enlace,
  raizDelFrontend: string,
): Record<string, string> {
  let entrada: string;
  try {
    entrada = requerir.resolve(enlace.paquete);
  } catch (causa) {
    throw new Error(porQueNoResuelve(enlace, raizDelFrontend), { cause: causa });
  }
  const manifiesto = JSON.parse(readFileSync(join(dirname(entrada), 'package.json'), 'utf8')) as {
    peerDependencies?: Record<string, string>;
  };
  return manifiesto.peerDependencies ?? {};
}

/** El rojo de arriba, entero: que paquete, donde se le espera y que comando lo pone ahi. */
function porQueNoResuelve(enlace: Enlace, raizDelFrontend: string): string {
  const { paquete, declarada } = enlace;
  const destino = destinoDelEnlace(declarada, raizDelFrontend);
  const raiz = raizDelClon(declarada);
  const donde =
    raiz === null
      ? `  Se espera en:\n    ${destino}\n`
      : `  Se espera el clon hermano en:\n    ${destinoDelEnlace(raiz, raizDelFrontend)}\n` +
        `  y «${paquete}» dentro de el, en:\n    ${destino}\n`;
  // Si el destino ESTA, el `git clone` seria un consejo falso: lo que falta es el enlace.
  const remedio = existsSync(destino)
    ? 'El destino existe, o sea que el clon hermano esta y lo que falta es el enlace:\n' +
      '    yarn install\n' +
      '  (en `frontend/`; `node_modules/' +
      paquete +
      '` no esta puesto).'
    : remedioDelEnlace(paquete, declarada);
  return (
    `No se pudo resolver «${paquete}», declarado «link:${declarada}» en el package.json de este frontend.\n` +
    donde +
    `  ${remedio}`
  );
}

/**
 * Que le pasa a cada `link:` de este `package.json`.
 *
 * <h2>Por que hace falta, medido</h2>
 *
 * **`yarn install --frozen-lockfile` con el clon hermano AUSENTE sale con codigo 0** y deja el
 * enlace colgando —o no lo crea— sin decir una palabra. Medido con yarn 1.22.22, y `--check-files`
 * tampoco lo caza:
 *
 *     $ yarn install --frozen-lockfile
 *     success Already up-to-date.
 *     Done in 0.17s.                      <- rc=0
 *     $ ls node_modules/@kamayuk/
 *                                          <- vacio
 *
 * El rojo llega dos o tres pasos mas tarde, y **no nombra lo que pasa**: `tsc` dice
 * «TS2307: Cannot find module '@kamayuk/formato'» y Vite, «Rollup failed to resolve import». Con
 * eso delante, quien lo lea busca el defecto en el codigo de este repositorio.
 *
 * Es exactamente de lo que el backend se defiende desde `settings.gradle.kts`, que se para ANTES
 * y dice el `git clone` que falta. El frontend no tenia ese equivalente hasta aqui.
 */
export function problemasDelEnlace(raizDelFrontend: string): Problema[] {
  const manifiesto = join(raizDelFrontend, 'package.json');
  if (!existsSync(manifiesto)) {
    return [
      {
        paquete: '(ninguno)',
        que: `No hay package.json en «${raizDelFrontend}».`,
        remedio: 'Se esperaba la raiz del frontend.',
      },
    ];
  }

  const salida: Problema[] = [];
  for (const { paquete, declarada } of enlacesDeclarados(readFileSync(manifiesto, 'utf8'))) {
    const destino = destinoDelEnlace(declarada, raizDelFrontend);
    const remedio = remedioDelEnlace(paquete, declarada);

    if (!existsSync(destino)) {
      salida.push({ paquete, que: `«${declarada}» no existe (${destino}).`, remedio });
      continue;
    }

    const suyo = join(destino, 'package.json');
    if (!existsSync(suyo)) {
      salida.push({
        paquete,
        que: `«${declarada}» existe pero no es un paquete: no tiene package.json.`,
        remedio,
      });
      continue;
    }

    const nombre = (JSON.parse(readFileSync(suyo, 'utf8')) as { name?: string }).name;
    if (nombre !== paquete) {
      // El enlace apunta a un paquete, pero no al que se declaro. Pasa al mover un directorio
      // dentro del hermano: el `link:` sigue resolviendo y se importa otra cosa.
      salida.push({
        paquete,
        que: `«${declarada}» es «${nombre ?? 'sin nombre'}», no «${paquete}».`,
        remedio: 'La ruta del `link:` y el paquete que hay al final tienen que ser el mismo.',
      });
    }
  }
  return salida;
}

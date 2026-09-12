import { existsSync, readFileSync } from 'node:fs';
import { isAbsolute, join, resolve } from 'node:path';

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
 * De un `link:` al `git clone` que lo haria existir.
 *
 * `../../kamayuk-lib/paquetes/formato` -> `kamayuk-lib`. Se deriva de la ruta en vez de
 * escribirse: un mensaje escrito a mano nombra el repositorio de ayer.
 */
function clonDe(declarada: string): string | null {
  const partes = declarada.split('/').filter((parte) => parte !== '..' && parte !== '.');
  return partes[0] ?? null;
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
    const destino = isAbsolute(declarada) ? declarada : resolve(raizDelFrontend, declarada);
    const clon = clonDe(declarada);
    const remedio =
      clon === null
        ? `Revisa la ruta declarada para «${paquete}».`
        : `Este frontend NO funciona sin «${clon}» clonado al lado de «rentas»:\n` +
          `    git clone https://github.com/hneyra/${clon} ../../${clon}\n` +
          '  Y no basta con que yarn haya salido en verde: un `link:` a un directorio que no ' +
          'existe se instala con codigo 0 y sin avisar.';

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

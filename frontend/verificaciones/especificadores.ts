import { readFileSync, readdirSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { type Requeridor, enlacesDeclarados, remedioDelEnlace } from './enlace.ts';

/**
 * **Alcanzar lo de `@kamayuk/*` POR SU ESPECIFICADOR, y no por su sitio en el disco** (#138).
 *
 * <h2>De que defecto viene</h2>
 *
 * `kamayuk-lib`#24 convirtio el `exports` de cada paquete en un contrato con guarda propia
 * —«lo que `exports` promete existe»—. **Nada de este lado estaba atado a ese contrato**: las tres
 * guardas que leen la hoja de la libreria la alcanzaban por `join(dirname(resolve('@kamayuk/ui')),
 * 'estilos', 'estilos.css')`, o sea por la DISPOSICION INTERNA del paquete, que es justo lo unico
 * que el paquete no promete.
 *
 * Medido quitando `"./estilos.css"` del `exports` de `@kamayuk/ui` —exactamente lo que #24 acaba de
 * hacer con `"./fuentes"`—:
 *
 *     $ npx vitest run verificaciones/ src/
 *      Tests  567 passed (567)          <- VERDE
 *     $ npx vite build
 *     [@tailwindcss/vite:generate:build] Missing "./estilos.css" specifier in "@kamayuk/ui" package
 *
 * O sea: la libreria publicaba un paquete del que este frontend **no puede importar su hoja**, y la
 * orden que se corre antes de commitear pasaba en verde.
 *
 * <h2>Por que `require.resolve` y no una ruta</h2>
 *
 * Porque `require.resolve` de un subcamino **respeta `exports`**: si la entrada no esta, revienta
 * con `ERR_PACKAGE_PATH_NOT_EXPORTED`, que es la misma puerta por la que pasa el empaquetador.
 * Una ruta al disco encuentra el archivo aunque el paquete ya no lo publique — que es el modo de
 * fallo entero de este issue.
 *
 * Y funciona con el `link:`: medido en este arbol, `resolve('@kamayuk/ui/estilos.css')` devuelve
 * `/…/kamayuk-lib/paquetes/ui/estilos/estilos.css`, o sea que sigue el enlace hasta el clon
 * hermano **despues** de haber pasado por el `exports` del paquete enlazado. Que devuelva la ruta
 * real y no la del `node_modules` da igual aqui: lo que se lee es el mismo byte. `preserveSymlinks`
 * es cosa de `tsc` —no de este resolvedor— y de `resolve.dedupe` en Vite.
 */

const requerir = createRequire(import.meta.url);

/** La raiz del frontend, dos niveles por encima de este archivo. */
const RAIZ_DEL_FRONTEND = dirname(dirname(fileURLToPath(import.meta.url)));

/** Los paquetes cuyo `exports` es un contrato vigilado de los dos lados (`kamayuk-lib`#24). */
const DE_LA_LIBRERIA = '@kamayuk/';

/**
 * **La hoja de `@kamayuk/ui`, alcanzada POR EL ESPECIFICADOR.**
 *
 * Es el mismo especificador que escribe `src/estilos.css`, y por eso esta constante y la
 * aplicacion se rompen a la vez: no hay forma de que las guardas lean una hoja que el bundle no
 * pueda importar.
 */
export const ESPECIFICADOR_DE_LA_HOJA = '@kamayuk/ui/estilos.css';

/** `@kamayuk/ui/estilos.css` -> `['@kamayuk/ui', './estilos.css']`. */
export function partirElEspecificador(especificador: string): readonly [string, string] {
  const partes = especificador.split('/');
  const paquete = especificador.startsWith('@') ? partes.slice(0, 2).join('/') : (partes[0] ?? '');
  const dentro = especificador.slice(paquete.length);
  return [paquete, dentro === '' ? '.' : `.${dentro}`];
}

/**
 * Lo que un especificador resuelve **pasando por el `exports` del paquete**, o un rojo que dice
 * cual de las dos cosas falto: el clon hermano o la entrada del contrato.
 *
 * Los dos remedios son distintos y por eso se distinguen. Sin clon, lo que falta es un `git clone`
 * —y ese mensaje ya esta escrito una vez, en `enlace.ts` (#113)—. Con el clon puesto, lo que falta
 * es una linea en el `package.json` de la libreria, y quien lo lea tiene que saber que el archivo
 * PUEDE estar ahi: no esta publicado, que no es lo mismo.
 */
export function resolverPorExports(requeridor: Requeridor, especificador: string): string {
  try {
    return requeridor.resolve(especificador);
  } catch (causa) {
    throw new Error(porQueNoResuelve(especificador, causa), { cause: causa });
  }
}

/** El codigo con que Node dice «el paquete esta, pero no publica ese subcamino». */
const NO_PUBLICADO = 'ERR_PACKAGE_PATH_NOT_EXPORTED';

function esDeNodeConCodigo(causa: unknown, codigo: string): boolean {
  return (
    typeof causa === 'object' &&
    causa !== null &&
    (causa as { code?: unknown }).code === codigo
  );
}

/** El rojo entero: que especificador, que paquete, que subcamino y que lo pone en su sitio. */
export function porQueNoResuelve(especificador: string, causa: unknown): string {
  const [paquete, dentro] = partirElEspecificador(especificador);
  if (esDeNodeConCodigo(causa, NO_PUBLICADO)) {
    return (
      `«${especificador}» no resuelve: el \`exports\` de «${paquete}» no publica «${dentro}».\n` +
      '  El archivo puede seguir estando en el disco — «no publicado» no es «no esta», y es\n' +
      '  exactamente lo que el empaquetador rechaza:\n' +
      `    Missing "${dentro}" specifier in "${paquete}" package\n` +
      `  Se arregla en «${paquete}/package.json», anadiendo la entrada a su \`exports\`. Ese lado\n` +
      '  tiene su propia guarda desde `kamayuk-lib`#24; esta es la de este lado.'
    );
  }
  return `«${especificador}» no resuelve.\n  ${remedioDeclarado(paquete)}`;
}

/** El `git clone` que pone el paquete, sacado del `link:` que este frontend declara. */
function remedioDeclarado(paquete: string): string {
  try {
    const manifiesto = readFileSync(join(RAIZ_DEL_FRONTEND, 'package.json'), 'utf8');
    const enlace = enlacesDeclarados(manifiesto).find((e) => e.paquete === paquete);
    if (enlace !== undefined) return remedioDelEnlace(paquete, enlace.declarada);
  } catch {
    // Si no se puede leer el manifiesto, el mensaje generico es mejor que una excepcion dentro
    // del constructor de otra excepcion.
  }
  return `«${paquete}» no esta instalado ni enlazado en este frontend.`;
}

/**
 * La vecina que una hoja **arrastra** con un `@import` relativo.
 *
 * Hace falta porque no todo lo que el navegador recibe tiene entrada propia en el `exports`, y a
 * proposito: `estilos/temas.css` —las seis paletas— se arrastra desde `estilos.css` en vez de
 * publicarse aparte, y el porque esta escrito en la propia hoja de la libreria
 * (`kamayuk-lib`#23): con una entrada propia, el consumidor tiene que escribir DOS `import` y
 * quien se olvide del segundo se queda sin paletas y sin que nada se lo diga.
 *
 * Asi que se alcanza **como la alcanza el empaquetador**: desde la hoja publicada, siguiendo el
 * `@import` que ella escribe. Y se exige que lo escriba: si la libreria deja de arrastrarla, lo
 * que sale no es un archivo huerfano leido en silencio sino este rojo.
 */
export function hermanaDe(hoja: string, relativo: string): string {
  const escritos = importesDe(readFileSync(hoja, 'utf8'));
  if (!escritos.includes(relativo)) {
    throw new Error(
      `«${hoja}» ya no escribe \`@import "${relativo}"\`.\n` +
        `  Lo que escribe es: ${escritos.length === 0 ? '(ningun @import)' : escritos.join(', ')}\n` +
        '  Esa hoja se alcanza SOLO porque la publicada la arrastra. Sin el `@import`, el\n' +
        '  navegador no la recibe — y leerla igual del disco seria medir un archivo que nadie sirve.',
    );
  }
  return join(dirname(hoja), relativo);
}

/** Un `@import` de un paquete, con el archivo que lo escribe y la linea donde lo escribe. */
export interface ImportDePaquete {
  readonly hoja: string;
  readonly especificador: string;
  readonly linea: number;
}

/**
 * Los especificadores que los `@import` de una hoja nombran, en orden.
 *
 * Los comentarios se quitan antes, y no es cosmetico: la hoja de `@kamayuk/ui` escribe
 * `@import '@kamayuk/ui/estilos.css'` **dentro de un comentario**, explicando lo que el consumidor
 * hace. Contarlo seria un rojo sobre una linea que nadie ejecuta.
 */
export function importesDe(css: string): string[] {
  const sinComentarios = css.replace(/\/\*[\s\S]*?\*\//g, ' ');
  return [...sinComentarios.matchAll(/@import\s+(?:url\(\s*)?['"]([^'"]+)['"]/g)].map(
    ([, nombre]) => nombre ?? '',
  );
}

/** Los de arriba que nombran un paquete de la libreria, con su linea para que el rojo la diga. */
export function importesDeLaLibreria(hoja: string, css: string): ImportDePaquete[] {
  const lineas = css.split('\n');
  return importesDe(css)
    .filter((especificador) => especificador.startsWith(DE_LA_LIBRERIA))
    .map((especificador) => ({
      hoja,
      especificador,
      linea: lineas.findIndex((l) => l.includes(especificador) && l.includes('@import')) + 1,
    }));
}

/** Todas las hojas de estilo de un arbol. */
export function hojasDe(raiz: string): string[] {
  return readdirSync(raiz, { withFileTypes: true }).flatMap((entrada) => {
    const ruta = join(raiz, entrada.name);
    if (entrada.isDirectory()) return entrada.name === 'node_modules' ? [] : hojasDe(ruta);
    return entrada.name.endsWith('.css') ? [ruta] : [];
  });
}

/** Un `@import` que el empaquetador no podria resolver, con el porque ya redactado. */
export interface ImportSinSalida {
  readonly donde: string;
  readonly especificador: string;
  readonly porque: string;
}

/**
 * Cuales de esos `@import` **no pasan por el `exports`** de su paquete.
 *
 * Funcion pura sobre un `Requeridor` y una lista, para que la prueba pueda ejercerla contra un
 * paquete INVENTADO —uno que publica una hoja y esconde la de al lado— y demostrar que muerde sin
 * tocar el clon compartido. Es el equivalente de una `muestra/` para una guarda que no mira codigo
 * sino resolucion.
 */
export function importesQueNoResuelven(
  requeridorDe: (hoja: string) => Requeridor,
  importes: readonly ImportDePaquete[],
): ImportSinSalida[] {
  const salida: ImportSinSalida[] = [];
  for (const { hoja, especificador, linea } of importes) {
    try {
      // El requeridor es el de LA HOJA que escribe el `@import`, y no uno de la raiz: es desde su
      // directorio desde donde el empaquetador busca el `node_modules`, y dos hojas de sitios
      // distintos pueden resolver el mismo nombre a paquetes distintos.
      requeridorDe(hoja).resolve(especificador);
    } catch (causa) {
      salida.push({
        donde: `${hoja}:${linea}`,
        especificador,
        porque: porQueNoResuelve(especificador, causa),
      });
    }
  }
  return salida;
}

/**
 * **La ruta a la que ese especificador resuelve.** Si no resuelve, el rojo sale aqui y no dos pasos
 * despues, en `yarn build`.
 *
 * <h2>Por que es una funcion y no un `const` de este modulo</h2>
 *
 * Las dos cosas dan rojo; solo una dice donde. Como `const`, la rotura de #138 —quitar
 * `"./estilos.css"` del `exports`— tumba **este archivo al cargarse**, y con el a todo el que lo
 * importe: vitest lo cuenta como «Failed Suites … no tests», sin un nombre de prueba delante.
 * Como funcion, cada guarda revienta donde de verdad necesita la hoja, y la que existe para
 * preguntar por el contrato —`los-import-resuelven-por-exports`— puede preguntarlo DENTRO de una
 * prueba con nombre.
 *
 * <h2>Y por que vive al final del archivo</h2>
 *
 * Por un rojo de la propia rotura. Con la llamada puesta arriba, junto a su especificador, lo que
 * salio no fue el mensaje sino:
 *
 *     ReferenceError: Cannot access 'NO_PUBLICADO' before initialization
 *      ❯ porQueNoResuelve verificaciones/especificadores.ts:103:32
 *
 * O sea: la guarda mordia y **el mensaje que explica que hacer no llegaba a escribirse** —el
 * defecto de #113 otra vez—. Definida despues de todo lo que el mensaje usa, no puede repetirse.
 */
export function hojaDeUi(): string {
  return resolverPorExports(requerir, ESPECIFICADOR_DE_LA_HOJA);
}

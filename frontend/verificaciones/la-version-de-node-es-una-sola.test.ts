// @vitest-environment node
//
// Lee el disco —`.nvmrc`, los manifiestos y los flujos de CI— y pregunta por el `process.version`
// que la esta ejecutando. No es un DOM lo que necesita.

import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * **La version de Node se dice UNA VEZ, y el que la dice manda** (#289).
 *
 * <h2>De donde viene</h2>
 *
 * Lo medido el 2026-09-20 era que **seis sitios declaraban la version y ninguno la fijaba**: el
 * puesto corria un binario suelto, `engines` decia `>=22` —un suelo abierto, no un pin—, y los
 * cinco pasos de `actions/setup-node` de los cuatro flujos escribian el literal `"22"` cada uno
 * por su cuenta. Nada ataba esos numeros entre si, y no hay gestor de versiones en el puesto que
 * los ate desde fuera.
 *
 * El modo de fallo de eso no es ruidoso. Nadie despierta un martes porque `engines` diga `>=22`:
 * lo que pasa es que un dia la CI instala una version y el puesto otra, algo se comporta distinto
 * en un sitio que en el otro, y el rato se va buscando el defecto en el codigo. **Fue exactamente
 * asi como se encontro el de #289**: subir el puesto a Node 24 dejo las 1 014 pruebas pasando y
 * `yarn verificar` en rojo por 3 960 rechazos de `undici` que ninguna prueba afirmaba —ver
 * `vitest.setup.ts`, que monta el arnes del `Request` de `@kamayuk/verificaciones`—. Con seis
 * numeros sueltos, eso se descubre en CI y no aqui.
 *
 * <h2>Que fija esto, y por que cada mitad</h2>
 *
 * 1. **`.nvmrc` existe y dice una version entera.** Es la fuente. Va en la RAIZ y no en
 *    `frontend/` porque tres de los cinco pasos no son del frontend: `infraestructura.yml`
 *    construye el descriptor y `registro.yml` y `documentacion.yml` corren los guiones de
 *    `docs/00-gobierno/`.
 * 2. **Nadie mas la escribe.** Un solo `node-version:` literal en cualquier flujo y esto sale
 *    rojo nombrandolo: es el estado del que se viene, y con la fuente puesta vuelve solo en
 *    cuanto alguien copie un paso de otro sitio.
 * 3. **Y el que la lee, la lee de verdad.** Esta es la mitad que casi se escapa, y se anota
 *    porque costo: `frontend.yml` e `infraestructura.yml` sacan este repositorio a un
 *    subdirectorio (`path: rentas`), asi que ahi el archivo es `rentas/.nvmrc` y no `.nvmrc`.
 *    Con la ruta mal puesta `setup-node` **falla**, pero lo hace hablando de un archivo que no
 *    encuentra, no de que nadie ato la version. Asi que la ruta esperada no se escribe: **se
 *    deriva del `path:` del checkout de ESTE repositorio, trabajo por trabajo**.
 * 4. **`engines` deja de ser un suelo abierto.** Que admita lo que `.nvmrc` dice no basta:
 *    `>=22` tambien lo admitia. Tiene que **rechazar** la mayor anterior, o no es un pin.
 * 5. **Y lo declarado lo admiten las herramientas**, leido de `node_modules` y no supuesto. Es
 *    lo que convierte «subimos de mayor» en una pregunta con respuesta medida.
 * 6. **Y el Node que corre ESTO es el que `.nvmrc` nombra.** Sin esta ultima, las cinco de
 *    arriba comprueban que seis papeles dicen lo mismo mientras el puesto corre otra cosa.
 *
 * <h2>Por que el rango se interpreta aqui y no con `semver`</h2>
 *
 * `semver` esta en `node_modules`, pero de acarreo: no lo declara este manifiesto. Una guarda
 * que se apoye en una dependencia transitiva se queda sin piso el dia que quien la arrastraba
 * deje de hacerlo, y el rojo hablaria de un `Cannot find module`. Anadirlo mueve el candado, que
 * en este issue es un hallazgo aparte y no entra. Asi que los rangos se leen aqui, con
 * {@link admite}, que reconoce las formas que de verdad aparecen —`^X.Y.Z`, `>=X.Y.Z` y sus
 * versiones cortas, unidas por `||`—. Y lo que no reconoce **no pasa en silencio**: sale por
 * {@link SIN_RECONOCER} y esta prueba se pone roja pidiendo que se enseñe la forma nueva.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const REPOSITORIO = join(FRONTEND, '..');

const leer = (ruta: string) => readFileSync(ruta, 'utf8');

const RUTA_DEL_NVMRC = join(REPOSITORIO, '.nvmrc');

/** Lo que `.nvmrc` dice, sin la `v` y sin espacios. `''` si el archivo no esta. */
const LA_VERSION = existsSync(RUTA_DEL_NVMRC) ? leer(RUTA_DEL_NVMRC).trim().replace(/^v/, '') : '';

/** Las formas de rango que {@link admite} no supo leer. Una sola pone roja la ultima prueba. */
const SIN_RECONOCER: string[] = [];

type Trio = readonly [number, number, number];

const aTrio = (version: string): Trio | undefined => {
  const m = /^(\d+)(?:\.(\d+))?(?:\.(\d+))?$/.exec(version.trim().replace(/^v/, ''));
  return m === null ? undefined : [Number(m[1]), Number(m[2] ?? 0), Number(m[3] ?? 0)];
};

const compara = (a: Trio, b: Trio) => a[0] - b[0] || a[1] - b[1] || a[2] - b[2];

/**
 * Si `version` cae dentro de `rango`.
 *
 * Reconoce `^X[.Y[.Z]]`, `>=X[.Y[.Z]]` y `X[.Y[.Z]]` a secas, separados por `||`. Cualquier otra
 * forma se apunta en {@link SIN_RECONOCER} y cuenta como NO admitida: es la direccion segura
 * —una forma que no se sabe leer no puede certificar nada— y ademas deja el rastro para
 * enseñarsela.
 */
function admite(rango: string, version: string): boolean {
  const v = aTrio(version);
  if (v === undefined) return false;
  return rango.split('||').some((trozo) => {
    const t = trozo.trim();
    const cursor = /^(\^|>=|)\s*v?(\d+(?:\.\d+){0,2})$/.exec(t);
    if (cursor === null) {
      SIN_RECONOCER.push(t);
      return false;
    }
    const piso = aTrio(cursor[2] as string) as Trio;
    if (compara(v, piso) < 0) return false;
    return cursor[1] === '^' ? v[0] === piso[0] : true;
  });
}

/* ────────────────────────── los flujos, trabajo por trabajo ────────────────────────── */

const FLUJOS = ['frontend', 'infraestructura', 'registro', 'documentacion', 'backend', 'publicar-imagenes'];

interface Trabajo {
  readonly flujo: string;
  readonly nombre: string;
  /** El `path:` del checkout de ESTE repositorio; `''` si se saca en la raiz del espacio. */
  readonly raiz: string;
  readonly literales: string[];
  readonly archivos: string[];
  readonly pasosDeSetupNode: number;
}

/** Los trabajos de un flujo, troceados por el texto. Sin parser de YAML: no hay ninguno declarado. */
function trabajosDe(flujo: string): Trabajo[] {
  const ruta = join(REPOSITORIO, '.github', 'workflows', `${flujo}.yml`);
  if (!existsSync(ruta)) return [];
  const texto = leer(ruta);
  const desdeJobs = texto.slice(texto.indexOf('\njobs:'));
  // Un trabajo empieza en una clave a dos espacios de sangria, que es como los escribe este arbol.
  const trozos = desdeJobs.split(/\n(?= {2}[A-Za-z_][\w-]*:\s*\n)/).slice(1);
  return trozos.map((trozo) => {
    const nombre = /^\s{2}([\w-]+):/.exec(trozo)?.[1] ?? '(sin nombre)';
    // Los pasos, para saber CUAL checkout es el de este repositorio: el que no nombra otro.
    const pasos = trozo.split(/\n(?=\s*- (?:name|uses):)/);
    const propio = pasos.find(
      (paso) => paso.includes('actions/checkout@') && !/^\s*repository:/m.test(paso),
    );
    return {
      flujo,
      nombre,
      raiz: /^\s*path:\s*(\S+)/m.exec(propio ?? '')?.[1] ?? '',
      literales: [...trozo.matchAll(/^\s*node-version:\s*(\S+)/gm)].map((m) => m[1] as string),
      archivos: [...trozo.matchAll(/^\s*node-version-file:\s*(\S+)/gm)].map((m) => m[1] as string),
      pasosDeSetupNode: pasos.filter((paso) => paso.includes('actions/setup-node@')).length,
    };
  });
}

const TRABAJOS = FLUJOS.flatMap(trabajosDe);

/* ────────────────────────────────── las herramientas ────────────────────────────────── */

const HERRAMIENTAS = [
  'vite',
  'vitest',
  'typescript',
  'eslint',
  '@playwright/test',
  '@vitejs/plugin-react',
];

/** Lo que cada herramienta instalada pide, leido de `node_modules`. `null` = no pide nada. */
const LO_QUE_PIDEN = HERRAMIENTAS.map((nombre) => {
  const ruta = join(FRONTEND, 'node_modules', nombre, 'package.json');
  const manifiesto = existsSync(ruta)
    ? (JSON.parse(leer(ruta)) as { version?: string; engines?: { node?: string } })
    : undefined;
  return { nombre, instalada: manifiesto?.version, rango: manifiesto?.engines?.node ?? null };
});

/**
 * La etiqueta de Node de la etapa `construccion` del `Dockerfile`, o `undefined` si no la hay.
 *
 * Es el SEPTIMO sitio, y el issue no lo tenia: la medida de #289 contaba seis y este no estaba.
 * Lo delata su propio comentario, que hasta ese dia decia que la mayor era «la que `frontend.yml`
 * declara (`node-version: "22"`)» — una copia que se justificaba citando otra copia.
 */
const DEL_DOCKERFILE = /^FROM\s+node:(\d+(?:\.\d+){0,2})-alpine\s+AS\s+construccion/m.exec(
  leer(join(FRONTEND, 'Dockerfile')),
)?.[1];

const ENGINES = (
  JSON.parse(leer(join(FRONTEND, 'package.json'))) as { engines?: { node?: string } }
).engines?.node;

/* ─────────────────────────────────────── lo que fija ─────────────────────────────────────── */

describe('la version de Node se dice una vez, y ahi manda', () => {
  it('EL CENTINELA: hay `.nvmrc`, hay flujos que leerlo y hay herramientas instaladas', () => {
    // Sin esto, un `.nvmrc` borrado dejaria `LA_VERSION` en `''` y las comparaciones de abajo
    // saldrian rojas hablando de rangos, no del archivo que falta. Y unos flujos que no se
    // pudieron trocear dejarian `TRABAJOS` vacio: todo lo que se afirma sobre ellos se
    // cumpliria sobre el conjunto vacio, en verde.
    expect(existsSync(RUTA_DEL_NVMRC), `No hay «.nvmrc» en la raiz: ${RUTA_DEL_NVMRC}`).toBe(true);
    expect(TRABAJOS.length, 'no se troceo ni un trabajo de los flujos: el parseo se rompio').toBeGreaterThan(5);
    expect(
      TRABAJOS.reduce((n, t) => n + t.pasosDeSetupNode, 0),
      'ningun trabajo usa `actions/setup-node`: entonces esto no vigila nada',
    ).toBeGreaterThanOrEqual(5);
    expect(
      LO_QUE_PIDEN.filter((h) => h.instalada !== undefined).length,
      'las herramientas no estan instaladas: `yarn install` primero',
    ).toBe(HERRAMIENTAS.length);
  });

  it('`.nvmrc` dice una version ENTERA, no una mayor suelta', () => {
    // Una mayor suelta —«24»— deja que CI y el puesto instalen parches distintos, que es media
    // deriva en vez de ninguna. Y `actions/setup-node` admite las dos, asi que no lo dice nadie.
    expect(
      LA_VERSION,
      `«.nvmrc» dice «${LA_VERSION}». Tiene que decir MAYOR.MENOR.PARCHE —la version que de\n` +
        '  verdad se usa— para que la CI y el puesto instalen el mismo binario.',
    ).toMatch(/^\d+\.\d+\.\d+$/);
  });

  it('ningun flujo escribe la version: los cinco pasos la LEEN', () => {
    const escritos = TRABAJOS.flatMap((t) =>
      t.literales.map((v) => `  · ${t.flujo}.yml, trabajo «${t.nombre}»: node-version: ${v}`),
    );
    expect(
      escritos,
      'Hay flujos que escriben la version de Node en vez de leerla de «.nvmrc»:\n' +
        `${escritos.join('\n')}\n\n` +
        '  Es el estado del que viene #289: seis sitios diciendola y ninguno fijandola. Se\n' +
        '  cambia por `node-version-file:`, con la ruta que le toque a ese trabajo.',
    ).toEqual([]);
  });

  it('y cada paso de `setup-node` nombra un `node-version-file`, no se queda sin ninguno', () => {
    const mudos = TRABAJOS.filter((t) => t.pasosDeSetupNode > t.archivos.length).map(
      (t) => `  · ${t.flujo}.yml, trabajo «${t.nombre}»: ${t.pasosDeSetupNode} pasos, ${t.archivos.length} lecturas`,
    );
    expect(
      mudos,
      'Un `actions/setup-node` sin `node-version-file` instala la version por omision del\n' +
        'runner, que cambia sola y sin avisar:\n' +
        `${mudos.join('\n')}`,
    ).toEqual([]);
  });

  it('y la ruta que nombra cae donde ESE trabajo saca el repositorio', () => {
    // La mitad que casi se escapa. `frontend.yml` e `infraestructura.yml` usan `path: rentas`,
    // asi que ahi el archivo es `rentas/.nvmrc`. Con `.nvmrc` a secas `setup-node` falla, pero
    // hablando de un archivo que no encuentra. La ruta esperada se DERIVA del checkout.
    const mal = TRABAJOS.flatMap((t) => {
      const esperada = t.raiz === '' ? '.nvmrc' : `${t.raiz}/.nvmrc`;
      return t.archivos
        .filter((a) => a !== esperada)
        .map(
          (a) =>
            `  · ${t.flujo}.yml, trabajo «${t.nombre}»: dice «${a}» y ahi el archivo es «${esperada}»` +
            `${t.raiz === '' ? '' : ` (el checkout usa «path: ${t.raiz}»)`}`,
        );
    });
    expect(
      mal,
      'Hay pasos que leen la version de una ruta que en ese trabajo no existe:\n' + `${mal.join('\n')}`,
    ).toEqual([]);
  });

  it('`engines.node` admite lo que `.nvmrc` dice', () => {
    expect(ENGINES, '«frontend/package.json» no declara `engines.node`').toBeDefined();
    expect(
      admite(ENGINES as string, LA_VERSION),
      `«engines.node» pide «${ENGINES}» y «.nvmrc» instala «${LA_VERSION}»: la CI instalaria una\n` +
        '  version que el propio manifiesto rechaza, y el rojo llegaria al instalar.',
    ).toBe(true);
  });

  it('y NO es un suelo abierto: rechaza la mayor anterior', () => {
    // Lo que distingue un pin de lo que habia. `>=22` tambien «admitia» 24; lo que no hacia era
    // impedir que alguien instalara con 22 y no se enterara nadie.
    const anterior = `${Number(LA_VERSION.split('.')[0]) - 1}.0.0`;
    expect(
      admite(ENGINES as string, anterior),
      `«engines.node» pide «${ENGINES}», que todavia admite Node ${anterior}.\n\n` +
        '  Eso es un SUELO, no un pin: quien instale con la mayor anterior no se entera de\n' +
        '  nada hasta que algo se comporta distinto. El criterio 2 de #289 es justo este.',
    ).toBe(false);
  });

  it('y lo que se declara lo admiten las herramientas instaladas, leido y no supuesto', () => {
    const quejas = LO_QUE_PIDEN.filter(
      (h) => h.rango !== null && !admite(h.rango, LA_VERSION),
    ).map((h) => `  · ${h.nombre}@${h.instalada} pide «${h.rango}» y «.nvmrc» dice «${LA_VERSION}»`);
    expect(
      quejas,
      'Hay herramientas que no admiten la version que este repositorio instala:\n' +
        `${quejas.join('\n')}\n\n` +
        '  Subir de mayor no se supone: se mide contra lo que cada herramienta declara en su\n' +
        '  propio manifiesto. Si una no lo admite, ese es su issue y no este.',
    ).toEqual([]);
  });

  it('y la imagen se construye con la misma mayor, que es el septimo sitio', () => {
    // El `Dockerfile` no puede LEER `.nvmrc` —un `FROM` no admite sustitucion sin un `ARG`, y
    // eso obligaria a pasarlo en los tres sitios que construyen la imagen—, asi que la copia se
    // queda y se vigila. Solo la MAYOR: la etapa `construccion` se tira y usa la etiqueta movil
    // a proposito, con su motivo escrito en el propio archivo.
    expect(DEL_DOCKERFILE, 'el `Dockerfile` no declara `FROM node:<version>-alpine AS construccion`').toBeDefined();
    expect(
      (DEL_DOCKERFILE as string).split('.')[0],
      `El «Dockerfile» construye con Node ${DEL_DOCKERFILE} y «.nvmrc» dice ${LA_VERSION}.\n\n` +
        '  Es el bundle que se PUBLICA: construirlo con otra mayor que la que verifica la CI\n' +
        '  hace que «yarn verificar en verde» deje de decir algo de la imagen.',
    ).toBe(LA_VERSION.split('.')[0]);
  });

  it('y el Node que corre ESTO es el que `.nvmrc` nombra', () => {
    // Sin esta, las de arriba comprueban que los papeles dicen lo mismo mientras el puesto corre
    // otra cosa — que es literalmente de donde viene #289. Se compara la MAYOR: el parche lo
    // fija `.nvmrc` para la CI, y exigirlo aqui pondria rojo a quien tenga uno mas nuevo.
    const mayorQueCorre = process.versions.node.split('.')[0];
    const mayorDeclarada = LA_VERSION.split('.')[0];
    expect(
      mayorQueCorre,
      `Esto corre en Node ${process.versions.node} y «.nvmrc» dice ${LA_VERSION}.\n\n` +
        `  Instala la que el repositorio fija antes de seguir; en un puesto sin gestor de\n` +
        `  versiones, el tarball oficial de nodejs.org en «~/.local» y los enlaces movidos.`,
    ).toBe(mayorDeclarada);
  });

  it('EL CIERRE: toda forma de rango que se leyo, se supo leer', () => {
    // `admite` cuenta lo que no reconoce como «no admitido», que es la direccion segura pero
    // tambien la silenciosa: una forma nueva —`~24.1`, un `<25`— saldria como un rojo que habla
    // de la herramienta en vez de del lector. Esto lo dice en su sitio.
    expect(
      [...new Set(SIN_RECONOCER)],
      'Hay formas de rango que `admite` no sabe leer, asi que lo que dicen no se comprobo:\n' +
        '  enseñaselas antes de fiarte de las pruebas de arriba.',
    ).toEqual([]);
  });
});

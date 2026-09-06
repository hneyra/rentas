// @vitest-environment node
//
// En `node` y no en jsdom, que es el entorno por omision de este proyecto: este archivo importa
// `vite.config.ts` de verdad —en vez de leerlo como texto, que es lo que permitiria que la
// configuracion dijera una cosa y la prueba comprobara otra— y eso arrastra a esbuild, que bajo
// jsdom muere con «Invariant violation: new TextEncoder().encode("") instanceof Uint8Array is
// incorrectly false». Aqui no hay DOM que necesitar: lo que se mide son archivos y objetos.
import { readFileSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

import { PROHIBICIONES } from '../eslint.prohibiciones.mjs';
import { RAIZ } from '../src/api/proxy.ts';
import { YA_SERVIDAS } from '../src/datos/servidas.ts';
import configuracion from '../vite.config.ts';

/**
 * **El camino a la API**: que exista, que sea uno solo, y que lo que se declara servido lo
 * publique el backend (I-1, AC1/AC4/AC7).
 *
 * <h2>Por que estas comprobaciones son estaticas y no de comportamiento</h2>
 *
 * Porque las tres cosas que vigilan **no producen ningun sintoma cuando se rompen**, y ese es
 * justo el patron que este repositorio persigue:
 *
 *   · sin `server.proxy`, `/rentas/api/v1/...` lo atiende el propio servidor de Vite y devuelve
 *     el `index.html` con un **200**. Un exito con HTML donde la pantalla espera JSON: no
 *     parece un error, asi que nadie lo busca;
 *   · con las tres raices desalineadas, cada mitad funciona sola y el desajuste solo aparece
 *     con las dos puestas a la vez;
 *   · una ruta declarada en `YA_SERVIDAS` que el backend no publica sale a la red y vuelve con
 *     un 404 que se confunde con los 404 de negocio — lo midio este mismo issue.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const FORMAS = join(FRONTEND, '../docs/50-api/formas-de-la-api.json');

const declaradas = Object.keys(JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>);

/** Todos los `.ts`/`.tsx` bajo `src/`, con su ruta relativa al frontend. */
function fuentes(desde = join(FRONTEND, 'src')): readonly string[] {
  return readdirSync(desde).flatMap((entrada) => {
    const ruta = join(desde, entrada);
    if (statSync(ruta).isDirectory()) return fuentes(ruta);
    return /\.tsx?$/.test(entrada) ? [relative(FRONTEND, ruta)] : [];
  });
}

const deProduccion = fuentes().filter((ruta) => !/\.test\.tsx?$/.test(ruta));

describe('AC4 — vite.config.ts declara el camino a la API', () => {
  const proxy = configuracion.server?.proxy ?? {};

  it('declara una regla para la raiz del sistema, y no para otra cosa', () => {
    // Sin esto la peticion no sale del servidor de Vite. Y el backend NO publica ninguna
    // cabecera `Access-Control-Allow-Origin` —cero `CorsConfiguration`, cero `@CrossOrigin` en
    // todo `backend/`—, asi que el mismo origen es la unica via.
    expect(Object.keys(proxy)).toEqual([RAIZ]);
  });

  it('el destino por omision es Traefik en el 8082, y sale de una variable de entorno', () => {
    const regla = proxy[RAIZ];
    expect(typeof regla === 'object' ? regla.target : regla).toBe('http://localhost:8082');
    // Que se pueda cambiar sin editar el archivo: un archivo de configuracion editado a mano
    // acaba en un commit que nadie queria.
    expect(readFileSync(join(FRONTEND, 'vite.config.ts'), 'utf8')).toContain(
      'process.env.KAMAYUK_BACKEND',
    );
  });

  it('y NO reescribe la ruta: Traefik enruta por PathPrefix(/rentas)', () => {
    const regla = proxy[RAIZ];
    // Quitarle el prefijo seria quitarle justo aquello por lo que se enruta, y el sintoma seria
    // un 404 de Traefik que parece un 404 del backend.
    expect(typeof regla === 'object' ? regla.rewrite : undefined).toBeUndefined();
  });
});

describe('AC4 — la raiz de la API es UNA, escrita en tres sitios que tienen que coincidir', () => {
  it('el prefijo del cliente, la raiz del proxy y la regla de Vite dicen lo mismo', () => {
    const cliente = readFileSync(join(FRONTEND, 'src/api/cliente.ts'), 'utf8');
    const enElCliente = /const PREFIJO = '([^']+)'/.exec(cliente)?.[1];

    expect(enElCliente).toBe(RAIZ);
    expect(Object.keys(configuracion.server?.proxy ?? {})).toContain(RAIZ);
  });

  it('ninguna fuente de produccion escribe la URL del backend: la API es del mismo origen', () => {
    // Un `http://localhost:8082` dentro de `src/` funcionaria en este puesto y en ningun otro,
    // y en el cluster pediria a un puerto que no existe — con el sintoma en el navegador de
    // quien atiende y no en ninguna prueba.
    const culpables = deProduccion.filter((ruta) =>
      /localhost:8082|127\.0\.0\.1:8082/.test(readFileSync(join(FRONTEND, ruta), 'utf8')),
    );

    expect(culpables).toEqual([]);
  });
});

describe('AC7 — lo que se declara servido tiene que publicarlo el backend', () => {
  it('las seis de seguridad estan declaradas: las dos de I-1 y las cuatro de I-3', () => {
    // La lista escrita a mano es a proposito. Derivarla de `YA_SERVIDAS` la haria pasar diga lo
    // que diga: encender una ruta es una decision, y una decision se revisa leyendo su diff.
    expect(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`)).toEqual([
      'GET /seguridad/sesion',
      'GET /seguridad/sesion/municipalidad',
      'GET /seguridad/modulos',
      'GET /seguridad/accesos',
      'GET /seguridad/sesion/permisos',
      'PUT /seguridad/sesion/ejercicio',
    ]);
  });

  it('y la escritura es UNA, que es la primera de esta interfaz', () => {
    // Las escrituras cambian datos y quedan auditadas, asi que encender una no es como
    // encender una lectura: si algun dia son cinco, esta cifra lo dice en la revision.
    expect(YA_SERVIDAS.filter((o) => o.metodo !== 'GET').map((o) => o.ruta)).toEqual([
      '/seguridad/sesion/ejercicio',
    ]);
  });

  it.each(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`))(
    'y «%s» es una operacion del contrato',
    (clave) => {
      // Esta comprobacion sustituye a la heuristica que I-1 quito del proxy: convertir en un 502
      // ruidoso cualquier 404 de una ruta declarada daba por hecho que un 404 significaba «esa
      // ruta no esta publicada», y NO lo significa — el cuarto peldano de la escalera de
      // identidad es un 404 legitimo de una ruta que si existe. Esto lo dice antes, y sin
      // necesidad de que nadie levante un backend.
      expect(declaradas).toContain(clave);
    },
  );

  it('el contrato NO publica ningun papel para la sesion, y por eso la barra no lo dibuja', () => {
    // El artboard escribe «Rentas · ventanilla» debajo del nombre. Afirmar un papel que nadie
    // concede es, en un sistema de recaudacion, la peor clase de invencion. Esta prueba caduca
    // sola el dia que alguna operacion lo publique: entonces se pone roja y dice donde mirar.
    const conPapel = declaradas.filter((clave) =>
      /"(rol|roles|perfil|papel)"/i.test(JSON.stringify((JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>)[clave])),
    );

    expect(conPapel).toEqual([]);
  });

  it('y `GET /seguridad/sesion` publica CUATRO campos, los que la barra lee', () => {
    const formas = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;

    expect(Object.keys(formas['GET /seguridad/sesion'] as object).sort()).toEqual([
      'cuenta',
      'ejercicioDeTrabajo',
      'nombre',
      'usuarioId',
    ]);
  });
});

/**
 * AC8 — las formas de las cuatro operaciones nuevas, contra el contrato y contra la captura.
 *
 * <h2>Se comparan TRES cosas y no dos, y la tercera es la que vale</h2>
 *
 * Lo que declara `docs/50-api/formas-de-la-api.json`, lo que devuelve la instalacion
 * (`seguridadMedida.ts`) y lo que esta interfaz lee. Comparar solo las dos primeras diria que el
 * generador y el servidor coinciden —que es cierto y no es el riesgo—; el riesgo es que la
 * pantalla lea `zonaCodigo` donde el contrato dice `codigo`, que es el sintoma **mudo** de C-1:
 * un campo que falta no da error, da `undefined`.
 */
describe('AC8 — el contrato, la instalacion y lo que se lee dicen lo mismo', () => {
  const formas = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, Record<string, unknown>>;

  it.each([
    ['GET /seguridad/modulos', ['activo', 'codigo', 'id', 'nombre', 'orden']],
    ['GET /seguridad/accesos', ['activo', 'codigo', 'id', 'moduloId', 'nombre', 'tipo']],
  ])('«%s» publica una pagina, y su fila tiene estos campos', (clave, campos) => {
    const forma = formas[clave] ?? {};
    const fila = (forma['contenido'] as unknown[])[0] as object;

    expect(Object.keys(fila).sort()).toEqual(campos);
    // El envoltorio de paginacion, con la advertencia que el propio AC8 hace: `totalElementos`
    // y `totalPaginas` son CUENTAS de cosas y no importes, asi que llegan como `entero` y la
    // prohibicion del importe como `number` no les aplica (el lookahead de F-4).
    expect(Object.keys(forma).sort()).toEqual([
      'contenido',
      'hayMas',
      'pagina',
      'tamano',
      'totalElementos',
      'totalPaginas',
    ]);
    expect(forma['totalElementos']).toBe('entero');
    expect(forma['totalPaginas']).toBe('entero');
  });

  it('y la instalacion devuelve EXACTAMENTE esos campos, ni uno mas ni uno menos', async () => {
    const { MODULOS_MEDIDOS, ACCESOS_MEDIDOS } = await import('../src/marco/seguridadMedida.ts');

    for (const [clave, medido] of [
      ['GET /seguridad/modulos', MODULOS_MEDIDOS[0]],
      ['GET /seguridad/accesos', ACCESOS_MEDIDOS[0]],
    ] as const) {
      const fila = ((formas[clave] ?? {})['contenido'] as unknown[])[0] as object;

      expect(Object.keys(medido ?? {}).sort(), clave).toEqual(Object.keys(fila).sort());
    }
  });

  it('`PUT /seguridad/sesion/ejercicio` NO publica la cuenta ni el nombre: solo la sesion', () => {
    // Es la razon por la que de esta respuesta se toma **solo** `ejercicioDeTrabajo`. Leer de
    // aqui quien esta trabajando dejaria la cabecera en blanco despues de cada cambio.
    expect(Object.keys(formas['PUT /seguridad/sesion/ejercicio'] ?? {}).sort()).toEqual([
      'ejercicioDeTrabajo',
      'id',
      'inicio',
      'usuarioId',
    ]);
  });

  it('la matriz de permisos NO tiene forma declarada: el contrato dice «objeto» y ya', () => {
    // Y hay que saberlo: la comparacion campo a campo del AC5 de #4 **no puede aplicarse aqui**.
    // El generador describe el tipo de retorno de cada controlador y este devuelve un
    // `Map<String, List<String>>`, que no tiene campos que describir. Lo unico que sostiene la
    // lectura son las 134 llaves medidas — y por eso `composicion.ts` no da por hecho que el
    // valor sea una lista. Esta prueba caduca sola el dia que el contrato lo declare.
    expect(formas['GET /seguridad/sesion/permisos']).toBe('objeto');
  });

  it('ninguna operacion publica un menu de la sesion: por eso el arbol se compone aqui', () => {
    // Si `seguridad` publicara el catalogo YA filtrado por quien pregunta, componerlo en la
    // interfaz —con dos operaciones de administracion, ver `SinArbol.tsx`— sobraria. Hoy no lo
    // publica: cero operaciones cuyo nombre hable de un menu, un arbol o una navegacion.
    const candidatas = Object.keys(formas).filter((clave) =>
      /menu|arbol|navegacion|submodulo/i.test(clave),
    );

    expect(candidatas).toEqual([]);
  });
});

describe('AC1 — el token no toca el almacenamiento del navegador', () => {
  it('la prohibicion sigue en la lista, con su clave', () => {
    expect(PROHIBICIONES.map((p) => p.clave)).toContain('token-en-almacenamiento');
  });

  it('y NO se le anadio ninguna excepcion: vale en todo el arbol', () => {
    const suya = PROHIBICIONES.find((p) => p.clave === 'token-en-almacenamiento');

    // Un `salvo: 'src/api/'` la apagaria justo en el unico directorio donde hay un token.
    expect(suya?.salvo).toBeUndefined();
  });

  it('un solo archivo de produccion toca localStorage o sessionStorage, y es la puerta', () => {
    // Cuanto mas se reparte el almacenamiento, menos vale mirar un sitio para saber que se
    // guarda. Hoy es uno, y su contenido lo mide `api/identidad.test.ts` por VALOR — que es la
    // mitad que la prohibicion de ESLint no puede ver, porque mira el nombre de la clave.
    const tocan = deProduccion.filter((ruta) =>
      /\b(localStorage|sessionStorage)\b/.test(readFileSync(join(FRONTEND, ruta), 'utf8')),
    );

    expect(tocan).toEqual(['src/api/identidad.ts']);
  });

  it('la prohibicion del fetch tampoco gano excepcion nueva: sigue siendo src/api/', () => {
    const delFetch = PROHIBICIONES.find((p) => p.clave === 'fetch-fuera-del-cliente');

    // AC2: el token entra por `solicitar()`, y para eso `solicitar()` tiene que seguir siendo
    // el unico camino. Una excepcion mas y deja de serlo.
    expect(delFetch?.salvo).toBe('src/api/');
    expect(PROHIBICIONES.filter((p) => p.salvo !== undefined)).toHaveLength(1);
  });
});

/**
 * Las capturas de la instalacion, y la guarda que impide que se conviertan en respaldos.
 *
 * Son dos desde I-3: la sesion (quien esta dentro) y la seguridad (que modulos hay y que puede
 * abrir esta cuenta). Las dos son lo mismo —bytes de un `curl`, para que las pruebas del marco
 * no repitan cuarenta y cuatro literales— y las dos tienen el mismo riesgo: que alguien escriba
 * `arbol ?? ARBOL_MEDIDO` y devuelva la navegacion constante que I-3 vino a quitar, esta vez
 * con una constante que ademas parece medida.
 */
const CAPTURAS = ['src/marco/sesionMedida.ts', 'src/marco/seguridadMedida.ts'];

describe('las capturas de la instalacion son de las pruebas, y no respaldos de produccion', () => {
  it.each(CAPTURAS)('«%s» solo la importan archivos de prueba', (captura) => {
    const archivo = captura.slice(captura.lastIndexOf('/') + 1);
    // Se busca un `import ... from '…/<archivo>'` y **no una mencion cualquiera**, y esa
    // correccion la trajo I-3 con su rojo: `seguridadMedida.ts` nombra a `sesionMedida.ts` en
    // su javadoc —dice que es su hermano y por que— y el patron anterior, que buscaba el
    // nombre a secas, lo dio por culpable. Una guarda que no distingue «lo importa» de «lo
    // menciona» acaba desactivandose para poder escribir un comentario, y entonces no vigila.
    const importa = new RegExp(`from\\s+'[^']*${archivo.replace('.', '\\.')}'`);
    const culpables = deProduccion.filter(
      (ruta) => ruta !== captura && importa.test(readFileSync(join(FRONTEND, ruta), 'utf8')),
    );

    expect(culpables).toEqual([]);
  });

  it('y lo que declara es lo que contesta la instalacion: sin ejercicio de trabajo', async () => {
    const { SESION_MEDIDA } = await import('../src/marco/sesionMedida.ts');

    // `null` no es una eleccion del archivo: es lo que contesta el backend, y es el caso que el
    // AC8 obliga a no mentir. Una muestra con un `2026` dentro lo dejaria sin ejercitar.
    expect(SESION_MEDIDA.ejercicioDeTrabajo).toBeNull();
  });
});

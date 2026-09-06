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
  it('las dos rutas de sesion estan declaradas, y son las dos primeras', () => {
    expect(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`)).toEqual([
      'GET /seguridad/sesion',
      'GET /seguridad/sesion/municipalidad',
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

describe('la sesion medida es de las pruebas, y no un respaldo de produccion', () => {
  it('solo la importan archivos de prueba', () => {
    // Sin esta guarda, `sesionMedida.ts` acabaria siendo el respaldo que `MarcoProps` existe
    // para prohibir: un `sesion ?? SESION_MEDIDA` en cualquier sitio devolveria la cabecera
    // constante que I-1 vino a quitar, y esta vez con una constante que ademas parece medida.
    const culpables = deProduccion.filter(
      (ruta) =>
        ruta !== 'src/marco/sesionMedida.ts' &&
        /sesionMedida\.ts/.test(readFileSync(join(FRONTEND, ruta), 'utf8')),
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

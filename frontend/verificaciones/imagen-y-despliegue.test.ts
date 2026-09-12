import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

/**
 * Que `rentas-web` se pueda desplegar, y que llegue a alguien (#44).
 *
 * <h2>Lo que este archivo vigila, y por que cada cosa</h2>
 *
 * Ninguna de estas propiedades falla haciendo ruido. Es la lista entera de las que, rotas, dejan
 * un despliegue que arranca:
 *
 *   · Un `USER` no numerico deja el pod en `CreateContainerConfigError`, y solo al desplegar.
 *   · Un `.dockerignore` sin los `.env` **hornea lo que lleven dentro en el paquete publicado**.
 *   · Las cabeceras de seguridad escritas a nivel `server` se apagan en cada `location` que
 *     declare una cabecera propia, porque `add_header` no se hereda. La pagina sigue saliendo.
 *   · Las dos prioridades del ingreso al reves hacen que la API la conteste el nginx, con un
 *     **200** y el `index.html` dentro.
 *   · Y el compose y el descriptor separandose es la trampa que ADR-0011 anoto.
 *
 * <h2>Lo que NO puede vigilar, y por eso no se finge aqui</h2>
 *
 * Que la imagen levante y sirva. Eso es `docker build` + `docker run` + pedirle una pagina, y
 * esta suite corre sin demonio de Docker. Las mediciones estan en el PR de #44 y en la fila del
 * registro; aqui se sujeta que los archivos que las producen no se deshagan.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');
const REPOSITORIO = join(FRONTEND, '..');

const leer = (ruta: string) => readFileSync(ruta, 'utf8');

const DOCKERFILE = leer(join(FRONTEND, 'Dockerfile'));
const NGINX = leer(join(FRONTEND, 'nginx.conf'));
const DOCKERIGNORE = leer(join(FRONTEND, '.dockerignore'));
const COMPOSE = leer(join(REPOSITORIO, 'despliegue', 'compose.yaml'));
const DESCRIPTOR = leer(join(REPOSITORIO, 'infrastructure', 'src', 'descriptor.ts'));
const PUBLICAR = leer(join(REPOSITORIO, '.github', 'workflows', 'publicar-imagenes.yml'));
const INDEX = leer(join(FRONTEND, 'index.html'));

/** Las lineas de una configuracion, sin comentarios: `#` a final de linea no es una directiva. */
const sinComentarios = (texto: string) =>
  texto
    .split('\n')
    .filter((l) => !l.trimStart().startsWith('#'))
    .join('\n');

describe('AC-1 — la imagen existe, y quien la publica sabe de donde sale', () => {
  it('estan los tres archivos que la definen', () => {
    for (const archivo of ['Dockerfile', 'nginx.conf', '.dockerignore']) {
      expect(existsSync(join(FRONTEND, archivo)), `falta frontend/${archivo}`).toBe(true);
    }
  });

  /**
   * La tercera entrada de la matriz, **con su contexto y su archivo propios**.
   *
   * Los dos tienen que ir en la matriz y no fijos en el paso: un `.dockerignore` solo cuenta
   * desde la raiz de SU contexto, asi que construir `frontend/Dockerfile` con el contexto en la
   * raiz del repositorio se llevaria dentro `node_modules`, los `.env` y las cookies de Keycloak
   * que el arnes deja en `e2e/.estado/`.
   */
  it('la matriz declara las TRES imagenes, cada una con su contexto y su archivo', () => {
    const entradas = [...PUBLICAR.matchAll(/- destino: (\S+)\n\s+imagen: (\S+)\n\s+archivo: (\S+)\n\s+contexto: (\S+)/g)]
      .map((m) => ({ destino: m[1], imagen: m[2], archivo: m[3], contexto: m[4] }));

    // Las rutas llevan `rentas/` delante desde #75: el anfitrion baja a `path: rentas` para que
    // el clon hermano quepa a su lado. `contexto: rentas` es la raiz de ESTE repositorio —
    // exactamente lo que era `.`—, asi que el `.dockerignore` que aplica a cada imagen no cambia.
    expect(entradas).toEqual([
      { destino: 'aplicacion', imagen: 'kamayuk-rentas', archivo: 'rentas/backend/Dockerfile', contexto: 'rentas' },
      { destino: 'migrador', imagen: 'kamayuk-rentas-migrador', archivo: 'rentas/backend/Dockerfile', contexto: 'rentas' },
      { destino: 'interfaz', imagen: 'kamayuk-rentas-interfaz', archivo: 'rentas/frontend/Dockerfile', contexto: 'rentas/frontend' },
    ]);

    // Y el paso las TOMA de la matriz. Con `context: .` fijo, la matriz seria decorativa.
    expect(PUBLICAR).toContain('context: ${{ matrix.contexto }}');
    expect(PUBLICAR).toContain('file: ${{ matrix.archivo }}');
  });

  /**
   * El trabajo `comprobar` pregunta por las MISMAS tres.
   *
   * Son dos listas que tienen que decir lo mismo: una imagen que se publique y no se pregunte
   * aqui queda sin la unica afirmacion que decide si el pod arranca — un `build-push-action` en
   * verde solo dice que el `push` no devolvio error.
   */
  it('el registro se pregunta por las mismas tres que se publican', () => {
    const publicadas = [...PUBLICAR.matchAll(/imagen: (\S+)/g)].map((m) => m[1]);
    const preguntadas = PUBLICAR.match(/for imagen in ([^;]+); do/)?.[1]?.trim().split(/\s+/) ?? [];
    expect(preguntadas.sort()).toEqual([...publicadas].sort());
  });

  /** El objetivo que la matriz publica tiene que ser una etapa que el Dockerfile define. */
  it('la etapa «interfaz» existe en el Dockerfile', () => {
    const etapas = [...DOCKERFILE.matchAll(/^FROM .+ AS (\S+)/gm)].map((m) => m[1]);
    expect(etapas).toContain('interfaz');
  });
});

describe('AC-2 — la imagen no lleva dentro nada que no deba', () => {
  /**
   * El uid EN NUMERO. `runAsNonRoot: true` no puede comprobar un nombre: el kubelet se niega a
   * arrancar el contenedor con un `CreateContainerConfigError`, y eso solo aparece al desplegar.
   */
  it('el USER es numerico', () => {
    const usuarios = [...DOCKERFILE.matchAll(/^USER\s+(\S+)/gm)].map((m) => m[1]);
    expect(usuarios, 'sin USER, nginx corre como root').not.toHaveLength(0);
    for (const u of usuarios) {
      expect(u, `«USER ${u}» no es numerico: runAsNonRoot no lo puede comprobar`).toMatch(/^\d+$/);
    }
  });

  it('declara su HEALTHCHECK, y pide un archivo por su nombre', () => {
    expect(DOCKERFILE).toMatch(/^HEALTHCHECK /m);
    // `/` cae al `index.html` por el `try_files` pase lo que pase, asi que no distingue «nginx
    // levantado» de «nginx levantado sobre el dist que se copio».
    expect(DOCKERFILE).toContain('/index.html');
  });

  /**
   * **Todos** los archivos de entorno que Vite lee, y no solo los que `.gitignore` nombra.
   *
   * Medido en este repositorio: el `.gitignore` de la raiz ignora `.env` y `*.local.*`, y con eso
   * `.env.local` y `.env.production` se quedan fuera de las dos reglas —`*.local.*` exige algo
   * detras del `.local`, asi que no casa con `.env.local`—. Los dos son nombres que Vite lee y
   * cuyo contenido acabaria **dentro del JavaScript publicado**. Es el hallazgo de `caja`#47.
   */
  it('el .dockerignore deja fuera node_modules y TODOS los archivos de entorno de Vite', () => {
    const reglas = sinComentarios(DOCKERIGNORE)
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => l !== '');

    expect(reglas).toContain('node_modules');

    // Los cuatro nombres que Vite carga, mas el que este repositorio tiene versionado.
    const queViteLee = [
      '.env',
      '.env.local',
      '.env.production',
      '.env.production.local',
      '.env.development',
    ];
    const cubierto = (archivo: string) =>
      reglas.some((r) => r === archivo || (r.endsWith('*') && archivo.startsWith(r.slice(0, -1))));
    for (const archivo of queViteLee) {
      expect(cubierto(archivo), `«${archivo}» entraria en el contexto y Vite lo hornearia`).toBe(true);
    }
  });

  /**
   * Y el estado del arnes, que son CREDENCIALES VIVAS.
   *
   * `e2e/.estado/` guarda cookies de sesion de Keycloak de la instalacion de quien corrio
   * `yarn e2e`. Esta en `.gitignore`, pero eso no lo mantiene fuera del contexto de Docker: en la
   * maquina de quien construye existe de verdad.
   */
  it('el .dockerignore deja fuera el estado del arnes', () => {
    const reglas = sinComentarios(DOCKERIGNORE).split('\n').map((l) => l.trim());
    expect(reglas).toContain('e2e');
  });

  /** Que no quede fuente dentro del `dist/`, comprobado por la propia imagen al construirse. */
  it('la imagen comprueba que su dist no lleva codigo fuente', () => {
    expect(DOCKERFILE).toMatch(/-name '\*\.ts'/);
    expect(DOCKERFILE).toMatch(/-name '\*\.tsx'/);
  });
});

describe('AC-3 — el proxy de datos NO viaja en la imagen', () => {
  it('se construye con la bandera apagada, escrito y no supuesto', () => {
    expect(DOCKERFILE).toMatch(/ENV VITE_KAMAYUK_PROXY_DE_DATOS=false/);
    // Y antes del build, o no serviria de nada.
    expect(DOCKERFILE.indexOf('VITE_KAMAYUK_PROXY_DE_DATOS=false')).toBeLessThan(
      DOCKERFILE.indexOf('RUN yarn build'),
    );
  });

  /**
   * Las cinco cadenas del AC-3, comprobadas **dentro** de la construccion de la imagen.
   *
   * Medirlo fuera tambien vale, y esta en el PR; tenerlo aqui es lo que hace que una imagen con
   * cifras del artboard **no se pueda publicar**: el `docker build` sale en rojo.
   */
  it('la imagen se niega a construirse si lo servido lleva codigo fuente', () => {
    // La mitad de #44 que SIGUE VIVA, y es la que mas vale: un `.ts` servido es el codigo de la
    // puerta de identidad publicado. Medido en su dia: con un `COPY` de `src/` puesto en la ultima
    // etapa, la imagen servia `/src/api/identidad.ts` con 200 y 13 712 bytes.
    expect(DOCKERFILE).toMatch(/-name '\*\.ts' -o -name '\*\.tsx'/);
    expect(DOCKERFILE).toContain('Lo que se sirve lleva codigo fuente dentro');
  });

  /**
   * **La otra mitad de #44 esta abierta, y el Dockerfile tiene que DECIRLO.**
   *
   * «Ni una cifra del artboard en lo servido» era cierto mientras las cifras vivian tras una
   * bandera que Rollup plegaba. En V8 **son el contenido de las cuarenta pantallas**, asi que
   * viajan siempre.
   *
   * Y no hay un subconjunto de las cinco cadenas que sirva para distinguir V6 de V8: se intento
   * dejar tres y **la CI lo desmintio en el primer intento** —«Rufina Medina Medina» esta en
   * `definiciones/fiscalizacion.ts` y «170,616.75» en `definiciones/rentas-registro.ts»—. El
   * artboard V8 reutiliza los datos de muestra del V6, que es coherente con que se declare
   * derivado suyo.
   *
   * Asi que el bucle se retiro entero. Esta prueba existe para que ese hueco **no se olvide**: un
   * recorte silencioso de una guarda es peor que quitarla, porque nadie sabe que dejo de cubrir.
   */
  it('y DICE que la garantia de las cifras esta abierta, con su numero', () => {
    expect(DOCKERFILE, 'el Dockerfile no dice que la garantia esta abierta').toContain('#97');
    // Se mira el MECANISMO —el bucle que recorre cadenas— y no la palabra: la prosa de arriba
    // tiene que poder nombrar «Rufina Medina Medina» para explicar por que el bucle se fue.
    // Prohibir la palabra obligaria a escribir el motivo en acertijos.
    expect(
      DOCKERFILE,
      'el Dockerfile volvio a buscar cadenas del artboard: con las cifras en las definiciones,\n' +
        'eso bloquea la construccion SIEMPRE. Si vuelve, que sea con #97 resuelto.',
    ).not.toMatch(/for cadena in/);
  });

  /**
   * Los mapas de fuente no se publican.
   *
   * `vite.config.ts` declara `build.sourcemap: true`, asi que `dist/` sale con un `.map` por
   * trozo — y esta medido en I-2 que ahi dentro SI aparecen «Rufina Medina Medina» y «62,418»,
   * aunque en el `.js` sean cero. Sin retirarlos, el AC-3 seria falso por los mapas. Y ademas un
   * `.map` lleva el codigo fuente entero.
   */
  it('los mapas de fuente se retiran antes de servir', () => {
    expect(DOCKERFILE).toMatch(/find dist -name '\*\.map' -delete/);
  });
});

describe('AC-4 y AC-5 — lo que nginx sirve, y con que cabeceras', () => {
  /** Los bloques `location` de la configuracion, con su cuerpo. */
  const bloques = (() => {
    const texto = sinComentarios(NGINX);
    const salida: { cabecera: string; cuerpo: string }[] = [];
    const patron = /location\s+([^{]+)\{/g;
    let m: RegExpExecArray | null;
    while ((m = patron.exec(texto)) !== null) {
      let profundidad = 1;
      let i = patron.lastIndex;
      while (i < texto.length && profundidad > 0) {
        if (texto[i] === '{') profundidad += 1;
        if (texto[i] === '}') profundidad -= 1;
        i += 1;
      }
      salida.push({ cabecera: m[1]!.trim(), cuerpo: texto.slice(patron.lastIndex, i - 1) });
    }
    return salida;
  })();

  const LAS_TRES = ['X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy'];

  it('el analizador encuentra los bloques de verdad', () => {
    // Si esto se rompe, todas las de abajo pasarian en verde sobre una lista vacia.
    expect(bloques.length).toBeGreaterThanOrEqual(4);
    expect(bloques.map((b) => b.cabecera)).toContain('/assets/');
  });

  /**
   * AC-5. `add_header` **no se hereda**: un bloque que declara una cabecera propia descarta
   * TODAS las del nivel de arriba. Escribirlas una vez a nivel `server` las apagaria justo en los
   * bloques que ponen `Cache-Control`, o sea en casi todo lo que se sirve, y la pagina seguiria
   * saliendo igual.
   */
  it('CADA location declara las tres cabeceras de seguridad, con «always»', () => {
    for (const bloque of bloques) {
      for (const cabecera of LAS_TRES) {
        const linea = new RegExp(`add_header\\s+${cabecera}\\s+[^;]+always\\s*;`);
        expect(
          bloque.cuerpo,
          `el bloque «location ${bloque.cabecera}» no declara «${cabecera} … always»`,
        ).toMatch(linea);
      }
    }
  });

  /**
   * `always`, y no por gusto: sin el, `add_header` **solo se aplica a 2xx, 204, 301, 302, 303,
   * 304, 307 y 308**. O sea que se caerian precisamente en las respuestas de error, que es donde
   * mas importan — un 404 tambien se puede enmarcar en un `iframe`.
   */
  it('ninguna cabecera de seguridad se declara sin «always»', () => {
    for (const cabecera of LAS_TRES) {
      const sinAlways = new RegExp(`add_header\\s+${cabecera}\\s+[^;]*;`, 'g');
      for (const encontrada of sinComentarios(NGINX).match(sinAlways) ?? []) {
        expect(encontrada, 'sin «always» la cabecera no sale en los errores').toContain('always');
      }
    }
  });

  /**
   * Y la cabecera de cache es el caso CONTRARIO: va **sin** `always`, a proposito.
   *
   * Lo encontro medir la imagen de verdad. Con `always`, un activo que no existe contestaba
   * `404` **con `max-age=31536000, immutable` dentro**: un navegador que cachee esa respuesta se
   * queda un ano sin volver a pedir ese archivo, y el remedio normal —recargar— no lo arregla,
   * porque lo que tiene guardado es el 404. Sin `always`, `add_header` solo se aplica a 2xx y a
   * los redirigidos, que es justo lo que se quiere.
   *
   * Es la unica cabecera del archivo que NO lleva `always`, y por eso se comprueba: alguien que
   * «uniformara» el archivo poniendoselo a todas devolveria el defecto en silencio.
   */
  it('el cache de un ano NO se aplica a los errores', () => {
    const activos = bloques.find((b) => b.cabecera === '/assets/');
    const linea = activos?.cuerpo.match(/add_header\s+Cache-Control[^;]+;/)?.[0] ?? '';
    expect(linea).toContain('immutable');
    expect(linea, 'con «always» un 404 de un activo se cachearia un ano').not.toContain('always');
  });

  /**
   * AC-4, el «200 que miente». `try_files $uri /index.html` es lo que hace que recargar en
   * `#/contribuyentes` funcione, y tambien lo que convierte un `.js` que falta en HTML con codigo
   * de exito. En `/assets/` no se admite ese repliegue: un activo que falta da 404.
   */
  it('un activo que falta da 404 y no el index.html', () => {
    const activos = bloques.find((b) => b.cabecera === '/assets/');
    expect(activos?.cuerpo).toMatch(/try_files\s+\$uri\s+=404\s*;/);
    expect(activos?.cuerpo).not.toContain('index.html');
  });

  /**
   * Y la averia del prefijo, dicha en voz alta.
   *
   * A este nginx nunca le puede llegar una ruta que empiece por `/rentas/`: el ingreso lo quita
   * antes de reenviar. Si llega, el `stripPrefix` no esta haciendo su trabajo — y sin este bloque
   * esa averia entraria por `location /` y saldria como el 200 de arriba.
   */
  it('una ruta con el prefijo puesto da 404 nombrando la causa', () => {
    const guarda = bloques.find((b) => b.cabecera === '/rentas/');
    expect(guarda, 'sin este bloque, un stripPrefix ausente sale como un 200 con HTML').toBeDefined();
    expect(guarda?.cuerpo).toMatch(/return\s+404/);
    expect(guarda?.cuerpo).toContain('stripPrefix');
  });

  /**
   * Este nginx NO reenvia a ningun sitio, y esa ausencia es una afirmacion.
   *
   * El mismo origen se consigue un piso mas arriba —el ingreso parte `/rentas` en dos—, asi que
   * un reenvio aqui seria un SEGUNDO camino a la API que nadie revisa, y obligaria a este
   * contenedor a alcanzar el backend por la red, que es justo lo que su `NetworkPolicy` le niega.
   *
   * La cuenta se hace sobre el archivo entero a proposito, lo que obliga a que ni la prosa de
   * `nginx.conf` ni la de aqui escriban el nombre de la directiva: nombrarla daria un positivo
   * que no es un reenvio, y una comprobacion que se dispara con el texto que la explica es una
   * comprobacion que alguien acaba apagando.
   */
  it('no hay ni un reenvio en toda la configuracion', () => {
    const directiva = ['proxy', 'pass'].join('_');
    expect(NGINX.split(directiva).length - 1).toBe(0);
  });
});

describe('AC-6 y AC-8 — el compose y el descriptor dicen lo mismo (ADR-0011)', () => {
  /**
   * El compose SIN sus comentarios, y hace falta de verdad.
   *
   * La primera version de este bloque leia el archivo entero y dio **dos rojos que no eran
   * defectos**: el comentario del servicio explica que la variable NO puede llamarse
   * `KAMAYUK_PUERTO_INTERFAZ` —y para explicarlo la escribe— y que la imagen NUNCA es
   * `kamayuk-rentas-web` —y para explicarlo la escribe—. O sea que las dos guardas se disparaban
   * con la prosa que las justifica, que es la forma mas segura de que alguien acabe borrando el
   * comentario en vez del defecto.
   */
  const SIN_PROSA = sinComentarios(COMPOSE);

  /** Las etiquetas de Traefik del compose, como pares. */
  const etiquetas = Object.fromEntries(
    [...SIN_PROSA.matchAll(/^\s+- (traefik\.[^=]+)=(.+)$/gm)].map((m) => [m[1]!, m[2]!]),
  );

  it('el compose declara el servicio de la interfaz, con la imagen y la etapa del Dockerfile', () => {
    expect(SIN_PROSA).toMatch(/^ {2}rentas-interfaz:$/m);
    expect(SIN_PROSA).toContain('image: kamayuk-rentas-interfaz:compose');
    expect(SIN_PROSA).toContain('context: ../frontend');
    expect(SIN_PROSA).toContain('target: interfaz');
  });

  /**
   * AC-8. **Sin `depends_on`**, y es una afirmacion: esta interfaz no necesita el backend ni para
   * dibujarse ni para hablar con el, porque no reenvia nada. Declarar una dependencia que no
   * existe haria que el compose mintiera sobre el grafo —que es lo que la guarda de
   * `infrastructure` compara contra el descriptor— y obligaria a `up -d rentas-interfaz` a
   * levantar la base, el migrador y la implantacion para servir unos archivos que no los usan.
   */
  it('la interfaz no declara depends_on', () => {
    const servicio = SIN_PROSA.slice(SIN_PROSA.indexOf('  rentas-interfaz:'));
    expect(servicio).not.toContain('depends_on');
  });

  /**
   * AC-8, el puerto. `KAMAYUK_PUERTO_INTERFAZ` a secas YA lo usa la interfaz del monolito, y el
   * `.env` es el mismo para todo esto: reusar el nombre haria que el valor por omision de aqui no
   * se aplicara nunca y que las dos interfaces pidieran el mismo puerto del anfitrion. Es lo que
   * `caja`#39 midio.
   */
  it('el puerto se pide por una variable con el sufijo del sistema', () => {
    const puertos = [...SIN_PROSA.matchAll(/\$\{(KAMAYUK_PUERTO_[A-Z_]+)/g)].map((m) => m[1]);
    expect(puertos).toContain('KAMAYUK_PUERTO_INTERFAZ_RENTAS');
    expect(puertos, 'ese nombre ya es el de la interfaz del monolito').not.toContain(
      'KAMAYUK_PUERTO_INTERFAZ',
    );
  });

  /**
   * AC-6 en el compose: la ruta partida en dos, con las prioridades escritas.
   *
   * Traefik v3 ordena por longitud de la regla cuando nadie declara `priority`, asi que hoy
   * saldria bien **por accidente**. Y al reves el fallo no grita: la API la contestaria el nginx
   * con un 200 y el `index.html` dentro.
   */
  it('las dos reglas de Traefik llevan prioridad, y la de la API es la mayor', () => {
    expect(etiquetas['traefik.http.routers.rentas.rule']).toBe('PathPrefix(`/rentas/api/v1`)');
    expect(etiquetas['traefik.http.routers.rentas-interfaz.rule']).toBe('PathPrefix(`/rentas`)');

    const api = Number(etiquetas['traefik.http.routers.rentas.priority']);
    const interfaz = Number(etiquetas['traefik.http.routers.rentas-interfaz.priority']);
    expect(api, 'sin prioridad explicita la precedencia la decide la longitud del texto').not.toBeNaN();
    expect(interfaz).not.toBeNaN();
    expect(api).toBeGreaterThan(interfaz);
  });

  /**
   * Y el prefijo se quita SOLO en la de la interfaz: `Api.RAIZ` del backend es `/rentas/api/v1`
   * entera, asi que quitarselo lo dejaria buscando `/api/v1/...` y contestando 404 a todo.
   */
  it('el stripprefix va solo en el enrutador de la interfaz', () => {
    expect(etiquetas['traefik.http.routers.rentas-interfaz.middlewares']).toBe(
      'rentas-quitar-prefijo',
    );
    expect(etiquetas['traefik.http.middlewares.rentas-quitar-prefijo.stripprefix.prefixes']).toBe(
      '/rentas',
    );
    expect(etiquetas['traefik.http.routers.rentas.middlewares']).toBeUndefined();
  });

  /**
   * Las dos mitades de ADR-0011, comparadas de verdad: el reparto del compose tiene que ser el
   * mismo que el del descriptor.
   *
   * Se compara contra el TEXTO del descriptor y no importandolo, porque este paquete no depende
   * de aquel; lo que se busca son las cuatro decisiones que tendrian que moverse a la vez.
   */
  it('el descriptor declara el mismo reparto que el compose', () => {
    expect(DESCRIPTOR).toContain('PathPrefix(\\`/${SISTEMA}/api/v1\\`)');
    expect(DESCRIPTOR).toContain('stripPrefix: { prefixes: [`/${SISTEMA}`] }');
    expect(DESCRIPTOR).toContain('imagenes: [SISTEMA, MIGRADOR, INTERFAZ]');
    expect(DESCRIPTOR).toMatch(/const INTERFAZ = `\$\{SISTEMA\}-interfaz`/);
  });

  /** Y `rentas-web` sigue siendo el backend. Pisarlo seria un Service repartiendo entre dos cosas. */
  it('la interfaz no reclama el nombre del backend', () => {
    expect(SIN_PROSA).not.toContain('kamayuk-rentas-web');
    expect(DESCRIPTOR).not.toContain('const INTERFAZ = `${SISTEMA}-web`');
  });
});

describe('las senias del ambiente: lo que Vite no puede hornear', () => {
  const PUBLICO = leer(join(FRONTEND, 'public', 'configuracion.js'));

  /**
   * El orden de las dos lineas de `index.html` es lo unico que hace que esto funcione: un modulo
   * se difiere hasta despues del analisis del documento, asi que un guion clasico se ejecuta
   * antes aunque este escrito debajo. Con los dos como modulos, la puerta de identidad leeria las
   * senias antes de que nadie las hubiera puesto.
   */
  it('index.html carga la configuracion como guion clasico, y antes del paquete', () => {
    const configuracion = INDEX.indexOf('src="/configuracion.js"');
    const paquete = INDEX.indexOf('src="/src/main.tsx"');
    expect(configuracion, 'index.html no carga configuracion.js').toBeGreaterThan(-1);
    expect(paquete).toBeGreaterThan(-1);

    const etiqueta = INDEX.slice(INDEX.lastIndexOf('<script', configuracion), configuracion);
    expect(etiqueta, 'un modulo llegaria tarde: se difiere hasta despues del documento').not.toContain(
      'type="module"',
    );
  });

  /**
   * El que viaja en la imagen esta VACIO a proposito: es el que el `ConfigMap` reemplaza. Si
   * trajera valores, una municipalidad sin `ConfigMap` montado entraria por el emisor de otra —o
   * por `localhost`— sin que nada lo dijera.
   */
  it('el configuracion.js que viaja en la imagen no fija ninguna senia', () => {
    expect(PUBLICO).toContain('window.__KAMAYUK_RENTAS__');
    for (const senia of ['oidcRealm', 'oidcCliente', 'oidcAlcance']) {
      expect(PUBLICO, `«${senia}» no puede venir con valor dentro de la imagen`).not.toContain(senia);
    }
  });

  /**
   * Y el descriptor lo sirve desde el ambiente. La cadena que se busca es la del contrato
   * (`plataforma.emisor`), no un literal: escribir aqui una URL seria repetir una convencion de
   * `infrastructure`, y dos copias de una convencion se separan.
   */
  it('el descriptor compone el guion con el emisor del ambiente', () => {
    expect(DESCRIPTOR).toContain('oidcRealm: e.plataforma.emisor');
    expect(DESCRIPTOR).toContain('"configuracion.js"');
  });

  /**
   * AC-9. El hueco que este repositorio **no puede cerrar**, escrito donde se lee antes de
   * desplegar y no el dia del despliegue.
   */
  it('el hueco del redirect_uri queda declarado, y nombra donde se cierra', () => {
    expect(DESCRIPTOR).toContain('redirect_uri');
    expect(DESCRIPTOR).toContain('localhost:5173');
    expect(DESCRIPTOR, 'hay que decir de quien es el realm').toContain('infrastructure');
  });
});

/**
 * **#75 — el `Dockerfile` alcanza al clon hermano, y los cuatro sitios dicen lo mismo.**
 *
 * `frontend/package.json` declara `@kamayuk/{api,formato,sesion}` como
 * `link:../../kamayuk-lib/paquetes/*`, y el contexto de esta imagen es `frontend/`: el destino
 * queda DOS niveles por encima, fuera de lo que Docker puede copiar. Lo resuelve un contexto con
 * nombre de BuildKit, y eso reparte una sola verdad en cuatro archivos —el `package.json`, el
 * `Dockerfile`, el workflow y el compose— que tienen que decir lo mismo.
 *
 * <h2>Por que estas tres guardas, y no ninguna</h2>
 *
 * Porque medido el 2026-09-12 **no existia ni una** asercion en todo el repositorio sobre el
 * `WORKDIR`, sobre un `COPY --from=`, sobre el orden o el nombre de las etapas, ni sobre
 * `build-contexts`/`additional_contexts`. O sea que el arreglo de #75 entraba sin que nada lo
 * sujetara, y su modo de fallo es el mismo que viene a cerrar:
 *
 * > `WORKDIR /obra/rentas/frontend` se «simplifica» a `/obra/frontend` seis meses despues
 * > —queda mas corto—, la imagen sigue construyendose mientras nadie importe `@kamayuk/*`, y el
 * > dia que el import llegue, `../../kamayuk-lib` resuelve a `/kamayuk-lib`, el `yarn install`
 * > falla **en `main`** y toda la CI estaba en verde.
 */
describe('#75 — el contexto con nombre, y la profundidad que lo sostiene', () => {
  /** Las etapas que el propio Dockerfile declara: `FROM … AS <nombre>`. */
  const etapas = [...DOCKERFILE.matchAll(/^FROM\s+\S+\s+AS\s+(\S+)/gm)].map((m) => m[1]);
  /** De donde copia: una etapa suya, o un contexto que alguien tiene que darle. */
  const copiaDe = [...DOCKERFILE.matchAll(/^COPY\s+--from=(\S+)/gm)].map((m) => m[1]);
  const contextosQuePide = [...new Set(copiaDe.filter((n) => !etapas.includes(n)))];

  it('EL CENTINELA: el Dockerfile declara etapas y copia de sitios', () => {
    // Sin esto, todo lo de abajo pasaria sobre listas vacias si las dos expresiones dejaran de
    // casar — que es como una guarda se queda sin sujeto y sigue en verde.
    expect(etapas).toContain('interfaz');
    expect(copiaDe.length).toBeGreaterThan(0);
  });

  it('todo `COPY --from=` que no es una etapa lo declaran el workflow Y el compose', () => {
    // Son dos sitios que tienen que decir el mismo nombre, y ninguno de los dos falla solo:
    // BuildKit resuelve un `--from=` desconocido como NOMBRE DE IMAGEN, o sea que se va a
    // buscar `docker.io/library/kamayuk-lib:latest` — y el rojo que sale habla de una imagen
    // que no existe, no de un contexto que falta.
    expect(contextosQuePide).toEqual(['kamayuk-lib']);

    for (const nombre of contextosQuePide) {
      expect(PUBLICAR, `«${nombre}» no lo declara publicar-imagenes.yml`).toContain(
        `contextos: ${nombre}=`,
      );
      expect(PUBLICAR).toContain('build-contexts: ${{ matrix.contextos }}');
      expect(sinComentarios(COMPOSE), `«${nombre}» no lo declara el compose`).toMatch(
        new RegExp(`additional_contexts:\\s*\\n\\s*${nombre}:`),
      );
    }
  });

  it('y el workflow clona ese hermano, porque un contexto con nombre no se inventa', () => {
    expect(PUBLICAR).toContain('repository: hneyra/kamayuk-lib');
    expect(PUBLICAR).toContain('path: kamayuk-lib');
    // El anfitrion tiene que bajar, o el hermano no cabe al lado.
    expect(PUBLICAR).toContain('path: rentas');
  });

  it('LA PROFUNDIDAD: el `WORKDIR` deja `../../` donde el `COPY` pone al hermano', () => {
    // ESTA es la que sujeta el arreglo, y se DERIVA de los tres archivos en vez de escribirse:
    // una constante repetida a mano se queda vieja el dia que alguien mueva uno de los tres.
    const paquete = /"@kamayuk\/[a-z]+":\s*"link:([^"]+)"/.exec(
      leer(join(FRONTEND, 'package.json')),
    )?.[1];
    const trabajo = /^WORKDIR\s+(\S+)/m.exec(DOCKERFILE)?.[1];
    const destinoDelCopy = /^COPY --from=kamayuk-lib\s+\S+\s+(\S+)/m.exec(DOCKERFILE)?.[1];

    expect(paquete, 'no hay ningun `link:` en el package.json').toBeDefined();
    expect(trabajo, 'el Dockerfile no declara WORKDIR').toBeDefined();
    expect(destinoDelCopy, 'el COPY del hermano no dice donde lo deja').toBeDefined();

    // Donde cae el paquete si se sigue el `link:` desde el `WORKDIR`, resuelto como lo haria
    // el sistema de archivos de la imagen.
    const resuelto = join(trabajo as string, paquete as string);
    // Y donde el COPY lo dejo de verdad. `paquetes/formato` cuelga de ahi.
    const puesto = join(destinoDelCopy as string, '..');

    expect(
      resuelto.startsWith(puesto),
      `El «link:» lleva a «${resuelto}» y el COPY deja al hermano en «${puesto}».\n` +
        'El `WORKDIR` tiene que reproducir la disposicion de clones hermanos —' +
        '`/obra/rentas/frontend` con el hermano en `/obra/kamayuk-lib`— o `yarn install` no lo\n' +
        'encuentra. Y no fallaria hoy: mientras nada de `src/` importe `@kamayuk/*`, la imagen\n' +
        'se construye igual y el defecto espera a `main`.',
    ).toBe(true);
  });

  it('del hermano se copia SOLO `paquetes/`: un contexto con nombre no lo acota ningun `.dockerignore`', () => {
    // Medido el 2026-09-12: `kamayuk-lib` no trae `.dockerignore` y su `node_modules` pesa
    // 160 MB. Un `COPY --from=kamayuk-lib .` se lo llevaria dentro, con el `.git` y cualquier
    // `.env`. Y `frontend/.dockerignore` no ayuda: solo filtra el contexto PRINCIPAL.
    expect(DOCKERFILE).toMatch(/^COPY --from=kamayuk-lib paquetes\/ /m);
    expect(DOCKERFILE).not.toMatch(/^COPY --from=kamayuk-lib \.\s/m);
  });

  it('la imagen se CONSTRUYE en los PR y se PUBLICA solo al integrar', () => {
    // Hasta #75 solo se construia al integrar en `main`, asi que un `Dockerfile` roto se
    // descubria despues del merge — y en un flujo sin filtro `paths`, dejando sin su tercera
    // imagen a todos los commits siguientes.
    expect(PUBLICAR).toMatch(/^\s{2}pull_request:$/m);
    expect(PUBLICAR).toContain("push: ${{ github.event_name == 'push' }}");
    // Y nadie publica desde una rama. Sin esta linea, cambiar la expresion por `true` no
    // rompería nada y las imagenes de un PR acabarian en el registro.
    expect(PUBLICAR).not.toContain('push: true');
    // El trabajo que pregunta al registro no corre en un PR: alli no hay nada publicado, y su
    // rojo —`MANIFEST_UNKNOWN`— no hablaria de nada roto.
    expect(PUBLICAR).toContain("if: github.event_name != 'pull_request'");
  });
});

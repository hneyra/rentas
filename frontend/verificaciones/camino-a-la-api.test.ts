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
import { PREFIJO as RAIZ } from '../src/api/cliente.ts';
import { RUTAS } from '../src/datos/lecturas.ts';
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
/** El contrato de la PETICION: que hace falta para pedir cada operacion (#26). */
const PARAMETROS = join(FRONTEND, '../docs/50-api/parametros-de-la-api.json');

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
  it('las treinta y siete: I-1, I-3, I-4, #168, #170, #167, #169, #181, #179, #180, #184, #215, #237 y #272', () => {
    // La lista escrita a mano es a proposito. Derivarla de `YA_SERVIDAS` la haria pasar diga lo
    // que diga: encender una ruta es una decision, y una decision se revisa leyendo su diff. La
    // lista crece de una en una porque encenderlas todas a la vez seria cambiar todas las
    // respuestas en una tarde sin poder decir cual rompio la pantalla; que cada una exista en el contrato
    // lo comprueba el caso de mas abajo.
    expect(YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`)).toEqual([
      'GET /seguridad/sesion',
      'GET /seguridad/sesion/municipalidad',
      'GET /seguridad/modulos',
      'GET /seguridad/accesos',
      'GET /seguridad/sesion/permisos',
      'PUT /seguridad/sesion/ejercicio',
      'GET /rentas/contribuyentes',
      'GET /rentas/contribuyentes/{id}/ficha',
      'GET /coactiva/deudas',
      'GET /rentas/predial/corridas/ultima',
      'GET /rentas/predial/corridas/{corridaId}/observados',
      'GET /rentas/beneficios',
      'GET /licencias/ciiu',
      'GET /licencias/funcionamiento',
      'GET /coactiva/expedientes',
      'GET /coactiva/expedientes/{numero}/proceso',
      'GET /coactiva/liquidaciones-costas',
      'GET /coactiva/prescripcion',
      'GET /indicadores/recaudacion',
      'GET /indicadores/trabajo-parado',
      'GET /consultas/unificada',
      'GET /consultas/deudas-con-beneficio',
      'GET /consultas/constancias/no-adeudo',
      'GET /seguridad/auditoria',
      'GET /fiscalizacion/programas',
      'GET /fiscalizacion/programas/{id}/muestra',
      'GET /fiscalizacion/programas/{id}/embudo',
      'GET /fiscalizacion/actas',
      'GET /fiscalizacion/resoluciones',
      'GET /fiscalizacion/resoluciones/{numero}',
      'GET /transito/papeletas',
      'GET /transito/papeletas/{numero}/actos',
      'GET /transito/internamientos',
      'GET /rentas/vehiculos/{placa}',
      'GET /transito/reportes/resumen-papeletas',
      // #237. La unica lectura de `territorio`, publicada por #207 **sin conectar la hoja**. Es la
      // primera de la lista que contesta **204 sin cuerpo** —y la segunda que puede hacerlo: la de
      // la corrida ya lo hacia y nadie lo habia mirado, ver `api/cliente.ts`—.
      'GET /rentas/predial/determinaciones',
      // #272. El resumen de la cartera coactiva por etapa: cuatro de los cinco campos de
      // `coa-panel`, que hasta ahora sacaba UNO del `totalElementos` de otra operacion.
      'GET /coactiva/cartera/resumen',
    ]);
  });

  it('y la escritura sigue siendo UNA: las de I-4, #168, #170 y #181 son todas lecturas', () => {
    // Las escrituras cambian datos y quedan auditadas, asi que encender una no es como
    // encender una lectura: si algun dia son cinco, esta cifra lo dice en la revision. I-4
    // enciende seis rutas y ninguna escribe — el expediente todavia no guarda nada. Y #168
    // enciende dos mas que tampoco: el padron por el que `aut-tram` filtraria de verdad es
    // `POST /licencias/funcionamiento/reportes/padron`, y quedarse fuera por el verbo es
    // justo lo que esta cifra vigila.
    //
    // enciende seis rutas y ninguna escribe — el expediente todavia no guarda nada.
    //
    // #170 enciende otras cuatro y tampoco: es la prueba que impide que `coa-cart` se conecte
    // con `POST /coactiva/convenios`, que es la unica operacion que publica sus ocho campos y
    // **crea un convenio de fraccionamiento**. Pintar una pantalla no puede fraccionar la deuda
    // de nadie.
    // #179 enciende cuatro mas y tampoco: es la prueba que deja fuera a `POST
    // /fiscalizacion/programas/{id}/muestra` —«Regenerar muestra», el boton de la tabla de
    // `fis-prog`— y a `POST /fiscalizacion/liquidaciones`. Sortear una muestra o liquidar la deuda
    // de alguien para pintar una pantalla es exactamente lo que esta cifra vigila.
    //
    // Y #180 enciende cuatro mas que tampoco escriben, que es lo que deja fuera a las dos rutas
    // que el arbol le atribuye a `tra-pap` y `tra-veh` en `BASE`: `/transito/descargos` y
    // `/transito/constancias-libres` existen en el contrato **solo como POST**. La primera
    // presentaria un descargo en nombre de alguien para pintar una pantalla; la segunda contesta
    // un archivo y no un JSON con campos.
    expect(YA_SERVIDAS.filter((o) => o.metodo !== 'GET').map((o) => o.ruta)).toEqual([
      '/seguridad/sesion/ejercicio',
    ]);
  });

  it('las DOS del expediente que exigen un parametro sin declarar siguen fuera (#26)', () => {
    // Medido: sin `?codContribuyente=` las dos contestan **422**, y ese parametro no aparece en
    // el contrato — que declara la forma de la RESPUESTA y no la de la peticion. Encenderlas
    // seria escribir en el frontend un nombre que nada de este repositorio puede comprobar.
    // Esta prueba es la lista de trabajo pendiente: el dia que se enciendan, se borra de aqui.
    const fuera = YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`);

    expect(fuera).not.toContain('GET /rentas/predios');
    expect(fuera).not.toContain('GET /consultas/deuda');
  });

  it('las tres de Consultas mandan un parametro que el contrato de la PETICION declara', () => {
    // Es la mitad que #26 enseno y que la lista de arriba no puede ver: una ruta que existe puede
    // exigir un parametro que nadie declara, y escribirlo en el frontend seria construir sobre un
    // nombre que nada de este repositorio comprueba. `parametros-de-la-api.json` lo genera
    // `ParametrosDeLaApiTest` de la FIRMA de cada controlador, asi que si alguien le cambia el
    // nombre al parametro, esto sale rojo antes de que nadie levante nada.
    const parametros = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as Record<
      string,
      { readonly obligatorios: readonly string[]; readonly opcionales: readonly string[] }
    >;
    const declara = (clave: string, parametro: string): boolean => {
      const suyos = parametros[clave];
      return (
        suyos !== undefined && [...suyos.obligatorios, ...suyos.opcionales].includes(parametro)
      );
    };

    // `deudas-con-beneficio` lo declara OPCIONAL y el controlador lo exige igual (422 sin el), asi
    // que se manda siempre: lo que importa aqui es que el nombre exista, no en que lista este.
    expect(declara('GET /consultas/unificada', 'contribuyente')).toBe(true);
    expect(declara('GET /consultas/deudas-con-beneficio', 'contribuyente')).toBe(true);
    expect(declara('GET /consultas/constancias/no-adeudo', 'codContribuyente')).toBe(true);

    // Y lo que se manda es eso y no otra cosa: las rutas se construyen aqui una sola vez.
    expect(RUTAS.fichaUnificadaDe('A/1')).toBe('/consultas/unificada?contribuyente=A%2F1');
    expect(RUTAS.deudasConBeneficioDe('A/1')).toBe(
      '/consultas/deudas-con-beneficio?contribuyente=A%2F1',
    );
    expect(RUTAS.constanciaDeNoAdeudoDe('A/1')).toBe(
      '/consultas/constancias/no-adeudo?codContribuyente=A%2F1',
    );
  });

  it('las DOS rutas `BASE` de Transito existen, y SOLO como `POST` (#180)', () => {
    // El arbol se las atribuye a `tra-pap` y `tra-veh` con verbo `BASE` —«solo se leyo el
    // `@RequestMapping` de la clase»—. Verificarlas es la mitad del issue, y el resultado no es
    // «no existen»: existen, y con un solo verbo. Asi que no se encienden, y no por prudencia:
    // una escribe un descargo en nombre de alguien y la otra contesta un archivo.
    //
    // Esta prueba caduca sola el dia que alguien publique el `GET`: entonces se pone roja y dice
    // que hay algo nuevo que mirar, en vez de dejar la pantalla sin conectar para siempre.
    const conVerbo = (ruta: string) =>
      declaradas.filter((clave) => clave.endsWith(` ${ruta}`)).sort();

    expect(conVerbo('/transito/descargos')).toEqual(['POST /transito/descargos']);
    expect(conVerbo('/transito/constancias-libres')).toEqual(['POST /transito/constancias-libres']);

    // Y lo que esa primera ruta guarda **ya sale** por la que si se enciende: el expediente de la
    // papeleta publica `descargos[]`. O sea que el hueco no lo es.
    const actos = JSON.parse(readFileSync(FORMAS, 'utf8')) as Record<string, unknown>;
    expect(Object.keys(actos['GET /transito/papeletas/{numero}/actos'] as object)).toContain(
      'descargos',
    );
  });

  it('las tres de Transito que se piden admiten lo que se les manda (#180)', () => {
    const parametros = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as Record<
      string,
      { readonly obligatorios: readonly string[]; readonly opcionales: readonly string[] }
    >;
    const declara = (clave: string, parametro: string): boolean => {
      const suyos = parametros[clave];
      return suyos !== undefined && [...suyos.obligatorios, ...suyos.opcionales].includes(parametro);
    };

    // `?tamano=` y `?placa=` son los dos unicos parametros que estas hojas mandan hoy. Escribir
    // uno que el contrato no declare seria construir sobre un nombre que nada de este repositorio
    // comprueba — es lo que #26 enseno con `/rentas/predios`.
    expect(declara('GET /transito/papeletas', 'tamano')).toBe(true);
    expect(declara('GET /transito/internamientos', 'tamano')).toBe(true);
    expect(declara('GET /transito/internamientos', 'placa')).toBe(true);
    // Y la ficha del vehiculo lleva la placa EN LA RUTA: no admite ni un parametro.
    expect(parametros['GET /rentas/vehiculos/{placa}']?.obligatorios).toEqual([]);

    expect(RUTAS.papeletas).toBe('/transito/papeletas?tamano=1');
    // La del deposito pasa a componerse con la ventana que su tabla declara (#186): el tamano ya
    // no esta escrito aqui, porque en dos sitios diverge. Sin ventana, la ruta pelada.
    expect(RUTAS.internamientos()).toBe('/transito/internamientos');
    expect(RUTAS.internamientos({ tamano: '20', pagina: '2' })).toBe(
      '/transito/internamientos?tamano=20&pagina=2',
    );
    expect(RUTAS.internamientosDe('T2G/418')).toBe(
      '/transito/internamientos?placa=T2G%2F418&tamano=1',
    );
    expect(RUTAS.vehiculoDe('T2G/418')).toBe('/rentas/vehiculos/T2G%2F418');
    expect(RUTAS.actosDeLaPapeleta('00/41')).toBe('/transito/papeletas/00%2F41/actos');
  });

  it('y `constancias/no-adeudo` se enciende SIN `?formato`: el JSON, no el archivo', () => {
    // El mismo controlador publica las dos. Con `?formato=PDF|XLS|RTF` contesta un `byte[]` con su
    // `Content-Disposition`, que no es lo que una pantalla pinta; el contrato solo declara la
    // forma del JSON. Encender la ruta con el parametro dentro —que es como la declaraba el
    // artboard hasta #169— habria hecho que `laSirveElBackend` no reconociera ninguna de las dos.
    const fuera = YA_SERVIDAS.map((o) => `${o.metodo} ${o.ruta}`);

    expect(fuera).toContain('GET /consultas/constancias/no-adeudo');
    expect(fuera).not.toContain('GET /consultas/constancias/no-adeudo?formato');
  });

  it('la bitacora declara `ejercicio` OBLIGATORIO, y es la unica de las treinta y tres (#181)', () => {
    // Es la medida que abrio #181, y la que justifica que `Conector` tenga una tercera forma de
    // exigir algo. Las otras treinta y una, o no tienen obligatorio, o lo llevan **en la ruta** —y en
    // la ruta no se olvida, porque sin el la URL no existe—. Este va en la cadena de consulta y
    // sale de la SESION: es el unico que se puede omitir sin que la ruta lo note.
    //
    // Y no se deriva de `parametros-de-la-api.json` a proposito: esta escrito, asi que el dia que
    // otra operacion encendida gane un obligatorio fuera de la ruta, esto sale rojo y obliga a
    // mirar si su conector lo manda.
    const parametros = JSON.parse(readFileSync(PARAMETROS, 'utf8')) as Record<
      string,
      { readonly obligatorios: readonly string[] }
    >;

    expect(parametros['GET /seguridad/auditoria']?.obligatorios).toEqual(['ejercicio']);

    const conObligatorioFueraDeLaRuta = YA_SERVIDAS.filter(
      (o) => (parametros[`${o.metodo} ${o.ruta}`]?.obligatorios ?? []).length > 0,
    ).map((o) => `${o.metodo} ${o.ruta}`);

    expect(conObligatorioFueraDeLaRuta).toEqual([
      // `?contribuyente=` de la ficha unificada, que lo lleva desde #169 y sale de la RUTA de la
      // hoja: `#/con-panel/00000025673`. Es la segunda forma, y por eso no hizo falta la tercera.
      'GET /consultas/unificada',
      'GET /consultas/constancias/no-adeudo',
      'GET /seguridad/auditoria',
    ]);
  });

  it('y la bitacora se pide CON su ejercicio dentro, y con su ventana', () => {
    // El numero entra como numero y no como texto: con la firma de texto, un
    // `String(sesion.ejercicioDeTrabajo)` sobre el nulo medido de la instalacion saldria a la red
    // como `?ejercicio=null` — un 422, y no un rojo del compilador. Ver `RUTAS.bitacoraDe`.
    expect(RUTAS.bitacoraDe(2026)).toBe('/seguridad/auditoria?ejercicio=2026');
    // Y la ventana entra aparte desde #186: el tamano lo declara la tabla, no esta ruta.
    expect(RUTAS.bitacoraDe(2026, { tamano: '20', pagina: '3' })).toBe(
      '/seguridad/auditoria?ejercicio=2026&tamano=20&pagina=3',
    );
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
    const { MODULOS_MEDIDOS, ACCESOS_MEDIDOS } = await import('../src/datos/seguridadMedida.ts');

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
    // interfaz —con dos operaciones de ADMINISTRACION— sobraria. Hoy no lo publica: cero
    // operaciones cuyo nombre hable de un menu, un arbol o una navegacion.
    //
    // Que las dos sean de administracion no es una suposicion, y esta medido en
    // `src/datos/servidas.ts`: `GET /seguridad/modulos` declara `@RequiereAcceso(acceso =
    // "modulos")` y `GET /seguridad/accesos`, `acceso = "accesos"` — «Modulos del sistema» y
    // «Accesos y politicas». Hasta #282 esto mandaba a ver un SinArbol.tsx —escrito sin comillas
    // invertidas, porque con ellas la guarda de #255 vuelve a dispararse sobre la cita de la
    // cita—: era la pantalla con que la V6 contaba este caso (#33, AC7) y salio del arbol con
    // ella en #90, de modo que `src/marco/` esta en la lista de `la-v6-no-esta.test.ts` y no
    // puede volver. Lo que la V8 hace en su lugar —desde #311, nombrar las opciones que faltan y
    // ofrecer reintentar, y solo en el 403 `SIN_PRIVILEGIO`— esta en `src/datos/servidas.ts`.
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

    // Un `salvo: ['src/api/']` la apagaria justo en el unico directorio donde hay un token.
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
    //
    // El `salvo` es una LISTA desde #137, que es como lo publica `@kamayuk/verificaciones`; lo
    // que este arbol comprueba es que la lista siga teniendo un solo sitio dentro.
    expect([...(delFetch?.salvo ?? [])]).toEqual(['src/api/']);
    expect(PROHIBICIONES.filter((p) => p.salvo !== undefined)).toHaveLength(1);
  });
});

/**
 * Las capturas de la instalacion, y la guarda que impide que se conviertan en respaldos.
 *
 * Son TRES desde I-4: la sesion (quien esta dentro), la seguridad (que modulos hay y que puede
 * abrir esta cuenta) y el padron (que contesta `GET /rentas/contribuyentes` a diez mil filas).
 * Las tres son lo mismo —bytes de un `curl`, para que las pruebas no repitan literales— y las
 * tres tienen el mismo riesgo, que es el peor que hay en `src/` porque **parecen datos
 * legitimos**: un respaldo hecho con ellas no se ve como una invencion, se ve como un dato
 * medido. Un `arbol ?? ARBOL_MEDIDO` devolveria la navegacion constante que I-3 vino a quitar,
 * y un `padron.dato ?? PAGINA_0_FILAS` ensenaria contribuyentes de Catacaos con el token de
 * cualquier otra municipalidad.
 */
const CAPTURAS = [
  'src/datos/sesionMedida.ts',
  'src/datos/seguridadMedida.ts',
  'src/datos/backendMedido.ts',
  // La cuarta no es una captura sino respuestas construidas desde el contrato (#169), y corre el
  // MISMO riesgo: un `ficha ?? FICHA` ensenaria la cuenta corriente de un contribuyente inventado
  // con la cara de un dato medido. Ver su javadoc, que dice lo que sostiene y lo que no.
  'src/datos/conectores/consultasDeMuestra.ts',
  // Y la quinta, por lo mismo (#179): respuestas construidas desde el contrato y el codigo de los
  // seis controladores de fiscalizacion. Un `resolucion ?? RESOLUCION_SIN_CIFRAS` ensenaria la
  // determinacion de un contribuyente inventado con la cara de un dato medido.
  'src/datos/conectores/fiscalizacionDeMuestra.ts',
];

describe('las capturas de la instalacion son de las pruebas, y no respaldos de produccion', () => {
  it('EL CENTINELA: la lista nombra archivos que existen, y no se deja ninguno fuera (#277)', () => {
    // Las dos primeras entradas dijeron `src/marco/` hasta #277, y `src/marco/` salio del arbol
    // con la V6 (#90): los dos archivos viven en `src/datos/`. La guarda seguia mordiendo **por
    // casualidad**, porque casa por NOMBRE de archivo y no por ruta, asi que la ruta falsa no
    // producia ningun rojo. Dos consecuencias, y las dos callaban: el `ruta !== captura` de abajo
    // dejaba de excluir la captura de si misma, y una TERCERA entrada mal escrita habria pasado
    // igual de inadvertida.
    //
    // La lista se sigue escribiendo a mano —cada entrada lleva su motivo, y eso no sale del
    // disco—, pero se cruza con el barrido: `deProduccion` ya se deriva de `src/` con
    // `readdirSync`, y el nombre de un dato medido o construido es su propia senal. Asi la lista
    // no puede nombrar lo que no existe, ni dejarse fuera una captura nueva.
    const conNombreDeCaptura = deProduccion
      .filter((ruta) => /(Medid[oa]|DeMuestra)\.ts$/.test(ruta))
      .sort();

    expect(
      conNombreDeCaptura,
      'La lista de capturas y los archivos que el arbol tiene no cuadran.\n\n' +
        '  Si sobra una entrada: nombra una ruta que no existe —se movio, o se escribio mal— y\n' +
        '  esta guarda lleva desde entonces vigilando un archivo por su nombre a secas.\n' +
        '  Si falta una: hay un dato medido o construido desde el contrato que nadie vigila, y es\n' +
        '  el peor material que hay en `src/` porque PARECE un dato legitimo. Anadela CON SU\n' +
        '  MOTIVO, como las cinco de arriba.',
    ).toEqual([...CAPTURAS].sort());
  });

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
    },
  );

  it('y lo que declara es lo que contesta la instalacion: sin ejercicio de trabajo', async () => {
    const { SESION_MEDIDA } = await import('../src/datos/sesionMedida.ts');

    // `null` no es una eleccion del archivo: es lo que contesta el backend, y es el caso que el
    // AC8 obliga a no mentir. Una muestra con un `2026` dentro lo dejaria sin ejercitar.
    expect(SESION_MEDIDA.ejercicioDeTrabajo).toBeNull();
  });
});

// @vitest-environment node
//
// Lee el DISCO y nada mas: los fuentes de `frontend/` enteros —`src/`, `verificaciones/`, `e2e/`,
// `desarrollo/` y los de la raiz— y la lista de archivos que hay debajo. No importa ni un modulo
// del arbol, porque lo que mide es lo que los comentarios DICEN y no lo que el codigo hace.

import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

/**
 * **Ningun javadoc delega su justificacion en un archivo que no existe** (#255).
 *
 * <h2>De que defecto viene, medido</h2>
 *
 * Tres sitios de `src/` citaban `secciones/determinacion.ts` como **donde esta la medida o el
 * razonamiento** de lo que afirmaban, y ese archivo salio del arbol con la V6 en #90. El mas caro
 * era el de `DeterminacionDeAlcabala`: decia que la regla 9 impide dibujar sus dos importes
 * «medido y razonado» en un archivo que no se puede abrir, de modo que quien llegara a conectar la
 * hoja se encontraba una prohibicion que **no podia comprobar y tampoco levantar**. Las dos
 * salidas eran malas: creersela sin verla, o rehacer la medida desde cero.
 *
 * Y no rompe nada: compila, las pruebas pasan, y lo que se lee es una afirmacion con su respaldo
 * aparente. Es exactamente lo que `CLAUDE.md` distingue —un comentario que dice de que defecto
 * viene una guarda **es el motivo por el que el codigo de al lado es como es**; uno que manda a un
 * archivo que no existe deja el motivo sin sujeto—.
 *
 * <h2>Por que DELEGACION y no «toda ruta citada»</h2>
 *
 * Porque la regla ancha se mide y **no se sostiene**. Barriendo todas las citas a un `*.ts(x)` en
 * los comentarios de `src/` salen **235**; exigiendo que todas resuelvan, **22** dan rojo y solo
 * **4** son el defecto. Las otras 18 son prosa deliberada que nombra lo que YA NO esta —«Hubo un
 * segundo arbol —`src/marco/arbol.ts`— y ya no esta», «Vivia en `api/proxy.ts`, que salio con la
 * V6 (#90)»—, que es la misma clase de comentario que `CLAUDE.md` protege cuando nombra el
 * monolito. Una guarda con 18 rojos sobre codigo bueno se acaba desactivando.
 *
 * Lo que separa las dos cosas es el **verbo**: «lo comprueba X», «lo vigila X», «medido en X»,
 * «ver X» prometen que la medida esta AHI; «vivia en X», «salio con la V6» cuentan donde estuvo.
 * Medido con esa regla: **64 citas delegadas** y **cero falsos rojos** —ninguna cita a la libreria
 * hermana es una delegacion, asi que esto no necesita el clon de `kamayuk-lib` para correr—.
 *
 * <h2>Lo que esto NO comprueba, y se dice</h2>
 *
 * Que lo que hay en el archivo citado sea de verdad la medida que se promete. Eso no lo lee una
 * maquina: lo lee la revision. Aqui se cierra la mitad que si es mecanica —que el sujeto exista—,
 * que es la que se pierde en silencio cuando un archivo se retira.
 *
 * Y una cita **sin barra** —«`conectores.ts`»— se resuelve por sufijo contra el arbol entero, asi
 * que un nombre suelto casa con cualquier archivo que se llame igual. Es deliberado: exigir la
 * ruta completa en cada javadoc daria rojos sobre prosa correcta.
 *
 * <h2>Por que barre `frontend/` entero y no solo `src/` (#282)</h2>
 *
 * Porque nacio mirando `src/` —que es de donde venian las tres citas— y **la cuarta aparecio en el
 * unico sitio que no miraba**. Medido con este mismo patron sobre los otros arboles antes de
 * ensanchar: `verificaciones/` tenia **10 delegaciones y una rota** —`camino-a-la-api.test.ts`
 * mandaba a un SinArbol.tsx que salio del arbol con la V6 en #90, y que `la-v6-no-esta.test.ts`
 * impide que vuelva—, y `e2e/` tenia **2 y ninguna rota**. O sea que una guarda escrita para que la
 * prosa no se quede sin sujeto se estaba saltando el arbol que mas prosa tiene por linea de codigo:
 * el de las guardas.
 *
 * El alcance de hoy **no es una lista de directorios**: es el arbol entero de `frontend/` menos
 * `node_modules` y lo oculto, que es lo mismo que `archivosDelArbol()` ya devolvia para resolver
 * las citas. Asi un directorio nuevo entra solo, que es justo lo que no paso con `verificaciones/`.
 *
 * <h2>La quinta familia: «sigue en `X`», y por que se ensancho (#282)</h2>
 *
 * Las cuatro primeras prometen que **la medida** esta ahi. Falta la que promete que **la cosa**
 * esta ahi: «la suma exacta en centimos con la que se haria sigue en dominio/aritmetica.ts». Esa
 * frase se quedo mintiendo cuando #279 retiro el archivo, y esta guarda salio **verde** sobre ella
 * —medido en #262—, porque «sigue en» no era verbo de ninguna de las cuatro.
 *
 * Las dos citas de esta seccion van **sin comillas invertidas** a proposito: con ellas, esta guarda
 * se muerde a si misma, porque no distingue una delegacion de la cita de una delegacion retirada.
 * Lo aprendio #255 en su primera corrida verde, y queda escrito para que nadie se las «arregle».
 *
 * Es el mismo defecto y el mismo remedio: una afirmacion en **presente** cuyo sujeto ya no se puede
 * abrir. Y lo que decide no es que suene bien, sino la medida: la familia locativa en presente
 * —«sigue/siguen», «esta/estan», «vive/viven», «queda/quedan» en `X`— casa **20 citas en el arbol
 * entero y ninguna deja de resolver**. Cero falsos rojos, asi que se ensancha. Si hubiera salido
 * como la regla ancha de #255 —22 rojos para 4 defectos— no se habria ensanchado y esta seccion
 * diria eso, con el numero. Lo que si trajo fue **una cita al clon hermano**, la primera —#255
 * habia medido que no habia ninguna—: `archivosDeLaLibreria()` cuenta por que el resolvedor la
 * admite en vez de darla por rota.
 *
 * El barrido entero, con las cinco familias, leido sobre `dd64f02`: **115 delegaciones y 0 rotas**
 * —`src/` 82, `verificaciones/` 25, `e2e/` 4 y la raiz 4—, repartidas en «ver» 52, «lo comprueba»
 * 24, la locativa 22, «`X` comprueba» 13 y «medido en» 4. Ocho de las 115 citan un NOMBRE de
 * guarda y no una ruta. Es una lectura de ESE arbol y no un contrato: la renueva quien vuelva a
 * correr esto, y sube sola con cada PR que escriba prosa.
 *
 * El **pasado** se queda fuera a proposito, y es la mitad que hace que esto no sea la regla ancha:
 * «vivia en `api/proxy.ts`», «estaba en `src/marco/`» cuentan donde ESTUVO algo, que es la clase de
 * comentario que `CLAUDE.md` protege. El `\s+en` que sigue al verbo es lo que los separa —«vivia»
 * no es «vive»—, y no hay que fiarse de esta nota: `sabe decir que no` lo ejercita.
 */

const AQUI = dirname(fileURLToPath(import.meta.url));
const FRONTEND = join(AQUI, '..');

/**
 * **El verbo que convierte una cita en una delegacion.**
 *
 * Cinco familias, y las cinco salen de la prosa que ya hay en el arbol. Van **separadas y no en
 * una alternancia** desde #282: en una sola expresion, la alternativa que casa primero se come el
 * texto y esconde a la siguiente —«a.ts, que comprueba lo que b.ts mide», escrito con sus comillas
 * invertidas, daba UNA cita y no dos; van sin ellas aqui porque si no esta guarda se muerde a si
 * misma—, y cada familia tiene ademas su propio grupo sin que haya que contar parentesis.
 */
/**
 * **Lo que puede ir citado**: una ruta de archivo, o el NOMBRE de una guarda sin extension (#282).
 *
 * Lo segundo llego con #288: `pantallas-del-artboard.test.ts` decia «lo comprueba
 * \`las-piezas-del-consumidor-son-las-que-el-artboard-declara\`, al final de este archivo», y esa
 * guarda **no existe** —ni con ese nombre ni en ese archivo, cuyos cuatro `describe` son otros—.
 * Mismo defecto de #255 y misma prosa, pero sin `.ts` detras, asi que el patron de rutas no la veia.
 *
 * **La forma es kebab de tres segmentos o mas, con directorios opcionales delante, y eso tambien se
 * midio.** Admitir cualquier cosa entre comillas detras del verbo da **13 citas y once serian falsos
 * rojos**: «mide \`yarn build\`», «mide \`#111213\`», «lo prueba \`LaCostaCaeEnSuActoTest\`» —una
 * prueba de Java, en otro repositorio—, «comprueba \`hayMas\`» —un campo—, «mide \`pct\`» —una
 * variable—, «comprueba \`typeof ResizeObserver !== 'undefined'\`» —una expresion— y las cuatro
 * \`X\` de este mismo javadoc. Con la forma de nombre de guarda salen **6 citas, cinco resuelven y
 * una no**, y la que no es el defecto. Cero falsos rojos, asi que se ensancha; con once no se habria
 * ensanchado y esto diria eso, con el numero.
 */
const CITA = String.raw`[^\`]+\.tsx?|(?:[a-z0-9-]+\/)*[a-z0-9]+(?:-[a-z0-9]+){2,}`;

/** Arma el patron de una familia poniendo `CITA` donde el fuente escribe `«CITA»`. */
function conLaCita(fuente: string): RegExp {
  return new RegExp(fuente.replace('«CITA»', CITA), 'g');
}

const FAMILIAS: readonly { readonly nombre: string; readonly patron: RegExp }[] = [
  /** «lo comprueba `X`», «lo vigila `X`», «lo prueba `X`», «lo mide `X`». */
  {
    nombre: 'lo comprueba `X`',
    patron: conLaCita(String.raw`(?:lo |la |las |los )?(?:comprueban?|vigilan?|prueban?|miden?|mide)\s+\`(«CITA»)\``),
  },
  /** «medido en `X`», «razonado en `X`», «escrito en `X`», «se comprueba en `X`». */
  {
    nombre: 'medido en `X`',
    patron: conLaCita(
      String.raw`(?:medido|razonado|escrito|se comprueba|se mide|se prueba|se vigila)\s+(?:y \w+\s+)?en\s+\`(«CITA»)\``,
    ),
  },
  /** «Ver `X`». */
  { nombre: 'ver `X`', patron: conLaCita(String.raw`\b[Vv]er\s+\`(«CITA»)\``) },
  /** La vuelta: «`X` comprueba campo a campo», que es como lo escribe `lecturas.ts`. */
  {
    nombre: '`X` comprueba',
    patron: conLaCita(String.raw`\`(«CITA»)\`(?:\*\*)?,?\s+(?:lo |que )?(?:comprueba|vigila|prueba|mide)\b`),
  },
  /**
   * La locativa, en PRESENTE: «sigue en `X`», «esta en `X`», «vive en `X`», «queda en `X`» (#282).
   *
   * Promete que la cosa esta ahi, no que la medida lo este — y se queda mintiendo igual. El
   * pasado —«vivia en `X`»— no entra: el `\s+` que sigue al verbo lo excluye, y es lo que separa
   * esta familia de la regla ancha que #255 midio y descarto.
   */
  {
    nombre: 'sigue en `X`',
    patron: conLaCita(
      String.raw`\b(?:sigue|siguen|est[aá]|est[aá]n|vive|viven|queda|quedan)\s+(?:hoy\s+)?en\s+\`(«CITA»)\``,
    ),
  },
];

/**
 * Todo lo que hay bajo `frontend/`, menos `node_modules`. Es contra esto que una cita resuelve.
 *
 * Con `readdirSync` recursivo y no con `globSync`, que en Node 22 sigue siendo experimental y
 * escupe un `ExperimentalWarning` por corrida: una guarda que ensucia la salida de `yarn
 * verificar` se acaba leyendo como ruido, y este archivo no necesita patrones para nada.
 */
function archivosDelArbol(desde = '.'): ReadonlySet<string> {
  const salida = new Set<string>();
  for (const entrada of readdirSync(join(FRONTEND, desde), { withFileTypes: true })) {
    if (entrada.name === 'node_modules' || entrada.name.startsWith('.')) continue;
    const relativa = desde === '.' ? entrada.name : `${desde}/${entrada.name}`;
    if (entrada.isDirectory()) {
      for (const dentro of archivosDelArbol(relativa)) salida.add(dentro);
    } else {
      salida.add(relativa);
    }
  }
  return salida;
}

/**
 * Los fuentes del clon hermano, o el conjunto vacio si no esta en el disco.
 *
 * **Hasta #282 esto no hacia falta, y ahora si**: #255 midio que ninguna delegacion de entonces
 * citaba `kamayuk-lib`, y lo dejo escrito. La familia locativa trae la primera —`bloques.ts` dice
 * que `esBloque` «vive en `interprete/componer.ts`», y **es verdad**: vive ahi, en el paquete
 * enlazado con `link:../../kamayuk-lib/paquetes/*`—. Resolver solo contra este arbol la daria por
 * rota, que es un falso rojo sobre prosa exacta.
 *
 * Si el clon no esta, esto devuelve el conjunto vacio y una cita asi cae. No es un modo de fallo
 * nuevo: sin el clon, `yarn verificar` ya se paro antes en `enlace-con-kamayuk-lib.test.ts`, que
 * es quien nombra el `git clone` que falta.
 */
function archivosDeLaLibreria(): ReadonlySet<string> {
  const raiz = join(FRONTEND, '..', '..', 'kamayuk-lib', 'paquetes');
  if (!existsSync(raiz)) return new Set<string>();
  const salida = new Set<string>();
  const bajar = (desde: string): void => {
    for (const entrada of readdirSync(join(raiz, desde), { withFileTypes: true })) {
      if (entrada.name === 'node_modules' || entrada.name.startsWith('.')) continue;
      const relativa = desde === '.' ? entrada.name : `${desde}/${entrada.name}`;
      if (entrada.isDirectory()) bajar(relativa);
      else salida.add(relativa);
    }
  };
  bajar('.');
  return salida;
}

/**
 * Cada archivo, con su nombre y **tambien sin su extension**.
 *
 * Es lo que hace que una cita por NOMBRE DE GUARDA —«lo comprueba `la-siembra-abre-los-destinos`»,
 * sin el `.test.tsx`— resuelva con el mismo `resuelve` que una ruta, en vez de con una segunda
 * rama que habria que mantener al lado.
 */
function conYSinExtension(archivos: ReadonlySet<string>): ReadonlySet<string> {
  const salida = new Set<string>(archivos);
  for (const uno of archivos) {
    const pelado = uno.replace(/\.(?:test|spec)\.tsx?$/, '').replace(/\.tsx?$/, '');
    if (pelado !== uno) salida.add(pelado);
  }
  return salida;
}

/** Se lee una vez: el clon hermano no cambia mientras corre la suite. */
const DE_LA_LIBRERIA = conYSinExtension(archivosDeLaLibreria());

/**
 * Una cita resuelve si algun archivo real termina con ella, en frontera de segmento.
 *
 * «Real» es de este arbol **o del clon hermano**: los seis paquetes de `@kamayuk/*` estan en el
 * disco por el `link:`, no en un registro, asi que citar uno de sus archivos es citar algo que se
 * puede abrir.
 */
function resuelve(cita: string, reales: ReadonlySet<string>): boolean {
  const limpia = cita.replace(/^\.\//, '');
  for (const real of reales) if (real === limpia || real.endsWith(`/${limpia}`)) return true;
  for (const real of DE_LA_LIBRERIA) if (real === limpia || real.endsWith(`/${limpia}`)) return true;
  return false;
}

interface Delegacion {
  readonly archivo: string;
  readonly linea: number;
  readonly cita: string;
  readonly frase: string;
  readonly familia: string;
}

/**
 * Lo que un bloque de prosa delega, con la familia que lo dijo.
 *
 * Se saca aparte para que los centinelas puedan ejercitar el patron sobre una frase escrita a
 * mano: sin esto, «el pasado no dispara» solo se podria afirmar.
 */
function citasDe(texto: string): readonly { readonly cita: string; readonly frase: string; readonly familia: string }[] {
  const salida: { cita: string; frase: string; familia: string }[] = [];
  for (const { nombre, patron } of FAMILIAS) {
    for (const encontrado of texto.matchAll(new RegExp(patron.source, 'g'))) {
      salida.push({ cita: encontrado[1] ?? '', frase: encontrado[0], familia: nombre });
    }
  }
  return salida;
}

/**
 * Las delegaciones de todos los comentarios de `frontend/`.
 *
 * **Todos**, y no los de `src/`: desde #282 el barrido es el arbol entero menos `node_modules` y
 * lo oculto, porque la cuarta cita rota vivia en `verificaciones/`, que es justo lo que la version
 * anterior no miraba.
 *
 * El barrido es por **bloque de comentario** y no por linea, porque el verbo y la cita se separan
 * al ajustar el margen: `laVentana.ts` escribe «Lo vigila\n * `verificaciones/…`». Leyendo linea a
 * linea, esa —que era una de las rotas— no se veia.
 */
function delegaciones(): readonly Delegacion[] {
  const salida: Delegacion[] = [];
  for (const rel of [...archivosDelArbol()].filter((uno) => /\.tsx?$/.test(uno))) {
    const lineas = readFileSync(join(FRONTEND, rel), 'utf8').split('\n');
    let bloque: string[] | null = null;
    let inicio = 0;
    const cerrar = (): void => {
      if (bloque === null) return;
      const texto = bloque.join(' ').replace(/\s+/g, ' ');
      for (const encontrado of citasDe(texto)) salida.push({ archivo: rel, linea: inicio, ...encontrado });
      bloque = null;
    };
    lineas.forEach((linea, i) => {
      if (/^\s*(\/\/|\*|\/\*)/.test(linea)) {
        if (bloque === null) {
          bloque = [];
          inicio = i + 1;
        }
        bloque.push(linea.replace(/^\s*(\/\/|\*\/|\/\*\*?|\*)/, ''));
      } else {
        cerrar();
      }
    });
    cerrar();
  }
  return salida;
}

describe('ningun javadoc delega en un archivo que no existe (#255)', () => {
  it('EL CENTINELA: hay delegaciones que comprobar, y el barrido las ve', () => {
    // Sin esto, el dia que el patron deje de casar —un margen distinto, un verbo nuevo— esto
    // pasaria sobre la lista vacia y seguiria en verde sin comprobar nada, que es como una
    // barrera se apaga sin que nadie la borre.
    const todas = delegaciones();
    expect(todas.length).toBeGreaterThanOrEqual(70);
    // Y una concreta que se sabe buena, para que el centinela no se conforme con el numero.
    expect(todas.map((una) => una.cita)).toContain('verificaciones/camino-a-la-api.test.ts');
  });

  it('EL CENTINELA: los TRES arboles aportan, y la familia locativa tambien', () => {
    // El de arriba no basta desde #282, y este es el motivo: con `src/` aportando setenta y tantas
    // delegaciones, el dia que `verificaciones/` o `e2e/` dejen de barrerse —un filtro nuevo, un
    // `readdirSync` que no los encuentre— el recuento sigue por encima del tope y la guarda vuelve
    // a ser la de antes **sin que nada se ponga rojo**. Que es exactamente como se colo la cita a
    // un archivo que no existe que este issue vino a cerrar.
    const todas = delegaciones();
    for (const arbol of ['src/', 'verificaciones/', 'e2e/']) {
      expect(
        todas.filter((una) => una.archivo.startsWith(arbol)).length,
        `el barrido dejo de ver \`${arbol}\``,
      ).toBeGreaterThanOrEqual(1);
    }
    // Y lo mismo para la familia que #282 anadio: con las otras cuatro dando setenta, que la
    // locativa deje de casar no mueve el recuento de arriba ni un poco.
    expect(
      todas.filter((una) => una.familia === 'sigue en `X`').length,
      'la familia locativa dejo de casar',
    ).toBeGreaterThanOrEqual(10);
    // Y lo mismo para las citas por NOMBRE de guarda, sin extension: son seis entre ciento y pico,
    // asi que su desaparicion no mueve ningun recuento — y fue la forma del defecto de #288.
    expect(
      todas.filter((una) => !/\.tsx?$/.test(una.cita)).length,
      'dejaron de verse las citas por nombre de guarda',
    ).toBeGreaterThanOrEqual(4);
  });

  it('EL CENTINELA: el pasado NO dispara, y el presente si', () => {
    // La mitad que hace que esto no sea la regla ancha de #255. Si el patron dejara de distinguir
    // el tiempo verbal, la prosa que cuenta donde ESTUVO algo —la que `CLAUDE.md` protege— pasaria
    // a dar rojo, y una guarda con rojos sobre codigo bueno se acaba desactivando. Medido: con
    // «vivia» admitido en la familia locativa salen **cuatro** rojos sobre prosa correcta, y uno
    // de ellos es el ejemplo de este mismo javadoc.
    //
    // Las frases van en **minuscula** a proposito. Escritas como empiezan una oracion —«Vivia en
    // …»— este centinela pasa aunque el patron admita el pasado, porque el patron distingue
    // mayusculas: se midio, y con el pasado metido a mano las cuatro comprobaciones de arriba
    // seguian verdes mientras la de abajo caia. Un centinela que no puede fallar no protege nada.
    expect(citasDe('lo que vivia en `api/proxy.ts` salio con la V6 (#90).')).toEqual([]);
    expect(citasDe('lo que estaba en `src/marco/SinArbol.tsx` hasta #90.')).toEqual([]);
    expect(citasDe('lo que estuvo en `src/ds/Boton.tsx` lo publica la libreria.')).toEqual([]);
    expect(citasDe('La captura sigue en `datos/seguridadMedida.ts`.').map((una) => una.cita)).toEqual([
      'datos/seguridadMedida.ts',
    ]);
  });

  it('EL CENTINELA: el resolvedor sabe decir que no', () => {
    // Un `resuelve` que dijera que si a todo dejaria la prueba de abajo en verde para siempre.
    const reales = conYSinExtension(archivosDelArbol());
    expect(resuelve('src/datos/lecturas.ts', reales)).toBe(true);
    expect(resuelve('secciones/determinacion.ts', reales)).toBe(false);
    // Y desde #282 tambien el clon hermano, que es donde vive lo que `bloques.ts` cita. Si esto
    // dejara de leerse —el clon movido, el `paquetes/` renombrado— la cita de `componer.ts`
    // pasaria a dar un rojo que no es un defecto, y una guarda asi se acaba desactivando.
    expect(DE_LA_LIBRERIA.size, 'no se leyo el clon hermano').toBeGreaterThanOrEqual(100);
    expect(resuelve('interprete/componer.ts', reales)).toBe(true);
    expect(resuelve('interprete/componer-que-no-existe.ts', reales)).toBe(false);
    // Y un NOMBRE de guarda, sin extension, en las dos direcciones.
    expect(resuelve('la-siembra-abre-los-destinos', reales)).toBe(true);
    expect(resuelve('las-piezas-del-consumidor-son-las-que-el-artboard-declara', reales)).toBe(false);
  });

  it('y todas resuelven a un archivo de este arbol', () => {
    const reales = conYSinExtension(archivosDelArbol());
    const rotas = delegaciones().filter((una) => !resuelve(una.cita, reales));

    expect(
      rotas.map((una) => `  ${una.archivo}:${una.linea}  «${una.frase}»`),
      'UN JAVADOC DELEGA EN UN ARCHIVO QUE NO EXISTE:\n' +
        `${rotas.map((una) => `  ${una.archivo}:${una.linea}  [${una.familia}] «${una.frase}»`).join('\n')}\n\n` +
        '  Ese comentario afirma algo y dice que la medida o el razonamiento estan en ese\n' +
        '  archivo. Sin el archivo, la afirmacion se queda sin sujeto: quien la lea solo puede\n' +
        '  creersela sin verla o rehacer la medida desde cero. Fue el defecto de las tres citas\n' +
        '  a `secciones/determinacion.ts`, que salio con la V6 en #90 y sobrevivio en la prosa.\n\n' +
        '  Tres salidas, y las tres valen: apuntar a donde SI esta la medida en este arbol;\n' +
        '  hacer la medida que falta; o reescribir la afirmacion para que se sostenga sola.\n' +
        '  Inventar otra referencia que tampoco se pueda abrir, no.\n\n' +
        '  Y si lo que quieres es contar donde ESTUVO algo —«vivia en `api/proxy.ts`, que salio\n' +
        '  con la V6»—, eso no es una delegacion y esta guarda no lo mira: lo que la dispara es\n' +
        '  el verbo que promete que la medida esta ahi.',
    ).toEqual([]);
  });
});

import {
  coordenada,
  type Ausencia,
  type Coordenada,
  type DatoConNombre,
  type FilaDeLaTabla,
} from '@kamayuk/ui';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import type { CorridaDelPredial } from './lecturas.ts';
import { RUTAS, pedirUno } from './lecturas.ts';
import { CONECTORES_DE_LICENCIAS } from './conectores/licencias.ts';
import { CONECTORES_DE_COACTIVA } from './conectores/coactiva.ts';
import { CONECTORES_DE_INICIO } from './conectores/inicio.ts';
import { CONECTORES_DE_CONSULTAS } from './conectores/consultas.ts';
import { CONECTORES_DE_FISCALIZACION } from './conectores/fiscalizacion.ts';
import { CONECTORES_DE_TRANSITO } from './conectores/transito.ts';
import { CONECTORES_DE_SEGURIDAD } from './conectores/seguridad.ts';

/**
 * **Que pantalla pide que, y que de lo que llega dibuja cada campo** (#97, #169).
 *
 * <h2>Eran DOS de siete, y son CUATRO: las dos de Consultas entraron en #169</h2>
 *
 * Y con #179 son **quince conectores**: las tres hojas de Fiscalizacion —`fis-prog`, `fis-actas`
 * y `fis-res`— entran juntas, las tres **de tabla**. Su conector vive aparte, en
 * `conectores/fiscalizacion.ts`, y una de ellas obligo a corregir la declaracion del arbol otra
 * vez: `fis-prog` declaraba dos operaciones con `{id}` en la ruta y ninguna que publicara un `id`.
 * Ahi tambien se estrena la segunda razon de hueco que este archivo conocia: no que la operacion
 * no publique el campo, sino que lo publique **vacio** —D-02a—, que no es lo mismo y no se dice
 * con la misma palabra.
 *
 * `con-panel` y `con-doc` no estaban en la lista de abajo por un motivo que resulto ser falso:
 * **la hoja declaraba la operacion equivocada**. `con-panel` decia
 * `GET /consultas/cuenta-corriente/{codigo}` —una pagina de asientos, y esta hoja no tiene tabla—
 * cuando sus ocho campos son `resumenDeSaldos` de `GET /consultas/unificada`. Corregida la
 * declaracion (ver `pantallas/arbol.ts`), las dos se pintan. Su conector vive aparte, en
 * `conectores/consultas.ts`.
 *
 * <h2>Por que las otras cinco de las siete siguen sin pedir</h2>
 *
 * Siete hojas declaraban alguna operacion servida. Pero «servida» no es «puede pintarse», y la
 * diferencia se midio campo a campo:
 *
 *   · **`seg-panel`** declara TRES servidas —modulos, sesion y municipalidad— y **ninguna publica
 *     nada de lo que la pantalla ensena**: usuarios registrados, activos, contrasenas caducadas.
 *   · **`valores`** declara `GET /rentas/beneficios`, y lo que ensena son UIT e intereses, que son
 *     parametros normativos. Otro contexto.
 *   · **`predios`** necesita **un contribuyente elegido** para pedir su ficha y sus predios, y
 *     esta pantalla todavia no tiene con que elegirlo.
 *   · **`seg-acc`** podria dar tres de las cinco columnas de su tabla; las otras dos —origen y
 *     sensible— no las publica nadie, y la matriz de permisos es, medido, **una bolsa de codigos
 *     planos**: no distingue propios de heredados, que es justo lo que la pantalla pregunta.
 *
 * <h2>Y `seg-aud`, que no estaba en esas siete y entra con #181</h2>
 *
 * No estaba porque su unica operacion —`GET /seguridad/auditoria`— **no estaba servida**: la lista
 * de `servidas.ts` la enciende en #181. Al encenderla resulto ser la primera con un parametro
 * **obligatorio que no va en la ruta**, y de ahi sale `Conector.exigeEjercicio`. Su reparto, y por
 * que la columna «Riesgo» no la publica nadie, en `conectores/seguridad.ts`.
 *
 * Las otras tres hojas de Seguridad siguen fuera, y `seg-panel` y `seg-acc` con el motivo de
 * arriba. `seg-sis` no entra porque su unica servida es **la escritura** —`PUT
 * /seguridad/sesion/ejercicio`—, y un PUT no dibuja una pantalla.
 *
 * Se hacen enteras y bien las que pueden. Las que no, lo dicen — ver `porQueNoHayDato.ts`.
 *
 * <h2>Lo que NO se hace, y es la regla que gobierna este archivo</h2>
 *
 * **No se calcula un agregado que la operacion no publica.** `coa-panel` pregunta «con REC
 * notificada», «con medida cautelar» y «sin REC»: contarlos sobre la pagina que llega daria un
 * numero, y ese numero seria **indistinguible de uno real**. Y «deuda en cartera» sumada sobre una
 * pagina de veinte de un total de cientos seria sencillamente falsa.
 *
 * Un hueco que dice «no publicado» es informacion: dice a quien mantiene el backend exactamente
 * que le falta. Un cero calculado mal no es informacion, es una mentira con formato.
 */

/** Lo que una pantalla saca de una respuesta. */
export interface Reparto {
  /** Los campos de solo lectura que SI salen de lo que llego. */
  readonly valores: ReadonlyMap<Coordenada, string>;
  /** Las filas de la tabla de un bloque, **por indice de bloque**. Sus celdas son cadenas. */
  readonly filas: ReadonlyMap<number, readonly (readonly string[])[]>;
  /**
   * Las filas de una tabla **con `clave`**, por esa clave (`kamayuk-lib`#87, #180).
   *
   * <h2>Por que hay dos caminos y no uno</h2>
   *
   * Porque `filas` —el de arriba, el de #97— lleva `readonly string[]` por fila, y una cadena **no
   * puede decir que no hay dato**: `''` se lee como un blanco y `'—'` como una raya muda. El
   * camino de la clave lleva `CeldaDeLaTabla`, que ademas de la cadena admite `{ texto: null,
   * nota }` — y entonces el interprete escribe la palabra que su tabla declara en `sinDato` y
   * **anuncia el motivo en la celda**.
   *
   * **Y es ademas el unico camino por el que viaja el total publicado** (#172): ver
   * `TablaRepartida.total`. Una tabla que quiera decir «20 de 1 842» declara `clave`, y con eso el
   * total tiene un solo sitio donde vivir en vez de dos que se contradigan.
   *
   * **Es aditivo y no sustituye a nada**: una tabla sin `clave` sigue tomando sus filas del indice
   * de su bloque, exactamente como antes.
   */
  readonly tablas?: ReadonlyMap<string, TablaRepartida>;
  /**
   * **Lo que el SERVIDOR dijo de la ventana**, por su nombre (#187).
   *
   * Es el canal que `DefinicionDeTabla.paginacion` lee: `hayMas` y `paginas` son **nombres** de
   * `DatosDeLaPantalla.nombrados` y no los datos —el interprete los busca ahi
   * (`TablaDelBloque.tsx`)—, y los nombres se derivan del de la tabla con `hayMasDe` y `paginasDe`
   * para que no haya dos registros paralelos que se desincronicen.
   *
   * Lo que entra aqui es **lo que el envoltorio de la respuesta publica** —`hayMas`,
   * `totalPaginas`—, jamas una cuenta sobre las filas recibidas: con el tope alcanzado, contarlas
   * diria que no hay pagina siguiente justo cuando la hay. Lo vigila
   * `verificaciones/el-total-es-el-que-publica-la-operacion.test.ts`.
   */
  readonly nombrados?: ReadonlyMap<string, DatoConNombre>;
  /**
   * **El dia al que estan las cifras de esta pantalla**, en ISO y sin formatear (#196, regla 9).
   *
   * <h2>Por que hace falta un canal y no vale un campo mas</h2>
   *
   * Porque regla 9 —RNF-075— dice que **toda cifra mostrada indica su fecha**, y hay pantallas
   * cuyo artboard no dibuja ningun campo donde decirla: `con-panel` tiene «Fecha de cálculo» y
   * `fis-panel` **no tiene ninguno de sus seis libre**. Inventarle un septimo seria cambiar el
   * artboard para que quepa un dato, que es al reves de como se decide aqui.
   *
   * Asi que se dice **una vez, arriba**: `useDatosDeLaHoja` lo mete en la frase de pantalla que el
   * interprete ya dibuja. Lo que viaja por aqui es la fecha **cruda** y no la frase, por lo mismo
   * que `TablaRepartida.totalElementos` viaja como numero: un conector es dato y no tiene `t()`
   * delante, y «al 17/09/2026» escrito aqui llegaria al DOM en castellano en cualquier idioma
   * (#103).
   *
   * No es de toda pantalla: lo pone quien recibe un `aLaFecha` de su operacion. `fis-panel` es la
   * primera, y no es decorativo —las tres ultimas etapas de su embudo estan congeladas y la
   * primera se resuelve contra el padron de hoy—.
   */
  readonly aLaFecha?: string;
  /** Los campos que la operacion servida NO publica, con la palabra que va en su hueco. */
  readonly noPublicados: ReadonlyMap<Coordenada, string>;
}

/**
 * Lo que un conector entrega de una tabla **con `clave`**: sus filas y el total que la operacion
 * publica.
 *
 * Es `DatosDeUnaTabla` de `@kamayuk/ui` con una diferencia deliberada: alli el conteo es la
 * **frase** que se lee y aqui es el **numero** que llego. La frase la redacta `useDatosDeLaHoja`,
 * que es un gancho y tiene `t()` delante; escrita aqui seria «20 de 1 842» en castellano dentro de
 * un archivo de datos, y eso es exactamente lo que #103 prohibe —una cadena que llega al DOM sin
 * pasar por `t()`—.
 */
export interface TablaRepartida {
  readonly filas: readonly FilaDeLaTabla[];
  /**
   * **El total que la OPERACION publica**, y jamas uno contado aqui sobre la pagina.
   *
   * <h2>Se llama como el campo del envoltorio, y eso no es pereza</h2>
   *
   * `totalElementos` es el nombre que el backend publica en su envoltorio de paginacion, asi que
   * el nombre dice **de donde sale**. Y ademas es lo unico que la prohibicion de ESLint deja
   * pasar: `total…` declarado `number` esta prohibido —un importe pierde centimos como `number`
   * (regla 1)— con dos excepciones escritas, `totalElementos` y `totalPaginas`, que son las dos
   * del envoltorio. Un `total: number` aqui habria arrancado con un `eslint-disable`.
   *
   * La diferencia es la regla de este archivo vista desde el otro lado. `coa-panel` no lee
   * `totalElementos` **a proposito**: sus campos preguntan «con REC notificada» y «con medida
   * cautelar», y contarlos sobre la pagina daria un numero indistinguible de uno real. Lo que
   * entra aqui es lo contrario: un total que el backend ya conto sobre el padron entero y que hoy
   * llega y se tira, de modo que el encabezado dice «20 registros» donde el artboard dice «20 de
   * 1 842».
   *
   * Sin el, el interprete cuenta las filas que recibe —que es cierto de lo que se ve— y no afirma
   * ningun tamano de padron.
   */
  readonly totalElementos?: number;
}

/**
 * **Un parametro que esta hoja lleva en su ruta y manda a una operacion** (#172, #186).
 *
 * <h2>Un solo nombre, y no dos registros</h2>
 *
 * `nombre` es a la vez **el sitio de la ruta** —`#/aut-cat?pagina=2`— y **el parametro del
 * contrato** —`GET /licencias/ciiu?pagina=2`—. Con dos nombres distintos haria falta una tabla de
 * equivalencias que no protege de nada y que hay que leer dos veces para seguir una peticion desde
 * la barra de direcciones hasta la red. Ver `pantallas/tablas.ts`.
 *
 * <h2>Para que sirve estar declarado, que son tres cosas y ninguna es documentacion</h2>
 *
 * <ol>
 *   <li><b>El marco entrega el valor.</b> `catalogo.ts` DERIVA de aqui el `enLaRuta.parametros`
 *       del destino, y `@kamayuk/shell` ignora con aviso lo que un destino no declara. Sin esta
 *       linea, el mando de pagina escribiria `?pagina=2` y el marco lo tiraria.</li>
 *   <li><b>La cache se entera.</b> `useDatosDeLaHoja` mete estos valores en la llave de la
 *       consulta: sin ellos, cambiar de pagina no volveria a pedir y la tabla ensenaria la pagina
 *       0 diciendo «Pagina 3».</li>
 *   <li><b>El contrato lo publica.</b> Una guarda lo cruza contra
 *       `docs/50-api/parametros-de-la-api.json`, que sale de la FIRMA de cada controlador: mandar
 *       un parametro que el contrato no declara es construir sobre un nombre que nada de este
 *       repositorio puede comprobar — es lo que #26 enseno con `/rentas/predios`.</li>
 * </ol>
 */
export interface ParametroDeLaHoja {
  /** El sitio de la ruta, que es tambien el nombre del parametro. Ver el javadoc: es uno solo. */
  readonly nombre: string;
  /** La operacion a la que viaja, con su verbo: `GET /licencias/ciiu`. */
  readonly operacion: string;
}

/**
 * **Lo que se sabe al pedir** (#172).
 *
 * Eran tres argumentos posicionales —`(senal, sujeto, ejercicio)`— y #181 dejo escrito que ya eran
 * demasiados y que el dia que entrara el filtro de la pantalla lo que entraria seria **un objeto**.
 * Es este, y no se anadio un cuarto: los tres de antes son sus tres primeros campos y el cuarto
 * es la ruta.
 *
 * <h2>Por que la ruta entera y no «el filtro»</h2>
 *
 * Porque la ruta es el unico sitio donde el interprete de `@kamayuk/ui` puede dejar lo que se
 * elige en una tabla: la pagina y el campo de orden los ESCRIBE ahi y no pide nada
 * (`MandosDeLaTabla.tsx`). O sea que «el filtro que entra al conector» (#172) y «la pagina que el
 * mando movio» (#186, #187) **son el mismo canal**, y un segundo camino para el filtro dejaria dos
 * formas de decir lo mismo.
 *
 * Lo que llega aqui es **solo lo que el conector declara** en `Conector.parametros`: el marco tira
 * con aviso lo que el destino no declaro, asi que `#/aut-cat?loQueSea=1` no entra.
 */
export interface LoQueSeSabeAlPedir {
  readonly senal: AbortSignal;
  /** El de la ruta, o `null` cuando la hoja no lleva. */
  readonly sujeto: string | null;
  /**
   * El de trabajo de la sesion, y **solo llega con valor a quien declara `exigeEjercicio`**: a las
   * demas les llega `null`, porque a las demas no se les pide la sesion.
   */
  readonly ejercicio: number | null;
  /**
   * Lo que la hoja lleva en su ruta, ya descodificado: la pagina y el orden que el interprete
   * escribio, y los filtros que la hoja declare. Vacio en las hojas que no declaran ninguno.
   */
  readonly enLaRuta: Readonly<Record<string, string>>;
}

export interface Conector {
  /**
   * La clave de consulta de TanStack. Lleva la hoja dentro: dos pantallas no comparten cache.
   *
   * **Ni el sujeto ni lo de la ruta van aqui**: los anade `useDatosDeLaHoja` al final, porque si
   * no dos contribuyentes compartirian la cache de la misma hoja —y el segundo veria la cuenta del
   * primero mientras llega la suya— y la pagina 3 se dibujaria con las filas de la 0.
   */
  readonly clave: readonly string[];
  /**
   * **Esta hoja es de un sujeto concreto** (#169): sin el no se pide nada y la pantalla lo dice.
   *
   * Las tres operaciones de Consultas son de un contribuyente —sin su codigo contestan 422—, y el
   * codigo viaja en la direccion (`#/<hoja>/<codigo>`). Que este declarado aqui y no en una lista
   * aparte es lo que hace que el catalogo no pueda desincronizarse: `catalogo.ts` deriva de esto
   * el `enLaRuta` con que el marco lee el sujeto.
   */
  readonly exigeSujeto?: boolean;
  /**
   * **Esta hoja LEE el sujeto de la ruta si lo trae, y sin el toma la primera de la relacion**
   * (#215).
   *
   * Es la hermana de `exigeSujeto` y no una variante suya: alli el sujeto es una **condicion
   * previa** —sin el no se pide nada y la pantalla lo dice— y aqui es una **eleccion**.
   *
   * <h2>El caso que la trae, y por que no bastaba ninguno de los dos extremos</h2>
   *
   * `fis-res` exigia sujeto desde #179, y con motivo: `GET /fiscalizacion/resoluciones/{numero}` es
   * de UNA resolucion y **no existia ninguna operacion que publicara la relacion**, asi que no
   * habia «primera» que tomar. #192 la publico, y entonces la hoja podia tomarla como `coa-exp`.
   *
   * Retirar `exigeSujeto` a secas —que es lo que #215 propone— arregla lo que mas se nota —abierta
   * desde el menu la pantalla **no ensenaba una resolucion nunca**— y **pierde lo otro**:
   * `catalogo.ts` deriva `enLaRuta.sujeto` de `exigeSujeto`, y sin esa linea el marco **tira** el
   * numero de la direccion, de modo que `#/fis-res/RDF-2026-000001` dejaria de abrir esa
   * resolucion. Dos capacidades por una.
   *
   * Asi que se declara la tercera forma, que es la union de las dos: el catalogo deriva el sitio
   * del sujeto de `exigeSujeto` **o** de esta, y `useDatosDeLaHoja` solo se detiene por la primera.
   * Quien decide que hacer sin sujeto es el conector, que es quien sabe si hay una primera.
   *
   * **Las dos juntas no tienen sentido** —«sin el no pido nada» y «sin el tomo la primera» se
   * contradicen— y lo vigila una guarda.
   */
  readonly admiteSujeto?: boolean;
  /**
   * **Que decir cuando exige sujeto y la direccion no lo trae**, si no vale la frase de por
   * omision (#180).
   *
   * Las tres operaciones de Consultas son de un **contribuyente** y `useDatosDeLaHoja` lo dice con
   * esas palabras. `tra-veh` es de una **placa**, y la misma frase le pediria a quien atiende el
   * codigo de un contribuyente para abrir la ficha de un vehiculo: un mensaje que nombra el dato
   * equivocado se lee como una pantalla rota, no como una pantalla que espera algo.
   *
   * Va aqui —y no en una lista aparte, ni en el gancho con un `if` por hoja— por lo mismo que
   * `exigeSujeto`: quien sabe que sujeto necesita una hoja es quien la pide.
   */
  readonly sinSujeto?: Ausencia;
  /**
   * **Los parametros que esta hoja lleva en su ruta**, y a que operacion viajan (#172, #186).
   *
   * Declarados aqui y no en una lista aparte por lo mismo que `exigeSujeto`: quien sabe que
   * necesita una hoja para pedir es quien la pide. De aqui salen el `enLaRuta.parametros` del
   * catalogo, la llave de la cache y la guarda contra el contrato. Ver `ParametroDeLaHoja`.
   */
  readonly parametros?: readonly ParametroDeLaHoja[];
  /**
   * **Esta hoja es de un ejercicio concreto, y el ejercicio sale de la SESION** (#181).
   *
   * Es la **tercera** forma de exigir algo, y no se parece a las dos anteriores. Sin parametro
   * obligatorio estaban las once primeras; con el sujeto en la ruta, las dos de Consultas
   * (`exigeSujeto`, #169). Esta es un obligatorio que **no va en la ruta y no lo elige nadie**:
   * `GET /seguridad/auditoria` declara `ejercicio` entre sus obligatorios
   * —`parametros-de-la-api.json`— y el ejercicio de trabajo es del contexto de sesion, que ya lo
   * publica `GET /seguridad/sesion` y que fija la unica escritura de esta interfaz,
   * `PUT /seguridad/sesion/ejercicio`.
   *
   * Asi que no se le pregunta al usuario y **no se inventa**: ni un literal, ni un
   * `new Date().getFullYear()`. Un ano de hoy no es el ejercicio de trabajo de nadie —medido, la
   * cuenta `administrador` de la instalacion lo tiene **nulo**—, y una auditoria del ejercicio
   * equivocado es peor que una pantalla vacia: contesta 200, con filas, de otro ano. Sin
   * ejercicio en la sesion no se pide nada y la pantalla lo dice (ver `useDatosDeLaHoja`).
   *
   * <h2>Y por que NO es un parametro de la ruta, con los dos candidatos delante (#181, AC3)</h2>
   *
   * <ol>
   *   <li><b>No sale de la pantalla.</b> Sale de la sesion, que ninguna de las 40 hojas tiene.
   *       Meterlo por el canal de «lo que la pantalla sabe» convertiria la sesion en una propiedad
   *       de cada pantalla, y entonces cada una podria decir un ejercicio distinto.</li>
   *   <li><b>Y NO puede vivir en la ruta.</b> La ruta la escribe cualquiera: con el ejercicio ahi,
   *       `#/seg-aud?ejercicio=2019` ensena la bitacora de 2019 con la sesion puesta en 2026, y la
   *       pantalla no tendria como saber que no es la suya. El ejercicio de trabajo es <b>global a
   *       la sesion</b> y lo fija una escritura auditada, con su observacion. La pagina y el orden
   *       si pueden: cambiarlos no cambia <b>que</b> se esta mirando, solo por donde y en que
   *       orden.</li>
   *   <li><b>Sin el no hay peticion, no hay menos filas.</b> Un filtro que falta acota de menos y
   *       la tabla trae mas; una pagina que falta es la primera. Un obligatorio que falta hace que
   *       la peticion <b>no se mande</b>. Eso no es un dato de entrada: es una <b>condicion
   *       previa</b>, del mismo tipo que `exigeSujeto` —y por eso se declara al lado y se resuelve
   *       en el mismo sitio, con su propia frase—.</li>
   * </ol>
   */
  readonly exigeEjercicio?: boolean;
  /** Pide lo de esta hoja, con lo que se sabe al pedir. Ver `LoQueSeSabeAlPedir`. */
  readonly pedir: (lo: LoQueSeSabeAlPedir) => Promise<unknown>;
  readonly repartir: (respuesta: never) => Reparto;
}

/**
 * **Lo que la hoja lleva en su ruta, de lo que SU CONECTOR declara y nada mas** (#172).
 *
 * Filtra por `Conector.parametros` y no por lo que traiga la direccion, por lo mismo que el marco
 * tira con aviso lo que un destino no declara: un `#/aut-cat?loQueSea=1` no puede convertirse en
 * un `GET /licencias/ciiu?loQueSea=1`, que es un parametro escrito por quien pasaba por ahi.
 *
 * Y el orden es el DECLARADO y no el de la direccion, porque de esto sale la llave de la cache:
 * con el orden de la ruta, `?pagina=2&tamano=20` y `?tamano=20&pagina=2` serian dos entradas
 * distintas de la misma pagina.
 *
 * Un parametro vacio no esta: `?descripcion=` es no filtrar, y mandarlo seria acotar por la cadena
 * vacia.
 */
export function loQueLaHojaDeclara(
  conector: Conector | undefined,
  parametros: Readonly<Record<string, string>>,
): Readonly<Record<string, string>> {
  const salida: Record<string, string> = {};
  for (const { nombre } of conector?.parametros ?? []) {
    const valor = parametros[nombre];
    if (valor !== undefined && valor.trim() !== '') salida[nombre] = valor;
  }
  return salida;
}

/** La palabra del hueco cuando la operacion se pidio y no trae ese dato. */
const NO_PUBLICADO = 'no publicado';

/**
 * `panel` — el estado de la ultima corrida del padron.
 *
 * De `CorridaDelPredial` salen la fecha, los observados y **las cinco columnas de la tabla, que
 * cuadran una a una** con `EtapaDeLaCorrida`. Lo que no sale —cuentas emitidas, monto determinado,
 * derecho de emision— no se deduce de las etapas aunque se parezca: la ultima etapa trae 61 350
 * registros y la pantalla ensena 61 350 cuentas emitidas, y **que coincidan no las hace lo mismo**.
 */
const PANEL: Conector = {
  clave: ['panel', 'ultima-corrida'],
  pedir: ({ senal }) => pedirUno<CorridaDelPredial>(RUTAS.ultimaCorrida, senal),
  repartir: (corrida: CorridaDelPredial): Reparto => ({
    valores: new Map([
      [coordenada(0, 1), corrida.fechaCalculo],
      [coordenada(0, 3), String(corrida.observados)],
    ]),
    filas: new Map([
      [
        0,
        corrida.etapas.map((e) => [
          e.etapa,
          String(e.registros),
          e.monto,
          String(e.observados),
          e.estado,
        ]),
      ],
    ]),
    noPublicados: new Map([
      [coordenada(0, 2), NO_PUBLICADO],
      [coordenada(0, 4), NO_PUBLICADO],
      [coordenada(0, 5), NO_PUBLICADO],
    ]),
  }),
};


/** Las hojas que piden de verdad. Las demas lo dicen; ver `porQueNoHayDato.ts`. */
export const CONECTORES: Readonly<Partial<Record<ClaveDeHoja, Conector>>> = {
  panel: PANEL,
  ...CONECTORES_DE_LICENCIAS,
  ...CONECTORES_DE_COACTIVA,
  ...CONECTORES_DE_INICIO,
  ...CONECTORES_DE_CONSULTAS,
  ...CONECTORES_DE_FISCALIZACION,
  ...CONECTORES_DE_TRANSITO,
  ...CONECTORES_DE_SEGURIDAD,
};

export { NO_PUBLICADO };

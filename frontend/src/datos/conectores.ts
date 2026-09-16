import { coordenada, type Coordenada } from '@kamayuk/ui';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import type { CorridaDelPredial } from './lecturas.ts';
import { RUTAS, pedirUno } from './lecturas.ts';
import { CONECTORES_DE_LICENCIAS } from './conectores/licencias.ts';
import { CONECTORES_DE_COACTIVA } from './conectores/coactiva.ts';
import { CONECTORES_DE_INICIO } from './conectores/inicio.ts';
import { CONECTORES_DE_CONSULTAS } from './conectores/consultas.ts';
import { CONECTORES_DE_FISCALIZACION } from './conectores/fiscalizacion.ts';
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
  /** Las filas de la tabla de un bloque. */
  readonly filas: ReadonlyMap<number, readonly (readonly string[])[]>;
  /** Los campos que la operacion servida NO publica, con la palabra que va en su hueco. */
  readonly noPublicados: ReadonlyMap<Coordenada, string>;
}

export interface Conector {
  /**
   * La clave de consulta de TanStack. Lleva la hoja dentro: dos pantallas no comparten cache.
   *
   * **El sujeto no va aqui**: lo anade `useDatosDeLaHoja` al final, porque si no dos
   * contribuyentes compartirian la cache de la misma hoja y el segundo veria la cuenta del
   * primero mientras llega la suya.
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
   * <h2>La decision del AC3: camino propio, y NO #172 — con los dos candidatos delante</h2>
   *
   * Habia dos mecanismos que podian haber servido, y se miraron los dos.
   *
   * <b>#172 — «no entra un filtro».</b> Pide que `pedir` reciba lo que la PANTALLA sabe —lo
   * tecleado en el buscador, los desplegables— para poder mandar los parametros que la operacion
   * publica. Cinco de los nueve opcionales de esta misma operacion —`usuario`, `tabla`,
   * `operacion`, `desde`, `hasta`— son exactamente eso, y <b>siguen siendo de #172</b>, que se
   * queda abierto por ellos.
   *
   * <b>`kamayuk-lib`#87 — «la pagina y el orden EN LA RUTA».</b> Desde el 2026-09-16 el interprete
   * de `@kamayuk/ui` sabe paginar y ordenar contra el servidor: escribe la pagina y el campo de
   * orden <b>en la ruta de la hoja</b> (`PaginacionDeLaTabla.enLaRuta`, `OrdenDeLaTabla.enLaRuta`)
   * y quien lee la ruta pide. Es el mismo canal que `enLaRuta` estreno en #169 para el sujeto, o
   * sea un mecanismo <b>mas parecido a este</b> que el de #172 — y por eso hay que decir por que
   * tampoco sirve. Cubre los otros cuatro opcionales (`pagina`, `tamano`, `ordenarPor`,
   * `direccion`), y su adopcion aqui es su propio issue.
   *
   * El ejercicio no es ninguno de los dos, por tres diferencias que no son de grado:
   *
   * <ol>
   *   <li><b>No sale de la pantalla.</b> Sale de la sesion, que ninguna de las 40 hojas tiene.
   *       Meterlo por el canal de «lo que la pantalla sabe» convertiria la sesion en una propiedad
   *       de cada pantalla, y entonces cada una podria decir un ejercicio distinto.</li>
   *   <li><b>Y NO puede vivir en la ruta, que es lo que descarta el de la libreria.</b> La ruta la
   *       escribe cualquiera: con el ejercicio ahi, `#/seg-aud?ejercicio=2019` ensena la bitacora
   *       de 2019 con la sesion puesta en 2026, y la pantalla no tendria como saber que no es la
   *       suya. El ejercicio de trabajo es <b>global a la sesion</b> —«decide sobre que ano
   *       escriben todos los modulos», lo dice la propia hoja `seg-sis`— y lo fija una escritura
   *       auditada, `PUT /seguridad/sesion/ejercicio`, con su observacion. Un estado que se cambia
   *       tecleando en la barra de direcciones no puede ser el mismo. La pagina y el orden si
   *       pueden: cambiarlos no cambia <b>que</b> se esta mirando, solo por donde y en que
   *       orden.</li>
   *   <li><b>Sin el no hay peticion, no hay menos filas.</b> Un filtro que falta acota de menos y
   *       la tabla trae mas; una pagina que falta es la primera. Un obligatorio que falta hace que
   *       la peticion <b>no se mande</b>. Eso no es un dato de entrada: es una <b>condicion
   *       previa</b>, del mismo tipo que `exigeSujeto` —y por eso se declara al lado y se resuelve
   *       en el mismo sitio, con su propia frase—. El AC1 de #172 comprueba que un parametro
   *       mandado este publicado; ninguna comprobacion sobre el NOMBRE de un parametro puede decir
   *       que sin el no se puede pedir.</li>
   * </ol>
   */
  readonly exigeEjercicio?: boolean;
  /**
   * Pide lo de esta hoja.
   *
   * `sujeto` es el de la ruta, o `null` cuando la hoja no lleva. `ejercicio` es el de trabajo de
   * la sesion, y **solo llega con valor a quien declara `exigeEjercicio`**: a las demas les llega
   * `null`, porque a las demas no se les pide la sesion.
   *
   * **Son tres argumentos posicionales y ya son demasiados**, y queda dicho aqui en vez de
   * descubrirse: el dia que #172 haga entrar los filtros de la pantalla, lo que entra por aqui es
   * un objeto —lo que se sabe al pedir— y estos tres son sus tres primeros campos. No se hace hoy
   * porque cambiar la firma con doce conectores puestos toca cinco archivos que cuatro ramas
   * comparten, y #172 va a tocarlos igual.
   */
  readonly pedir: (
    senal: AbortSignal,
    sujeto: string | null,
    ejercicio: number | null,
  ) => Promise<unknown>;
  readonly repartir: (respuesta: never) => Reparto;
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
  pedir: (senal) => pedirUno<CorridaDelPredial>(RUTAS.ultimaCorrida, senal),
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
  ...CONECTORES_DE_SEGURIDAD,
};

export { NO_PUBLICADO };

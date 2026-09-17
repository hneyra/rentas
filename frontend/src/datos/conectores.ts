import {
  coordenada,
  type Ausencia,
  type Coordenada,
  type DatoConNombre,
  type FilaDeLaTabla,
} from '@kamayuk/ui';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { NO_PUBLICADO, SIN_CRONOGRAMA, type PalabraDeHueco } from './palabrasDeHueco.ts';
import type { CorridaDelPredial, DeterminacionGuardada } from './lecturas.ts';
import { RUTAS, pedirUnoOVacio } from './lecturas.ts';
import {
  formatearAlicuota,
  formatearImporte,
  formatearImporteSinRedondear,
} from '../dominio/formato.ts';
import { CONECTORES_DE_LICENCIAS } from './conectores/licencias.ts';
import { CONECTORES_DE_COACTIVA } from './conectores/coactiva.ts';
import { CONECTORES_DE_INICIO } from './conectores/inicio.ts';
import { CONECTORES_DE_CONSULTAS } from './conectores/consultas.ts';
import { CONECTORES_DE_FISCALIZACION } from './conectores/fiscalizacion.ts';
import { CONECTORES_DE_TRANSITO } from './conectores/transito.ts';
import { CONECTORES_DE_SEGURIDAD } from './conectores/seguridad.ts';
import { CONECTORES_DE_VALORES } from './conectores/valores.ts';

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
  /**
   * **De quien es lo que esta pantalla dibuja**, cuando la operacion lo publica (#239).
   *
   * <h2>El hueco que lo trae</h2>
   *
   * Hay hojas que dibujan **una** de muchas y toman «la primera de la relacion» porque todavia no
   * tienen con que elegirla: `fis-actas`, `coa-exp`, `coa-cost`, `tra-pap`. Hasta #239 ninguna
   * decia **cual**, y un contraste de areas que no nombra al obligado no se puede comprobar contra
   * nada — se lee como si fuera del contribuyente que uno tenia en la cabeza.
   *
   * <h2>Por que va por aqui y no a un campo de la pantalla</h2>
   *
   * Porque el sitio que el artboard le da al titular en `fis-actas` es un **mando** —`tipo: '1'`,
   * un control de entrada— y `valores` son «los campos de solo lectura que si salen de lo que
   * llego»: meter ahi el nombre de un acta ya registrada convertiria el formulario de alta en algo
   * que parece estar editando esa acta. Y anadirle una celda al bloque es cambiar el artboard para
   * que quepa un dato, que es al reves de como se decide aqui.
   *
   * **Es exactamente la decision de #196 y se resuelve igual**: se dice **una vez, arriba**, en la
   * frase de pantalla que el interprete ya dibuja, al lado de `aLaFecha`. Las tres salidas que #239
   * ofrecia tocaban el artboard (una celda nueva, o la nota de la tabla, que `pantallas-del-artboard`
   * compara palabra por palabra) o la libreria (un valor dentro de un mando); esta no toca ninguno
   * de los dos.
   *
   * <h2>Nulo no es «no publicado»: es que ya no esta en el padron</h2>
   *
   * Los dos campos son anulables **a la vez** y el backend dice que nulo significa que el obligado
   * ya no esta en el padron (#216). Asi que la frase que se lee es otra, y no la de un hueco.
   *
   * Lo que viaja por aqui son **las dos piezas crudas**, nunca la frase: un conector es dato y no
   * tiene `t()` delante, y «El acta es de …» escrito aqui llegaria al DOM en castellano en
   * cualquier idioma (#103). La redacta `useDatosDeLaHoja`, que es un gancho.
   */
  readonly deQuienEs?: DeQuienEs;
  /**
   * **Un TROZO de la pantalla que la operacion no trae, con su motivo** (#237).
   *
   * `noPublicados` dice lo que le falta a un **campo**, y para eso basta una palabra en su hueco.
   * Una **tabla** entera no tiene hueco donde escribirla: sin filas, el interprete dibuja la frase
   * de pantalla —la de arriba— y esa es generica. El «Cronograma» de `territorio` es el caso, y
   * desde #234 su motivo **cambio de mitad**: ya no es que nadie lo publique —la operacion trae
   * `modalidad` y `cuotas[]`— sino que esta hoja todavia no lo dibuja (#252).
   *
   * Lo que viaja es **una clave de traduccion** declarada como constante —nunca una frase compuesta
   * aqui—, por lo mismo que todo lo demas de este archivo: un conector es dato y no tiene `t()`
   * delante (#103). Y desde #246 no hay que acordarse de listarla: el tipo es `PalabraDeHueco`, o
   * sea que una frase que no este en `palabrasDeHueco.ts` **no compila**, y lo que esta alli lo
   * deriva solo el inventario del locale.
   */
  readonly loQueLaOperacionNoTrae?: PalabraDeHueco;
  /** Los campos que la operacion servida NO publica, con la palabra que va en su hueco. */
  readonly noPublicados: ReadonlyMap<Coordenada, PalabraDeHueco>;
}

/**
 * **De quien es lo que la pantalla dibuja** (#239). Ver `Reparto.deQuienEs`.
 *
 * Los dos son nulos **a la vez**, y eso no es un hueco del contrato: es que el obligado ya no esta
 * en el padron (#216).
 */
export interface DeQuienEs {
  /** El nombre tal como el padron lo escribe. */
  readonly nombre: string | null;
  /** Su codigo en el padron. */
  readonly codigo: string | null;
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
   * **Que decir cuando la operacion contesta que TODAVIA NO HAY**, si no vale la frase de por
   * omision (#237).
   *
   * Es la hermana de `sinSujeto` y vive al lado por lo mismo: quien sabe que significa un vacio en
   * una hoja es quien la pide. La de por omision —`VACIO`, «sin datos»— dice «todavia no existe el
   * dato que esta pantalla ensena», que vale para una relacion vacia y **no** para
   * `GET /rentas/predial/determinaciones`: alli el 204 es «este contribuyente existe y todavia no
   * se le ha determinado este ejercicio», que es un hecho del expediente y no de la pantalla, y lo
   * que hay que hacer con el es **determinarlo**.
   */
  readonly sinDato?: Ausencia;
  /**
   * **Que decir cuando la operacion contesta 404**, si no vale la frase de por omision (#237).
   *
   * Sin esto un 404 sale por `alFallar` como «fallo (404)», o sea **como una averia**. Y no lo es:
   * la lectura del predial contesta 404 cuando el codigo **no esta en el padron** —lo dice
   * nombrandolo— y 204 cuando esta y no tiene determinacion. Son dos vacios distintos a proposito
   * (#546), y decirlos igual es exactamente el defecto que el backend evito al publicarlos
   * distintos.
   *
   * El tono tambien cambia: «el codigo que trae la direccion no existe» se arregla escribiendo otro
   * codigo, no reintentando.
   */
  readonly noEncontrado?: Ausencia;
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
  // `pedirUnoOVacio` y no `pedirUno`: sin ninguna corrida del ejercicio esta operacion contesta
  // **204 sin cuerpo** (#523), y hasta #237 eso reventaba en `respuesta.json()` — la pantalla decia
  // «fallo» donde la verdad es «todavia no se ha corrido».
  pedir: ({ senal }) => pedirUnoOVacio<CorridaDelPredial>(RUTAS.ultimaCorrida, senal),
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


/** Lo que se dice cuando el codigo de la direccion no esta en el padron (#237). */
const NO_ESTA_EN_EL_PADRON: Ausencia = {
  enElCampo: 'no esta en el padron',
  explicacion:
    'El codigo de contribuyente que trae la direccion no existe en esta municipalidad, y por eso ' +
    'no hay determinacion que leer. No es una averia y reintentar no lo cambia: se abre con otro ' +
    'codigo.',
  tono: 'atencion',
};

/** Lo que se dice cuando el contribuyente existe y todavia no se le ha determinado (#237). */
const TODAVIA_SIN_DETERMINAR: Ausencia = {
  enElCampo: 'sin determinar',
  explicacion:
    'Este contribuyente esta en el padron y todavia no tiene determinacion de este ejercicio: no ' +
    'es que falte un dato, es que el calculo no se ha asentado. Se asienta desde esta misma ' +
    'pantalla, y entonces lo que quede asentado es lo que se lee aqui.',
  tono: 'info',
};

/**
 * `territorio` — la Determinacion, y la unica hoja de las cuarenta que NO se pintaba por declarar
 * solo escrituras (#182, #207, #237).
 *
 * <h2>Lo que cambia, que no es lo que se ve sino lo que se dice</h2>
 *
 * Hasta aqui decia «solo escribe», que era cierto y ya no lo es: `GET
 * /rentas/predial/determinaciones` existe desde #207 y es una lectura. Con ella la hoja pide de
 * verdad, y sus TRES ausencias dejan de ser una sola frase de pantalla apagada.
 *
 * <h2>Hasta #245 no pintaba NI UNA celda, y el que no cabia era el artboard</h2>
 *
 * La operacion publica **veinte campos** —hasta los tramos del articulo 13— y esta pantalla tenia
 * **un solo campo de solo lectura en sus tres bloques**: «Monto deducido», que ademas no lo
 * publica nadie. Lo que #237 compro entonces no fue una celda sino que las tres ausencias se
 * distinguieran: 404 «ese codigo no esta en el padron», 204 «esta, y todavia no se le ha
 * determinado» y 200 con el cronograma que nadie publica.
 *
 * **#245 le da su sitio a la memoria**, y la decision la toma el artboard, no este archivo: su
 * instruccion ya prometia «compruebe la **memoria del calculo** antes de asentar la
 * determinacion» sin un campo donde comprobarla, y su cronograma ya dibujaba **lo determinado**
 * —151,36 + 3 x 146,86 = 591,94 = 587,44 + 4,50—, que es la escala de la hoja `valores` sobre la
 * base de los predios de `predios`. O sea que la memoria estaba en el artboard repartida en tres
 * hojas, y esta ensenaba el resultado sin ensenar de donde salia. Ver `definiciones/rentas-registro.ts`.
 *
 * Asi que el bloque 2 se llena entero: **diez de diez**, y ninguno calculado aqui.
 *
 * <h2>Lo que sigue sin pintarse, y por que</h2>
 *
 * **«Monto deducido»** (`1|3`) es el importe que una deduccion resta de la base, y lo mas cercano
 * que llega es `valuoExonerado`, que es la parte exonerada del valuo. No son lo mismo y pintar uno
 * por otro seria una cifra al centimo indistinguible de la correcta — en un beneficio de
 * pensionista esa cifra decide cuanto se cobra. Sigue diciendo «no publicado».
 *
 * **El «Cronograma»**, que ya no es #234 —la operacion trae `modalidad` y `cuotas[]` desde
 * esa migracion— sino el conector que las reparta: ver abajo, y #252.
 *
 * <h2>El aporte de un tramo NO pasa por `formatearImporte`, y no es un detalle</h2>
 *
 * `AporteDeTramo.aporte` llega **sin redondear** —ADR-0018: los intermedios corren sin redondear,
 * y el unico redondeo es el del cierre de la regla—, o sea con ocho decimales:
 * `porcionGravada.por(alicuota.movePointLeft(2))`. `formatearImporte` **revienta** con eso, y tiene
 * razon; recortarlo aqui seria aritmetica sobre dinero (regla 1). Va por
 * `formatearImporteSinRedondear`, que escribe lo que llego. La cifra que manda es
 * `impuestoInsoluto`, que si esta redondeada y esta arriba, en su campo.
 *
 * <h2>Exige las dos cosas: el sujeto y el ejercicio</h2>
 *
 * Es el primer conector con `exigeSujeto` y `exigeEjercicio` a la vez, y las dos por el mismo
 * motivo que en sus estrenos: sin `codContribuyente` la operacion es 422 —«no se contesta la de
 * cualquiera»— y sin `ejercicio` contesta **200 con la determinacion del ano del reloj del
 * backend**, que no es el de trabajo de la sesion. Un acierto de ese tipo no se distingue del
 * correcto.
 *
 * <h2>El boton que determina no refresca nada, porque no existe</h2>
 *
 * La otra decision que #237 dejaba abierta. La definicion de esta hoja **no declara ningun acto**:
 * el artboard le atribuye un `AlertDialog` «Confirmar antes de asentar», y eso es una pieza
 * declarada, no un acto montado. Mientras no haya boton no hay nada que refrescar, y escribir aqui
 * la invalidacion de una cache que nadie va a tocar es codigo que no puede fallar ni acertar.
 */
const TERRITORIO: Conector = {
  clave: ['territorio', 'determinacion-guardada'],
  exigeSujeto: true,
  exigeEjercicio: true,
  noEncontrado: NO_ESTA_EN_EL_PADRON,
  sinDato: TODAVIA_SIN_DETERMINAR,
  pedir: ({ senal, sujeto, ejercicio }) =>
    pedirUnoOVacio<DeterminacionGuardada>(
      // Los dos van con valor: el marco no deja llegar aqui sin sujeto y sin ejercicio de sesion.
      RUTAS.determinacionGuardada(sujeto ?? '', ejercicio ?? 0),
      senal,
    ),
  repartir: (determinacion: DeterminacionGuardada): Reparto => ({
    // Los diez de la «Memoria del calculo», y ni uno compuesto aqui: los nueve importes y el
    // nombre del conjunto sellado llegan tal cual. La pantalla NO suma los tramos para adelantar
    // el insoluto —lo pide— por lo mismo que `con-panel` no suma interes y reajuste: el dia que el
    // total y el desglose discreparan nadie sabria cual mirar (RNF-083), y aqui ademas no cuadran
    // por construccion, porque los aportes no estan redondeados y el impuesto si.
    valores: new Map([
      [coordenada(2, 0), formatearImporte(determinacion.valuoTotal)],
      [coordenada(2, 1), formatearImporte(determinacion.valuoExonerado)],
      [coordenada(2, 2), formatearImporte(determinacion.valuoAfecto)],
      [coordenada(2, 3), formatearImporte(determinacion.baseImponible)],
      [coordenada(2, 4), formatearImporte(determinacion.uit)],
      [coordenada(2, 5), formatearImporte(determinacion.minimoImponible)],
      [coordenada(2, 6), formatearImporte(determinacion.impuestoInsoluto)],
      [coordenada(2, 7), formatearImporte(determinacion.derechoDeEmision)],
      [coordenada(2, 8), formatearImporte(determinacion.totalAPagar)],
      // El conjunto SELLADO, y no su identificador: es lo que hace reproducible la memoria
      // (ARQ-09 §3). `uit`, `tramos`, `minimoImponible` y `derechoDeEmision` salen del conjunto
      // que ESA determinacion fijo, no del vigente hoy.
      [coordenada(2, 9), determinacion.conjunto],
    ]),
    // Vacio: la tabla de los tramos lleva `clave`, asi que sus filas van por `tablas`. Y el
    // «Cronograma» no lleva filas — y no `[]`, que significaria «la operacion contesto que no hay
    // ninguna cuota». Desde #234 la operacion SI trae `cuotas[]`; lo que falta es el conector que
    // las reparta, y eso es #252. Repartirlas aqui de paso seria conectar una tabla sin haber
    // decidido que dice su cuarta columna —«Situacion», que es cuenta corriente y no calculo—.
    filas: new Map(),
    tablas: new Map([
      [
        'tramos-del-articulo-13',
        {
          filas: determinacion.tramos.map((tramo) => ({
            clave: String(tramo.orden),
            celdas: [
              String(tramo.orden),
              // **Nulo en el ultimo tramo, y no es un hueco**: ese tramo no tiene tope. Por eso la
              // tabla declara `sinDato` con la palabra —«Sin tope»— y su motivo: una raya muda se
              // leeria como un campo que al backend se le paso publicar.
              tramo.limiteSuperior === null
                ? { texto: null }
                : formatearImporte(tramo.limiteSuperior),
              formatearAlicuota(tramo.alicuota),
              formatearImporte(tramo.porcionGravada),
              // Sin redondear: ver el javadoc. `formatearImporte` reventaria con sus ocho
              // decimales, y recortarlos aqui seria aritmetica sobre dinero.
              formatearImporteSinRedondear(tramo.aporte),
            ],
          })),
          // Sin `totalElementos`: la operacion no pagina tramos, los publica enteros. Lo que se ve
          // es todo lo que hay, asi que el interprete cuenta las filas y no afirma ningun total.
        },
      ],
    ]),
    loQueLaOperacionNoTrae: SIN_CRONOGRAMA,
    noPublicados: new Map([[coordenada(1, 3), NO_PUBLICADO]]),
  }),
};

/** Las hojas que piden de verdad. Las demas lo dicen; ver `porQueNoHayDato.ts`. */
export const CONECTORES: Readonly<Partial<Record<ClaveDeHoja, Conector>>> = {
  panel: PANEL,
  territorio: TERRITORIO,
  ...CONECTORES_DE_LICENCIAS,
  ...CONECTORES_DE_COACTIVA,
  ...CONECTORES_DE_INICIO,
  ...CONECTORES_DE_CONSULTAS,
  ...CONECTORES_DE_FISCALIZACION,
  ...CONECTORES_DE_TRANSITO,
  ...CONECTORES_DE_SEGURIDAD,
  ...CONECTORES_DE_VALORES,
};

export { NO_ESTA_EN_EL_PADRON, NO_PUBLICADO, SIN_CRONOGRAMA, TODAVIA_SIN_DETERMINAR };

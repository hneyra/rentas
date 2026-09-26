import { coordenada, type CeldaDeLaTabla, type Coordenada } from '@kamayuk/ui';

import {
  formatearEntero,
  formatearImporte,
  formatearImporteEnColumna,
} from '../../dominio/formato.ts';
import type {
  ActaDeFiscalizacion,
  EmbudoDelPrograma,
  FilaDeLaMuestra,
  LineaDeterminada,
  Paginado,
  ProgramaDeFiscalizacion,
  ResolucionDeDeterminacion,
  ResolucionEnLaRelacion,
} from '../lecturas.ts';
import { RUTAS, pedirPagina, pedirUno } from '../lecturas.ts';
import { ejercicioDeLaRespuesta, type Conector, type Reparto } from '../conectores.ts';
import {
  NO_PUBLICADO,
  SIN_CIFRAR,
  SIN_PARAMETROS_DEL_SORTEO,
  type PalabraDeHueco,
} from '../palabrasDeHueco.ts';
import { laVentanaDe, laVentanaQueSePide, loQueDijoElServidor } from '../laVentana.ts';

/**
 * **Las tres hojas de Fiscalizacion, que son de TABLA** (#179).
 *
 * Aparte de `conectores.ts` porque el registro es de todos los modulos y un conector es de uno:
 * asi el registro tiene UNA linea por modulo —`...CONECTORES_DE_FISCALIZACION`— y una conexion
 * nueva no toca el unico archivo que todas las ramas comparten. Es lo mismo que hicieron #168,
 * #170, #167 y #169 con los suyos.
 *
 * <h2>Dos de las tres no llenan ni un `valores`, y no es un olvido</h2>
 *
 * `fis-prog` y `fis-actas` **no tienen un solo campo de solo lectura**: sus ocho y sus nueve
 * campos son mandos —cajas, desplegables, fechas, una casilla y un area— o sea el formulario con
 * que se define un programa y se registra un acta. Lo que esas dos pantallas ENSENAN es su tabla,
 * asi que el conector llena `filas` y deja `valores` y `noPublicados` vacios. El patron es el de
 * `aut-cat` y `aut-tram` (#168).
 *
 * `fis-res` si tiene seis, y de los seis sale **uno**. Ver su javadoc.
 *
 * <h2>El artboard le faltaba una operacion a `fis-prog`, y se corrigio en los dos sitios</h2>
 *
 * `fis-prog` declaraba `GET /fiscalizacion/programas/{id}/muestra` y su `POST`, las dos con `{id}`
 * en la ruta, **y ninguna operacion que publicara ningun `id`**. El `{id}` es el identificador
 * interno del programa —`ProgramaResource.id`, «lo asigna la base»— y no el «Nº de programa» que
 * la pantalla teclea, que es `codigo`. O sea que tal como estaba declarada, esa hoja no podia
 * llamar a ninguna de sus dos operaciones nunca: habria que inventarse un numero, y con uno que no
 * exista el backend contesta 404.
 *
 * Lo que falta es `GET /fiscalizacion/programas`, que es la que publica los programas con su `id`
 * y la unica que admite `?nDePrograma=`. Se anadio **en `diseno/RentasV8.dc.html` y en
 * `pantallas/arbol.ts`**, que es la unica forma de anadirla: `pantallas-del-artboard.test.ts`
 * compara los dos y tocar uno solo sale rojo. Es lo que #169 hizo con `con-panel`.
 *
 * **Lo que NO se toco es `fis-actas`**, y se miro con la misma lupa: sus tres declaraciones son
 * correctas. `BASE /fiscalizacion/actas` cubre la lectura que esta hoja pide, y
 * `BASE /fiscalizacion/predial/actas` y `BASE /fiscalizacion/vehicular` son las dos escrituras que
 * registran un acta — que son exactamente las dos opciones de su desplegable «Tipo de acta».
 *
 * <h2>La regla de `conectores.ts` manda aqui, y en este modulo manda dos veces</h2>
 *
 * **No se calcula un agregado que la operacion no publica.** En `fis-res` los cuatro campos que
 * faltan —insoluto omitido, interes, multa tributaria y total liquidado— son **sumas de la tabla
 * de abajo**, y sumarlas daria cuatro cifras al centimo indistinguibles de unas liquidadas de
 * verdad. Una resolucion de determinacion es el papel que vuelve una diferencia deuda exigible: un
 * total inventado ahi se cobra.
 *
 * <h2>Y una segunda razon de hueco que este modulo estrena: D-02a</h2>
 *
 * **Casi todo el dinero de fiscalizacion llega nulo**, y no por un fallo: `insolutoOmitido`,
 * `multaTributaria`, `determinado`, `declarado`, `diferencia`, `multa`, `total`,
 * `impuestoOmitidoS` y los otros tres importes de omisos van `null` **hasta D-02a**, porque sin el
 * cuadro de valores unitarios, la depreciacion y el arancel firmados no hay base que calcular. El
 * propio backend publica un `esperaSusCifras` para esto y dice por que: «para que la interfaz
 * pueda escribir "sin cifra" en vez de dibujar un cero, que un contribuyente leeria como "no debe
 * nada"».
 *
 * Asi que aqui hay **dos palabras distintas y no una**, y la diferencia importa:
 *
 *   · **la celda sin dato** (`sinDato`) — la operacion **no tiene ese campo**. Se cierra
 *     publicandolo, y **desde #195 la celda dice cual de los motivos es el suyo**.
 *   · **«sin cifrar»** (`SIN_CIFRAR`) — la operacion tiene el campo y lo contesto **vacio**. Se
 *     cierra cerrando D-02a, que es una decision de negocio y no de backend.
 *
 * Meterlas en una sola diria que falta publicar algo que ya esta publicado, y mandaria a quien
 * mantiene el backend a buscar un campo que existe.
 *
 * <h2>Las tablas salen SIN FILTRAR, y eso se dice sin mentir sobre el tamano de nada</h2>
 *
 * Los mandos que las tres pantallas dibujan —ejercicio, programa, sector, criterio, tipo de acta,
 * verificador, el par de fechas— **no se mandan**. Unos porque la operacion no los admite y otros
 * porque `Conector.pedir` no recibe todavia lo tecleado; el filtro que entra es #172 y no se
 * adelanta aqui.
 *
 * Lo que eso significa en cada tabla es distinto, y se dice tabla por tabla en su javadoc. Lo que
 * **ninguna** de las tres hace es afirmar un tamano: el encabezado que el interprete escribe cuenta
 * las filas que recibe, que es cierto de lo que se ve, y ninguna de las tres pide una pagina
 * grande para parecer un padron.
 */

/**
 * **Una celda que llego sin dato, con el motivo dentro** (`kamayuk-lib`#87, #195).
 *
 * Era `SIN_DATO = '—'`, una raya escrita como cualquier otra cadena, y la raya **sigue siendo lo
 * que se ve**: la declara cada tabla en su `sinDato`. Lo que cambia es que la celda dice **por
 * que**, anunciado con `title`. Aqui eso vale doble, porque en este modulo las celdas vacias
 * tienen **cinco motivos distintos** y la raya decia lo mismo de los cinco: quien mira la pantalla
 * no podia distinguir «esto no lo publica nadie» de «esto es una resta que no se hace aqui».
 *
 * `texto: null` es «aqui no hay dato»: nunca `''` y **nunca un `0`**, que en una columna de
 * diferencias se leeria como «no hay diferencia».
 *
 * Escrita aqui y no importada de `conectores/coactiva.ts`, que tiene la misma: un conector no
 * depende de otro modulo para una palabra. Que las dos digan lo mismo lo sujeta una prueba, que es
 * mas barato que el acoplamiento y falla igual de fuerte.
 */
const sinDato = (porQue: string): CeldaDeLaTabla => ({ texto: null, nota: porQue });

/** «Contribuyente» de la muestra: la fila sorteada no siempre trae titular. */
const SIN_TITULAR =
  'La muestra sortea PREDIOS, y esta fila no trae titular: el predio no tiene ninguno inscrito, o ' +
  'la vista que la sirve no lo trajo. Pedirselo al padron por fila serian veinte lecturas para ' +
  'una columna.';

/** «Diferencia estimada S/» de la muestra: no hay ningun importe que ensenar. */
const SIN_DIFERENCIA_ESTIMADA =
  'La muestra no publica ni un importe: sortea predios por un criterio de cruce, no los valoriza. ' +
  'Estimar la diferencia aqui exigiria el cuadro de valores unitarios firmado, que es D-02a.';

/**
 * «Declarado» y «Diferencia» del acta cuando el lado declarado **no consta** (#191, #215).
 *
 * No es «no publicado»: desde #191 la operacion publica las tres —`areaDeclarada`, `usoDeclarado` y
 * `diferenciaDeArea`—, resueltas desde la version de ficha que el acta referencia. Que lleguen
 * nulas significa que **no hay contra que contrastar**, y son dos casos de verdad: un acta
 * **vehicular** —un vehiculo no tiene area ni uso declarados— y una **predial de un predio sin
 * ficha registrada a la fecha de la visita**, que es justamente el predio que no consta en el
 * catastro.
 *
 * La distincion no es academica: esto se cierra con una ficha y no publicando un campo, o sea que
 * decirlo con la palabra de «no publicado» mandaria a quien mantiene el backend a buscar algo que
 * ya esta.
 */
const NO_CONSTA_LO_DECLARADO =
  'El acta publica su lado declarado desde #191, y en esta llega vacio: no hay contra que ' +
  'contrastar. Pasa en un acta VEHICULAR —un vehiculo no tiene area ni uso declarados— y en una ' +
  'predial de un predio sin ficha registrada a la fecha de la visita. No es que falte publicarlo: ' +
  'es que no consta.';

/** «Diferencia» de la fila del USO: no es un numero, y el artboard la dibuja con una raya. */
const SIN_DIFERENCIA_DE_UN_USO =
  'La diferencia de un uso no es un numero: «Casa habitacion» contra «Comercio» no se resta. El ' +
  'artboard dibuja esa misma celda con una raya, y la operacion tampoco publica ninguna: ' +
  '«diferenciaDeArea» es de area, como su nombre dice.';

/** «Situacion» del acta: el hallazgo es lo unico que dice en que quedo, y puede no estar. */
const SIN_HALLAZGO =
  'El acta no trae hallazgo anotado, y es lo unico que publica de en que quedo la inspeccion: no ' +
  'hay una situacion por concepto. Escribir «Conforme» seria afirmar que el predio esta en regla.';

/** «Area hallada» sin medir: el acta existe y la magnitud no llego. */
const SIN_AREA_HALLADA =
  'Esta acta no publica ninguna superficie medida en campo. No es cero: cero seria un predio sin ' +
  'area construida.';

/** «Interes S/» de la resolucion: no lo publica ninguna de las dieciseis operaciones. */
const SIN_INTERES =
  'Ninguna de las dieciseis operaciones de fiscalizacion publica un interes: el cuadro que se ' +
  'imprime lleva «Multa» donde el prototipo decia «Interes». Cerrarlo es del backend.';

/**
 * Un importe para una columna que ya dice «S/»; «sin cifrar» cuando llega nulo (D-02a).
 *
 * Aqui solo se decide el nulo: la cifra la escribe `formatearImporteEnColumna`. Hasta #389 este
 * archivo recortaba el prefijo con su propia copia de `LA_MONEDA`, la segunda del arbol.
 */
function enColumnaDeSoles(importe: string | null): string {
  return importe === null ? SIN_CIFRAR : formatearImporteEnColumna(importe);
}

/** Las operaciones de este modulo que reciben parametros, escritas una vez (#172). */
const MUESTRA_DEL_PROGRAMA = 'GET /fiscalizacion/programas/{id}/muestra';

/**
 * `fis-prog` — la muestra sorteada de un programa de fiscalizacion.
 *
 * <h2>Dos operaciones, y la segunda no se puede pedir sin la primera</h2>
 *
 * `GET /fiscalizacion/programas?tamano=1` dice **cual** programa se dibuja —el primero de la
 * relacion, porque esta pantalla todavia no tiene con que elegirlo— y
 * `GET /fiscalizacion/programas/{id}/muestra` trae sus predios sorteados. Es la misma composicion
 * que `coa-exp` (#170) y que el panel del predial: la segunda lectura necesita una llave que solo
 * publica la primera.
 *
 * Con la relacion vacia no hay programa que pedir y el conector devuelve `null`, que la pantalla
 * dice como «sin datos» — un hecho del negocio, no una averia. Un programa **sin muestra
 * sorteada** es distinto: contesta 200 con la pagina vacia, y la tabla sale sin filas.
 *
 * <h2>Columna por columna: cuatro de cinco</h2>
 *
 * <ul>
 *   <li><b>Codigo predial</b> ← `codRefCatastral`, «el codigo con el que se identifica el predio
 *       en ventanilla».</li>
 *   <li><b>Contribuyente</b> ← `titular`. Sale la raya cuando el predio **no tiene titular
 *       vigente**, que es un dato y no un hueco de la interfaz.</li>
 *   <li><b>Causa del cruce</b> ← `condicion`, que es «lo que la deteccion concluyo ese dia»
 *       congelado en el sorteo: `CONFORME`, `OMISO`, `SUBVALUADOR`, `USO_DISTINTO` o
 *       `NO_UBICADO`. Es el mismo vocabulario que el desplegable «Criterio del cruce» de la
 *       pantalla, y por eso no se traduce a nada: lo que llega es lo que la columna dice.</li>
 *   <li><b>Diferencia estimada S/</b> → <b>la raya</b>. La muestra **no publica ningun importe**:
 *       sus trece campos incluyen tres areas —`areaCatastral`, `areaDeclarada` y
 *       `diferenciaDeArea`, en m² y no en soles— y ni uno de dinero. Quien publica un importe
 *       estimado es `GET /fiscalizacion/omisos`, con `impuestoOmitidoS`, y <b>llega nulo hasta
 *       D-02a</b>; ademas es otra poblacion (ver abajo). Estimar aqui la diferencia a partir de
 *       `diferenciaDeArea` exigiria un valor unitario, que es exactamente lo que D-02a no ha
 *       firmado.</li>
 *   <li><b>Estado</b> ← `visitado`, y esto no es una deduccion de la interfaz: el backend dice de
 *       ese campo que es «de donde sale la columna "Estado" de la grilla». Es un booleano derivado
 *       —«este predio ya tiene acta en este programa», no se guarda— y se escribe con las dos
 *       palabras del propio artboard. <b>La tercera que el artboard ensena, «Con diferencia», no se
 *       puede producir</b>: eso es el `hallazgo` del acta, que esta operacion no trae.</li>
 * </ul>
 *
 * <h2>Por que la tabla NO la llena `GET /fiscalizacion/omisos`, que esta hoja tambien declara</h2>
 *
 * Porque son **dos poblaciones distintas y la tabla nombra una**. Omisos publica la DETECCION —el
 * cruce de catastro contra rentas, que en el artboard son 3 418 predios— y esta tabla se titula
 * «Muestra del programa», que son los sorteados —96 en el mismo artboard, que ademas lo escribe:
 * «96 de 3,418 detectados»—. Pintar la deteccion bajo ese rotulo daria un conteo falso con formato
 * de bueno, que es justo lo que la regla de `conectores.ts` prohibe. Y los cuatro importes de
 * omisos llegan nulos hasta D-02a, asi que ni siquiera compraria la columna que falta.
 *
 * <h2>Sin filtrar, y lo que eso significa aqui</h2>
 *
 * Se dibuja la muestra del **primer** programa de la relacion. Los ocho mandos de la pantalla no se
 * mandan: `GET /fiscalizacion/programas` admite `ejercicio` y `nDePrograma` —los dos publicados— y
 * la muestra admite `predio`, y ninguno tiene hoy por donde entrar.
 *
 * <h2>Pero desde #228 la ventana SI se mueve, y era el hueco de verdad</h2>
 *
 * Desde #172 el encabezado dice **«2 de 84»** —el `totalElementos` que la operacion publica, o sea
 * cuantos predios sorteo el programa— y la tabla **no declaraba `paginacion`**: nombraba lo que
 * faltaba y no lo daba, que es honesto y peor que incompleto. Ahora la declara, y este conector lee
 * la ventana de la ruta de la hoja —`#/fis-prog?pagina=2&ordenarPor=condicion`— con los cuatro
 * sitios que `laVentanaDe` declara para la operacion de la muestra.
 *
 * **La ventana viaja a la MUESTRA y no a la relacion de programas**, que sigue pidiendose con
 * `?tamano=1`: paginar la relacion cambiaria **cual** programa se dibuja, no que trozo de su
 * muestra se ve. Son dos lecturas y el mando es de la segunda.
 *
 * `hayMas` y `totalPaginas` **los dice el servidor** y viajan por `nombrados`: contar las filas
 * recibidas diria que no hay pagina siguiente justo cuando el tope se alcanza exacto.
 */
const FIS_PROG: Conector = {
  clave: ['fis-prog', 'muestra-del-programa'],
  parametros: laVentanaDe(MUESTRA_DEL_PROGRAMA),
  pedir: async ({ senal, enLaRuta }) => {
    const relacion = await pedirPagina<ProgramaDeFiscalizacion>(
      RUTAS.programasDeFiscalizacion,
      senal,
    );
    const primero = relacion.contenido[0];
    // Sin programa no hay muestra que pedir. `null` es «se pregunto y no hay», que la pantalla
    // dice distinto de un fallo.
    if (primero === undefined) return null;
    return pedirPagina<FilaDeLaMuestra>(
      RUTAS.muestraDelPrograma(
        primero.id,
        laVentanaQueSePide('fis-prog', 'muestra-del-programa', enLaRuta),
      ),
      senal,
    );
  },
  repartir: (muestra: Paginado<FilaDeLaMuestra>): Reparto => ({
    valores: new Map(),
    // Vacio: esta tabla lleva `clave` desde #195, asi que sus filas van por `tablas`.
    filas: new Map(),
    tablas: new Map([
      [
        'muestra-del-programa',
        {
          filas: muestra.contenido.map((fila) => ({
            clave: fila.codRefCatastral,
            celdas: [
              fila.codRefCatastral,
              fila.titular ?? sinDato(SIN_TITULAR),
              fila.condicion,
              sinDato(SIN_DIFERENCIA_ESTIMADA),
              fila.visitado ? 'Inspeccionado' : 'Programado',
            ],
          })),
          // El que la OPERACION publica: cuantos predios sorteo el programa, y no cuantos llegaron.
          totalElementos: muestra.totalElementos,
        },
      ],
    ]),
    nombrados: loQueDijoElServidor('muestra-del-programa', muestra),
    noPublicados: new Map(),
  }),
};

/**
 * Las dos filas del contraste de un acta: una por magnitud que el acta publica.
 *
 * **El rotulo de la fila es el nombre del campo que esa fila ensena**, no un dato: la operacion
 * publica las magnitudes como campos y la tabla las quiere como filas, asi que alguien tiene que
 * decir cual es cual. Es lo mismo que hace una cabecera de columna, y vive aqui porque la
 * definicion de la pantalla no tiene donde ponerlo.
 *
 * Se escribe **«Area hallada»** y no «Area construida», que es lo que el artboard dibuja: el acta
 * publica **una** superficie —`areaHallada`, «la superficie medida en campo»— y el artboard
 * distingue tres filas de area —terreno, construida y numero de pisos—. Decir cual de las tres es
 * la que llego seria elegir por el backend; el artboard dibuja el caso completo y lo que se sirve
 * es una.
 *
 * **La fila del uso solo sale cuando hay uso anotado.** `usoHallado` nulo significa «no se anoto»,
 * que el backend dice expresamente que **no es lo mismo que "coincide con el declarado"** —y solo
 * un acta predial lo lleva—: una fila con cuatro rayas de cinco no informaria de eso, informaria de
 * que la pantalla esta rota.
 */
function contrasteDelActa(acta: ActaDeFiscalizacion): readonly (readonly CeldaDeLaTabla[])[] {
  // `hallazgo` es lo que una persona anoto en campo, y es lo unico que el acta dice de en que
  // quedo cada magnitud: no hay una situacion por concepto. Va igual en las dos filas porque el
  // acta no la reparte, y decir «Conforme» en una y no en otra seria repartirla aqui.
  const situacion = acta.hallazgo ?? sinDato(SIN_HALLAZGO);
  return [
    [
      'Área hallada (m²)',
      acta.areaDeclarada ?? sinDato(NO_CONSTA_LO_DECLARADO),
      acta.areaHallada ?? sinDato(SIN_AREA_HALLADA),
      // **La diferencia se COPIA, no se resta** (#191): la publica el backend «nunca negativa,
      // nula si falta un lado», con la misma funcion pura que usan la liquidacion y la deteccion.
      // Restar `hallada − declarada` aqui —con los dos lados ya delante— seria publicar una cifra
      // que ninguna operacion afirma, y esta es la columna que sostiene la determinacion.
      acta.diferenciaDeArea ?? sinDato(NO_CONSTA_LO_DECLARADO),
      situacion,
    ],
    ...(acta.usoHallado === null
      ? []
      : [
          [
            'Uso del predio',
            acta.usoDeclarado ?? sinDato(NO_CONSTA_LO_DECLARADO),
            acta.usoHallado,
            // Y esta se queda en la raya con TODO publicado: la diferencia de un uso no es un
            // numero, y el artboard la dibuja asi.
            sinDato(SIN_DIFERENCIA_DE_UN_USO),
            situacion,
          ],
        ]),
  ];
}

/**
 * `fis-actas` — el contraste de lo declarado contra lo verificado, de un acta de inspeccion.
 *
 * <h2>Una operacion, y se dibuja UNA acta</h2>
 *
 * `GET /fiscalizacion/actas?tamano=1`. La tabla de esta hoja **no es una relacion de actas**: sus
 * columnas son «Concepto, Declarado, Verificado, Diferencia, Situacion», o sea las magnitudes de
 * una sola inspeccion. Y la pantalla todavia no tiene con que elegirla, asi que se toma la primera
 * de la relacion —ordenada por `fechaVisita`, «como se recorre una jornada de campo»—, que es lo
 * que ya hacen `coa-exp` y `coa-cost`. Sin ninguna acta, `null`: «sin datos», no una averia.
 *
 * <h2>Y desde #239 DICE cual es</h2>
 *
 * Hasta aqui tomaba la primera y **no decia de quien era**, que es lo que su propio javadoc
 * denunciaba: un contraste de areas que no nombra al obligado no se puede comprobar contra nada —se
 * lee como si fuera del contribuyente que uno tenia en la cabeza—. #216 publico `contribuyente` y
 * `codContribuyente`, asi que ya hay con que decirlo.
 *
 * **Va por `Reparto.deQuienEs` y no a un campo**, y esa es la decision que #239 dejaba abierta. El
 * sitio que el artboard le da al titular en esta hoja es el campo «Contribuyente», que es de tipo
 * `1` —un **mando**, un control de entrada— y no una celda: escribir dentro el nombre de un acta ya
 * registrada convierte el formulario con que se registra una inspeccion en algo que parece estar
 * editando esa. Las tres salidas que el issue ofrecia tocaban el artboard —una celda nueva, o la
 * nota de la tabla, que `pantallas-del-artboard` compara palabra por palabra— o la libreria. La
 * cuarta es la que #196 ya tomo para la fecha: se dice **una vez, arriba**, en la frase de pantalla
 * que el interprete ya dibuja.
 *
 * **Nulo es «ya no esta en el padron», no «no publicado»**: los dos campos llegan nulos a la vez
 * cuando el obligado se dio de baja, y el acta sale igual porque ocultarla esconderia justo el caso
 * que hay que revisar (#216).
 *
 * <h2>Media tabla era la raya, y desde #191 no: el acta publica las DOS mitades</h2>
 *
 * Hasta #191 `ActaFiscalizacionResource` publicaba solo el lado hallado —`areaHallada` y
 * `usoHallado`—, de modo que de las dos mitades que el titulo de la tabla contrasta —«Lo que el
 * verificador midio frente a lo que el titular declaro»— la operacion servia una, y `SIN_DATO`
 * caia en **4 celdas de 10**. Ahora viajan `areaDeclarada`, `usoDeclarado` y `diferenciaDeArea`,
 * resueltos por `ActaConLoDeclarado` desde la version de ficha que el acta referencia.
 *
 * <ul>
 *   <li><b>Concepto</b> ← el nombre de la magnitud (ver `contrasteDelActa`).</li>
 *   <li><b>Declarado</b> ← `areaDeclarada` y `usoDeclarado`.</li>
 *   <li><b>Verificado</b> ← `areaHallada` y `usoHallado`.</li>
 *   <li><b>Diferencia</b> ← `diferenciaDeArea` <b>en la fila del area</b>, y <b>copiada</b>: la
 *       publica el backend «nunca negativa, nula si falta un lado», con la misma funcion pura que
 *       usan la liquidacion y la deteccion. <b>Aqui no se resta</b> aunque ahora esten los dos
 *       lados delante — restar dos magnitudes servidas para llenar una celda es publicar una cifra
 *       que ninguna operacion afirma, y esta es la columna que sostiene la determinacion. En la
 *       fila del <b>uso</b> se queda en la raya con todo publicado: la diferencia de un uso no es
 *       un numero, y el artboard la dibuja asi.</li>
 *   <li><b>Situacion</b> ← `hallazgo`, lo que el fiscalizador anoto: `CONFORME`, `OMISO`,
 *       `SUBVALUADOR`, `USO_DISTINTO`, `NO_UBICADO`. <b>No es `estado`</b>, que es en que punto
 *       esta el papel —`ABIERTA`, `LIQUIDADA`, `ANULADA`— y no que se encontro en el predio.</li>
 * </ul>
 *
 * <h2>Y cuando los tres llegan nulos, eso NO es «no publicado»: es «no consta»</h2>
 *
 * Un acta **vehicular** —un vehiculo no tiene area ni uso declarados contra los que contrastar— y
 * una **predial de un predio sin ficha registrada a la fecha de la visita** llegan con los tres en
 * nulo. Se cierra con una ficha y no publicando un campo, asi que la celda lo dice con su causa
 * —`NO_CONSTA_LO_DECLARADO`— y no con la palabra que manda a buscar lo que ya esta.
 *
 * **`GET /fiscalizacion/resultados` sigue sin pedirse desde aqui**, y no por lo que decia antes:
 * es la LIQUIDACION y esta pantalla es la etapa anterior —su propia nota lo dice, «sin acta
 * levantada no se puede liquidar» (#241)—, y sus lineas son por ejercicio y por unidad, no por
 * concepto. Pintar aqui
 * la liquidacion de otra acta seria ensenar el resultado de un paso que esta pantalla no ha dado.
 *
 * <h2>Sin filtrar, y desde #242 tampoco hay ninguno que mandar</h2>
 *
 * `GET /fiscalizacion/actas` admitia **un** filtro, `?programa=`, y esta hoja no lo mandaba:
 * acotarla exige haber elegido programa, y elegirlo aqui seria decidir por quien atiende cual
 * inspeccion se mira. Los nueve mandos de la pantalla son los de registrar un acta, no los de
 * buscarla.
 *
 * **Ese filtro ya no existe** (#242). Su unico motivo escrito era llenar la etapa «Inspeccionados»
 * de un embudo con el `totalElementos` de esta operacion acotada al programa, que es exactamente la
 * composicion que este archivo prohibe y que #196 midio que no se puede cuadrar — y que ademas no
 * daba el numero, porque ese total cuenta **actas** y el embudo cuenta **unidades**. El embudo lo
 * publica entero `GET /fiscalizacion/programas/{id}/embudo`, que es lo que `fis-panel` consume.
 * Hoy la operacion solo admite lo de la paginacion, y cualquier otro parametro es 422.
 */
const FIS_ACTAS: Conector = {
  clave: ['fis-actas', 'acta-de-inspeccion'],
  pedir: async ({ senal }) => {
    const relacion = await pedirPagina<ActaDeFiscalizacion>(RUTAS.actasDeFiscalizacion, senal);
    return relacion.contenido[0] ?? null;
  },
  repartir: (acta: ActaDeFiscalizacion): Reparto => ({
    valores: new Map(),
    filas: new Map(),
    tablas: new Map([
      [
        'declarado-contra-verificado',
        {
          // Sin total: las filas son las MAGNITUDES que el acta publica, no una pagina de nada.
          filas: contrasteDelActa(acta).map((celdas) => ({ celdas })),
        },
      ],
    ]),
    // De quien es el acta que se dibuja (#239). Las dos piezas crudas: la frase la redacta el
    // gancho, que tiene `t()` delante.
    deQuienEs: { nombre: acta.contribuyente, codigo: acta.codContribuyente },
    noPublicados: new Map(),
  }),
};

/** Una linea de la resolucion, en las cinco columnas de «Detalle por ejercicio». */
function filaDelEjercicio(linea: LineaDeterminada): readonly CeldaDeLaTabla[] {
  return [
    String(linea.ejercicio),
    // «Base omitida S/» ← `baseOmitida`, que desde #193 la resta el BACKEND: es
    // «determinado − declarado», nunca negativa. Los dos sumandos ya viajaban y la resta no, y
    // hacerla aqui seria aritmetica sobre dinero en el navegador (regla 1, RNF-055).
    enColumnaDeSoles(linea.baseOmitida),
    // «Insoluto S/» ← `diferencia`, que pese al nombre es «el tributo que se dejo de pagar».
    enColumnaDeSoles(linea.diferencia),
    sinDato(SIN_INTERES),
    enColumnaDeSoles(linea.total),
  ];
}

/**
 * Un total de la resolucion, con **cual de los tres huecos** le toca si no hay cifra (#193).
 *
 * Los tres no se confunden, y aqui conviven dos:
 *
 *   · con cifra, la cifra formateada — va a `valores`;
 *   · sin cifra y `esperaSusCifras`, **«sin cifrar»**: el campo existe y llego vacio (D-02a). Va al
 *     hueco, porque no es un valor: es la ausencia de uno, con su causa;
 *   · sin cifra y **sin** esperar, «no publicado»: el backend dice que las cifras estan y no las
 *     manda. Eso es un defecto suyo y hay que poder verlo, no taparlo con la palabra de D-02a.
 *
 * Devuelve la pareja y no solo el texto porque el sitio importa: un `valores` con «sin cifrar»
 * dentro pintaria esa palabra **como si fuera el importe**, sin el tono ni el `title` con que el
 * interprete dibuja un hueco.
 */
function totalDeLaResolucion(
  importe: string | null,
  espera: boolean,
): { readonly valor: string } | { readonly hueco: PalabraDeHueco } {
  if (importe !== null) return { valor: formatearImporte(importe) };
  return { hueco: espera ? SIN_CIFRAR : NO_PUBLICADO };
}

/**
 * `fis-res` — la resolucion de determinacion.
 *
 * <h2>Dejo de EXIGIR sujeto y pasa a ADMITIRLO, que no es lo mismo (#192, #215)</h2>
 *
 * Exigia sujeto desde #179 y con motivo: `GET /fiscalizacion/resoluciones/{numero}` es de UNA
 * resolucion y **no existia ninguna operacion que publicara la relacion**, asi que no habia
 * «primera» que tomar como hacen `coa-exp` y las otras dos de este archivo. La consecuencia estaba
 * medida: abierta desde el menu, **esta pantalla no ensenaba una resolucion nunca**.
 *
 * #192 publico `GET /fiscalizacion/resoluciones`, paginada. Asi que ahora:
 *
 * <ul>
 *   <li>con numero en la direccion —`#/fis-res/RDF-2026-000001`— se pide <b>esa</b>, y la relacion
 *       no se pide siquiera;</li>
 *   <li>sin numero se toma <b>la primera de la relacion</b> —`?tamano=1`, ordenada por `numero`,
 *       que es el `ORDEN_POR_OMISION` de `ResolucionController`— y con ella se pide su detalle.</li>
 * </ul>
 *
 * **Retirar `exigeSujeto` a secas habria costado la mitad buena**: `catalogo.ts` deriva de el el
 * sitio del sujeto, y sin esa linea el marco **tira** el numero de la direccion con un aviso, de
 * modo que un enlace a una resolucion concreta abriria siempre la primera del padron. Por eso la
 * tercera forma, `admiteSujeto`; ver su javadoc en `datos/conectores.ts`.
 *
 * Con la relacion vacia devuelve `null`: «se pregunto y no hay», que la pantalla dice como «sin
 * datos». Un numero que no existe sigue siendo **404** del backend, y eso es correcto —lo pidio
 * quien escribio la direccion—.
 *
 * <h2>Campo a campo: de UNO de seis a CUATRO de seis (#192, #193)</h2>
 *
 * <ul>
 *   <li><b>`0|1` Contribuyente</b> ← `contribuyente`, el nombre del obligado.</li>
 *   <li><b>`0|3` Insoluto omitido</b>, <b>`0|5` Multa tributaria</b> y <b>`0|7` Total
 *       liquidado</b> ← `insolutoOmitido`, `multaTributaria` y `totalLiquidado`, que **suma el
 *       backend** desde #193 (`TotalesDeLaDeterminacion`, funcion pura). Aqui **no se suma la
 *       tabla** y eso no cambia: la rotura R2 de #179 midio esa suma dando <b>290.20 al centimo</b>
 *       —o sea el total real— sobre el papel que vuelve una diferencia deuda exigible. Con D-02a
 *       abierta los tres llegan nulos y lo que se escribe es <b>«sin cifrar»</b> y no un cero, que
 *       un contribuyente leeria como «no debe nada»; quien separa ese hueco del otro sin adivinar
 *       es `esperaSusCifras`. Ver `totalDeLaResolucion`.</li>
 *   <li><b>`0|0` Nº de acta</b> → <b>«no publicado»</b>, <b>y se decidio con el artboard
 *       delante</b> (#215). La operacion publica `actaId` desde #193, y escribirlo ahi seria poner
 *       un identificador donde el usuario espera un papel: el artboard dibuja ese campo con
 *       <b>«ACT-2026-00418»</b>, o sea el numero de un documento, y el propio backend lo deja
 *       escrito en su javadoc — «es un identificador interno y no el numero de un documento: un
 *       acta no se numera —lo que la identifica es su programa, su unidad y su version— asi que
 *       esto sirve para enlazar hacia atras, <b>no para escribirlo en un campo rotulado N.º de
 *       acta</b>». Tampoco vale `documentoSustento`, que se parece —en la muestra dice justamente
 *       «ACT-2026-00418»— y es <b>texto libre del cuerpo de la transferencia</b>: lo teclea quien
 *       transfiere, y nada garantiza que sea el acta. O un acta se numera, o esa celda pide que se
 *       numere; las dos son decisiones y ninguna se toma escribiendo un `id` debajo del rotulo.</li>
 *   <li><b>`0|4` Interes</b> → <b>«no publicado»</b>, y este no es una suma: **no existe**. No hay
 *       en este sistema ninguna tasa de interes moratorio sellada, y ponerle una seria inventar un
 *       valor normativo (regla 5) sobre el valor que arranca el plazo del art. 137. El cuadro que
 *       <b>se imprime</b> lleva «Multa» donde el prototipo decia «Interes». O se sella o se quita
 *       del artboard: es <b>#213</b>, y hasta entonces el hueco es lo que lo pide.</li>
 * </ul>
 *
 * <h2>La tabla: cuatro columnas de cinco</h2>
 *
 * Ver `filaDelEjercicio`. «Base omitida S/» **deja de ser la raya**: la resta el backend desde #193
 * —`determinado − declarado`, nunca negativa—. «Interes S/» se queda, por lo de arriba. Y las tres
 * de dinero dicen <b>«sin cifrar»</b> mientras D-02a no este cerrada, que es distinto de la raya:
 * el campo existe y llega vacio. Las dos ramas estan probadas.
 *
 * <h2>Sin filtrar</h2>
 *
 * La operacion del detalle no admite ni un parametro, y a la relacion **no se le manda su unico
 * filtro** —`?contribuyente=`—: acotarla exige haber elegido a alguien, y elegirlo aqui seria
 * decidir por quien atiende de quien es la resolucion que se mira. Lo que la pantalla dibuja y no
 * viaja son sus dos desplegables —«Ejercicios alcanzados» y «Articulo del Codigo Tributario»—, que
 * ademas no son filtros sino decisiones de la emision, o sea de `POST /fiscalizacion/liquidaciones`.
 * Y la resolucion **ya trae su periodo** hecho, en `periodoDesde` y `periodoHasta`.
 */
const FIS_RES: Conector = {
  clave: ['fis-res', 'resolucion-de-determinacion'],
  admiteSujeto: true,
  pedir: async ({ senal, sujeto }) => {
    // Con numero en la direccion se pide ESE, y la relacion no se toca: pedirla seria una ida de
    // mas para elegir lo que ya esta elegido.
    if (sujeto !== null && sujeto !== '') {
      return pedirUno<ResolucionDeDeterminacion>(RUTAS.resolucionDeDeterminacion(sujeto), senal);
    }
    const relacion = await pedirPagina<ResolucionEnLaRelacion>(
      RUTAS.resolucionesDeDeterminacion,
      senal,
    );
    const primera = relacion.contenido[0];
    // Sin ninguna transferida no hay resolucion que pedir. `null` es «se pregunto y no hay».
    if (primera === undefined) return null;
    return pedirUno<ResolucionDeDeterminacion>(
      RUTAS.resolucionDeDeterminacion(primera.numero),
      senal,
    );
  },
  repartir: (resolucion: ResolucionDeDeterminacion): Reparto => {
    const valores = new Map<Coordenada, string>([[coordenada(0, 1), resolucion.contribuyente]]);
    const noPublicados = new Map<Coordenada, PalabraDeHueco>([
      // El `actaId` es interno y el rotulo pide un documento; el interes no lo publica nadie (#213).
      [coordenada(0, 0), NO_PUBLICADO],
      [coordenada(0, 4), NO_PUBLICADO],
    ]);
    const totales = [
      [coordenada(0, 3), resolucion.insolutoOmitido],
      [coordenada(0, 5), resolucion.multaTributaria],
      [coordenada(0, 7), resolucion.totalLiquidado],
    ] as const;
    for (const [donde, importe] of totales) {
      const cual = totalDeLaResolucion(importe, resolucion.esperaSusCifras);
      if ('valor' in cual) valores.set(donde, cual.valor);
      else noPublicados.set(donde, cual.hueco);
    }

    return {
      valores,
      filas: new Map(),
      tablas: new Map([
        [
          'detalle-por-ejercicio',
          // Sin total: `lineas[]` son los ejercicios alcanzados y vienen todas, no paginadas.
          { filas: resolucion.lineas.map((linea) => ({ celdas: filaDelEjercicio(linea) })) },
        ],
      ]),
      // A que dia estan sus cifras (regla 9, RNF-075). Aqui es **dinero notificable** y no un
      // recuento, asi que pesa mas que en el embudo: la operacion lo publica aparte del `fecha`
      // «para no dejarlo implicito», y esta pantalla tampoco tiene un campo libre donde decirlo.
      aLaFecha: resolucion.aLaFecha,
      noPublicados,
    };
  },
};

/**
 * `fis-panel` — el embudo de un programa de fiscalizacion, de lo detectado a lo determinado (#196).
 *
 * <h2>Dejo de estar sin conectar, y lo que faltaba era una OPERACION</h2>
 *
 * Declaraba `GET /fiscalizacion/estado-cuenta`, que no publica nada de lo que esta pantalla ensena
 * —es la deuda de fiscalizacion de UN contribuyente, con `?contribuyente=` obligatorio—. #196
 * publico `GET /fiscalizacion/programas/{id}/embudo`, que trae **las cuatro cifras juntas, cuadradas
 * y en una sola lectura**. Se compone como `fis-prog`: la relacion de programas con `?tamano=1` dice
 * cual programa, y el embudo se pide con su `id`.
 *
 * **Lo que NO se hace, y es la regla de `conectores.ts`**: componer el embudo con el
 * `totalElementos` de cuatro operaciones distintas. Serian cuatro peticiones para cuatro numeros
 * que ninguna operacion afirma que signifiquen eso, tres de ellas acotadas a mano al programa, y el
 * resultado **se lee igual** que uno publicado sin que ninguno de los dos se pueda cuadrar.
 *
 * <h2>Campo a campo: las cuatro cifras, y desde #241 las cuatro se pintan</h2>
 *
 * <ul>
 *   <li><b>`0|0` Ejercicio</b> ← `ejercicio` del embudo, no la opcion que toco por omision: es el
 *       ejercicio del programa cuyas cifras estan debajo. Nulo en un programa anterior a `V60`.</li>
 *   <li><b>`0|1` Programa</b> ← `codigo`, que es el «N.º de programa» que la pantalla teclea —y no
 *       el `id`, que es interno—. Afirma <b>cual</b> programa se esta mirando, que con el
 *       desplegable en «Todos» quedaria sin decir.</li>
 *   <li><b>`0|2` Detectados por cruce</b> ← `detectadosPorCruce`. Nulo cuando el programa no
 *       declara sus parametros de sorteo, y entonces el hueco lo dice (ver
 *       `SIN_PARAMETROS_DEL_SORTEO`) — nunca un cero.</li>
 *   <li><b>`0|3` Programados</b> ← `programados`.</li>
 *   <li><b>`0|4` Con acta levantada</b> ← `conActa`. <b>Hasta #241 esta celda era un hueco</b>, y
 *       lo era por el rotulo: decia «Con acta cerrada», `conActa` cuenta las unidades con acta
 *       <b>viva</b> —levantada y no anulada— y pintarlo debajo habria dicho otra cosa. #215
 *       planteaba tres salidas —o se cierra #214, o el rotulo cambia en el artboard, o la celda
 *       dice por que no—; se tomo la tercera, y #241 tomo la segunda con la medida entera delante.
 *       Lo que la decidio no es una opinion sobre el rotulo sino <b>dos frases del propio
 *       artboard</b>: la nota de esta hoja dice «Lo detectado, lo <b>inspeccionado</b> y lo que
 *       sostiene una determinacion» —tres cosas para cuatro cifras, y la tercera es la
 *       inspeccion—, y la de `fis-actas` situaba el cierre <b>antes</b> de liquidar —«sin acta
 *       cerrada no se puede liquidar»—, que es lo contrario de lo que #214 llamo «cerrada», o sea
 *       que el acta <b>tenga</b> liquidacion. Con el rotulo corregido la celda dice lo que la
 *       cifra cuenta: la etapa que el manual llama «Inspeccionados». La publica el embudo, **en
 *       unidades**; no se compone con el `totalElementos` de la relacion de actas, que cuenta
 *       filas y que desde #242 ni siquiera admite acotarse a un programa.</li>
 *   <li><b>`0|5` Con diferencia</b> ← `conDiferencia`.</li>
 * </ul>
 *
 * <h2>Y la hoja dice de CUANDO son</h2>
 *
 * `aLaFecha` viaja por `Reparto.aLaFecha` y la pantalla lo escribe arriba (regla 9, RNF-075). No es
 * decorativo: las tres ultimas etapas estan congeladas por lo que se sorteo y se visito, y la
 * primera se resuelve contra el padron de **hoy**, asi que dos aperturas del mismo programa en dos
 * dias pueden dar embudos distintos sin que nada haya fallado.
 */
const FIS_PANEL: Conector = {
  clave: ['fis-panel', 'embudo-del-programa'],
  pedir: async ({ senal }) => {
    const relacion = await pedirPagina<ProgramaDeFiscalizacion>(
      RUTAS.programasDeFiscalizacion,
      senal,
    );
    const primero = relacion.contenido[0];
    // Sin programa no hay embudo. `null` es «se pregunto y no hay», no una averia.
    if (primero === undefined) return null;
    return pedirUno<EmbudoDelPrograma>(RUTAS.embudoDelPrograma(primero.id), senal);
  },
  repartir: (embudo: EmbudoDelPrograma): Reparto => {
    const valores = new Map<Coordenada, string>([
      [coordenada(0, 1), embudo.codigo],
      // Los conteos agrupados, como el artboard escribe «3,418» (#389).
      [coordenada(0, 3), formatearEntero(embudo.programados)],
      [coordenada(0, 4), formatearEntero(embudo.conActa)],
      [coordenada(0, 5), formatearEntero(embudo.conDiferencia)],
    ]);
    const noPublicados = new Map<Coordenada, PalabraDeHueco>();

    // El ejercicio del programa, por la regla de todos los paneles (#390). Uno anterior a `V60` no
    // lo lleva, y entonces se escribe «Todos», que no es una opcion de esta hoja: el control se
    // queda EN BLANCO. Hasta #390 esta rama registraba `NO_PUBLICADO` aqui y decia que el
    // desplegable «se queda en su primera opcion en vez de afirmar un ano que nadie dijo»; pero el
    // interprete no dibuja la palabra en un desplegable, y su primera opcion ES un ano — «2026».
    valores.set(coordenada(0, 0), ejercicioDeLaRespuesta(embudo.ejercicio));

    if (embudo.detectadosPorCruce !== null) {
      valores.set(coordenada(0, 2), formatearEntero(embudo.detectadosPorCruce));
    } else {
      noPublicados.set(coordenada(0, 2), SIN_PARAMETROS_DEL_SORTEO);
    }

    // Esta hoja no tiene tabla: su bloque son dos mandos y cuatro cifras.
    return { valores, filas: new Map(), aLaFecha: embudo.aLaFecha, noPublicados };
  },
};

/** Las cuatro hojas de Fiscalizacion, que desde #215 piden **las cuatro**. */
export const CONECTORES_DE_FISCALIZACION = {
  'fis-panel': FIS_PANEL,
  'fis-prog': FIS_PROG,
  'fis-actas': FIS_ACTAS,
  'fis-res': FIS_RES,
} as const;

export {
  FIS_ACTAS,
  FIS_PANEL,
  FIS_PROG,
  FIS_RES,
  NO_CONSTA_LO_DECLARADO,
  SIN_AREA_HALLADA,
  SIN_CIFRAR,
  SIN_DIFERENCIA_DE_UN_USO,
  SIN_DIFERENCIA_ESTIMADA,
  SIN_HALLAZGO,
  SIN_INTERES,
  SIN_PARAMETROS_DEL_SORTEO,
  SIN_TITULAR,
  contrasteDelActa,
  filaDelEjercicio,
  sinDato,
  totalDeLaResolucion,
};

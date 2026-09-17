import { coordenada, type CeldaDeLaTabla } from '@kamayuk/ui';

import { formatearImporte } from '../../dominio/formato.ts';
import type {
  ActaDeFiscalizacion,
  FilaDeLaMuestra,
  LineaDeterminada,
  Paginado,
  ProgramaDeFiscalizacion,
  ResolucionDeDeterminacion,
} from '../lecturas.ts';
import { RUTAS, pedirPagina, pedirUno } from '../lecturas.ts';
import type { Conector, Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';
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

/** «Declarado» del acta: el acta publica el lado hallado y no el declarado. */
const SIN_DECLARADO =
  'ActaFiscalizacionResource publica «areaHallada» y «usoHallado» y NO publica «areaDeclarada» ni ' +
  '«usoDeclarado»: de las dos mitades que esta tabla contrasta, la operacion sirve una. Quien ' +
  'publica las dos es GET /fiscalizacion/resultados, que es la liquidacion y no esta etapa.';

/** «Diferencia» del acta: es una resta, y no se hace aqui. */
const SIN_DIFERENCIA_DEL_ACTA =
  'La diferencia es «hallada − declarada», y no solo falta el minuendo: restar dos magnitudes ' +
  'servidas para llenar una celda es calcular lo que nadie publico. Quien la publica hecha es la ' +
  'liquidacion, con «diferenciaDeArea».';

/** «Situacion» del acta: el hallazgo es lo unico que dice en que quedo, y puede no estar. */
const SIN_HALLAZGO =
  'El acta no trae hallazgo anotado, y es lo unico que publica de en que quedo la inspeccion: no ' +
  'hay una situacion por concepto. Escribir «Conforme» seria afirmar que el predio esta en regla.';

/** «Area hallada» sin medir: el acta existe y la magnitud no llego. */
const SIN_AREA_HALLADA =
  'Esta acta no publica ninguna superficie medida en campo. No es cero: cero seria un predio sin ' +
  'area construida.';

/** «Base omitida S/» de la resolucion: es una resta sobre dinero, y no se hace en el navegador. */
const SIN_BASE_OMITIDA =
  'La base omitida es «determinado − declarado», y la resolucion publica los dos sumandos y no la ' +
  'resta. Restarlos aqui seria aritmetica sobre dinero en el navegador (regla 1, RNF-055).';

/** «Interes S/» de la resolucion: no lo publica ninguna de las dieciseis operaciones. */
const SIN_INTERES =
  'Ninguna de las dieciseis operaciones de fiscalizacion publica un interes: el cuadro que se ' +
  'imprime lleva «Multa» donde el prototipo decia «Interes». Cerrarlo es del backend.';

/**
 * Lo que va donde la operacion publica el campo y lo contesta **vacio** (D-02a). Nunca `0.00`.
 *
 * Es la misma palabra que `conectores/inicio.ts` escribe cuando un frente no se puede cifrar, y
 * por el mismo motivo: un cero donde no se ha calculado nada se lee como «no debe nada».
 */
const SIN_CIFRAR = 'sin cifrar';

/** El prefijo de moneda que `formatearImporte` pone siempre; la columna ya lo dice en su rotulo. */
const LA_MONEDA = /^S\/\s/;

/** Un importe para una columna que ya dice «S/»; «sin cifrar» cuando llega nulo (D-02a). */
function enColumnaDeSoles(importe: string | null): string {
  return importe === null ? SIN_CIFRAR : formatearImporte(importe).replace(LA_MONEDA, '');
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
      sinDato(SIN_DECLARADO),
      acta.areaHallada ?? sinDato(SIN_AREA_HALLADA),
      sinDato(SIN_DIFERENCIA_DEL_ACTA),
      situacion,
    ],
    ...(acta.usoHallado === null
      ? []
      : [
          [
            'Uso del predio',
            sinDato(SIN_DECLARADO),
            acta.usoHallado,
            sinDato(SIN_DIFERENCIA_DEL_ACTA),
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
 * <h2>El acta publica el lado HALLADO y NO el declarado, y eso deja media tabla en raya</h2>
 *
 * Es el hallazgo de este issue y no un recorte: `ActaFiscalizacionResource` publica `areaHallada` y
 * `usoHallado` y **no publica `areaDeclarada` ni `usoDeclarado`**. O sea que de las dos mitades que
 * el titulo de la tabla contrasta —«Lo que el verificador midio frente a lo que el titular
 * declaro»—, esta operacion sirve una.
 *
 * <ul>
 *   <li><b>Concepto</b> ← el nombre de la magnitud (ver `contrasteDelActa`).</li>
 *   <li><b>Declarado</b> → <b>la raya</b>, las dos filas. No esta en el acta.</li>
 *   <li><b>Verificado</b> ← `areaHallada` y `usoHallado`.</li>
 *   <li><b>Diferencia</b> → <b>la raya</b>. Es `hallada − declarada`, y **no se resta aqui**: no
 *       solo falta el minuendo, es que restar dos magnitudes servidas para llenar una celda es
 *       calcular lo que nadie publico. Quien la publica hecha es la liquidacion, con
 *       `diferenciaDeArea` —«nunca negativa, nula si falta un lado»—, y ademas el artboard dibuja
 *       esa misma celda con una raya en la fila del uso: la diferencia de un uso no es un
 *       numero.</li>
 *   <li><b>Situacion</b> ← `hallazgo`, lo que el fiscalizador anoto: `CONFORME`, `OMISO`,
 *       `SUBVALUADOR`, `USO_DISTINTO`, `NO_UBICADO`. <b>No es `estado`</b>, que es en que punto
 *       esta el papel —`ABIERTA`, `LIQUIDADA`, `ANULADA`— y no que se encontro en el predio.</li>
 * </ul>
 *
 * **Quien si publica las dos mitades es `GET /fiscalizacion/resultados`**, cuyas `lineas[]` traen
 * `areaDeclarada`, `areaHallada`, `diferenciaDeArea`, `usoDeclarado` y `usoHallado` — o sea la
 * tabla entera. Y aun asi **no se pide desde aqui**, por dos motivos: es la LIQUIDACION y esta
 * pantalla es la etapa anterior —su propia nota lo dice, «sin acta cerrada no se puede
 * liquidar»—, y sus lineas son por ejercicio y por unidad, no por concepto. Pintar aqui la
 * liquidacion de otra acta seria ensenar el resultado de un paso que esta pantalla todavia no ha
 * dado. Lo que cierra el hueco es que el acta publique lo declarado, y eso tiene su issue.
 *
 * <h2>Sin filtrar</h2>
 *
 * `GET /fiscalizacion/actas` admite **un** filtro, `?programa=`, y no se manda: acotarla exige
 * haber elegido programa, y elegirlo aqui seria decidir por quien atiende cual inspeccion se mira.
 * Los nueve mandos de la pantalla son los de registrar un acta, no los de buscarla.
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
    noPublicados: new Map(),
  }),
};

/** Una linea de la resolucion, en las cinco columnas de «Detalle por ejercicio». */
function filaDelEjercicio(linea: LineaDeterminada): readonly CeldaDeLaTabla[] {
  return [
    String(linea.ejercicio),
    sinDato(SIN_BASE_OMITIDA),
    // «Insoluto S/» ← `diferencia`, que pese al nombre es «el tributo que se dejo de pagar».
    enColumnaDeSoles(linea.diferencia),
    sinDato(SIN_INTERES),
    enColumnaDeSoles(linea.total),
  ];
}

/**
 * `fis-res` — la resolucion de determinacion, por su numero.
 *
 * <h2>Es la segunda hoja que exige sujeto, y aqui no habia alternativa ninguna</h2>
 *
 * `GET /fiscalizacion/resoluciones/{numero}` es de UNA resolucion, y **no existe ninguna operacion
 * que publique la relacion**: `ResolucionController` publica esta y `POST
 * /fiscalizacion/transferencias`, y nada mas. O sea que no se puede «tomar la primera» como hacen
 * `coa-exp` y las otras dos de este archivo — no hay primera. El numero viaja en la direccion,
 * `#/fis-res/RDF-2026-000001`, que es el mecanismo que #169 dejo instalado: `exigeSujeto` aqui y
 * `enLaRuta` derivado de aqui en `catalogo.ts`.
 *
 * Sin numero la pantalla **no pide nada y lo dice**. La alternativa era inventarse uno, y con uno
 * que no exista el backend contesta 404 «No hay ninguna resolucion de determinacion con el numero
 * "…"», que se leeria como una averia de la pantalla.
 *
 * <h2>Campo a campo: UNO de seis, y los otros cinco son sumas o no existen</h2>
 *
 * <ul>
 *   <li><b>`0|1` Contribuyente</b> ← `contribuyente`, el nombre del obligado. Es el unico de los
 *       seis que la operacion publica como campo.</li>
 *   <li><b>`0|0` Nº de acta</b> → <b>«no publicado»</b>. La resolucion enlaza hacia atras con
 *       `nLiquidacion`, que es el numero de la LIQUIDACION; el del acta no aparece —lo que la
 *       liquidacion publica es `actaId`, un identificador interno, y esta operacion ni eso—.
 *       Escribir ahi el numero de liquidacion pondria un documento donde va otro.</li>
 *   <li><b>`0|3` Insoluto omitido</b>, <b>`0|5` Multa tributaria</b> y <b>`0|7` Total
 *       liquidado</b> → <b>«no publicado»</b>, los tres. Son las sumas de las columnas
 *       `diferencia`, `multa` y `total` de la tabla de abajo, y la operacion **no publica ningun
 *       total**: ni por linea agregada ni de la resolucion entera. Sumarlos daria tres cifras
 *       exactas al centimo sobre un papel que vuelve una diferencia deuda exigible.</li>
 *   <li><b>`0|4` Interes</b> → <b>«no publicado»</b>, y este no es una suma: **no existe**. El
 *       propio backend lo deja escrito —la pantalla del prototipo declara «Ejercicio, Determinado,
 *       Declarado, Diferencia, Interes, Total» y el cuadro que se imprime lleva <b>Multa</b> donde
 *       decia Interes—, y ninguna de las dieciseis operaciones de fiscalizacion publica un
 *       interes. Cerrarlo es del backend, y hasta entonces el hueco es lo que lo pide.</li>
 * </ul>
 *
 * <h2>La tabla: tres columnas de cinco, y dos de las tres esperan a D-02a</h2>
 *
 * Ver `filaDelEjercicio`. «Base omitida S/» y «Interes S/» dicen la raya —la primera porque es una
 * resta de dos campos publicados y la segunda porque no hay campo—, y «Insoluto S/» y «Total S/»
 * dicen <b>«sin cifrar»</b> mientras D-02a no este cerrada, que es distinto: el campo existe y
 * llega vacio. Las dos ramas estan probadas.
 *
 * <h2>Sin filtrar</h2>
 *
 * No hay nada que filtrar: la operacion no admite ni un parametro. Lo que la pantalla dibuja y no
 * entra en la peticion son sus dos desplegables —«Ejercicios alcanzados» y «Articulo del Codigo
 * Tributario»—, que ademas no son filtros sino decisiones de la emision, o sea de
 * `POST /fiscalizacion/liquidaciones`. Y la resolucion **ya trae su periodo** hecho, en
 * `periodoDesde` y `periodoHasta`.
 */
const FIS_RES: Conector = {
  clave: ['fis-res', 'resolucion-de-determinacion'],
  exigeSujeto: true,
  pedir: ({ senal, sujeto }) =>
    pedirUno<ResolucionDeDeterminacion>(RUTAS.resolucionDeDeterminacion(sujeto ?? ''), senal),
  repartir: (resolucion: ResolucionDeDeterminacion): Reparto => ({
    valores: new Map([[coordenada(0, 1), resolucion.contribuyente]]),
    filas: new Map(),
    tablas: new Map([
      [
        'detalle-por-ejercicio',
        // Sin total: `lineas[]` son los ejercicios alcanzados y vienen todas, no paginadas.
        { filas: resolucion.lineas.map((linea) => ({ celdas: filaDelEjercicio(linea) })) },
      ],
    ]),
    noPublicados: new Map([
      [coordenada(0, 0), NO_PUBLICADO],
      [coordenada(0, 3), NO_PUBLICADO],
      [coordenada(0, 4), NO_PUBLICADO],
      [coordenada(0, 5), NO_PUBLICADO],
      [coordenada(0, 7), NO_PUBLICADO],
    ]),
  }),
};

/**
 * Las hojas de Fiscalizacion que piden de verdad. **Son tres de cuatro.**
 *
 * <h2>Y `fis-panel` no entra, con su motivo medido</h2>
 *
 * Declara `GET /fiscalizacion/estado-cuenta`, que **no esta servida** y ademas no publica nada de
 * lo que esa pantalla ensena: sus cuatro cifras son el embudo del programa —detectados por cruce,
 * programados, con acta cerrada, con diferencia— y lo que `estado-cuenta` publica es la deuda de
 * fiscalizacion **de un contribuyente**, que ademas exige `?contribuyente=`.
 *
 * Las cuatro cifras se podrian componer con `totalElementos` de cuatro operaciones distintas
 * —omisos, muestra, actas y resultados—, y eso es exactamente lo que no se hace: serian cuatro
 * peticiones para cuatro numeros que ninguna operacion afirma que signifiquen eso, y tres de las
 * cuatro habria que acotarlas a un programa que la pantalla no elige. Un embudo compuesto en el
 * navegador se lee igual que uno publicado por el backend, y solo uno de los dos se puede cuadrar.
 */
export const CONECTORES_DE_FISCALIZACION = {
  'fis-prog': FIS_PROG,
  'fis-actas': FIS_ACTAS,
  'fis-res': FIS_RES,
} as const;

export {
  FIS_ACTAS,
  FIS_PROG,
  FIS_RES,
  SIN_AREA_HALLADA,
  SIN_BASE_OMITIDA,
  SIN_CIFRAR,
  SIN_DECLARADO,
  SIN_DIFERENCIA_DEL_ACTA,
  SIN_DIFERENCIA_ESTIMADA,
  SIN_HALLAZGO,
  SIN_INTERES,
  SIN_TITULAR,
  contrasteDelActa,
  enColumnaDeSoles,
  filaDelEjercicio,
  sinDato,
};

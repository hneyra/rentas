import { coordenada, type Ausencia, type CeldaDeLaTabla, type Coordenada } from '@kamayuk/ui';

import { formatearFecha } from '../../dominio/formato.ts';
import type {
  ExpedienteDeLaPapeleta,
  InternamientoEnDeposito,
  Paginado,
  PapeletaDeTransito,
  ResumenDePapeletas,
  VehiculoServido,
} from '../lecturas.ts';
import { RUTAS, pedirPagina, pedirUno } from '../lecturas.ts';
import type { Conector, Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import { laVentanaDe, laVentanaQueSePide, loQueDijoElServidor } from '../laVentana.ts';

/**
 * **Las hojas de Transito que piden de verdad** (#180, #184).
 *
 * Un archivo por modulo, como Licencias (#168), Coactiva (#170), Inicio (#167) y Consultas
 * (#169): el registro de `conectores.ts` es de todos los modulos y un conector es de uno, asi que
 * en `CONECTORES` hay **una linea** por modulo y conectar una hoja no toca el unico archivo que
 * todas las ramas comparten.
 *
 * **Y con #184 son TRES**: `tra-panel` entra sobre `GET /transito/reportes/resumen-papeletas`,
 * que estaba publicada desde siempre y no la consumia nadie.
 *
 * <table>
 *   <tr><th>Hoja</th><th>Operaciones</th><th>Que llena</th></tr>
 *   <tr><td>`tra-panel`</td><td>`GET /transito/reportes/resumen-papeletas`</td><td>el
 *     desplegable de ejercicio y **tres de sus cinco recuentos**; los otros dos dicen «no
 *     publicado» y nombran lo que falta</td></tr>
 *   <tr><td>`tra-pap`</td><td>`GET /transito/papeletas` -> `GET
 *     /transito/papeletas/{numero}/actos`</td><td>**solo su tabla**: cero campos de solo
 *     lectura</td></tr>
 *   <tr><td>`tra-veh`</td><td>`GET /rentas/vehiculos/{placa}` + `GET /transito/internamientos`
 *     (x2)</td><td>cuatro campos, la tabla, y **dos huecos de una decision abierta**</td></tr>
 * </table>
 *
 * <h2>La regla de `conectores.ts` manda aqui, y en el deposito la respalda el propio backend</h2>
 *
 * **No se calcula un agregado que la operacion no publica.** En `tra-veh` la tentacion es una
 * multiplicacion de una linea: «Total de custodia» = dias x tasa diaria, con los dias servidos y
 * `tasaDeCustodia` ahi mismo. **No se hace, y por dos motivos que no son de estilo**:
 *
 * <ul>
 *   <li>`tasaDeCustodia` **no es una tarifa**: `InternamientoEnConsulta` la documenta como «el
 *       concepto del TUPA con que se cobra la custodia». Multiplicar dias por un codigo de TUPA no
 *       da soles: da una cifra sin unidad puesta donde va dinero.</li>
 *   <li>Y la tarifa de verdad **no existe todavia**: el backend se niega expresamente a publicar
 *       las dos columnas —«el prototipo dibuja "Tasa diaria S/" y "Custodia S/" en la grilla. Aqui
 *       no estan, y no es un olvido: la tarifa de la custodia vive en `tasa` y su ordenanza es
 *       **D-02b, que sigue abierta**. Publicar una cifra compuesta con una tarifa inventada seria
 *       peor que no publicarla —el administrado pagaria lo que la pantalla diga—»—. Un hueco que
 *       dice «no publicado» nombra la decision que falta; una cifra calculada aqui la esconde, y
 *       la paga alguien.</li>
 * </ul>
 *
 * <h2>Las dos tablas salen SIN FILTRAR, y eso se dice en vez de taparse</h2>
 *
 * Ninguna de las dos hojas puede pasarle todavia a su conector lo que se teclea en su formulario:
 * `Conector.pedir` recibe una senal de aborto y el sujeto de la ruta, y nada mas. Es el mismo
 * hueco que `conectores/licencias.ts` dejo declarado, y lo cierra **#172**.
 *
 * Mientras tanto, lo que se pide esta escrito donde se ve —`RUTAS.papeletas` lleva `?tamano=1`, y
 * el de la tabla del deposito lo declara su `paginacion.tamano`—. **Y desde #172 «3 de 188» si se
 * escribe**, porque ya no es una cuenta de aqui: es `totalElementos`, que la operacion publica
 * sobre el deposito entero y que hasta este issue llegaba y se tiraba.
 *
 * <h2>Las tres celdas que nadie publica lo DICEN, y ese camino es nuevo</h2>
 *
 * De las once columnas de las dos tablas hay tres que ninguna operacion llena. Hasta hoy la unica
 * forma de dibujarlas era **una raya muda** —`'—'` como cualquier otra cadena—, porque
 * `Reparto.filas` lleva `readonly string[]` por fila y una cadena no puede decir «aqui no hay
 * dato»: `''` se lee como un blanco y una raya no distingue «nadie lo publica» de «esto esta roto».
 * Es lo que hoy tienen «Costa S/» de `coa-exp` y «Cantidad» de `coa-cost`, cuyos motivos —que
 * existen y estan escritos— viven solo en el javadoc de su conector, donde no los lee quien mira la
 * pantalla.
 *
 * Desde `kamayuk-lib`#87 el interprete tiene el camino bueno, y estas dos tablas lo estrenan: la
 * tabla lleva `clave`, sus filas van por `Reparto.tablas` y una celda puede llegar como
 * `{ texto: null, nota }`. Entonces el interprete escribe la palabra que la tabla declara en su
 * `sinDato` —traducida, nunca un `''` ni un `0`— y **anuncia el motivo en la propia celda**. Las
 * tres notas de aqui abajo son ese motivo.
 *
 * **Y desde #186 la tabla del deposito pagina y ordena de verdad.** Lo que faltaba era de este
 * lado —`Conector.pedir` no recibia la ruta de la hoja, asi que nadie podia PEDIR la pagina que el
 * mando escribiese— y lo abrio #172: hoy `tra-veh` declara sus cuatro parametros, lee la ventana
 * de la ruta y entrega el `hayMas` y el `totalPaginas` que el backend publica. La tabla de
 * `tra-pap` **no** pagina, y no es un olvido: se pide con `?tamano=1` porque esta pantalla dibuja
 * los actos de UNA papeleta, no una relacion de papeletas.
 *
 * <h2>Las dos rutas `BASE` que el arbol declara y que NO se encienden</h2>
 *
 * El arbol le atribuye a estas hojas dos rutas mas, las dos con verbo `BASE` —«solo se leyo el
 * `@RequestMapping` de la clase: sus metodos no se han verificado»—. **Se verificaron, y las dos
 * existen en el contrato con un solo verbo, y es `POST`**:
 *
 * <ul>
 *   <li><b>`/transito/descargos`</b> — el contrato publica `POST /transito/descargos` y ningun
 *       `GET`. **Registra un recurso contra una papeleta**, o sea que pedirle datos seria presentar
 *       un descargo en nombre de alguien para pintar una pantalla. Y no hace falta: lo que esa
 *       ruta guarda sale ya por la que si se enciende —`GET /transito/papeletas/{numero}/actos`
 *       publica `descargos[]` con su expediente, su fecha, su tipo de recurso y si esta en
 *       plazo—.</li>
 *   <li><b>`/transito/constancias-libres`</b> — el contrato publica `POST
 *       /transito/constancias-libres` y ningun `GET`, y lo que contesta no es JSON: es
 *       **`"archivo"`**, un documento descargable. No hay forma que repartir en campos.</li>
 * </ul>
 *
 * **El arbol no se toca**: es la transcripcion del artboard y
 * `verificaciones/pantallas-del-artboard.test.ts` lo compara contra el, verbo a verbo. Lo que
 * cambia es lo que se sabe de esas dos rutas, y se sabe aqui.
 *
 * <h2>Y `tra-panel` y `tra-cua` NO entran, con su medida</h2>
 *
 * · **`tra-panel`** entro con #184, y su reparto esta abajo. Lo que decia aqui —«la unica
 *   operacion que el arbol le atribuye es `BASE /transito/estado-cuenta`, que publica el estado de
 *   cuenta de una placa y no un resumen del ejercicio»— seguia siendo cierto, y lo que faltaba era
 *   declarar la que si la sirve: se corrigio en el ARTBOARD y en `arbol.ts` a la vez, como #169,
 *   #173 y #179.
 *
 * · **`tra-cua`** ensena el cuadro de infracciones con su escala en % de UIT. `GET
 *   /transito/codigos` esta en el contrato y **no esta en `YA_SERVIDAS`**: encenderla es conectar
 *   una tercera hoja, y este issue conecta dos. Su campo «UIT vigente» ademas no es de transito
 *   sino un valor normativo, que desde P5B es de otro sistema.
 */

/**
 * **Una celda que llego sin dato, con el motivo dentro** (`kamayuk-lib`#87).
 *
 * `texto: null` es «aqui no hay dato», y el interprete lo pinta con la palabra que la tabla declara
 * en su `sinDato` —nunca con `''` ni con un `0`, que se leerian como una afirmacion—. La `nota` va
 * en la celda y dice **por que**; es dato y no se traduce, igual que la celda.
 *
 * Es lo que separa estas dos tablas de las de Coactiva, donde la misma ausencia se dibuja con una
 * raya muda y su motivo vive solo en el javadoc del conector: ahi no lo lee quien mira la pantalla.
 */
const sinDato = (porQue: string): CeldaDeLaTabla => ({ texto: null, nota: porQue });

/**
 * **Lo que `tra-veh` dice cuando la direccion no trae placa** (#180, AC2).
 *
 * No es la frase de Consultas: alli el sujeto es el **codigo de un contribuyente** y aqui es una
 * **placa**. Pedir «el codigo del contribuyente» para abrir la ficha de un vehiculo manda a quien
 * atiende a buscar el dato equivocado, que es peor que no decir nada.
 *
 * Y es la alternativa a lo unico que se podria haber hecho en su lugar, que era pedir el padron
 * vehicular entero y dibujar el primero: la ficha de un vehiculo de verdad, con su titular detras,
 * en una pantalla que nadie pidio.
 */
const SIN_PLACA: Ausencia = {
  enElCampo: 'falta la placa',
  explicacion:
    'Esta pantalla es de un vehiculo concreto, y la direccion no nombra a ninguno: la placa va ' +
    'detras del nombre de la hoja. Hasta que la lleve no se pide su ficha, porque lo unico que se ' +
    'podria pedir en su lugar es el padron vehicular entero.',
  tono: 'info',
};

/**
 * `tra-panel` — los cinco recuentos de papeletas del ejercicio (#184).
 *
 * <h2>De DIEZ rutas publicadas, una; y de cuatro `resumen-*`, tambien una</h2>
 *
 * El contrato publica diez operaciones bajo `/transito/reportes/` y hasta #184 ninguna hoja
 * nombraba una sola: el backend iba por delante de la interfaz. La medida —cual de las cuatro
 * `resumen-*` dibuja este panel, y por que las otras tres no— esta en `datos/servidas.ts`, y en
 * corto: las dos de codigo y placa publican **la misma forma** agrupada por otra dimension, y la de
 * recaudacion publica **importes del libro** cuando aqui no se dibuja ni uno.
 *
 * <h2>Se pide UNA vez, sin filtrar, y la respuesta dice de que ejercicio es</h2>
 *
 * `RUTAS.resumenDePapeletas` lleva `?agrupadoPor=ANO` escrito y **no lleva `desde` ni `hasta`**, asi
 * que el backend acota al ejercicio en curso de su reloj y lo publica dentro (regla 9, RNF-075). Un
 * rango de un ano natural agrupado por ano da **exactamente un grupo**, y esa es la unica linea que
 * este conector lee.
 *
 * **Y esa invariante se comprueba en vez de suponerse.** Si algun dia llegara mas de una linea
 * —porque el rango dejara de ser de un ano—, leer la primera pondria en «Canceladas» y «En
 * coactiva» las cuentas de **un trozo** del periodo bajo unos rotulos que hablan del ejercicio
 * entero: una cifra exacta y equivocada, que es la peor clase. Con cero lineas pasa lo mismo al
 * reves: no hay de donde sacarlas. En los dos casos los dos campos dicen «no publicado».
 *
 * <h2>Campo a campo: uno de contexto, tres con dato y DOS huecos que nombran al backend</h2>
 *
 * <ul>
 *   <li><b>`0|0` Ejercicio</b> ← el <b>ano de `desde`</b>, que es el rango que la respuesta dice
 *       haber contado. Es un desplegable, y se rellena por lo mismo que `ini-panel` rellena el
 *       suyo: es el ejercicio del que son las cifras de debajo, y dejarlo en la primera opcion
 *       —«2026», la que el artboard escribio primero— seria afirmarlo sin saberlo. Lo tecleado gana
 *       sobre esto, como en cualquier campo.
 *       <p><b>Y hay una limitacion medida que se deja escrita en vez de descubrirse</b>: el
 *       desplegable lleva las dos opciones del artboard —«2026» y «2025»—, asi que un ejercicio
 *       que no sea ninguna de las dos deja el control <b>en blanco</b>. Es lo mismo que `coa-exp`
 *       midio con «Ejecutor coactivo» y lo que hace que `tra-veh` no escriba «Deposito» ni «Clase
 *       de vehiculo». <b>Aqui si se escribe igual</b>, y es una eleccion: en blanco no se afirma
 *       nada y las cinco cifras de debajo siguen siendo las que llegaron, mientras que no
 *       escribirlo dejaria el desplegable en «2026» —en 2027, sobre cifras de 2027—, que es
 *       afirmar un ano que nadie dijo. Su prueba esta escrita.</li>
 *   <li><b>`0|1` Levantadas</b> ← `papeletas`, el total del resumen. Es el unico de los cinco
 *       rotulos que empareja <b>exacto</b> con lo que la operacion publica: «levantar una papeleta»
 *       es emitirla, y toda fila del padron se levanto —tambien las anuladas y las prescritas—. El
 *       total va <b>calculado en el servidor</b> y no sumando las lineas aqui.</li>
 *   <li><b>`0|3` Canceladas</b> ← `pagadas` de la linea. «Cancelar» es <b>pagar</b>, y no se
 *       decidio por el sonido: el artboard usa la misma palabra en la instruccion de `tra-veh`
 *       —«sin la papeleta cancelada y la custodia pagada no se emite la orden de retiro»—, y el
 *       prototipo del monolito dibuja la casilla «Multa cancelada» con el marcador «Recibo de la
 *       papeleta». El SQL que la cuenta es `count(*) FILTER (WHERE p.estado = 'PAGADA')`.</li>
 *   <li><b>`0|5` En coactiva</b> ← `enCoactiva` de la linea, que es
 *       `count(*) FILTER (WHERE p.estado = 'COACTIVA')`. El rotulo y el estado dicen lo mismo.</li>
 * </ul>
 *
 * <h2>Y los dos que dicen «no publicado», con lo que le falta al backend en cada uno</h2>
 *
 * Son los dos casos que #184 pedia nombrar, y no son el mismo hueco:
 *
 * <ul>
 *   <li><b>`0|2` Notificadas</b> — <b>falta el acto que registra la notificacion de la papeleta en
 *       si</b>. `EstadoDePapeleta` declara `NOTIFICADA` y la secuencia `IMPUESTA → NOTIFICADA → …`,
 *       pero <b>ningun codigo de produccion escribe ese estado</b>: lo midio el backend al escribir
 *       `PapeletasSinNotificar`, cuyo javadoc censa los usos del enumerado en `src/main` y encuentra
 *       `IMPUESTA`, `PAGADA`, `ANULADA` y `PRESCRITA`, y ninguna otra. O sea que pedir esta misma
 *       operacion con `?agrupadoPor=ESTADO` y leer la linea `NOTIFICADA` daria <b>cero, siempre</b>,
 *       bajo un rotulo que dice cuantas se notificaron — la cifra plausible y equivocada. Lo que el
 *       sistema si sabe de la notificacion es indirecto y de <b>otro</b> documento: la resolucion de
 *       multa, cuyos acuses publica `GET /transito/papeletas/{numero}/actos` una papeleta a una
 *       papeleta y no como agregado.</li>
 *   <li><b>`0|4` Caducadas sin notificar</b> — <b>no es un estado</b>, y le faltan <b>las dos
 *       cosas</b>: el acto de arriba, y ademas el <b>plazo</b> para notificar, contra el que se
 *       decide si vencio. Ese plazo es un valor normativo y la regla 5 prohibe compilarlo; el
 *       conjunto sellado publica dos plazos de sanciones —`DESCARGO_PAPELETA` y el de cumplimiento
 *       de la resolucion ordinaria— y <b>ninguno de los dos es este</b>. Es exactamente el mismo
 *       hueco por el que `tra-pap` escribe `NOTIFICADO` tal cual en vez de traducirlo a «Conforme»
 *       (#185): «Conforme» y «Por vencer» son estados <b>del plazo</b>, y el plazo no lo publica
 *       nadie.</li>
 * </ul>
 *
 * <p>Los dos huecos son informacion: dicen a quien mantiene el backend exactamente que le falta
 * para cerrar este panel —es [#222](https://github.com/hneyra/rentas/issues/222)—. Un cero
 * calculado aqui no lo seria.
 *
 * <h2>Lo que NO se hace, y podria parecer que si</h2>
 *
 * <ul>
 *   <li><b>«Caducadas sin notificar» ← `pendientes`</b>. La linea publica `pendientes`
 *       —`estado NOT IN ('PAGADA','ANULADA','PRESCRITA')`, o sea «las que siguen debiendose»— y es
 *       un numero que cabe en ese hueco sin que nada chirrie. <b>No es lo mismo</b>: una papeleta
 *       pendiente se puede cobrar, y una caducada es justamente la que <b>ya no</b>. Ponerla ahi
 *       diria que hay 1 842 papeletas incobrables donde las hay cobrables, debajo de una
 *       instruccion que manda atenderlas primero.</li>
 *   <li><b>«Notificadas» ← `papeletas` − `pendientes`</b>, o cualquier otra resta. Aritmetica en el
 *       navegador sobre cifras que nadie publico junta, y ademas falsa: las pagadas, las anuladas y
 *       las prescritas no son las notificadas.</li>
 *   <li><b>La fecha al lado de cada recuento</b>. `actualizadoA` llega y no se escribe: el bloque no
 *       tiene ningun campo donde ponerla, y pegarla a tres cifras de un formulario de seis campos
 *       la repetiria tres veces. Lo que si se escribe es el <b>ejercicio</b>, que es el rango que se
 *       conto — la parte de la regla 9 que esta pantalla tiene sitio para decir. Es la misma
 *       decision que `ini-parado` y `panel` toman con sus recuentos, y la contraria a la de «Dias de
 *       custodia» de `tra-veh`, donde la fecha cambia lo que la cifra significa y hay un campo
 *       suelto para ella.</li>
 * </ul>
 */
const TRA_PANEL: Conector = {
  clave: ['tra-panel', 'resumen-de-papeletas'],
  pedir: (senal) => pedirUno<ResumenDePapeletas>(RUTAS.resumenDePapeletas, senal),
  repartir: (resumen: ResumenDePapeletas): Reparto => {
    // Ver el javadoc: un rango de un ano natural agrupado por ANO da exactamente un grupo. Se
    // COMPRUEBA, porque leer la primera de varias pondria las cuentas de un trozo del periodo bajo
    // rotulos que hablan del ejercicio entero.
    const delEjercicio = resumen.lineas.length === 1 ? resumen.lineas[0] : undefined;
    const valores = new Map<Coordenada, string>([
      // El ejercicio que la respuesta dice haber contado, y no el que toco por omision.
      [coordenada(0, 0), resumen.desde.slice(0, 4)],
      // El total, calculado en el servidor. No se suman las lineas aqui.
      [coordenada(0, 1), String(resumen.papeletas)],
    ]);
    const noPublicados = new Map<Coordenada, string>([
      // «Notificadas»: ningun codigo de produccion escribe `NOTIFICADA`. Ver el javadoc.
      [coordenada(0, 2), NO_PUBLICADO],
      // «Caducadas sin notificar»: no es un estado, y el plazo no lo publica nadie.
      [coordenada(0, 4), NO_PUBLICADO],
    ]);
    if (delEjercicio === undefined) {
      noPublicados.set(coordenada(0, 3), NO_PUBLICADO);
      noPublicados.set(coordenada(0, 5), NO_PUBLICADO);
    } else {
      valores.set(coordenada(0, 3), String(delEjercicio.pagadas));
      valores.set(coordenada(0, 5), String(delEjercicio.enCoactiva));
    }
    // Esta hoja no tiene tabla: su bloque son seis campos.
    return { valores, filas: new Map(), noPublicados };
  },
};

/**
 * `tra-pap` — los actos de una papeleta, de DOS operaciones.
 *
 * <h2>La segunda no se puede pedir sin la primera</h2>
 *
 * `GET /transito/papeletas` dice **cual** papeleta se dibuja —la primera de la relacion, porque
 * esta pantalla todavia no tiene con que elegirla— y `GET /transito/papeletas/{numero}/actos` trae
 * su expediente: sus recursos y todos sus documentos, en orden de fecha. Es la misma composicion
 * que `coa-exp` ya hace con la cartera coactiva y que `panel` hace con la ultima corrida.
 *
 * Con la relacion vacia no hay papeleta que pedir y el conector devuelve `null`, que la pantalla
 * dice como «sin datos» — un hecho del negocio, no una averia.
 *
 * <h2>Ningun campo que decidir, y no es un olvido</h2>
 *
 * **Los doce campos del bloque se escriben: ni uno es de solo lectura.** Son el formulario con que
 * se registra una papeleta y su notificacion —numero, placa, fecha, hora, codigo de infraccion,
 * lugar, inspector, infractor, sus dos documentos, la casilla de vehiculo internado y las
 * observaciones—, y esta interfaz hace **una** escritura, que es el ejercicio de la sesion. Asi que
 * `valores` y `noPublicados` salen vacios los dos: lo que esta pantalla ensena es su tabla, y la
 * tabla sale entera del expediente. Es la misma forma que `aut-cat` y `aut-tram`.
 *
 * <h2>La tabla «Actos de la papeleta»: cuatro columnas de cinco</h2>
 *
 * <ul>
 *   <li><b>Nº</b> ← `numero`, el numero impreso del documento. **No es el ordinal de la fila** que
 *       el artboard dibuja («1», «2», «3», «4»): el ordinal no es un dato de la respuesta, y
 *       contarlo aqui seria escribir en una celda algo que nadie publico — ademas de mentir en
 *       cuanto la tabla se pagine.</li>
 *   <li><b>Acto</b> ← `tipo`, que documento es dentro de su clase.</li>
 *   <li><b>Fecha</b> ← `fecha`, el dia del acto.</li>
 *   <li><b>Documento</b> ← `clase`, de que registro sale el papel: `RESOLUCION_GERENCIA` o
 *       `ACTA_INTERNAMIENTO`. El artboard escribe esta celda como una sola frase —«Papeleta
 *       0041182»—, que es la clase y el numero juntos; la operacion los publica como **dos**
 *       campos y cada uno va a la columna que lo pregunta. Lo que NO se pone aqui es
 *       `documentoId`: es la fila de `documento_emitido` con que se reimprime (RF-132), un
 *       identificador interno, y un numero de base de datos en una columna que dice «Documento» se
 *       lee como el numero del papel.</li>
 *   <li><b>Estado</b> ← `estado`, <b>desde #185</b>. Hasta ese issue esta celda decia que no habia
 *       dato, y era lo correcto: lo unico que `ActoResource` publicaba de como quedo un acto eran
 *       sus `acuses`, una fila por intento, y resumirlos en el ultimo es justo lo que el backend
 *       prohibe en su propio javadoc —«quedarse con la ultima escondería que las dos anteriores no
 *       encontraron a nadie, que es justamente lo que hay que poder mostrar cuando el administrado
 *       discute la notificación»—. Ahora el estado lo <b>deriva el dominio</b> de todos los acuses
 *       y llega ya hecho: `SIN_NOTIFICACION`, `SIN_DILIGENCIAR`, `NO_NOTIFICADO`, `NOTIFICADO`.
 *       <p><b>Y se escribe tal cual, sin traducirlo a las palabras del artboard.</b> El artboard
 *       dibuja «Conforme», «Por vencer» y «Pendiente», que son estados <b>del plazo</b>, y el plazo
 *       no lo publica nadie —vive en el conjunto sellado, y el backend explica por que no lo pide
 *       desde esta operacion—. Traducir `NOTIFICADO` a «Conforme» aqui seria afirmar que la
 *       papeleta todavia se puede cobrar, que es exactamente lo que una notificada fuera de plazo
 *       NO permite: caduca. El vocabulario que llega es el unico que se puede escribir sin
 *       inventar.
 *       <p>Es columna de insignia, y su tono sale de `tonoDe` como el de las demas: ninguna de las
 *       cuatro palabras la reconoce ninguna de las tres listas, asi que todas caen en el tono de
 *       «no se» (#175). Es lo correcto —`NOTIFICADO` no es un juicio favorable sobre la papeleta—
 *       y no hay que tocar `tono.ts` para conseguirlo.</li>
 * </ul>
 */
const TRA_PAP: Conector = {
  clave: ['tra-pap', 'actos-de-la-papeleta'],
  pedir: async ({ senal }) => {
    const relacion = await pedirPagina<PapeletaDeTransito>(RUTAS.papeletas, senal);
    const primera = relacion.contenido[0];
    // Sin papeleta no hay expediente que pedir. `null` es «se pregunto y no hay», que la pantalla
    // dice distinto de un fallo.
    if (primera === undefined) return null;
    return pedirUno<ExpedienteDeLaPapeleta>(RUTAS.actosDeLaPapeleta(primera.numero), senal);
  },
  repartir: (expediente: ExpedienteDeLaPapeleta): Reparto => ({
    // Los doce campos del bloque se escriben. Ver el javadoc: no hay ninguno que decidir.
    valores: new Map(),
    // Vacio: esta tabla lleva `clave`, asi que sus filas van por `tablas` — es el camino cuyas
    // celdas pueden decir que no hay dato, que es lo que «Estado» necesita.
    filas: new Map(),
    tablas: new Map([
      [
        'actos-de-la-papeleta',
        {
          filas: expediente.actos.map((acto) => ({
            // La clave de React: dos actos del mismo dia y tipo no comparten el numero del
            // documento, que es lo unico que los distingue de verdad.
            clave: `${acto.clase}|${acto.numero}`,
            celdas: [
              acto.numero,
              acto.tipo,
              formatearFecha(acto.fecha),
              acto.clase,
              // El estado que el backend DERIVA de todos los acuses (#185), escrito tal cual. Ver
              // el javadoc: traducirlo a «Conforme» seria afirmar que la papeleta se puede cobrar.
              acto.estado,
            ],
          })),
        },
      ],
    ]),
    noPublicados: new Map(),
  }),
};

/** Lo que `tra-veh` pide: la ficha del vehiculo, el deposito entero y los internamientos suyos. */
type LoDeTraVeh = readonly [
  VehiculoServido,
  Paginado<InternamientoEnDeposito>,
  Paginado<InternamientoEnDeposito>,
];

/**
 * `tra-veh` — el deposito municipal, y el vehiculo que nombra la direccion.
 *
 * <h2>La placa va en la RUTA, y sin ella no se pide nada</h2>
 *
 * `GET /rentas/vehiculos/{placa}` es la primera operacion encendida que lleva su sujeto **en la
 * ruta** y no en la cadena de consulta. El mecanismo es el que #169 dejo instalado: `exigeSujeto`
 * aqui, `enLaRuta` derivado de aqui en `catalogo.ts` —derivado, y no una lista paralela que se
 * desincroniza en silencio— y la placa viajando en la direccion, `#/tra-veh/T2G-418`. Sin ella la
 * hoja lo dice con `SIN_PLACA` en vez de pedir el padron vehicular entero.
 *
 * La placa se compara **sin el guion** en el backend, asi que `T2G-418` y `T2G418` llevan a la
 * misma ficha; una que no esta en el padron de esta municipalidad contesta **404** y una mal
 * formada **422**, y las dos se dicen como averia y no como dato.
 *
 * <h2>TRES lecturas, y la tercera no es un capricho</h2>
 *
 * <ol>
 *   <li>`GET /rentas/vehiculos/{placa}` — **quien** es el vehiculo.</li>
 *   <li>`GET /transito/internamientos` — la tabla «Vehiculos internados», que es **el deposito** y
 *       no el historial de esta placa. Sale sin filtrar (ver el javadoc de este archivo).</li>
 *   <li>`GET /transito/internamientos?placa=` — el internamiento de **este** vehiculo, que es de
 *       donde sale «Dias de custodia». Buscar esa fila dentro de la pagina de arriba diria «no
 *       publicado» cada vez que el vehiculo no cayera entre las veinte primeras: un hueco que
 *       depende de la suerte es peor que un hueco.</li>
 * </ol>
 *
 * <h2>Campo a campo: cuatro con dato, dos huecos y tres que no se rellenan</h2>
 *
 * <ul>
 *   <li><b>`0|0` Placa</b> ← `vehiculo.placa`, ya normalizada por el backend. Es lo primero que
 *       hay que poder leer: de quien son las cifras de debajo.</li>
 *   <li><b>`0|1` Nº de papeleta</b> ← `papeleta` del internamiento de esta placa, la que dispuso la
 *       medida preventiva. Llega nulo cuando no hubo ninguna —el internamiento pudo disponerlo otra
 *       cosa—, y entonces el campo se queda sin escribir.</li>
 *   <li><b>`0|2` Fecha de internamiento</b> ← `fechaDeIngreso` del mismo.</li>
 *   <li><b>`0|5` Marca y modelo</b> ← `marca` y `modelo`, que la ficha publica por separado y el
 *       campo pregunta juntos.</li>
 *   <li><b>`0|6` Dias de custodia</b> ← `dias` **con su fecha**, `calculadoA`. Regla 9 (RNF-075):
 *       los dias en deposito de hoy no son los de manana, y el backend cuenta a una fecha y la
 *       publica al lado justamente para que no se lea como un numero fijo. Sin ningun internamiento
 *       de esta placa dice «no publicado» — que es lo que la operacion contesto, no una decision
 *       de aqui.</li>
 *   <li><b>`0|7` Tasa diaria</b> y <b>`0|8` Total de custodia</b> → <b>«no publicado»</b>, los dos.
 *       Ver el javadoc de este archivo: `tasaDeCustodia` es el concepto del TUPA y no una tarifa, y
 *       la tarifa de verdad espera a <b>D-02b</b>. Estos dos huecos son los que nombran esa
 *       decision abierta; una multiplicacion los taparia.</li>
 * </ul>
 *
 * Y tres que **no** se rellenan aunque algo parecido llegue, porque rellenarlos se veria peor:
 *
 * <ul>
 *   <li><b>`0|3` Deposito</b> y <b>`0|4` Clase de vehiculo</b> son desplegables de lista cerrada
 *       —«Deposito municipal 1/2», «Automovil … Trimovil»— y lo que llega es texto libre
 *       (`deposito`, `categoria`). Un valor servido que no sea una de las opciones deja el control
 *       <b>en blanco</b>, o sea que ponerlo perderia el dato en vez de ensenarlo. Es lo mismo que
 *       `coa-exp` midio con «Ejecutor coactivo».</li>
 *   <li><b>`0|9` Grua</b> es una casilla —«se uso grua para el traslado»— y <b>ninguna</b> de las
 *       operaciones de transito publica si se uso: no esta en `InternamientoResource` ni en el
 *       acta. Marcarla o desmarcarla seria afirmar algo del traslado de un vehiculo.</li>
 * </ul>
 *
 * <h2>La tabla «Vehiculos internados»: cuatro columnas de seis</h2>
 *
 * «Placa» ← `placa`, «Clase» ← `clase` **desde #185**, «Ingreso» ← `fechaDeIngreso`, «Dias» ←
 * `dias` y «Situacion» ← `estado`, que es la situacion derivada de los movimientos.
 *
 * «Clase» era el segundo hueco de esta tabla y ya no lo es: el backend la trae del padron por el
 * `vehiculo_id` que el propio ingreso guarda, que es lo que evitaba las dos salidas que esta
 * interfaz NO iba a tomar —pedir una ficha por fila, veinte lecturas para una columna; o escribir
 * la categoria del vehiculo de la direccion en las veinte filas, que diria que el deposito entero
 * es de esa clase—. Cuando el vehiculo no esta inscrito, o su ficha no declara categoria, la celda
 * sigue diciendo que no hay dato con su motivo dentro.
 *
 * La que queda **sin dato y con su motivo** es una:
 *
 * <ul>
 *   <li><b>«Custodia S/»</b> — el importe que el backend se niega a componer. El mismo D-02b de
 *       arriba, y en una celda de tabla ni siquiera cabe decirlo con palabras.</li>
 * </ul>
 *
 * Y la columna «Dias» va **sin fecha**, al contrario que el campo: `calculadoA` es la misma para
 * toda la pagina —es el corte con que se pidio— y repetirla en cada fila seria ruido. La fecha se
 * lee una vez, en «Dias de custodia».
 */
const TRA_VEH: Conector = {
  clave: ['tra-veh', 'deposito'],
  exigeSujeto: true,
  sinSujeto: SIN_PLACA,
  // La ventana de la tabla del deposito. La tercera lectura —la de ESTA placa— no pagina: es una
  // fila, y se pide con `?tamano=1`.
  parametros: laVentanaDe('GET /transito/internamientos'),
  pedir: ({ senal, sujeto, enLaRuta }) => {
    // `useDatosDeLaHoja` no llama a `pedir` sin sujeto cuando `exigeSujeto` esta puesto; el `??`
    // es para el compilador, no una rama que se ejecute.
    const placa = sujeto ?? '';
    return Promise.all([
      pedirUno<VehiculoServido>(RUTAS.vehiculoDe(placa), senal),
      pedirPagina<InternamientoEnDeposito>(
        RUTAS.internamientos(laVentanaQueSePide('tra-veh', 'vehiculos-internados', enLaRuta)),
        senal,
      ),
      pedirPagina<InternamientoEnDeposito>(RUTAS.internamientosDe(placa), senal),
    ]);
  },
  repartir: ([vehiculo, deposito, suyos]: LoDeTraVeh): Reparto => {
    const suyo = suyos.contenido[0];
    const papeleta = suyo?.papeleta;
    return {
      valores: new Map([
        [coordenada(0, 0), vehiculo.placa],
        ...(papeleta === undefined || papeleta === null
          ? []
          : ([[coordenada(0, 1), papeleta]] as const)),
        ...(suyo === undefined
          ? []
          : ([[coordenada(0, 2), formatearFecha(suyo.fechaDeIngreso)]] as const)),
        [coordenada(0, 5), `${vehiculo.marca} ${vehiculo.modelo}`],
        ...(suyo === undefined
          ? []
          : ([
              [coordenada(0, 6), `${String(suyo.dias)} · ${formatearFecha(suyo.calculadoA)}`],
            ] as const)),
      ]),
      filas: new Map(),
      tablas: new Map([
        [
          'vehiculos-internados',
          {
            filas: deposito.contenido.map((fila) => ({
              clave: String(fila.id),
              celdas: [
                fila.placa,
                // La categoria con que el vehiculo internado esta inscrito (#185). Llega NULA en
                // dos casos que no son lo mismo que un dato: el ingreso que no nombro ninguna
                // ficha —se interna lo que se interna— y la ficha sin categoria declarada. Los dos
                // se dicen igual, porque desde aqui no se distinguen y ninguno se puede rellenar.
                fila.clase ??
                  sinDato(
                    'El vehiculo internado no esta en el padron, o su ficha no declara categoria: ' +
                      'la grilla trae la clase de cada vehiculo, y de este no hay ninguna que traer.',
                  ),
                formatearFecha(fila.fechaDeIngreso),
                String(fila.dias),
                sinDato(
                  'El backend no publica ningun importe de custodia: la tarifa es dato de una ' +
                    'ordenanza que todavia no esta —D-02b—, y componerla aqui haria pagar al ' +
                    'administrado lo que diga la pantalla.',
                ),
                fila.estado,
              ],
            })),
            // **El total que la OPERACION publica** (#172). Hasta este issue no viajaba, y el
            // encabezado contaba las filas que tenia delante: cierto de lo que se ve, y sin decir
            // que son una ventana sobre el deposito entero —el artboard escribe «3 de 188»—. Lo
            // que NO se hace sigue igual: contarlo aqui. El «de» lo pone `useDatosDeLaHoja` con
            // `t()`, porque escrito en este archivo seria castellano que nunca podria traducirse.
            totalElementos: deposito.totalElementos,
          },
        ],
      ]),
      // `hayMas` y `totalPaginas`, dichos por el SERVIDOR (#187).
      nombrados: loQueDijoElServidor('vehiculos-internados', deposito),
      noPublicados: new Map([
        ...(suyo === undefined ? ([[coordenada(0, 6), NO_PUBLICADO]] as const) : []),
        [coordenada(0, 7), NO_PUBLICADO],
        [coordenada(0, 8), NO_PUBLICADO],
      ]),
    };
  },
};

/**
 * Las TRES hojas de Transito que piden de verdad. Se montan de **una linea** en `CONECTORES`.
 *
 * Son tres de cuatro: la que se queda fuera es `tra-cua`, y su motivo esta arriba.
 */
export const CONECTORES_DE_TRANSITO = {
  'tra-panel': TRA_PANEL,
  'tra-pap': TRA_PAP,
  'tra-veh': TRA_VEH,
} as const;

export { TRA_PANEL, TRA_PAP, TRA_VEH, SIN_PLACA };
export type { LoDeTraVeh };

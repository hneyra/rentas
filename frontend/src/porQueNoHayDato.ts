import type { Ausencia } from '@kamayuk/ui';
import type { Hoja, Operacion } from './pantallas/tipos.ts';
import { YA_SERVIDAS } from './datos/servidas.ts';

/**
 * **Por que una pantalla no tiene datos**, dicho con las palabras que corresponden a cada caso.
 *
 * <h2>Por que vive aqui y no en el interprete</h2>
 *
 * Porque cruza dos cosas que son de ESTE sistema: las operaciones que cada hoja declara y las que
 * su backend sirve. El interprete es de `@kamayuk/ui` desde #153 y no puede nombrar ninguna de las
 * dos; recibe el resultado ya redactado.
 *
 * <h2>Los cuatro casos NO son uno, y esto esta medido</h2>
 *
 * Sobre las 40 pantallas y las **109** declaraciones de operacion del artboard, **recontadas de
 * cero en #237 y no arrastradas**: el javadoc decia 104 desde #184 y la cuenta ya no cuadraba
 * —entre medias entraron declaraciones que nadie volvio a contar—, asi que aqui se escribe lo que
 * salio de barrer el arbol, no lo que decia la linea anterior mas uno. Las cifras de servidas
 * cambiaron ademas porque `YA_SERVIDAS` paso de 8 a **36** entre I-4 y #237:
 *
 *   · **12 hojas no declaran ninguna operacion servida.** No hay a quien preguntar.
 *   · **36 de las 109 declaraciones llevan el verbo `BASE`**, que el propio artboard define como
 *     «solo se leyo el `@RequestMapping` de la clase: sus metodos no se han verificado». **Once
 *     hojas lo tienen TODO en `BASE`** — de ellas no se sabe ni con que verbo se pediria. Eran
 *     doce hasta que `tra-panel` gano la operacion que la sirve.
 *   · **28 hojas tienen al menos una servida**, **26** declaran ademas alguna de LECTURA, y de
 *     esas **21** se pintan de verdad — las que tienen conector. Eran 20 hasta #230, que conecto
 *     `val-tip` corrigiendo su artboard.
 *   · Y una —`aut-panel`, desde #173— **no declara NINGUNA operacion**, que no es lo mismo que
 *     declararlas todas en `BASE` y hay que no confundirlo: ver el `length > 0` de abajo.
 *   · **TRES hojas no declaran ni un `GET` y si declaran escrituras** (#182, #237): `aut-sol`,
 *     `val-val` y `val-cart`. No son pantallas de consulta: son de **ejecutar**, y decirles «sin
 *     conectar» —«ninguna de las operaciones que declara la sirve el backend»— es falso dos veces,
 *     porque el backend SI las sirve y lo que pasa es que escriben. **Eran cuatro**: `territorio`
 *     salio de aqui en #237, no porque la frase se estrechara sino porque la hoja gano una lectura
 *     de verdad — y la cuenta se REMIDE, no se amplia a ojo.
 *   · Y el ultimo caso —servida, pedida y vacia— no lo produce este archivo todavia: llega cuando
 *     las pantallas pidan de verdad.
 *
 * Meter los tres primeros en un «no hay datos» unico seria mentir por omision: «este modulo no
 * esta conectado» y «esta pantalla se conecta pero de esta lista no se sabe el verbo» son cosas
 * distintas para quien tenga que arreglarlas.
 *
 * <h2>El cruce va por RUTA y solo con verbos de LECTURA. Las dos mitades importan</h2>
 *
 * **Por ruta**, porque dos operaciones que el backend SI sirve las declara el artboard como
 * `BASE`: `/rentas/contribuyentes` y `/rentas/beneficios`. Con el cruce estricto por verbo, las
 * hojas `predios` y `valores` perderian dato que existe.
 *
 * **Solo lectura**, porque una hoja cuya unica servida es `PUT /seguridad/sesion/ejercicio` no
 * tiene con que pintarse: un PUT no devuelve una pantalla.
 *
 * <h2>Y la frase que faltaba: «solo escribe» (#182)</h2>
 *
 * `territorio` —la hoja de la Determinacion— declaraba **siete** operaciones y **ni un `GET`**:
 * cuatro `POST` y tres `BASE` que, medidas contra `docs/50-api/formas-de-la-api.json` y contra sus
 * controladores (`AlcabalaController`, `EspectaculoController`, `VehicularController` publican **un
 * solo metodo y es `@PostMapping`**), tambien escriben. Con las tres frases de antes contestaba
 * «sin conectar», cuyo texto dice «ninguna de las operaciones que declara la sirve el backend» — y
 * eso es **falso**: el backend las sirve todas. Lo que ocurre es que **esta pantalla no consulta,
 * ejecuta**: se fija el sujeto y el ejercicio, se confirma —el artboard le declara un `AlertDialog`
 * «Confirmar antes de asentar»— y las cifras que dibuja son la **respuesta** a lo que se ejecuto.
 *
 * El arbol NO se corrigio entonces, y era lo correcto: esta bien transcrito del artboard, y el
 * artboard dice esto a proposito. Lo que faltaba era una **lectura que el backend no publicaba**, y
 * tenia su issue: `rentas`#207.
 *
 * <b>#207 la publico y #237 la conecta, asi que `territorio` ya no cae aqui.</b> Su hoja declara
 * hoy `GET /rentas/predial/determinaciones` —en el artboard y en `arbol.ts` a la vez, como #169,
 * #173, #179 y #184—, tiene conector, y esta funcion ni siquiera se llama para ella. La frase
 * **no se retira**: la siguen diciendo `aut-sol`, `val-val` y `val-cart`, que siguen sin tener a
 * quien preguntar.
 *
 * **La condicion no mira el contrato**, que este archivo no puede leer: mira que no haya ni un
 * `GET` declarado y que haya alguna escritura. Barridas las cuarenta hojas, son tres —`aut-sol`,
 * `val-val` y `val-cart`—, y las tres son de ejecutar: presentar una solicitud, notificar un valor,
 * emitir un lote. `val-val` declara ademas `BASE /consultas/valores`, que el contrato SI
 * publica como `GET`; por eso la frase dice «ninguna es una lectura **comprobada**» y no «todas
 * escriben», que seria mentir sobre esa.
 */

/** Los verbos con los que se puede pedir algo para dibujarlo. */
const DE_LECTURA = new Set(['GET', 'BASE']);

/**
 * Los verbos que **cambian datos**. Una operacion con uno de estos no dibuja una pantalla: la
 * ejecuta.
 *
 * `BASE` no esta aqui y no puede estarlo: significa «solo se leyo el `@RequestMapping` de la
 * clase», o sea que el verbo **no se sabe**. Meterlo seria afirmar que escribe, que es justo la
 * clase de invencion que este archivo existe para evitar.
 */
const DE_ESCRITURA = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Las rutas que el backend sirve, sin el verbo. Ver el javadoc: el cruce va por ruta. */
const RUTAS_SERVIDAS = new Set(YA_SERVIDAS.map((o) => o.ruta));

/** Las operaciones de una hoja que se pueden pedir Y estan servidas. */
export function operacionesUtiles(hoja: Hoja): readonly Operacion[] {
  return hoja.operaciones.filter(
    (o) => DE_LECTURA.has(o.verbo) && RUTAS_SERVIDAS.has(o.ruta),
  );
}

const NADA_SERVIDO: Ausencia = {
  enElCampo: 'sin conectar',
  explicacion:
    'Esta pantalla todavia no esta conectada: ninguna de las operaciones que declara la sirve el ' +
    'backend. Lo que se ve es su forma —que campos tiene y que columnas llevan sus listas—, no ' +
    'sus datos.',
  tono: 'info',
};

const SOLO_BASE: Ausencia = {
  enElCampo: 'sin verificar',
  explicacion:
    'De las rutas que esta pantalla declara solo se leyo el `@RequestMapping` de su controlador: ' +
    'sus metodos no se han verificado, asi que no se sabe ni con que verbo pedirlos. No se ' +
    'inventan.',
  tono: 'atencion',
};

const SOLO_ESCRIBE: Ausencia = {
  enElCampo: 'solo escribe',
  explicacion:
    'Esta pantalla no consulta: ejecuta. Ninguna de las operaciones que declara es una lectura ' +
    'comprobada, y las que si se verificaron escriben —disparan un calculo, dan de alta o de baja ' +
    'una deuda—: una escritura no devuelve una pantalla. Lo que se ve es su forma; sus cifras ' +
    'saldrian de la respuesta a lo que se ejecute, o de una lectura que todavia no publica nadie.',
  tono: 'atencion',
};

const SERVIDO_Y_SIN_PEDIR: Ausencia = {
  enElCampo: 'sin pedir',
  explicacion:
    'Esta pantalla SI tiene operaciones servidas, y todavia no las pide: la conexion llega en su ' +
    'propio issue. Hasta entonces no se ensena una cifra de ejemplo, porque en un sistema de ' +
    'recaudacion una cifra se lee como real.',
  tono: 'atencion',
};

/**
 * Que decir en una pantalla que no tiene dato.
 *
 * Devuelve `null` cuando la pantalla si podria tenerlo y alguien se lo va a dar — que hoy no pasa
 * nunca, y por eso esta escrito y no supuesto: cuando pase, esta funcion no cambia.
 */
export function porQueNoHayDato(hoja: Hoja): Ausencia {
  if (operacionesUtiles(hoja).length > 0) return SERVIDO_Y_SIN_PEDIR;
  // Todas en `BASE` es peor que ninguna servida: hay controlador, y no se sabe como llamarlo.
  //
  // **El `length > 0` no sobra, y lo demostro #173.** `[].every(...)` es `true` —es la verdad
  // vacia de JavaScript, no un descuido del motor—, asi que una hoja que no declara NINGUNA
  // operacion contestaba «sin verificar»: «de las rutas que esta pantalla declara solo se leyo el
  // `@RequestMapping` de su controlador». Eso afirma dos cosas falsas a la vez —que declara rutas
  // y que hay un controlador detras— justo de la hoja de la que no se sabe nada. Mientras las
  // cuarenta declararon al menos una operacion el defecto no tenia sintoma; `aut-panel` se quedo
  // sin ninguna al devolverle a `aut-tram` la que la sirve, y entonces lo tuvo.
  // Ni un `GET` y alguna escritura: la hoja **ejecuta**, y decirle «sin conectar» es mentir (#182).
  //
  // Los dos lados de la condicion hacen falta. Sin «ni un `GET`», caeria aqui cualquier hoja con un
  // boton; sin «alguna escritura», caeria la que no declara NADA —y esa no ejecuta: no se sabe nada
  // de ella, que es `aut-panel` desde #173—.
  //
  // **Y va ANTES de `todasSonBase`, que no es indiferente.** Detras, una hoja toda en `BASE` ya
  // habria salido por «sin verificar» y entonces meter `BASE` en `DE_ESCRITURA` no cambiaria ni una
  // respuesta: el error seria **indetectable**, y la afirmacion de que `BASE` no escribe no la
  // sostendria ninguna prueba. Delante, ensancharla asi convierte a `tra-cua` —una sola operacion,
  // en `BASE`— en una hoja que «solo escribe», y eso sale rojo. Medido en las dos posiciones.
  const niUnaLectura = !hoja.operaciones.some((o) => o.verbo === 'GET');
  const algunaEscribe = hoja.operaciones.some((o) => DE_ESCRITURA.has(o.verbo));
  if (niUnaLectura && algunaEscribe) return SOLO_ESCRIBE;
  const todasSonBase =
    hoja.operaciones.length > 0 && hoja.operaciones.every((o) => o.verbo === 'BASE');
  return todasSonBase ? SOLO_BASE : NADA_SERVIDO;
}

export { NADA_SERVIDO, SOLO_BASE, SOLO_ESCRIBE, SERVIDO_Y_SIN_PEDIR };

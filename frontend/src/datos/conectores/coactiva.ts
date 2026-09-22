import { coordenada, type CeldaDeLaTabla } from '@kamayuk/ui';

import type { Conector, Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import { formatearFecha, formatearImporte } from '../../dominio/formato.ts';
import type {
  ActoDelExpediente,
  LiquidacionDeCostas,
  PrescripcionDeclarada,
  ProcesoDelExpediente,
  ResumenDeLaCarteraCoactiva,
} from '../lecturas.ts';
import { RUTAS, pedirPagina, pedirUno } from '../lecturas.ts';

/**
 * **Lo que las tres hojas de Coactiva sacan de sus operaciones** (#170).
 *
 * Aparte de `conectores.ts` porque cuatro modulos se conectan a la vez y el registro es el unico
 * archivo que los cuatro tocan: repartidos por modulo, cada uno se lee —y se revisa— entero.
 * `coa-panel` **se mudo aqui** con #170 en vez de quedarse suelto: el criterio es el modulo, y
 * dejar uno de los tres en otro archivo obliga a mirar dos sitios para saber que pide Coactiva.
 * Lo que NO se hizo es copiarlo: en `conectores.ts` ya no esta.
 *
 * <h2>La regla que gobierna las tres, y aqui es donde mas tienta</h2>
 *
 * **No se calcula un agregado que la operacion no publica** (`conectores.ts`). En cobranza
 * coactiva la tentacion tiene nombre: sumar las costas de la pagina que llego daria un «total de
 * costas» **indistinguible de uno real**, y una costa es deuda que se le anade al obligado. Un
 * hueco que dice «no publicado» le dice a quien mantiene el backend que le falta; un total mal
 * sumado no se distingue de uno bueno hasta que alguien lo cobra.
 *
 * <h2>Las cifras llevan su fecha, unidas al dibujarlas</h2>
 *
 * Regla 9 (RNF-075): no existe «la deuda», existe `deudaActualizadaA(fecha)`. El contrato publica
 * los importes como texto plano y la fecha al lado —`deudaAlDia` en el expediente, `fecha` en la
 * liquidacion—, asi que el conector los junta: `S/ 9,412.15 · 04/08/2026`. El separador es el
 * punto medio del artboard, y no una palabra: lo que sale de aqui es **dato**, y un «al» escrito
 * en este archivo seria castellano que nunca podria traducirse (#103).
 */

/**
 * **Una celda que llego sin dato, con el motivo dentro** (`kamayuk-lib`#87, #195).
 *
 * Era `SIN_DATO = '—'`, la raya del artboard escrita como cualquier otra cadena. La raya **sigue
 * siendo lo que se ve** —la declara la tabla en su `sinDato`—, y lo que cambia es que la celda
 * dice **por que**, anunciado con `title`: hasta #195 el motivo existia, estaba escrito, y vivia
 * solo en el javadoc de este archivo, donde no lo lee quien mira la pantalla.
 *
 * `texto: null` es «aqui no hay dato»: nunca `''` —que se lee como un blanco— y **nunca un `0`**,
 * que en una columna de costas se leeria como «este acto no cuesta nada».
 */
const sinDato = (porQue: string): CeldaDeLaTabla => ({ texto: null, nota: porQue });

/** Lo que dice la celda de un acto en el que la operacion no publica en que quedo. */
/** Lo que dice la celda «Cantidad» de `coa-cost`. El arancel tarifa el acto una vez. */
const SIN_CANTIDAD =
  'CostaResource no publica ninguna cantidad, y no es un olvido: el arancel tarifa el acto una ' +
  'vez y «costa_acto_uq» impide liquidarlo dos, asi que una cantidad solo podria valer uno.';

const SIN_MEDIDA =
  'El expediente solo publica en que quedo un acto cuando dicto una medida cautelar; de este no ' +
  'publica ninguna. Escribir «Conforme» aqui seria afirmar que el acto surtio efecto.';

/** `"9412.15"` + `"2026-08-04"` -> `"S/ 9,412.15 · 04/08/2026"`. Regla 9. */
function importeConSuFecha(importe: string, fecha: string): string {
  return `${formatearImporte(importe)} · ${formatearFecha(fecha)}`;
}

/**
 * `coa-panel` — la cartera coactiva por etapa (#170, #272).
 *
 * <h2>Lo que #272 corrigio: un rotulo que prometia una cosa sobre una cifra que era otra</h2>
 *
 * Hasta #272 el unico campo con dato era «Expedientes abiertos», y lo llenaba el recuento de
 * `GET /coactiva/deudas`. La **unidad** era la correcta —esa operacion devuelve una fila por
 * expediente y no por deuda, medido en `ConsultaDeDeudasCoactivas`—, pero el **adjetivo** no: ese
 * recuento cuenta TODOS los expedientes del criterio, concluidos incluidos, y ademas cuenta los
 * que la propia respuesta descarta por no tener nada que cobrar. O sea que la pantalla decia
 * «abiertos» sobre el numero de «todos», en verde. Es el mismo modo de fallo que #254 encontro en
 * `val-tip`. Desde #307 ese campo **ya no se llama `totalElementos`** sino
 * `expedientesDelCriterio`, justamente para que nadie vuelva a leerlo como el total de una
 * relacion.
 *
 * Ahora los cuatro campos que se dibujan salen de **una sola** operacion —`GET
 * /coactiva/cartera/resumen`— y cada uno de un campo que se llama como el rotulo:
 *
 * <table>
 *   <tr><td>Expedientes abiertos</td><td>`abiertos` — todos menos los concluidos</td></tr>
 *   <tr><td>Con REC notificada</td><td>`conRecNotificada`</td></tr>
 *   <tr><td>Con medida cautelar</td><td>`conMedidaCautelar`</td></tr>
 *   <tr><td>Sin REC</td><td>`sinRec`</td></tr>
 * </table>
 *
 * **Las tres ultimas son etapas DISJUNTAS y no suman «abiertos»**: el estado es el del ultimo
 * movimiento, asi que un expediente con la medida trabada cuenta en «Con medida cautelar» y no en
 * «Con REC notificada», y quedan fuera de las tres los que estan en REC-1 emitida, REC-2 emitida
 * o suspendidos. Aqui no se resta ni se suma nada para que cuadren: cuadrarlas es `porEtapa`, que
 * la lectura declara y esta pantalla no dibuja porque el artboard no tiene donde.
 *
 * <h2>«Deuda en cartera» se queda en «no publicado», con el motivo medido</h2>
 *
 * Es el quinto campo y **sigue sin dato**, y no por falta de sumandos: `GET /coactiva/deudas`
 * publica `totalS` por expediente y sumarlo daria un numero. Daria uno **de la pagina que llego**,
 * que es justo lo que `conectores.ts` prohibe. Y el backend tampoco la publica, por dos razones
 * que estan escritas en el contrato: componerla cuesta una lectura del libro **por expediente**
 * —lo mismo que `ExpedientesSinRec` se nego a pagar en la pantalla de aterrizaje— y no seria
 * segura, porque dos expedientes del mismo obligado pueden formalizar la misma obligacion por dos
 * valores distintos y la suma la contaria dos veces. Un importe casi correcto en un panel es peor
 * que un hueco: nadie lo comprueba porque se parece al bueno.
 */
const COA_PANEL: Conector = {
  clave: ['coa-panel', 'resumenDeLaCarteraCoactiva'],
  pedir: ({ senal }) =>
    pedirUno<ResumenDeLaCarteraCoactiva>(RUTAS.resumenDeLaCarteraCoactiva, senal),
  repartir: (resumen: ResumenDeLaCarteraCoactiva): Reparto => ({
    // `0|0` es el desplegable de ejercicio, no un campo de solo lectura: las coordenadas son las
    // del bloque entero y no las de los campos `r`. Lo cazo la guarda de este archivo.
    valores: new Map([
      [coordenada(0, 1), String(resumen.abiertos)],
      [coordenada(0, 2), String(resumen.conRecNotificada)],
      [coordenada(0, 3), String(resumen.conMedidaCautelar)],
      [coordenada(0, 4), String(resumen.sinRec)],
    ]),
    filas: new Map(),
    noPublicados: new Map([[coordenada(0, 5), NO_PUBLICADO]]),
  }),
};

/**
 * `coa-exp` — el expediente coactivo, su proceso y sus actos.
 *
 * <h2>Dos operaciones, y la segunda no se puede pedir sin la primera</h2>
 *
 * `GET /coactiva/expedientes` dice **cual** expediente se dibuja —el primero de la cartera,
 * porque esta pantalla todavia no tiene con que elegirlo— y `GET
 * /coactiva/expedientes/{numero}/proceso` trae la cabecera otra vez y ademas sus actos. Los seis
 * campos de la cabecera se leen **del proceso** y no de la lista, aunque las dos los publiquen:
 * dos fuentes para el mismo dato es como se llega a una pantalla que se contradice consigo misma.
 *
 * Con la cartera vacia no hay expediente que pedir y el conector devuelve `null`, que la pantalla
 * dice como «sin datos» — un hecho del negocio, no una averia.
 *
 * <h2>Campo a campo: cinco con dato, uno «no publicado» y cuatro que no se rellenan</h2>
 *
 * <ul>
 *   <li><b>Nº de expediente</b> ← `expediente.numero`.</li>
 *   <li><b>Contribuyente</b> ← `expediente.codContribuyente`. Es el <b>codigo</b> del padron y no
 *       el nombre: la operacion no publica el nombre, y esta pantalla no va a pedirselo al padron
 *       para rellenar un hueco.</li>
 *   <li><b>Documento</b> → <b>«no publicado»</b>. Ni el tipo ni el numero de documento del
 *       obligado estan en `ExpedienteResource`; sacarlos de `GET /rentas/contribuyentes` seria
 *       cruzar dos operaciones para que un campo no se vea vacio.</li>
 *   <li><b>Fecha de apertura</b> ← `expediente.fechaDeApertura`.</li>
 *   <li><b>Deuda en el expediente</b> ← `deudaMateriaDeCobranza`, con `deudaAlDia`.</li>
 *   <li><b>Costas acumuladas</b> ← `costas`, con `deudaAlDia`. Las siete cifras del expediente
 *       estan todas a esa fecha: es a la que el backend proyecto el interes.</li>
 * </ul>
 *
 * Y cuatro que la operacion **si** publica y aun asi no se rellenan, porque rellenarlos se veria
 * peor que dejarlos:
 *
 * <ul>
 *   <li><b>Ejecutor</b> y <b>Auxiliar coactivo</b> son desplegables de lista cerrada, y sus
 *       opciones son los cuatro nombres de ejemplo del artboard. `ejecutor` y `auxiliar` llegan
 *       como texto libre: un nombre servido que no este entre las opciones deja el control
 *       <b>en blanco</b> —Radix no dibuja un valor que no es ninguna de sus opciones—, o sea que
 *       ponerlo perderia el dato en vez de ensenarlo.</li>
 *   <li><b>Etapa</b>, lo mismo con `estado`: las cinco opciones del artboard no son los diez
 *       estados que `EstadoDelExpediente` deriva del historial.</li>
 *   <li><b>Direccion referencial</b> es el area donde se escribe la direccion nueva, y cambiarla
 *       es `PATCH /coactiva/expedientes/{numero}/direccion-referencial`. Esta interfaz hace UNA
 *       escritura y no es esa; rellenar el area con lo servido dibujaria un formulario de edicion
 *       que no guarda.</li>
 * </ul>
 *
 * <h2>La tabla: cuatro columnas de cinco, y la quinta YA tiene llave (#177)</h2>
 *
 * «Nº», «Acto», «Fecha» y «Estado» salen de `actuaciones[]` —`numero`, `titulo`, `fecha` y
 * `medida`—. **«Costa S/» sigue en raya, pero ya no por falta de llave**: hasta #177
 * `ActoResource` no publicaba ningun identificador del acto —eran diez campos y ninguno era el
 * `actoId`—, de modo que lo unico comun con `CostaResource` era el `tipo`, y emparejar por tipo
 * se rompe el primer dia que un expediente tenga dos EMBARGO o dos TASACION: `costa_acto_uq` es
 * por acto, no por tipo, y la costa de uno acabaria escrita en la fila del otro. Una costa es
 * deuda que se le anade al obligado; ponerla en la fila equivocada es peor que no ponerla.
 *
 * **Desde #177 `actuaciones[].actoId` viaja**, con el mismo nombre y el mismo tipo que
 * `costas[].actoId`, y el cruce es posible: lo prueba `LaCostaCaeEnSuActoTest` del backend con un
 * expediente de dos EMBARGO. Lo que falta ya no es la llave, es **pedir la tercera operacion**:
 * esta hoja pide dos —la cartera y el proceso— y las costas las publica `GET
 * /coactiva/liquidaciones-costas`, que es una peticion mas, con su decision de que liquidacion se
 * mira y que dice la celda del acto que ninguna liquidacion tarifa todavia. Eso es **#200** y no
 * entra en #177: encender una ruta es una decision que se revisa sola (ver la lista escrita a
 * mano de `camino-a-la-api.test.ts`).
 *
 * Y «Estado» dibuja `medida`, que es lo unico que la operacion dice de en que quedo el acto. Solo
 * la REC-2 la lleva, asi que las demas filas dicen la raya — nunca «Conforme», que seria afirmar
 * que el acto surtio efecto sin que nadie lo haya publicado.
 */
/** Lo que `coa-exp` recibe: el proceso del expediente y lo que se pudo saber de sus costas. */
interface ProcesoYSusCostas {
  readonly proceso: ProcesoDelExpediente;
  readonly costas: CostasDelExpediente;
}

/**
 * **Lo que se sabe de las costas de un expediente**: la tarifada de cada acto, o por que no.
 *
 * Es una union de dos ramas y no un mapa con huecos, a proposito: «este acto todavia no se
 * liquido» y «no se pudo preguntar por las costas» son cosas distintas y la celda tiene que poder
 * decir cual. Con un mapa vacio las dos se dirian igual, y la segunda es una averia disfrazada de
 * hecho del negocio.
 */
type CostasDelExpediente =
  | { readonly seSupo: true; readonly porActo: ReadonlyMap<number, string> }
  | { readonly seSupo: false; readonly porQue: string };

/**
 * **La costa de cada acto, cruzada por `actoId`** (#200).
 *
 * <h2>Por `actoId` y NUNCA por `tipo`</h2>
 *
 * Hasta #177 `ActoResource` no publicaba ningun identificador —eran diez campos y ninguno era el
 * `actoId`—, asi que lo unico comun con `CostaResource` era el `tipo`. Emparejar por tipo se rompe
 * el primer dia que un expediente tenga **dos EMBARGO** o dos TASACION: `costa_acto_uq` es por
 * acto y no por tipo, y la costa de uno acabaria escrita en la fila del otro. Una costa es deuda
 * que se le anade al obligado; ponerla en la fila equivocada es peor que no ponerla.
 *
 * <h2>Y aqui NO se suma, que es lo que parecia que habia que hacer</h2>
 *
 * Un expediente puede tener **varias** liquidaciones y la costa de un acto esta en una de ellas,
 * asi que hay que recorrerlas todas. Recorrerlas **no es sumarlas**: `costa_acto_uq` garantiza que
 * un acto se tarifa <b>una sola vez</b>, o sea que lo que se busca es una fila y no un total. Y
 * eso importa por la regla 1: sumar dos importes servidos en el navegador es aritmetica sobre
 * dinero, y el importe llega como texto justamente para que nadie la haga.
 *
 * Si alguna vez llegaran **dos** costas del mismo acto, la garantia de la base se habria roto y
 * aqui no se elige una: la celda lo dice. Elegir la primera pondria un importe plausible donde
 * hay una contradiccion.
 */
function costasPorActo(liquidaciones: readonly LiquidacionDeCostas[]): CostasDelExpediente {
  const porActo = new Map<number, string>();
  const repetidos: number[] = [];
  for (const liquidacion of liquidaciones) {
    for (const costa of liquidacion.costas) {
      if (porActo.has(costa.actoId)) repetidos.push(costa.actoId);
      porActo.set(costa.actoId, costa.montoS);
    }
  }
  if (repetidos.length > 0) {
    return {
      seSupo: false,
      porQue:
        'Dos liquidaciones distintas tarifan el mismo acto, y «costa_acto_uq» dice que eso no ' +
        `puede pasar (actos ${repetidos.join(', ')}). Elegir una de las dos pondria un importe ` +
        'plausible donde hay una contradiccion, en una columna que es deuda del obligado.',
    };
  }
  return { seSupo: true, porActo };
}

/** La celda «Costa S/» de una actuacion: el importe que la tarifa, o por que no lo hay. */
function costaDelActo(acto: ActoDelExpediente, costas: CostasDelExpediente): CeldaDeLaTabla {
  if (!costas.seSupo) return sinDato(costas.porQue);
  const tarifada = costas.porActo.get(acto.actoId);
  if (tarifada !== undefined) {
    // Tal como llega y sin el simbolo, que lo lleva el rotulo de la columna — igual que `coa-cost`.
    return tarifada;
  }
  return sinDato(
    'Ninguna liquidacion de costas de este expediente tarifa este acto todavia. **No es cero**: ' +
      'cero seria que el arancel dice que no cuesta nada, y lo que pasa es que no se ha liquidado.',
  );
}

const COA_EXP: Conector = {
  clave: ['coa-exp', 'proceso'],
  pedir: async ({ senal }) => {
    const cartera = await pedirPagina<{ readonly numero: string }>(
      RUTAS.expedientesCoactivos,
      senal,
    );
    const primero = cartera.contenido[0];
    // Sin expediente no hay proceso que pedir. `null` es «se pregunto y no hay», que la pantalla
    // dice distinto de un fallo.
    if (primero === undefined) return null;
    const proceso = await pedirUno<ProcesoDelExpediente>(
      RUTAS.procesoDelExpediente(primero.numero),
      senal,
    );
    return { proceso, costas: await costasDe(primero.numero, senal) };
  },
  repartir: ({ proceso, costas }: ProcesoYSusCostas): Reparto => {
    const { expediente } = proceso;
    return {
      valores: new Map([
        [coordenada(0, 0), expediente.numero],
        [coordenada(0, 1), expediente.codContribuyente],
        [coordenada(0, 3), formatearFecha(expediente.fechaDeApertura)],
        [
          coordenada(0, 6),
          importeConSuFecha(expediente.deudaMateriaDeCobranza, expediente.deudaAlDia),
        ],
        [coordenada(0, 7), importeConSuFecha(expediente.costas, expediente.deudaAlDia)],
      ]),
      // Vacio: esta tabla lleva `clave` desde #195, asi que sus filas van por `tablas` — el unico
      // camino cuyas celdas pueden decir que no hay dato y por que.
      filas: new Map(),
      tablas: new Map([
        [
          'actos-del-expediente',
          {
            filas: proceso.actuaciones.map((acto) => ({
              clave: String(acto.actoId),
              celdas: [
                acto.numero,
                acto.titulo,
                formatearFecha(acto.fecha),
                costaDelActo(acto, costas),
                acto.medida ?? sinDato(SIN_MEDIDA),
              ],
            })),
            // **Sin total**: `actuaciones[]` no es una pagina —el proceso las publica todas— asi
            // que no hay ningun `totalElementos` que enseñar, y el interprete cuenta las que hay.
          },
        ],
      ]),
      noPublicados: new Map([[coordenada(0, 2), NO_PUBLICADO]]),
    };
  },
};

/**
 * Las liquidaciones de costas de un expediente, **sin poder tumbar la pantalla** (#200).
 *
 * Es una tercera lectura para UNA columna de cinco, asi que su fallo no puede costar la tabla
 * entera: con un `Promise.all`, un 500 de esta operacion dejaria `coa-exp` diciendo «fallo» y las
 * otras cuatro columnas —que salen de operaciones que contestaron bien— sin dibujarse. Asi que se
 * atrapa aqui y lo que se pierde es la celda, que ademas dice por que.
 *
 * Y `hayMas` se mira: si las liquidaciones de este expediente no cupieran en la pagina que se
 * pide, un acto liquidado en la siguiente diria «no se ha liquidado» — un hueco falso, que es peor
 * que un hueco.
 */
async function costasDe(numero: string, senal: AbortSignal): Promise<CostasDelExpediente> {
  try {
    const relacion = await pedirPagina<LiquidacionDeCostas>(
      RUTAS.liquidacionesDelExpediente(numero),
      senal,
    );
    if (relacion.hayMas) {
      return {
        seSupo: false,
        porQue:
          `Las liquidaciones de costas de ${numero} no caben en una pagina ` +
          `(${String(relacion.totalElementos)} en total). Con solo las primeras, un acto ` +
          'liquidado en la siguiente diria que no esta liquidado, que es un hueco falso.',
      };
    }
    return costasPorActo(relacion.contenido);
  } catch (fallo) {
    return {
      seSupo: false,
      porQue:
        'No se pudieron pedir las liquidaciones de costas de este expediente' +
        (fallo instanceof Error && fallo.message !== '' ? `: ${fallo.message}` : '.') +
        ' Las otras cuatro columnas si llegaron, y por eso la tabla se dibuja igual.',
    };
  }
}

/** Lo que `coa-cost` necesita de sus dos operaciones, ya pedido. */
interface CostasYPrescripcion {
  readonly liquidacion: LiquidacionDeCostas;
  /** La primera declaracion sobre el tributo de la liquidacion, o `null` si no hay ninguna. */
  readonly prescripcion: PrescripcionDeclarada | null;
}

/**
 * `coa-cost` — la liquidacion de costas y el plazo de prescripcion.
 *
 * <h2>Dos operaciones, y la segunda se acota con lo que trae la primera</h2>
 *
 * `GET /coactiva/liquidaciones-costas` trae la liquidacion con su detalle **linea por acto**, y
 * `GET /coactiva/prescripcion?tributo=` la relacion de declaraciones sobre **ese mismo tributo**.
 * El tributo es la unica llave que las dos comparten: `LiquidacionResource` no publica el
 * contribuyente, asi que `?codContribuyente=` no tiene de donde salir. Sin acotar, lo que llegaria
 * seria la primera declaracion de la relacion entera —de cualquiera— y al lado de una liquidacion
 * se leeria como suya.
 *
 * <h2>Campo a campo: tres con dato y TRES «no publicado», y los tres motivos son distintos</h2>
 *
 * <ul>
 *   <li><b>Nº de expediente</b> ← `liquidacion.expedCoact`.</li>
 *   <li><b>Actos dictados</b> → <b>«no publicado»</b>. `costas[]` tiene una linea por acto
 *       <b>liquidado</b>, y una liquidacion puede cubrir un subconjunto —`LiquidarCostas` recibe
 *       los actos que se liquidan—. Contar sus lineas daria un numero exacto y falso: los actos
 *       dictados en el expediente los publica `GET /coactiva/expedientes/{numero}/proceso`, que
 *       esta pantalla no pide.</li>
 *   <li><b>Costas tasadas</b> ← `totalS`, con `fecha`. El backend lo dice en su propio javadoc:
 *       `totalS` es «lo liquidado, congelado a `fecha`», que es exactamente la suma de la tabla de
 *       abajo — y por eso <b>no se suma aqui</b>: se pide.</li>
 *   <li><b>Gastos de notificacion</b> → <b>«no publicado»</b>. La liquidacion no los separa: un
 *       acto de notificacion es una linea mas de `costas[]`, con su arancel, y no hay ningun campo
 *       que diga cuanto de `totalS` es gasto de notificacion.</li>
 *   <li><b>Total de costas</b> → <b>«no publicado»</b>, y es el hueco que mas cuesta dejar. El
 *       artboard lo escribe como tasadas + gastos; con los gastos sin publicar, escribir aqui
 *       `totalS` seria afirmar que los gastos son cero. Un cero calculado mal no es informacion:
 *       es una mentira con formato.</li>
 *   <li><b>Reloj de prescripcion</b> ← `plazo` de la declaracion, «4 ANIOS»: el plazo del art. 43
 *       leido del conjunto sellado. <b>No es la fecha en que prescribe</b>, y no se calcula: la
 *       relacion no la publica —sale del computo ejercicio por ejercicio de `POST
 *       /coactiva/prescripcion`— y el propio backend advierte que «no es el inicio mas el plazo».
 *       Sin ninguna declaracion sobre ese tributo, el campo dice «no publicado».</li>
 * </ul>
 *
 * <h2>La tabla: tres columnas de cuatro</h2>
 *
 * «Acto» ← `descripcion`, la glosa impresa; «Arancel» ← `arancelFuente`, que es la llave del
 * parametro sellado con su documento fuente —lo que explica la cifra—; «Costa S/» ← `montoS`, tal
 * como llega y sin el simbolo, que lo lleva el rotulo de la columna. **«Cantidad» dice la raya**:
 * `CostaResource` no publica ninguna, porque el arancel tarifa el acto una vez y `costa_acto_uq`
 * impide liquidarlo dos.
 */
const COA_COST: Conector = {
  clave: ['coa-cost', 'liquidacion-de-costas'],
  pedir: async ({ senal }) => {
    const relacion = await pedirPagina<LiquidacionDeCostas>(RUTAS.liquidacionesDeCostas, senal);
    const liquidacion = relacion.contenido[0];
    if (liquidacion === undefined) return null;
    const declaradas = await pedirPagina<PrescripcionDeclarada>(
      RUTAS.prescripcionesDe(liquidacion.tributo),
      senal,
    );
    return { liquidacion, prescripcion: declaradas.contenido[0] ?? null };
  },
  repartir: ({ liquidacion, prescripcion }: CostasYPrescripcion): Reparto => ({
    valores: new Map([
      [coordenada(0, 0), liquidacion.expedCoact],
      [coordenada(0, 2), importeConSuFecha(liquidacion.totalS, liquidacion.fecha)],
      ...(prescripcion === null
        ? []
        : ([[coordenada(0, 5), prescripcion.plazo]] as const)),
    ]),
    filas: new Map(),
    tablas: new Map([
      [
        'costas-por-acto',
        {
          filas: liquidacion.costas.map((costa) => ({
            clave: String(costa.actoId),
            celdas: [
              costa.descripcion,
              costa.arancelFuente,
              // Desde #195 la celda dice POR QUE, y no solo que no hay: el arancel tarifa el acto
              // una vez, asi que una cantidad no significaria nada aqui.
              sinDato(SIN_CANTIDAD),
              costa.montoS,
            ],
          })),
        },
      ],
    ]),
    noPublicados: new Map([
      [coordenada(0, 1), NO_PUBLICADO],
      [coordenada(0, 3), NO_PUBLICADO],
      [coordenada(0, 4), NO_PUBLICADO],
      ...(prescripcion === null ? ([[coordenada(0, 5), NO_PUBLICADO]] as const) : []),
    ]),
  }),
};

/**
 * Las hojas de Coactiva que piden de verdad. **Son tres de cuatro.**
 *
 * <h2>Y `coa-cart` no entra, con su motivo medido</h2>
 *
 * Sus ocho campos —deuda a fraccionar, inicial, cuotas, interes, cuota resultante— los publica
 * **`POST /coactiva/convenios`**, que <b>crea</b> un convenio de fraccionamiento. No existe
 * `GET /coactiva/convenios`: es un hueco del backend y tiene su issue aparte. Pedirle datos a una
 * operacion que escribe seria fraccionar la deuda de alguien para pintar una pantalla.
 *
 * Su tabla de medidas cautelares **si** podria salir de `actuaciones[].medida`, y aun asi no se
 * conecta a medias: de sus cinco columnas —Tipo, Sobre, Dictada, Importe S/, Estado— la operacion
 * publica <b>dos</b> (`medida` y `fecha`), y una tabla no tiene «no publicado» por celda. Quedaria
 * una pantalla con ocho campos diciendo «no publicado» y una tabla con tres de cada cinco celdas
 * en raya, que se lee como una pantalla rota y no como una pantalla que espera a su operacion.
 * Mientras tanto dice, entera y con una sola frase, que no esta conectada.
 */
export const CONECTORES_DE_COACTIVA = {
  'coa-panel': COA_PANEL,
  'coa-exp': COA_EXP,
  'coa-cost': COA_COST,
} as const;

export {
  COA_PANEL,
  COA_EXP,
  COA_COST,
  SIN_CANTIDAD,
  SIN_MEDIDA,
  costaDelActo,
  costasPorActo,
  importeConSuFecha,
  sinDato,
};
export type { CostasDelExpediente, CostasYPrescripcion, ProcesoYSusCostas };

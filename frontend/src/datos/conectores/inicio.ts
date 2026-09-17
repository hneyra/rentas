import { coordenada, type Coordenada } from '@kamayuk/ui';

import { formatearImporte } from '../../dominio/formato.ts';
import type { Conector, Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';
import type {
  CorridaDelPredial,
  FilaDeAvance,
  ImporteConFecha,
  IndicadorDeRecaudacion,
  KpiDeRecaudacion,
  PanelDeAvance,
  TrabajoParado,
} from '../lecturas.ts';
import { RUTAS, pedirUno } from '../lecturas.ts';

/**
 * **Las tres hojas de Inicio, conectadas** (#167).
 *
 * <h2>Que pide cada una</h2>
 *
 * <table>
 *   <tr><td>`ini-panel`</td><td>`GET /indicadores/recaudacion` <b>y</b>
 *     `GET /rentas/predial/corridas/ultima`</td></tr>
 *   <tr><td>`ini-flujo`</td><td>`GET /indicadores/recaudacion`</td></tr>
 *   <tr><td>`ini-parado`</td><td>`GET /indicadores/trabajo-parado`</td></tr>
 * </table>
 *
 * La cuarta hoja del modulo, `ini-cierre`, **no entra y no va a entrar por aqui**: sus nueve rutas
 * se fueron a `caja` con P5D (ADR-0026). No es que falte conectarla; es que lo que ensena no lo
 * publica este sistema.
 *
 * <h2>Lo que NO publica cada operacion, campo por campo (AC2)</h2>
 *
 * · **`ini-panel` · «Contribuyentes activos»** — <b>no publicado</b>. No esta en
 *   `GET /indicadores/recaudacion` ni en la ultima corrida, y **no se deduce de la corrida aunque
 *   se parezca**: la etapa «Lectura del padron» trae 62 418 registros y el artboard ensena 62 418
 *   contribuyentes activos. Que coincidan no las hace lo mismo —una es cuantas filas leyo la
 *   corrida y la otra cuantos contribuyentes estan activos hoy—, y el dia que difieran nadie
 *   sabria que el numero era deducido. Es el mismo razonamiento con que `panel` se niega a deducir
 *   «cuentas emitidas» de su ultima etapa.
 * · **`ini-flujo` · «Avance» de una fila sin base** — la operacion publica `pct: 0` **y**
 *   `avanceConocido: false`, que es justamente el par que separa el cero medido del cero que no se
 *   pudo medir. Ahi se escribe `sin medir`, nunca `0 %`: un tributo sin cargos asentados dibujado
 *   al 0 % se lee como «no se ha cobrado nada».
 * · **`ini-parado` · «Importe S/» de un frente que no se cifra** — la operacion publica
 *   `importe: null`, que el backend declara a proposito para que esto se pueda distinguir de
 *   `"0.00"`. Ahi se escribe `sin cifrar`.
 *
 * Los tres huecos son del mismo tipo y ninguno es un cero. Ver el javadoc de `conectores.ts`: un
 * numero deducido es indistinguible de uno real, y eso es peor que el hueco.
 *
 * <h2>Lo que este archivo NO calcula, y podria parecer que si</h2>
 *
 * «Saldo S/» **no es** emitido menos recaudado: es `rows[].pendiente`, un `ImporteConFecha` que la
 * operacion publica. Restar los otros dos daria una cifra parecida y distinta —lo recaudado del
 * bloque es lo cobrado del propio ejercicio y lo pendiente es el insoluto a la fecha de corte— y
 * seria ademas aritmetica sobre dinero en el navegador (regla 1, y la prohibicion
 * `aritmetica-con-importes`).
 */

/** Lo que se escribe donde la operacion publica `importe: null`. Nunca `0.00`. */
const SIN_CIFRAR = 'sin cifrar';

/** Lo que se escribe donde `avanceConocido` es falso. Nunca `0 %`. */
const SIN_MEDIR = 'sin medir';

/**
 * El prefijo de moneda que `formatearImporte` pone siempre.
 *
 * Se quita **solo** en las celdas de una tabla cuyo ROTULO ya lo dice —«Emitido S/», «Importe
 * S/»—, que es como lo escribe el artboard: `9,418,204.60` bajo la cabecera y `S/ 23,725,394.80`
 * en un campo suelto. El importe se formatea igual en los dos sitios: lo unico que cambia es que
 * la columna no repita la moneda cinco veces.
 */
const LA_MONEDA = /^S\/\s/;

/** Un importe para una columna que ya dice «S/» en su rotulo; `sin cifrar` cuando llega nulo. */
function enColumnaDeSoles(importe: ImporteConFecha | null): string {
  return importe === null ? SIN_CIFRAR : formatearImporte(importe.importe).replace(LA_MONEDA, '');
}

/**
 * El KPI que se llama asi, o `undefined`.
 *
 * Por su `label` y no por su posicion en la lista: el orden de los indicadores es una decision del
 * backend que nadie ha prometido mantener, y leer el segundo de la lista dibujaria «Cartera
 * pendiente» bajo el rotulo «Avance» sin que nada se pusiera rojo. Si el nombre cambia, el campo
 * dice «no publicado» —que es cierto: con ese nombre no viene— en vez de ensenar otra cifra.
 */
function kpiLlamado(
  recaudacion: IndicadorDeRecaudacion,
  label: string,
): KpiDeRecaudacion | undefined {
  return recaudacion.kpis.find((kpi) => kpi.label === label);
}

/**
 * El bloque de filas que va por tributo, o `undefined`.
 *
 * La operacion publica **dos** paneles —uno por tributo y otro por mes— y la tabla de `ini-flujo`
 * se titula «Cuadre por tributo». Se busca por el titulo y **no** se coge el primero: si algun dia
 * llegaran en otro orden, coger el primero pintaria doce meses bajo la columna «Tributo», con
 * cifras correctas y un rotulo que miente. Sin el, la tabla dice su ausencia.
 */
function panelPorTributo(recaudacion: IndicadorDeRecaudacion): PanelDeAvance | undefined {
  return recaudacion.paneles.find((panel) => /tributo/i.test(panel.title));
}

/** Las cinco celdas de una fila de «Cuadre por tributo», en el orden de sus columnas. */
function cuadreDelTributo(fila: FilaDeAvance): readonly string[] {
  return [
    fila.label,
    enColumnaDeSoles(fila.cargado),
    enColumnaDeSoles(fila.importe),
    enColumnaDeSoles(fila.pendiente),
    fila.avanceConocido ? `${String(fila.pct)} %` : SIN_MEDIR,
  ];
}

/**
 * `ini-panel` — el avance del ejercicio en curso.
 *
 * **Pide DOS operaciones**, y las dos estan servidas: el panel de recaudacion y la ultima corrida
 * del padron, de donde sale «Observados sin emision» —el unico de los seis campos que el panel de
 * recaudacion no toca—. Van en un `Promise.all` **sin capturar el fallo de ninguna**: si la
 * corrida falla, la pantalla dice que fallo. Poner ahi «no publicado» seria mentir sobre la causa
 * —el dato SI se publica, lo que paso es que no se pudo pedir— y es justo la confusion que
 * `useDatosDeLaHoja` separa en cuatro estados.
 *
 * El desplegable de ejercicio se rellena con el `ejercicio` de la respuesta y no se deja en su
 * primera opcion: es el ejercicio del que son las cifras que estan debajo, y afirmarlo con la
 * opcion que toco por omision seria afirmarlo sin saberlo. Lo tecleado gana sobre esto, como en
 * cualquier campo.
 */
const INI_PANEL: Conector = {
  clave: ['ini-panel', 'recaudacion', 'ultima-corrida'],
  pedir: ({ senal }) =>
    Promise.all([
      pedirUno<IndicadorDeRecaudacion>(RUTAS.recaudacion, senal),
      pedirUno<CorridaDelPredial>(RUTAS.ultimaCorrida, senal),
    ]),
  repartir: ([recaudacion, corrida]: readonly [
    IndicadorDeRecaudacion,
    CorridaDelPredial,
  ]): Reparto => {
    const valores = new Map<Coordenada, string>();
    const noPublicados = new Map<Coordenada, string>();
    const poner = (coord: Coordenada, valor: string | undefined) => {
      if (valor === undefined) noPublicados.set(coord, NO_PUBLICADO);
      else valores.set(coord, valor);
    };

    poner(coordenada(0, 0), String(recaudacion.ejercicio));
    poner(coordenada(0, 1), formatearImporte(recaudacion.cargado.importe));
    // «Recaudado <ejercicio>» y no «Recaudado hoy en caja», que tambien empieza por «Recaudado»:
    // el ejercicio de la propia respuesta es lo que los distingue sin lugar a duda.
    const recaudado = kpiLlamado(recaudacion, `Recaudado ${String(recaudacion.ejercicio)}`);
    poner(
      coordenada(0, 2),
      recaudado === undefined || recaudado.importe === null
        ? recaudado?.value
        : formatearImporte(recaudado.importe.importe),
    );
    // El avance es un porcentaje y por eso su `importe` viene nulo: lo que se dibuja es el texto
    // que redacto el servidor, que con la base en cero dice «—» en vez de un 0 % inventado.
    poner(coordenada(0, 3), kpiLlamado(recaudacion, 'Avance de cobranza')?.value);
    // «Contribuyentes activos»: ver el javadoc de arriba. No lo publica ninguna de las dos.
    poner(coordenada(0, 4), undefined);
    poner(coordenada(0, 5), String(corrida.observados));

    return { valores, filas: new Map(), noPublicados };
  },
};

/**
 * `ini-flujo` — el cuadre por tributo.
 *
 * **Cinco de cinco columnas salen de la respuesta**, y ninguna se calcula: emitido, recaudado y
 * saldo son tres `ImporteConFecha` distintos que la fila publica —`cargado`, `importe` y
 * `pendiente`— y el avance es el `pct` que ya viene medido.
 *
 * El bloque no tiene ni un campo de solo lectura: sus cuatro son el desplegable de ejercicio, dos
 * fechas y el desplegable de tributo. De ellos solo se rellena el ejercicio, por lo mismo que en
 * `ini-panel`. **El periodo y el tributo no filtran todavia**: la operacion admite `?ejercicio` y
 * nada mas (`parametros-de-la-api.json`), asi que se piden todos los tributos del ejercicio que la
 * respuesta diga.
 */
const INI_FLUJO: Conector = {
  clave: ['ini-flujo', 'recaudacion'],
  pedir: ({ senal }) => pedirUno<IndicadorDeRecaudacion>(RUTAS.recaudacion, senal),
  repartir: (recaudacion: IndicadorDeRecaudacion): Reparto => {
    const porTributo = panelPorTributo(recaudacion);
    return {
      valores: new Map([[coordenada(0, 0), String(recaudacion.ejercicio)]]),
      // Sin el bloque por tributo no hay filas que poner, y la tabla dice su ausencia. Una tabla
      // vacia afirmaria que no hay ni un tributo con movimiento.
      filas:
        porTributo === undefined ? new Map() : new Map([[0, porTributo.rows.map(cuadreDelTributo)]]),
      noPublicados: new Map(),
    };
  },
};

/**
 * `ini-parado` — los frentes abiertos.
 *
 * **Cinco de cinco columnas, columna a columna**, y es la hoja mas limpia del repositorio: modulo,
 * que esta parado, cuantos, cuanto suma y por que cuesta dinero tenerlo asi.
 *
 * `cuantos` se escribe con `String()` y sin separador de miles, como hace `panel` con los
 * registros de sus etapas: un segundo formateador de recuentos seria una segunda verdad sobre como
 * se escribe un numero en una tabla, y el que hay —`formatearImporte`— es de importes.
 *
 * <b>Lo que hay que mirar al revisar esto</b>: la quinta columna es la de la insignia
 * (`columnaDeInsignia: 4` en la definicion, que viene del artboard) y lo que se le da es
 * `porQueCuestaDinero`, que es una **frase** y no un estado —«sin emitir no se pueden notificar ni
 * cobrar, y prescriben»—. Es el unico campo del modulo que la operacion publica con una forma
 * distinta de la que el artboard esperaba: el artboard dibuja un estado («Vencida», «Por vencer»)
 * y el backend no publica ninguno.
 *
 * **Hasta #175 eso salia VERDE**: `tono.ts` deducia el color de lo que dice la celda y, al no
 * reconocer ninguna de las cuatro frases, caia en `ok` — o sea que la interfaz pintaba con la
 * insignia de «conforme» trabajo que esta parado y cuesta dinero. Desde #175 lo que ninguna regla
 * reconoce sale con el tono de «no se», asi que la pantalla ya no miente; **pero su insignia
 * tampoco dice nada**.
 *
 * **Y ya no va a decirlo, porque el backend NO puede publicar el estado**
 * ([#183](https://github.com/hneyra/rentas/issues/183), cerrado sin implementar el 2026-09-17).
 * Se midio antes de escribir codigo, y la medida esta entera en el javadoc de
 * `FrenteDeTrabajo.java`. En corto, dos cosas y las dos de fondo:
 *
 *   · **no hay con que juzgar** — los cuatro puertos devuelven un recuento (uno de ellos con su
 *     suma) y ninguno publica la antiguedad de lo que esta parado; devolver la lista para poder
 *     medirla es lo que prohibe el AC 4 de #56, porque esta es la pantalla que todo el mundo abre
 *     al entrar;
 *   · **y no hay plazo** — «vencida» es «vencida respecto de que», y de las nueve filas `PLAZO`
 *     que el corpus publica ninguna es el plazo que la administracion tiene para desatascar
 *     ninguno de los cuatro frentes: las tres de prescripcion se eligen por si el deudor presento
 *     declaracion, la de coactiva corre **desde** el REC-1 que a estos expedientes les falta, las
 *     tres de valores corren **desde** la notificacion que a estos valores les falta, y para
 *     catastro no hay ninguna. Un umbral inventado aqui declararia vencido trabajo que todavia se
 *     puede hacer.
 *
 * Asi que la columna **sobra**, y eso se arregla en el artboard y no en este archivo: mientras
 * tanto se le sigue pasando `porQueCuestaDinero` y sale con el tono de «no se», que es lo unico
 * que se puede decir sin inventar. Deducir el estado de la frase aqui seria exactamente lo que
 * prohibe la regla de `conectores.ts`.
 */
const INI_PARADO: Conector = {
  clave: ['ini-parado', 'trabajo-parado'],
  pedir: ({ senal }) => pedirUno<TrabajoParado>(RUTAS.trabajoParado, senal),
  repartir: (parado: TrabajoParado): Reparto => ({
    valores: new Map(),
    filas: new Map([
      [
        0,
        parado.frentes.map((frente) => [
          frente.modulo,
          frente.queEstaParado,
          String(frente.cuantos),
          enColumnaDeSoles(frente.importe),
          frente.porQueCuestaDinero,
        ]),
      ],
    ]),
    noPublicados: new Map(),
  }),
};

export { INI_FLUJO, INI_PANEL, INI_PARADO, SIN_CIFRAR, SIN_MEDIR };

/**
 * Los tres de Inicio, en la forma que el registro monta.
 *
 * Un mapa por modulo y no tres constantes sueltas: es como lo hacen Licencias (#168) y Coactiva
 * (#170), y es lo que deja el registro con UNA linea por modulo. Con tres constantes, cada modulo
 * que se conecta anade tres lineas al unico archivo que todas las ramas tocan.
 */
export const CONECTORES_DE_INICIO = {
  'ini-panel': INI_PANEL,
  'ini-flujo': INI_FLUJO,
  'ini-parado': INI_PARADO,
};

import { coordenada } from '@kamayuk/ui';

import { formatearFecha, formatearImporte } from '../../dominio/formato.ts';
import type {
  ConstanciaDeNoAdeudo,
  DeudaConBeneficio,
  DeudaPorConcepto,
  FichaUnificada,
} from '../lecturas.ts';
import { RUTAS, pedirUno } from '../lecturas.ts';
import type { Conector, Reparto } from '../conectores.ts';
import { NO_PUBLICADO } from '../conectores.ts';

/**
 * **Las dos hojas de Consultas que se pintan de verdad** (#169).
 *
 * <h2>Por que este archivo esta aparte de `conectores.ts`</h2>
 *
 * Porque el registro es de todos los modulos y un conector es de uno: con cuatro modulos
 * conectandose a la vez —Inicio, Licencias, Consultas y Coactiva—, cuatro ramas tocando el mismo
 * archivo chocan en el mismo sitio por construccion. `conectores.ts` se queda con la **regla** y
 * con el registro; aqui esta lo de Consultas, y en `CONECTORES` hay una linea por hoja.
 *
 * <h2>La regla de `conectores.ts` manda aqui tambien, y aqui muerde de verdad</h2>
 *
 * **No se calcula un agregado que la operacion no publica.** En `con-panel` la tentacion tiene
 * nombre y numero: el campo «Interes y reajuste» del artboard. La operacion publica `interes` y
 * `reajuste` **por separado** y no publica su suma; `sumarImportes()` existe en este arbol y
 * sumaria los dos exacto, al centimo. Y aun asi no se suma, por dos motivos que no son de estilo:
 *
 *   · El servidor dice explicitamente que esa aritmetica es suya —«las cinco llegan sumadas y
 *     `estadoDeLaConsulta` redactado (RNF-083): la interfaz no suma ni compone texto con cifras
 *     dentro. Si lo hiciera, el dia que el total y el desglose discreparan nadie sabria cual de
 *     los dos mirar»—. Una sexta cifra sumada aqui es exactamente esa discrepancia.
 *   · Y un hueco que dice «no publicado» **nombra lo que falta publicar**: un
 *     `resumenDeSaldos.interesYReajuste`, o el campo partido en dos. Una cifra sumada aqui no lo
 *     pide nunca, porque parece que ya esta.
 *
 * <h2>Las dos piden por un contribuyente, y el contribuyente sale de la RUTA</h2>
 *
 * Las tres operaciones son de una persona concreta —sin su codigo contestan 422— y esta interfaz
 * todavia no tiene la pantalla que la elige. Lo que NO se hace es elegirla aqui: pedir «el primero
 * del padron» pintaria la cuenta corriente de una persona de verdad a quien nadie pregunto. Lo que
 * se hace es lo que `@kamayuk/shell` ya sabe hacer desde su #67: el codigo va en la direccion
 * —`#/con-panel/00000025673`— y sin el la pantalla lo dice en vez de pedir (ver
 * `useDatosDeLaHoja.ts`).
 */

/** Los importes llegan como texto decimal; a la pantalla van como el artboard los escribe. */
const importe = (valor: { readonly importe: string }): string => formatearImporte(valor.importe);

/**
 * `con-panel` — la cuenta corriente del contribuyente, de DOS operaciones.
 *
 * <h2>De donde sale cada uno de los ocho campos</h2>
 *
 * <table>
 *   <tr><td>`0|0` Contribuyente</td><td>`contribuyente.nombre` de la ficha unificada. Es un campo
 *     que se escribe, y llega con el nombre de quien nombra la ruta: la pantalla dice **de quien**
 *     son las cifras que ensena debajo, que es lo primero que hay que poder leer</td></tr>
 *   <tr><td>`0|1` Documento</td><td>`contribuyente.documento`</td></tr>
 *   <tr><td>`0|2` Fecha de calculo</td><td>`aLaFecha`, la fecha de corte con la que se contesto
 *     todo lo que depende de hoy (regla 9)</td></tr>
 *   <tr><td>`0|3` Insoluto</td><td>`resumenDeSaldos.insoluto`</td></tr>
 *   <tr><td>`0|4` Interes y reajuste</td><td><b>NO PUBLICADO</b>. Ver el javadoc de arriba: la
 *     operacion publica `interes` y `reajuste` sueltos y no su suma, y la suma no se hace
 *     aqui</td></tr>
 *   <tr><td>`0|5` Gastos y costas</td><td>`resumenDeSaldos.gasto`</td></tr>
 *   <tr><td>`0|6` Total</td><td>`resumenDeSaldos.total`</td></tr>
 *   <tr><td>`0|7` Beneficio vigente</td><td>`simulacion.campania` de la simulacion de acogimiento
 *     **cuando la hay**, y «no publicado» cuando no. Ver abajo</td></tr>
 * </table>
 *
 * <h2>Por que «Beneficio vigente» depende de la respuesta, y no es una excepcion a la regla</h2>
 *
 * `GET /consultas/deudas-con-beneficio` publica `simulacion` **nula** mientras no se elija campana
 * con `?benefAplicable=`, y esta pantalla no tiene con que elegirla. Asi que el campo dice «no
 * publicado» casi siempre — y lo dice **porque la operacion contesto eso**, no porque se haya
 * decidido aqui: el dia que la peticion lleve campana, el mismo conector escribe su nombre sin
 * tocar una linea. Las dos ramas estan probadas.
 *
 * Y lo que este campo **no** hace es rellenarse con `campaniasAplicables`: son las campanas que la
 * municipalidad publica —una amnistia, un descuento de ordenanza—, y el ejemplo del artboard
 * («Pensionista — 50 UIT») es una <b>deduccion personal</b>, que publica `GET /rentas/beneficios` y
 * ninguna de estas dos operaciones. Pintar una en el hueco de la otra daria un valor plausible y
 * falso, que es la peor clase de dato en una pantalla de deuda.
 */
export const CON_PANEL: Conector = {
  clave: ['con-panel', 'unificada'],
  exigeSujeto: true,
  pedir: async ({ senal, sujeto }) => {
    // `Promise.all` y no dos lecturas con estado propio: `Reparto` reparte UNA respuesta, asi que
    // si una de las dos falla la pantalla dice que fallo entera en vez de pintar la mitad. Pintar
    // la mitad se puede —`DatosDeLaPantalla.lecturas` de #44 es justo eso— y pide un `Reparto` que
    // hoy no existe. Queda dicho aqui, que es donde se notaria.
    const codigo = sujeto ?? '';
    return Promise.all([
      pedirUno<FichaUnificada>(RUTAS.fichaUnificadaDe(codigo), senal),
      pedirUno<DeudaConBeneficio>(RUTAS.deudasConBeneficioDe(codigo), senal),
    ]);
  },
  repartir: ([ficha, beneficio]: readonly [FichaUnificada, DeudaConBeneficio]): Reparto => {
    const saldos = ficha.resumenDeSaldos;
    const campania = beneficio.simulacion?.campania;
    return {
      valores: new Map([
        [coordenada(0, 0), ficha.contribuyente.nombre],
        [coordenada(0, 1), ficha.contribuyente.documento],
        [coordenada(0, 2), formatearFecha(ficha.aLaFecha)],
        [coordenada(0, 3), importe(saldos.insoluto)],
        [coordenada(0, 5), importe(saldos.gasto)],
        [coordenada(0, 6), importe(saldos.total)],
        ...(campania === undefined ? [] : [[coordenada(0, 7), campania] as const]),
      ]),
      filas: new Map(),
      noPublicados: new Map([
        [coordenada(0, 4), NO_PUBLICADO],
        ...(campania === undefined ? [[coordenada(0, 7), NO_PUBLICADO] as const] : []),
      ]),
    };
  },
};

/** Las cuotas de una obligacion, tal como el artboard las escribe: «1» o «1 a 4». */
function cuotasDe(obligacion: DeudaPorConcepto): string {
  // No es una cuenta: son los DOS extremos que la operacion publica, escritos juntos. Restarlos
  // para decir «4 cuotas» seria calcular lo que nadie publico — y ademas saldria mal en cuanto un
  // periodo no empiece en 1.
  return obligacion.periodoDesde === obligacion.periodoHasta
    ? String(obligacion.periodoDesde)
    : `${String(obligacion.periodoDesde)} a ${String(obligacion.periodoHasta)}`;
}

/**
 * `con-doc` — la constancia de no adeudo.
 *
 * <h2>De donde sale cada cosa</h2>
 *
 * <table>
 *   <tr><td>`0|0` Contribuyente</td><td>`codigoContribuyente`, que es lo que se teclea y lo que
 *     sale impreso</td></tr>
 *   <tr><td>`0|5` Resultado</td><td>`seNiega` **con `fechaDeCorte` dentro**. Ver abajo</td></tr>
 *   <tr><td>tabla `0`</td><td>`obligaciones[]` entera: ano, concepto, cuotas, total y
 *     situacion</td></tr>
 * </table>
 *
 * <h2>Lo que la operacion NO publica, campo por campo</h2>
 *
 *   · **`0|4` Nº de expediente** — no lo publica **nadie**: no aparece en ninguna de las 162
 *     operaciones del contrato. Es un campo que se escribe, asi que no deja hueco; queda dicho
 *     aqui para que no haya que volver a buscarlo.
 *   · **`0|2` Identificador del objeto** y **`0|1` Objeto de la constancia** — son filtros de la
 *     peticion, no datos de la respuesta. El contrato de esta operacion declara `codContribuyente`
 *     y `fecha`, y **ningun parametro de objeto**: acotar la constancia a un predio o a un
 *     vehiculo no se puede pedir hoy. Se dibujan porque el artboard los dibuja, y no se mandan.
 *   · **`0|6` Incluye deuda en coactiva** — tampoco es un parametro de la operacion. Y no hace
 *     falta que lo sea: `ConsultarDeuda#constanciaDeNoAdeudo` mira **todas** las obligaciones «en
 *     cualquier fase», o sea que lo coactivo ya esta contado. Marcar o desmarcar la casilla no
 *     cambia lo que se pide, y eso es lo que hay que arreglar en el backend antes que aqui.
 *
 * <h2>Por que el «Resultado» lleva la fecha dentro</h2>
 *
 * Porque `seNiega` es un booleano y la pantalla ensena una frase, y porque la frase es una
 * afirmacion **a un dia**: la deuda de la tabla es la de `fechaDeCorte` y manana es otra (regla 9,
 * RNF-075). Sin la fecha, esta pantalla seria la unica de las dos conectadas que ensena importes
 * sin decir a que dia estan — `con-panel` lo dice en «Fecha de calculo» y aqui no hay campo de
 * fecha ninguno.
 *
 * Las palabras son las del propio artboard («Con deuda: saldría constancia de deuda»). Y llevan un
 * limite que conviene saber: **el valor de un campo es DATO y no pasa por `t()`**, asi que estas
 * dos frases se quedan en castellano cuando la sesion este en otro idioma. Lo que lo cerraria de
 * verdad es que el servidor publique la frase, como ya hace `estadoDeLaConsulta` de la ficha
 * unificada (RNF-083); mientras no la publique, la alternativa era ensenar «true».
 */
export const CON_DOC: Conector = {
  clave: ['con-doc', 'constancia-de-no-adeudo'],
  exigeSujeto: true,
  pedir: ({ senal, sujeto }) =>
    pedirUno<ConstanciaDeNoAdeudo>(RUTAS.constanciaDeNoAdeudoDe(sujeto ?? ''), senal),
  repartir: (constancia: ConstanciaDeNoAdeudo): Reparto => {
    const alDia = formatearFecha(constancia.fechaDeCorte);
    return {
      valores: new Map([
        [coordenada(0, 0), constancia.codigoContribuyente],
        [
          coordenada(0, 5),
          constancia.seNiega
            ? `Con deuda al ${alDia}: saldría constancia de deuda`
            : `Sin deuda al ${alDia}: procede la constancia de no adeudo`,
        ],
      ]),
      filas: new Map([
        [
          0,
          constancia.obligaciones.map((obligacion) => [
            String(obligacion.ejercicio),
            obligacion.tributo,
            cuotasDe(obligacion),
            importe(obligacion.deuda.total),
            obligacion.fase,
          ]),
        ],
      ]),
      noPublicados: new Map(),
    };
  },
};

/**
 * Los dos de Consultas, en la forma que el registro monta.
 *
 * Un mapa por modulo, como Licencias (#168), Coactiva (#170) e Inicio (#167): asi el registro
 * tiene UNA linea por modulo y una conexion nueva no toca el unico archivo que todas las ramas
 * comparten.
 */
export const CONECTORES_DE_CONSULTAS = {
  'con-panel': CON_PANEL,
  'con-doc': CON_DOC,
};

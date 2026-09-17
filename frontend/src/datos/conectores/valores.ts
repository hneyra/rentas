import { coordenada } from '@kamayuk/ui';

import type { Conector, Reparto } from '../conectores.ts';
import { formatearFecha } from '../../dominio/formato.ts';
import type { Paginado, PrescripcionDeclarada } from '../lecturas.ts';
import { RUTAS, pedirPagina } from '../lecturas.ts';

/**
 * **Lo que `val-tip` saca de la bitacora de prescripciones** (#230).
 *
 * Archivo propio y no un renglon en `conectores/coactiva.ts`, por el criterio de siempre: el
 * archivo es del **modulo de la hoja**, y `val-tip` es de Valores aunque su operacion viva bajo
 * `/coactiva/`. `coa-cost` lee la misma ruta con otro proposito —la declaracion que acompana a UNA
 * liquidacion— y se queda donde esta.
 *
 * <h2>Lo que este issue decidio, y por que no fue «publicar el agregado»</h2>
 *
 * El artboard dibujaba un **reloj agregado por ejercicio** —«Ejercicio · Valores · Importe S/ ·
 * Prescribe el · Situacion»— sobre una operacion que publica **una fila por solicitud**. Las dos
 * mitades del hueco se midieron por separado, y no son la misma:
 *
 * <ul>
 *   <li><b>«Valores» e «Importe S/» no los publica nadie, y el backend se niega POR ESCRITO</b>:
 *       «la prescripcion no extingue un importe: deja sin accion su cobro (art. 43 del TUO del
 *       Codigo Tributario) … publicar aqui un importe obligaria ademas a decir a que fecha (regla
 *       9), y la fecha que tendria sentido —cuanto se dejo de poder cobrar— no es un dato de esta
 *       fila sino del libro» (`PrescripcionEnLista`). O sea que no es un campo que falte: es una
 *       cifra que este contexto dice no poder afirmar. Salen del artboard y de la definicion **a la
 *       vez**, como #218.</li>
 *   <li><b>«Prescribe el» SI se podia publicar, y no estaba bloqueada por la regla 5.</b> Es la
 *       diferencia con #183, que se cerro sin implementar: alli el corpus no publicaba el plazo, y
 *       aqui **si lo publica** —`PLAZO:PRESCRIPCION-DECLARACION_PRESENTADA`, `…-SIN_DECLARACION`,
 *       `…-AGENTE_RETENCION` y los dos `PRESCRIPCION_INICIO-*` estan en
 *       `normativa/docs/10-negocio/valores-normativos/publicacion/parametros-2026.csv`, y
 *       `PlazosParametrizados` ya los lee—. La fecha **ya estaba calculada y guardada** por
 *       `V28.prescripcion_ejercicio` desde #39; lo unico que hacia la relacion era tirarla. #230 la
 *       publica en `ejercicios[].prescribeEl` y aqui se escribe tal cual: <b>viaja como dato</b>, no
 *       se resta en la pantalla.</li>
 * </ul>
 *
 * <p>Lo que <b>no</b> se podia era dejar la tabla agregada por ejercicio a secas. Un ejercicio del
 * padron no tiene UNA fecha de prescripcion: el plazo del art. 43 lo elige la <b>causal</b> —cuatro
 * anios si el deudor declaro, seis si no, diez para el agente de retencion— que es de cada deudor y
 * no del ejercicio; y las interrupciones del art. 45, que reinician el computo, solo constan
 * alegadas dentro de una solicitud (`prescripcion_hecho` cuelga de `prescripcion_id`, y no hay
 * ninguna tabla que las registre para el padron). Una sola fecha por ejercicio seria una cifra
 * exacta y equivocada para casi todos.
 *
 * <p>Por eso la fila pasa a ser <b>el ejercicio de una declaracion</b>, y entra la columna
 * «Contribuyente»: una fecha de prescripcion sin decir de quien es no dice nada.
 *
 * <h2>Sin mando de pagina, y el motivo NO es que falte (#228)</h2>
 *
 * La operacion pagina **declaraciones** y esta tabla dibuja **ejercicios**: veinte declaraciones de
 * un rango de cuatro anios son ochenta filas. Publicar `totalElementos` —48— junto a las filas
 * recibidas haria que el encabezado dijera «80 de 48», y declarar `paginacion` pondria un mando que
 * mueve una ventana que no es la de la tabla. Las dos cosas serian cifras plausibles y falsas, que
 * es lo que la regla de `datos/conectores.ts` existe para impedir.
 *
 * <p>Lo que si se escribe es el campo <b>«Declaraciones»</b>, que es `totalElementos` dicho por su
 * nombre: cuantas declaraciones tiene la bitacora entera, contadas por el servidor. Es el mismo
 * camino que #172 abrio, con el rotulo puesto sobre lo que de verdad se cuenta.
 *
 * <h2>Y el orden no se ofrece, tambien medido</h2>
 *
 * `GET /coactiva/prescripcion` admite ordenar por `fechaPresentacion` —su orden por omision—,
 * `tributo`, `resultado`, `ejercicioDesde` y `ejercicioHasta`. **Ninguno es una columna de esta
 * tabla**, y ofrecer ordenar por algo que no se ve deja la barra anunciando un orden que quien mira
 * no puede comprobar — es la regla que `fis-prog` dejo escrita con `sector`.
 */
const VAL_TIP: Conector = {
  clave: ['val-tip', 'reloj-de-prescripcion'],
  pedir: ({ senal }) => pedirPagina<PrescripcionDeclarada>(RUTAS.prescripciones, senal),
  repartir: (bitacora: Paginado<PrescripcionDeclarada>): Reparto => ({
    valores: new Map([
      // «Declaraciones»: el total que el SERVIDOR conto sobre la bitacora entera, no las filas que
      // llegaron. Ver el javadoc: el rotulo dice «declaraciones» porque eso es lo que cuenta.
      [coordenada(0, 3), String(bitacora.totalElementos)],
    ]),
    // Vacio: esta tabla lleva `clave`, asi que sus filas van por `tablas`.
    filas: new Map(),
    tablas: new Map([
      [
        'reloj-de-prescripcion',
        {
          filas: bitacora.contenido.flatMap((declaracion) =>
            declaracion.ejercicios.map((reloj) => ({
              // Dos declaraciones distintas pueden cubrir el mismo ejercicio del mismo
              // contribuyente —una de oficio y otra a pedido—, asi que la clave lleva las dos.
              clave: `${String(declaracion.id)}|${String(reloj.ejercicio)}`,
              celdas: [
                // El padron puede no resolver el identificador, y entonces la fila sale igual: es
                // justo la que hay que revisar. `codContribuyente` no se escribe al lado porque la
                // columna pregunta por el nombre y no por el codigo.
                declaracion.contribuyente ?? declaracion.codContribuyente ?? '—',
                String(reloj.ejercicio),
                // La fecha que el backend publica, formateada y no calculada.
                formatearFecha(reloj.prescribeEl),
                // Dos valores, y son los dos que el dato admite: `prescrita` es booleano. «Por
                // prescribir» —que el desplegable «Estado» ofrecia hasta #244— exigiria un umbral
                // que el corpus no publica, y no se inventa (regla 5). Ese mando pregunta ahora
                // por el resultado de la solicitud, que es lo que `?resultado=` admite.
                reloj.prescrita ? 'Prescrito' : 'Vigente',
              ],
            })),
          ),
          // Sin `totalElementos` a proposito: ver el javadoc. Cuenta las declaraciones y las filas
          // son ejercicios, asi que el interprete cuenta las que hay y no afirma ningun total.
        },
      ],
    ]),
    noPublicados: new Map(),
  }),
};

/** Las hojas de **Valores** que piden de verdad. Hoy una. */
export const CONECTORES_DE_VALORES = {
  'val-tip': VAL_TIP,
} as const;

export { VAL_TIP };

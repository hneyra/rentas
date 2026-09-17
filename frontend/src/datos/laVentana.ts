import type { DatoConNombre, DefinicionDeTabla, OrdenDeLaTabla, PaginacionDeLaTabla } from '@kamayuk/ui';

import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import type { ParametroDeLaHoja } from './conectores.ts';
import { pantallaDe } from '../pantallas/definiciones/index.ts';
import { EN_LA_RUTA, hayMasDe, paginasDe } from '../pantallas/tablas.ts';
import type { Paginado } from './lecturas.ts';

/**
 * **La ventana que se pide: de la DEFINICION lo que es fijo, de la RUTA lo que se eligio** (#186,
 * #187).
 *
 * <h2>El tamano vive en UN sitio, y ese sitio es la definicion</h2>
 *
 * Hasta este issue el tamano estaba escrito a mano en `RUTAS` —`'/licencias/ciiu?tamano=20'`— y la
 * tabla no lo sabia. Con la paginacion puesta pasa a estar en los dos: la definicion declara
 * `paginacion.tamano` porque el interprete lo necesita para dibujar los mandos, y el conector lo
 * necesita para pedir. **Un tamano en dos sitios es un tamano que diverge** (#186, AC3): el dia que
 * alguien suba uno, los mandos contarian paginas de cien sobre respuestas de veinte y «Siguiente»
 * llevaria a una pagina que no existe. Asi que el conector lo LEE de la definicion y no lo escribe.
 *
 * <h2>Lo que llega de la ruta se valida aqui, y no porque el interprete se equivoque</h2>
 *
 * El interprete solo escribe paginas enteras y campos de su lista blanca. Pero **la ruta la escribe
 * cualquiera**: `#/seg-aud?ordenarPor=;DROP` es una direccion que se puede teclear, y reenviarla
 * tal cual seria mandar al backend un `ordenarPor` que su lista blanca rechaza con **422
 * ORDEN_NO_ADMITIDO**. La pantalla ensenaria una averia por algo que se escribio en la barra de
 * direcciones.
 *
 * Asi que un campo que la definicion no ofrece **no viaja**, y entonces el backend ordena por lo
 * suyo por omision — que es exactamente lo que el interprete anuncia, ver abajo.
 *
 * <h2>Por que NO se manda `ordenarPor` cuando la ruta no trae uno valido</h2>
 *
 * Porque el interprete, sin campo en la ruta, dibuja **el primero de `orden.campos`**
 * (`campoOrdenado`, `MandosDeLaTabla.tsx`), y el backend, sin `ordenarPor`, ordena por su
 * `ORDEN_POR_OMISION`. Que los dos coincidan es lo que hace que la barra no mienta sobre el orden
 * de las filas, y por eso **`campos[0]` es el orden por omision de la operacion** —medido controlador
 * a controlador, y escrito en el javadoc de cada conector—. Lo vigila
 * `verificaciones/el-orden-que-se-ofrece-lo-admite-el-backend.test.ts`.
 *
 * La alternativa era copiar aqui la regla del interprete —«el de la ruta si es admitido, o el
 * primero»— y mandar siempre un `ordenarPor`. Es una segunda copia de una regla de la libreria, y
 * el dia que la libreria la cambie esta se queda vieja **en verde**.
 */

/**
 * **Los cuatro sitios del dialecto de paginacion**, declarados para la operacion que los recibe.
 *
 * Se componen y no se copian cuatro veces por conector: son los mismos nombres en toda la API
 * —`GuardiaDeParametros.DIALECTO_DE_LA_PAGINACION` los admite en TODA operacion— y cual de ellos
 * es cual no es una decision de cada hoja. Una hoja que no ordene los declara igual: el mando de
 * orden no existe, pero el marco no puede tirar un parametro que la operacion admite.
 */
export const laVentanaDe = (operacion: string): readonly ParametroDeLaHoja[] =>
  [EN_LA_RUTA.pagina, EN_LA_RUTA.tamano, EN_LA_RUTA.ordenarPor, EN_LA_RUTA.direccion].map(
    (nombre) => ({ nombre, operacion }),
  );

/** La tabla `clave` de una hoja, con la paginacion y el orden que declara. Revienta si no esta. */
function tablaDeclarada(hoja: ClaveDeHoja, clave: string): DefinicionDeTabla {
  const tabla = pantallaDe(hoja)
    .bloques.map((bloque) => bloque.tabla)
    .find((una): una is DefinicionDeTabla => una?.clave === clave);
  if (tabla === undefined) {
    throw new Error(
      `La hoja «${hoja}» no tiene ninguna tabla con la clave «${clave}».\n\n` +
        '  El conector pide la ventana de una tabla que su definicion no declara: o la clave esta\n' +
        '  mal escrita en uno de los dos, o la tabla perdio su `clave` y sus filas ya no llegan.',
    );
  }
  return tabla;
}

/** La paginacion EN SERVIDOR que declara esa tabla. Revienta si no declara ninguna. */
function paginacionDeclarada(hoja: ClaveDeHoja, clave: string): PaginacionDeLaTabla {
  const paginacion = tablaDeclarada(hoja, clave).paginacion;
  if (paginacion === undefined || paginacion.en !== 'servidor') {
    throw new Error(
      `La tabla «${clave}» de «${hoja}» no declara «paginacion: { en: 'servidor' }».\n\n` +
        '  Su conector pide una ventana, o sea que manda `?pagina=` y `?tamano=`, y la tabla no\n' +
        '  dibujaria ningun mando con que moverla: la pantalla ensenaria veinte filas de un padron\n' +
        '  entero sin decir que son una ventana.',
    );
  }
  return paginacion;
}

/**
 * **Lo que se le manda a la operacion por la pagina y el orden de una tabla.**
 *
 * @param hoja la hoja que pide
 * @param clave la `clave` de su tabla paginada
 * @param enLaRuta lo que la hoja lleva en su ruta, ya acotado a lo que su conector declara
 */
export function laVentanaQueSePide(
  hoja: ClaveDeHoja,
  clave: string,
  enLaRuta: Readonly<Record<string, string>>,
): Readonly<Record<string, string>> {
  const paginacion = paginacionDeclarada(hoja, clave);
  const orden: OrdenDeLaTabla | undefined = tablaDeclarada(hoja, clave).orden;

  // El tamano que se OFRECE, si la ruta trae uno de ellos. No cualquiera: el backend topa en 500
  // (`Paginacion.TAMANO_MAXIMO`) y un `?tamano=600` tecleado seria un 422 dicho como averia.
  const pedido = enLaRuta[EN_LA_RUTA.tamano];
  const tamano =
    pedido !== undefined && (paginacion.tamanos ?? []).some((uno) => String(uno) === pedido)
      ? pedido
      : String(paginacion.tamano);

  const pedida = enLaRuta[EN_LA_RUTA.pagina];
  // La pagina 0 no se manda: es la de por omision del backend, y una direccion mas corta es una
  // direccion que se puede leer. Lo que no sea un entero no negativo tampoco: lo escribio alguien.
  const pagina = pedida !== undefined && /^\d+$/.test(pedida) && pedida !== '0' ? pedida : undefined;

  const campo = enLaRuta[EN_LA_RUTA.ordenarPor];
  const admitido =
    orden !== undefined && orden.campos.some((uno) => uno.valor === campo) ? campo : undefined;

  const sentido = enLaRuta[EN_LA_RUTA.direccion];
  // El sentido solo viaja si es uno de los dos que la definicion escribe. Y solo acompana a un
  // campo admitido: sin el, el backend ordena por lo suyo y el sentido de otra columna no dice nada.
  const direccion =
    orden !== undefined &&
    admitido !== undefined &&
    (sentido === orden.ascendente || sentido === orden.descendente)
      ? sentido
      : undefined;

  return {
    [EN_LA_RUTA.tamano]: tamano,
    ...(pagina === undefined ? {} : { [EN_LA_RUTA.pagina]: pagina }),
    ...(admitido === undefined ? {} : { [EN_LA_RUTA.ordenarPor]: admitido }),
    ...(direccion === undefined ? {} : { [EN_LA_RUTA.direccion]: direccion }),
  };
}

/**
 * **Lo que el SERVIDOR dijo de la ventana**, con los nombres que la tabla `clave` busca (#187).
 *
 * Los dos salen del envoltorio de la respuesta y **ninguno se cuenta aqui**:
 *
 *   · `hayMas` lo dice el backend. Contar las filas recibidas —«si llegaron veinte, quiza hay
 *     mas»— diria que no hay pagina siguiente justo cuando el tope se alcanza exacto, que es el
 *     unico caso en que equivocarse cuesta algo.
 *   · `totalPaginas` igual: dividir el total entre el tamano es aritmetica sobre un dato que ya
 *     viene hecho, y el dia que el backend pagine de otra forma la cuenta de aqui seria la falsa.
 *
 * `paginas` viaja como texto porque `DatoConNombre` no admite `number` —una cifra llega ya
 * formateada por el sistema (`interprete/datos.ts`)—, y esta no lleva formato: es un indice.
 */
export function loQueDijoElServidor(
  clave: string,
  pagina: Paginado<unknown>,
): ReadonlyMap<string, DatoConNombre> {
  return new Map<string, DatoConNombre>([
    [hayMasDe(clave), pagina.hayMas],
    [paginasDe(clave), String(pagina.totalPaginas)],
  ]);
}

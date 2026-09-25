import type { Ausencia, DefinicionDePantalla, PiezaDeLaPantalla } from '@kamayuk/ui';

import { ErrorDeLaApi } from '../api/cliente.ts';
import { EXPLICACIONES_DE_LA_VUELTA, MOTIVOS_DE_LA_VUELTA } from '../api/identidad.ts';
import { peldanoDe } from '../api/escalera.ts';
import { ARBOL } from '../pantallas/arbol.ts';
import { bloquesDe } from '../pantallas/bloques.ts';
import { PANTALLAS } from '../pantallas/definiciones/index.ts';
import type { Modulo } from '../pantallas/tipos.ts';
import { CONECTORES } from '../datos/conectores.ts';
import { PALABRAS_DE_HUECO } from '../datos/palabrasDeHueco.ts';
import * as laPantallaQuePide from '../datos/useDatosDeLaHoja.ts';
import * as laPantallaSinConector from '../porQueNoHayDato.ts';
import * as elMarco from './textosDelMarco.ts';

/**
 * **Todas las cadenas traducibles del sistema, sacadas de donde estan** (#103).
 *
 * <h2>Por que esto existe, y por que `i18next-cli` no basta</h2>
 *
 * `i18next-cli` extrae lo que encuentra escrito como una llamada con la frase dentro. Medido:
 * encuentra **12**. Las otras **747 no estan escritas asi y no pueden estarlo**: viven en las
 * definiciones de las 40 pantallas y en el arbol, y el interprete las traduce con
 * `t(campo.etiqueta)` — una variable, que ninguna extraccion estatica puede seguir.
 *
 * **Y ojo con los ejemplos en los comentarios.** Este parrafo decia la llamada con una frase
 * literal dentro, a modo de ejemplo, y el extractor **la cogio como clave de verdad**: `status`
 * salio rojo con «✗ una frase (absent)» sobre un codigo perfecto. No distingue un ejemplo de una
 * llamada, asi que aqui no se escriben ejemplos con la frase dentro.
 *
 * No es un defecto de la herramienta ni de la forma: es la consecuencia de que **las pantallas
 * sean dato**, que es justo lo que hace posible la guarda anti-deriva. Se paga aqui.
 *
 * Asi que el catalogo se DERIVA del dato en vez de extraerse del codigo. La ventaja es que no
 * puede quedarse corto: una pantalla nueva trae sus cadenas sin que nadie se acuerde de nada.
 *
 * <h2>Y desde #133 tambien lo que dice el MARCO</h2>
 *
 * Las treinta y dos palabras de `@kamayuk/shell` y la marca de opcional de `@kamayuk/ui` entran
 * por `textosDelMarco.ts`, y entran **derivadas** por el mismo motivo que las 747: escritas dentro
 * de cada `t()` habria que acordarse de listarlas a mano en el inventario del locale, y un olvido
 * ahi no produce ningun rojo — nadie echa de menos lo que nadie listo.
 *
 * <h2>Lo que NO entra</h2>
 *
 * **Los valores de los campos de solo lectura y las filas de las tablas** — ya no existen aqui
 * (#97), y no se traducirian aunque existieran: un importe no tiene traduccion.
 */

/**
 * Todo lo que las 40 pantallas dicen.
 *
 * **Los bloques, y no todas las piezas** (#288): lo que una pieza del consumidor dice es de ella y
 * entra en el catalogo por donde entra lo del marco —un saco exportado de `textosDelMarco.ts`—,
 * porque una pieza no tiene titulo, nota ni campos que recorrer. Ver `FRASES_DEL_GRAFICO`.
 */
function deLasPantallas(): readonly string[] {
  const salida: string[] = [];
  // Anotado: `PANTALLAS` es un `as const satisfies` de cuarenta formas distintas, y sin la
  // anotacion el compilador intenta unificar cuarenta y se rinde. La forma comun la da el
  // `satisfies`, que es lo que garantiza que la anotacion no miente.
  for (const pantalla of Object.values(PANTALLAS) as readonly DefinicionDePantalla<PiezaDeLaPantalla>[]) {
    salida.push(pantalla.instruccion);
    for (const bloque of bloquesDe(pantalla)) {
      salida.push(bloque.titulo);
      if (bloque.nota !== '') salida.push(bloque.nota);
      for (const campo of bloque.campos) {
        salida.push(campo.etiqueta);
        if ('opciones' in campo) salida.push(...campo.opciones);
        if ('casilla' in campo) salida.push(campo.casilla);
        if ('ayuda' in campo && campo.ayuda !== undefined) salida.push(campo.ayuda);
      }
      const tabla = bloque.tabla;
      if (tabla === undefined) continue;
      salida.push(tabla.titulo, ...tabla.columnas.map((c) => c.rotulo));
      if (tabla.nota !== undefined) salida.push(tabla.nota);
      if (tabla.accion !== undefined) salida.push(tabla.accion);
      // La palabra de una celda sin dato y su motivo son TEXTO de la definicion —el interprete los
      // pasa por `traducir`—, asi que entran en el inventario como el titulo o la nota
      // (`kamayuk-lib`#87, #180). Sin esta linea se irian al DOM en castellano en cualquier idioma,
      // y el locale no lo echaria de menos: lo que nadie lista, nadie lo reclama.
      if (tabla.sinDato !== undefined) {
        salida.push(tabla.sinDato.texto);
        if (tabla.sinDato.nota !== undefined) salida.push(tabla.sinDato.nota);
      }
      // Los rotulos de los campos por los que se ordena son FRASES —el interprete los pasa por
      // `resolverTexto`, o sea por `traducir`— y salen en el desplegable de la barra de la tabla
      // (#186). Lo que NO entra es `campo.valor`: eso viaja al servidor, y cambiar de idioma no
      // puede cambiar lo que se pide.
      if (tabla.orden !== undefined) {
        salida.push(...tabla.orden.campos.map((campo) => campo.rotulo));
      }
    }
  }
  return salida;
}

/** Los rotulos del arbol: diez modulos con su nota, y cuarenta hojas. */
function delArbol(): readonly string[] {
  return (ARBOL as readonly Modulo[]).flatMap((modulo) => [
    modulo.rotulo,
    modulo.nota,
    ...modulo.hojas.map((hoja) => hoja.rotulo),
  ]);
}

/**
 * **Las frases con que el sistema explica que no hay dato, DERIVADAS** (#215, #237, #246).
 *
 * <h2>Aqui habia una lista a mano, y era el agujero de este archivo</h2>
 *
 * Todo lo demas del catalogo se deriva del dato —las 40 pantallas, el arbol, el marco— y el
 * javadoc de arriba dice por que: «un olvido ahi no produce ningun rojo — nadie echa de menos lo
 * que nadie listo». Menos esto, que hasta #246 era **una importacion por constante**: once
 * `Ausencia` nombradas una a una y tres palabras de hueco.
 *
 * Y mordio tres veces. #215 encontro cinco de `useDatosDeLaHoja.ts` sin listar —«en un segundo
 * idioma la mitad conectada de la interfaz salia en castellano»—; #237 anadio tres mas y tuvo que
 * acordarse; y al medir #246 seguian fuera **las cinco frases de `alFallar`** y
 * **`SIN_PARAMETROS_DEL_SORTEO`**, que entro en #196 y nadie inventario. Ninguna de las tres tuvo
 * rojo: la guarda del locale compara el locale contra este catalogo, y el catalogo tampoco las
 * tenia.
 *
 * <h2>De donde salen ahora, y por que de ahi</h2>
 *
 * · **De lo que los dos modulos de frases EXPORTAN**, filtrado por forma: lo que tiene
 *   `enElCampo`, `explicacion` y `tono` es una `Ausencia` y entra. Una frase nueva entra sola con
 *   solo declararla al lado de sus hermanas.
 * · **De los CONECTORES**, campo por campo: `sinSujeto`, `sinDato` y `noEncontrado` son
 *   `Ausencia` y viajan a la pantalla igual. Se recorre el conector entero y no esos tres nombres,
 *   asi que el dia que `Conector` gane un cuarto canal no hay que volver aqui.
 * · **De `PALABRAS_DE_HUECO`**, que ademas es de donde sale el TIPO con que estan declarados
 *   `Reparto.noPublicados` y `Reparto.loQueLaOperacionNoTrae`: una palabra que no este alli **no
 *   compila**, y la que esta, entra en el locale sin que nadie la liste.
 *
 * Lo que la forma no puede ver —una `Ausencia` escrita dentro de una funcion, o en un modulo que
 * esto no recorre— lo vigila `verificaciones/ninguna-ausencia-se-queda-sin-inventariar.test.ts`,
 * que barre las fuentes y sale roja nombrando el archivo. Derivar cubre lo corriente; el centinela
 * cubre que derivar se haya quedado corto.
 *
 * <h2>Lo que NO entra</h2>
 *
 * Las palabras de una **celda** de tabla: son dato de la fila y no pasan por `traducir`.
 */
function esUnaAusencia(valor: unknown): valor is Ausencia {
  if (typeof valor !== 'object' || valor === null) return false;
  const quiza = valor as Partial<Ausencia>;
  return (
    typeof quiza.enElCampo === 'string' &&
    typeof quiza.explicacion === 'string' &&
    typeof quiza.tono === 'string'
  );
}

/** Todas las `Ausencia` que el sistema puede ensenar, sacadas de donde estan declaradas. */
function lasAusenciasDelSistema(): readonly Ausencia[] {
  const salida: Ausencia[] = [];
  for (const modulo of [laPantallaSinConector, laPantallaQuePide]) {
    for (const exportado of Object.values(modulo)) {
      if (esUnaAusencia(exportado)) salida.push(exportado);
    }
  }
  for (const conector of Object.values(CONECTORES)) {
    if (conector === undefined) continue;
    for (const campo of Object.values(conector)) {
      if (esUnaAusencia(campo)) salida.push(campo);
    }
  }
  return salida;
}

/** Lo que se pidio, para construir los fallos. No se lee en ninguna frase: ver el 404 de #283. */
const OPERACION_DE_MUESTRA = 'GET /seguridad/sesion';

/**
 * **Un fallo por peldano de la escalera, con el cuerpo VACIO** (#283).
 *
 * El cuerpo vacio no es un descuido: es lo que hace que `peldanoDe` conteste su **respaldo**, o
 * sea la frase escrita en este arbol, que es la que puede ser clave. Con un `mensaje` dentro
 * contestaria lo que dijo el backend, que es dato y no se traduce.
 *
 * La lista es a mano —ocho fallos para siete peldanos, porque `averia` se llega de dos maneras— y
 * eso es una lista que alguien puede olvidar ampliar. Por eso no es la unica linea de defensa:
 * `verificaciones/ninguna-ausencia-se-queda-sin-inventariar.test.ts` barre `api/escalera.ts` y
 * exige que **toda** frase escrita dentro de un peldano este en este catalogo, asi que un octavo
 * peldano que nadie anada aqui sale rojo nombrando el archivo y la linea. Es el reparto de #246:
 * derivar cubre lo corriente, el centinela cubre que derivar se haya quedado corto.
 */
const LOS_FALLOS_DE_LA_ESCALERA: readonly unknown[] = [
  // Un corte de red no llega como `ErrorDeLaApi`, y tambien tiene que decir algo.
  new TypeError('Failed to fetch'),
  new ErrorDeLaApi(401, OPERACION_DE_MUESTRA),
  new ErrorDeLaApi(403, OPERACION_DE_MUESTRA, { codigo: 'SIN_MUNICIPALIDAD' }),
  new ErrorDeLaApi(403, OPERACION_DE_MUESTRA, { codigo: 'SIN_PRIVILEGIO' }),
  new ErrorDeLaApi(403, OPERACION_DE_MUESTRA),
  new ErrorDeLaApi(404, OPERACION_DE_MUESTRA),
  new ErrorDeLaApi(422, OPERACION_DE_MUESTRA),
  new ErrorDeLaApi(500, OPERACION_DE_MUESTRA),
];

/**
 * **Las frases de los siete peldanos** (#283).
 *
 * `datos/useDatosDeLaHoja.ts` las dibuja: `enElHueco` va en el hueco del campo y los otros tres se
 * arman en la explicacion con `FRASE_DEL_PELDANO`, los tres pasados por `t()`. O sea que son
 * claves, y una clave que nadie lista nadie la echa de menos. `estado` no entra: es un numero.
 */
function deLosPeldanos(): readonly string[] {
  return LOS_FALLOS_DE_LA_ESCALERA.flatMap((fallo) => {
    const peldano = peldanoDe(fallo);
    return [peldano.enElHueco, peldano.titulo, peldano.detalle, peldano.remedio];
  });
}

function deLasAusencias(): readonly string[] {
  return [
    ...lasAusenciasDelSistema().flatMap((a) => [a.enElCampo, a.explicacion]),
    ...Object.values(PALABRAS_DE_HUECO),
  ];
}

/**
 * **Lo que dice el MARCO, derivado de lo que `textosDelMarco.ts` exporta** (#133, #246).
 *
 * Aquel archivo tenia su propia `clavesDelMarco()`, que juntaba los tres sacos **y nombraba a mano
 * las cuatro frases sueltas** —la del conteo, la de la fecha, las dos de quien es—. Es la misma
 * lista a mano de aqui abajo y con el mismo modo de fallo: la quinta frase suelta se escribe, no
 * se lista, y nadie se entera. Derivado del modulo entero no hay quinta que olvidar — una cadena
 * exportada, o un saco de cadenas, es una clave; los ganchos son funciones y no entran.
 */
function delMarco(): readonly string[] {
  const salida: string[] = [];
  for (const exportado of Object.values(elMarco)) {
    if (typeof exportado === 'string') salida.push(exportado);
    else if (typeof exportado === 'object' && exportado !== null) {
      for (const frase of Object.values(exportado)) {
        if (typeof frase === 'string') salida.push(frase);
      }
    }
  }
  return salida;
}

/**
 * **Por que no se pudo terminar la entrada, y su explicacion** (#355, ronda 1).
 *
 * La pantalla de volver a identificarse las dibuja con `t(vuelta.motivo)` y
 * `t(vuelta.explicacion, …)` —una variable, que `i18next-cli` no sigue—, asi que se derivan de las
 * dos tablas de `api/identidad.ts`, que son el UNICO sitio de donde una vuelta fallida puede
 * sacarlas: el tipo de la vuelta no admite otra cadena.
 */
function deLaVuelta(): readonly string[] {
  return [...Object.values(MOTIVOS_DE_LA_VUELTA), ...Object.values(EXPLICACIONES_DE_LA_VUELTA)];
}

/** El catalogo entero, sin repetidos y en orden. */
export function catalogoDeClaves(): readonly string[] {
  const todas = new Set([
    ...deLasPantallas(),
    ...delArbol(),
    ...deLasAusencias(),
    ...deLosPeldanos(),
    ...delMarco(),
    ...deLaVuelta(),
  ]);
  return [...todas].filter((c) => c.trim() !== '').sort((a, b) => a.localeCompare(b, 'es'));
}

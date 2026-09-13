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
 * Sobre las 40 pantallas y las 101 declaraciones de operacion del artboard:
 *
 *   · **33 hojas no declaran ninguna operacion servida.** No hay a quien preguntar.
 *   · **36 de las 101 declaraciones llevan el verbo `BASE`**, que el propio artboard define como
 *     «solo se leyo el `@RequestMapping` de la clase: sus metodos no se han verificado». **Doce
 *     hojas lo tienen TODO en `BASE`** — de ellas no se sabe ni con que verbo se pediria.
 *   · **7 hojas tienen al menos una servida**, y de esas solo **dos** se pintan enteras.
 *   · Y el cuarto caso —servida, pedida y vacia— no lo produce este archivo todavia: llega cuando
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
 */

/** Los verbos con los que se puede pedir algo para dibujarlo. */
const DE_LECTURA = new Set(['GET', 'BASE']);

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
  const todasSonBase = hoja.operaciones.every((o) => o.verbo === 'BASE');
  return todasSonBase ? SOLO_BASE : NADA_SERVIDO;
}

export { NADA_SERVIDO, SOLO_BASE, SERVIDO_Y_SIN_PEDIR };

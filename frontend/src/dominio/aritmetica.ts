import type { Importe } from './valores.ts';

/**
 * La suma exacta de importes, en centimos y **sin coma flotante**.
 *
 * <h2>Por que existe, si la regla dice «sin aritmetica sobre importes»</h2>
 *
 * Porque la regla no dice que nadie sume: dice que **no sume la pantalla**. Su prohibicion de
 * ESLint —`aritmetica-con-importes`— lo escribe con todas las letras: *«El total lo calcula el
 * backend y lo sostiene con su fecha: pidelo, no lo sumes»*. Este archivo tiene exactamente dos
 * consumidores, y ninguno es una pantalla que se invente un total:
 *
 *   1. **`datos/operaciones.ts`**, que es el backend simulado. Ahi la memoria del predial deja
 *      de copiar sus dos totales del artboard y los DERIVA —el insoluto es la suma de los tres
 *      tramos, el total es el insoluto mas el derecho de emision—, que es lo que hace que
 *      cambiar un tramo mueva lo que depende de el. Un total copiado se veria idéntico y
 *      estaria muerto: el artboard cuadra hoy, y seguiria «cuadrando» el dia que dejara de
 *      cuadrar.
 *   2. **`secciones/determinacion.ts`**, que **no suma para mostrar**: comprueba. La pantalla
 *      dibuja el insoluto y el total que la respuesta publica —pidelos, no los sumes— y ademas
 *      verifica que cuadren con las filas que esta ensenando. Si no cuadran, lo dice en vez de
 *      dibujar un total que miente sobre sus propios sumandos.
 *
 * <h2>Centimos enteros, y no decimales de coma flotante</h2>
 *
 * `0.1 + 0.2` no es `0.3` en coma flotante, y el centimo se pierde antes de llegar a la
 * pantalla (regla 1, RNF-055). Aqui el texto decimal se convierte a un entero de centimos
 * —`BigInt`, sin techo de precision— se suma exacto y se vuelve a escribir con dos decimales.
 * `Number` no aparece en ninguna linea de este archivo, y es deliberado.
 *
 * <h2>Lo que NO hace</h2>
 *
 * No multiplica, no divide, no aplica alicuotas y no redondea. Aplicar una alicuota a una
 * porcion gravada es una **regla tributaria** y las reglas tributarias son funciones puras del
 * backend (regla 6): recalcular 2027 en 2037 tiene que dar el mismo centimo, y eso no se
 * garantiza desde un navegador. Sumar dos cifras que el backend ya publico es otra cosa: no
 * decide cuanto se debe, comprueba que lo publicado cuadre consigo mismo.
 */

/** Un importe servido por el backend: opcionalmente negativo, con 0..2 decimales. */
const IMPORTE_SERVIDO = /^-?\d+(\.\d{1,2})?$/;

/** `'587.44'` → `58744n`. Falla ruidosamente con cualquier otra forma. */
function centimosDe(valor: Importe): bigint {
  const limpio = valor.trim();
  if (!IMPORTE_SERVIDO.test(limpio)) {
    throw new Error(
      `Importe con una forma que el backend no sirve: «${valor}». ` +
        'Se espera texto decimal con dos decimales como mucho, sin separador de miles.',
    );
  }
  const negativo = limpio.startsWith('-');
  const sinSigno = negativo ? limpio.slice(1) : limpio;
  const [entera, decimales] = sinSigno.split('.');
  const enCentimos = BigInt(`${entera ?? '0'}${`${decimales ?? ''}00`.slice(0, 2)}`);
  return negativo ? -enCentimos : enCentimos;
}

/** `58744n` → `'587.44'`. */
function comoTextoDecimal(centimos: bigint): Importe {
  const negativo = centimos < 0n;
  const absoluto = (negativo ? -centimos : centimos).toString().padStart(3, '0');
  const entera = absoluto.slice(0, -2);
  const decimales = absoluto.slice(-2);
  return `${negativo ? '-' : ''}${entera}.${decimales}`;
}

/**
 * La suma exacta de los sumandos, con dos decimales.
 *
 * Con la lista vacia da `'0.00'`, que es la suma de nada y no un fallo: una determinacion sin
 * tramos aplicados aporta cero, y reventar ahi obligaria a cada llamador a comprobarlo antes.
 */
export function sumarImportes(sumandos: readonly Importe[]): Importe {
  let acumulado = 0n;
  for (const sumando of sumandos) {
    acumulado += centimosDe(sumando);
  }
  return comoTextoDecimal(acumulado);
}

/**
 * Si dos importes son el mismo hasta el centimo.
 *
 * No es `a === b`: `'587.4'` y `'587.40'` son la misma cifra escrita de dos maneras, y el
 * backend puede publicar cualquiera de las dos. Comparar el texto diria que no cuadran cuando
 * si cuadran, y una comprobacion que da falsos rojos se acaba borrando.
 */
export function mismosCentimos(uno: Importe, otro: Importe): boolean {
  return centimosDe(uno) === centimosDe(otro);
}

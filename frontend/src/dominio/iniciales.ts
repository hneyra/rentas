/**
 * **Las dos letras del circulo de la barra, sacadas del nombre de la sesion** (#356).
 *
 * Hasta #356 eran `'JC'`, escritas a mano junto a «J. Cardenas Vega»: las iniciales de una persona
 * que no existe, encima de las cifras de cualquier municipalidad. Ahora las deriva esta funcion del
 * `nombre` que publica `GET /seguridad/sesion`, y por eso es **pura**: el mismo nombre da siempre
 * las mismas letras, sin reloj, sin idioma de la sesion y sin red.
 *
 * <h2>La regla, y de donde sale</h2>
 *
 * **La primera letra de las dos primeras palabras que no son una particula.** Es la que el propio
 * artboard aplicaba: «J. Cardenas Vega» -> «JC», o sea la inicial y el primer apellido, no el
 * ultimo. Las particulas —«de», «del», «la», «los», «y»…— se saltan porque no nombran a nadie:
 * «Administrador del Sistema» es «AS» y no «AD», y «María de los Ángeles» es «MÁ».
 *
 * · Se toma la primera **letra** de la palabra, no su primer caracter: «J.» da «J», y un nombre que
 *   empieza por comillas o por un parentesis no pone un signo en el circulo.
 * · Las tildes se quedan. Quitarlas seria escribir mal el nombre de alguien; la barra lo dibuja en
 *   mayusculas, y «Ó» es una mayuscula.
 * · Si TODAS las palabras son particulas —un nombre raro, pero posible—, se usan tal cual: dejar el
 *   circulo vacio por una regla de estilo seria peor que una inicial poco elegante.
 * · **Un nombre sin ninguna letra da la cadena vacia**, y no una letra inventada. Quien dibuja el
 *   circulo decide que poner en su lugar; esta funcion no se lo inventa.
 */

/** Las palabras que no nombran a nadie. En minusculas: se comparan asi. */
const PARTICULAS: ReadonlySet<string> = new Set(['de', 'del', 'la', 'las', 'los', 'y', 'e']);

/** La primera letra de una palabra, en mayuscula; o nada, si no tiene ninguna. */
function primeraLetra(palabra: string): string {
  const letra = /\p{L}/u.exec(palabra)?.[0];
  return letra === undefined ? '' : letra.toLocaleUpperCase('es');
}

export function inicialesDe(nombre: string): string {
  const palabras = nombre
    .trim()
    .split(/\s+/)
    .filter((palabra) => primeraLetra(palabra) !== '');
  const conNombre = palabras.filter((palabra) => !PARTICULAS.has(palabra.toLocaleLowerCase('es')));
  const elegidas = conNombre.length > 0 ? conNombre : palabras;
  return elegidas.slice(0, 2).map(primeraLetra).join('');
}

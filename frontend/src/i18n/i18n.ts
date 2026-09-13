import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';

import es from './locales/es.json' with { type: 'json' };

/**
 * **El castellano es la clave** (#103).
 *
 * <h2>La decision, y la tension que resuelve</h2>
 *
 * `verificaciones/pantallas-del-artboard.test.ts` compara las 40 definiciones **campo por campo y
 * literal** contra `diseno/RentasV8.dc.html`. Es la guarda que hace cierto «debe lucir identico».
 *
 * Con claves opacas —`'ini-panel.b0.c5'`— esa comparacion muere: compararia claves contra
 * castellano. Y ensenandola a resolver, sus rojos pasarian de decir
 *
 *     bloque 0 · campo 5: «Observados sin emisión» tipo «r»
 *
 * a decir «ini-panel.b0.c5», que es mucho peor de leer justo cuando mas falta hace.
 *
 * Asi que la clave **es** el castellano. `t('Observados sin emisión')`. La guarda queda intacta, el
 * artboard sigue siendo la fuente de verdad, y **un segundo idioma es un JSON** que mapea
 * castellano → destino. Ese es el andamiaje, y es todo.
 *
 * <h2>El coste conocido de esta forma, y quien lo cubre</h2>
 *
 * Cambiar el castellano **pierde su traduccion**, porque la clave cambia. Eso lo cubre
 * `i18next-cli`, que informa de las claves que el codigo usa y el locale no tiene — y que corre
 * dentro de `yarn verificar`, no como un paso que alguien se acuerda de ejecutar.
 *
 * <h2>Los dos idiomas que hay, y el tercero que no es un idioma</h2>
 *
 * · **`es`** — el de verdad. Su JSON esta **casi vacio a proposito**: sin entrada, i18next devuelve
 *   la clave, que ya es el castellano. Un JSON con 747 entradas identicas a su clave seria 747
 *   sitios donde el castellano podria divergir del artboard sin que la guarda lo viera.
 *
 *   **La excepcion son los PLURALES, y es una excepcion de verdad y no una comodidad**: una clave
 *   sola no puede expresar dos formas. `{{count}} registro` tiene que decir «1 registro» y «2
 *   registros», y eso son dos entradas —`_one` y `_other`— que i18next elige por el numero. Sin
 *   ellas sale «2 registro», que es lo que salio al medirlo. Y no se resuelve con un ternario en
 *   el codigo: hay idiomas con mas de dos formas, y el ternario las deja fuera para siempre.
 * · **`marcado`** — no es un idioma: es el arnes de la guarda de cobertura. Envuelve TODO lo que
 *   traduce entre `⟦` y `⟧`, de modo que lo que llegue al DOM sin marcar es texto que se escapo de
 *   `t()`. Ver `verificaciones/todo-el-texto-se-traduce.test.tsx`.
 *
 * <h2>Por que el marcado es un POST-PROCESADOR y no un «no encontre la clave»</h2>
 *
 * La primera version lo hacia con `parseMissingKeyHandler`, y funciono **hasta que el locale se
 * lleno**: con las 764 entradas puestas, i18next las encuentra por el idioma de reserva y ese
 * gancho **no se llama nunca**. El sintoma fue el peor posible — la guarda paso de 42 en verde a
 * 40 en rojo diciendo que todo se escapaba, cuando lo que se habia roto era el arnes.
 *
 * Un post-procesador corre **sobre lo que ya se tradujo**, venga de donde venga. Es lo unico que
 * mide lo que se queria medir: no «que claves faltan» sino **que texto paso por `t()`**.
 */

/** Lo que envuelve el locale de prueba. No son caracteres que ninguna pantalla use. */
export const ABRE = '⟦';
export const CIERRA = '⟧';

export const IDIOMA_POR_OMISION = 'es';

/**
 * El locale que marca todo, para que la guarda pueda ver lo que NO paso por `t()`.
 *
 * Es un `parseMissingKeyHandler`, no un diccionario: tiene que envolver **cualquier** clave, no
 * las que alguien se haya acordado de listar — y justamente lo que se busca son las que nadie
 * listo.
 */
export const IDIOMA_MARCADO = 'marcado';

/**
 * Envuelve lo traducido cuando el idioma es el de marcado. En cualquier otro, no toca nada.
 *
 * `postProcess` se declara en la configuracion y no en cada llamada: si hubiera que acordarse de
 * pedirlo en cada `t()`, la cadena que alguien olvidara seria justo la que la guarda no veria.
 */
const marcador = {
  type: 'postProcessor' as const,
  name: 'marcar',
  process: (valor: string) =>
    i18next.language === IDIOMA_MARCADO ? `${ABRE}${valor}${CIERRA}` : valor,
};

await i18next
  .use(initReactI18next)
  .use(marcador)
  .init({
  lng: IDIOMA_POR_OMISION,
  fallbackLng: IDIOMA_POR_OMISION,
  // Sin espacios de nombre ni separadores: la clave es una frase en castellano y lleva puntos,
  // dos puntos y comas dentro. Con los separadores puestos, «Nada se escribe hasta que pulse
  // Guardar.» se partiria por el punto y la traduccion no se encontraria nunca.
  keySeparator: false,
  nsSeparator: false,
  resources: { es: { translation: es } },
  interpolation: { escapeValue: false },
  postProcess: ['marcar'],
});

export default i18next;

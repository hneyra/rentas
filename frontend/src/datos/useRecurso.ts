import { useEffect, useState } from 'react';

import { peldanoDe } from '../api/escalera.ts';
import { pedirCalculo, pedirLista, pedirPagina, pedirUno } from './lecturas.ts';
import type { Paginado } from './lecturas.ts';

/**
 * **Los tres nombres empiezan por `use` y no por `usar`, y es la excepcion que confirma la
 * regla del idioma.** «Ingles en lo tecnico»: un hook es una pieza de React, y el prefijo `use`
 * no es una convencion de estilo sino **el contrato que permite comprobar las reglas de los
 * hooks**. Con `usarUno`, `react-hooks/rules-of-hooks` no reconoce la funcion como hook y falla
 * el lint —«React Hook "useState" is called in function "usarPeticion" that is neither a React
 * function component nor a custom React Hook function»—: la regla deja de poder vigilar que no
 * se llamen dentro de una condicion, que es el defecto que existe para impedir.
 */

/**
 * Lo que una pantalla sabe de un dato que pidio: si llego, si sigue en camino, o por que no.
 *
 * **Los tres estados son obligatorios**, y por eso son un tipo y no tres `useState` sueltos en
 * cada seccion. Una pantalla que solo distingue «tengo dato» de «no tengo» ensena el vacio
 * mientras carga —«Ningún contribuyente coincide» antes de que llegue el padron— y ensena el
 * mismo vacio cuando el backend contesta 500. En ventanilla eso es la diferencia entre esperar
 * y llamar por telefono.
 */
export interface Recurso<T> {
  readonly dato: T | null;
  readonly cargando: boolean;
  readonly error: string | null;
  /**
   * Lo que lanzo la peticion, sin interpretar. `null` si no fallo.
   *
   * `error` es una frase para ensenar; esto es el fallo entero, con su `codigo`. Los dos hacen
   * falta y no son el mismo dato: una seccion pinta la frase en un `Aviso`, pero quien tiene
   * que DECIDIR —el casco, que ante un 401 vuelve a la puerta de identidad y ante un 403
   * `SIN_PRIVILEGIO` no, porque volver a entrar con la misma cuenta daria el mismo 403— no
   * puede decidir sobre una frase en castellano sin volver a parsearla.
   */
  readonly fallo: unknown;
}

/**
 * Lo que se le dice al usuario cuando la peticion no salio.
 *
 * Desde I-1 lo redacta `peldanoDe`, que es la misma funcion con la que el casco decide que
 * hacer. Antes esto componia su propia frase —«El sistema no pudo contestar (403): …»— y el
 * casco componia otra, asi que un 403 `SIN_PRIVILEGIO` se explicaba de dos maneras distintas
 * segun donde saltara: en la pantalla como una averia, y en la puerta como lo que es. Una sola
 * fuente para las dos.
 */
function mensajeDe(fallo: unknown): string {
  const peldano = peldanoDe(fallo);
  return `${peldano.detalle} ${peldano.remedio}`;
}

/**
 * El estado inicial: nada, y cargando.
 *
 * `cargando` empieza en `true` cuando hay ruta porque el efecto todavia no ha corrido; con
 * `false` la pantalla parpadearia su estado vacio en el primer fotograma.
 */
function alEmpezar<T>(hayRuta: boolean): Recurso<T> {
  return { dato: null, cargando: hayRuta, error: null, fallo: null };
}

/**
 * Pide `ruta` y devuelve su estado. Con `ruta` nula no pide nada.
 *
 * La ruta nula no es un caso raro: es como se encadenan dos peticiones —la lista de observados
 * necesita el id de la ultima corrida— sin romper la regla de los hooks. El efecto se rehace
 * cuando cambia la ruta y **aborta la anterior**: sin eso, elegir tres contribuyentes seguidos
 * deja tres peticiones vivas y la que pinta la ficha es la que conteste ultima, que no tiene
 * por que ser la del contribuyente elegido.
 */
function usePeticion<T>(ruta: string | null, pedir: (ruta: string, senal: AbortSignal) => Promise<T>) {
  const [estado, fijar] = useState<Recurso<T>>(() => alEmpezar<T>(ruta !== null));

  useEffect(() => {
    if (ruta === null) {
      fijar({ dato: null, cargando: false, error: null, fallo: null });
      return;
    }

    const control = new AbortController();
    let vivo = true;
    fijar({ dato: null, cargando: true, error: null, fallo: null });

    pedir(ruta, control.signal).then(
      (dato) => {
        if (vivo) {
          fijar({ dato, cargando: false, error: null, fallo: null });
        }
      },
      (fallo: unknown) => {
        // Abortar no es fallar: es que la pantalla ya no quiere esa respuesta.
        if (vivo && !control.signal.aborted) {
          fijar({ dato: null, cargando: false, error: mensajeDe(fallo), fallo });
        }
      },
    );

    return () => {
      vivo = false;
      control.abort();
    };
    // `pedir` es una de las dos funciones de modulo de abajo y no cambia nunca; meterla en las
    // dependencias obligaria a envolverla en `useCallback` en cada llamada sin cambiar nada.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ruta]);

  return estado;
}

/** Pide una operacion que contesta un objeto. */
export function useUno<T>(ruta: string | null): Recurso<T> {
  return usePeticion<T>(ruta, (donde, senal) => pedirUno<T>(donde, senal));
}

/** Pide una operacion paginada y devuelve su contenido. */
export function useLista<T>(ruta: string | null): Recurso<readonly T[]> {
  return usePeticion<readonly T[]>(ruta, (donde, senal) => pedirLista<T>(donde, senal));
}

/**
 * Pide una operacion paginada y devuelve **el envoltorio entero**, no solo su contenido.
 *
 * <h2>Por que hace falta, y por que `useLista` no basta (I-4)</h2>
 *
 * Porque `useLista` tira `pagina`, `tamano`, `totalElementos`, `totalPaginas` y `hayMas`, y con
 * seis filas eso no se notaba: el padron del artboard cabia entero en una respuesta, asi que
 * `contenido.length` ERA la cuenta del padron. Con **10 603** contribuyentes medidos —y
 * `totalPaginas: 5302` con `tamano=2`— deja de serlo: el contenido es una ventana y la cuenta
 * la sabe el backend. Recalcularla aqui daria «20 de 20» sobre un padron de diez mil.
 *
 * Quien pagina necesita ademas `hayMas` para saber si «Siguiente» lleva a alguna parte, y
 * `totalPaginas` para decir «pagina 3 de 5 302». Ninguno de los dos se deduce del contenido.
 */
export function usePagina<T>(ruta: string | null): Recurso<Paginado<T>> {
  return usePeticion<Paginado<T>>(ruta, (donde, senal) => pedirPagina<T>(donde, senal));
}

/**
 * Pide un calculo, que el contrato publica como `POST`. Ver `pedirCalculo`.
 *
 * Con `ruta` nula no pide nada, que es como la seccion «Determinación» se ahorra las cinco
 * memorias que no se estan mirando: elegir un tipo cambia la ruta, y el efecto **aborta la
 * anterior**. Sin eso, pasar por los seis tipos dejaria seis peticiones vivas y el cuadro lo
 * pintaria la que contestara ultima.
 */
export function useCalculo<T>(ruta: string | null): Recurso<T> {
  return usePeticion<T>(ruta, (donde, senal) => pedirCalculo<T>(donde, senal));
}

import { useEffect, useState } from 'react';

import { ErrorDeLaApi } from '../api/cliente.ts';
import { pedirLista, pedirUno } from './lecturas.ts';

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
}

/** Lo que se le dice al usuario cuando la peticion no salio. */
function mensajeDe(fallo: unknown): string {
  if (fallo instanceof ErrorDeLaApi) {
    // El codigo de estado va dentro a proposito: «no tienes permiso» y «el sistema no
    // contesta» se arreglan de maneras distintas, y quien atiende tiene que poder decir cual.
    return `El sistema no pudo contestar (${String(fallo.estado)}): ${fallo.message}.`;
  }
  return 'El sistema no pudo contestar. Reintente en unos segundos.';
}

/**
 * El estado inicial: nada, y cargando.
 *
 * `cargando` empieza en `true` cuando hay ruta porque el efecto todavia no ha corrido; con
 * `false` la pantalla parpadearia su estado vacio en el primer fotograma.
 */
function alEmpezar<T>(hayRuta: boolean): Recurso<T> {
  return { dato: null, cargando: hayRuta, error: null };
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
      fijar({ dato: null, cargando: false, error: null });
      return;
    }

    const control = new AbortController();
    let vivo = true;
    fijar({ dato: null, cargando: true, error: null });

    pedir(ruta, control.signal).then(
      (dato) => {
        if (vivo) {
          fijar({ dato, cargando: false, error: null });
        }
      },
      (fallo: unknown) => {
        // Abortar no es fallar: es que la pantalla ya no quiere esa respuesta.
        if (vivo && !control.signal.aborted) {
          fijar({ dato: null, cargando: false, error: mensajeDe(fallo) });
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

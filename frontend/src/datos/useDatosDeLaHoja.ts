import { useQuery } from '@tanstack/react-query';

import { ErrorDeLaApi } from '../api/cliente.ts';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { hojaDe } from '../pantallas/arbol.ts';
import type { Ausencia, DatosDeLaPantalla } from '@kamayuk/ui';
import { porQueNoHayDato } from '../porQueNoHayDato.ts';
import type { Reparto } from './conectores.ts';
import { CONECTORES } from './conectores.ts';

/**
 * **Los datos de una pantalla, pedidos de verdad** (#97, AC1).
 *
 * <h2>Los cuatro estados, y por que ninguno se puede saltar</h2>
 *
 * · **Cargando.** Dura lo que tarde la red. Sin decirlo, una pantalla llena de huecos durante dos
 *   segundos es indistinguible de una pantalla sin backend — y el usuario ya se fue.
 * · **Error.** Se dice **con el peldano de identidad cuando lo hay**: un 401 no es «fallo la red»,
 *   es «su sesion caduco», y la diferencia decide si uno recarga o vuelve a entrar.
 * · **Vacio.** La operacion contesto y no hay nada. **No es lo mismo que no haber preguntado**, y
 *   por eso tiene su propia frase: «no hay ninguna corrida» es un hecho del negocio.
 * · **Dato.** Lo que llego, repartido por su conector.
 *
 * <h2>Y el quinto, que no es un estado sino una propiedad del dato</h2>
 *
 * **No publicado**: la operacion se pidio, contesto bien, y no trae ese campo. Va por campo y no
 * por pantalla —`ausenciaPorCampo`— porque en la misma pantalla conviven campos con dato y campos
 * sin el. Decir «sin conectar» ahi seria falso: si esta conectada.
 *
 * <h2>Por que no se reintenta</h2>
 *
 * `retry: false`. Un 401 reintentado tres veces son tres idas a un backend que ya dijo que no, y
 * el usuario espera el triple para leer el mismo mensaje. Lo que hay que hacer con un 401 no es
 * insistir: es volver a entrar.
 */

/** Lo que se dice mientras se espera. */
const CARGANDO: Ausencia = {
  enElCampo: 'pidiendo…',
  explicacion: 'Pidiendo los datos de esta pantalla.',
  tono: 'info',
};

/**
 * **Lo que se dice cuando la hoja es de un contribuyente y la direccion no nombra a ninguno**
 * (#169).
 *
 * No es «sin conectar» —lo esta— ni «sin datos» —no se ha preguntado nada—: es que **falta el
 * sujeto**. Es el `en-espera` que `@kamayuk/ui` ya nombra en `EstadoDeUnaLectura`: «falta el sujeto
 * para poder pedir: no hay nada que pedir todavia».
 *
 * Y es la alternativa a lo unico que se podria haber hecho en su lugar, que era elegir un
 * contribuyente aqui —el primero del padron— y pintar su cuenta corriente: las cifras de una
 * persona de verdad en una pantalla que nadie le pidio.
 */
const SIN_SUJETO: Ausencia = {
  enElCampo: 'falta el contribuyente',
  explicacion:
    'Esta pantalla es de un contribuyente concreto, y la direccion no nombra a ninguno: su codigo ' +
    'va detras del nombre de la hoja. Hasta que lo lleve no se pide nada, porque pedir la de ' +
    'cualquiera seria ensenar la cuenta de quien nadie pregunto.',
  tono: 'info',
};

/** Lo que se dice cuando la operacion contesto y no habia nada. */
const VACIO: Ausencia = {
  enElCampo: 'sin datos',
  explicacion:
    'Se pidieron los datos de esta pantalla y el sistema contesto que no hay ninguno. No es que ' +
    'falle: es que todavia no existe el dato que esta pantalla ensena.',
  tono: 'info',
};

/** Lo que se dice cuando fallo, con el peldano si lo hay. */
function alFallar(error: unknown): Ausencia {
  const esDeLaApi = error instanceof ErrorDeLaApi;
  const codigo = esDeLaApi ? error.estado : null;
  if (codigo === 401 || codigo === 403) {
    return {
      enElCampo: 'sin acceso',
      explicacion:
        codigo === 401
          ? 'La sesion no vale para pedir estos datos. Vuelva a entrar.'
          : 'Su cuenta no tiene permiso para ver los datos de esta pantalla.',
      tono: 'atencion',
    };
  }
  return {
    enElCampo: 'fallo',
    explicacion:
      'No se pudieron pedir los datos de esta pantalla' +
      (codigo === null ? '.' : ` (${String(codigo)}).`) +
      ' Lo que se ve es su forma, no sus datos.',
    tono: 'atencion',
  };
}

/** Un reparto vacio, para los estados en que no hay nada que repartir. */
const NADA: Reparto = { valores: new Map(), filas: new Map(), noPublicados: new Map() };

/**
 * @param clave la hoja abierta
 * @param sujeto el que lleva la ruta —`#/<hoja>/<sujeto>`—, o `null` si no lleva ninguno. Sale de
 *   `useHoja().ruta` del marco y solo llega con valor en las hojas que lo declaran (#169)
 */
export function useDatosDeLaHoja(
  clave: ClaveDeHoja,
  sujeto: string | null = null,
): DatosDeLaPantalla {
  const conector = CONECTORES[clave];
  // Una hoja de un sujeto sin sujeto no pide: no hay nada que pedir, y lo que llegaria seria un
  // 422 del backend dicho como si fuera una averia.
  const faltaElSujeto = conector?.exigeSujeto === true && (sujeto === null || sujeto === '');

  const consulta = useQuery({
    // La clave lleva la hoja dentro: dos pantallas no comparten cache aunque pidan lo mismo. Y
    // lleva el sujeto al final: dos contribuyentes de la misma hoja tampoco.
    queryKey: [...(conector?.clave ?? ['sin-conector', clave]), sujeto ?? ''],
    queryFn: ({ signal }) => conector?.pedir(signal, sujeto) ?? Promise.resolve(null),
    // Sin conector no se pide nada. Es lo que hace que 36 de las 40 pantallas no toquen la red.
    enabled: conector !== undefined && !faltaElSujeto,
    retry: false,
  });

  if (conector === undefined) {
    // Ni siquiera se intenta: el motivo lo redacta quien cruza las operaciones de la hoja contra
    // lo que el backend sirve.
    return { ausencia: porQueNoHayDato(hojaDe(clave)) };
  }

  if (faltaElSujeto) return { ausencia: SIN_SUJETO };

  if (consulta.isPending) return { ausencia: CARGANDO };
  if (consulta.isError) return { ausencia: alFallar(consulta.error) };
  if (consulta.data === null || consulta.data === undefined) return { ausencia: VACIO };

  const reparto = conector.repartir(consulta.data as never);
  return {
    valores: reparto.valores,
    filas: reparto.filas,
    ausenciaPorCampo: reparto.noPublicados,
    // La pantalla SI tiene datos, asi que la frase de arriba no puede decir que no esta conectada.
    // Lo que queda por decir es lo que el campo concreto no trae, y eso va por campo.
    ausencia: {
      enElCampo: 'no publicado',
      explicacion:
        'Esta pantalla lee del sistema. Los campos marcados «no publicado» los pide y la ' +
        'operacion que los sirve no los trae: no se calculan aqui, porque un numero deducido ' +
        'seria indistinguible de uno real.',
      tono: 'info',
    },
  };
}

export { CARGANDO, SIN_SUJETO, VACIO, NADA, alFallar };

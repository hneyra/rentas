import { useQuery } from '@tanstack/react-query';
import type { CuentaEnLaBarra } from '@kamayuk/shell';
import { useTranslation } from 'react-i18next';

import { inicialesDe } from '../dominio/iniciales.ts';
import type { MunicipalidadDeLaSesion, SesionDeLaVentanilla } from './lecturas.ts';
import { RUTAS, pedirUno } from './lecturas.ts';
import { LLAVES } from './useCatalogoPermitido.ts';

/**
 * **La entidad y la cuenta de la barra, leidas de la sesion** (#356).
 *
 * <h2>De que defecto viene</h2>
 *
 * I-1 lo habia cerrado: la barra leia `GET /seguridad/sesion` y `GET /seguridad/sesion/municipalidad`.
 * `623a968` (#90) quito las dos lecturas al pasar a `@kamayuk/shell` y puso tres literales del
 * artboard —«Municipalidad Distrital de Catacaos», su escudo y «J. Cardenas Vega»—, sin
 * declararlo. Desde entonces una cuenta de **cualquier** municipalidad veia, en las cuarenta
 * pantallas y encima de las cifras de su propio padron, el nombre de Catacaos y a una persona que no
 * existe. RLS seguia aislando los datos; lo que mentia era quien decia de quien son.
 *
 * <h2>Las dos lecturas son la unica fuente, y el armazon no se toca</h2>
 *
 * `Armazon` ya recibe `entidad` y `cuenta` como datos, asi que el arreglo es de COMPOSICION: la
 * raiz (`aplicacion.tsx`) le pasa lo que devuelve este gancho en vez de una constante. Las dos
 * consultas viven en la rama `seguridad` de la cache (`LLAVES`), y la de la sesion es **la misma**
 * que pide `useDatosDeLaHoja` para el ejercicio: se pide una vez.
 *
 * <h2>Mientras no se sabe, se dice; y cuando falla, tambien. Nunca un nombre</h2>
 *
 * Las dos partes se resuelven **cada una por su lado**: si la municipalidad contesta y la sesion
 * no, la entidad es la de verdad y la cuenta dice que no se sabe. Lo que ninguna rama hace es
 * poner un nombre que no haya contestado el backend — ni el del artboard ni «el de la captura»,
 * que es el respaldo que `sesionMedida.ts` existe para prohibir.
 *
 * · **Pidiendo**: «Averiguando…», con la raya en el circulo.
 * · **Fallo**: «No se pudo saber…». No hace falta mas remedio que este: el que decide si la sesion
 *   vale es el catalogo, que ya tiene su rama del 401 (#355) y no monta el armazon sin arbol.
 * · **Contestado**: el nombre tal como llega, **sin pasar por `t()`** —es un dato, como los rotulos
 *   de los modulos: traducir «Municipalidad Provincial de Sullana» seria falso—, y las iniciales
 *   derivadas de el con `inicialesDe`.
 *
 * Si una respuesta ya llegada falla al refrescar, React Query conserva el dato y marca el error:
 * se sigue ensenando el dato, porque sigue siendo la misma sesion.
 */

/** Lo que la barra dibuja arriba a la izquierda y arriba a la derecha. */
export interface CabeceraDeLaSesion {
  /** La municipalidad de la sesion, o por que no se sabe. */
  readonly entidad: string;
  readonly cuenta: CuentaEnLaBarra;
}

/** El circulo cuando no hay nombre del que sacar iniciales: la raya de un dato vacio. */
const SIN_INICIALES = '—';

export function useCabeceraDeLaSesion(): CabeceraDeLaSesion {
  const { t } = useTranslation();

  const sesion = useQuery({
    queryKey: LLAVES.sesion,
    queryFn: ({ signal }) => pedirUno<SesionDeLaVentanilla>(RUTAS.sesion, signal),
    retry: false,
  });
  const municipalidad = useQuery({
    queryKey: LLAVES.municipalidad,
    queryFn: ({ signal }) =>
      pedirUno<MunicipalidadDeLaSesion>(RUTAS.municipalidadDeLaSesion, signal),
    retry: false,
  });

  const nombreDeLaEntidad = municipalidad.data?.nombre;
  const entidad =
    nombreDeLaEntidad ??
    (municipalidad.isError
      ? t('No se pudo saber de que municipalidad es esta sesion')
      : t('Averiguando la municipalidad de esta sesion'));

  const nombre = sesion.data?.nombre;
  const cuenta: CuentaEnLaBarra =
    nombre === undefined
      ? {
          nombre: sesion.isError
            ? t('No se pudo saber quien ha entrado')
            : t('Averiguando quien ha entrado'),
          iniciales: SIN_INICIALES,
        }
      : {
          nombre,
          iniciales: inicialesDe(nombre) || SIN_INICIALES,
          // La linea de debajo repite la entidad, como el artboard; y solo si se sabe: una segunda
          // «Averiguando…» bajo la primera no dice nada que no diga ya la barra.
          ...(nombreDeLaEntidad === undefined ? {} : { nota: nombreDeLaEntidad }),
        };

  return { entidad, cuenta };
}

import { useQuery } from '@tanstack/react-query';
import type { Catalogo } from '@kamayuk/shell';
import { useTranslation } from 'react-i18next';
import { useMemo } from 'react';

import { ErrorDeLaApi } from '../api/cliente.ts';
import { CATALOGO, CODIGO_POR_CLAVE } from '../catalogo.ts';
import type { CatalogoCompuesto } from '../permisos.ts';
import { componer } from '../permisos.ts';
import type { AccesoDelSistema, ModuloDelSistema, PermisosDeLaSesion } from './lecturas.ts';
import { RUTAS, pedirLista, pedirPagina, pedirUno } from './lecturas.ts';

/**
 * **El catalogo que el armazon recibe, filtrado por lo que la cuenta puede abrir** (#105).
 *
 * <h2>Los cuatro estados, y por que «sin permiso» NO es un error</h2>
 *
 * · **Pidiendo** — no se ofrece nada todavia. Ofrecer el catalogo entero «mientras llega» seria
 *   ensenar durante un segundo justo lo que este issue existe para esconder, y un segundo basta
 *   para pulsar.
 * · **Error** — no se sabe que puede la cuenta, asi que **no se ofrece nada** y se dice. Ofrecerlo
 *   todo ante un fallo convierte un problema de red en un agujero de autorizacion.
 * · **Sin permiso para nada** — se pidio, contesto, y esta cuenta no puede abrir ni un modulo. **No
 *   es un error**: es una cuenta recien creada o mal afiliada, y quien la mire tiene que poder
 *   distinguirlo de un backend caido. Una pantalla en blanco no distingue las dos.
 * · **Compuesto** — lo que se puede abrir, con el rotulo del backend.
 *
 * <h2>Las tres consultas van juntas y no una tras otra</h2>
 *
 * No se necesitan entre si: ninguna usa el resultado de otra. Encadenarlas triplicaria la espera
 * del arranque —tres idas seguidas— para no ganar nada.
 */

/** Que se sabe del catalogo, ademas del catalogo. */
export interface CatalogoDeLaSesion extends CatalogoCompuesto {
  /** `null` mientras se pide. Distinto de «ninguno», que es una lista vacia. */
  readonly estado: 'pidiendo' | 'error' | 'sin-permiso' | 'compuesto';
  /** Que decir cuando no hay arbol. Vacio cuando si lo hay. */
  readonly porQue: string;
}

const VACIO: CatalogoCompuesto = {
  catalogo: [],
  sinCatalogo: [],
  sinPermiso: [],
  deOtroSistema: [],
};

export function useCatalogoPermitido(): CatalogoDeLaSesion {
  const { t } = useTranslation();

  const modulos = useQuery({
    queryKey: ['seguridad', 'modulos'],
    queryFn: ({ signal }) => pedirLista<ModuloDelSistema>(RUTAS.modulos, signal),
    retry: false,
  });
  const accesos = useQuery({
    queryKey: ['seguridad', 'accesos'],
    queryFn: ({ signal }) => pedirPagina<AccesoDelSistema>(RUTAS.accesos, signal),
    retry: false,
  });
  const permisos = useQuery({
    queryKey: ['seguridad', 'permisos'],
    queryFn: ({ signal }) => pedirUno<PermisosDeLaSesion>(RUTAS.permisosDeLaSesion, signal),
    retry: false,
  });

  const compuesto = useMemo(() => {
    if (
      modulos.data === undefined ||
      accesos.data === undefined ||
      permisos.data === undefined
    ) {
      return null;
    }
    return componer(
      CATALOGO,
      modulos.data,
      accesos.data.contenido,
      permisos.data,
      (modulo) => CODIGO_POR_CLAVE.get(modulo.clave) ?? '',
    );
  }, [modulos.data, accesos.data, permisos.data]);

  if (modulos.isError || accesos.isError || permisos.isError) {
    const error = modulos.error ?? accesos.error ?? permisos.error;
    const codigo = error instanceof ErrorDeLaApi ? error.estado : null;
    return {
      ...VACIO,
      estado: 'error',
      porQue:
        codigo === 401
          ? t('La sesion no vale para saber que puede abrir esta cuenta. Vuelva a entrar.')
          : t(
              'No se pudo saber que modulos puede abrir esta cuenta, asi que no se ofrece ninguno. ' +
                'Ofrecerlos todos ante un fallo convertiria un problema de red en un agujero de ' +
                'autorizacion.',
            ),
    };
  }

  if (compuesto === null) {
    return { ...VACIO, estado: 'pidiendo', porQue: t('Averiguando que puede abrir esta cuenta.') };
  }

  if (compuesto.catalogo.length === 0) {
    return {
      ...compuesto,
      estado: 'sin-permiso',
      porQue: t(
        'Esta cuenta no puede abrir ningun modulo de este sistema. No es un fallo: es una cuenta ' +
          'sin permisos, o afiliada a un grupo que no los tiene.',
      ),
    };
  }

  return { ...compuesto, estado: 'compuesto', porQue: '' };
}

/** El catalogo a secas, para quien solo quiera eso. */
export function catalogoDe(sesion: CatalogoDeLaSesion): Catalogo {
  return sesion.catalogo;
}

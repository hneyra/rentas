import { useQuery } from '@tanstack/react-query';
import type { Catalogo } from '@kamayuk/shell';
import { useTranslation } from 'react-i18next';
import { useMemo } from 'react';

import { ErrorDeLaApi } from '../api/cliente.ts';
import type { FallaDeLaPuerta } from '../api/identidad.ts';
import { entrar, hayPuerta, olvidarLaParada } from '../api/identidad.ts';
import { CATALOGO, CODIGO_POR_CLAVE } from '../catalogo.ts';
import type { CatalogoCompuesto } from '../permisos.ts';
import { componer } from '../permisos.ts';
import type { AccesoDelSistema, ModuloDelSistema, PermisosDeLaSesion } from './lecturas.ts';
import { RUTAS, pedirLista, pedirPagina, pedirUno } from './lecturas.ts';

/**
 * **El catalogo que el armazon recibe, filtrado por lo que la cuenta puede abrir** (#105).
 *
 * <h2>Los cinco estados, y por que «sin permiso» NO es un error</h2>
 *
 * · **Pidiendo** — no se ofrece nada todavia. Ofrecer el catalogo entero «mientras llega» seria
 *   ensenar durante un segundo justo lo que este issue existe para esconder, y un segundo basta
 *   para pulsar.
 * · **Error** — no se sabe que puede la cuenta, asi que **no se ofrece nada** y se dice. Ofrecerlo
 *   todo ante un fallo convierte un problema de red en un agujero de autorizacion. **Si el error es
 *   un 401** (#355), trae su remedio —**volver a identificarse**, ver `volverAEntrar`—: es la otra
 *   rama, junto al 403, que ofrece algo que pulsar.
 * · **Sin privilegio para leer el catalogo** (#311) — `GET /seguridad/{modulos,accesos}` contesto
 *   403 `SIN_PRIVILEGIO`. Tampoco es un error: el sistema contesto lo que tenia que contestar, y
 *   se arregla dando una opcion. Por eso es la unica rama que **nombra lo que falta** y la unica
 *   que **ofrece reintentar** — ver `OPCIONES_QUE_LEEN_EL_CATALOGO`.
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

/** La rama de la cache donde viven las tres. Una sola palabra, escrita una sola vez. */
const RAMA = 'seguridad';

/**
 * Las llaves con que las tres viven en la cache de consultas.
 *
 * **Se exportan**, y eso dice algo de ellas: la siembra de desarrollo (#114) tiene que poner el
 * dato **en estas mismas llaves** para que estas tres consultas lo encuentren ya contestado. Con
 * los literales repetidos alli, renombrar una llave aqui dejaria la siembra apuntando a una
 * llave que nadie lee — y el sintoma no seria un error sino el catalogo vacio, o sea el mismo
 * que se ve cuando no hay backend. Un desajuste mudo entre dos sitios que tienen que decir lo
 * mismo es el defecto que este archivo lleva evitando desde I-3.
 */
export const LLAVES = {
  rama: [RAMA],
  modulos: [RAMA, 'modulos'],
  accesos: [RAMA, 'accesos'],
  permisos: [RAMA, 'permisos'],
  /**
   * **Quien esta trabajando, y con que ejercicio** (#181, #356).
   *
   * No la pide este gancho. La piden dos: `useCabeceraDeLaSesion`, **siempre**, para que la barra
   * diga quien ha entrado (#356); y `useDatosDeLaHoja`, solo cuando la hoja abierta declara
   * `exigeEjercicio`. Con la misma llave las dos leen **la misma respuesta**, y la sesion se pide
   * una vez. Vive aqui con las otras tres porque es de la misma rama —`seguridad`— y porque las
   * llaves de esa rama se escriben en un sitio: la siembra de desarrollo (#114) fija
   * `staleTime: Infinity` sobre `LLAVES.rama` entera, y una llave suelta en otro archivo quedaria
   * fuera de ese trato sin que nada lo dijera.
   *
   * **La siembra la siembra desde #356, y con la captura**: `SESION_MEDIDA` trae
   * `ejercicioDeTrabajo: null`, o sea que **no pone ningun ejercicio**, que es lo que #181 prohibe
   * inventar. Hasta #356 no se sembraba y `seg-aud` salia a la red y ensenaba su error; hoy dice que
   * la sesion no tiene ejercicio, que es lo que contesta la instalacion con esa cuenta. Sin
   * sembrarla, la barra de `yarn dev` saldria a pedir quien esta dentro y la siembra dejaria de ser
   * «cero peticiones a `/seguridad/`».
   */
  sesion: [RAMA, 'sesion'],
  /**
   * **De que municipalidad es la sesion** (#356). La lee `useCabeceraDeLaSesion` para la entidad
   * de la barra, y la siembra de desarrollo la cubre con `MUNICIPALIDAD_MEDIDA`.
   */
  municipalidad: [RAMA, 'municipalidad'],
} as const;

/**
 * **Las dos opciones que pide leer el catalogo, con su nombre del CATALOGO** (#311).
 *
 * `GET /seguridad/modulos` declara `@RequiereAcceso(acceso = "modulos", …)` y `GET
 * /seguridad/accesos`, `acceso = "accesos"` (`SeguridadController`). A la cuenta que no las tenga
 * le contestan **403 `SIN_PRIVILEGIO`**, y lo unico con que eso se arregla es saber **que**
 * opciones pedir — por el nombre con que las encuentra quien administra los perfiles, no por su
 * codigo.
 *
 * <h2>Por que el nombre esta escrito aqui, y no se lee</h2>
 *
 * Porque **se lee de la misma lectura que fallo**: el nombre de un acceso lo publica `GET
 * /seguridad/accesos`, y es justo la que contesto 403. No hay otra operacion de este backend que
 * lo diga —`/sesion/permisos` trae codigos y privilegios, no nombres—, y pedirselo a `identidad`
 * seria una lectura mas a otro sistema para ensenar dos frases fijas.
 *
 * Asi que se escribe, y **se escribe lo que el backend siembra**: `SembradorDelCatalogo` lee
 * `docs/10-negocio/catalogo-de-opciones.md` y mete ese nombre en `acceso.nombre`, que es lo que
 * `GET /seguridad/accesos` devuelve (la captura de `seguridadMedida.ts` lo dice igual). Que los
 * dos codigos y los dos nombres sigan siendo esos lo vigila
 * `verificaciones/las-opciones-que-leen-el-catalogo-son-las-del-backend.test.ts`, que lee el
 * controlador y el catalogo de opciones: escritos a mano sin esa guarda, renombrar la opcion alla
 * dejaria esta pantalla mandando a pedir una que no existe.
 *
 * Los nombres pasan por `t()` al dibujarse, por variable; por eso estan en `LITERALES` de la
 * guarda del locale, como los rotulos del mando de temas.
 */
export const OPCIONES_QUE_LEEN_EL_CATALOGO = {
  modulos: { codigo: 'modulos', nombre: 'Módulos del sistema' },
  accesos: { codigo: 'accesos', nombre: 'Accesos y políticas' },
} as const;

/** Que se sabe del catalogo, ademas del catalogo. */
export interface CatalogoDeLaSesion extends CatalogoCompuesto {
  /**
   * Distinto de «ninguno», que es una lista vacia. `sin-privilegio` es el 403 `SIN_PRIVILEGIO`
   * sobre las lecturas del catalogo (#311): no es un error, y se arregla dando una opcion.
   */
  readonly estado: 'pidiendo' | 'error' | 'sin-privilegio' | 'sin-permiso' | 'compuesto';
  /** Que decir cuando no hay arbol. Vacio cuando si lo hay. */
  readonly porQue: string;
  /**
   * Los NOMBRES —en castellano, sin traducir: son claves— de las opciones cuyo 403 dejo sin
   * arbol. Vacio fuera de `sin-privilegio`.
   */
  readonly faltan: readonly string[];
  /**
   * Volver a pedir las tres. **Solo en `sin-privilegio`**, y `null` en todo lo demas: en un 401
   * reintentar trae el mismo token y el mismo 401, y un boton que no arregla nada es lo que #291
   * retiro de 39 pantallas. En el 403 si arregla: el guardia comprueba cada peticion contra la
   * base (ADR-0013), asi que en cuanto la opcion llega la siguiente peticion pasa, sin cerrar la
   * sesion.
   */
  readonly reintentar: (() => void) | null;
  /** Si hay una vuelta en curso. Para no ofrecer pulsar otra vez mientras. */
  readonly reintentando: boolean;
  /**
   * **Volver a identificarse: el remedio del 401, y solo del 401** (#355).
   *
   * La premisa de `reintentar` —«en un 401 reintentar trae el mismo 401»— es cierta para
   * reintentar y falsa para esto: volver a identificarse **si** arregla un 401, porque trae un
   * token nuevo. Y es la unica salida de la pestana cuando el arranque freno —la marca de salida
   * tras «Cerrar sesion», o el tope de idas tras tres canjes fallidos—: F5 repite la parada,
   * porque las dos marcas viven lo que la pestana. Hasta #355 este estado no traia ningun remedio
   * y la pestana quedaba inservible.
   *
   * Devuelve lo que devuelve `entrar()`: `null` cuando el navegador se va, y **la falla cuando el
   * emisor no contesto** (#112), para que quien dibuja el boton la ensene en vez de quedarse muda.
   *
   * `null` fuera del 401, y tambien en el 401 **sin puerta** (`hayPuerta()`): sin `crypto.subtle`
   * no hay S256, y un boton que revienta al pulsarlo es peor que no tenerlo.
   */
  readonly volverAEntrar: (() => Promise<FallaDeLaPuerta | null>) | null;
}

/** Lo que no cambia fuera de `sin-privilegio` y del 401. */
const SIN_REMEDIO = {
  faltan: [],
  reintentar: null,
  reintentando: false,
  volverAEntrar: null,
} as const;

/**
 * **Olvidar la parada va ANTES de entrar**, y es el motivo que la V6 dejo escrito (#355): el tope
 * de tres idas existe para cortar un bucle AUTOMATICO, y esto es una persona pulsando un boton. Sin
 * olvidarla, la ida de quien pulsa se contaria como la cuarta de una racha que ya termino, y la
 * marca de salida seguiria diciendo «recien salido» a una pestana que acaba de pedir entrar.
 */
function volverAEntrar(): Promise<FallaDeLaPuerta | null> {
  olvidarLaParada();
  return entrar();
}

/** El 403 `SIN_PRIVILEGIO`, y solo ese: el `SIN_MUNICIPALIDAD` no se arregla dando una opcion. */
function esSinPrivilegio(error: unknown): boolean {
  return error instanceof ErrorDeLaApi && error.estado === 403 && error.codigo === 'SIN_PRIVILEGIO';
}

/**
 * **El `problem+json` de un 403, en los tres campos que esta rama mira** (#311).
 *
 * Vive aqui, y no en `api/cliente.ts` junto a `CuerpoDeProblema` —la forma completa, con sus siete
 * campos opcionales—: esta es la forma REDUCIDA con la que el arnes de extremo a extremo
 * (`e2e/el-403-del-catalogo.spec.ts`) simula la respuesta, y `estado` en `title`/`status`/`codigo`
 * es lo unico que `esSinPrivilegio` lee. Declararla bajo `src/datos/` —y no como objeto literal en
 * el arnes— es lo que `verificaciones/los-fixtures-del-arnes-llevan-tipo.test.ts` de #314 exige de
 * todo cuerpo que `e2e/` sirve: sin tipo, un campo que cambie de nombre compilaria igual y el rojo
 * saldria como un tiempo agotado sin nombrar ni el archivo ni el campo (#313).
 */
export interface CuerpoDelSinPrivilegio {
  readonly title: string;
  readonly status: number;
  readonly codigo: string;
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
    queryKey: LLAVES.modulos,
    queryFn: ({ signal }) => pedirLista<ModuloDelSistema>(RUTAS.modulos, signal),
    retry: false,
  });
  const accesos = useQuery({
    queryKey: LLAVES.accesos,
    queryFn: ({ signal }) => pedirPagina<AccesoDelSistema>(RUTAS.accesos, signal),
    retry: false,
  });
  const permisos = useQuery({
    queryKey: LLAVES.permisos,
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
    const errores = [modulos.error, accesos.error, permisos.error].filter((e) => e !== null);
    const es401 = errores.some((e) => e instanceof ErrorDeLaApi && e.estado === 401);

    // **El 403 `SIN_PRIVILEGIO` tiene rama propia (#311), y solo cuando es TODO lo que paso.**
    // Solo las dos del catalogo lo pueden dar —`/sesion/permisos` declara `SESION_PROPIA` y pasa
    // con un token valido—, y si ademas algo se rompio, decir «le faltan estas opciones» seria
    // mentir: dadas, la pantalla seguiria sin arbol.
    const soloFaltanOpciones =
      !permisos.isError &&
      (!modulos.isError || esSinPrivilegio(modulos.error)) &&
      (!accesos.isError || esSinPrivilegio(accesos.error));

    if (!es401 && soloFaltanOpciones) {
      const faltan = [
        ...(modulos.isError ? [OPCIONES_QUE_LEEN_EL_CATALOGO.modulos.nombre] : []),
        ...(accesos.isError ? [OPCIONES_QUE_LEEN_EL_CATALOGO.accesos.nombre] : []),
      ];
      return {
        ...VACIO,
        estado: 'sin-privilegio',
        porQue: t(
          'Esta cuenta no tiene permiso para leer el catalogo de este sistema, asi que no hay ' +
            'modulos que ofrecerle. No es una averia: le falta el permiso de lectura en estas ' +
            'opciones, y lo da quien administre los perfiles.',
        ),
        faltan,
        // Las tres, y no solo las que fallaron: quien da una opcion puede estar cambiando el
        // grupo entero, y la matriz de `/sesion/permisos` es la que filtra el arbol que se monte.
        reintentar: () => {
          void Promise.all([modulos.refetch(), accesos.refetch(), permisos.refetch()]);
        },
        reintentando: modulos.isFetching || accesos.isFetching || permisos.isFetching,
        volverAEntrar: null,
      };
    }

    return {
      ...VACIO,
      ...SIN_REMEDIO,
      estado: 'error',
      // El remedio viaja con el estado, como `reintentar` en el 403 (#311): aqui se decide, en un
      // solo sitio, que arregla cada cosa, y quien dibuja solo pinta el boton si lo hay.
      volverAEntrar: es401 && hayPuerta() ? volverAEntrar : null,
      porQue: es401
        ? t('La sesion no vale para saber que puede abrir esta cuenta. Vuelva a entrar.')
        : t(
            'No se pudo saber que modulos puede abrir esta cuenta, asi que no se ofrece ninguno. ' +
              'Ofrecerlos todos ante un fallo convertiria un problema de red en un agujero de ' +
              'autorizacion.',
          ),
    };
  }

  if (compuesto === null) {
    return {
      ...VACIO,
      ...SIN_REMEDIO,
      estado: 'pidiendo',
      porQue: t('Averiguando que puede abrir esta cuenta.'),
    };
  }

  if (compuesto.catalogo.length === 0) {
    return {
      ...compuesto,
      ...SIN_REMEDIO,
      estado: 'sin-permiso',
      porQue: t(
        'Esta cuenta no puede abrir ningun modulo de este sistema. No es un fallo: es una cuenta ' +
          'sin permisos, o afiliada a un grupo que no los tiene.',
      ),
    };
  }

  return { ...compuesto, ...SIN_REMEDIO, estado: 'compuesto', porQue: '' };
}

/** El catalogo a secas, para quien solo quiera eso. */
export function catalogoDe(sesion: CatalogoDeLaSesion): Catalogo {
  return sesion.catalogo;
}

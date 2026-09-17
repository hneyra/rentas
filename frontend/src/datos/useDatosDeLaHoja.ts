import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { ErrorDeLaApi } from '../api/cliente.ts';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { hojaDe } from '../pantallas/arbol.ts';
import type { Ausencia, DatosDeLaPantalla, DatosDeUnaTabla, RutaDeLaHoja } from '@kamayuk/ui';
import { porQueNoHayDato } from '../porQueNoHayDato.ts';
import type { Reparto } from './conectores.ts';
import { CONECTORES, loQueLaHojaDeclara } from './conectores.ts';
import { formatearEntero } from '../dominio/formato.ts';
import { FRASE_DEL_CONTEO } from '../i18n/textosDelMarco.ts';
import type { SesionDeLaVentanilla } from './lecturas.ts';
import { RUTAS, pedirUno } from './lecturas.ts';
import { LLAVES } from './useCatalogoPermitido.ts';

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

/**
 * **Lo que se dice cuando la hoja es de un ejercicio y la sesion no tiene ninguno** (#181).
 *
 * Es la hermana de `SIN_SUJETO` y no una variante suya: alli falta algo que trae la **direccion**
 * y aqui falta algo que trae la **sesion**. Las dos dicen lo mismo de fondo —no se pide, porque no
 * se puede pedir bien— y por eso las dos son `info` y no `atencion`: no ha fallado nada.
 *
 * Y es la alternativa a las dos cosas que se podrian haber hecho en su lugar, que son las dos que
 * el AC2 de #181 prohibe por su nombre: escribir un ano fijo, o preguntarle el ano al reloj del
 * puesto. Las dos harian que esta pantalla contestara **200, con filas, de un ejercicio que nadie
 * eligio** — y una bitacora de auditoria del ano equivocado no se distingue de una del correcto.
 *
 * **No es un caso teorico**: medido contra la instalacion, la cuenta `administrador` contesta
 * `ejercicioDeTrabajo: null` (ver `sesionMedida.ts`). Es el estado que hay hoy, no el raro.
 */
const SIN_EJERCICIO: Ausencia = {
  enElCampo: 'falta el ejercicio',
  explicacion:
    'Esta pantalla es de un ejercicio concreto, y la sesion no tiene ninguno fijado: se fija en ' +
    'Seguridad · Sistema, y vale para todos los modulos a la vez. Hasta que lo tenga no se pide ' +
    'nada, porque el ano de hoy no es el ejercicio de trabajo de nadie y una bitacora del ' +
    'ejercicio equivocado contesta igual de bien que la correcta.',
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

/**
 * **El total publicado, convertido en la frase que se lee**: «20 de 1 842» (#172, AC2).
 *
 * Es lo unico que esta funcion hace, y es lo que el conector no puede hacer: un conector es dato y
 * no tiene `t()` delante, asi que un «de» escrito alli llegaria al DOM en castellano en cualquier
 * idioma (#103). Lo que viaja por `TablaRepartida.total` es el numero que la OPERACION publica
 * —`totalElementos`—, nunca uno contado sobre la pagina.
 *
 * Sin `total`, la tabla no recibe conteo y el interprete cuenta las filas que tiene delante
 * —«20 registros»—, que es cierto de lo que se ve y no afirma ningun tamano de padron.
 */
function conConteo(
  tablas: NonNullable<Reparto['tablas']>,
  t: (clave: string, datos?: Readonly<Record<string, unknown>>) => string,
): ReadonlyMap<string, DatosDeUnaTabla> {
  return new Map(
    [...tablas].map(([clave, tabla]) => [
      clave,
      {
        filas: tabla.filas,
        ...(tabla.totalElementos === undefined
          ? {}
          : {
              conteo: t(FRASE_DEL_CONTEO, {
                cuantos: formatearEntero(tabla.filas.length),
                total: formatearEntero(tabla.totalElementos),
              }),
            }),
      },
    ]),
  );
}

/** Un reparto vacio, para los estados en que no hay nada que repartir. */
const NADA: Reparto = { valores: new Map(), filas: new Map(), noPublicados: new Map() };

/** Una ruta vacia, para quien monte una pantalla suelta sin marco que le de la suya. */
const SIN_RUTA: RutaDeLaHoja = { sujeto: null, parametros: {} };

/**
 * @param clave la hoja abierta
 * @param ruta **la de la hoja, entera** (#172): su sujeto —`#/<hoja>/<sujeto>`— y sus parametros,
 *   que es donde el interprete de `@kamayuk/ui` deja la pagina y el campo de orden que se
 *   eligieron. Sale de `useHoja().ruta` del marco, que ya entrega **solo lo que el destino
 *   declara**. Hasta #172 solo entraba el sujeto, y entonces el mando de pagina habria movido la
 *   direccion sin que nadie volviera a pedir
 */
export function useDatosDeLaHoja(
  clave: ClaveDeHoja,
  ruta: RutaDeLaHoja = SIN_RUTA,
): DatosDeLaPantalla {
  const { t } = useTranslation();
  const conector = CONECTORES[clave];
  const sujeto = ruta.sujeto;
  // Solo lo que el conector declara, y en su orden: de aqui sale tambien la llave de la cache.
  const enLaRuta = loQueLaHojaDeclara(conector, ruta.parametros);
  // Una hoja de un sujeto sin sujeto no pide: no hay nada que pedir, y lo que llegaria seria un
  // 422 del backend dicho como si fuera una averia.
  const faltaElSujeto = conector?.exigeSujeto === true && (sujeto === null || sujeto === '');

  /*
   * **El ejercicio de trabajo, y solo para quien lo exige** (#181).
   *
   * `enabled` acotado a `exigeEjercicio` es lo que hace que las otras 39 hojas sigan sin pedir la
   * sesion: sin eso, abrir cualquier destino sumaria una ida a `/seguridad/sesion` — y la siembra
   * de #114, que afirma **cero peticiones a `/seguridad/`** con el catalogo sembrado, saldria roja
   * por una lectura que esa pantalla no necesita.
   *
   * Va por `useQuery` y no por una lectura suelta porque asi **se comparte**: la llave es de la
   * rama `seguridad`, o sea que dos hojas que exijan ejercicio piden la sesion una sola vez, y el
   * dia que la barra global lea quien esta trabajando lee de la misma.
   */
  const pideLaSesion = conector?.exigeEjercicio === true;
  const sesion = useQuery({
    queryKey: LLAVES.sesion,
    queryFn: ({ signal }) => pedirUno<SesionDeLaVentanilla>(RUTAS.sesion, signal),
    enabled: pideLaSesion,
    retry: false,
  });
  const ejercicio = sesion.data?.ejercicioDeTrabajo ?? null;
  // Sin ejercicio no se pide: no es que se pida peor, es que la operacion lo declara obligatorio y
  // **la peticion no se manda**. Ver `SIN_EJERCICIO` y el javadoc de `Conector.exigeEjercicio`.
  const faltaElEjercicio = pideLaSesion && ejercicio === null;

  const consulta = useQuery({
    // La clave lleva la hoja dentro: dos pantallas no comparten cache aunque pidan lo mismo. Y
    // lleva el sujeto al final: dos contribuyentes de la misma hoja tampoco. El ejercicio va
    // detras por lo mismo: cambiarlo en la sesion tiene que traer OTRA bitacora, no la cacheada.
    // Y lo de la ruta detras, serializado: cambiar de pagina o de orden tiene que traer OTRA
    // respuesta. Sin esto, el mando movería la direccion, `pedir` no se volveria a llamar y la
    // tabla dibujaria la pagina 0 con el rotulo «Pagina 3» — en verde y sin un solo error.
    queryKey: [
      ...(conector?.clave ?? ['sin-conector', clave]),
      sujeto ?? '',
      ejercicio ?? '',
      JSON.stringify(enLaRuta),
    ],
    queryFn: ({ signal }) =>
      conector?.pedir({ senal: signal, sujeto, ejercicio, enLaRuta }) ?? Promise.resolve(null),
    // Sin conector no se pide nada. Es lo que hace que 36 de las 40 pantallas no toquen la red.
    enabled: conector !== undefined && !faltaElSujeto && !faltaElEjercicio,
    retry: false,
  });

  if (conector === undefined) {
    // Ni siquiera se intenta: el motivo lo redacta quien cruza las operaciones de la hoja contra
    // lo que el backend sirve.
    return { ausencia: porQueNoHayDato(hojaDe(clave)) };
  }

  // El conector puede decirlo con sus palabras: `tra-veh` espera una PLACA, no un contribuyente
  // (#180). Sin esa salida, la frase de abajo le pediria a quien atiende el codigo de un
  // contribuyente para abrir la ficha de un vehiculo.
  if (faltaElSujeto) return { ausencia: conector.sinSujeto ?? SIN_SUJETO };

  /*
   * El orden de estas tres importa, y es el de las causas: primero si la sesion fallo, luego si
   * todavia esta en vuelo, y solo entonces si no trae ejercicio.
   *
   * Al reves, mientras la sesion viaja `ejercicio` es `null` y la pantalla diria «falta el
   * ejercicio» un instante antes de pintarse — o para siempre, si la sesion falla: un 401 se
   * leeria como «fije usted el ejercicio», que manda a arreglar lo que no esta roto.
   */
  if (sesion.isError) return { ausencia: alFallar(sesion.error) };
  if (pideLaSesion && sesion.isPending) return { ausencia: CARGANDO };
  if (faltaElEjercicio) return { ausencia: SIN_EJERCICIO };

  if (consulta.isPending) return { ausencia: CARGANDO };
  if (consulta.isError) return { ausencia: alFallar(consulta.error) };
  if (consulta.data === null || consulta.data === undefined) return { ausencia: VACIO };

  const reparto = conector.repartir(consulta.data as never);
  return {
    valores: reparto.valores,
    filas: reparto.filas,
    // Las tablas con `clave` van aparte: sus celdas pueden decir que no hay dato, y por que
    // (`kamayuk-lib`#87). Ver `Reparto.tablas`. Aqui es donde el TOTAL publicado se convierte en
    // la frase que se lee, porque aqui hay `t()` y en el conector no (#172).
    ...(reparto.tablas === undefined ? {} : { tablas: conConteo(reparto.tablas, t) }),
    // Lo que el SERVIDOR dijo de la ventana: `hayMas` y `totalPaginas` (#187). El interprete los
    // busca por nombre, y los nombres los deriva el conector con `hayMasDe` y `paginasDe`.
    ...(reparto.nombrados === undefined ? {} : { nombrados: reparto.nombrados }),
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

export { CARGANDO, SIN_SUJETO, SIN_EJERCICIO, VACIO, NADA, alFallar };

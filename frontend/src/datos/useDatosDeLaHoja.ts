import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';

import { ErrorDeLaApi } from '../api/cliente.ts';
import { peldanoDe } from '../api/escalera.ts';
import type { ClaveDeHoja } from '../pantallas/arbol.ts';
import { hojaDe } from '../pantallas/arbol.ts';
import type { Ausencia, DatosDeLaPantalla, DatosDeUnaTabla, RutaDeLaHoja } from '@kamayuk/ui';
import { porQueNoHayDato } from '../porQueNoHayDato.ts';
import type { DeQuienEs, Reparto } from './conectores.ts';
import { CONECTORES, loQueLaHojaDeclara } from './conectores.ts';
import { formatearEntero, formatearFecha } from '../dominio/formato.ts';
import {
  FRASE_DE_LA_FECHA,
  FRASE_DE_QUIEN_ES,
  FRASE_DE_QUIEN_ES_SIN_PADRON,
  FRASE_DEL_CONTEO,
  FRASE_DEL_PELDANO,
  FRASE_DEL_PELDANO_CON_ESTADO,
  FRASE_DE_LA_HOJA_QUE_NO_SE_DIBUJA,
} from '../i18n/textosDelMarco.ts';
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
 *
 * <h2>El remedio que dice tiene que poder seguirse (#391)</h2>
 *
 * Hasta #391 mandaba a fijarlo «en Seguridad · Sistema», y alli no se escribe nada: `seg-sis` es
 * una definicion interpretada sin conector, y su desplegable y su motivo no mandan ninguna
 * peticion. La cuenta seguia el remedio, volvia y leia la misma frase — atribuyendole a un paso
 * que ya habia dado el no poder abrir la hoja. Hoy el sitio es el mando «Ejercicio» de la barra
 * (`sesion/MandoDelEjercicio.tsx`), que hace el `PUT /seguridad/sesion/ejercicio`; y como solo se
 * ofrece a quien tiene `especial` sobre `cambiar_anio`, la frase lo dice en vez de prometerselo a
 * cualquiera. La fija entera `useDatosDeLaHoja.test.tsx`.
 */
const SIN_EJERCICIO: Ausencia = {
  enElCampo: 'falta el ejercicio',
  explicacion:
    'Esta pantalla es de un ejercicio concreto, y la sesion no tiene ninguno fijado. Se fija con ' +
    'el mando «Ejercicio» de la barra de arriba, que se ofrece a las cuentas con permiso para ' +
    'cambiarlo, y vale para todos los modulos a la vez. Hasta que lo tenga no se pide nada, ' +
    'porque el ano de hoy no es el ejercicio de trabajo de nadie y una bitacora del ejercicio ' +
    'equivocado contesta igual de bien que la correcta.',
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

/**
 * **Lo que se dice cuando fallo: el peldano de la escalera, traducido a una ausencia** (#283).
 *
 * <h2>Aqui habia una escalera corta, y separaba tres cosas donde hay siete</h2>
 *
 * Daba tres respuestas —`SIN_SESION` para el 401, `SIN_PERMISO` para **cualquier** 403 y un
 * `FALLO` con el codigo interpolado para todo lo demas—, y #262 midio lo que eso deja sin decir:
 *
 * · **Los dos 403 se leian igual.** `SIN_MUNICIPALIDAD` —el token no dice de que municipalidad es
 *   la cuenta— lo arregla el administrador en el emisor de identidad; `SIN_PRIVILEGIO` lo arregla
 *   quien administre los perfiles. Con una sola frase para los dos, quien atiende tiene que
 *   llamar por telefono para averiguar a cual de los dos llamar.
 * · **El 422 salia como «fallo (422)»**, o sea **como una averia**, con el tono de que algo se
 *   rompio y el remedio de avisar a soporte — para una observacion de tres letras.
 *
 * Los siete peldanos y sus siete remedios ya estaban escritos y medidos con `curl` en
 * `api/escalera.ts`, sin un solo consumidor de produccion. Esto es ese consumidor.
 *
 * <h2>Los tres campos de una `Ausencia` llevan seis de los siete del peldano</h2>
 *
 * `enElHueco` es la palabra del hueco; `titulo`, `detalle` y `remedio` se arman en la explicacion
 * con `FRASE_DEL_PELDANO`; y `esAveria` elige el tono —`atencion` solo si algo se rompio de
 * verdad—. El que no cabe es `pideIdentidad`, que es un **boton** que el interprete no dibuja en
 * el hueco de una ausencia: lo lleva uno de los siete, y su frase se lee igual.
 *
 * <h2>Por que los tres trozos entran por INTERPOLACION, y no concatenados</h2>
 *
 * Es la leccion de #246, y aqui vale doble: el `detalle` **es lo que el backend dijo** cuando dijo
 * algo, con su cifra dentro. Concatenado, la cadena que llegaria al interprete seria distinta en
 * cada fallo y ninguna clave del locale podria casar con ella. Con `FRASE_DEL_PELDANO` la clave es
 * una y el idioma decide el orden.
 *
 * Y los trozos van **ya pasados por `t()`**: los tres son frases escritas en `api/escalera.ts`, o
 * sea claves, y el inventario del locale las deriva de alli (ver `i18n/catalogo-de-claves.ts`). El
 * `detalle` que viene del backend no es ninguna clave, y `t()` devuelve tal cual lo que no conoce
 * —sin separadores de espacio de nombre, ver `i18n.ts`—, asi que pasarlo no le hace nada.
 *
 * <h2>Lo llama tambien el mando del ejercicio (#391)</h2>
 *
 * Es la primera escritura de esta interfaz que se puede pulsar, y lo que contesta cuando no la
 * acepta —el 422 de una observacion corta o de un ejercicio fuera de rango, un 403 sin el
 * privilegio— es un peldano de la misma escalera. Dibujarlo aqui y alli con la misma frase es lo
 * que evita dos escaleras que dicen cosas distintas del mismo 403; y asi `api/escalera.ts` sigue
 * teniendo un solo consumidor que la dibuja, que es lo que vigila `escalera.test.ts`.
 */
function alFallar(
  error: unknown,
  t: (clave: string, datos?: Readonly<Record<string, unknown>>) => string,
): Ausencia {
  const peldano = peldanoDe(error);
  return {
    enElCampo: peldano.enElHueco,
    explicacion: t(
      // Dos claves y no una con el hueco vacio: ver `FRASE_DEL_PELDANO_CON_ESTADO`.
      peldano.estado === null ? FRASE_DEL_PELDANO : FRASE_DEL_PELDANO_CON_ESTADO,
      {
        titulo: t(peldano.titulo),
        detalle: t(peldano.detalle),
        remedio: t(peldano.remedio),
        estado: peldano.estado,
      },
    ),
    // «El sistema funcionando» no se pinta de «algo se rompio»: un 403 en tono de averia manda a
    // mirar un despliegue cuando lo que falta es una fila en una tabla de permisos.
    tono: peldano.esAveria ? 'atencion' : 'info',
  };
}

/**
 * **Lo que se dice cuando la hoja no se pudo DIBUJAR: lo que lanzo, convertido en ausencia** (#354).
 *
 * No es `alFallar`, y no puede serlo: alli la peticion fallo y el peldano de la escalera dice por
 * que —la red, la sesion, un permiso—. Aqui la respuesta llego bien y lo que fallo es este arbol al
 * traducirla: un conector que recibe una forma que no esperaba y lo dice lanzando, como
 * `formatearImporte` hace a proposito. El peldano de averia diria «el sistema no contesta», que
 * manda a mirar el backend cuando el backend contesto.
 *
 * La recoge la frontera de `aplicacion.tsx`, que envuelve el cuerpo de cada hoja. La palabra del
 * hueco es la de siempre —«fallo»—, y el motivo es el `message` de lo que se lanzo: sin el, quien
 * lo reporta no puede decir que valor fue ni en que hoja.
 */
function alNoPoderDibujarla(
  lanzado: unknown,
  t: (clave: string, datos?: Readonly<Record<string, unknown>>) => string,
): Ausencia {
  return {
    enElCampo: 'fallo',
    explicacion: t(FRASE_DE_LA_HOJA_QUE_NO_SE_DIBUJA, {
      motivo: lanzado instanceof Error ? lanzado.message : String(lanzado),
    }),
    // `atencion`: aqui si se rompio algo, y es de este arbol.
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

/**
 * **Lo que dice una pantalla CONECTADA, arriba y una sola vez** (#97).
 *
 * Va aqui y no escrito dentro del `return` para que entre en el inventario del locale: el
 * interprete lo pasa por `traducir`, o sea que es una clave, y **una clave que nadie lista nadie la
 * echa de menos** (#103). Ver `i18n/catalogo-de-claves.ts`.
 */
const NO_PUBLICADO_EN_PANTALLA: Ausencia = {
  enElCampo: 'no publicado',
  explicacion:
    'Esta pantalla lee del sistema. Los campos marcados «no publicado» los pide y la operacion ' +
    'que los sirve no los trae: no se calculan aqui, porque un numero deducido seria ' +
    'indistinguible de uno real.',
  tono: 'info',
};

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
   * `enabled` acotado a `exigeEjercicio` es lo que hace que ESTE gancho no pida la sesion para las
   * otras 39 hojas: no la necesitan. Desde #356 la aplicacion montada la pide de todos modos —la
   * barra dice quien ha entrado, `useCabeceraDeLaSesion`—, y por eso la siembra de #114 la siembra:
   * sin eso, su **cero peticiones a `/seguridad/`** saldria rojo.
   *
   * Va por `useQuery` y no por una lectura suelta porque asi **se comparte**: la llave es de la
   * rama `seguridad`, o sea que la barra y cualquier hoja que exija ejercicio leen **la misma
   * respuesta**, pedida una sola vez.
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
   *
   * **Y el fallo de la sesion solo cuenta para quien la pide** (#356). `enabled` no basta: la
   * consulta es de la llave `LLAVES.sesion`, y desde #356 la pide tambien la barra en todas las
   * pantallas, asi que su error llega a este observador aunque ESTE no la haya pedido. Sin acotarlo,
   * una sesion que no contesta tumbaba las 39 hojas que no la necesitan — medido: `con-panel`, con
   * su ficha contestada, decia «No se encontro lo solicitado (404)» por el 404 de la sesion.
   */
  if (pideLaSesion && sesion.isError) return { ausencia: alFallar(sesion.error, t) };
  if (pideLaSesion && sesion.isPending) return { ausencia: CARGANDO };
  if (faltaElEjercicio) return { ausencia: SIN_EJERCICIO };

  if (consulta.isPending) return { ausencia: CARGANDO };
  /*
   * **El 404 lo puede decir el conector con sus palabras** (#237).
   *
   * Desde #283 `alFallar` ya no lo redacta como «fallo (404)»: el peldano `no-encontrado` de la
   * escalera dice que la cuenta puede ser valida en el emisor y no estar dada de alta aqui. Eso
   * sirve para la cadena de identidad y **no** para la lectura del predial, donde 404 es «ese
   * codigo no esta en el padron» y 204 «esta y todavia no se le determino». El backend los publica
   * distintos a proposito (#546); decirlos igual aqui tiraria la mitad de esa decision en el
   * ultimo paso.
   */
  if (consulta.isError) {
    const suyo =
      consulta.error instanceof ErrorDeLaApi && consulta.error.estado === 404
        ? conector.noEncontrado
        : undefined;
    return { ausencia: suyo ?? alFallar(consulta.error, t) };
  }
  // Y el vacio tambien: `null` es lo que devuelve un 204 —o una relacion sin ninguna fila—, y
  // «todavia no se ha determinado» no es «no hay nada que ensenar».
  if (consulta.data === null || consulta.data === undefined) {
    return { ausencia: conector.sinDato ?? VACIO };
  }

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
    //
    // **Y de cuando son las cifras, cuando la operacion lo dice** (#196, regla 9 / RNF-075). La
    // frase se arma AQUI y no en el conector porque aqui hay `t()` y alli no: un «al» escrito en un
    // archivo de datos llegaria al DOM en castellano en cualquier idioma (#103). Se concatenan dos
    // frases enteras y no media —cada una se traduce sola—, y por eso las dos van ya traducidas: lo
    // que el interprete reciba entonces no es una clave, y su `traducir` lo devuelve tal cual.
    //
    // **Y desde #239 tambien DE QUIEN es lo que se dibuja**, por el mismo canal y por el mismo
    // motivo: las hojas que toman «la primera de la relacion» no tienen en el artboard donde
    // decirlo —en `fis-actas` el sitio del titular es un MANDO—, y anadirles una celda seria
    // cambiar el diseno para que quepa un dato.
    //
    // Los trozos se arman en una lista y se juntan, en vez de anidar dos ternarios: con dos
    // canales opcionales son cuatro combinaciones, y la que lleva los dos no la escribiria nadie.
    ausencia: conFrasesDePantalla(reparto, t),
  };
}

/**
 * **La frase de pantalla, con lo que la operacion haya dicho de mas** (#196, #239).
 *
 * Cada trozo es una frase ENTERA y ya traducida —nunca media—: lo que el interprete recibe entonces
 * no es una clave, y su `traducir` lo devuelve tal cual. Partirlas decidiria por el traductor donde
 * cae el dato, y por eso los datos entran por interpolacion.
 */
function conFrasesDePantalla(
  reparto: Reparto,
  t: (clave: string, datos?: Readonly<Record<string, unknown>>) => string,
): Ausencia {
  const trozos = [t(NO_PUBLICADO_EN_PANTALLA.explicacion)];
  if (reparto.aLaFecha !== undefined) {
    trozos.push(t(FRASE_DE_LA_FECHA, { fecha: formatearFecha(reparto.aLaFecha) }));
  }
  if (reparto.deQuienEs !== undefined) trozos.push(deQuienEs(reparto.deQuienEs, t));
  // Un trozo entero que la operacion no trae, con su motivo (#237). Es una clave, no una frase.
  if (reparto.loQueLaOperacionNoTrae !== undefined) trozos.push(t(reparto.loQueLaOperacionNoTrae));
  if (trozos.length === 1) return NO_PUBLICADO_EN_PANTALLA;
  return { ...NO_PUBLICADO_EN_PANTALLA, explicacion: trozos.join(' ') };
}

/**
 * **De quien es lo que se dibuja, o que ya no esta en el padron** (#239).
 *
 * Los dos campos llegan nulos **a la vez**, y eso no es un hueco del contrato: es un hecho que
 * #216 publica a proposito —el acta sigue saliendo porque ocultarla esconderia justo el caso que
 * hay que revisar—. Con la frase de arriba se leeria «es de undefined (undefined)».
 */
function deQuienEs(
  quien: DeQuienEs,
  t: (clave: string, datos?: Readonly<Record<string, unknown>>) => string,
): string {
  if (quien.nombre === null || quien.codigo === null) return t(FRASE_DE_QUIEN_ES_SIN_PADRON);
  // Ni el nombre ni el codigo pasan por `traducir`: son dato, como una celda.
  return t(FRASE_DE_QUIEN_ES, { nombre: quien.nombre, codigo: quien.codigo });
}

export {
  CARGANDO,
  NADA,
  NO_PUBLICADO_EN_PANTALLA,
  SIN_EJERCICIO,
  SIN_SUJETO,
  VACIO,
  alFallar,
  alNoPoderDibujarla,
};

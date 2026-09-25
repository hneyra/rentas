import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Armazon, useHoja, type AccionesDelSistema } from '@kamayuk/shell';
import { Alerta, Boton, ProveedorDeTema, type ConfiguracionDeTema } from '@kamayuk/ui';

import escudo from '../diseno/escudo-catacaos.png';
import { MandoDeTema } from './preferencias/MandoDeTema.tsx';
import { useCatalogoPermitido, type CatalogoDeLaSesion } from './datos/useCatalogoPermitido.ts';
import { traducirCatalogo } from './catalogo.ts';
import { PantallaDeRentas } from './pantallas/PantallaDeRentas.tsx';
import type { ClaveDeHoja } from './pantallas/arbol.ts';
import { pantallaDe } from './pantallas/definiciones/index.ts';
import { alNoPoderDibujarla, useDatosDeLaHoja } from './datos/useDatosDeLaHoja.ts';
import { FronteraDeLaHoja } from './pantallas/FronteraDeLaHoja.tsx';
import type { FallaDeLaPuerta, VueltaFallida } from './api/identidad.ts';
import { abrirLaCuenta, salir } from './api/identidad.ts';
import { fallaDeLaPuerta, vueltaFallida } from './arranque.ts';
import { useTextosDelMarco } from './i18n/textosDelMarco.ts';

/**
 * **`rentas-web`, sobre el artboard V8** (#90).
 *
 * <h2>Que hay aqui, y que NO</h2>
 *
 * Aqui hay **la costura**: el catalogo de este sistema, la entidad, la cuenta y que hace cada
 * accion del pie. Nada mas. El marco lo dibuja `@kamayuk/shell` —que no sabe que existe Rentas— y
 * el cuerpo de cada pantalla lo dibuja el interprete de `@kamayuk/ui` desde su definicion (#153).
 *
 * Es la forma que ADR-0030 §4 pide, y se nota en el tamano de este archivo: **la aplicacion de un
 * sistema es una lista de decisiones, no una interfaz**. Cuando `catastro` se reconstruya, su
 * archivo equivalente sera igual de corto y su contenido sera otro.
 *
 * <h2>Lo que todavia NO hace, dicho aqui y no descubierto luego</h2>
 *
 * · **Pedir datos en las pantallas que no tienen backend.** Solo unas pocas de las cuarenta piden
 *   de verdad; el resto dice por que no. Cuantas son lo dice el registro `CONECTORES` de
 *   `datos/conectores.ts`, y por que cada una si o no, el javadoc de su modulo en
 *   `datos/conectores/` — campo a campo, que es donde se ve que «servida» no es «puede pintarse».
 * · **Las acciones del pie hacen lo minimo honesto**: imprimir imprime, y las otras tres avisan
 *   de que no escriben todavia. Un boton que no dice nada al pulsarlo se lee como una pantalla
 *   rota; uno que dice lo que hace —y lo que no— se lee como una pantalla a medio conectar, que
 *   es lo que es.
 *
 * Y **una entrada se fue de esta lista**, que es lo que hay que anotar en vez de borrarla (#136):
 * *filtrar el catalogo por permisos*. Decia que el armazon lo recibe ya filtrado pero que aqui se
 * le pasaba entero, que la V6 lo filtraba desde I-3 con `GET /seguridad/accesos` y que aquello
 * volveria cuando la sesion se conectase a las pantallas nuevas. **Volvio, en #105**: hoy lo
 * compone `datos/useCatalogoPermitido.ts` con las tres de seguridad, y lo que la cuenta no puede
 * abrir no se ofrece ni se abre por su hash — medido en
 * `verificaciones/los-cuarenta-destinos-se-recorren.test.tsx`.
 *
 * <h2>El menu de sesion: las cuatro opciones hacen algo, y dos de ellas se van de aqui</h2>
 *
 * Es el mismo criterio de la linea de arriba, aplicado al otro sitio donde habia botones mudos
 * (#115). Hasta este issue tres de las cuatro eran `al: () => {}`; hoy no queda ninguna, y lo
 * vigila `verificaciones/ninguna-opcion-del-menu-se-queda-muda.test.ts`.
 *
 *     Mi perfil               -> la consola de cuenta del EMISOR, en otra pestana
 *     Cambiar la contrasena   -> la misma consola, en su pagina de credenciales
 *     Preferencias            -> el cajon de los temas (#111)
 *     Cerrar sesion           -> `salir()`
 *
 * Las dos primeras **no se resuelven aqui a proposito**, y no por falta de backend: la
 * autorizacion es de `identidad` desde ADR-0039 y la contrasena la guarda Keycloak, que ya publica
 * su propia pagina de cuenta. Dibujar aqui esos dos formularios seria prometer una escritura que
 * ningun backend de este repositorio puede atender — y por eso el issue lo deja fuera por escrito.
 * A donde llevan, y con que se midio, esta en `api/identidad.ts`.
 */

const ENTIDAD = 'Municipalidad Distrital de Catacaos';

/**
 * **El tema de este servicio** (#111).
 *
 * Dos decisiones y ninguna mas; las seis paletas son de `@kamayuk/ui` y valen para los cuatro
 * sistemas.
 *
 * · `identidadPorOmision`: con que se ve Rentas para quien no ha elegido nada. `institucional` es
 *   la del artboard V8, o sea exactamente lo que se servia antes de este issue.
 * · `prefijoDeClaves`: lo mismo que pide `@kamayuk/sesion`, y por lo mismo. Las cuatro interfaces
 *   del producto se sirven **del mismo origen** —`/rentas/`, `/caja/`, `/catastro/`,
 *   `/normativa/`—, asi que comparten el almacenamiento del navegador: sin prefijo propio, cambiar
 *   el tema aqui se lo cambiaria a las otras tres.
 *
 * **Quien guarda es la libreria, no este archivo**, y eso es lo que hace que siga siendo cierto que
 * un solo archivo de produccion de este repositorio toca el almacenamiento del navegador: la
 * puerta. Lo comprueba `verificaciones/camino-a-la-api.test.ts`.
 *
 * **Y el modo no se declara**, que es la tercera decision y va por omision: ausente significa «el
 * del equipo». Traer aqui un `claro` de fabrica congelaria en claro a quien tenga la maquina en
 * oscuro, que es lo contrario de lo que pide quien la puso asi.
 */
const TEMA: ConfiguracionDeTema = {
  identidadPorOmision: 'institucional',
  prefijoDeClaves: 'kamayuk.rentas',
};

/**
 * Que hace cada accion del pie.
 *
 * `imprimir` es la unica que puede hacer su trabajo entero sin backend, asi que lo hace. Las
 * otras tres dicen que les falta: ver `avisos.ts` para el texto que acompana a los botones.
 */
/**
 * El cliente de consultas, **creado una vez y fuera del componente**.
 *
 * Dentro se crearia uno nuevo en cada pintada, y con el se tiraria la cache entera: cada vuelta al
 * mismo destino volveria a pedir. Fuera, volver a una pantalla ya vista la ensena mientras
 * refresca.
 *
 * `retry` en falso tambien aqui, ademas de en el gancho: un 401 reintentado tres veces son tres
 * idas a un backend que ya dijo que no, y el usuario espera el triple para leer lo mismo.
 *
 * **Se exporta por las pruebas, y eso dice algo de el.** Al ser de modulo, su cache **sobrevive a
 * cada `render`**: en la aplicacion es justo lo que se quiere —volver a una pantalla ya vista la
 * ensena mientras refresca—, y en una suite significa que una prueba hereda lo que cacheo la
 * anterior. Medido: una prueba que cambiaba los permisos leia los de la prueba de antes y salia
 * verde sobre el catalogo equivocado. Quien monta la aplicacion en una prueba tiene que llamar a
 * `CONSULTAS.clear()`.
 */
export const CONSULTAS = new QueryClient({
  defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } },
});

const ACCIONES: AccionesDelSistema = {
  imprimir: () => {
    window.print();
  },
};

/** El cuerpo de una pantalla: su definicion, y lo que se sepa de sus datos. */
function CuerpoDeLaPantalla({ clave }: { readonly clave: ClaveDeHoja }) {
  // Un componente y no una funcion suelta: `useDatosDeLaHoja` es un gancho, y un gancho solo puede
  // llamarse desde un componente. Ademas esto es lo que hace que **solo se vuelva a pintar la
  // pantalla** cuando llega su respuesta, y no el armazon entero.
  //
  // **El sujeto sale de la RUTA** (#169): las dos hojas de Consultas que piden son de un
  // contribuyente concreto, y su codigo va en la direccion —`#/con-panel/00000025673`—. Se lee del
  // marco y no de `window.location` porque el marco ya lo descodifica y **solo entrega lo que la
  // hoja declara** en su `enLaRuta`; lo que llegue sin declarar se ignora con aviso. Las 38 hojas
  // que no lo declaran reciben `null` y no cambian en nada.
  //
  // **Y desde #172 lo que se le pasa es la ruta ENTERA**, no solo su sujeto: los mandos de una
  // tabla paginada escriben la pagina y el campo de orden **en la ruta de la hoja** y no piden
  // nada (`MandosDeLaTabla.tsx` de `@kamayuk/ui`), asi que quien pide tiene que leerla. Con solo
  // el sujeto, pulsar «Siguiente» movia la direccion y nadie volvia a pedir.
  const hoja = useHoja();
  return (
    // `hoja` es ademas lo que el interprete necesita para ESCRIBIR ahi: sin ella, la tabla guarda
    // la pagina en su propio estado —no sobrevive a recargar y, peor, no llega al conector—.
    <PantallaDeRentas
      definicion={pantallaDe(clave)}
      datos={useDatosDeLaHoja(clave, hoja.ruta)}
      hoja={hoja}
    />
  );
}

/**
 * **El cuerpo de la hoja, dentro de su frontera** (#354).
 *
 * Lo que la hoja lance al dibujarse —un conector que recibe una forma que no esperaba, el
 * interprete, una pieza— se queda en ella: la barra, el arbol y el pie siguen en pie y el cuerpo
 * dice «fallo» con su motivo. Sin esto, react-router lo recogia con su pantalla por omision,
 * «Unexpected Application Error!», en ingles y en lugar del armazon entero. Ver
 * `pantallas/FronteraDeLaHoja.tsx`.
 *
 * <h2>Dos fronteras y no una, y el motivo es el interprete</h2>
 *
 * Lo primero que se intenta en lugar de la hoja es **la misma pantalla con una ausencia**: su
 * titulo, sus bloques y cada hueco diciendo «fallo», que es como dice cualquier otra hoja que no
 * tiene su dato. Pero si lo que lanzo fue el interprete con esa definicion, dibujarla otra vez
 * lanzaria lo mismo, y un error dentro de lo que dibuja una frontera ya no lo recoge ella: sube a
 * la siguiente, que es la de react-router. La segunda frontera es para eso, y lo que dibuja no
 * depende de la definicion: la frase sola.
 *
 * `reinicio` es la hoja y su ruta: ver el javadoc de la frontera para por que no es una `key`.
 */
function HojaConFrontera({ clave }: { readonly clave: ClaveDeHoja }) {
  const { t } = useTranslation();
  const hoja = useHoja();
  const reinicio = `${clave}|${JSON.stringify(hoja.ruta)}`;

  return (
    <FronteraDeLaHoja
      reinicio={reinicio}
      enSuLugar={(lanzado) => (
        <FronteraDeLaHoja
          reinicio={reinicio}
          enSuLugar={(otraVez) => (
            <div data-slot="hoja-sin-dibujar" className="p-[30px]">
              <Alerta tono="atencion">
                <p className="m-0">{alNoPoderDibujarla(otraVez, t).explicacion}</p>
              </Alerta>
            </div>
          )}
        >
          <PantallaDeRentas
            definicion={pantallaDe(clave)}
            datos={{ ausencia: alNoPoderDibujarla(lanzado, t) }}
            hoja={hoja}
          />
        </FronteraDeLaHoja>
      )}
    >
      <CuerpoDeLaPantalla clave={clave} />
    </FronteraDeLaHoja>
  );
}

/**
 * El armazon y lo que lo alimenta.
 *
 * Va **dentro** del proveedor y no fuera, y no es un detalle de orden: `useCatalogoPermitido` es un
 * gancho de consulta, y un gancho corre **antes** que el JSX del componente que lo llama. Con las
 * dos cosas en la misma funcion, el gancho se ejecutaba antes de que el proveedor existiera y
 * reventaba con «No QueryClient set, use QueryClientProvider to set one» — en las cuarenta pruebas
 * del recorrido a la vez.
 */
function ArmazonDelSistema({ vuelta }: { readonly vuelta: VueltaFallida | null }) {
  const { t } = useTranslation();
  // Las treinta y dos palabras que el marco dice por su cuenta, en el idioma de la sesion (#133).
  // Sin esto el armazon usa las suyas por omision y la pantalla sale a medias: el cuerpo
  // traducido y el marco en castellano. Ver `i18n/textosDelMarco.ts`.
  const textos = useTextosDelMarco();
  const sesion = useCatalogoPermitido();
  const catalogo = traducirCatalogo(sesion.catalogo, t);
  // El cajon de preferencias: lo abre la opcion del menu de sesion y nada mas. Vive aqui —y no
  // dentro del `<Armazon>`— porque el armazon no sabe que existe un tema: lo suyo es ofrecer la
  // opcion y avisar de que se pulso.
  const [preferencias, setPreferencias] = useState(false);

  /*
   * **No se monta el armazon hasta saber que puede abrir la cuenta.** Se queda, y por lo que se
   * midio al revisarlo (#136) — no por lo que decia antes.
   *
   * **El defecto ajeno ya no lo sostiene.** Hasta #136 aqui ponia que `@kamayuk/shell` revienta si
   * su catalogo cambia despues de montar —`useHoja() fuera de una pantalla del <Armazon>`— y que
   * por eso esto «hoy ademas es necesario». `kamayuk-lib`#20 esta **cerrado**: lo arreglan
   * `6e7aa0b` y `e13c5aa`, con sus pruebas en `paquetes/shell/armazon.test.tsx`. Y esta medido
   * desde aqui: con este rodeo retirado y el catalogo llegando tarde, el armazon **no revienta** y
   * la pantalla del enlace profundo abre.
   *
   * **Y el primer motivo, tal como estaba escrito, tampoco lo sostiene ya.** Decia que ofrecer «el
   * catalogo entero mientras llega» ensenaria un segundo lo que #105 esconde. Eso no puede pasar,
   * y no es este rodeo quien lo impide: quien lo impide es `useCatalogoPermitido`, que mientras
   * pide devuelve el catalogo **vacio** — retirado el rodeo, el arbol no nombra ni un modulo.
   *
   * **Lo que si lo sostiene es la mentira contraria, y esa esta medida.** Montar el armazon con el
   * catalogo vacio hace que un enlace profundo —`#/tra-pap`, con las tres de seguridad todavia en
   * vuelo— dibuje «Esa direccion no corresponde a ningun destino disponible para esta cuenta». Se
   * lo dice a una cuenta que SI puede abrirla, y una negativa de permisos no se lee como una
   * espera: se lee como un no. Con el rodeo, lo que se lee es «Averiguando que puede abrir esta
   * cuenta», que es exactamente lo que esta pasando.
   *
   * Y no hay que fiarse de esta nota: retirar el rodeo pone en rojo **45 pruebas** de
   * `los-cuarenta-destinos-se-recorren` y `la-siembra-abre-los-destinos`, y el volcado del rojo es
   * literalmente ese `data-slot="destino-no-ofrecido"`.
   */
  if (sesion.estado === 'sin-privilegio') {
    return <FaltanOpcionesParaLeerElCatalogo sesion={sesion} />;
  }

  // El 401 con su remedio (#355). Antes de la rama generica de abajo, que era donde caia: un
  // parrafo suelto sin nada que pulsar, y la pestana sin forma de volver a entrar.
  if (sesion.volverAEntrar !== null) {
    return (
      <HayQueVolverAIdentificarse
        porQue={sesion.porQue}
        volverAEntrar={sesion.volverAEntrar}
        vuelta={vuelta}
      />
    );
  }

  if (sesion.estado !== 'compuesto') {
    return (
      <div className="grid min-h-screen place-items-center p-[30px] bg-fondo">
        <p className="m-0 max-w-[52ch] text-center text-[14px] leading-[1.6] text-tinta-2 text-pretty">
          {sesion.porQue}
        </p>
      </div>
    );
  }

  return (
    <>
      <Armazon
        textos={textos}
        titulo={t('Rentas')}
        entidad={t(ENTIDAD)}
        escudo={<img src={escudo} alt="" width={28} height={28} />}
        catalogo={catalogo}
        cuenta={{ nombre: 'J. Cardenas Vega', iniciales: 'JC', nota: t(ENTIDAD) }}
        opcionesDeSesion={[
          {
            rotulo: t('Mi perfil'),
            al: () => {
              abrirLaCuenta('perfil');
            },
          },
          {
            rotulo: t('Cambiar la contrasena'),
            al: () => {
              abrirLaCuenta('contrasena');
            },
          },
          {
            rotulo: t('Preferencias'),
            al: () => {
              setPreferencias(true);
            },
          },
          { rotulo: t('Cerrar sesion'), peligrosa: true, al: () => void salir() },
        ]}
        acciones={ACCIONES}
        // Cuando no hay arbol, el pie del carril dice POR QUE: sin eso, «pidiendo», «fallo» y «esta
        // cuenta no puede abrir nada» son la misma pantalla en blanco, y son tres cosas distintas.
        //
        // Con el rodeo de arriba puesto, la rama de `porQue` **no se alcanza**: aqui el estado es
        // siempre `compuesto` y `porQue` siempre vacio. Se deja escrita porque es la que recoge
        // los tres casos el dia que el rodeo se retire, y borrarla haria que retirarlo saliera en
        // blanco en vez de explicado.
        pieDelCarril={
          sesion.porQue === ''
            ? t('Diez modulos y cuarenta submodulos. Catastro y Tesoreria son de otros sistemas.')
            : sesion.porQue
        }
        pantalla={(hoja) => <HojaConFrontera clave={hoja.destino.clave as ClaveDeHoja} />}
      />
      <MandoDeTema
        abierto={preferencias}
        alCerrar={() => {
          setPreferencias(false);
        }}
      />
    </>
  );
}

/**
 * **A la cuenta le faltan las opciones con que se lee el catalogo** (#311, el AC7 de #33).
 *
 * «Cada cinco minutos» no es una estimacion: es la ventana del `CronJob` que corre el consumidor
 * del buzon de `identidad` (`VENTANA_DEL_CONSUMIDOR_DE_IDENTIDAD` en
 * `infrastructure/src/descriptor.ts`). Por eso el boton no se pulsa una vez y se abandona.
 *
 * `GET /seguridad/{modulos,accesos}` contesto 403 `SIN_PRIVILEGIO`, y sin esas dos lecturas no
 * hay arbol. Se dice **que opciones faltan, por su nombre del catalogo** —el dato con que quien
 * administra los perfiles la encuentra— y se ofrece **reintentar**, que aqui si arregla: el
 * guardia comprueba cada peticion contra la base (ADR-0013), asi que en cuanto la opcion llega a
 * este sistema la siguiente peticion pasa, sin cerrar la sesion.
 *
 * Tono `info` y no `mal`: el sistema contesto lo que tenia que contestar. Pintarlo de averia
 * manda a mirar un despliegue cuando lo que falta es una fila en una tabla de permisos — el mismo
 * criterio que `esAveria` de `api/escalera.ts` desde #283.
 *
 * Lo que de verdad lo cerraria no es de este lado: que `seguridad` publique **el menu de la
 * sesion**, el catalogo ya filtrado por quien pregunta, que no pida una opcion de administracion.
 */
function FaltanOpcionesParaLeerElCatalogo({ sesion }: { readonly sesion: CatalogoDeLaSesion }) {
  const { t } = useTranslation();
  const reintentar = sesion.reintentar;

  return (
    <div className="grid min-h-screen place-items-center p-[30px] bg-fondo">
      <div data-slot="catalogo-sin-privilegio" className="max-w-[64ch]">
        <Alerta tono="info" titulo={t('A esta cuenta le faltan opciones para ver sus modulos')}>
          <p className="m-0">{sesion.porQue}</p>
          <ul className="mt-[10px] mb-0 pl-[20px] list-disc">
            {sesion.faltan.map((nombre) => (
              <li key={nombre} className="font-bold">
                {t(nombre)}
              </li>
            ))}
          </ul>
          <p className="mt-[10px] mb-0">
            {t(
              'Cuando se las den, pulse Reintentar: no hace falta volver a entrar. El permiso se ' +
                'da en identidad, y este sistema lo recoge cada cinco minutos.',
            )}
          </p>
        </Alerta>
        {reintentar === null ? null : (
          <Boton
            variante="primario"
            className="mt-[14px]"
            disabled={sesion.reintentando}
            onClick={reintentar}
          >
            {t('Reintentar')}
          </Boton>
        )}
      </div>
    </div>
  );
}

/**
 * **La sesion no vale, y se ofrece volver a identificarse** (#355).
 *
 * <h2>De que defecto viene</h2>
 *
 * Tras «Cerrar sesion» la marca de salida impide que el arranque vuelva a entrar solo —y es
 * correcto—, asi que se monta, las tres lecturas del catalogo contestan 401 y hasta #355 lo que se
 * dibujaba era un parrafo que decia «Vuelva a entrar.» **sin nada que pulsar**. F5 repetia lo mismo,
 * porque la marca vive lo que la pestana: cada cambio de turno dejaba la pestana inservible. El
 * boton que la levantaba se fue con `Puerta.tsx` en #90; vuelve aqui, con el remedio decidido en
 * `useCatalogoPermitido` y no en este archivo.
 *
 * <h2>Si lo que freno fue un canje fallido, se dice el motivo del emisor</h2>
 *
 * Con el tope de idas agotado el 401 es consecuencia, no causa: lo que paso es que el emisor
 * rechazo la vuelta —un `redirect_uri` mal declarado, un codigo ya usado, «La vuelta no cuadra con
 * la ida»—. Su `motivo` y su `explicacion` **sustituyen** a la frase generica: con las dos, la que
 * se lee primero es la que no dice nada de lo que paso. Es el unico diagnostico de una
 * configuracion equivocada, y hasta #355 se tiraba en el arranque.
 *
 * **Cada frase es su propia clave de `t()`** (ronda 1): el motivo es el titulo y la explicacion el
 * cuerpo, y **solo** lo que escribio el emisor (`delEmisor`) se le atribuye a el. La primera
 * version metia el motivo como valor de «…no dejo terminar la entrada: {{motivo}}.» —que el
 * marcador de #103 no ve, y que con «No se completo la entrada» decia dos veces lo mismo— y
 * presentaba cualquier detalle como «Lo que contesto», tambien los que escribia este sistema.
 *
 * <h2>Y si al pulsar el emisor no contesta, se explica como desde #112</h2>
 *
 * `entrar()` pregunta primero si el emisor esta; si no, devuelve la falla y no navega. Un boton
 * que en ese caso no hiciera nada seria el `al: () => {}` de #115 con otra forma, asi que la falla
 * se ensena con la misma pantalla que ensena el arranque.
 *
 * <h2>Y si la ida REVIENTA, el boton vuelve (ronda 1)</h2>
 *
 * `entrar()` tambien puede rechazar: escribe en el almacenamiento de la pestana —que lanza lleno
 * o bloqueado— y calcula el reto con `crypto.subtle`. El boton se deshabilita al pulsar, y sin
 * atender el rechazo se quedaba deshabilitado para siempre: otra vez una pestana sin nada que
 * pulsar, que es lo que #355 cierra. Se vuelve a habilitar y se dice lo que dijo el navegador, en
 * sus palabras.
 */
function HayQueVolverAIdentificarse({
  porQue,
  volverAEntrar,
  vuelta,
}: {
  readonly porQue: string;
  readonly volverAEntrar: () => Promise<FallaDeLaPuerta | null>;
  readonly vuelta: VueltaFallida | null;
}) {
  const { t } = useTranslation();
  const [falla, setFalla] = useState<FallaDeLaPuerta | null>(null);
  // La sonda puede tardar hasta ocho segundos: mientras, el boton no se ofrece otra vez.
  const [yendo, setYendo] = useState(false);
  // Lo que dijo el navegador si la ida revento antes de salir. Ver el javadoc.
  const [reventon, setReventon] = useState<string | null>(null);

  if (falla !== null) return <LaPuertaNoContesto falla={falla} />;

  return (
    <div className="grid min-h-screen place-items-center p-[30px] bg-fondo">
      <div data-slot="hay-que-volver-a-identificarse" className="max-w-[64ch]">
        {vuelta === null ? (
          <Alerta tono="info">
            <p className="m-0">{porQue}</p>
          </Alerta>
        ) : (
          <Alerta tono="atencion" titulo={t(vuelta.motivo)}>
            <p className="m-0 break-words">{t(vuelta.explicacion, vuelta.valores)}</p>
            {vuelta.delEmisor === null ? null : (
              <p className="mt-[6px] mb-0 break-words">
                {t('Lo que dijo el emisor: «{{texto}}»', { texto: vuelta.delEmisor })}
              </p>
            )}
          </Alerta>
        )}
        {reventon === null ? null : (
          <Alerta tono="mal" className="mt-[14px]">
            <p className="m-0 break-words">
              {t('No se pudo salir hacia el emisor de identidad. El navegador dijo: «{{motivo}}».', {
                motivo: reventon,
              })}
            </p>
          </Alerta>
        )}
        <Boton
          variante="primario"
          className="mt-[14px]"
          disabled={yendo}
          onClick={() => {
            setYendo(true);
            setReventon(null);
            void volverAEntrar().then(
              (otra) => {
                // `null` es que el navegador se va: no hay nada que volver a dibujar.
                if (otra !== null) {
                  setFalla(otra);
                  setYendo(false);
                }
              },
              (error: unknown) => {
                setReventon(enPalabrasDelNavegador(error));
                setYendo(false);
              },
            );
          }}
        >
          {t('Volver a identificarse')}
        </Boton>
      </div>
    </div>
  );
}

/**
 * Lo que dijo el navegador al rechazar la ida, tal cual: son las palabras que se pueden buscar y
 * las que salen en su consola. `DOMException` no siempre hereda de `Error`, por eso se mira la
 * forma y no la clase.
 */
function enPalabrasDelNavegador(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const { message, name } = error as { readonly message?: unknown; readonly name?: unknown };
    if (typeof message === 'string' && message !== '') return message;
    if (typeof name === 'string' && name !== '') return name;
  }
  return String(error);
}

/**
 * **Cuando no se pudo ni llegar al emisor de identidad** (#112).
 *
 * <h2>Por que esto se dibuja ANTES que nada, y no como un estado mas del catalogo</h2>
 *
 * Porque con el emisor caido el backend suele estar caido tambien, y entonces
 * `useCatalogoPermitido` contesta su «No se pudo saber que modulos puede abrir esta cuenta». Es
 * cierto y es inutil: manda a mirar los permisos cuando lo que pasa es que la plataforma no esta.
 * La falla de la puerta es mas honda que la del catalogo, asi que gana.
 *
 * <h2>Y por que nombra la URL</h2>
 *
 * Porque las tres causas de que no se pueda llegar —la plataforma sin levantar, un `ConfigMap` con
 * la URL equivocada y un cortafuegos— se distinguen leyendo **que URL** se pidio. Sin ella las
 * tres son «no conecta», y las tres se arreglan en sitios distintos.
 */
function LaPuertaNoContesto({ falla }: { readonly falla: FallaDeLaPuerta }) {
  const { t } = useTranslation();

  return (
    <div className="grid min-h-screen place-items-center p-[30px] bg-fondo">
      <div className="max-w-[64ch] border border-mal-borde bg-mal-fondo p-[20px] text-[14px] leading-[1.6]">
        <p className="m-0 font-bold text-mal-tinta">
          {t('No se pudo llegar al emisor de identidad, asi que no se mando a nadie a identificarse.')}
        </p>
        <p className="mt-[10px] mb-0 break-all text-tinta-2">
          {t('El emisor es {{emisor}}, y la peticion a {{url}} no llego a completarse: {{motivo}}.', {
            emisor: falla.emisor,
            url: falla.url,
            motivo: falla.motivo,
          })}
        </p>
        <p className="mt-[10px] mb-0 text-tinta-2 text-pretty">
          {t(
            'Si esto es un puesto de desarrollo, levante la plataforma; si no, avise a quien la ' +
              'administra. Despues vuelva a cargar la pagina.',
          )}
        </p>
      </div>
    </div>
  );
}

/**
 * **El proveedor del tema envuelve TODO, incluida la pantalla de la puerta caida** (#111).
 *
 * Podria envolver solo al armazon y seria mas corto. Seria tambien un fallo visible: quien eligio
 * sepia u oscuro y se encuentra el emisor caido leeria ese aviso —el unico momento en que la
 * interfaz de verdad no esta— con la paleta de otro. La pantalla que explica una averia es
 * exactamente la que no debe parecer de otro programa.
 *
 * Y va por fuera del `QueryClientProvider` porque no depende de el: el tema se resuelve del
 * navegador, no de la red, y no tiene que esperar a nada.
 */
export function Aplicacion() {
  // Se lee aqui y no en `main.tsx` porque el montaje no lleva argumentos a proposito: ver
  // `arranque.ts`. Al llegar aqui la pasada de arranque ya termino, asi que el valor esta fijo.
  const falla = fallaDeLaPuerta();
  // Y por lo mismo la vuelta fallida del emisor (#355): la dice la rama del 401, si se llega a ella.
  const vuelta = vueltaFallida();

  return (
    <ProveedorDeTema configuracion={TEMA}>
      {falla !== null ? (
        <LaPuertaNoContesto falla={falla} />
      ) : (
        <QueryClientProvider client={CONSULTAS}>
          <ArmazonDelSistema vuelta={vuelta} />
        </QueryClientProvider>
      )}
    </ProveedorDeTema>
  );
}

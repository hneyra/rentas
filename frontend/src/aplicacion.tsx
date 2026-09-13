import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Armazon, type AccionesDelSistema } from '@kamayuk/shell';

import escudo from '../diseno/escudo-catacaos.png';
import { useCatalogoPermitido } from './datos/useCatalogoPermitido.ts';
import { traducirCatalogo } from './catalogo.ts';
import { Pantalla } from './pantallas/Pantalla.tsx';
import type { ClaveDeHoja } from './pantallas/arbol.ts';
import { pantallaDe } from './pantallas/definiciones/index.ts';
import { useDatosDeLaHoja } from './datos/useDatosDeLaHoja.ts';
import { salir } from './api/identidad.ts';

/**
 * **`rentas-web`, sobre el artboard V8** (#90).
 *
 * <h2>Que hay aqui, y que NO</h2>
 *
 * Aqui hay **la costura**: el catalogo de este sistema, la entidad, la cuenta y que hace cada
 * accion del pie. Nada mas. El marco lo dibuja `@kamayuk/shell` —que no sabe que existe Rentas— y
 * el cuerpo de cada pantalla lo dibuja el interprete desde su definicion.
 *
 * Es la forma que ADR-0030 §4 pide, y se nota en el tamano de este archivo: **la aplicacion de un
 * sistema es una lista de decisiones, no una interfaz**. Cuando `catastro` se reconstruya, su
 * archivo equivalente sera igual de corto y su contenido sera otro.
 *
 * <h2>Lo que todavia NO hace, dicho aqui y no descubierto luego</h2>
 *
 * · **Filtrar el catalogo por permisos.** El armazon lo recibe YA filtrado y hoy se le pasa
 *   entero. La V6 lo filtraba desde I-3 con `GET /seguridad/accesos`, y eso vuelve cuando la
 *   sesion se conecte a las pantallas nuevas — no antes, porque filtrar contra una lista de
 *   permisos sin pantallas que abrir no se puede comprobar.
 * · **Pedir datos en las 38 pantallas que no tienen backend.** Dos de las cuarenta piden de verdad
 *   —`panel` y `coa-panel`—; el resto dice por que no. Ver `datos/conectores.ts`, que cuenta campo
 *   a campo por que «servida» no es «puede pintarse».
 * · **Las acciones del pie hacen lo minimo honesto**: imprimir imprime, y las otras tres avisan
 *   de que no escriben todavia. Un boton que no dice nada al pulsarlo se lee como una pantalla
 *   rota; uno que dice lo que hace —y lo que no— se lee como una pantalla a medio conectar, que
 *   es lo que es.
 */

const ENTIDAD = 'Municipalidad Distrital de Catacaos';

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
  return <Pantalla definicion={pantallaDe(clave)} datos={useDatosDeLaHoja(clave)} />;
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
function ArmazonDelSistema() {
  const { t } = useTranslation();
  const sesion = useCatalogoPermitido();
  const catalogo = traducirCatalogo(sesion.catalogo, t);

  /*
   * **No se monta el armazon hasta saber que puede abrir la cuenta**, y hay dos motivos.
   *
   * El bueno: ofrecer el catalogo entero «mientras llega» ensenaria durante un segundo justo lo que
   * #105 existe para esconder, y un segundo basta para pulsar.
   *
   * El otro es un rodeo declarado: `@kamayuk/shell` **revienta si su catalogo cambia despues de
   * montar** —`useHoja() fuera de una pantalla del <Armazon>`, reproducido en cinco lineas—, y eso
   * es exactamente lo que pasa cuando el catalogo sale de la red. Esta en `kamayuk-lib`#20. Cuando
   * se arregle, esto sigue siendo lo correcto por el primer motivo; hoy ademas es necesario.
   */
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
    <Armazon
      titulo={t('Rentas')}
      entidad={t(ENTIDAD)}
      escudo={<img src={escudo} alt="" width={28} height={28} />}
      catalogo={catalogo}
      cuenta={{ nombre: 'J. Cardenas Vega', iniciales: 'JC', nota: t(ENTIDAD) }}
      opcionesDeSesion={[
        { rotulo: t('Mi perfil'), al: () => {} },
        { rotulo: t('Cambiar la contrasena'), al: () => {} },
        { rotulo: t('Preferencias'), al: () => {} },
        { rotulo: t('Cerrar sesion'), peligrosa: true, al: () => void salir() },
      ]}
      acciones={ACCIONES}
      // Cuando no hay arbol, el pie del carril dice POR QUE: sin eso, «pidiendo», «fallo» y «esta
      // cuenta no puede abrir nada» son la misma pantalla en blanco, y son tres cosas distintas.
      pieDelCarril={
        sesion.porQue === ''
          ? t('Diez modulos y cuarenta submodulos. Catastro y Tesoreria son de otros sistemas.')
          : sesion.porQue
      }
      pantalla={(hoja) => <CuerpoDeLaPantalla clave={hoja.destino.clave as ClaveDeHoja} />}
    />
  );
}

export function Aplicacion() {
  return (
    <QueryClientProvider client={CONSULTAS}>
      <ArmazonDelSistema />
    </QueryClientProvider>
  );
}

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Armazon, type AccionesDelSistema } from '@kamayuk/shell';

import escudo from '../diseno/escudo-catacaos.png';
import { useCatalogo } from './catalogo.ts';
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
 */
const CONSULTAS = new QueryClient({
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

export function Aplicacion() {
  const { t } = useTranslation();
  const catalogo = useCatalogo();
  return (
    <QueryClientProvider client={CONSULTAS}>
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
      pieDelCarril={t('Diez modulos y cuarenta submodulos. Catastro y Tesoreria son de otros sistemas.')}
      pantalla={(hoja) => <CuerpoDeLaPantalla clave={hoja.destino.clave as ClaveDeHoja} />}
    />
    </QueryClientProvider>
  );
}

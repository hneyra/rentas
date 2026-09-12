import { Armazon, type AccionesDelSistema } from '@kamayuk/shell';

import escudo from '../diseno/escudo-catacaos.png';
import { CATALOGO } from './catalogo.ts';
import { Pantalla } from './pantallas/Pantalla.tsx';
import type { ClaveDeHoja } from './pantallas/arbol.ts';
import { pantallaDe } from './pantallas/definiciones/index.ts';
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
 * · **Pedir datos.** Cada pantalla dibuja las cifras de su definicion, que son las del artboard.
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
const ACCIONES: AccionesDelSistema = {
  imprimir: () => {
    window.print();
  },
};

export function Aplicacion() {
  return (
    <Armazon
      titulo="Rentas"
      entidad={ENTIDAD}
      escudo={<img src={escudo} alt="" width={28} height={28} />}
      catalogo={CATALOGO}
      cuenta={{ nombre: 'J. Cardenas Vega', iniciales: 'JC', nota: ENTIDAD }}
      opcionesDeSesion={[
        { rotulo: 'Mi perfil', al: () => {} },
        { rotulo: 'Cambiar la contrasena', al: () => {} },
        { rotulo: 'Preferencias', al: () => {} },
        { rotulo: 'Cerrar sesion', peligrosa: true, al: () => void salir() },
      ]}
      acciones={ACCIONES}
      pieDelCarril="Diez modulos y cuarenta submodulos. Catastro y Tesoreria son de otros sistemas."
      pantalla={(hoja) => <Pantalla definicion={pantallaDe(hoja.destino.clave as ClaveDeHoja)} />}
    />
  );
}

import { useMemo, useReducer } from 'react';

import { peldanoDe } from './api/escalera.ts';
import { entrar, olvidarLaParada, salir } from './api/identidad.ts';
import {
  RUTAS,
  cambiarElEjercicio,
  type AccesoDelSistema,
  type ModuloDelSistema,
  type MunicipalidadDeLaSesion,
  type PermisosDeLaSesion,
  type SesionDeLaVentanilla,
} from './datos/lecturas.ts';
import { useLista, useUno } from './datos/useRecurso.ts';
import { Esqueleto } from './ds/index.ts';
import { Marco } from './marco/Marco.tsx';
import { Puerta } from './marco/Puerta.tsx';
import { SinArbol } from './marco/SinArbol.tsx';
import { componerArbol } from './marco/composicion.ts';

/**
 * El casco de `rentas-web`: **primero quien eres, luego que puedes, y solo entonces el marco**.
 *
 * <h2>Por que la sesion se lee aqui y no dentro del marco</h2>
 *
 * Porque es la unica decision del casco, y es una decision de cuatro salidas —espera, puerta,
 * marco sin arbol, marco— que no se pueden tomar dos veces. Si cada seccion leyera la suya, un
 * 401 saldria cuatro veces en cuatro avisos distintos, cada uno pidiendo su remedio, y el marco
 * de fondo dibujando un padron vacio: la pantalla diria a la vez «no hay contribuyentes» y «hay
 * que identificarse». Aqui se lee una vez y se contesta una cosa.
 *
 * Y hace al marco **una funcion de la sesion y de los permisos**: `Marco` exige `sesion`,
 * `municipalidad`, `arbol` y `permisos`, sin respaldo, asi que no existe la forma de montarlo
 * sin decir quien esta dentro ni que puede abrir. Los respaldos eran «J. Cárdenas Vega»,
 * «Municipalidad Distrital de Catacaos» y un arbol de diez modulos iguales para todos: los tres
 * son el mismo defecto, y los cierran I-1 y I-3.
 *
 * <h2>Las cinco lecturas, y por que estan separadas en dos grupos</h2>
 *
 * <table>
 *   <tr><td>`GET /seguridad/sesion`</td><td rowspan="2">**quien**: sin esto no se entra</td></tr>
 *   <tr><td>`GET /seguridad/sesion/municipalidad`</td></tr>
 *   <tr><td>`GET /seguridad/modulos`</td><td rowspan="3">**que puede**: sin esto no hay arbol</td></tr>
 *   <tr><td>`GET /seguridad/accesos`</td></tr>
 *   <tr><td>`GET /seguridad/sesion/permisos`</td></tr>
 * </table>
 *
 * Las cinco salen a la vez —esperarlas en cadena serian cinco viajes en serie para pintar una
 * pantalla— pero **se contestan en dos grupos, y en este orden**. Un fallo de identidad manda a
 * la `Puerta`; uno de navegacion, a `SinArbol`. Mirarlos al reves diria «no se pudo leer el
 * arbol de modulos» cuando lo que pasa es que el token caduco, y las cinco fallan a la vez
 * porque las cinco pasan por la misma cadena de identidad: el primer mensaje seria el
 * equivocado.
 *
 * <h2>Negacion por omision, que es lo que ADR-0013 pide</h2>
 *
 * «Si la peticion falla, `NINGUNO`: negacion por omision, no un menu completo que falla en cada
 * pulsacion.» Aqui eso es literal: si `permisos` no llega, no se compone ningun arbol y no se
 * dibuja ningun marco. **No se cae del lado de ensenarlo todo**, que es la manera comoda de
 * fallar y la que deja a la ventanilla descubriendo por un 403 lo que podia hacer.
 *
 * <h2>Reintentar es volver a montar, y por eso hay `key`</h2>
 *
 * `useUno` pide cuando cambia la ruta, y ninguna de estas cinco cambia nunca. Un boton de
 * reintentar que no cambiara nada no reintentaria nada: lo que se hace es cambiar la `key` del
 * componente que pregunta, que React entiende como «este es otro» y remonta con su efecto. Y
 * por eso reintentar recoge de verdad un permiso recien concedido: vuelve a pedir la matriz.
 */
export function Aplicacion() {
  const [intento, reintentar] = useReducer((cuantos: number) => cuantos + 1, 0);

  return <Casco key={intento} alReintentar={reintentar} />;
}

function Casco({ alReintentar }: { readonly alReintentar: () => void }) {
  const sesion = useUno<SesionDeLaVentanilla>(RUTAS.sesion);
  const municipalidad = useUno<MunicipalidadDeLaSesion>(RUTAS.municipalidadDeLaSesion);
  const modulos = useLista<ModuloDelSistema>(RUTAS.modulos);
  const accesos = useLista<AccesoDelSistema>(RUTAS.accesos);
  const permisos = useUno<PermisosDeLaSesion>(RUTAS.permisosDeLaSesion);

  const compuesto = useMemo(
    () =>
      modulos.dato === null || accesos.dato === null || permisos.dato === null
        ? null
        : componerArbol(modulos.dato, accesos.dato, permisos.dato),
    [modulos.dato, accesos.dato, permisos.dato],
  );

  const volverAIdentificarse = () => {
    // Se olvida la parada ANTES de salir: el tope de tres idas existe para cortar un bucle
    // automatico, y esto es una persona pulsando un boton. Sin olvidarla, el cuarto clic no
    // haria nada y no diria por que.
    olvidarLaParada();
    void entrar();
  };

  const deLaIdentidad = sesion.fallo ?? municipalidad.fallo;
  if (deLaIdentidad !== null) {
    return (
      <Puerta
        peldano={peldanoDe(deLaIdentidad)}
        alVolverAIdentificarse={volverAIdentificarse}
        alReintentar={alReintentar}
      />
    );
  }

  const deLaNavegacion = modulos.fallo ?? accesos.fallo ?? permisos.fallo;
  if (deLaNavegacion !== null) {
    return (
      <SinArbol
        peldano={peldanoDe(deLaNavegacion)}
        alVolverAIdentificarse={volverAIdentificarse}
        alReintentar={alReintentar}
      />
    );
  }

  if (sesion.dato === null || municipalidad.dato === null || compuesto === null) {
    // Cargando. Un esqueleto y no una pantalla en blanco: entre el arranque y la respuesta hay
    // una ida a la red, y una pagina que no dibuja nada durante medio segundo se lee como una
    // aplicacion que no arranco.
    return (
      <main className="kr-puerta">
        <div className="kr-puerta__caja">
          <p className="kr-puerta__marca">
            Rentas
            <span className="kr-puerta__marca-nota">Identificando la sesión…</span>
          </p>
          <Esqueleto alto={18} />
        </div>
      </main>
    );
  }

  return (
    <Marco
      sesion={sesion.dato}
      municipalidad={municipalidad.dato}
      arbol={compuesto.modulos}
      permisos={permisos.dato ?? {}}
      alCambiarEjercicio={async (ejercicio, observacion) => {
        // Se devuelve lo que CONTESTA el backend y no lo que se le pidio. `SesionTrasElCambio`
        // publica `ejercicioDeTrabajo`, y es esa cifra —no la tecleada— la que la barra pasa a
        // decir: si el backend aceptara la peticion y fijara otra cosa, la pantalla diria la
        // suya. Lo que el backend no publica aqui es ni la cuenta ni el nombre, asi que de esta
        // respuesta no se toma nada mas.
        const sesionNueva = await cambiarElEjercicio(ejercicio, observacion);
        return sesionNueva.ejercicioDeTrabajo;
      }}
      alSalir={salir}
    />
  );
}

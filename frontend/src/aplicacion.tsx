import { useReducer } from 'react';

import { peldanoDe } from './api/escalera.ts';
import { entrar, olvidarLaParada, salir } from './api/identidad.ts';
import {
  RUTAS,
  type MunicipalidadDeLaSesion,
  type SesionDeLaVentanilla,
} from './datos/lecturas.ts';
import { useUno } from './datos/useRecurso.ts';
import { Esqueleto } from './ds/index.ts';
import { Marco } from './marco/Marco.tsx';
import { Puerta } from './marco/Puerta.tsx';

/**
 * El casco de `rentas-web`: **primero quien eres, y solo entonces el marco** (I-1).
 *
 * <h2>Por que la sesion se lee aqui y no dentro del marco</h2>
 *
 * Porque es la unica decision del casco, y es una decision de tres salidas —espera, puerta,
 * marco— que no se pueden tomar dos veces. Si cada seccion leyera la suya, un 401 saldria
 * cuatro veces en cuatro avisos distintos, cada uno pidiendo su remedio, y el marco de fondo
 * dibujando un padron vacio: la pantalla diria a la vez «no hay contribuyentes» y «hay que
 * identificarse». Aqui se lee una vez y se contesta una cosa.
 *
 * Y hace al marco **una funcion de la sesion**: `Marco` exige `sesion` y `municipalidad`, sin
 * respaldo, asi que no existe la forma de montarlo sin decir quien esta dentro. El respaldo era
 * «J. Cárdenas Vega» y «Municipalidad Distrital de Catacaos», y esas dos constantes son el
 * defecto que este issue cierra.
 *
 * <h2>Las dos lecturas, y por que las dos y no una</h2>
 *
 * `GET /seguridad/sesion` dice **quien**; `GET /seguridad/sesion/municipalidad` dice **de quien
 * son las cifras**. Las dos aparecen en la cabecera de todas las pantallas, asi que entrar con
 * una y sin la otra dejaria media cabecera afirmando y la otra media en blanco. Fallan juntas
 * —las dos pasan por la misma cadena de identidad— y por eso basta con ensenar el primer fallo
 * que llegue.
 *
 * <h2>Reintentar es volver a montar, y por eso hay `key`</h2>
 *
 * `useUno` pide cuando cambia la ruta, y la ruta de la sesion no cambia nunca. Un boton de
 * reintentar que no cambiara nada no reintentaria nada: lo que se hace es cambiar la `key` del
 * componente que pregunta, que React entiende como «este es otro» y remonta con su efecto.
 */
export function Aplicacion() {
  const [intento, reintentar] = useReducer((cuantos: number) => cuantos + 1, 0);

  return <Casco key={intento} alReintentar={reintentar} />;
}

function Casco({ alReintentar }: { readonly alReintentar: () => void }) {
  const sesion = useUno<SesionDeLaVentanilla>(RUTAS.sesion);
  const municipalidad = useUno<MunicipalidadDeLaSesion>(RUTAS.municipalidadDeLaSesion);

  const fallo = sesion.fallo ?? municipalidad.fallo;
  if (fallo !== null) {
    return (
      <Puerta
        peldano={peldanoDe(fallo)}
        alVolverAIdentificarse={() => {
          // Se olvida la parada ANTES de salir: el tope de tres idas existe para cortar un
          // bucle automatico, y esto es una persona pulsando un boton. Sin olvidarla, el cuarto
          // clic no haria nada y no diria por que.
          olvidarLaParada();
          void entrar();
        }}
        alReintentar={alReintentar}
      />
    );
  }

  if (sesion.dato === null || municipalidad.dato === null) {
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

  return <Marco sesion={sesion.dato} municipalidad={municipalidad.dato} alSalir={salir} />;
}

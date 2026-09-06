import { useCallback, useEffect, useReducer, useState } from 'react';

import { Icono } from '../ds/index.ts';
import {
  NUEVO,
  PADRON_AL_EMPEZAR,
  type EstadoDelPadron,
} from '../secciones/estadoDelPadron.ts';
import { BarraGlobal } from './BarraGlobal.tsx';
import { Confirmacion } from './Confirmacion.tsx';
import { Lienzo } from './Lienzo.tsx';
import { PaletaDeComandos } from './PaletaDeComandos.tsx';
import { PanelDeModulos } from './PanelDeModulos.tsx';
import { Trazos } from './Trazos.tsx';
import { HOJAS, MODULO_PROPIO, esPropia } from './arbol.ts';
import { destinoDelHash, marcarHash } from './hash.ts';
import { estadoInicial, reducir } from './pestanas.ts';

/**
 * El marco V6: **se construye una vez y lo usan todos los modulos** (F-3).
 *
 * Es la barra global, el arbol de la izquierda, las pestanas, el enrutado por
 * hash y el estado sin guardar. **No es ninguna pantalla**: las secciones viven
 * en `src/secciones/` y el marco solo decide cual se ensena. Desde F-5 hay dos
 * —el panel y el padron—; para las otras dos el lienzo sigue diciendo que estan
 * vacias en vez de aparentar que no lo estan.
 *
 * <h2>Solo la variante A</h2>
 *
 * De los tres marcos que el artboard conmuta se porta **uno**, el `a`. El
 * conmutador A/B/C no existe en el codigo: el artboard lo declara control del
 * prototipo. Lo que eso implica —252 px, la cola siempre visible, y ni un `esA`,
 * `esB`, `esC` o `panelVar` en ninguna parte— lo vigila
 * `verificaciones/marco-sin-selector.test.ts`, que lee el codigo fuente. Una
 * rama muerta no se ve en una revision y no la pone roja ninguna prueba de
 * comportamiento: solo la caza quien la busque por su nombre.
 *
 * <h2>El toast dura 3 400 ms, como el artboard</h2>
 *
 * Y se cancela al desmontar: un `setTimeout` vivo despues de que React tire el
 * arbol escribe estado sobre un componente que ya no esta, y en las pruebas eso
 * sale como un aviso suelto varias pruebas mas tarde.
 */

/** La municipalidad piloto. La pone el marco, no cada pantalla. */
const ENTIDAD = 'Municipalidad Distrital de Catacaos';

/** Cuanto vive un aviso flotante, en milisegundos. Del artboard. */
const VIDA_DEL_TOAST = 3400;

/**
 * El subtitulo de cada seccion propia.
 *
 * El del padron era «62,418 contribuyentes en el padrón» en el artboard. **Esa
 * cifra es un dato**, y los datos son de otro issue: escribirla aqui la
 * convertiria en una afirmacion del producto que nada respalda.
 */
const SUBTITULOS: Readonly<Record<string, string>> = {
  predios: 'Padrón de contribuyentes',
  territorio: 'Seis tipos de cálculo',
  valores: 'UIT, arbitrios e intereses',
};

function tituloDe(activa: string | null, ejercicio: string): string {
  if (activa === null) {
    return 'Sin pestañas abiertas';
  }
  const hoja = HOJAS.get(activa);
  if (hoja === undefined) {
    return 'Rentas';
  }
  if (!esPropia(activa)) {
    return hoja.rotulo;
  }
  if (activa === 'panel') {
    return 'Panel de Rentas';
  }
  if (activa === 'valores') {
    return `Valores del ejercicio ${ejercicio}`;
  }
  return hoja.rotulo;
}

function subtituloDe(activa: string | null, ejercicio: string, padron: EstadoDelPadron): string {
  if (activa === null) {
    return '';
  }
  const hoja = HOJAS.get(activa);
  if (hoja === undefined) {
    return '';
  }
  if (!esPropia(activa)) {
    return hoja.modulo;
  }
  if (activa === 'panel') {
    return `Ejercicio ${ejercicio}`;
  }
  // El del padron dice **a quien se esta mirando**, como el artboard: el codigo cuando hay un
  // expediente abierto, y que se esta creando cuando se esta creando. La cifra de «62,418
  // contribuyentes en el padrón» sigue sin escribirse aqui: es un dato, y el dato lo tiene la
  // seccion, que es quien lo pidio.
  if (activa === 'predios') {
    if (padron.elegido === NUEVO) {
      return 'Creando un contribuyente';
    }
    return padron.elegido ?? (SUBTITULOS[activa] ?? '');
  }
  return SUBTITULOS[activa] ?? '';
}

export function Marco() {
  const [pestanas, despachar] = useReducer(reducir, destinoDelHash(), estadoInicial);

  const [panelAbierto, fijarPanelAbierto] = useState(true);
  const [filtro, fijarFiltro] = useState('');
  const [desplegado, fijarDesplegado] = useState<string | null>(MODULO_PROPIO);
  const [lanzador, fijarLanzador] = useState(false);
  const [paleta, fijarPaleta] = useState(false);
  const [sesion, fijarSesion] = useState(false);
  const [avisoDescartado, fijarAvisoDescartado] = useState(false);
  const [avisoAbierto, fijarAvisoAbierto] = useState(false);
  const [ejercicio, fijarEjercicio] = useState('2026');
  const [toast, fijarToast] = useState('');
  // El estado del padron vive aqui y no dentro de la seccion: el marco la desmonta al cambiar
  // de pestana, y con el estado dentro, escribir media alta e ir al panel dejaria el formulario
  // en blanco **con el asterisco puesto**. Ver `secciones/estadoDelPadron.ts`.
  const [padron, fijarPadron] = useState<EstadoDelPadron>(PADRON_AL_EMPEZAR);

  const abrir = useCallback((destino: string) => {
    despachar({ tipo: 'abrir', destino });
    fijarPaleta(false);
    fijarLanzador(false);
  }, []);

  // El hash sigue a la pestana activa, con `replaceState`: abrir una seccion no
  // es navegar, y con `pushState` el «atras» del navegador haria falta cuarenta
  // veces para salir de la aplicacion (AC4).
  useEffect(() => {
    if (pestanas.activa !== null) {
      marcarHash(pestanas.activa);
    }
  }, [pestanas.activa]);

  useEffect(() => {
    const alCambiarElHash = () => {
      const destino = destinoDelHash();
      if (destino !== null) {
        despachar({ tipo: 'abrir', destino });
      }
    };
    window.addEventListener('hashchange', alCambiarElHash);
    return () => {
      window.removeEventListener('hashchange', alCambiarElHash);
    };
  }, []);

  useEffect(() => {
    const alPulsar = (evento: KeyboardEvent) => {
      if ((evento.ctrlKey || evento.metaKey) && evento.key.toLowerCase() === 'k') {
        // `preventDefault` porque `Ctrl+K` es el atajo del buscador de la barra
        // de direcciones en varios navegadores: sin el, la paleta se abre y el
        // foco se va fuera de la pagina.
        evento.preventDefault();
        fijarPaleta((abierta) => !abierta);
        fijarLanzador(false);
      } else if (evento.key === 'Escape') {
        fijarPaleta(false);
        fijarLanzador(false);
        fijarSesion(false);
        despachar({ tipo: 'cancelar-cierre' });
      }
    };
    window.addEventListener('keydown', alPulsar);
    return () => {
      window.removeEventListener('keydown', alPulsar);
    };
  }, []);

  useEffect(() => {
    if (toast === '') {
      return;
    }
    const reloj = setTimeout(() => {
      fijarToast('');
    }, VIDA_DEL_TOAST);
    return () => {
      clearTimeout(reloj);
    };
  }, [toast]);

  const cuantasSucias = Object.keys(pestanas.sucias).length;
  const porCerrar = pestanas.porCerrar;
  const hojaPorCerrar = porCerrar === null ? undefined : HOJAS.get(porCerrar);

  return (
    <div className="kr-marco">
      {paleta && (
        <PaletaDeComandos
          alAbrir={abrir}
          alCerrar={() => {
            fijarPaleta(false);
          }}
        />
      )}

      <BarraGlobal
        entidad={ENTIDAD}
        ejercicio={ejercicio}
        alCambiarEjercicio={(elegido) => {
          fijarEjercicio(elegido);
          fijarToast(
            `Ejercicio ${elegido}: se recargaron la UIT, la escala y las tablas de arbitrios.`,
          );
        }}
        panelAbierto={panelAbierto}
        alAlternarPanel={() => {
          fijarPanelAbierto((abierto) => !abierto);
          fijarLanzador(false);
          fijarPaleta(false);
        }}
        lanzadorAbierto={lanzador}
        alAlternarLanzador={() => {
          fijarLanzador((abierto) => !abierto);
          fijarPaleta(false);
          fijarSesion(false);
        }}
        alAbrirPaleta={() => {
          fijarPaleta(true);
          fijarLanzador(false);
          fijarSesion(false);
        }}
        sesionAbierta={sesion}
        alAlternarSesion={() => {
          fijarSesion((abierta) => !abierta);
          fijarLanzador(false);
          fijarPaleta(false);
        }}
        alCerrarSesion={() => {
          fijarSesion(false);
        }}
        hayAviso={!avisoDescartado && !avisoAbierto}
        alVerAviso={() => {
          fijarAvisoAbierto(true);
        }}
        cuantasSucias={cuantasSucias}
        alAbrir={abrir}
        alAvisar={fijarToast}
      />

      <div className="kr-marco__cuerpo">
        {panelAbierto && (
          <PanelDeModulos
            filtro={filtro}
            alFiltrar={fijarFiltro}
            desplegado={desplegado}
            alDesplegar={(modulo) => {
              fijarDesplegado((actual) => (actual === modulo ? null : modulo));
            }}
            activa={pestanas.activa}
            abiertas={pestanas.abiertas}
            sucias={pestanas.sucias}
            alAbrir={abrir}
          />
        )}

        <div className="kr-marco__area">
          <div className="kr-marco__pestanas" aria-label="Pestañas abiertas" role="group">
            {pestanas.abiertas.map((clave) => {
              // `abiertas` solo lleva claves del arbol —el hash se valida contra
              // el antes de aceptarse—, asi que `hoja` esta siempre. Se cae del
              // lado de ensenar la clave y ningun icono en vez de reventar: una
              // pestana es cromo, y el cromo no tumba la pantalla que envuelve.
              const hoja = HOJAS.get(clave);
              const rotulo = hoja?.rotulo ?? clave;
              const trazos = hoja?.trazos ?? [];
              const esLaActiva = pestanas.activa === clave;
              const sucia = pestanas.sucias[clave] === true;

              return (
                <span
                  key={clave}
                  className={`kr-marco__pestana${esLaActiva ? ' kr-marco__pestana--actual' : ''}`}
                >
                  <button
                    type="button"
                    onClick={() => {
                      abrir(clave);
                    }}
                    aria-current={esLaActiva}
                    className="kr-marco__pestana-boton"
                  >
                    <span className="kr-marco__pestana-icono">
                      <Trazos trazos={trazos} tamano={13} />
                    </span>
                    <span className="kr-marco__pestana-rotulo">
                      {rotulo}
                      {sucia ? ' *' : ''}
                    </span>
                  </button>
                  <button
                    type="button"
                    onClick={() => {
                      despachar({ tipo: 'pedir-cierre', destino: clave });
                    }}
                    aria-label={
                      sucia ? `Cerrar ${rotulo} — tiene cambios sin guardar` : `Cerrar ${rotulo}`
                    }
                    title={sucia ? `Cerrar ${rotulo} — tiene cambios sin guardar` : `Cerrar ${rotulo}`}
                    className="kr-marco__pestana-cerrar"
                  >
                    <Icono nombre="cerrar" tamano={13} grosor={2.2} />
                  </button>
                </span>
              );
            })}
            <span className="kr-marco__pestanas-resto" />
          </div>

          {pestanas.activa !== null && (
            <div className="kr-marco__cabecera">
              <h1 className="kr-marco__titulo">{tituloDe(pestanas.activa, ejercicio)}</h1>
              <span className="kr-marco__subtitulo">
                {subtituloDe(pestanas.activa, ejercicio, padron)}
              </span>
            </div>
          )}

          {!avisoDescartado && avisoAbierto && (
            <div role="status" className="kr-marco__aviso">
              <span className="kr-marco__aviso-icono">
                <svg
                  width="18"
                  height="18"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth={2}
                  strokeLinecap="round"
                  aria-hidden="true"
                  focusable="false"
                >
                  <circle cx="12" cy="12" r="9" />
                  <path d="M12 7.6V13M12 16.4h.02" />
                </svg>
              </span>
              <p className="kr-marco__aviso-texto">
                La emisión masiva del predial 2026 dejó 534 contribuyentes observados sin
                cuponera. Hasta que se corrija la inconsistencia no se les puede cobrar el
                ejercicio.
              </p>
              <button
                type="button"
                onClick={() => {
                  fijarAvisoDescartado(true);
                  fijarAvisoAbierto(false);
                }}
                aria-label="Descartar el aviso"
                className="kr-marco__aviso-cerrar"
              >
                <Icono nombre="cerrar" tamano={16} grosor={2.1} />
              </button>
            </div>
          )}

          <Lienzo
            activa={pestanas.activa}
            alCerrar={(destino) => {
              despachar({ tipo: 'pedir-cierre', destino });
            }}
            alAbrir={abrir}
            alEnsuciar={() => {
              despachar({ tipo: 'ensuciar' });
            }}
            alAvisar={fijarToast}
            padron={padron}
            alCambiarPadron={(cambio) => {
              fijarPadron((actual) => ({ ...actual, ...cambio }));
            }}
            ejercicio={ejercicio}
          />
        </div>
      </div>

      {porCerrar !== null && (
        <Confirmacion
          rotulo={hojaPorCerrar === undefined ? porCerrar : hojaPorCerrar.rotulo}
          alDescartar={() => {
            despachar({ tipo: 'cerrar', destino: porCerrar });
          }}
          alSeguirEditando={() => {
            despachar({ tipo: 'cancelar-cierre' });
          }}
          alGuardar={() => {
            despachar({ tipo: 'cerrar', destino: porCerrar });
            fijarToast(
              `Cambios guardados en ${
                hojaPorCerrar === undefined ? porCerrar : hojaPorCerrar.rotulo
              }.`,
            );
          }}
        />
      )}

      {toast !== '' && (
        <div role="status" className="kr-marco__toast">
          <Icono nombre="visto" tamano={16} grosor={2.6} />
          {toast}
        </div>
      )}
    </div>
  );
}

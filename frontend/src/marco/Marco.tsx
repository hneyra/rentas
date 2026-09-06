import { useCallback, useEffect, useMemo, useReducer, useState } from 'react';

import type {
  MunicipalidadDeLaSesion,
  PermisosDeLaSesion,
  SesionDeLaVentanilla,
} from '../datos/lecturas.ts';
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
import { CODIGO_PROPIO, HOJAS, esPropia, type Modulo } from './arbol.ts';
import { destinosOfrecidos, puedeCambiarElEjercicio } from './composicion.ts';
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

/**
 * De donde sale ahora el nombre de la entidad: **de `municipalidad.nombre`, y de ningun otro
 * sitio**.
 *
 * Aqui habia una constante —«Municipalidad Distrital de Catacaos»— sin ninguna interfaz que la
 * cambiara. Con el token de otra municipalidad, esa cabecera afirmaba de quien son unas cifras
 * que no son suyas, y lo afirmaba en las cuatro secciones a la vez. Lo cierra I-1 con
 * `GET /seguridad/sesion/municipalidad`, que es una de las dos primeras rutas que salen a la red
 * de verdad (`datos/servidas.ts`).
 */

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

function tituloDe(activa: string | null, ejercicio: string | null): string {
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
    // Sin ejercicio fijado, el titulo no nombra ninguno. «Valores del ejercicio 2026» con la
    // sesion sin ejercicio seria la misma mentira que la entidad constante, en el sitio donde
    // mas cara sale: lo que esta pantalla ensena son la UIT y las alicuotas de UN ano.
    return ejercicio === null ? 'Valores' : `Valores del ejercicio ${ejercicio}`;
  }
  return hoja.rotulo;
}

function subtituloDe(
  activa: string | null,
  ejercicio: string | null,
  padron: EstadoDelPadron,
): string {
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
    return ejercicio === null ? 'Sin ejercicio de trabajo fijado' : `Ejercicio ${ejercicio}`;
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

/**
 * Lo que el marco necesita saber de quien esta dentro.
 *
 * **Obligatorio, y a proposito.** Podrian ser opcionales con un respaldo, y ese respaldo seria
 * otra vez «J. Cárdenas Vega»: un valor por omision aqui es exactamente el defecto que I-1
 * cierra, con la diferencia de que nadie lo veria hasta que alguien montara el marco sin
 * pasarlas. Sin respaldo, no se puede.
 */
export interface MarcoProps {
  readonly sesion: SesionDeLaVentanilla;
  readonly municipalidad: MunicipalidadDeLaSesion;
  /**
   * Los modulos que se ofrecen, ya compuestos por `composicion.ts` (I-3).
   *
   * **Obligatorio y sin respaldo, por el mismo motivo que `sesion`.** Un valor por omision aqui
   * seria `ARBOL`, o sea la navegacion constante de diez modulos para todos que este issue
   * viene a quitar — y nadie lo veria hasta que alguien montara el marco sin pasarla.
   */
  readonly arbol: readonly Modulo[];
  /** La matriz de `GET /seguridad/sesion/permisos`. Decide si se ofrece el acto del AC3. */
  readonly permisos: PermisosDeLaSesion;
  /** Manda `PUT /seguridad/sesion/ejercicio`. Lanza si el backend no lo acepta. */
  readonly alCambiarEjercicio: (ejercicio: number, observacion: string) => Promise<number | null>;
  /** Cierra la sesion aqui y en el emisor. Lo enchufa el casco a `api/identidad.salir`. */
  readonly alSalir: () => void;
}

export function Marco({
  sesion: quien,
  municipalidad,
  arbol,
  permisos,
  alCambiarEjercicio,
  alSalir,
}: MarcoProps) {
  const [pestanas, despachar] = useReducer(reducir, arbol, (modulos) => {
    // El hash tambien se valida contra lo ofrecido, y hay que hacerlo AQUI: al arrancar, la
    // pestana no se abre llamando a `abrir` sino inicializando el reducer, asi que la guarda de
    // `abrir` no la ve pasar. Un enlace guardado a `#coa-exp` habria abierto la pestana igual.
    const ofrece = destinosOfrecidos(modulos);
    const pedido = destinoDelHash();
    // Y el arranque por omision es el primer destino que se ofrece, que casi siempre es el
    // `panel` de rentas y no lo es cuando esta cuenta no puede abrir ese modulo.
    const primero = modulos[0]?.submodulos[0]?.clave ?? 'panel';
    const propio = ofrece.has('panel') ? 'panel' : primero;
    return estadoInicial(pedido !== null && ofrece.has(pedido) ? pedido : null, propio);
  });

  const [panelAbierto, fijarPanelAbierto] = useState(true);
  const [filtro, fijarFiltro] = useState('');
  const [desplegado, fijarDesplegado] = useState<string | null>(
    // Por CLAVE y no por rotulo: el rotulo ya lo dice el backend. Ver `CODIGO_PROPIO`.
    arbol.find((modulo) => modulo.codigo === CODIGO_PROPIO)?.clave ?? null,
  );
  const [lanzador, fijarLanzador] = useState(false);
  const [paleta, fijarPaleta] = useState(false);
  const [sesion, fijarSesion] = useState(false);
  const [avisoDescartado, fijarAvisoDescartado] = useState(false);
  const [avisoAbierto, fijarAvisoAbierto] = useState(false);
  // El ejercicio arranca en el que dice el backend, y en `null` si no dice ninguno (AC4). Lo
  // que el artboard ponia aqui era un `'2026'` fijo. **Y desde I-3 tampoco cambia solo**: lo
  // fija `PUT /seguridad/sesion/ejercicio`, y lo que se guarda aqui es el que CONTESTO el
  // backend, no el que se le pidio. No es lo mismo: si el backend aceptara 2026 y devolviera
  // otro —o ninguno—, la barra diria el que el backend cree y no el que alguien tecleo.
  const [ejercicio, fijarEjercicio] = useState<number | null>(quien.ejercicioDeTrabajo);
  const [toast, fijarToast] = useState('');
  // El estado del padron vive aqui y no dentro de la seccion: el marco la desmonta al cambiar
  // de pestana, y con el estado dentro, escribir media alta e ir al panel dejaria el formulario
  // en blanco **con el asterisco puesto**. Ver `secciones/estadoDelPadron.ts`.
  const [padron, fijarPadron] = useState<EstadoDelPadron>(PADRON_AL_EMPEZAR);

  /**
   * Los destinos que esta cuenta puede abrir. La ultima puerta del AC2.
   *
   * El panel, el lanzador y la paleta ya ofrecen solo lo compuesto, pero **el hash no pasa por
   * ninguno de los tres**: `#coa-exp` escrito en la barra de direcciones —o un enlace guardado
   * de cuando la cuenta si podia— abriria la pestana igual. Que la pantalla de dentro fuera a
   * recibir un 403 no lo arregla: la pestana existiria, con su rotulo y su icono, y la persona
   * tendria que descubrir por el error que no era para ella.
   */
  const ofrecidos = useMemo(() => destinosOfrecidos(arbol), [arbol]);

  const abrir = useCallback(
    (destino: string) => {
      if (!ofrecidos.has(destino)) {
        return;
      }
      despachar({ tipo: 'abrir', destino });
      fijarPaleta(false);
      fijarLanzador(false);
    },
    [ofrecidos],
  );

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
        // Por `abrir` y no por `despachar`, para que pase por la guarda del AC2: cambiar el
        // hash a mano es la via mas directa que hay a un destino que el arbol no ofrece.
        abrir(destino);
      }
    };
    window.addEventListener('hashchange', alCambiarElHash);
    return () => {
      window.removeEventListener('hashchange', alCambiarElHash);
    };
  }, [abrir]);

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

  // El ejercicio es un numero —es lo que el backend publica y lo que el `PUT` recibe— y las
  // pantallas lo usan para componer una ruta y un titulo. La conversion se hace una vez, aqui,
  // en la frontera entre lo que se guarda y lo que se dibuja.
  const ejercicioTexto = ejercicio === null ? null : String(ejercicio);
  const cuantasSucias = Object.keys(pestanas.sucias).length;
  const porCerrar = pestanas.porCerrar;
  const hojaPorCerrar = porCerrar === null ? undefined : HOJAS.get(porCerrar);

  return (
    <div className="kr-marco">
      {paleta && (
        <PaletaDeComandos
          arbol={arbol}
          alAbrir={abrir}
          alCerrar={() => {
            fijarPaleta(false);
          }}
        />
      )}

      <BarraGlobal
        entidad={municipalidad.nombre}
        usuario={quien}
        arbol={arbol}
        ejercicio={ejercicio}
        puedeCambiarEjercicio={puedeCambiarElEjercicio(permisos)}
        alCambiarEjercicio={async (elegido, observacion) => {
          // Lo que se guarda es lo que CONTESTA el backend. El aviso flotante decia antes «se
          // recargaron la UIT, la escala y las tablas de arbitrios» y no se recargaba nada:
          // ahora dice lo unico que se sabe cierto —que quedo fijado, y que quedo registrado—.
          const fijado = await alCambiarEjercicio(elegido, observacion);
          fijarEjercicio(fijado);
          fijarToast(
            fijado === null
              ? 'La sesión quedó sin ejercicio de trabajo.'
              : `Ejercicio de trabajo: ${String(fijado)}. El cambio quedó registrado.`,
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
        alSalir={alSalir}
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
            arbol={arbol}
            filtro={filtro}
            alFiltrar={fijarFiltro}
            desplegado={desplegado}
            alDesplegar={(clave) => {
              fijarDesplegado((actual) => (actual === clave ? null : clave));
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
              <h1 className="kr-marco__titulo">{tituloDe(pestanas.activa, ejercicioTexto)}</h1>
              <span className="kr-marco__subtitulo">
                {subtituloDe(pestanas.activa, ejercicioTexto, padron)}
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
            ejercicio={ejercicioTexto}
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

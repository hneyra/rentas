import { Aviso, Boton, Esqueleto, FechaDeCalculo, Icono, Insignia } from '../ds/index.ts';
import { formatearFechaEnPalabras } from '../dominio/formato.ts';
import {
  RUTAS,
  type IndicadorDeRecaudacion,
  type MovimientoDeLaBitacora,
  type TrabajoParado,
} from '../datos/lecturas.ts';
import { useLista, useUno } from '../datos/useRecurso.ts';
import { tonoDelEstado } from './tonos.ts';

/**
 * El panel del modulo `Rentas · Registro` (F-5, AC1).
 *
 * Tres bloques y una cabecera de cuatro cifras, como el artboard: la cola de trabajo, el avance
 * de la recaudacion por tributo y la actividad reciente, con «Ver todo el padrón».
 *
 * <h2>Ninguna cifra sin su fecha (regla 9, RNF-075)</h2>
 *
 * El panel entero esta **a una fecha de corte**, que no es hoy: el artboard la escribe dos veces
 * —«al 31 de agosto»— y el backend la publica en `fechaCalculo`. Se dibuja arriba con
 * `FechaDeCalculo`, para que se lea una vez y valga para las cuatro tarjetas, y otra vez en la
 * cabecera del avance, que es donde el artboard la pone. **No se compone de la fecha del
 * navegador**: un panel que dijera «hoy» ensenaria el avance de agosto con fecha de hoy.
 *
 * <h2>Dos cosas del artboard que el port NO dibuja, y por que</h2>
 *
 *   1. **La pastilla «+3.1» de la tarjeta «Recaudado».** Ninguna de las 181 operaciones publica
 *      una variacion; el KPI del contrato es `label`, `value`, `note` y un importe con su fecha.
 *      Calcularla aqui seria inventar una cifra, y ademas una cifra sin fecha: «+3.1» respecto
 *      de que dia.
 *   2. **El «hace 2 h» de la actividad.** La bitacora publica un INSTANTE, que es lo que guarda.
 *      Convertirlo en distancia contra el reloj del puesto haria que la misma fila dijera «hace
 *      2 h» a las nueve y «ayer» a medianoche, sin que ningun dato hubiera cambiado.
 *
 * Las dos estan capturadas en `datos/prototipo.ts` y las dos las vigila
 * `verificaciones/secciones-del-artboard.test.ts`: el dia que el contrato publique el campo, la
 * prueba dice donde ponerlo.
 */
export interface PanelProps {
  /** «Ver todo el padrón», y cada frente de la cola de trabajo, que filtra el padron. */
  readonly alIrAlPadron: (chip?: string) => void;
  /** Una linea de actividad abre el expediente del contribuyente que toco. */
  readonly alAbrirContribuyente: (codigo: string) => void;
}

/**
 * Cuantos expedientes hay parados en total, con el separador de millar del artboard.
 *
 * **Se deriva de los tres frentes y no se copia** (regla 4 de PORTAR.md). El artboard escribe
 * «1,134 pendientes» y ademas sus tres sumandos; sumarlos aqui hace que la cabecera no pueda
 * mentir sobre sus propias filas el dia que una cambie. Son expedientes, no dinero: contar
 * cosas con `number` no es aritmetica sobre importes.
 */
function cuantosPendientes(parado: TrabajoParado): string {
  return parado.frentes
    .reduce((total, frente) => total + frente.cuantos, 0)
    .toLocaleString('en-US');
}

/** El instante de la bitacora, escrito como fecha y hora del puesto. */
function momento(instante: string): string {
  const partes = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(instante);
  if (partes === null) {
    return instante;
  }
  const [, anio, mes, dia, hora, minuto] = partes;
  return `${dia}/${mes}/${anio} ${hora}:${minuto}`;
}

export function Panel({ alIrAlPadron, alAbrirContribuyente }: PanelProps) {
  const recaudacion = useUno<IndicadorDeRecaudacion>(RUTAS.recaudacion);
  const parado = useUno<TrabajoParado>(RUTAS.trabajoParado);
  const bitacora = useLista<MovimientoDeLaBitacora>(RUTAS.bitacora);

  const indicador = recaudacion.dato;
  const avance = indicador?.paneles[0];

  return (
    <main className="kr-marco__lienzo kr-seccion">
      <div className="kr-panel">
        {recaudacion.error !== null && (
          <Aviso
            tipo="error"
            titulo="No se pudo leer el indicador de recaudación"
            detalle={recaudacion.error}
          />
        )}

        {indicador !== null && <FechaDeCalculo fecha={indicador.fechaCalculo} />}

        <div
          className="kr-panel__cifras"
          role="group"
          aria-label="Cifras del ejercicio"
          aria-busy={recaudacion.cargando}
        >
          {recaudacion.cargando &&
            [0, 1, 2, 3].map((hueco) => (
              <div className="kr-panel__cifra" key={hueco}>
                <Esqueleto alto={12} ancho="60%" />
                <Esqueleto alto={29} ancho="45%" />
                <Esqueleto alto={12} />
              </div>
            ))}
          {indicador?.kpis.map((kpi) => (
            <div className="kr-panel__cifra" key={kpi.label}>
              <p className="kr-panel__cifra-etiqueta">{kpi.label}</p>
              <p className="kr-panel__cifra-valor">{kpi.value}</p>
              <p className="kr-panel__cifra-nota">{kpi.note}</p>
            </div>
          ))}
        </div>

        <div className="kr-panel__dos">
          <section className="kr-tarjeta" aria-labelledby="kr-panel-cola">
            <div className="kr-tarjeta__cabecera">
              <h2 className="kr-tarjeta__titulo" id="kr-panel-cola">
                Cola de trabajo
              </h2>
              <span className="kr-tarjeta__apunte">
                {parado.dato === null ? '' : `${cuantosPendientes(parado.dato)} pendientes`}
              </span>
            </div>
            {parado.error !== null && (
              <Aviso tipo="error" titulo="No se pudo leer la cola de trabajo" detalle={parado.error} />
            )}
            {parado.dato?.frentes.map((frente) => (
              <button
                type="button"
                key={frente.frente}
                className="kr-fila-boton"
                onClick={() => {
                  alIrAlPadron(frente.frente === 'Observado' ? 'Observado' : 'Todos');
                }}
              >
                <Insignia tono={tonoDelEstado(frente.frente)}>{frente.frente}</Insignia>
                <span className="kr-fila-boton__cuerpo">
                  <span className="kr-fila-boton__titulo">{frente.queEstaParado}</span>
                  <span className="kr-fila-boton__detalle">{frente.porQueCuestaDinero}</span>
                </span>
                <span className="kr-fila-boton__cuenta">{frente.cuantos}</span>
                <Icono nombre="chevronDerecha" tamano={14} grosor={2} />
              </button>
            ))}
          </section>

          <section className="kr-tarjeta" aria-labelledby="kr-panel-avance">
            <div className="kr-tarjeta__cabecera">
              <h2 className="kr-tarjeta__titulo" id="kr-panel-avance">
                {avance === undefined ? 'Recaudado sobre emitido, por tributo' : avance.title}
              </h2>
              <span className="kr-tarjeta__apunte">
                {indicador === null ? '' : `al ${formatearFechaEnPalabras(indicador.fechaCalculo)}`}
              </span>
            </div>
            {avance?.rows.map((fila) => (
              <div className="kr-avance" key={fila.label}>
                <span className="kr-avance__tributo">{fila.label}</span>
                <span className="kr-avance__carril">
                  <span
                    className={`kr-avance__barra kr-avance__barra--${
                      fila.pct < 60 ? 'bajo' : fila.pct < 90 ? 'medio' : 'alto'
                    }`}
                    style={{ width: `${String(fila.pct)}%` }}
                  />
                </span>
                <span
                  className={`kr-avance__pct kr-avance__pct--${
                    fila.pct < 60 ? 'bajo' : fila.pct < 90 ? 'medio' : 'alto'
                  }`}
                >
                  {fila.value}
                </span>
                <span className="kr-avance__detalle">{fila.sub}</span>
              </div>
            ))}
            {avance !== undefined && <p className="kr-tarjeta__pie">{avance.note}</p>}
          </section>
        </div>

        <section className="kr-tarjeta" aria-labelledby="kr-panel-actividad">
          <div className="kr-tarjeta__cabecera">
            <h2 className="kr-tarjeta__titulo" id="kr-panel-actividad">
              Actividad reciente
            </h2>
            <Boton
              menudo
              onClick={() => {
                alIrAlPadron();
              }}
            >
              Ver todo el padrón
            </Boton>
          </div>
          {bitacora.error !== null && (
            <Aviso tipo="error" titulo="No se pudo leer la bitácora" detalle={bitacora.error} />
          )}
          {bitacora.dato?.map((acto) => (
            <button
              type="button"
              key={acto.id}
              className="kr-fila-boton kr-fila-boton--menuda"
              onClick={() => {
                alAbrirContribuyente(acto.clave);
              }}
            >
              <Insignia tono={tonoDelEstado(acto.operacion)}>{acto.operacion}</Insignia>
              <span className="kr-actividad__codigo">{acto.clave}</span>
              <span className="kr-actividad__detalle">{acto.observacion}</span>
              <span className="kr-actividad__cuando">{momento(acto.fecha)}</span>
            </button>
          ))}
        </section>
      </div>
    </main>
  );
}

import { useState } from 'react';

import { Aviso, Icono } from '../ds/index.ts';
import {
  RUTAS,
  type ArbitrioServido,
  type ConjuntoDelEjercicio,
  type DeterminacionIndividual,
  type DeterminacionVehicular,
} from '../datos/lecturas.ts';
import { useCalculo, useLista, useUno } from '../datos/useRecurso.ts';
import { Cuadro } from './Cuadro.tsx';
import type { FilaDeLaMemoria } from './determinacion.ts';
import {
  TABLAS_DE_VALORES,
  filasDeLaEscala,
  filasDeLosArbitriosPorZona,
  filasDeLosIntereses,
} from './valores.ts';

/**
 * La seccion «Valores» (clave `valores`): la portada de `VAL` (`:1163`) con sus tres pestanas.
 *
 * <h2>Lo que esta pantalla ensena, y de donde puede sacarlo</h2>
 *
 * De ningun sitio directo, y ese es el hallazgo: **ninguna de las 181 operaciones publica la
 * tabla de valores del ejercicio**. Los sella `normativa` (ADR-0025) y este sistema los consume
 * de su copia local sin republicarlos. Lo unico que el contrato dice de ellos son las **senas
 * del conjunto** —`GET /seguridad/parametros/ejercicios/{ejercicio}`: ejercicio, conjunto,
 * version y si esta sellado—, que es lo que esta pantalla pone arriba como procedencia.
 *
 * Las cifras se sacan de donde el sistema las **aplico**: dentro de una determinacion. Por eso
 * esta pantalla pide una memoria del predial y una vehicular — con `POST`, que es como el
 * contrato publica un calculo—. **Y eso es exactamente lo que hay que cerrar**: para ensenar la
 * UIT de un ejercicio hay que pedir que se determine algo. Mientras tanto la peticion no lleva
 * cuerpo y el proxy lo ignora (AC8 de #4); el dia que el backend conteste, esta pantalla tendra
 * que mandar `simulacion: true`, que el contrato ya publica.
 *
 * <h2>«Solo lectura» deja de ser un adorno</h2>
 *
 * El artboard dibuja la pastilla y no dice por que. Aqui la sostiene el sello: un conjunto
 * sellado no se edita, se sustituye por otro con su version. Si la respuesta dijera que **no**
 * esta sellado, la pastilla lo diria — que es la unica manera de que signifique algo.
 */
export interface ValoresProps {
  /**
   * El ejercicio de la barra global. Decide de que conjunto se piden las senas.
   *
   * `null` si la sesion no tiene ejercicio de trabajo fijado (AC8 de I-1). Entonces no se pide
   * nada y la pantalla lo dice: las senas que sostienen la pastilla «Solo lectura» son las de
   * UN ejercicio, y pedir las de un ano elegido aqui pondria una procedencia inventada encima
   * de una tabla de valores.
   */
  readonly ejercicio: string | null;
}

export function Valores({ ejercicio }: ValoresProps) {
  const [pestana, fijarPestana] = useState(0);

  const conjunto = useUno<ConjuntoDelEjercicio>(
    ejercicio === null ? null : RUTAS.conjuntoSellado(ejercicio),
  );
  const predial = useCalculo<DeterminacionIndividual>(RUTAS.calculoIndividual);
  const vehicular = useCalculo<DeterminacionVehicular>(RUTAS.calculoVehicular);
  const arbitrios = useLista<ArbitrioServido>(RUTAS.arbitrios);

  const tabla = TABLAS_DE_VALORES[Math.min(pestana, TABLAS_DE_VALORES.length - 1)];
  if (tabla === undefined) {
    throw new Error('No hay ninguna tabla de valores definida.');
  }

  const cargando = predial.cargando || vehicular.cargando || arbitrios.cargando;
  const filas: readonly FilaDeLaMemoria[] =
    tabla.clave === 'escala'
      ? filasDeLaEscala({
          predial: predial.dato,
          vehicular: vehicular.dato,
          arbitrios: arbitrios.dato,
        })
      : tabla.clave === 'arbitrios'
        ? filasDeLosArbitriosPorZona(arbitrios.dato ?? [])
        : filasDeLosIntereses();

  const errores = [predial.error, vehicular.error, arbitrios.error].filter(
    (error): error is string => error !== null,
  );

  return (
    <main className="kr-marco__lienzo kr-seccion kr-valores">
      <div className="kr-valores__pestanas" role="tablist" aria-label="Tablas de valores">
        {TABLAS_DE_VALORES.map((candidata, i) => (
          <button
            type="button"
            role="tab"
            key={candidata.clave}
            id={`kr-valores-${candidata.clave}`}
            aria-selected={i === pestana}
            aria-controls="kr-valores-cuadro"
            className={`kr-valores__pestana${i === pestana ? ' kr-valores__pestana--actual' : ''}`}
            onClick={() => {
              fijarPestana(i);
            }}
          >
            {candidata.rotulo}
          </button>
        ))}
      </div>

      <div className="kr-valores__cabecera">
        <p className="kr-valores__nota">{tabla.nota}</p>
        <span className="kr-valores__sello">
          <Icono nombre="candado" tamano={13} grosor={2} />
          {conjunto.dato === null
            ? 'Solo lectura'
            : conjunto.dato.sellado
              ? `Solo lectura · conjunto ${String(conjunto.dato.conjuntoId)} v${String(
                  conjunto.dato.version,
                )}, sellado`
              : `Conjunto ${String(conjunto.dato.conjuntoId)} v${String(
                  conjunto.dato.version,
                )}, SIN sellar`}
        </span>
      </div>

      <div className="kr-valores__cuerpo" id="kr-valores-cuadro" role="tabpanel">
        {errores.length > 0 && (
          <Aviso
            tipo="error"
            titulo="No se pudieron leer los valores del ejercicio"
            detalle={errores[0]}
          />
        )}

        {ejercicio === null && (
          <Aviso
            tipo="vacio"
            titulo="La sesión no tiene ejercicio de trabajo fijado"
            detalle={
              'La UIT, la escala y las tablas de arbitrios son las de UN ejercicio, y el backend ' +
              'no ha dicho cuál: «GET /seguridad/sesion» contesta «ejercicioDeTrabajo: null». ' +
              'Elegir uno aquí pondría una procedencia inventada encima de una tabla de valores. ' +
              'Fijarlo es «PUT /seguridad/sesion/ejercicio», y llega con «Cambiar de ejercicio».'
            }
          />
        )}

        {!cargando && filas.length === 0 && (
          <Aviso
            tipo="vacio"
            titulo={`«${tabla.rotulo}» no la publica ninguna operación`}
            detalle={
              'El interés moratorio, el de fraccionamiento, el reajuste por IPM y el arancel de ' +
              'costas son valores normativos, y este sistema los consume de la copia sellada de ' +
              '«normativa» sin republicarlos. Lo que sí publica el contrato es el interés en ' +
              'soles de una obligación, que es el resultado de aplicar la tasa y no la tasa.'
            }
          />
        )}

        <Cuadro
          columnas={tabla.columnas}
          filas={filas}
          rotulo={tabla.rotulo}
          cargando={cargando}
          variante={tabla.clave}
        />

        <p className="kr-valores__pie">{tabla.pie}</p>
      </div>
    </main>
  );
}

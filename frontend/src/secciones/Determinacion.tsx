import { useState } from 'react';

import { Aviso, FechaDeCalculo } from '../ds/index.ts';
import {
  RUTAS,
  type ArbitrioServido,
  type CorridaDelPredial,
  type DeterminacionDeAlcabala,
  type DeterminacionDeEspectaculo,
  type DeterminacionIndividual,
  type DeterminacionVehicular,
} from '../datos/lecturas.ts';
import { useCalculo, useLista, useUno } from '../datos/useRecurso.ts';
import { Cuadro } from './Cuadro.tsx';
import {
  CAMPO_QUE_FALTA,
  SIN_FECHA_DE_CALCULO,
  TIPOS,
  conteo,
  cuadreDelPredial,
  filasDeLaAlcabala,
  filasDeLaCorrida,
  filasDelEspectaculo,
  filasDelPredialIndividual,
  filasDelVehicular,
  filasDeLosArbitrios,
  type ClaveDelTipo,
  type FilaDeLaMemoria,
} from './determinacion.ts';

/**
 * La seccion «Determinación» (clave `territorio`): los seis tipos a la izquierda y la memoria
 * del elegido a la derecha.
 *
 * La clave es `territorio` y el rotulo «Determinación»: lo declara asi `SECS` (`:1241`) y se
 * conserva, como se conserva que `predios` se rotule «Contribuyentes». La clave va al codigo y
 * al slug —`#determinacion`—, y el rotulo a la pantalla.
 *
 * <h2>Cada memoria se pide UNA vez, y cuando se abre su tipo</h2>
 *
 * Cuatro de las seis se piden con `POST`, y no por capricho del verbo: **determinar produce un
 * acto**. Por eso esta pantalla no pide las seis al abrirse —eso serian cuatro determinaciones
 * por entrar a mirar— sino la del tipo que se abre, y **no la repite** al volver: la ruta se
 * fija la primera vez y el efecto no se rehace. Las otras dos son lecturas de verdad
 * —`GET /rentas/predial/corridas/ultima` y `GET /rentas/arbitrios`— y se piden desde el
 * principio.
 *
 * El dia que este backend conteste, la peticion tendra ademas cuerpo —que contribuyente, que
 * ejercicio— y muy probablemente `simulacion: true` mientras solo se mire: el contrato ya
 * publica ese campo. Hoy el proxy ignora el cuerpo a proposito (AC8 de #4), asi que mandar uno
 * inventado escribiria aqui una decision que es del backend.
 *
 * <h2>El conteo del artboard se DERIVA, y donde no hay dato no se escribe</h2>
 *
 * «62,418 cuentas» sale de los registros que leyo la primera etapa de la corrida; «4 servicios»,
 * de cuantos arbitrios contesta la operacion; «3 ejercicios», de cuantas determinaciones trae la
 * memoria vehicular. Mientras la memoria de un tipo no ha llegado, su conteo **esta vacio** en
 * vez de traer la cifra del artboard: es la misma decision que F-3 tomo con el «62,418» de
 * `SECS`, y por el mismo motivo — una cifra en la interfaz sin nada que la respalde.
 *
 * **Espectáculos es el unico que no cuadra con el artboard, y se dice**: alli el nodo declara
 * «84 eventos» y su propia tabla lista tres; `POST /rentas/espectaculos` determina **uno**, y no
 * hay ninguna operacion que liste los espectaculos de un ejercicio. El conteo dice lo que la
 * respuesta trae.
 */

/** Lo que se sabe de un tipo mientras su memoria no ha llegado. */
interface EstadoDelTipo {
  readonly filas: readonly FilaDeLaMemoria[];
  readonly cuantos: number | null;
  readonly cargando: boolean;
  readonly error: string | null;
  /** La fecha a la que esta la memoria entera, cuando la operacion la publica. */
  readonly fechaCalculo: string | null;
  /** El campo que impide dibujar los importes de este tipo, cuando lo hay. */
  readonly loQuePublicaSinFecha: readonly string[] | null;
}

const NADA_TODAVIA: EstadoDelTipo = {
  filas: [],
  cuantos: null,
  cargando: false,
  error: null,
  fechaCalculo: null,
  loQuePublicaSinFecha: null,
};

export function Determinacion() {
  const [elegido, fijarElegido] = useState<ClaveDelTipo>('predial-individual');
  // Los tipos ya abiertos. Una memoria pedida se queda pedida: volver a su nodo no la vuelve a
  // pedir, que con `POST` seria determinar otra vez.
  const [abiertos, fijarAbiertos] = useState<readonly ClaveDelTipo[]>(['predial-individual']);

  const siSeAbrio = (clave: ClaveDelTipo, ruta: string) => (abiertos.includes(clave) ? ruta : null);

  const individual = useCalculo<DeterminacionIndividual>(
    siSeAbrio('predial-individual', RUTAS.calculoIndividual),
  );
  const corrida = useUno<CorridaDelPredial>(RUTAS.ultimaCorrida);
  const arbitrios = useLista<ArbitrioServido>(RUTAS.arbitrios);
  const vehicular = useCalculo<DeterminacionVehicular>(
    siSeAbrio('vehicular', RUTAS.calculoVehicular),
  );
  const alcabala = useCalculo<DeterminacionDeAlcabala>(siSeAbrio('alcabala', RUTAS.alcabala));
  const espectaculo = useCalculo<DeterminacionDeEspectaculo>(
    siSeAbrio('espectaculos', RUTAS.espectaculos),
  );

  const cuadre = individual.dato === null ? null : cuadreDelPredial(individual.dato);

  const estados: Readonly<Record<ClaveDelTipo, EstadoDelTipo>> = {
    'predial-individual': {
      ...NADA_TODAVIA,
      filas: individual.dato === null ? [] : filasDelPredialIndividual(individual.dato),
      // Una determinacion individual es la de UN contribuyente: el que la respuesta nombra.
      cuantos: individual.dato === null ? null : 1,
      cargando: individual.cargando,
      error: individual.error,
      fechaCalculo: individual.dato?.fechaCalculo ?? null,
    },
    'predial-masivo': {
      ...NADA_TODAVIA,
      filas:
        corrida.dato === null ? [] : filasDeLaCorrida(corrida.dato.etapas, corrida.dato.fechaCalculo),
      // Cuantas cuentas abarca la corrida: los registros que leyo su primera etapa.
      cuantos: corrida.dato?.etapas[0]?.registros ?? null,
      cargando: corrida.cargando,
      error: corrida.error,
      fechaCalculo: corrida.dato?.fechaCalculo ?? null,
    },
    arbitrios: {
      ...NADA_TODAVIA,
      filas: arbitrios.dato === null ? [] : filasDeLosArbitrios(arbitrios.dato),
      cuantos: arbitrios.dato?.length ?? null,
      cargando: arbitrios.cargando,
      error: arbitrios.error,
      fechaCalculo: arbitrios.dato?.[0]?.fechaCalculo ?? null,
    },
    vehicular: {
      ...NADA_TODAVIA,
      filas: vehicular.dato === null ? [] : filasDelVehicular(vehicular.dato),
      cuantos: vehicular.dato?.determinaciones.length ?? null,
      cargando: vehicular.cargando,
      error: vehicular.error,
      fechaCalculo: vehicular.dato?.fechaCalculo ?? null,
    },
    alcabala: {
      ...NADA_TODAVIA,
      filas: alcabala.dato === null ? [] : filasDeLaAlcabala(alcabala.dato),
      cuantos: alcabala.dato === null ? null : 1,
      cargando: alcabala.cargando,
      error: alcabala.error,
      loQuePublicaSinFecha: SIN_FECHA_DE_CALCULO['alcabala'] ?? null,
    },
    espectaculos: {
      ...NADA_TODAVIA,
      filas: espectaculo.dato === null ? [] : filasDelEspectaculo(espectaculo.dato),
      cuantos: espectaculo.dato === null ? null : 1,
      cargando: espectaculo.cargando,
      error: espectaculo.error,
      loQuePublicaSinFecha: SIN_FECHA_DE_CALCULO['espectaculos'] ?? null,
    },
  };

  const tipo = TIPOS.find((candidato) => candidato.clave === elegido) ?? TIPOS[0];
  if (tipo === undefined) {
    throw new Error('No hay ningun tipo de determinacion definido.');
  }
  const estado = estados[tipo.clave];

  return (
    <main className="kr-marco__lienzo kr-seccion kr-determinacion">
      <div className="kr-determinacion__lista">
        <p className="kr-determinacion__rotulo">Tipos de determinación</p>
        <div className="kr-determinacion__tipos">
          {TIPOS.map((candidato) => (
            <button
              type="button"
              key={candidato.clave}
              aria-current={candidato.clave === elegido}
              className={`kr-determinacion__tipo${
                candidato.clave === elegido ? ' kr-determinacion__tipo--actual' : ''
              }`}
              onClick={() => {
                fijarElegido(candidato.clave);
                fijarAbiertos((antes) =>
                  antes.includes(candidato.clave) ? antes : [...antes, candidato.clave],
                );
              }}
            >
              <span className="kr-determinacion__tipo-rotulo">{candidato.titulo}</span>
              <span className="kr-determinacion__conteo">
                {conteo(estados[candidato.clave].cuantos, candidato.unidad)}
              </span>
            </button>
          ))}
        </div>
      </div>

      <div className="kr-determinacion__cuadro">
        <div className="kr-determinacion__cabecera">
          <h2 className="kr-determinacion__titulo">{tipo.titulo}</h2>
          <p className="kr-determinacion__nota">{tipo.nota}</p>
          {estado.fechaCalculo !== null && <FechaDeCalculo fecha={estado.fechaCalculo} />}
        </div>

        <div className="kr-determinacion__cuerpo">
          {estado.error !== null && (
            <Aviso
              tipo="error"
              titulo={`No se pudo leer «${tipo.titulo}»`}
              detalle={estado.error}
            />
          )}

          {estado.loQuePublicaSinFecha !== null && (
            <Aviso
              tipo="vacio"
              titulo="Esta determinación llega sin la fecha a la que está calculada"
              detalle={
                `La operación publica ${estado.loQuePublicaSinFecha.join(' y ')} y no publica ` +
                `«${CAMPO_QUE_FALTA}». No existe «el impuesto»: existe el impuesto a una fecha ` +
                '(regla 9), así que sus importes no se dibujan mientras nadie las fecha. Las ' +
                'otras cuatro determinaciones sí la publican.'
              }
            />
          )}

          {/* Una corrida SIMULADA no emite: no deja ninguna deuda en la cuenta corriente. El
              contrato publica `simulacion` y hasta I-4 nadie lo leia, asi que un ensayo y una
              emision de verdad se dibujaban iguales — y la diferencia entre las dos es si esos
              contribuyentes deben algo. Es lo primero que hay que saber al mirar este cuadro. */}
          {tipo.clave === 'predial-masivo' && corrida.dato?.simulacion === true && (
            <Aviso
              tipo="vacio"
              titulo="La última corrida fue una simulación"
              detalle={
                'Se calculó para ver qué saldría, y no emitió: ninguna de estas cuentas tiene ' +
                'deuda por esta corrida. Lo dice la propia respuesta, en «simulacion».'
              }
            />
          )}

          {cuadre !== null &&
            tipo.clave === 'predial-individual' &&
            (!cuadre.insolutoCuadra || !cuadre.totalCuadra) && (
              <Aviso
                tipo="error"
                titulo="El total no cuadra con las filas que se muestran"
                detalle={
                  `La suma de los tramos da ${cuadre.sumaDeLosTramos} y el insoluto publicado es ` +
                  `${individual.dato?.impuestoInsoluto ?? '—'}; con el derecho de emisión da ` +
                  `${cuadre.sumaDeLasPartidas} y el total publicado es ` +
                  `${individual.dato?.totalAPagar ?? '—'}.`
                }
              />
            )}

          <Cuadro
            columnas={tipo.columnas}
            filas={estado.filas}
            rotulo={`Memoria de ${tipo.titulo}`}
            cargando={estado.cargando}
            variante={tipo.clave}
          />
        </div>
      </div>
    </main>
  );
}

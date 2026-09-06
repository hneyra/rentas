import { useEffect, useId, useMemo, useState } from 'react';

import { Icono } from '../ds/index.ts';
import type { Modulo } from './arbol.ts';

/**
 * La paleta de comandos: `Ctrl/Cmd+K` (AC8).
 *
 * <h2>Que lista, y por que solo eso</h2>
 *
 * **Los cuarenta destinos del arbol, y nada mas.** El artboard buscaba tambien
 * contribuyentes por codigo, nombre y documento, pero eso son datos y los datos
 * son de otro issue: una paleta que devolviera «Rufina Medina Medina» seria una
 * cifra inventada con aspecto de dato real. Navegar es lo que el marco sabe
 * hacer hoy, y es lo que ofrece.
 *
 * <h2>Flechas y Enter, que el artboard no tiene</h2>
 *
 * El prototipo solo se opera con el raton: sus resultados son botones y no hay
 * un solo `ArrowDown` en su codigo. **Una paleta de comandos que hay que cerrar
 * para alcanzar con el raton lo que se acaba de escribir con el teclado no sirve
 * para lo que existe**, asi que el recorrido con flechas y la activacion con
 * Enter se anaden aqui. Es la unica pieza del marco que no se porta sino que se
 * escribe, y por eso se dice.
 *
 * El indice se acota en vez de dar la vuelta: en una lista de nueve, dar la
 * vuelta desde el primero al ultimo desorienta mas de lo que ahorra.
 */

/** Un destino alcanzable desde la paleta. */
interface Entrada {
  readonly destino: string;
  readonly rotulo: string;
  readonly modulo: string;
}

/** Cuantos resultados caben sin que la lista tape la pantalla. El artboard: 9. */
const CUANTOS = 9;

export interface PaletaDeComandosProps {
  /**
   * Los modulos que se ofrecen, ya compuestos (I-3).
   *
   * **La paleta es la puerta trasera del AC2 mas facil de olvidar.** Aplanaba `ARBOL` en una
   * constante de modulo —los cuarenta destinos, calculados una vez al cargar el archivo—, asi
   * que aunque el panel escondiera Coactiva, teclear «expediente» en `Ctrl+K` la seguia
   * ofreciendo y `Enter` la abria. Ahora se aplana lo que se ofrece, y nada mas.
   */
  readonly arbol: readonly Modulo[];
  readonly alAbrir: (destino: string) => void;
  readonly alCerrar: () => void;
}

export function PaletaDeComandos({ arbol, alAbrir, alCerrar }: PaletaDeComandosProps) {
  const [consulta, fijarConsulta] = useState('');
  const [indice, fijarIndice] = useState(0);
  const idDeLaLista = useId();

  const entradas: readonly Entrada[] = useMemo(
    () =>
      arbol.flatMap((modulo) =>
        modulo.submodulos.map((submodulo) => ({
          destino: submodulo.clave,
          rotulo: submodulo.rotulo,
          modulo: modulo.rotulo,
        })),
      ),
    [arbol],
  );

  const resultados = useMemo(() => {
    const busqueda = consulta.trim().toLowerCase();
    return entradas.filter(
      (entrada) =>
        busqueda === '' ||
        entrada.rotulo.toLowerCase().includes(busqueda) ||
        entrada.modulo.toLowerCase().includes(busqueda),
    ).slice(0, CUANTOS);
  }, [consulta, entradas]);

  // Al cambiar la consulta, la seleccion vuelve arriba. Sin esto, escribir una
  // letra mas dejaria marcado el cuarto resultado de una lista que ya es otra, y
  // Enter abriria algo que nadie eligio.
  useEffect(() => {
    fijarIndice(0);
  }, [consulta]);

  const activar = (posicion: number) => {
    const elegido = resultados[posicion];
    if (elegido !== undefined) {
      alAbrir(elegido.destino);
    }
  };

  return (
    <>
      <button
        type="button"
        onClick={alCerrar}
        aria-label="Cerrar la paleta de comandos"
        className="kr-marco__velo kr-marco__velo--paleta"
      />
      <div role="dialog" aria-modal="true" aria-label="Buscar" className="kr-marco__paleta">
        <div className="kr-marco__paleta-cabecera">
          <Icono nombre="lupa" tamano={18} grosor={1.8} />
          <input
            type="text"
            /* eslint-disable-next-line jsx-a11y/no-autofocus -- la paleta se abre
               con un atajo de teclado y su unico proposito es escribir en ella:
               mandar el foco a otro sitio obligaria a un tabulador de mas justo
               despues de haber pedido el teclado. */
            autoFocus
            value={consulta}
            onChange={(evento) => fijarConsulta(evento.target.value)}
            onKeyDown={(evento) => {
              if (evento.key === 'ArrowDown') {
                evento.preventDefault();
                fijarIndice((actual) => Math.min(actual + 1, resultados.length - 1));
              } else if (evento.key === 'ArrowUp') {
                evento.preventDefault();
                fijarIndice((actual) => Math.max(actual - 1, 0));
              } else if (evento.key === 'Enter') {
                evento.preventDefault();
                activar(indice);
              }
            }}
            placeholder="Un módulo, un submódulo, una sección…"
            aria-label="Buscar un destino"
            aria-controls={idDeLaLista}
            aria-activedescendant={
              resultados[indice] === undefined ? undefined : `${idDeLaLista}-${String(indice)}`
            }
            className="kr-marco__paleta-entrada"
          />
          <kbd className="kr-marco__tecla">Esc</kbd>
        </div>

        <div id={idDeLaLista} className="kr-marco__paleta-lista">
          {resultados.map((entrada, posicion) => (
            <button
              key={entrada.destino}
              type="button"
              id={`${idDeLaLista}-${String(posicion)}`}
              onClick={() => activar(posicion)}
              onMouseEnter={() => fijarIndice(posicion)}
              aria-current={posicion === indice}
              className={`kr-marco__resultado${
                posicion === indice ? ' kr-marco__resultado--marcado' : ''
              }`}
            >
              <span className="kr-marco__tipo">Ir a</span>
              <span className="kr-marco__resultado-rotulo">{entrada.rotulo}</span>
              <span className="kr-marco__resultado-nota">{entrada.modulo}</span>
            </button>
          ))}
        </div>

        <div className="kr-marco__paleta-pie">
          <span>
            {resultados.length} {resultados.length === 1 ? 'resultado' : 'resultados'}
          </span>
          <span>Ctrl K</span>
        </div>
      </div>
    </>
  );
}

import type { ClaveDeHoja } from '../arbol.ts';
import type { DefinicionDePantalla as Pantalla, PiezaDeLaPantalla as Pieza } from '@kamayuk/ui';

import { GRAFICO_DE_RECAUDACION } from '../../piezas/serieDeAvance.ts';

/**
 * Las cuatro pantallas de **Inicio** (UI-5, #85, AC2).
 *
 * Transcritas de `const PANTALLAS` y `const INSTRUCCIONES` de
 * `frontend/diseno/RentasV8.dc.html`, con las cadenas literales (AC9). Las compara con el
 * artboard —bloque a bloque, campo a campo y tipo a tipo—
 * `verificaciones/pantallas-del-artboard.test.ts`.
 *
 * El `satisfies` no es decorativo: `Partial<Record<ClaveDeHoja, Pantalla>>` es lo que hace que
 * una clave mal escrita —`'ini-panels'`— no compile, en vez de quedarse como una pantalla
 * huerfana que nadie abre nunca.
 *
 * <h2>Y desde #288 lleva `Pantalla<Pieza>`, porque una de las cuatro trae un grafico</h2>
 *
 * `DefinicionDePantalla` por omision solo admite bloques —es la de `kamayuk-lib`#27— y `ini-flujo`
 * lleva ademas `{ tipo: 'delConsumidor' }`, que es el punto de extension de `kamayuk-lib`#44 AC-2.
 * Lo que cambia es el parametro del tipo, no la forma de los bloques: un arreglo de bloques cabe en
 * uno de piezas, asi que las otras tres siguen escritas igual.
 */
export const INICIO = {
  'ini-panel': {
    instruccion: 'revise el avance del ejercicio. Si algo no cuadra, la sección «Trabajo parado» dice qué acto falta.',
    bloques: [
      {
        titulo: 'Ejercicio en curso',
        nota: '',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Emitido del ejercicio', tipo: 'r' },
          { etiqueta: 'Recaudado', tipo: 'r' },
          { etiqueta: 'Avance', tipo: 'r' },
          { etiqueta: 'Contribuyentes activos', tipo: 'r' },
          { etiqueta: 'Observados sin emisión', tipo: 'r' },
        ],
      },
    ],
  },
  'ini-flujo': {
    instruccion: 'elija el tributo y el periodo. El saldo por cobrar es lo que sigue vivo mientras no prescriba.',
    bloques: [
      {
        titulo: 'Emitido contra recaudado',
        nota: 'Por tributo, al día de hoy.',
        campos: [
          { etiqueta: 'Ejercicio', tipo: 's', opciones: ['2026', '2025', '2024'] },
          { etiqueta: 'Desde', tipo: 'd' },
          { etiqueta: 'Hasta', tipo: 'd' },
          {
            etiqueta: 'Tributo',
            tipo: 's',
            opciones: [
              'Todos',
              'Impuesto predial',
              'Arbitrios municipales',
              'Patrimonio vehicular',
              'Alcabala',
              'Multas',
            ],
          },
        ],
        tabla: {
          titulo: 'Cuadre por tributo',
          columnas: [
            { rotulo: 'Tributo', alineadoDerecha: false },
            { rotulo: 'Emitido S/', alineadoDerecha: true },
            { rotulo: 'Recaudado S/', alineadoDerecha: true },
            { rotulo: 'Saldo S/', alineadoDerecha: true },
            { rotulo: 'Avance', alineadoDerecha: true },
          ],
          nota: 'El saldo por cobrar no es deuda perdida: es lo que sigue vivo mientras no prescriba.',
        },
      },
      /*
       * **El grafico que el artboard pide, enganchado por `delConsumidor`** (#288).
       *
       * `diseno/RentasV8.dc.html:438` declara para esta hoja
       * `['Chart', 'Barras horizontales; recharts, que shadcn envuelve']`, y hasta este issue esa
       * serie se dibujaba **solo** como tabla. El componente vive en `src/piezas/`, no en
       * `@kamayuk/ui`: lo decide `kamayuk-lib`#25 y sube alla con el segundo sistema que pida una
       * serie.
       *
       * **Va DESPUES del bloque, y el orden no es estetico.** Los datos del interprete van por
       * indice de pieza —`filas` por indice de bloque, `valores` por `bloque|campo`— y el recorrido
       * numera en anchura, asi que el bloque conserva el 0 mientras nada se le ponga delante. Con
       * el grafico primero, `INI_FLUJO` tendria que repartir sus filas al 1 y sus campos al `1|0`,
       * y equivocarse ahi no da ningun error: pinta la tabla vacia.
       *
       * **Sin `ajustes`**, porque el punto de extension no los tiene: «un dato sin tipo es un
       * contrato que ningun compilador lee». Lo suyo lo lee de `datos.nombrados`, donde el conector
       * deja la serie (`piezas/serieDeAvance.ts`).
       */
      { tipo: 'delConsumidor', clave: GRAFICO_DE_RECAUDACION },
    ],
  },
  'ini-parado': {
    instruccion: 'elija un frente y resuélvalo: cada fila es dinero que no entra por un acto que se puede hacer hoy.',
    bloques: [
      {
        titulo: 'Trabajo parado',
        nota: 'Dinero que no entra por un acto que se puede hacer hoy.',
        campos: [
          {
            etiqueta: 'Módulo',
            tipo: 's',
            opciones: [
              'Todos',
              'Tránsito',
              'Valores',
              'Coactiva',
              'Fiscalización',
              'Autorizaciones',
            ],
          },
          {
            etiqueta: 'Antigüedad mínima',
            tipo: 's',
            opciones: ['Cualquiera', 'Más de 30 días', 'Más de 90 días'],
          },
        ],
        tabla: {
          titulo: 'Frentes abiertos',
          columnas: [
            { rotulo: 'Módulo', alineadoDerecha: false },
            { rotulo: 'Qué falta', alineadoDerecha: false },
            { rotulo: 'Registros', alineadoDerecha: true },
            { rotulo: 'Importe S/', alineadoDerecha: true },
            { rotulo: 'Situación', alineadoDerecha: false },
          ],
          // **Sin `columnaDeInsignia`, y la columna se queda** (#218, y su gemelo en el artboard).
          //
          // La quinta era de insignia —`i: 4` transcrito del artboard, que dibuja ahi un estado
          // del PLAZO: «Vencida», «Por vencer»— y lo que llega a esa celda es
          // `frentes[].porQueCuestaDinero`, que es una FRASE. #175 dejo de pintarla en verde; #183
          // pregunto lo otro —que el backend publique la situacion— y **se cerro sin implementarlo,
          // con la medida delante**: los cuatro puertos de `GET /indicadores/trabajo-parado`
          // devuelven un agregado y ninguno publica la antiguedad de lo que esta parado, y de las
          // nueve filas `PLAZO` del corpus ninguna es el plazo que la administracion tiene para
          // desatascar ninguno de los cuatro frentes.
          //
          // O sea que era un semaforo que **no se podia encender nunca**. Lo que sobra es la
          // insignia, no la columna: «Que falta» dice el frente y esta dice por que cuesta dinero,
          // que es lo que la pantalla existe para decir.
        },
      },
    ],
  },
  'ini-cierre': {
    instruccion: 'cuadre lo contado en caja contra lo registrado. La diferencia es lo que hay que explicar.',
    bloques: [
      {
        titulo: 'Cierre del día',
        nota: 'Lo cobrado en el turno, contra lo contado en caja.',
        campos: [
          { etiqueta: 'Caja', tipo: 's', opciones: ['C-1', 'C-2', 'C-3'] },
          { etiqueta: 'Turno', tipo: 's', opciones: ['Mañana', 'Tarde'] },
          { etiqueta: 'Fecha', tipo: 'd' },
          { etiqueta: 'Cajero', tipo: 'r' },
          { etiqueta: 'Recaudado del turno', tipo: 'r' },
          { etiqueta: 'Diferencia de arqueo', tipo: 'r' },
        ],
        tabla: {
          titulo: 'Lo cobrado por concepto',
          columnas: [
            { rotulo: 'Concepto', alineadoDerecha: false },
            { rotulo: 'Recibos', alineadoDerecha: true },
            { rotulo: 'Efectivo S/', alineadoDerecha: true },
            { rotulo: 'Tarjeta S/', alineadoDerecha: true },
            { rotulo: 'Total S/', alineadoDerecha: true },
          ],
          nota: 'El arqueo se cuadra contra lo contado en caja, no contra lo registrado: la diferencia es lo que hay que explicar.',
        },
      },
    ],
  },
} satisfies Partial<Record<ClaveDeHoja, Pantalla<Pieza>>>;

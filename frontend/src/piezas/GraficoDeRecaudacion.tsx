import type { PropsDeUnaPiezaDelConsumidor } from '@kamayuk/ui';
import { Tarjeta, TarjetaCabecera, TarjetaPie } from '@kamayuk/ui';
import { useTranslation } from 'react-i18next';
import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, XAxis, YAxis } from 'recharts';
import type { BarShapeProps } from 'recharts';

import { FRASES_DEL_GRAFICO } from '../i18n/textosDelMarco.ts';
import { serieDelAvance } from './serieDeAvance.ts';

/**
 * **El grafico de `ini-flujo`: barras horizontales, y la primera pieza del consumidor** (#288).
 *
 * <h2>Que pide el artboard, y donde</h2>
 *
 * `frontend/diseno/RentasV8.dc.html` linea 438, en la hoja `ini-flujo` del modulo Inicio:
 *
 *     ['Chart', 'Barras horizontales; recharts, que shadcn envuelve']
 *
 * El artboard **declara** la pieza y no la dibuja —de esa hoja dibuja los filtros y la tabla
 * «Cuadre por tributo»—, asi que lo que se compara contra el es el tipo: barras horizontales, que
 * en recharts es `layout="vertical"` (la categoria va en el eje Y).
 *
 * <h2>Por que vive aqui y no en `@kamayuk/ui`</h2>
 *
 * Lo decide `kamayuk-lib`#25 con su medida: con un solo sistema pidiendo una serie, subir `Chart`
 * a la libreria haria que **los seis consumidores** cargaran recharts para que lo use uno, y
 * obligaria a acertar la forma de la serie a ciegas. Sube a bloque del interprete **con el segundo
 * sistema que pida una serie**, y entonces su API sale de dos usos en vez de adivinarse de uno. Es
 * la misma regla con la que el interprete espero a su segundo consumidor (`kamayuk-lib`#27).
 *
 * Por eso se engancha por `delConsumidor`, el punto de extension de `kamayuk-lib`#44 AC-2, del que
 * esta es la primera pieza de todo el producto.
 *
 * <h2>Lo que mide la barra es `pct`, y NO un importe</h2>
 *
 * `GET /indicadores/recaudacion` publica emitido, recaudado y saldo como `ImporteConFecha`, o sea
 * **texto decimal**, y el unico numero que publica como numero es `pct` —el avance ya medido, con
 * `avanceConocido` diciendo si se pudo medir—. Darle largo a una barra con un importe exige
 * convertirlo a `number`, que es lo que prohiben la regla 1 y la prohibicion
 * `importe-convertido-a-number`. Asi que la barra dice **cuanto de lo emitido esta recaudado**, que
 * es lo que el bloque titula, y las cifras exactas las sigue diciendo la tabla — que por eso no se
 * quita (AC-5). El detalle esta en `serieDeAvance.ts`.
 *
 * Y es la misma decision que ya tomo la hoja de al lado: la pieza declarada de `ini-panel` es un
 * `Progress` «de avance por tributo», o sea la misma magnitud.
 *
 * <h2>Ni un color propio (AC-4)</h2>
 *
 * Todo lo que pinta sale de los tokens de `@kamayuk/ui`: los que recharts recibe como `prop` van
 * como `var(--color-x)` —que es como `@kamayuk/ui` alimenta a `sonner` en `shadcn/avisos.tsx`— y
 * los rectangulos que este archivo dibuja van con la utilidad de Tailwind, que resuelve al mismo
 * token. El radio es `var(--radius)`, los 3 px del artboard, y entra por CSS —`rx`— porque el
 * `radius` de recharts es un numero y un numero escrito aqui seria el radio de este grafico y no el
 * del producto.
 *
 * Lo vigila `verificaciones/el-grafico-sale-de-los-tokens.test.ts`, sobre el CSS compilado.
 */

/** Los colores que recharts recibe como `prop`. Ni uno propio: todos son tokens del artboard. */
const COLORES = {
  /** Las lineas verticales de la rejilla. */
  rejilla: 'var(--color-linea-2)',
  /** La linea de cada eje. */
  eje: 'var(--color-linea)',
  /** Los rotulos de los ejes. */
  rotulo: 'var(--color-tinta-3)',
  /** La cifra en la punta de cada barra. */
  cifra: 'var(--color-tinta-2)',
} as const;

/** Lo alto de cada fila y del eje, en pixeles. Geometria, no paleta: aqui no hay token que leer. */
const ALTO_DE_UNA_FILA = 34;
const ALTO_DEL_EJE = 34;
/** Lo ancho de la columna de rotulos. Cabe «Patrimonio vehicular», que es el tributo mas largo. */
const ANCHO_DE_LOS_ROTULOS = 150;
/** Las marcas del eje: el avance va de 0 a 100 y no se escala a lo que haya llegado. */
const MARCAS = [0, 25, 50, 75, 100];

/** Una fila de la serie, ya con el rotulo de su punta redactado. */
interface FilaDelGrafico {
  readonly tributo: string;
  readonly avance: number;
  readonly rotulo: string;
}

/**
 * Un rectangulo del grafico, con su color y su radio de un token.
 *
 * Es un `<rect>` y no el `Rectangle` de recharts porque aquel emite un `<path>`, y un `path` no
 * tiene `rx`: el radio solo podria entrar como el numero que recharts pide, y entonces dejaria de
 * ser el del producto. `width` se recorta a cero porque una barra de ancho negativo desaparece sin
 * decir nada, y aqui no puede pasar —el avance no es negativo— pero el dia que la serie cambie es
 * mejor ver una barra vacia que un hueco.
 */
function rectangulo(clase: string) {
  return function Rectangulo({ x, y, width, height }: BarShapeProps) {
    return <rect x={x} y={y} width={Math.max(width, 0)} height={height} className={clase} />;
  };
}

/** La barra: lo recaudado de ese tributo. */
const BarraDelTributo = rectangulo('fill-azul [rx:var(--radius)]');

/**
 * La pista de detras: lo emitido, que es el 100 % del eje.
 *
 * Es lo que hace que la barra se lea como «emitido CONTRA recaudado» y no como una cifra sola, y no
 * es un dato inventado: es el largo del eje, que va de 0 a 100 por declaracion y no por lo que haya
 * llegado.
 */
const PistaDelTributo = rectangulo('fill-esqueleto [rx:var(--radius)]');

/**
 * La pieza, tal como la monta el interprete.
 *
 * Recibe lo mismo que el interprete tiene —`datos`, `traducir`, `textos`, su `clave` y su indice— y
 * **no lleva `ajustes`**: el punto de extension no los tiene, a proposito, porque «un dato sin tipo
 * es un contrato que ningun compilador lee». Lo suyo lo lee de `datos`.
 */
export function GraficoDeRecaudacion({ clave, datos, traducir }: PropsDeUnaPiezaDelConsumidor) {
  const { t } = useTranslation();
  const { barras, sinMedir } = serieDelAvance(datos.nombrados);
  const tantoPorCiento = (avance: number) => t(FRASES_DEL_GRAFICO.tantoPorCiento, { avance });
  const filas: readonly FilaDelGrafico[] = barras.map((barra) => ({
    ...barra,
    rotulo: tantoPorCiento(barra.avance),
  }));

  return (
    <Tarjeta data-grafico={clave}>
      <TarjetaCabecera>{traducir(FRASES_DEL_GRAFICO.titulo)}</TarjetaCabecera>
      {filas.length === 0 ? (
        // Sin serie no se dibuja un grafico vacio: se dice la palabra del hueco de esta pantalla,
        // que es la misma que sale en cada campo. Un lienzo en blanco no distingue «todavia no
        // llego» de «no hay ni un tributo con movimiento».
        <p className="m-0 px-[15px] py-[14px] text-[13px] text-tinta-3">
          {traducir(datos.ausencia.enElCampo)}
        </p>
      ) : (
        <div
          role="img"
          aria-label={traducir(FRASES_DEL_GRAFICO.rotulo)}
          className="px-[15px] pt-[15px] pb-[10px]"
        >
          <ResponsiveContainer width="100%" height={filas.length * ALTO_DE_UNA_FILA + ALTO_DEL_EJE}>
            <BarChart data={[...filas]} layout="vertical" margin={{ top: 0, right: 46, bottom: 0, left: 0 }}>
              <CartesianGrid horizontal={false} stroke={COLORES.rejilla} />
              <XAxis
                type="number"
                domain={[0, 100]}
                ticks={MARCAS}
                tickFormatter={tantoPorCiento}
                stroke={COLORES.eje}
                tick={{ fill: COLORES.rotulo, fontSize: 11.5 }}
              />
              <YAxis
                type="category"
                dataKey="tributo"
                width={ANCHO_DE_LOS_ROTULOS}
                stroke={COLORES.eje}
                tick={{ fill: COLORES.rotulo, fontSize: 12 }}
              />
              {/* Sin animacion: un panel que se abre al entrar no tiene que moverse para decir una
                  cifra, y una barra creciendo desde cero ensena durante medio segundo un avance
                  que no es el que llego. */}
              <Bar
                dataKey="avance"
                shape={BarraDelTributo}
                background={PistaDelTributo}
                isAnimationActive={false}
              >
                {/* El `fill` va como `prop` y no como clase: recharts pone SU gris como atributo
                    del `<text>`, y aunque una clase le gana en la cascada, dejarle el atributo
                    puesto deja un color que nadie eligio escrito en el DOM. */}
                <LabelList dataKey="rotulo" position="right" fill={COLORES.cifra} fontSize={11.5} />
              </Bar>
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}
      {sinMedir === 0 ? null : <TarjetaPie>{t(FRASES_DEL_GRAFICO.sinMedir, { count: sinMedir })}</TarjetaPie>}
    </Tarjeta>
  );
}

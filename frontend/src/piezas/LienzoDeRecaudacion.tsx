import { Bar, BarChart, CartesianGrid, LabelList, ResponsiveContainer, XAxis, YAxis } from 'recharts';
import type { BarShapeProps } from 'recharts';

import type { FilaDelGrafico } from './GraficoDeRecaudacion.tsx';

/**
 * **El lienzo del grafico de `ini-flujo`: lo unico que importa recharts** (#288, #298).
 *
 * <h2>Por que es un archivo aparte, y quien puede importarlo</h2>
 *
 * Porque recharts no viene solo: arrastra `@reduxjs/toolkit`, `react-redux`, `immer`,
 * `victory-vendor` con sus nueve `d3-*` y media docena mas. Medido en #298: **+324,74 kB de JS
 * (+35,6 %)** en el trozo de ENTRADA, pagados por las cuarenta pantallas para que dibuje una.
 *
 * Asi que este archivo **solo se carga con el `import()` dinamico de `GraficoDeRecaudacion.tsx`**,
 * y Rollup lo parte a un trozo que el navegador pide al abrir `ini-flujo` con una serie que
 * dibujar. **Nadie lo importa estaticamente, ni siquiera por una constante**: un solo `import` que
 * no sea `import type` lo devuelve a la entrada con todo lo que arrastra, y el rojo lo da
 * `e2e/el-grafico-no-viaja-en-la-entrada.spec.ts` midiendo el `dist/`. Por eso lo que la tarjeta
 * necesita saber antes de que esto llegue —el alto del lienzo, para reservar su sitio— vive alla y
 * entra aqui como `prop`.
 *
 * El reparto con la tarjeta es este: la tarjeta dice lo que la pantalla tiene que poder leer sin
 * esperar a nadie —el titulo, la palabra del hueco, cuantos tributos no se pudieron medir— y el
 * lienzo dibuja las barras. Lo que se difiere es lo que pesa, y nada que tenga texto propio.
 *
 * <h2>Ni un color propio (#288, AC-4)</h2>
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

/** Lo ancho de la columna de rotulos. Cabe «Patrimonio vehicular», que es el tributo mas largo. */
const ANCHO_DE_LOS_ROTULOS = 150;
/** Las marcas del eje: el avance va de 0 a 100 y no se escala a lo que haya llegado. */
const MARCAS = [0, 25, 50, 75, 100];

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

export interface PropsDelLienzo {
  /** Las barras, ya con el rotulo de su punta redactado por la tarjeta. Nunca vacia: ver la tarjeta. */
  readonly filas: readonly FilaDelGrafico[];
  /** El alto que la tarjeta reservo mientras esto llegaba. El mismo, para que nada salte. */
  readonly alto: number;
  /** Como se escribe una marca del eje. Lo redacta la tarjeta: aqui no se traduce nada. */
  readonly tantoPorCiento: (avance: number) => string;
}

/**
 * Las barras horizontales. En recharts eso es `layout="vertical"`: la categoria va en el eje Y.
 *
 * Se exporta con nombre y la tarjeta lo adapta a lo que `lazy()` pide: un `export default` seria el
 * unico del arbol, y el nombre es lo que un `grep` encuentra.
 */
export function LienzoDeRecaudacion({ filas, alto, tantoPorCiento }: PropsDelLienzo) {
  return (
    <ResponsiveContainer width="100%" height={alto}>
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
        <Bar dataKey="avance" shape={BarraDelTributo} background={PistaDelTributo} isAnimationActive={false}>
          {/* El `fill` va como `prop` y no como clase: recharts pone SU gris como atributo
              del `<text>`, y aunque una clase le gana en la cascada, dejarle el atributo
              puesto deja un color que nadie eligio escrito en el DOM. */}
          <LabelList dataKey="rotulo" position="right" fill={COLORES.cifra} fontSize={11.5} />
        </Bar>
      </BarChart>
    </ResponsiveContainer>
  );
}

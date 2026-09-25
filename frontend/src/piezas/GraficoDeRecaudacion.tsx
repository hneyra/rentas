import type { PropsDeUnaPiezaDelConsumidor } from '@kamayuk/ui';
import { Tarjeta, TarjetaCabecera, TarjetaPie } from '@kamayuk/ui';
import { lazy, Suspense } from 'react';
import { useTranslation } from 'react-i18next';

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
 * <h2>Y el lienzo llega tarde, a proposito (#298)</h2>
 *
 * Lo que dibuja las barras —`LienzoDeRecaudacion.tsx`, el unico archivo que importa recharts— se
 * pide con un `import()` dinamico y **solo cuando hay una serie que dibujar**. Medido en #298:
 * recharts y lo que arrastra eran **+324,74 kB de JS (+35,6 %)** en el trozo de entrada, que
 * pagaban las cuarenta pantallas para que lo usara una.
 *
 * El `lazy()` va AQUI y no sobre la pieza entera en `index.ts`, y es una decision:
 *
 *   · **lo que la pantalla tiene que poder leer no espera a nadie** — el titulo, la palabra del
 *     hueco cuando no hay serie y cuantos tributos no se pudieron medir se dibujan en el primer
 *     pase, como antes; con la tarjeta entera diferida, la guarda de traduccion —que monta las
 *     cuarenta sin datos y de un tiron— habria dejado de ver el texto del grafico sin ponerse roja;
 *   · **sin serie no se pide nada** — la hoja caida o sin tributos dice su hueco sin bajarse
 *     325 kB para no dibujarlos;
 *   · **y el sitio se reserva** — mientras el trozo llega, en el hueco del lienzo hay un esqueleto
 *     del MISMO alto, de modo que la tabla de debajo no salta cuando las barras aparecen.
 *
 * Si el trozo no llega a cargarse, el `import()` rechaza y el error sube a la frontera de la hoja
 * (`src/pantallas/FronteraDeLaHoja.tsx`, #354): se ve, y no se queda un esqueleto para siempre.
 * Lo que si se quedaria para siempre es un `import()` que no contesta nunca, y eso lo ve el arnes:
 * `e2e/el-grafico-no-viaja-en-la-entrada.spec.ts` abre `ini-flujo` con serie y exige las barras.
 *
 * Los colores —ni uno propio, AC-4 de #288— estan en el lienzo, que es donde se pinta.
 */

/** Lo alto de cada fila y del eje, en pixeles. Geometria, no paleta: aqui no hay token que leer. */
const ALTO_DE_UNA_FILA = 34;
const ALTO_DEL_EJE = 34;

/** Una fila de la serie, ya con el rotulo de su punta redactado. */
export interface FilaDelGrafico {
  readonly tributo: string;
  readonly avance: number;
  readonly rotulo: string;
}

/**
 * El lienzo, pedido cuando hace falta. **Esta linea es la unica puerta a recharts**: ver el javadoc
 * de `LienzoDeRecaudacion.tsx` antes de importar de alli cualquier otra cosa que un tipo.
 */
const LienzoDeRecaudacion = lazy(() =>
  import('./LienzoDeRecaudacion.tsx').then((modulo) => ({ default: modulo.LienzoDeRecaudacion })),
);

/**
 * Lo que ocupa el sitio del lienzo mientras llega: su mismo alto, del color del esqueleto.
 *
 * Sin texto, y a proposito: una espera de un instante no tiene nada que decir que la tarjeta no
 * diga ya con su titulo, y una palabra aqui seria una que parpadea. Lo que la figura significa lo
 * dice el `aria-label` de fuera, que ya esta puesto.
 */
function EsperandoElLienzo({ alto }: { readonly alto: number }) {
  return <div data-esperando-el-lienzo className="rounded-[var(--radius)] bg-esqueleto" style={{ height: alto }} />;
}

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
  const alto = filas.length * ALTO_DE_UNA_FILA + ALTO_DEL_EJE;

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
          <Suspense fallback={<EsperandoElLienzo alto={alto} />}>
            <LienzoDeRecaudacion filas={filas} alto={alto} tantoPorCiento={tantoPorCiento} />
          </Suspense>
        </div>
      )}
      {sinMedir === 0 ? null : <TarjetaPie>{t(FRASES_DEL_GRAFICO.sinMedir, { count: sinMedir })}</TarjetaPie>}
    </Tarjeta>
  );
}

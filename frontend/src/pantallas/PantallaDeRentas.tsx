import { Pantalla, type PantallaProps } from '@kamayuk/ui';
import { useTranslation } from 'react-i18next';

import { useTextosDelInterprete } from '../i18n/textosDelMarco.ts';
import { PIEZAS_DE_RENTAS } from '../piezas/index.ts';
import { tonoDe } from './tono.ts';

/**
 * **El interprete de `@kamayuk/ui`, con lo que este sistema le tiene que decir** (#153).
 *
 * <h2>Por que existe esto y no se monta `<Pantalla>` a pelo</h2>
 *
 * Porque desde `kamayuk-lib`#27 el interprete ya no sabe nada de este sistema, y lo que antes
 * sacaba de el por su cuenta entra por `props`:
 *
 * · **`traducir`** — la `t` de `i18next`. Sin ella, la pantalla sale en castellano en cualquier
 *   idioma, y la guarda de cobertura lo veria como texto escapado.
 * · **`textos`** — las tres palabras propias del interprete, traducidas. Ver `textosDelMarco.ts`.
 * · **`tonoDeLaInsignia`** — el reparto de tonos de Rentas, que es vocabulario suyo. Ver `tono.ts`.
 * · **`piezas`** — desde #288, el registro de las piezas que dibuja ESTE sistema: lo que una
 *   definicion nombra con `{ tipo: 'delConsumidor', clave }`. Ver `piezas/index.ts`.
 *
 * Cuatro `props` que hay que pasar **igual** en todos los sitios que dibujan una pantalla —la
 * aplicacion y las guardas que la montan suelta—. Escritas en cada uno, una prueba podria montar
 * `<Pantalla>` con otros valores que los de la aplicacion y medir una pantalla que nadie ve. Aqui
 * se escriben una vez, y lo que la guarda monta es lo que se sirve. Por eso `piezas` esta en el
 * `Omit`: quien monta una pantalla no elige con que piezas se dibuja.
 *
 * **No es una copia del interprete**: no dibuja nada. Lo vigila
 * `verificaciones/el-interprete-es-de-la-libreria.test.ts`.
 */
export type PantallaDeRentasProps = Omit<
  PantallaProps,
  'traducir' | 'textos' | 'tonoDeLaInsignia' | 'piezas'
>;

export function PantallaDeRentas(props: PantallaDeRentasProps) {
  const { t } = useTranslation();
  const textos = useTextosDelInterprete();

  return (
    <Pantalla
      {...props}
      traducir={(texto) => t(texto)}
      textos={textos}
      tonoDeLaInsignia={tonoDe}
      piezas={PIEZAS_DE_RENTAS}
    />
  );
}

import { Tarjeta, TarjetaCabecera, TarjetaCampos, TarjetaNota } from '@kamayuk/ui';
import { useTranslation } from 'react-i18next';

import type { Ausencia, Coordenada } from '../datos.ts';
import { coordenada } from '../datos.ts';
import type { Bloque } from '../tipos.ts';
import { CampoDelBloque } from './CampoDelBloque.tsx';
import { TablaDelBloque } from './TablaDelBloque.tsx';

/**
 * Un bloque: la tarjeta con su cabecera azul, su nota, su rejilla de campos y su tabla.
 *
 * Las cuatro zonas son opcionales salvo la cabecera, y el artboard las usa en todas las
 * combinaciones: hay bloques que solo son una tabla, y bloques que solo son campos.
 */

export interface BloqueDeLaPantallaProps {
  readonly bloque: Bloque;
  /** Lo tecleado y lo sabido, por indice de campo. Lo que no esta aqui no se sabe. */
  readonly valores: Readonly<Record<number, string | boolean>>;
  /** Las filas de su tabla, si se saben. */
  readonly filas?: readonly (readonly string[])[];
  readonly conteo?: string;
  readonly ausencia: Ausencia;
  /** La palabra del hueco para campos concretos. Ver `datos.ts`. */
  readonly ausenciaPorCampo?: ReadonlyMap<Coordenada, string>;
  /** El indice de este bloque, para componer la coordenada de sus campos. */
  readonly indice: number;
  readonly alCambiar: (indiceDelCampo: number, valor: string | boolean) => void;
}

export function BloqueDeLaPantalla({
  bloque,
  valores,
  filas,
  conteo,
  ausencia,
  ausenciaPorCampo,
  indice,
  alCambiar,
}: BloqueDeLaPantallaProps) {
  // El castellano es la clave: ver `src/i18n/i18n.ts`. Aqui no hay nada que inventar — lo que se
  // traduce es exactamente lo que la definicion dice, que es lo que el artboard dibuja.
  const { t } = useTranslation();
  return (
    <Tarjeta>
      <TarjetaCabecera>{t(bloque.titulo)}</TarjetaCabecera>
      {bloque.nota === '' ? null : <TarjetaNota>{t(bloque.nota)}</TarjetaNota>}
      {bloque.campos.length === 0 ? null : (
        <TarjetaCampos>
          {bloque.campos.map((campo, i) => (
            <CampoDelBloque
              // La etiqueta mas su tipo: dos campos del mismo bloque no comparten rotulo en
              // ninguna de las 40 pantallas, y el indice haria que reordenar reusara el control
              // equivocado con el valor del anterior dentro.
              key={`${campo.etiqueta}|${campo.tipo}`}
              campo={campo}
              valor={valores[i]}
              ausencia={ausencia}
              enElCampo={ausenciaPorCampo?.get(coordenada(indice, i))}
              alCambiar={(v) => alCambiar(i, v)}
            />
          ))}
        </TarjetaCampos>
      )}
      {bloque.tabla === undefined ? null : (
        <TablaDelBloque tabla={bloque.tabla} filas={filas} conteo={conteo} ausencia={ausencia} />
      )}
    </Tarjeta>
  );
}

import { Tarjeta, TarjetaCabecera, TarjetaCampos, TarjetaNota } from '@kamayuk/ui';

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
  /** Los valores tecleados, por indice de campo. Lo que no esta aqui vale lo que dice la definicion. */
  readonly valores: Readonly<Record<number, string | boolean>>;
  readonly alCambiar: (indiceDelCampo: number, valor: string | boolean) => void;
}

export function BloqueDeLaPantalla({ bloque, valores, alCambiar }: BloqueDeLaPantallaProps) {
  return (
    <Tarjeta>
      <TarjetaCabecera>{bloque.titulo}</TarjetaCabecera>
      {bloque.nota === '' ? null : <TarjetaNota>{bloque.nota}</TarjetaNota>}
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
              alCambiar={(v) => alCambiar(i, v)}
            />
          ))}
        </TarjetaCampos>
      )}
      {bloque.tabla === undefined ? null : <TablaDelBloque tabla={bloque.tabla} />}
    </Tarjeta>
  );
}

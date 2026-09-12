import {
  Area,
  Calendario,
  Campo,
  Capa,
  Casilla,
  Dato,
  Desplegable,
  DisparadorEmergente,
  Emergente,
  Etiqueta,
  Opcion,
  anchoCompleto,
  tipoDe,
} from '@kamayuk/ui';

import type { Campo as Definicion } from '../tipos.ts';

/**
 * Un campo de la definicion, dibujado con la pieza que le toca.
 *
 * <h2>El reparto sale de `@kamayuk/ui`, no de aqui</h2>
 *
 * `tipoDe()` pela la marca de ancho y devuelve una de las siete letras, y **revienta** con una
 * octava en vez de caer en «campo de texto». Eso importa: una definicion con el tipo mal escrito
 * dibujada como texto se ve perfecta, y lo que era un desplegable de lista cerrada pasa a ser un
 * cuadro donde se teclea cualquier cosa.
 *
 * <h2>Por que el `switch` es exhaustivo y no lleva `default`</h2>
 *
 * Sin `default`, anadir un octavo tipo a `@kamayuk/ui` deja este archivo **sin compilar**, que es
 * donde se quiere que salte. Con `default`, compilaria y dibujaria el tipo nuevo como texto.
 *
 * <h2>El de solo lectura NO se dibuja como campo desactivado</h2>
 *
 * Es un `Dato`: filo discontinuo y `<output>`. No es un campo que «ahora no se puede escribir»,
 * es un valor que esta pantalla no decide — lo calcula el backend—, y esa diferencia es la que
 * evita que alguien intente corregir aqui una cifra que se corrige en otro sitio.
 */

export interface CampoDelBloqueProps {
  readonly campo: Definicion;
  /** El valor actual. `undefined` = el que la definicion trae. */
  readonly valor?: string | boolean;
  readonly alCambiar: (valor: string | boolean) => void;
}

export function CampoDelBloque({ campo, valor, alCambiar }: CampoDelBloqueProps) {
  const tipo = tipoDe(campo.tipo);
  const ancho = anchoCompleto(campo.tipo);
  // «(opcional)» sale de la propia ayuda, como en el artboard: `/opcional/i.test(ayuda)`. No hay
  // un campo aparte que mantener, y la frase que lo dice es la que el usuario lee.
  const ayuda = 'ayuda' in campo ? campo.ayuda : undefined;
  const opcional = ayuda !== undefined && /opcional/i.test(ayuda);

  const comun = { rotulo: campo.etiqueta, ancho, ayuda, opcional } as const;

  switch (tipo) {
    case 's': {
      const opciones = 'opciones' in campo ? campo.opciones : [];
      return (
        <Etiqueta {...comun}>
          <Desplegable
            value={typeof valor === 'string' ? valor : opciones[0]}
            onValueChange={alCambiar}
          >
            {opciones.map((o) => (
              <Opcion key={o} value={o}>
                {o}
              </Opcion>
            ))}
          </Desplegable>
        </Etiqueta>
      );
    }
    case 'r':
      return (
        <Etiqueta {...comun} ayuda={undefined}>
          <Dato>{'valor' in campo ? campo.valor : ''}</Dato>
        </Etiqueta>
      );
    case 'c':
      return (
        <Etiqueta {...comun} ayuda={undefined}>
          <Casilla
            rotulo={'casilla' in campo ? campo.casilla : ''}
            checked={valor === true}
            onCheckedChange={(marcado) => alCambiar(marcado === true)}
          />
        </Etiqueta>
      );
    case 'd':
      return (
        <Etiqueta {...comun}>
          <Emergente>
            {/* El disparador ES el control: es lo que la etiqueta apunta y lo que se enfoca con
                el tabulador. La capa solo lleva el calendario. */}
            <DisparadorEmergente className="w-full box-border border border-borde-campo rounded-sm px-[10px] py-2 bg-superficie text-[13.5px] text-left text-tinta hover:border-borde-hover focus-visible:border-azul focus-visible:ring-[3px] focus-visible:ring-foco outline-none">
              {typeof valor === 'string' && valor !== '' ? valor : 'dd/mm/aaaa'}
            </DisparadorEmergente>
            <Capa>
              <Calendario
                mode="single"
                onSelect={(dia) => alCambiar(dia === undefined ? '' : dia.toLocaleDateString('es-PE'))}
              />
            </Capa>
          </Emergente>
        </Etiqueta>
      );
    case 'a':
      return (
        <Etiqueta {...comun}>
          <Area value={typeof valor === 'string' ? valor : ''} onChange={(e) => alCambiar(e.target.value)} />
        </Etiqueta>
      );
    case '':
    case 't':
      return (
        <Etiqueta {...comun}>
          <Campo value={typeof valor === 'string' ? valor : ''} onChange={(e) => alCambiar(e.target.value)} />
        </Etiqueta>
      );
  }
}

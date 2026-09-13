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

import { useTranslation } from 'react-i18next';

import { MARCA_DE_OPCIONAL } from '../../i18n/textosDelMarco.ts';
import type { Ausencia } from '../datos.ts';
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
 *
 * <h2>Y cuando no hay dato, lo DICE (#97)</h2>
 *
 * Hasta #97 pintaba la cifra de ejemplo del artboard —«S/ 23,725,394.80»— que viajaba dentro de la
 * definicion. En un sistema de recaudacion **eso se lee como real**. Ahora el valor entra por
 * parametro y, cuando no esta, se dibuja la palabra que quien monta la pantalla haya elegido para
 * decir por que — nunca una cifra, y nunca un cero: **un cero es una afirmacion**, y no saber no
 * lo es.
 */

export interface CampoDelBloqueProps {
  readonly campo: Definicion;
  /** Lo tecleado, o —en un campo de solo lectura— lo que se sepa de la API. */
  readonly valor?: string | boolean;
  /** Que decir en el hueco de un campo de solo lectura cuando no hay valor. */
  readonly ausencia: Ausencia;
  /** Y si ESTE campo tiene su propio motivo, el suyo. Ver `datos.ts`. */
  readonly enElCampo?: string;
  readonly alCambiar: (valor: string | boolean) => void;
}

export function CampoDelBloque({
  campo,
  valor,
  ausencia,
  enElCampo,
  alCambiar,
}: CampoDelBloqueProps) {
  const { t } = useTranslation();
  const tipo = tipoDe(campo.tipo);
  const ancho = anchoCompleto(campo.tipo);
  // «(opcional)» sale de la propia ayuda, como en el artboard: `/opcional/i.test(ayuda)`. No hay
  // un campo aparte que mantener, y la frase que lo dice es la que el usuario lee.
  const ayuda = 'ayuda' in campo ? campo.ayuda : undefined;
  const opcional = ayuda !== undefined && /opcional/i.test(ayuda);

  const comun = {
    rotulo: t(campo.etiqueta),
    ancho,
    // La ayuda puede no estar; `t(undefined)` no vale, asi que se traduce solo si la hay.
    ayuda: ayuda === undefined ? undefined : t(ayuda),
    opcional,
    // La palabra del «(opcional)» la dice `@kamayuk/ui` por omision, y hasta #133 llegaba al DOM
    // sin pasar por `t()`: era la unica de `TEXTOS_DE_LA_UI` que este sistema tenia que pasar por
    // su cuenta. Se pasa SIEMPRE, y no solo cuando `opcional` es cierto, porque una propiedad que
    // se pone a veces es una propiedad que un dia se olvida.
    marcaDeOpcional: t(MARCA_DE_OPCIONAL),
  } as const;

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
                {t(o)}
              </Opcion>
            ))}
          </Desplegable>
        </Etiqueta>
      );
    }
    case 'r': {
      const hayDato = typeof valor === 'string' && valor !== '';
      return (
        <Etiqueta {...comun} ayuda={undefined}>
          <Dato
            // `data-sin-dato` no es decoracion: es lo que permite a una guarda contar los huecos
            // de una pantalla sin leer el texto, que cambia con quien la monta.
            data-sin-dato={hayDato ? undefined : ''}
            className={hayDato ? undefined : 'text-tinta-3 italic'}
          >
            {/* El valor NO se traduce: es un dato, y traducir un importe seria absurdo. La
                palabra del hueco si, porque es una frase nuestra. */}
            {hayDato ? valor : t(enElCampo ?? ausencia.enElCampo)}
          </Dato>
        </Etiqueta>
      );
    }
    case 'c':
      return (
        <Etiqueta {...comun} ayuda={undefined}>
          <Casilla
            rotulo={'casilla' in campo ? t(campo.casilla) : ''}
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
              {typeof valor === 'string' && valor !== '' ? valor : t('dd/mm/aaaa')}
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

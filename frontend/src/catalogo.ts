import type { Catalogo, ModuloDelCatalogo } from '@kamayuk/shell';
import { ICONOS, seEscribe, tipoDe, type NombreDeIcono } from '@kamayuk/ui';

import { ARBOL } from './pantallas/arbol.ts';
import { pantallaDe } from './pantallas/definiciones/index.ts';

/**
 * **El catalogo de ESTE sistema**, en la forma que `@kamayuk/shell` entiende.
 *
 * <h2>Por que esta pieza existe</h2>
 *
 * Porque el armazon **no sabe que existe Rentas**, y no puede: ADR-0030 §4 lo prohibe —«una
 * libreria comun no puede contener logica de negocio de un contexto»— y una guarda lo vigila del
 * otro lado. Asi que alguien tiene que traducir los diez modulos y las cuarenta hojas de este
 * sistema a la forma generica, y ese alguien vive aqui. Es literalmente la costura del reparto.
 *
 * <h2>`seEscribe` sale del DATO, y por eso se calcula aqui</h2>
 *
 * El armazon decide las acciones del pie —limpiar y guardar, o exportar e imprimir— segun si la
 * pantalla tiene algun campo que se escriba. Esa pregunta solo la puede contestar quien tiene las
 * definiciones, o sea este sistema. Pasarla como bandera a mano seria dejar la puerta abierta a
 * una pantalla de solo lectura con un boton de guardar que no guarda nada.
 *
 * <h2>Lo que este archivo NO hace</h2>
 *
 * **Traducir sus rotulos.** El catalogo es DATO y se construye una vez, fuera de React: no puede
 * llamar a `useTranslation`. Quien lo traduce es `useCatalogo()`, abajo, que si es un gancho — y
 * de paso vuelve a construirlo cuando el idioma cambie, que es lo que un `const` no haria.
 *
 * **Filtrar por permisos.** El armazon recibe el catalogo YA filtrado —lo dice su javadoc— y quien
 * lo filtra es quien sabe que puede abrir la cuenta. Eso llega cuando la sesion se conecte; hasta
 * entonces se ofrece el catalogo entero, que es lo mismo que hacia la V6 antes de I-3.
 *
 * <h2>El icono se DEDUCE del trazo, y no se escribe</h2>
 *
 * El arbol de este sistema guarda los trazos del artboard; el armazon quiere el NOMBRE de un icono
 * del catalogo de `@kamayuk/ui`. Un mapa a mano —`inicio: 'casa'`, diez lineas— parece mas simple
 * y tiene el defecto de siempre: el dia que el artboard le cambie el icono a un modulo, el mapa
 * sigue compilando y la pantalla dibuja el dibujo anterior. **En verde.**
 *
 * Deduciendolo del trazo no puede: si el dibujo del artboard deja de estar en el catalogo de la
 * libreria, esto **revienta al arrancar** diciendo que modulo y que trazo. Medido: los diez
 * coinciden hoy uno a uno.
 */

/** El nombre del icono cuyo dibujo es exactamente el del modulo. Revienta si no hay ninguno. */
function iconoDelTrazo(rotulo: string, trazos: readonly string[]): NombreDeIcono {
  const nombres = Object.keys(ICONOS) as NombreDeIcono[];
  const casa = nombres.find(
    (n) => ICONOS[n].length === trazos.length && ICONOS[n].every((d, i) => d === trazos[i]),
  );
  if (casa === undefined) {
    throw new Error(
      `El modulo «${rotulo}» dibuja un icono que «@kamayuk/ui» no publica.\n` +
        `  Trazos: ${trazos.join(' | ')}\n\n` +
        '  El dibujo entra primero en el catalogo de la libreria y de ahi se usa aqui — no al\n' +
        '  reves: un trazo suelto en un sistema es un icono que los otros tres no tienen.',
    );
  }
  return casa;
}

/** Si alguna de las pantallas de una hoja tiene un campo que se escribe. */
function laHojaSeEscribe(clave: Parameters<typeof pantallaDe>[0]): boolean {
  return pantallaDe(clave).bloques.some((bloque) =>
    bloque.campos.some((campo) => seEscribe(tipoDe(campo.tipo))),
  );
}

/**
 * El **codigo de modulo** de cada entrada del catalogo, por su clave.
 *
 * El catalogo generico lleva `clave` —el slug, que es lo que viaja al hash— y el backend habla de
 * `codigo` —`RENTAS_REGISTRO`—. Son dos identificadores del mismo modulo y ninguno de los dos
 * sobra: el slug es para la barra de direcciones y el codigo es el que el catalogo de seguridad
 * del clúster usa. La traduccion vive aqui porque es de este sistema.
 */
export const CODIGO_POR_CLAVE: ReadonlyMap<string, string> = new Map(
  ARBOL.map((modulo) => [modulo.slug, modulo.codigo]),
);

export const CATALOGO: Catalogo = ARBOL.map(
  (modulo): ModuloDelCatalogo => ({
    clave: modulo.slug,
    rotulo: modulo.rotulo,
    nota: modulo.nota,
    icono: iconoDelTrazo(modulo.rotulo, modulo.trazos),
    destinos: modulo.hojas.map((hoja) => ({
      clave: hoja.clave,
      rotulo: hoja.rotulo,
      seEscribe: laHojaSeEscribe(hoja.clave),
      // La barra gris de V8: que hay que HACER aqui. Vive en la definicion de la pantalla y no en
      // el arbol —dos registros paralelos de cuarenta claves se desincronizan—, y llega al marco
      // por aqui porque el marco no puede saberla.
      instruccion: pantallaDe(hoja.clave).instruccion,
    })),
  }),
);


/**
 * Un catalogo **con sus rotulos traducidos** (#103).
 *
 * Es una funcion pura y no un gancho, y eso cambio en #105: lo que se traduce ya no es `CATALOGO`
 * entero sino **el que la sesion permite**, que se compone pidiendo tres operaciones. Con un
 * gancho habria que decidir aqui de donde sale el catalogo, y este archivo no lo sabe.
 *
 * **El rotulo del modulo NO se traduce cuando viene del backend**, y esa es la parte sutil: desde
 * #105 lo pisa `GET /seguridad/modulos`, porque el dia que la municipalidad renombre un modulo el
 * arbol tiene que decir el nombre nuevo. Traducirlo lo devolveria al del artboard si coincidieran,
 * y lo dejaria sin traducir si no — las dos cosas malas a la vez. Se traduce lo que es NUESTRO:
 * la nota del modulo y los rotulos e instrucciones de sus destinos.
 */
export function traducirCatalogo(catalogo: Catalogo, t: (clave: string) => string): Catalogo {
  return catalogo.map((modulo) => ({
    ...modulo,
    nota: t(modulo.nota),
    destinos: modulo.destinos.map((destino) => ({
      ...destino,
      rotulo: t(destino.rotulo),
      instruccion: destino.instruccion === undefined ? undefined : t(destino.instruccion),
    })),
  }));
}

import type { Catalogo, ModuloDelCatalogo } from '@kamayuk/shell';
import { ICONOS, type NombreDeIcono } from '@kamayuk/ui';

import { ARBOL } from './pantallas/arbol.ts';
import { laHojaEscribe } from './pantallas/tipos.ts';
import { CONECTORES } from './datos/conectores.ts';
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
 * <h2>`seEscribe` sale del DATO, y desde #291 del dato que corresponde</h2>
 *
 * El armazon decide las acciones del pie —limpiar y guardar, o exportar e imprimir— y el aviso del
 * medio segun si la hoja se escribe. Esa pregunta solo la puede contestar este sistema, y pasarla
 * como bandera a mano dejaria la puerta abierta a una pantalla de solo lectura con un boton de
 * guardar que no guarda nada. Sigue siendo verdad. Lo que #291 midio es que **la puerta estaba
 * abierta igual, por el otro lado**.
 *
 * Hasta #291 la pregunta se contestaba con las definiciones: «¿tiene algun campo que no sea de
 * solo lectura?». Eso da **39 de 40**, porque en un tablero los campos que se escriben son **los
 * filtros**, y con ellas el pie ponia «Nada se escribe hasta que pulse Guardar.» al lado de un
 * «Guardar» deshabilitado en treinta y nueve pantallas — `ACCIONES` de `aplicacion.tsx` solo
 * atiende `imprimir`. Hoy la contesta `laHojaEscribe()`, con el **verbo** que la hoja declara:
 * **15 de 40**, y los diez paneles fuera. El porque entero, con sus parejas medidas, esta en
 * `pantallas/tipos.ts`.
 *
 * <h2>Lo que este archivo NO hace</h2>
 *
 * **Traducir sus rotulos.** El catalogo es DATO y se construye una vez, fuera de React: no puede
 * llamar a `useTranslation`. Quien lo traduce es `traducirCatalogo()`, abajo — era `useCatalogo()`,
 * un gancho, hasta que #105 dejo de traducir `CATALOGO` entero para traducir el que la sesion
 * permite; el javadoc de la funcion cuenta por que dejo de serlo (nombre corregido en #136).
 *
 * **Filtrar por permisos.** El armazon recibe el catalogo YA filtrado —lo dice su javadoc— y quien
 * lo filtra es quien sabe que puede abrir la cuenta. Decia aqui que eso llegaria cuando la sesion
 * se conectase, y que hasta entonces se ofrecia el catalogo entero como hacia la V6 antes de I-3:
 * **llego en #105**, y lo hace `permisos.ts` con lo que contestan las tres de seguridad, a
 * peticion de `datos/useCatalogoPermitido.ts`.
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

/**
 * **Si la direccion de esta hoja lleva un sujeto detras** (#169): `#/con-panel/00000025673`.
 *
 * Se DERIVA del conector y no se escribe en una lista aparte, por lo mismo que `seEscribe` sale de
 * la definicion: dos registros paralelos de claves se desincronizan, y este se desincronizaria en
 * silencio —el marco ignoraria el codigo de la direccion «porque la hoja no lo declara» y la
 * pantalla diria que falta el contribuyente **teniendolo delante en la barra**—. Quien sabe si una
 * hoja necesita sujeto es quien la pide, o sea su conector.
 *
 * **Son DOS formas de llevarlo y las dos declaran el sitio** (#215): `exigeSujeto` —sin el no se
 * pide nada— y `admiteSujeto` —con el se pide ESE, y sin el la primera de la relacion—. Lo que
 * decide si la direccion lo trae es el sitio, y el sitio es el mismo; lo que las separa es que se
 * hace cuando falta, y eso lo resuelve `useDatosDeLaHoja`. Derivar esto solo de la primera dejaria
 * a `fis-res` con `#/fis-res/RDF-2026-000001` **tirado por el marco**, «porque la hoja no lo
 * declara», y abriendo siempre la primera resolucion del padron.
 */
function laHojaLlevaSujeto(clave: Parameters<typeof pantallaDe>[0]): boolean {
  const conector = CONECTORES[clave];
  return conector?.exigeSujeto === true || conector?.admiteSujeto === true;
}

/**
 * **Los parametros que esta hoja lleva en su ruta**: `#/aut-cat?pagina=2&ordenarPor=descripcion`
 * (#172, #186).
 *
 * Se DERIVA del conector, igual y por lo mismo que `enLaRuta.sujeto`: el marco **ignora con aviso**
 * lo que un destino no declara, asi que una lista paralela aqui se desincronizaria en silencio y
 * el sintoma seria el peor de todos —el mando de pagina moveria la direccion, el marco tiraria el
 * parametro, el conector no lo veria y la tabla dibujaria la pagina 0 con el rotulo «Pagina 3»—.
 * Quien sabe que parametros necesita una hoja es quien la pide, o sea su conector.
 */
function losSitiosDeLaHoja(clave: Parameters<typeof pantallaDe>[0]): readonly string[] {
  return (CONECTORES[clave]?.parametros ?? []).map((parametro) => parametro.nombre);
}

/** El `enLaRuta` del destino, o nada si la hoja no lleva ni sujeto ni parametros. */
function enLaRutaDeLaHoja(
  clave: Parameters<typeof pantallaDe>[0],
): { readonly enLaRuta: { sujeto?: boolean; parametros?: readonly string[] } } | Record<never, never> {
  const sujeto = laHojaLlevaSujeto(clave);
  const parametros = losSitiosDeLaHoja(clave);
  if (!sujeto && parametros.length === 0) return {};
  return {
    enLaRuta: {
      ...(sujeto ? { sujeto: true } : {}),
      ...(parametros.length === 0 ? {} : { parametros }),
    },
  };
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
      seEscribe: laHojaEscribe(hoja),
      ...enLaRutaDeLaHoja(hoja.clave),
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

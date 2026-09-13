import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import type { TextosDelArmazon } from '@kamayuk/shell';
import { TEXTOS_DE_LA_UI } from '@kamayuk/ui';

/**
 * **Las palabras que el MARCO dice por su cuenta, traducidas por este sistema** (#133).
 *
 * <h2>El defecto que esto cierra, y por que no se veia</h2>
 *
 * `@kamayuk/shell` dibuja el marco —la barra, el carril, la paleta, la cabecera, el pie y el aviso
 * de cambios sin guardar— y **dice treinta y dos cosas por su cuenta**: «Volver», «Guardar»,
 * «Buscar», «Seguir editando», el nombre accesible de la miga… `kamayuk-lib`#19 las saco a
 * `ConfiguracionDelArmazon.textos`, con el castellano por omision, y hasta este issue **este
 * sistema no pasaba ninguna**.
 *
 * El sintoma no es «el marco no esta traducido»: es que un segundo idioma dejaria la pantalla **a
 * medias** —el cuerpo traducido y el marco en castellano—, que no se lee como un marco sin
 * traducir sino como una traduccion rota. Y la mitad que falta es la que sale en las cuarenta
 * pantallas.
 *
 * <h2>Por que las frases viven en un DATO y no escritas dentro de cada `t()`</h2>
 *
 * Porque el locale de este repositorio **se deriva del dato y no se escribe** —ver
 * `catalogo-de-claves.ts`—, y esa es la unica forma de que no pueda quedarse corto. Con las
 * treinta y dos escritas dentro de las llamadas, el dia que la libreria publique la treinta y
 * tres habria que acordarse de anadirla **a mano** al inventario del locale; y el olvido no
 * produce ningun rojo, porque lo que nadie lista tampoco nadie lo echa de menos.
 *
 * Derivadas de aqui, la cadena es: la libreria anade un texto -> `TextosDelArmazon` crece ->
 * `useTextosDelMarco` **deja de compilar** diciendo cual falta -> al escribirla entra sola en el
 * catalogo de claves -> `el-locale-esta-completo` se pone roja hasta que se regenera el locale.
 *
 * <h2>Y por que el saco entero y no «las que se ven»</h2>
 *
 * Ocho de las treinta y dos **no se dibujan**: seis son nombres accesibles —el boton del carril, el
 * menu de sesion, la miga, el dialogo de la paleta, su lista y la region viva de los avisos— y dos
 * son marcadores de una caja de texto. Un inventario hecho mirando la pantalla se los deja, y son
 * justo los que ya habian llegado **en ingles** desde `sonner` y desde `cmdk` sin que nadie lo
 * notara (`kamayuk-lib`#13 y #19). Aqui entran las treinta y dos o no compila.
 *
 * <h2>Las cuatro funciones llevan un dato dentro, y por eso son interpolacion y no concatenacion</h2>
 *
 * Un numero, un filtro o el rotulo de una hoja caen en distinto sitio en cada idioma. Partir la
 * frase en dos cadenas decide por el traductor donde va el dato; con `{{…}}` lo decide el idioma.
 * Y los dos que llevan una cuenta van con `{{count}}`, que es lo que hace que i18next elija la
 * forma plural — con un ternario en el codigo, los idiomas con mas de dos formas se quedan fuera
 * para siempre (es la misma decision que `{{count}} registro`, ver `i18n.ts`).
 */

/**
 * **Las frases, en castellano, que es la clave** (#103).
 *
 * Son **exactamente** las de `TEXTOS_DEL_ARMAZON`, palabra por palabra: este issue es el
 * andamiaje para que quepa un segundo idioma, no un cambio de lo que se lee. Las cuatro que
 * llevan dato dentro se escriben con sus llaves, que es lo unico que cambia de forma.
 *
 * El `satisfies` no es decoracion: es lo que hace que una entrada de menos aqui no compile.
 */
export const FRASES_DEL_MARCO = {
  // ── La barra global ──────────────────────────────────────────────────────────────────────────
  alternarElCarril: 'Mostrar u ocultar el menu',
  buscar: 'Buscar',
  atajoDeLaPaleta: 'Ctrl K',
  avisosSinLeer: '{{count}} aviso sin leer',
  opcionesDeLaSesion: 'Opciones de la sesion',
  avisos: TEXTOS_DE_LA_UI.avisos,

  // ── El carril de modulos ─────────────────────────────────────────────────────────────────────
  filtrarElCarril: 'Filtrar modulos y destinos',
  modulos: 'Modulos',
  elijaUnDestino: 'Elija el destino que quiere abrir.',
  nadaCasaEnElArbol: 'Ningun modulo ni destino coincide con «{{filtro}}».',
  sinGuardar: 'sin guardar',

  // ── La paleta de mando ───────────────────────────────────────────────────────────────────────
  buscarUnDestino: 'Buscar un destino',
  sugerenciasDeLaPaleta: TEXTOS_DE_LA_UI.sugerencias,
  marcadorDeLaPaleta: 'Un modulo o un destino…',
  cerrarLaPaleta: 'Esc',
  nadaCasaEnLaPaleta: 'Ningun destino coincide con lo que escribio.',
  cuantosDestinos: '{{casan}} de {{count}} destino',

  // ── El cuerpo ────────────────────────────────────────────────────────────────────────────────
  sinDestinoAbierto: 'No hay ningun destino abierto. Elija uno en el arbol de la izquierda.',
  destinoNoOfrecido:
    'Esa direccion no corresponde a ningun destino disponible para esta cuenta. Elija uno en el ' +
    'arbol de la izquierda.',
  ruta: TEXTOS_DE_LA_UI.ruta,

  // ── Las acciones al pie ──────────────────────────────────────────────────────────────────────
  volver: 'Volver',
  limpiar: 'Limpiar',
  guardar: 'Guardar',
  exportar: 'Exportar',
  imprimir: 'Imprimir',
  nadaSeEscribeTodavia: 'Nada se escribe hasta que pulse Guardar.',
  datosDeHoy: 'Los datos son los que figuran a la fecha de hoy.',

  // ── El aviso de cambios sin guardar ──────────────────────────────────────────────────────────
  hayCambiosSinGuardar: '{{rotulo}} tiene cambios sin guardar',
  losCambiosSePierden:
    'Si cierra la pantalla se pierden. Guardelos primero o cierrela descartandolos: eso no se ' +
    'puede deshacer.',
  salirYPerderLosCambios: 'Salir y perder los cambios',
  seguirEditando: 'Seguir editando',
  guardarYCerrar: 'Guardar y cerrar',
} as const satisfies Record<keyof TextosDelArmazon, string>;

/**
 * La marca de un campo que se puede dejar en blanco, que la dice `@kamayuk/ui` y no el armazon.
 *
 * Vive aqui y no dentro de `CampoDelBloque` por lo mismo que las de arriba: para que entre sola en
 * el catalogo de claves. Es la unica de `TEXTOS_DE_LA_UI` que este sistema tiene que pasar por su
 * cuenta — las otras tres que se dibujan aqui —la miga, la region de avisos y la lista de la
 * paleta— llegan **dentro** del saco del armazon, y las dos que envuelven una fecha son de
 * `Importe` y `FechaDeCalculo`, que este repositorio todavia no monta. Lo vigila
 * `verificaciones/todo-el-texto-se-traduce.test.tsx`.
 */
export const MARCA_DE_OPCIONAL = TEXTOS_DE_LA_UI.opcional;

/** Todo lo que este archivo aporta al inventario del locale. Ver `catalogo-de-claves.ts`. */
export function clavesDelMarco(): readonly string[] {
  return [...Object.values(FRASES_DEL_MARCO), MARCA_DE_OPCIONAL];
}

/**
 * El saco que `<Armazon>` recibe, con las treinta y dos ya pasadas por `t()`.
 *
 * **Memorizado sobre `t`, y no recalculado en cada pintada**: el armazon lo mete en un contexto
 * —`ProveedorDeLosTextos`— y un objeto nuevo en cada vuelta volveria a pintar las ocho piezas del
 * marco cada vez que la pantalla cambia una letra. `t` cambia de identidad cuando cambia el
 * idioma, que es exactamente cuando el saco tiene que rehacerse.
 */
export function useTextosDelMarco(): TextosDelArmazon {
  const { t } = useTranslation();

  return useMemo<TextosDelArmazon>(
    () => ({
      alternarElCarril: t(FRASES_DEL_MARCO.alternarElCarril),
      buscar: t(FRASES_DEL_MARCO.buscar),
      atajoDeLaPaleta: t(FRASES_DEL_MARCO.atajoDeLaPaleta),
      avisosSinLeer: (cuantos) => t(FRASES_DEL_MARCO.avisosSinLeer, { count: cuantos }),
      opcionesDeLaSesion: t(FRASES_DEL_MARCO.opcionesDeLaSesion),
      avisos: t(FRASES_DEL_MARCO.avisos),

      filtrarElCarril: t(FRASES_DEL_MARCO.filtrarElCarril),
      modulos: t(FRASES_DEL_MARCO.modulos),
      elijaUnDestino: t(FRASES_DEL_MARCO.elijaUnDestino),
      nadaCasaEnElArbol: (filtro) => t(FRASES_DEL_MARCO.nadaCasaEnElArbol, { filtro }),
      sinGuardar: t(FRASES_DEL_MARCO.sinGuardar),

      buscarUnDestino: t(FRASES_DEL_MARCO.buscarUnDestino),
      sugerenciasDeLaPaleta: t(FRASES_DEL_MARCO.sugerenciasDeLaPaleta),
      marcadorDeLaPaleta: t(FRASES_DEL_MARCO.marcadorDeLaPaleta),
      cerrarLaPaleta: t(FRASES_DEL_MARCO.cerrarLaPaleta),
      nadaCasaEnLaPaleta: t(FRASES_DEL_MARCO.nadaCasaEnLaPaleta),
      // `casan` va como interpolacion normal y `ofrecidos` como `count`: el plural lo decide
      // CUANTOS HAY, no cuantos casan — «1 de 40 destinos», no «1 de 40 destino».
      cuantosDestinos: (casan, ofrecidos) =>
        t(FRASES_DEL_MARCO.cuantosDestinos, { casan, count: ofrecidos }),

      sinDestinoAbierto: t(FRASES_DEL_MARCO.sinDestinoAbierto),
      destinoNoOfrecido: t(FRASES_DEL_MARCO.destinoNoOfrecido),
      ruta: t(FRASES_DEL_MARCO.ruta),

      volver: t(FRASES_DEL_MARCO.volver),
      limpiar: t(FRASES_DEL_MARCO.limpiar),
      guardar: t(FRASES_DEL_MARCO.guardar),
      exportar: t(FRASES_DEL_MARCO.exportar),
      imprimir: t(FRASES_DEL_MARCO.imprimir),
      nadaSeEscribeTodavia: t(FRASES_DEL_MARCO.nadaSeEscribeTodavia),
      datosDeHoy: t(FRASES_DEL_MARCO.datosDeHoy),

      hayCambiosSinGuardar: (rotulo) => t(FRASES_DEL_MARCO.hayCambiosSinGuardar, { rotulo }),
      losCambiosSePierden: t(FRASES_DEL_MARCO.losCambiosSePierden),
      salirYPerderLosCambios: t(FRASES_DEL_MARCO.salirYPerderLosCambios),
      seguirEditando: t(FRASES_DEL_MARCO.seguirEditando),
      guardarYCerrar: t(FRASES_DEL_MARCO.guardarYCerrar),
    }),
    [t],
  );
}

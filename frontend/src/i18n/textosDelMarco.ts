import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';

import type { TextosDelArmazon } from '@kamayuk/shell';
import {
  TEXTOS_DE_LA_UI,
  TEXTOS_DE_LAS_PIEZAS,
  TEXTOS_DEL_INTERPRETE,
  type TextosDeLasPiezas,
  type TextosDelInterprete,
} from '@kamayuk/ui';

import { AVISOS_DE_V8 } from '../pantallas/avisos.ts';

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
 * Y los que llevan una cuenta van con `{{count}}`, que es lo que hace que i18next elija la
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
  // **Los dos avisos del pie NO se escriben aqui** (#281). Salen de `pantallas/avisos.ts`, que es
  // la copia guardada palabra por palabra contra `diseno/RentasV8.dc.html`. Escritos aqui a mano
  // —como estuvieron hasta #281— habia dos copias de cada frase: la vigilada, que no se veia, y
  // esta, que se veia y no la vigilaba nadie. Y ya diferian: a `datosDeHoy` le faltaban las tres
  // palabras «en el padrón» que V8 escribia. Derivadas, cambiar una sola de las dos es imposible.
  nadaSeEscribeTodavia: AVISOS_DE_V8.escritura,
  datosDeHoy: AVISOS_DE_V8.consulta,

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
 * **Las tres palabras que el INTERPRETE dice por su cuenta** (#153).
 *
 * Hasta #153 el interprete vivia aqui y las decia con `t()` escrito dentro de sus piezas: la marca
 * de opcional —que salia de `MARCA_DE_OPCIONAL`, en este mismo archivo—, el marcador de una fecha
 * sin elegir y el conteo de filas de una tabla. Al subir a `@kamayuk/ui` (`kamayuk-lib`#27) las
 * tres entran por `textos` —`TEXTOS_DEL_INTERPRETE`—, porque `i18next` no es `peerDependency` de
 * la libreria.
 *
 * Asi que pasan a ser lo que ya eran las treinta y dos del armazon: **un dato que se traduce**, y
 * no dos llamadas con la frase escrita dentro. Por el mismo motivo que arriba: escritas como literal, el marcador y el
 * conteo solo entraban en el locale porque `el-locale-esta-completo` los listaba a mano; derivados
 * de aqui entran solos, y el dia que la libreria anada una cuarta, `useTextosDelInterprete` deja
 * de compilar.
 *
 * **El conteo va con `{{count}}`** y no con el ternario de `TEXTOS_DEL_INTERPRETE.registros`: el
 * plural lo decide el idioma, y hay idiomas con mas de dos formas.
 */
export const FRASES_DEL_INTERPRETE = {
  opcional: TEXTOS_DEL_INTERPRETE.opcional,
  marcadorDeFecha: TEXTOS_DEL_INTERPRETE.marcadorDeFecha,
  registros: '{{count}} registro',
} as const satisfies Record<keyof TextosDelInterprete, string>;

/**
 * **Las palabras de los MANDOS de una tabla, y la del conteo con total** (#186, #187).
 *
 * <h2>Por que hacen falta el dia que una tabla pagina, y no antes</h2>
 *
 * `<Pantalla textos>` recibe `Partial<TextosDeLaPantalla>`, o sea que lo que este sistema no pasa
 * lo pone la libreria por omision — **en castellano**. Mientras ninguna tabla declaraba
 * `paginacion` ni `orden`, ninguna de estas palabras llegaba al DOM y no faltaba ninguna. Con la
 * primera tabla paginada llegan las dieciseis de golpe: «Anterior», «Siguiente», «Pagina 3 de
 * 4 210», el motivo del mando impedido y los dos nombres accesibles de los desplegables.
 *
 * Sin pasarlas, la pantalla saldria **a medias** en un segundo idioma —el cuerpo traducido y los
 * mandos de la tabla en castellano—, que es exactamente el defecto que #133 cerro para el armazon.
 * Y no lo veria la guarda de cobertura mas que si monta una tabla CON mandos, que es lo que
 * `los-mandos-de-la-tabla-hablan-el-idioma-de-la-sesion` hace.
 *
 * <h2>Por que es un saco aparte y no mas claves en `FRASES_DEL_INTERPRETE`</h2>
 *
 * Porque aquel es `satisfies Record<keyof TextosDelInterprete, string>` y `TextosDelInterprete`
 * son **tres**: una cuarta clave no compila. Los mandos viven en `TextosDeLasPiezas`, que es el
 * saco hermano, y de el se toma **solo lo que se dibuja**: las demas piezas de #44 y #66 —lecturas,
 * actos, acciones— este sistema no las usa, y prometer su traduccion seria inventario que nadie
 * reclama.
 */
export const FRASES_DE_LAS_TABLAS = {
  paginaAnterior: 'Anterior',
  paginaSiguiente: 'Siguiente',
  pagina: 'Pagina {{pagina}}',
  paginaDe: 'Pagina {{pagina}} de {{paginas}}',
  yaEsLaPrimeraPagina: 'Esta es la primera pagina: no hay ninguna antes.',
  noHayMasPaginas: 'No hay ninguna pagina despues de esta.',
  filasPorPagina: 'Cuantas filas por pagina',
  ordenarLaLista: 'Ordenar la lista',
  pasarAAscendente: 'Ordenar de menor a mayor',
  pasarADescendente: 'Ordenar de mayor a menor',
  mandosDeLaTabla: 'Mandos de «{{tabla}}»',
  // Las dos flechas del sentido entran por el saco **aunque sean signos y no palabras**, y no es
  // celo: la libreria lo dice en su propio javadoc —«es un signo, y entra por el saco igual: hay
  // escrituras que lo giran»— y ademas la guarda de cobertura las ve llegar al DOM sin `t()`. En
  // castellano se traducen a si mismas; en una escritura de derecha a izquierda, no.
  flechaAscendente: TEXTOS_DE_LAS_PIEZAS.flechaAscendente,
  flechaDescendente: TEXTOS_DE_LAS_PIEZAS.flechaDescendente,
  celdaSinDato: TEXTOS_DE_LAS_PIEZAS.celdaSinDato,
  porQueLaCeldaNoTieneDato: 'Aqui no hay dato, y no es un cero.',
  tablaSinMotivo: 'Esta lista no tiene filas, y la definicion de la pantalla no dice por que.',
} as const satisfies Record<keyof LasQueSeDibujan, string>;

/**
 * Las de `TextosDeLasPiezas` que este sistema **si** dibuja. Derivado del tipo de la libreria: el
 * dia que una cambie de nombre, esto deja de compilar en vez de dejar una clave huerfana.
 */
type LasQueSeDibujan = Pick<
  TextosDeLasPiezas,
  | 'paginaAnterior'
  | 'paginaSiguiente'
  | 'pagina'
  | 'paginaDe'
  | 'yaEsLaPrimeraPagina'
  | 'noHayMasPaginas'
  | 'filasPorPagina'
  | 'ordenarLaLista'
  | 'pasarAAscendente'
  | 'pasarADescendente'
  | 'mandosDeLaTabla'
  | 'flechaAscendente'
  | 'flechaDescendente'
  | 'celdaSinDato'
  | 'porQueLaCeldaNoTieneDato'
  | 'tablaSinMotivo'
>;

/**
 * **«20 de 1 842»: el conteo de una tabla que sabe su total publicado** (#172, AC2).
 *
 * Esta frase vive aqui y no en el conector porque el conector es **dato** y no tiene `t()`
 * delante: escrita alli, el «de» llegaria al DOM en castellano en cualquier idioma (#103). Lo que
 * el conector entrega es el numero —`TablaRepartida.total`—, y la frase la arma
 * `useDatosDeLaHoja`, que es un gancho.
 */
export const FRASE_DEL_CONTEO = '{{cuantos}} de {{total}}';

/**
 * **«Las cifras son al 17/09/2026»: de cuando son los numeros de esta pantalla** (#196, regla 9).
 *
 * Vive aqui por lo mismo que la de arriba: el conector entrega la fecha **cruda** —`Reparto.aLaFecha`—
 * y la frase la arma `useDatosDeLaHoja`, que es un gancho y tiene `t()` delante. La fecha entra por
 * interpolacion y no concatenada, porque en otro idioma no cae necesariamente al final; y el
 * formato es de `@kamayuk/formato`, que no se traduce.
 *
 * <h2>Por que la pantalla lo dice ARRIBA y no en un campo</h2>
 *
 * Porque regla 9 —RNF-075— obliga a decirlo y **no toda pantalla tiene donde**: `con-panel` dibuja
 * «Fecha de cálculo» y `fis-panel` no tiene ninguno de sus seis campos libre. Anadirle un septimo
 * al artboard seria cambiar el diseno para que quepa un dato.
 */
export const FRASE_DE_LA_FECHA = 'Las cifras son al {{fecha}}.';

/**
 * **«Lo que se dibuja es de MEDINA SILVA, RUFINA (C-00025673)»: de quien es** (#239).
 *
 * Vive aqui por lo mismo que las dos de arriba: el conector entrega **las dos piezas crudas**
 * —`Reparto.deQuienEs`— y la frase la arma `useDatosDeLaHoja`, que tiene `t()` delante. Ni el
 * nombre ni el codigo se traducen: son dato.
 *
 * <h2>Por que la pantalla lo dice ARRIBA y no en un campo</h2>
 *
 * Porque las hojas que toman «la primera de la relacion» no tienen donde. En `fis-actas` el sitio
 * que el artboard le da al titular es un **mando** —un control de entrada—, y escribir dentro el
 * nombre de un acta ya registrada hace que el formulario de alta parezca estar editandola. Es la
 * misma decision de #196 con la fecha, y por el mismo motivo.
 */
export const FRASE_DE_QUIEN_ES = 'Lo que se dibuja es de {{nombre}} ({{codigo}}).';

/**
 * **La otra mitad: el obligado ya no esta en el padron** (#239, #216).
 *
 * `contribuyente` y `codContribuyente` llegan nulos **a la vez**, y el backend dice que eso no es
 * un hueco del contrato sino un hecho: el acta sigue saliendo porque ocultarla esconderia justo el
 * caso que hay que revisar. Con la frase de arriba se leeria «es de undefined (undefined)», y con
 * la palabra de un hueco —«no publicado»— se mandaria a arreglar un backend que no tiene nada que
 * arreglar.
 */
export const FRASE_DE_QUIEN_ES_SIN_PADRON =
  'Lo que se dibuja es de un contribuyente que ya no esta en el padron.';

/**
 * **«No se pudieron pedir los datos de esta pantalla (404)»: el fallo, con su peldano** (#246).
 *
 * Vive aqui por lo mismo que las tres de arriba: el peldano es **dato** y entra por interpolacion,
 * asi que la frase la arma quien tiene `t()` delante —`useDatosDeLaHoja.alFallar`— y no la propia
 * ausencia. Y hasta #246 no vivia en ninguna parte: el codigo se concatenaba dentro de la frase, o
 * sea que la cadena que llegaba al interprete era distinta en cada fallo y **ninguna clave del
 * locale podia casar con ella**.
 */
export const FRASE_DEL_FALLO =
  'No se pudieron pedir los datos de esta pantalla ({{codigo}}). Lo que se ve es su forma, no sus ' +
  'datos.';

/**
 * El saco que `<Pantalla>` de `@kamayuk/ui` recibe como `textos`, ya pasado por `t()`.
 *
 * Memorizado sobre `t`, por lo mismo que el del armazon: cambia de identidad cuando cambia el
 * idioma, que es exactamente cuando el saco tiene que rehacerse.
 */
export function useTextosDelInterprete(): TextosDelInterprete & LasQueSeDibujan {
  const { t } = useTranslation();

  return useMemo<TextosDelInterprete & LasQueSeDibujan>(
    () => ({
      opcional: t(FRASES_DEL_INTERPRETE.opcional),
      marcadorDeFecha: t(FRASES_DEL_INTERPRETE.marcadorDeFecha),
      registros: (cuantos) => t(FRASES_DEL_INTERPRETE.registros, { count: cuantos }),

      // Los mandos de una tabla (#186). Las cifras entran por interpolacion y no concatenadas:
      // en otro idioma «Pagina 3 de 4 210» pone el numero en otro sitio.
      paginaAnterior: t(FRASES_DE_LAS_TABLAS.paginaAnterior),
      paginaSiguiente: t(FRASES_DE_LAS_TABLAS.paginaSiguiente),
      pagina: (pagina) => t(FRASES_DE_LAS_TABLAS.pagina, { pagina }),
      paginaDe: (pagina, paginas) => t(FRASES_DE_LAS_TABLAS.paginaDe, { pagina, paginas }),
      yaEsLaPrimeraPagina: t(FRASES_DE_LAS_TABLAS.yaEsLaPrimeraPagina),
      noHayMasPaginas: t(FRASES_DE_LAS_TABLAS.noHayMasPaginas),
      filasPorPagina: t(FRASES_DE_LAS_TABLAS.filasPorPagina),
      ordenarLaLista: t(FRASES_DE_LAS_TABLAS.ordenarLaLista),
      pasarAAscendente: t(FRASES_DE_LAS_TABLAS.pasarAAscendente),
      pasarADescendente: t(FRASES_DE_LAS_TABLAS.pasarADescendente),
      mandosDeLaTabla: (tabla) => t(FRASES_DE_LAS_TABLAS.mandosDeLaTabla, { tabla }),
      flechaAscendente: t(FRASES_DE_LAS_TABLAS.flechaAscendente),
      flechaDescendente: t(FRASES_DE_LAS_TABLAS.flechaDescendente),
      celdaSinDato: t(FRASES_DE_LAS_TABLAS.celdaSinDato),
      porQueLaCeldaNoTieneDato: t(FRASES_DE_LAS_TABLAS.porQueLaCeldaNoTieneDato),
      tablaSinMotivo: t(FRASES_DE_LAS_TABLAS.tablaSinMotivo),
    }),
    [t],
  );
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

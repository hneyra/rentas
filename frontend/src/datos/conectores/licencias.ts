import { RUTAS, pedirPagina } from '../lecturas.ts';
import type { GiroCiiu, LicenciaDeFuncionamiento, Paginado } from '../lecturas.ts';
import type { Conector, Reparto } from '../conectores.ts';
import { laVentanaDe, laVentanaQueSePide, loQueDijoElServidor } from '../laVentana.ts';

/**
 * **Autorizaciones y licencias: las dos hojas que se pintan enteras** (#168).
 *
 * <h2>Las dos que entran, y con que llenan cada columna</h2>
 *
 * <table>
 *   <tr><th>Hoja</th><th>Operacion</th><th>Cobertura</th></tr>
 *   <tr><td>`aut-cat`</td><td>`GET /licencias/ciiu`</td><td><b>4 de 4</b></td></tr>
 *   <tr><td>`aut-tram`</td><td>`GET /licencias/funcionamiento`</td><td><b>5 de 5</b></td></tr>
 * </table>
 *
 * `aut-cat` · tabla «Giros CIIU»: `codigo` → Codigo CIIU, `descripcion` → Actividad, `seccion` →
 * Materia, `riesgoItse` → Riesgo (la columna de insignia, indice 3).
 *
 * `aut-tram` · tabla «Padron de licencias»: `nroLicencia` → N.º licencia, `contribuyente` →
 * Titular, `denominacionComercial` → Denominacion, `giros[].descripcion` → Giro, `estado` →
 * Estado (la columna de insignia, indice 4).
 *
 * **Ninguna de las dos tiene un solo campo de solo lectura**, y por eso `valores` y
 * `noPublicados` salen vacios en las dos. No es un olvido: los nueve campos que dibujan sus
 * bloques son **mandos de filtro** —una caja de busqueda, seis desplegables y dos fechas—, no
 * cifras. Lo que estas dos pantallas ensenan es su tabla, y la tabla sale entera de la respuesta.
 *
 * <h2>Lo que la operacion NO publica, campo por campo (AC2)</h2>
 *
 * No es «no publica un campo de la tabla» —las nueve columnas de las dos tablas llegan—, sino
 * **no admite un filtro que la pantalla dibuja**. Se dice aqui porque el sintoma es mudo: un
 * desplegable que se mueve y no cambia la tabla se lee como una tabla rota.
 *
 * `aut-cat` · bloque 0:
 * <ul>
 *   <li><b>0|0 «Buscar giro o actividad»</b> — la operacion SI publica el parametro
 *       (`?descripcion=`, y tambien `?codigoCiiu=` y `?seccion=`). Lo que falta no es del backend:
 *       es que `Conector.pedir` recibe hoy **solo una senal de aborto**, asi que no hay por donde
 *       entrar lo tecleado. Es el hueco declarado de este issue.</li>
 *   <li><b>0|1 «Materia»</b> — sus cinco opciones son las del artboard («Todas»,
 *       «Comercializacion», «Alimentos», «Transporte», «Industria») y lo que la operacion filtra
 *       es `?seccion=`, que es una **seccion CIIU** y no esas cinco palabras. Traducir una en otra
 *       aqui seria inventar una tabla de equivalencias que nadie ha publicado.</li>
 *   <li><b>0|2 «Nivel de riesgo»</b> — `riesgoItse` llega en cada fila y **no es un parametro**:
 *       los ocho que la operacion admite no lo incluyen. Filtrar por el sobre la pagina que llego
 *       daria «cuatro giros de riesgo alto» contando sobre veinte de 1 842, que es la clase de
 *       numero que `conectores.ts` prohibe.</li>
 * </ul>
 *
 * `aut-tram` · bloque 0: **los seis mandos**, y por la misma razon. Los ocho parametros que
 * `GET /licencias/funcionamiento` admite son `nroLicencia`, `nombreDelContribuyente`,
 * `denominacionComercial`, `direccion`, `nExpediente`, `ordenarPor`, `pagina` y `tamano`;
 * «Ejercicio», «Tipo de licencia», «Estado», «Agrupado por», «Desde» y «Hasta» no estan entre
 * ellos. Quien los quiera es `POST /licencias/funcionamiento/reportes/padron`, que ademas publica
 * los cuatro totales —`licencias`, `vigentes`, `vencidas`, `canceladas`—; es una **escritura** por
 * el verbo, y las escrituras no entran en esta interfaz (hace una sola, `PUT
 * /seguridad/sesion/ejercicio`).
 *
 * <h2>El catalogo es una VENTANA de 1 842 giros, y la tabla no puede decir lo contrario</h2>
 *
 * El artboard lo deja escrito dos veces: en la pieza declarada de la hoja —«Combobox: el giro CIIU
 * son 1,842 y no caben en un Select»— y en el conteo de su tabla, «4 de 1,842». O sea que la
 * pantalla **nunca** espero la lista entera: espero una ventana con un buscador encima. Por eso
 * `RUTAS.ciiu` pide `?tamano=20` y no `?tamano=1842`.
 *
 * **Lo que queda abierto, y se dice en vez de taparse**: el encabezado de la tabla lo escribe el
 * interprete contando las filas que recibe —«20 registros»—, y `totalElementos` llega en la
 * respuesta sin que nadie lo lea. Decir «20 de 1 842» exige un campo de conteo en `Reparto` y una
 * frase traducible para el «de»; no se hace en este issue porque no es de este issue, y se anota
 * aqui para que no haya que volver a medirlo. Lo que el encabezado dice hoy es **cierto de lo que
 * se ve** —hay veinte filas dibujadas—: no afirma ser el tamano del catalogo, que es la diferencia
 * con el «20 expedientes abiertos» que `coa-panel` evita.
 *
 * <h2>Las otras dos hojas del modulo NO entran, y este es el motivo</h2>
 *
 * · **`aut-panel`** ensena cinco cifras —«En evaluacion», «Con requisitos incompletos», «Con plazo
 *   agotado», «Otorgadas», «Denegadas»—, o sea **un resumen por estado de tramite**. Ninguna
 *   operacion lo publica: los dos reportes que existen agregan por otra cosa. Esta medida es la
 *   que #173 uso para quitarle la operacion que el artboard le daba: desde ahi **no declara
 *   ninguna**, y lo que la pantalla dice es «sin conectar».
 *   `POST /licencias/funcionamiento/reportes/padron` cuenta `licencias`, `vigentes`, `vencidas` y
 *   `canceladas` —estados de la LICENCIA ya emitida, no del expediente en tramite: una licencia
 *   vencida no es una solicitud denegada— y ademas es una escritura;
 *   `GET /licencias/funcionamiento/reportes/resumen-anual` agrega **por ano** (`emitidas`,
 *   `canceladas`, `duplicados`, `vigentesAlCierre`). Y contar los estados sobre la pagina de
 *   `GET /licencias/funcionamiento` —la operacion que su hoja declara— daria cinco numeros exactos
 *   sobre veinte filas de un padron entero: la regla de `conectores.ts` al pie de la letra.
 *
 * · **`aut-sol`** ensena **los requisitos del TUPA** —seis casillas, de «Solicitud-declaracion
 *   jurada» a «Inspeccion tecnica de seguridad»— y `GET /licencias/funcionamiento` **no publica
 *   ningun `documentos[]`**. Quien lo publica es `GET /licencias/edificacion`, con
 *   `documentos[{requisito, presentado, folios}]`, que es otro tramite: los requisitos de una
 *   licencia de edificacion no son los de una de funcionamiento, y pintar unos donde van los otros
 *   seria peor que el hueco. Le faltan ademas «Plazo del TUPA» y «Dias transcurridos», que son las
 *   dos cifras de las que depende el silencio positivo y que **ninguna** de las diecisiete
 *   operaciones de licencias publica.
 */

/**
 * El giro que la columna «Giro» del padron ensena: **el principal**, y el primero si ninguno lo
 * es.
 *
 * Una licencia puede llevar varios giros y la columna es una. Elegir el principal es lo que hace
 * la licencia misma —`principal` es un campo de la respuesta, no una deduccion— y caer en el
 * primero cuando ninguno viene marcado es lo unico que se puede hacer sin inventar: la alternativa
 * seria concatenarlos, y una celda que dice «Bodega · Restaurante · Taller» donde el artboard
 * dibuja un codigo no es la misma columna.
 *
 * Con `giros` vacio devuelve la cadena vacia y **no un guion ni un «ninguno»**: la celda se queda
 * en blanco, que es lo que significa. Escribir ahi una palabra seria afirmar que la licencia no
 * tiene giros, y lo que se sabe es que la respuesta no trajo ninguno.
 */
function giroQueSeEnsena(licencia: LicenciaDeFuncionamiento): string {
  const principal = licencia.giros.find((giro) => giro.principal);
  return (principal ?? licencia.giros[0])?.descripcion ?? '';
}

/** Las dos operaciones de este modulo, escritas una vez: son la llave del contrato (#172). */
const CIIU = 'GET /licencias/ciiu';
const FUNCIONAMIENTO = 'GET /licencias/funcionamiento';

/**
 * `aut-cat` — el catalogo de giros CIIU.
 *
 * Las cuatro columnas de «Giros CIIU» salen de los cuatro campos que las llenan, una a una. No
 * hay ningun campo de solo lectura que decidir: los tres mandos del bloque son filtros, y lo que
 * cada uno no puede hacer esta arriba.
 *
 * <h2>Desde #172 la tabla dice «20 de 1 842» y no «20 registros»</h2>
 *
 * `totalElementos` llegaba en cada respuesta y se tiraba, de modo que el encabezado contaba las
 * filas que tenia delante. Contar veinte no es mentir —hay veinte dibujadas— pero **pierde justo
 * lo que esta pantalla necesita decir**: que son una ventana sobre 1 842 giros, que es lo que el
 * artboard escribe («4 de 1,842») y el motivo por el que dibuja un combobox y no un Select.
 *
 * Lo que viaja es **el numero que la operacion publica**, no uno contado aqui: esa es la mitad
 * que separa esto de lo que `conectores.ts` prohibe. El «de» es una frase y la pone
 * `useDatosDeLaHoja` con `t()`, porque un «de» escrito en este archivo nunca podria traducirse.
 *
 * <h2>Y desde #186 la ventana se mueve de verdad</h2>
 *
 * La tabla declara `paginacion: { en: 'servidor' }` y `orden` con la lista blanca de
 * `CiiuRepositoryJdbc` —`codigo`, `descripcion`, `seccion`, `riesgoItse`—, y el primero es el
 * `ORDEN_POR_OMISION` de `CiiuController`. El interprete escribe la pagina y el campo **en la ruta
 * de la hoja** y este conector los lee: `#/aut-cat?pagina=2&ordenarPor=descripcion`.
 *
 * `hayMas` y `totalPaginas` **los dice el servidor** y viajan por `nombrados`: contar las filas
 * recibidas diria que no hay pagina siguiente justo cuando el tope se alcanza exacto.
 *
 * <h2>El buscador: el parametro YA entra, y lo que falta es el mando</h2>
 *
 * `?descripcion=` esta declarado aqui, o sea que el marco lo conserva en la ruta, entra en la
 * llave de la cache y viaja a la operacion: `#/aut-cat?descripcion=bodega` acota el catalogo de
 * verdad. **Lo que no hay es quien lo escriba desde la pantalla**, y esto esta medido y no
 * supuesto: `@kamayuk/ui` guarda lo tecleado en el estado de `<Pantalla>` (`Tecleado`,
 * `Pantalla.tsx:113`) y **no lo publica por ningun lado** — ni en `nombrados`, que es lo unico
 * que las acciones de #66 resuelven, ni por una `prop` de salida. O sea que un campo del bloque no
 * puede llegar a la ruta, y la caja «Buscar giro o actividad» sigue sin mover nada.
 *
 * Eso es de la libreria y tiene su issue; aqui queda **el canal entero de este lado**, que es lo
 * que #172 pedia.
 */
const AUT_CAT: Conector = {
  clave: ['aut-cat', 'ciiu'],
  parametros: [...laVentanaDe(CIIU), { nombre: 'descripcion', operacion: CIIU }],
  pedir: ({ senal, enLaRuta }) =>
    pedirPagina<GiroCiiu>(
      RUTAS.ciiu({
        ...laVentanaQueSePide('aut-cat', 'giros-ciiu', enLaRuta),
        // Lo tecleado en el buscador, cuando la ruta lo trae. `descripcion` lo publica el
        // contrato; `codigoCiiu` y `seccion` tambien, y entran el dia que haya con que escribirlos.
        ...(enLaRuta.descripcion === undefined ? {} : { descripcion: enLaRuta.descripcion }),
      }),
      senal,
    ),
  repartir: (pagina: Paginado<GiroCiiu>): Reparto => ({
    valores: new Map(),
    filas: new Map(),
    tablas: new Map([
      [
        'giros-ciiu',
        {
          filas: pagina.contenido.map((giro) => ({
            clave: giro.codigo,
            celdas: [giro.codigo, giro.descripcion, giro.seccion, giro.riesgoItse],
          })),
          // El que la OPERACION publica, sobre el catalogo entero. Nunca `contenido.length`.
          totalElementos: pagina.totalElementos,
        },
      ],
    ]),
    nombrados: loQueDijoElServidor('giros-ciiu', pagina),
    noPublicados: new Map(),
  }),
};

/**
 * `aut-tram` — el padron de licencias de funcionamiento.
 *
 * Las cinco columnas de «Padron de licencias» salen de la respuesta, y la de «Giro» del giro
 * principal de cada licencia (ver `giroQueSeEnsena`). Los seis mandos del bloque son filtros que
 * esta operacion no admite, y eso esta dicho arriba en vez de mandarles un parametro inventado.
 *
 * **Y desde #173 la hoja DECLARA la operacion que pide.** Hasta entonces no: el artboard le
 * atribuia `POST …/reportes/padron`, `GET …/reportes/resumen-anual` y
 * `GET /licencias/edificacion/reportes/general`, y le daba a `aut-panel` la que dibuja estas cinco
 * columnas. #168 lo dejo anotado aqui y no toco el arbol, que era lo correcto —es la transcripcion
 * del artboard, y cambiarla sin decidirlo la desincroniza de su guarda—; #173 lo decidio y lo
 * corrigio **en el artboard y en `arbol.ts` a la vez**, como #169 y #179. Las otras tres siguen
 * declaradas: son suyas, y lo que las deja fuera es el verbo.
 */
const AUT_TRAM: Conector = {
  clave: ['aut-tram', 'licencias-de-funcionamiento'],
  parametros: laVentanaDe(FUNCIONAMIENTO),
  pedir: ({ senal, enLaRuta }) =>
    pedirPagina<LicenciaDeFuncionamiento>(
      RUTAS.licenciasDeFuncionamiento(
        laVentanaQueSePide('aut-tram', 'padron-de-licencias', enLaRuta),
      ),
      senal,
    ),
  repartir: (pagina: Paginado<LicenciaDeFuncionamiento>): Reparto => ({
    valores: new Map(),
    filas: new Map(),
    tablas: new Map([
      [
        'padron-de-licencias',
        {
          filas: pagina.contenido.map((licencia) => ({
            clave: licencia.nroLicencia,
            celdas: [
              licencia.nroLicencia,
              licencia.contribuyente,
              licencia.denominacionComercial,
              giroQueSeEnsena(licencia),
              licencia.estado,
            ],
          })),
          totalElementos: pagina.totalElementos,
        },
      ],
    ]),
    nombrados: loQueDijoElServidor('padron-de-licencias', pagina),
    noPublicados: new Map(),
  }),
};

/** Las dos hojas de licencias que piden de verdad. Se montan de una linea en `CONECTORES`. */
export const CONECTORES_DE_LICENCIAS = {
  'aut-cat': AUT_CAT,
  'aut-tram': AUT_TRAM,
} as const;

export { AUT_CAT, AUT_TRAM, giroQueSeEnsena };

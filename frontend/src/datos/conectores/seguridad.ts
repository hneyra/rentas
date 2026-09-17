import type { CeldaDeLaTabla } from '@kamayuk/ui';

import { formatearInstante } from '../../dominio/formato.ts';
import type { MovimientoDeLaBitacora, Paginado } from '../lecturas.ts';
import { RUTAS, pedirPagina } from '../lecturas.ts';
import type { Conector, Reparto } from '../conectores.ts';
import { laVentanaDe, laVentanaQueSePide, loQueDijoElServidor } from '../laVentana.ts';

/**
 * **La bitacora de auditoria, que es la primera hoja cuyo obligatorio sale de la SESION** (#181).
 *
 * <h2>Por que este archivo esta aparte de `conectores.ts`</h2>
 *
 * Por lo mismo que los otros cuatro: el registro es de todos los modulos y un conector es de uno.
 * `conectores.ts` se queda con la regla y con el registro, y alli hay **una linea por modulo**.
 *
 * <h2>Una sola hoja, y de las cuatro de Seguridad es la unica que puede</h2>
 *
 * `seg-panel` ensena usuarios registrados, activos y contrasenas caducadas, y **ninguna de sus
 * tres servidas publica nada de eso**; `seg-acc` pregunta que permiso es propio y cual heredado, y
 * la matriz es una bolsa de codigos planos que no lo distingue; `seg-sis` declara una sola servida
 * y es **la escritura** (`PUT /seguridad/sesion/ejercicio`), que no dibuja una pantalla. Los tres
 * motivos estan medidos en `conectores.ts`.
 *
 * <h2>El ejercicio no se pide al usuario y no se inventa</h2>
 *
 * `GET /seguridad/auditoria` declara `ejercicio` entre sus **obligatorios**
 * —`docs/50-api/parametros-de-la-api.json`, generado de la firma de `SesionController`— y no va en
 * la ruta. El ejercicio de trabajo es del contexto de sesion: lo publica `GET /seguridad/sesion` y
 * lo fija `PUT /seguridad/sesion/ejercicio`, que es la unica escritura encendida de esta interfaz.
 *
 * Asi que el conector declara `exigeEjercicio` y lo recibe ya resuelto. Lo que **no** hace, y es
 * la mitad cara de este issue:
 *
 * <ul>
 *   <li><b>No escribe un literal.</b> Un `2026` aqui contestaria 200 con filas de 2026 para
 *       siempre, incluso abierta en 2031. No hay hueco, no hay error y no hay sintoma.</li>
 *   <li><b>No llama a `new Date().getFullYear()`.</b> El ano del reloj del puesto no es el
 *       ejercicio de trabajo de nadie: medido, la cuenta `administrador` de la instalacion tiene
 *       `ejercicioDeTrabajo: null` —esta en `sesionMedida.ts`, copiado de un `curl`—, o sea que el
 *       caso «la sesion no tiene ejercicio» no es teorico, es el que hay hoy.</li>
 * </ul>
 *
 * Y sin ejercicio **no se manda la peticion**: la pantalla lo dice. Quien decide eso es
 * `useDatosDeLaHoja`, que es donde ya vivia la decision hermana de `exigeSujeto`, y lo vigila su
 * propia guarda. Una auditoria del ejercicio equivocado es peor que una pantalla vacia porque
 * **contesta**: 200, con filas, de otro ano, y nada dice de cual.
 *
 * <h2>La hoja es una TABLA con formulario de filtro, asi que se llena `filas` y no `valores`</h2>
 *
 * Sus seis campos —Usuario, Modulo, Desde, Hasta, Riesgo y «Buscar en el detalle»— son **mandos**:
 * cero de solo lectura, seis que escriben. Por eso `valores` y `noPublicados` salen vacios, igual
 * que en las dos hojas de Licencias, y no es un olvido. Lo que esta pantalla ensena es su tabla.
 *
 * <h2>De donde sale cada columna de «Movimientos», y la que no sale</h2>
 *
 * <table>
 *   <tr><th>Columna</th><th>De donde</th></tr>
 *   <tr><td>0 · Fecha y hora</td><td>`fecha`, que es un `Instant` y llega en <b>UTC</b>. Se
 *     escribe con su marca de zona y no se mueve — ver `formatearInstante`</td></tr>
 *   <tr><td>1 · Usuario</td><td>`usuario`</td></tr>
 *   <tr><td>2 · Acto</td><td>`operacion`, la palabra del vocabulario cerrado que la bitacora
 *     guarda</td></tr>
 *   <tr><td>3 · Detalle</td><td>`tabla`, `clave` y `observacion`, los tres que la operacion
 *     publica, escritos juntos</td></tr>
 *   <tr><td>4 · Riesgo</td><td><b>No la publica nadie.</b> Va la raya del artboard</td></tr>
 * </table>
 *
 * <h2>«Acto» es la palabra que la bitacora guarda, y NO «Anulacion de recibo»</h2>
 *
 * El artboard escribe frases —«Anulacion de recibo», «Cambio de permisos», «Baja de deuda»—. Lo
 * que la operacion publica son dos campos sueltos: `operacion`, que es uno de los siete valores
 * del `CHECK` de `auditoria.operacion` (`ALTA`, `MODIFICACION`, `BAJA`, `ANULACION`, `REVERSION`,
 * `PERMISO`, `ACCESO`), y `tabla`, que es el nombre de una tabla de la base. Componer la frase del
 * artboard con los dos exigiria una tabla de equivalencias —de `recibo` a «recibo», de
 * `usuario_acceso` a «permisos»— que **nadie ha publicado**, y escribirla aqui es inventarla: el
 * mismo motivo por el que `aut-cat` no traduce sus cinco materias a secciones CIIU.
 *
 * Asi que «Acto» dice el acto, y `tabla` se ensena al lado, en «Detalle», donde dice sobre que.
 *
 * <h2>«Detalle» junta tres campos publicados, y eso no es calcular</h2>
 *
 * `tabla · clave · observacion`, con el punto medio del artboard —que es el separador que ya usa
 * `coactiva.ts` para juntar un importe con su fecha, y no una palabra: lo que sale de aqui es
 * **dato**, y un «en» escrito en este archivo seria castellano que nunca podria traducirse (#103)—.
 *
 * Ninguna de las tres se deduce: las tres llegan en la respuesta. Es el mismo trato que
 * `cuotasDe()` le da a los dos extremos de un periodo. Y la `observacion` esta ahi porque **no
 * puede faltar**: la regla 10 dice que sin observacion del usuario no se guarda nada, asi que es
 * el unico campo de la bitacora que cuenta que se hizo con palabras de quien lo hizo.
 *
 * Lo que **no** entra en la celda son `datosAnteriores` y `datosNuevos`, que la operacion tambien
 * publica: son el volcado del registro entero antes y despues, y meterlos en una celda de tabla
 * llenaria la pantalla con dos JSON. Son el detalle del detalle, y eso es una hoja lateral que
 * esta pantalla no tiene. Queda dicho aqui para no volver a medirlo.
 *
 * <h2>«Riesgo» no la publica NADIE, y no se deduce del acto</h2>
 *
 * Ninguna de las operaciones del contrato publica un riesgo, una criticidad ni nada que se le
 * parezca: `AuditoriaResource` tiene doce campos y ninguno es ese. El artboard ensena «Alto»,
 * «Alto», «Alto», «Medio», y ademas dibuja un desplegable para filtrar por ello.
 *
 * Deducirlo seria facil y seria exactamente lo prohibido: una anulacion es alta, una modificacion
 * es media, un acceso es bajo. Eso es **una clasificacion de riesgo**, la clase de cosa que se
 * defiende en una auditoria, y escribirla en este archivo la convertiria en politica de la
 * municipalidad decidida por quien escribio una interfaz. El dia que discrepara de la del auditor,
 * la pantalla estaria calificando actos con criterio propio y en verde.
 *
 * Asi que la celda lleva la raya del artboard, que es lo que `coactiva.ts` ya hace con las celdas
 * que su operacion no llena. Y se ve raro a proposito: **una columna entera de rayas nombra lo que
 * falta publicar**, que es justo lo que un valor plausible no haria nunca.
 *
 * <h2>Lo que `kamayuk-lib`#87 ya sabe hacer con esta tabla, y por que aqui todavia no llega</h2>
 *
 * Se miro antes de escribir esto, y **tres de sus cuatro piezas son para esta pantalla**. No se
 * usan hoy, y el motivo no es que no sirvan: es que **el canal por el que esta hoja entrega sus
 * filas no las admite**, medido en la libreria y no supuesto.
 *
 * <ul>
 *   <li><b>`sinDato` y la celda `{ texto: null, nota }`</b> (`celda-nula-con-palabra-y-nota`) es
 *       exactamente lo que le falta a «Riesgo»: pinta la palabra —la raya, por omision— y ademas
 *       un `title` con <b>por que</b> no hay dato, que es lo que una raya sola no dice. Pero la
 *       celda ancha solo viaja por `FilaDeLaTabla.celdas`, o sea por las tablas <b>con
 *       nombre</b> (`DatosDeLaPantalla.tablas`); el canal de esta hoja es el del indice de bloque
 *       —`DatosDeLaPantalla.filas`—, que sigue declarado `readonly (readonly string[])[]`
 *       (`interprete/datos.ts` y `BloqueDeLaPantalla.tsx:39`), asi que un `null` no cabe. Pasarla
 *       a tabla con nombre exige que `Reparto` gane ese canal, que es ensanchar el contrato del
 *       conector — lo mismo que #172 hace por el otro lado. Mientras tanto va la raya, que es
 *       <b>lo mismo que la libreria pinta</b> para un nulo sin palabra propia; lo que se pierde
 *       es la nota.</li>
 *   <li><b>`paginacion: { en: 'servidor' }` y `orden`</b> son para una tabla de <b>84 182</b>
 *       filas, que es esta. El interprete escribe la pagina y el campo en la RUTA y quien la lee
 *       pide; aqui falta lo mismo por los dos lados: `Conector.pedir` no recibe la ruta —#172—, y
 *       `paginacion.hayMas` se lee de `nombrados` (`TablaDelBloque.tsx:158`), que es un tercer
 *       canal que `Reparto` tampoco tiene.</li>
 *   <li><b>`vacioConSalida`</b> diria «este ejercicio no tiene ningun movimiento» con su boton,
 *       en vez del aviso del saco. Es de la <b>definicion</b> de la pantalla, no del conector, y
 *       tocarla arrastra el inventario de traducciones (`i18n/catalogo-de-claves.ts`) — otro
 *       issue, y no este.</li>
 * </ul>
 *
 * Los tres estan medidos arriba para que no haya que volver a medirlos, y viven en **#187**, que
 * este PR abre. Lo que **no** se hace es escribir aqui una paginacion a mano: la libreria ya la
 * tiene, y una segunda en `rentas` seria la que habria que retirar despues.
 *
 * <h2>Lo que la pantalla dibuja y NO se manda: los seis mandos</h2>
 *
 * Y aqui la mitad que falta no es del backend, que los publica casi todos. `GET
 * /seguridad/auditoria` admite `usuario`, `tabla`, `operacion`, `desde`, `hasta`, `ordenarPor`,
 * `pagina`, `tamano` y `direccion`:
 *
 * <ul>
 *   <li><b>«Usuario», «Desde» y «Hasta»</b> — la operacion los publica tal cual (`usuario`,
 *       `desde`, `hasta`). Lo que falta es por donde entrarlos: `Conector.pedir` no recibe lo que
 *       la pantalla sabe. Es el hueco declarado de <b>#172</b>, y este issue no lo cierra. Los
 *       otros cuatro opcionales —`pagina`, `tamano`, `ordenarPor`, `direccion`— ya no son de
 *       #172: desde `kamayuk-lib`#87 tienen mecanismo, y esta escrito arriba lo que cuesta.</li>
 *   <li><b>«Modulo»</b> — sus seis opciones son modulos del sistema («Rentas», «Tesoreria»,
 *       «Catastro»…) y lo que la operacion filtra es <b>`tabla`</b>, que es una tabla de la base.
 *       Traducir una en otra aqui seria inventar la misma tabla de equivalencias que «Acto»
 *       rechaza — y ademas dos de esas seis opciones, Tesoreria y Catastro, son de <b>otros
 *       sistemas</b> desde ADR-0029: sus tablas no estan en esta base.</li>
 *   <li><b>«Riesgo»</b> — no es un parametro, y no puede serlo mientras el riesgo no exista como
 *       dato. Filtrar por el sobre la pagina que llego daria «tres movimientos de riesgo alto»
 *       contando sobre veinte de 84 182, que es la clase de numero que `conectores.ts` prohibe.</li>
 *   <li><b>«Buscar en el detalle»</b> — la operacion **no publica ningun parametro de texto
 *       libre**: acota por `usuario`, `tabla` y `operacion`, que son columnas, y no por el
 *       contenido de `observacion`. Buscar aqui sobre las veinte filas que llegaron diria «no hay
 *       ninguno» sobre una bitacora que si lo tiene.</li>
 * </ul>
 */

/**
 * **La celda que llego sin dato, con el motivo dentro** (`kamayuk-lib`#87, #187).
 *
 * Era la raya del artboard escrita como cualquier otra cadena, y la raya **sigue siendo lo que se
 * ve**: la declara la tabla en su `sinDato`. Lo que cambia es que ahora la celda dice **por que**,
 * anunciado con `title`, en vez de dejar el motivo en el javadoc de este archivo donde no lo lee
 * quien mira la pantalla.
 *
 * `texto: null` es «aqui no hay dato»: nunca `''` —que se lee como un blanco— y nunca un `0`
 * —que en una bitacora de auditoria se leeria como «riesgo cero»—.
 */
const sinDato = (porQue: string): CeldaDeLaTabla => ({ texto: null, nota: porQue });

/**
 * El motivo de «Riesgo», escrito UNA vez: es el que viaja en la celda y el que explica el javadoc.
 *
 * Es dato y no se traduce, igual que la celda: lo que se traduce es la palabra de la tabla.
 */
const SIN_RIESGO_PUBLICADO =
  'Ninguna operacion del contrato publica un riesgo: AuditoriaResource tiene doce campos y ' +
  'ninguno es ese. Deducirlo del acto —una anulacion es alta, un acceso es bajo— seria escribir ' +
  'aqui una clasificacion de riesgo que nadie ha aprobado.';

/** El punto medio del artboard. Es un separador, no una palabra: lo que sale de aqui es dato. */
const JUNTO = ' · ';

/**
 * Lo que dice la columna «Detalle»: sobre que fue, cual, y que dijo quien lo hizo.
 *
 * Los tres campos llegan en la respuesta y **ninguno se deduce**. Los vacios se caen en vez de
 * dejar un punto medio suelto: una celda que empieza por «· » se lee como un dato roto, y lo que
 * pasa es que ese campo vino vacio.
 */
function detalleDelMovimiento(movimiento: MovimientoDeLaBitacora): string {
  return [movimiento.tabla, movimiento.clave, movimiento.observacion]
    .filter((trozo) => trozo.trim() !== '')
    .join(JUNTO);
}

/**
 * `seg-aud` — la bitacora de auditoria, del ejercicio de trabajo de la sesion.
 *
 * `exigeEjercicio`, que es lo que hace que sin ejercicio no salga ni una peticion. El `?? 0` de
 * abajo **no se alcanza nunca** y esta escrito asi a proposito: el compilador no sabe que
 * `useDatosDeLaHoja` no llama a `pedir` sin ejercicio, y un `!` mentiria sobre quien lo garantiza.
 * Un cero no es un ejercicio valido —el backend admite de 1990 a 2100— asi que si algun dia se
 * alcanzara, lo que llegaria seria un 422 ruidoso y no una pagina de otro ano.
 */
const SEG_AUD: Conector = {
  clave: ['seg-aud', 'auditoria'],
  exigeEjercicio: true,
  // La ventana de «Movimientos». El `ejercicio` NO esta aqui y no puede estarlo: sale de la
  // sesion, no de la ruta. Ver el javadoc de `Conector.exigeEjercicio`.
  parametros: laVentanaDe('GET /seguridad/auditoria'),
  pedir: ({ senal, ejercicio, enLaRuta }) =>
    pedirPagina<MovimientoDeLaBitacora>(
      RUTAS.bitacoraDe(ejercicio ?? 0, laVentanaQueSePide('seg-aud', 'movimientos', enLaRuta)),
      senal,
    ),
  repartir: (pagina: Paginado<MovimientoDeLaBitacora>): Reparto => ({
    valores: new Map(),
    // Vacio: esta tabla lleva `clave` desde #187, asi que sus filas van por `tablas` — que es el
    // camino cuyas celdas pueden decir que no hay dato, y el unico por el que viaja el total.
    filas: new Map(),
    tablas: new Map([
      [
        'movimientos',
        {
          filas: pagina.contenido.map((movimiento) => ({
            // La clave de React: dos movimientos del mismo usuario y del mismo acto no comparten
            // el instante, y aun asi el unico identificador de verdad es el de la fila.
            clave: String(movimiento.id),
            celdas: [
              formatearInstante(movimiento.fecha),
              movimiento.usuario,
              movimiento.operacion,
              detalleDelMovimiento(movimiento),
              sinDato(SIN_RIESGO_PUBLICADO),
            ],
          })),
          // 84 182 movimientos, dichos por la operacion y no contados aqui: la pagina trae veinte.
          totalElementos: pagina.totalElementos,
        },
      ],
    ]),
    // `hayMas` y `totalPaginas` los dice el SERVIDOR (#187, AC2).
    nombrados: loQueDijoElServidor('movimientos', pagina),
    noPublicados: new Map(),
  }),
};

/**
 * La unica hoja de Seguridad que pide de verdad. Se monta de una linea en `CONECTORES`.
 *
 * Un mapa por modulo, como Licencias (#168), Coactiva (#170), Inicio (#167) y Consultas (#169).
 */
export const CONECTORES_DE_SEGURIDAD = {
  'seg-aud': SEG_AUD,
};

export { SEG_AUD, SIN_RIESGO_PUBLICADO, detalleDelMovimiento, sinDato };

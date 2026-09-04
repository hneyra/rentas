/* Genera docs/50-api/openapi/rentas-v1.yaml a partir de los `endpoint` que declara
   cada pantalla del prototipo de interfaz (docs/50-api/prototipo/sgtm-data-*.js).

   DONDE VIVE EL PROTOTIPO, y por que aqui. En `sgtm` estos cinco archivos estaban en
   `design/`, junto a los artboards y el handoff de la interfaz. En el corte, `design/`
   se queda con la interfaz y NO viaja a `rentas`; pero estos cinco archivos no son un
   activo de diseno, son LA ENTRADA DE ESTE GENERADOR, y sin ellos el contrato dejaria
   de poder reproducirse. Eso es exactamente el defecto que #312 encontro y que
   `--comprobar` existe para impedir: un YAML valido, cumplido por el backend, y que
   nadie puede regenerar. Viajan, y viajan con nombre propio y fuera de `design/`.

   Por que se genera y no se escribe a mano: el contrato tiene 134 operaciones y
   su fuente de verdad son las pantallas. Escribirlo a mano garantizaria que se
   desincronizara con el prototipo en la primera semana.

   Lo que este generador NO inventa: los esquemas de cuerpo y respuesta. Cada
   operacion queda con su verbo, su ruta, sus parametros y de que pantalla sale.
   El esquema de cada recurso se escribe cuando se implemente la operacion, y
   entonces esta generacion pasa a ser el punto de partida, no el destino.

   Lo que si declara, porque es lo que la interfaz manda: **los filtros de cada
   pantalla y, en las que traen tabla, el orden y la pagina.** El prototipo
   dibuja los filtros pero no dice como viajan; el contrato lo dice, con el
   mismo nombre de campo que usa el catalogo portado —una prueba del frontend
   exige que coincidan—. Filtrar, ordenar y paginar en el cliente una pagina de
   un padron de cientos de miles de filas ordena media tabla y miente, asi que
   los tres viajan al servidor.

   **Este generador reproduce el contrato comprometido byte a byte** (#312). No lo
   hizo durante quince issues: cada onda afino el YAML a mano —una respuesta 307,
   un filtro que la pantalla no dibuja, una descripcion que el prototipo no puede
   saber— y aplico al archivo solo el diff aditivo, de modo que regenerar en limpio
   borraba 519 lineas y dos operaciones enteras. Lo afinado esta ahora aqui, en las
   tablas de mas abajo: `DEL_BACKEND` para los parametros, `DESCRIPCIONES` para lo
   que el prototipo no dice, `RESPUESTAS` para lo que el generador no sabria
   inventar y `OPERACIONES_ADICIONALES` para los verbos sin pantalla propia.

   El YAML sigue siendo la verdad: **lo que se mueve es el generador**. Si el
   contrato y esta salida discrepan, lo que hay que corregir es lo de aqui.

   Uso: node docs/50-api/generar-openapi.mjs
        node docs/50-api/generar-openapi.mjs --comprobar   no escribe; falla si no cuadra
*/

import { createContext, runInContext } from 'node:vm';
import { readFileSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const raiz = new URL('../../', import.meta.url);
const origen = new URL('docs/50-api/prototipo/', raiz);
const destino = new URL('docs/50-api/openapi/rentas-v1.yaml', raiz);

const ventana = {};
const contexto = createContext({ window: ventana, Object, Array, JSON, Math, String, Number });
for (let i = 1; i <= 5; i++) {
  runInContext(readFileSync(fileURLToPath(new URL(`sgtm-data-${i}.js`, origen)), 'utf8'), contexto);
}

const NAV = ventana.SGTM_NAV;
const PANTALLAS = ventana.SGTM_SCREENS;

/* ── Nombres de parametro ─────────────────────────────────────────────────
   Misma regla que `frontend/scripts/portar-catalogo.mjs`: `Tipo de Vía` →
   `tipoDeVia`. Esta duplicada a proposito —los dos generadores viven en arboles
   distintos y no comparten build— y una prueba del frontend exige que los dos
   produzcan el mismo nombre para el mismo filtro. Si se separan, se pone roja.

   ── Los once nombres con mayuscula en la segunda letra (#539) ────────────
   De un rotulo como «D.N.I.» esta regla saca `dNI`, y de «Nº de expediente»,
   `nExpediente`. Son once nombres distintos en 19 parametros del contrato:
   nExpediente, rUC, nDePrograma, nDeExpediente, nDeCertificado, dNI, nPlaca,
   nNotificacion, nLiquidacion, nDeFicha y nDeConstancia. Cada uno invita a la
   errata —la mayuscula esta donde nadie la escribe—, y #539 nacio de esa
   errata: `?dni=` devolvia el padron entero de Catacaos con 200.

   **Se conservan, y no es por comodidad.** Tres motivos, en este orden:

   1. El contrato esta DERIVADO del prototipo (#312) y el nombre lo produce el
      rotulo del manual. Meterle una tabla de excepciones —«dNI se publica como
      dni»— convierte el nombre en un dato que alguien mantiene a mano, que es
      lo que este generador existe para no tener.
   2. Renombrar solo aqui deja al controlador leyendo el nombre viejo y a la
      pantalla mandando el nuevo: un filtro que deja de filtrar, o sea ESTE
      MISMO issue otra vez. Hay que moverlo en los tres sitios a la vez —el
      generador, el controlador y `frontend/src/api`—, y el frontend nuevo NO
      genera sus tipos del contrato, asi que nada rompería la compilación: el
      unico sintoma seria el listado entero en pantalla.
   3. Y sobre todo: lo que hacia peligroso el nombre ya no lo es. Desde #539 la
      errata contesta «Parametro desconocido: 'dni'» en vez de devolver el
      padron. Renombrar pasa de arreglo a mejora de estilo, y como tal se hace
      cuando se pueda mover el frontend en el mismo PR. */

const sinTildes = (texto) =>
  texto
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/ñ/g, 'n')
    .replace(/Ñ/g, 'N');

function aClave(etiqueta) {
  const partes = sinTildes(etiqueta)
    .replace(/[^A-Za-z0-9 ]+/g, ' ')
    .trim()
    .split(/\s+/)
    .filter(Boolean);
  if (partes.length === 0) return 'campo';
  const [primera, ...resto] = partes;
  const camel =
    primera.toLowerCase() + resto.map((p) => p[0].toUpperCase() + p.slice(1).toLowerCase()).join('');
  return /^[0-9]/.test(camel) ? `c${camel}` : camel;
}

/* ── Textos largos ────────────────────────────────────────────────────────
   Una descripcion de una linea va entre comillas; una de cinco, en bloque. El
   YAML usa las dos y hay que devolver la misma, asi que el corte de linea es
   parte del dato: se escribe aqui como se lee alli.

   `bloque` es `>-` —el lector une las lineas con un espacio, o sea un parrafo—;
   `literal` es `|` —el lector conserva los saltos, que es lo que hace falta
   cuando el texto tiene dos parrafos—. */

const bloque = (texto) => ({
  lineas: texto.trim().split('\n').map((linea) => linea.trim()),
  marca: '>-',
});

const literal = (texto) => ({
  lineas: texto
    .replace(/^\n/, '')
    .replace(/\n[ ]*$/, '')
    .split('\n')
    .map((linea) => linea.trim()),
  marca: '|',
});

/** Los filtros de una pantalla, con el nombre con el que viajan. */
function filtrosDe(pantalla) {
  const usadas = new Set();
  return (pantalla.filters ?? []).map((filtro) => {
    let clave = aClave(filtro.label);
    for (let n = 2; usadas.has(clave); n++) clave = `${aClave(filtro.label)}${n}`;
    usadas.add(clave);
    return { nombre: clave, etiqueta: filtro.label };
  });
}

/**
 * Paginacion y orden, para las operaciones de lectura que traen tabla.
 *
 * **Los nombres son los del backend, no los que la interfaz propuso.** Cuando
 * se escribieron aqui el backend todavia no tenia capa web; ahora la tiene
 * (`ParametrosDePaginacion` de #6) y manda ella: `ordenarPor` y no `orden`,
 * `direccion` y no `sentido`, y la pagina contada desde 0. Que la interfaz
 * proponga esta bien; que siga proponiendo cuando ya hay respuesta, no.
 */
const PAGINACION = [
  { nombre: 'pagina', ejemplo: '0', descripcion: 'Pagina que se pide, contada desde 0' },
  { nombre: 'tamano', ejemplo: '20', descripcion: 'Filas por pagina' },
  { nombre: 'ordenarPor', ejemplo: '', descripcion: 'Campo por el que se ordena, en camelCase' },
  {
    nombre: 'direccion',
    ejemplo: 'ASCENDENTE',
    descripcion: 'ASCENDENTE | DESCENDENTE',
  },
];

/**
 * Las pantallas que traen tabla pero **no** son GET y aun asi paginan.
 *
 * La paginacion se anade sola a las lecturas con tabla; `respaldo` trae tabla y
 * su verbo es POST —lo fija el contrato del prototipo, no la pantalla—, pero
 * `SesionController#respaldos` pagina igual que las lecturas: sin esto, la
 * pantalla no podria pedir la pagina siguiente de un historico que solo crece.
 */
const PAGINAN_SIN_SER_GET = new Set(['respaldo']);

/**
 * El formato de salida de un reporte, que ninguna pantalla dibuja.
 *
 * Once pantallas de Transito e Infracciones administrativas responden JSON o
 * documento segun este parametro (#53, RF-132). No sale del prototipo: la
 * pantalla dibuja el boton «Imprimir» y no dice como viaja.
 */
const FORMATO_DE_REPORTE = {
  nombre: 'formato',
  ejemplo: '',
  descripcion: 'PDF, XLS o RTF; sin el, la respuesta es JSON (RF-132)',
};

/**
 * Parametros que la pantalla del prototipo dibuja y el backend **retira a
 * proposito**.
 *
 * Es el reverso de `DEL_BACKEND`, y hace falta por lo mismo: cuando el backend
 * ya existe, manda el backend. La diferencia es que aqui no se anade una
 * divergencia sino que se cierra una, y cada entrada tiene que decir por que
 * ese parametro no puede existir.
 *
 * Se suprime del contrato y no solo «se deja sin implementar» porque un
 * parametro publicado es una promesa: el generador de tipos del frontend lo
 * expone, y alguien lo manda.
 */
const SUPRIMIDOS = {
  // `GET /portal/deuda?doc=44218937` es literalmente el endpoint de enumeracion
  // que mantuvo D-07 abierta: contesta «quien es esta persona y cuanto debe» a
  // quien teclee ocho digitos. Con la sesion del ciudadano (ADR-0020) el
  // documento deja de ser un parametro y pasa a ser un claim firmado del token,
  // asi que no hay nada que teclear y la caja desaparece de la pantalla.
  //
  // La operacion `portal` se queda —es la vista del FUNCIONARIO, la opcion del
  // catalogo de las 134, y sigue sin backend que la sirva (ADR-0016 §3)—; lo
  // que se va es su parametro. Quien pregunta por su propia situacion usa
  // `portal_mi_situacion`, que no tiene ninguno.
  portal: ['doc'],
  // `GET /seguridad/auditoria?accion=ALTA` se teclea y **no filtra**: medido
  // sobre las 1 441 filas de la municipalidad 1, deja el total en 1 441 (#544).
  //
  // Y no es un filtro sin implementar: es `operacion` con el nombre del
  // prototipo. La bitacora guarda UNA columna para esto —`auditoria.operacion`
  // (V5), con su CHECK— y la propia grilla ya la dibuja en la columna «Acción».
  // Publicar los dos nombres seria publicar dos veces el mismo dato, y hacer
  // que `accion` filtrara obligaria a traducir ademas su vocabulario: de las
  // cinco palabras del desplegable, dos coinciden letra por letra (ALTA,
  // ACCESO), dos difieren solo en la tilde que ningun identificador de este
  // proyecto lleva (MODIFICACIÓN, ANULACIÓN) y una **no existe ni puede
  // existir** —ELIMINACIÓN: la aplicacion no borra (RNF-051, regla 4)—;
  // mientras que BAJA, REVERSION y PERMISO, que la bitacora si registra, el
  // desplegable no las ofrece. Traducir a ciegas es lo que #427 se nego a hacer
  // con «ACTIVA» y VIGENTE, y aqui saldria peor: quien audita cambios de
  // permisos —lo que ADR-0008 §5 anadio al manual— no tendria como pedirlos.
  //
  // Asi que el que se va es el nombre, no el filtro: `operacion` sube a
  // DEL_BACKEND con el vocabulario del enumerado, y la pantalla manda ese.
  auditoria: ['accion'],
  // `POST /tesoreria/caja/tasas?partida=…&conceptoTupa=…` se teclea y no filtra
  // NADA, y aqui ni siquiera es un filtro sin implementar (#548).
  //
  // Los dos acotan la tabla «Conceptos a cobrar» del prototipo, que es el
  // CATALOGO DEL TUPA: `partida` es `tasa.partida_presupuestal` y `conceptoTupa`
  // es la descripcion del concepto (V3). Esa tabla no la publica ninguna
  // operacion del contrato —#430 lo midio y decidio no publicarla: nada en
  // produccion escribe `tasa`, y sus cifras son D-02b fila 29— y, sobre todo,
  // no la publica ESTA: `caja_tasas` es el POST que COBRA los conceptos que
  // llegan en el cuerpo (`conceptos[].conceptoTupa`), no la lectura que los
  // lista. Leerlos aqui no podria cambiar ni una fila de la respuesta.
  //
  // `codContribuyente` se queda, y la diferencia es exactamente la que #425
  // dejo escrita: ese identifica A QUIEN se le cobra —el controlador lo lee de
  // la consulta y del cuerpo— y estos dos acotan una lista que esta operacion
  // no devuelve. El dia que exista `GET /tesoreria/tasas` seran sus filtros, y
  // se declararan alli.
  caja_tasas: ['partida', 'conceptoTupa'],
  // `POST /fiscalizacion/vehicular` declara tres filtros del **cruce registral**
  // —SUNARP, SUNAT, MTC— y ese cruce NO EXISTE: no hay integracion con ningun
  // registro, ni tabla donde apoyarla, ni operacion que la haga (#546, AC 9).
  // `ActaVehicularController` registra el acta de una inspeccion, lee `hallazgo`
  // —de la consulta y del cuerpo, como #425 lo dejo— y no mira ninguno de los
  // tres.
  //
  // Y no se cierra dejandolos declarados: un parametro publicado es una promesa
  // —el frontend lo manda creyendo que acota, y aqui acotaria QUE VEHICULO se
  // fiscaliza—. Fingir la integracion seria peor todavia: la placa que el cruce
  // «no encontro» es indistinguible de la del cruce que nadie hizo.
  //
  // Vuelven el dia que exista la operacion del cruce, y entonces seran suyos y
  // no de esta acta.
  fisc_vehicular: ['placa', 'ejercicio', 'origenDelCruce'],
  // `GET /fiscalizacion/estado-cuenta` declaraba los filtros de una pantalla de
  // PAPELETAS —«Tipo de papeleta», «Papeleta», «Placa»— y la paginacion de una
  // grilla, y no leia ninguno de los siete (#546, AC 4). Lo que la operacion
  // contesta es el estado de cuenta de UN contribuyente: una sola respuesta, sin
  // sobre paginado, con sus lineas dentro.
  //
  // Los tres primeros no son de esta pantalla —`OmisosController.estadoDeCuenta`
  // lee `contribuyente` y `fechaDeConsulta`, y nada mas—; los cuatro de
  // paginacion los pone el generador a toda lectura con tabla, y aqui no hay
  // ninguna que paginar. `fechaDeConsulta`, que si se lee y es el parametro de la
  // regla 9, se declara en DEL_BACKEND.
  // `POST /rentas/predial/calculo-individual` declara tres filtros de la
  // DECLARACION JURADA que motiva la determinacion —«DJ N°», «Tipo de
  // declaracion», «Fecha de declaracion»— y `PredialController.calcular` no lee
  // ninguno (#576). Medido: con `djN=ZZZ` contesta exactamente lo mismo.
  //
  // Y no se cierra haciendo que los lea, porque **acotar por declaracion jurada
  // no es filtrar esta operacion: es calcular otra cosa**. La base del predial es
  // POR CONTRIBUYENTE y no por predio (NEG-05 §1): los tramos progresivos se
  // aplican al conjunto de sus predios, y calcularlo sobre los de una sola
  // declaracion produce el mismo error sistematico a la baja que NEG-05 advierte
  // —y la cifra que sale es plausible: nadie la distinguiria de la correcta—.
  //
  // La pantalla los dibuja porque el manual los dibuja: son los datos de la DJ
  // que se esta atendiendo, no un criterio de calculo. Quien quiera acotar la
  // determinacion a una declaracion no quiere un filtro, quiere otra operacion.
  predial_individual: ['djN', 'tipoDeDeclaracion', 'fechaDeDeclaracion'],
  // `POST /rentas/espectaculos` declara los cuatro filtros de una BUSQUEDA
  // —«Nº de expediente», «Organizador», «Desde», «Hasta»— y
  // `EspectaculoController.registrar` solo tiene `@RequestBody` (#576).
  //
  // No hay a que ruta sumarlos: **ninguna lectura del contrato lista los
  // espectaculos declarados**, que es lo que #432 dejo medido al bloquear esos
  // mismos cuatro en la pantalla. Un filtro de busqueda declarado sobre el POST
  // que registra no acota nada ni podria: lo que ese POST recibe es un
  // espectaculo, no una consulta.
  //
  // Vuelven el dia que exista la lectura, y entonces seran suyos y no de este
  // acto — igual que los tres de `fisc_vehicular`.
  espectaculos: ['nDeExpediente', 'organizador', 'desde', 'hasta'],
  // Y `alcabala` SIGUE sin declarar ninguno, que es lo correcto y conviene
  // dejarlo dicho (#576 AC 4): sus tres filtros del manual —«Nº de
  // liquidacion», «Nº de expediente», «Fecha de la transferencia»— no tienen a
  // que ruta sumarse, porque **ninguna lectura del contrato lista las
  // transferencias**. #432 lo midio: rentas declara los dos POST que las
  // registran y `/fiscalizacion/transferencias`, que es otra cosa. Publicar esa
  // lectura es su propio issue; hasta entonces, no declarar nada es la unica
  // respuesta honesta.
  fisc_estado_cuenta: [
    'tipoDePapeleta',
    'papeleta',
    'placa',
    'pagina',
    'tamano',
    'ordenarPor',
    'direccion',
  ],
};

/**
 * Parametros que el backend tiene y la pantalla no dibuja.
 *
 * Misma regla que `PAGINACION`: cuando el backend ya existe, manda el backend.
 * La bitacora esta particionada por ejercicio y su controlador lo pide
 * obligatorio (`SesionController#auditoria`, #13); sin el, la consulta recorre
 * todas las particiones, y con el volumen que alcanza esa tabla la diferencia
 * es entre una pantalla que responde y una que hay que cancelar.
 *
 * Un parametro aqui es una divergencia entre lo que la pantalla dibuja y lo que
 * el servicio ofrece, y cada una se anota con el controlador que la impone.
 *
 * Van delante de los filtros de la pantalla salvo que declaren `tras`, que los
 * pone detras del parametro que nombran. Tres pantallas los intercalan: el
 * «Año» y el «Tributo» de altas y bajas viven en la seccion «Filtros del
 * detalle» y no en la barra de filtros; el `formato` y la `observacion` del
 * duplicado se piden despues de haber elegido el recibo; y la fecha de corte de
 * la consulta de vehiculos, despues del estado.
 */
const DEL_BACKEND = {
  // El catalogo vial gana un filtro que el prototipo no dibuja: una via dada de
  // baja no deberia poder elegirse para un predio nuevo (RNF-051: no se borra,
  // se desactiva) y hasta #565 salia en la lista sin distinguirse.
  calles: [
    {
      nombre: 'activa',
      ejemplo: 'true',
      tras: 'sector',
      esquema: '{ type: string, enum: [true, false] }',
      descripcion: bloque(`
        Solo las vigentes («true»), solo las dadas de baja («false») o las dos (ausente).
        Cualquier otra palabra es 422: un «si» tecleado que se leyera como «no filtres»
        devolveria a la lista las vias que se dieron de baja, que es lo que este filtro existe
        para impedir.
      `),
    },
  ],
  auditoria: [
    {
      nombre: 'ejercicio',
      ejemplo: '2026',
      descripcion: 'Ejercicio de trabajo. Obligatorio: es la clave de particion de la bitacora',
    },
    // Los dos filtros que la bitacora si sabe acotar y el contrato no publicaba
    // (#544). `operacion` ocupa el sitio del «Acción» que el prototipo dibuja
    // —es el mismo dato con el nombre del backend, ver SUPRIMIDOS— y `tabla`
    // va detras, porque es la columna «Opción» de la grilla leida al reves.
    {
      nombre: 'operacion',
      ejemplo: 'ANULACION',
      tras: 'usuario',
      esquema:
        '{ type: string, enum: [ALTA, MODIFICACION, BAJA, ANULACION, REVERSION, PERMISO, ACCESO] }',
      descripcion: bloque(`
        Que clase de acto se registro. El vocabulario es el del enumerado «Operacion» y el
        del CHECK de «auditoria.operacion» (V5), letra por letra; cualquier otra palabra se
        rechaza con 422 en vez de devolver una pagina vacia. No hay ELIMINACION: la
        aplicacion no borra (RNF-051, regla 4), y lo que parece un borrado es una BAJA, una
        ANULACION o una REVERSION.
      `),
    },
    {
      nombre: 'tabla',
      ejemplo: 'recibo',
      tras: 'operacion',
      descripcion: bloque(`
        Sobre que tabla se actuo, tal como la bitacora la nombra —«recibo», «predio»,
        «sesion»—. Es lo que la grilla dibuja en la columna «Opción».
      `),
    },
  ],
  // La resolucion de determinacion sale en papel, como los otros quince
  // documentos del contrato (#593, RF-132). Era el UNICO que no: es el valor que
  // se notifica al contribuyente y el que arranca el plazo del art. 137 para
  // reclamar, y su pantalla solo podia leerlo en JSON. `ResolucionController`
  // parte la ruta con `params = "formato"`, como la ficha del contribuyente:
  // sin el parametro, el JSON que la pantalla pinta; con el, el documento.
  // No sale del prototipo —la pantalla dibuja «Descargar PDF» y no dice como
  // viaja—, asi que se declara aqui.
  resolucion_determinacion_fisc: [FORMATO_DE_REPORTE],
  // El estado de cuenta de fiscalizacion responde **a una fecha**: es la unica
  // cifra que publica y no existe «la deuda», existe la deuda actualizada a un
  // dia (regla 9, RNF-075). `OmisosController.estadoDeCuenta` lo lee desde #49 y
  // el contrato no lo declaraba, asi que el unico parametro que la operacion
  // sirve de verdad era el que ninguna pantalla podia mandar (#546).
  fisc_estado_cuenta: [
    {
      nombre: 'fechaDeConsulta',
      ejemplo: '2026-09-01',
      tras: 'contribuyente',
      descripcion:
        'A que dia se actualizan las cifras (regla 9, RNF-075). Sin el, hoy. En formato ISO' +
        ' (2026-09-01)',
    },
  ],
  // Las cuatro fichas responden **a una fecha**: sin ella, la que rige hoy; con
  // ella, la que regia entonces. Es lo que contesta «como estaba este predio
  // cuando se emitio el valor de 2027», que es la pregunta de una reclamacion.
  // Y `historico` trae todas las versiones: la pantalla que solo pinta la
  // vigente no tiene por que pagarlas (`FichaController`, #18).
  ...Object.fromEntries(
    ['ficha_urbana', 'ficha_economica', 'ficha_bienes', 'ficha_rural'].map((id) => [
      id,
      [
        {
          nombre: 'fecha',
          ejemplo: '2026-08-20',
          descripcion: 'Ficha vigente a esta fecha. Sin ella, la que rige hoy',
        },
        {
          nombre: 'historico',
          ejemplo: 'true',
          descripcion: 'Trae todas las versiones de la ficha, no solo la vigente',
        },
      ],
    ]),
  ),
  // El reporte responde a una fecha, igual que las cuatro fichas, y ademas
  // a un formato: sin el, JSON —lo que la pantalla dibuja—; con
  // `PDF`/`XLS`/`RTF`, el documento (`ReporteController`, #71).
  ficha_contribuyente_reporte: [
    {
      nombre: 'fecha',
      ejemplo: '2026-08-20',
      descripcion: 'Ficha vigente a esta fecha. Sin ella, la que rige hoy',
    },
    {
      nombre: 'formato',
      ejemplo: 'PDF',
      descripcion: 'PDF | XLS | RTF. Sin el, responde JSON: lo que la pantalla dibuja',
    },
  ],
  // El avance en vivo del turno de un cajero: lo que la pantalla de cierre llama
  // «Cuadrar», y que se mira ANTES de firmar el arqueo (#36, RF-087). La pantalla
  // de avance dibuja ejercicio, rango y tributo, y ninguno de los dos identifica
  // una ventanilla; sin ellos, «Cierre y arqueo de caja» no tendria de donde leer
  // sus totales sin bloquear el turno, que es lo que hace la cobranza
  // (`RecaudacionController`, #36).
  avance_recaudacion: [
    {
      nombre: 'caja',
      ejemplo: 'C-01',
      descripcion: bloque(`
        Codigo de la ventanilla. Junto con «cajero», la respuesta trae ademas el arqueo
        EN VIVO de su turno: lo que la pantalla de cierre llama «Cuadrar», mirado antes
        de firmar el acta (#36, RF-087). Del backend, no de la pantalla.
      `),
    },
    {
      nombre: 'cajero',
      ejemplo: 'jperez',
      descripcion: bloque(`
        Cajero del turno. Solo tiene efecto junto con «caja»; si ese cajero no abrio
        turno ese dia, 404 —un arqueo en ceros haria pensar que abrio y no cobro—.
      `),
    },
  ],
  // El duplicado se pide sobre un recibo ya elegido, asi que sus dos parametros
  // van detras de los filtros que lo eligen (#34, RF-132).
  duplicado_recibo: [
    {
      nombre: 'formato',
      ejemplo: '',
      tras: 'caja',
      esquema: '{ type: string, enum: [PDF, XLS, RTF] }',
      descripcion: bloque(`
        Sin el, la respuesta es el contenido del recibo en JSON: la vista previa, que
        no emite nada. Con el, la respuesta es el duplicado como documento (#34,
        RF-132).
      `),
    },
    {
      nombre: 'observacion',
      ejemplo: '',
      tras: 'formato',
      descripcion: bloque(`
        Por que se pide el duplicado. Obligatoria cuando se pide con «formato»: cada
        reimpresion queda registrada con quien la genero, y eso es una escritura
        (regla 10, RNF-052). Va en la consulta y no en el cuerpo porque el verbo de
        esta operacion lo fija el prototipo, y un GET no lleva cuerpo.
      `),
    },
  ],
  // «Año» y «Tributo» los dibuja la pantalla, pero en la seccion «Filtros del
  // detalle» y no en la barra de filtros, que es lo unico que el prototipo
  // publica como `filters`. Por eso van aqui, y entre los dos que los rodean.
  // El codigo del contribuyente se unifica en `codContribuyente` (#622). En la
  // misma pantalla el mismo dato viajaba con tres grafias —`contribuyente` en
  // predios y vehiculos, `codContribuyente` en deuda, pagos y valores, y
  // `codigoCont` en altas y bajas—, de modo que el dia que una dejara de admitir
  // su nombre el 422 nombraria un parametro que quien lee la pantalla no ha
  // escrito. Las tres que no lo tenian lo ganan aqui; el nombre anterior sigue
  // declarado y sigue admitido, porque es el que el frontend manda hoy.
  consulta_altas_bajas: [
    {
      nombre: 'codContribuyente',
      ejemplo: '',
      tras: 'codigoCont',
      descripcion:
        'Codigo del contribuyente, con el nombre unificado. «codigoCont» es el mismo dato',
    },
    {
      nombre: 'ano',
      ejemplo: '',
      tras: 'codContribuyente',
      descripcion: 'Filtro «Año» de la pantalla, dentro de «Filtros del detalle»',
    },
    {
      nombre: 'tributo',
      ejemplo: '',
      tras: 'ano',
      descripcion: 'Filtro «Tributo» de la pantalla, dentro de «Filtros del detalle»',
    },
    // El filtro que #684 abrio, y que ninguna pantalla dibuja todavia: hasta V77
    // la causal de una baja viajaba DENTRO de la observacion —texto libre de
    // quien atiende— y no habia forma de preguntar «ensename las bajas por
    // prescripcion», que es lo que hace quien audita como se extingue deuda del
    // municipio. Va detras de «Alta / Baja» porque acota a las bajas.
    {
      nombre: 'causal',
      ejemplo: 'PRESCRIPCION_DECLARADA',
      tras: 'altaBaja',
      esquema:
        '{ type: string, enum: [PRESCRIPCION_DECLARADA, RESOLUCION_QUE_DEJA_SIN_EFECTO,' +
        ' ERROR_MATERIAL, COMPENSACION, DEUDA_DE_COBRANZA_DUDOSA, CONDONACION_POR_ORDENANZA] }',
      descripcion: bloque(`
        Por que se dio de baja la deuda. El vocabulario es el del desplegable «Causal» de la
        pantalla de baja de deuda, letra por letra salvo la tilde y el espacio, que ningun
        identificador lleva; cualquier otra palabra se rechaza con 422 nombrando lo admitido en
        vez de devolver la relacion entera bajo un filtro que no acota. Acota a las bajas —un
        alta no tiene causal— y deja fuera las anteriores a la migracion que estampa la
        columna, que la tienen vacia y no se pueden reparar.
      `),
    },
  ],
  // La granularidad de la fila, que es lo que decide si la fila se puede dar de
  // baja (#551).
  //
  // Por omision `GET /consultas/deuda` agrega los periodos: `periodoDesde` y
  // `periodoHasta` son el minimo y el maximo del grupo, y hay UN solo desglose
  // para todos. `POST /rentas/deuda/bajas` extingue UNA cuota —su
  // `ClaveDeSaldo` lleva el periodo—, asi que desde una fila que agrega cinco
  // periodos no hay ningun cuerpo que se pueda componer: la pantalla sabe cuanto
  // se debe por el conjunto y no cuanto por cada cuota, y repartir el total en la
  // interfaz es componer dinero en la pantalla (RNF-083).
  //
  // Con `porPeriodo=true` cada fila es una cuota, con `periodoDesde ==
  // periodoHasta` y su propio desglose con su fecha. La forma del recurso no
  // cambia: lo unico que cambia es donde se corta.
  //
  // No lo dibuja la pantalla porque el prototipo nunca tuvo que elegirlo —su
  // grilla es la de cobranza—, y por eso se declara aqui y no como filtro.
  consulta_deuda: [
    {
      nombre: 'porPeriodo',
      ejemplo: 'true',
      tras: 'incluyeConvenios',
      esquema: '{ type: string, enum: [true, false] }',
      descripcion: bloque(`
        Una fila por cuota («true») o una por obligacion con sus periodos agregados
        (ausente o «false»). Cualquier otra palabra es 422: un «si» tecleado que se leyera
        como «false» devolveria filas agregadas a quien pidio cuotas, y esa respuesta es
        indistinguible de la correcta hasta que alguien intenta dar una de baja.
      `),
    },
  ],
  // El ejercicio del calculo individual del predial, con su nombre (#541).
  //
  // La pantalla dibuja «Año» —de ahi sale `ano`— y el cuerpo de la operacion lo
  // llama `ejercicio`, que es como se llama en el dominio, en la columna y en el
  // otro endpoint del mismo controlador (`/rentas/predial/corridas/ultima`). Dos
  // nombres para el mismo dato en la misma operacion obligaban al cliente a saber
  // cual toca en cada mitad. El canonico es `ejercicio` y se declara tambien en la
  // consulta; `ano` se queda como el alias que produce el rotulo del prototipo, y
  // el controlador lee los dos (`PredialController`).
  predial_individual: [
    {
      nombre: 'ejercicio',
      ejemplo: '2026',
      tras: 'ano',
      descripcion: bloque(`
        El ejercicio que se determina, con el nombre que lleva en el cuerpo y en el
        dominio. «ano» es el mismo dato con el rotulo del prototipo; si vienen los dos,
        gana el del cuerpo (FiltroDeLaConsulta) y despues este.
      `),
    },
  ],
  // La deuda de cada fila se actualiza a una fecha, y la fila la dice (regla 9).
  consulta_predios: [
    {
      nombre: 'codContribuyente',
      ejemplo: '',
      tras: 'contribuyente',
      descripcion:
        'Codigo del contribuyente, con el nombre unificado. «contribuyente» es el mismo dato',
    },
  ],
  consulta_vehiculos: [
    {
      nombre: 'codContribuyente',
      ejemplo: '',
      tras: 'contribuyente',
      descripcion:
        'Codigo del contribuyente, con el nombre unificado. «contribuyente» es el mismo dato',
    },
    {
      nombre: 'fecha',
      ejemplo: '',
      tras: 'estado',
      descripcion: 'Fecha de corte de la deuda de cada fila. Sin ella, hoy',
    },
  ],
  // La constancia no dibuja filtros: es un formulario. Pero acredita **a una
  // fecha**, y sin el contribuyente no hay a quien acreditar. Y sale en papel:
  // `ConstanciaController` responde el documento con `formato` (#72, RNF-081).
  constancia: [
    {
      nombre: 'codContribuyente',
      ejemplo: '',
      descripcion: 'Filtro «Cod. Contribuyente» de la pantalla',
    },
    { nombre: 'fecha', ejemplo: '', descripcion: 'Fecha de corte; sin ella, la de hoy' },
    FORMATO_DE_REPORTE,
  ],
  // Las once pantallas que salen en papel (#53, RF-132).
  ...Object.fromEntries(
    [
      'transito_padron',
      'transito_padron_coactiva',
      'transito_padron_constancias',
      'transito_resumen_recaudacion',
      'transito_resumen_papeletas',
      'transito_resumen_codigo',
      'transito_resumen_placa',
      'adm_padron_notificaciones',
      'adm_resumen_recaudacion',
      // La hoja informativa de una papeleta sale en los mismos tres formatos que
      // las otras trece de #53: `HojaDePapeletaController` la dibuja con los
      // renderizadores de `documentos` cuando llega `formato` (#396).
      'transito_papeleta_reporte',
    ].map((id) => [id, [FORMATO_DE_REPORTE]]),
  ),
  // Los dos records no dibujan ningun filtro en el prototipo —ni siquiera en
  // `report.meta`, que ahi llega ya resuelto—: `RecordsDeTransitoController`
  // exige a quien (licencia o documento; placa) porque sin sujeto la consulta
  // seria el padron entero con otro titulo, y ese sujeto no tiene de donde
  // salir si el contrato no lo declara (#77).
  transito_record_conductor: [
    {
      nombre: 'licencia',
      ejemplo: '',
      descripcion: bloque(`
        Licencia de conducir del infractor. Uno de los dos —esta o «documento»— es
        obligatorio: sin ninguno, 422 (RecordsDeTransitoController).
      `),
    },
    {
      nombre: 'documento',
      ejemplo: '',
      descripcion: 'Documento del infractor. Alternativo a «licencia»',
    },
    FORMATO_DE_REPORTE,
  ],
  transito_record_vehicular: [
    {
      nombre: 'placa',
      ejemplo: '',
      descripcion: 'Placa del vehiculo. Obligatoria: sin ella, 422 (RecordsDeTransitoController)',
    },
    FORMATO_DE_REPORTE,
  ],
};

/**
 * Lo que la pantalla dice de si misma, corregido por lo que el backend hace.
 *
 * El `desc` del prototipo describe **la pantalla**, y sirve mientras la
 * operacion no existe. Cuando existe, hay cosas que solo se saben desde el
 * codigo —que la generacion masiva no emite ningun valor sino que deja la
 * corrida para el perfil batch, que lo recaudado se lee del libro y no sumando
 * papeletas pagadas, que un filtro que este servicio no sirve responde 422 en
 * vez de devolver el padron entero—, y esas son las que se leen en la consola
 * de un cliente que integra contra el contrato.
 *
 * Cada entrada nombra el issue que la escribio. Una descripcion aqui es la
 * pantalla explicada por su implementacion; una pantalla sin entrada es una
 * pantalla que todavia no la tiene.
 */
const DESCRIPCIONES = {
  // Cuenta corriente · Consultas (#640, #662)
  consulta_altas_bajas: bloque(`
    Los **actos** de alta y de baja de deuda de un contribuyente (RF-043, RF-044), con el documento
    que los aprueba y el motivo con que se registraron.

    **No es todo el movimiento del libro.** Un cobro de ventanilla no sale aquí —es un pago, y lo
    lista \`GET /consultas/pagos\`— ni el cargo de la emisión, aunque los tres se escriban con los
    mismos conceptos del desglose: el abono de una baja y el de una cobranza son, columna a
    columna, el mismo asiento. Lo que los separa es de qué acto nace la fila.

    **Los movimientos anteriores a la migración que estampa ese dato no aparecen**, y no se pueden
    recuperar: el libro no admite corrección —se anula, se da de baja o se reversa— y las filas ya
    escritas nacieron sin él. Para una instalación con historia previa, esta relación empieza el día
    en que se aplicó esa migración; lo anterior se consulta como movimientos de la cuenta corriente.

    **También salen las bajas que dicta una resolución de gerencia** —la que deja una multa sin
    efecto, y la que declara fundado un descargo—: son una baja de deuda como la de la pantalla que
    la registra a mano, con las mismas causales, y el documento que las sustenta es el número de esa
    resolución.

    **«Auto / Manual» no se sirve** y se rechaza con 422 en vez de ignorarse: el origen de un acto
    —tecleado en ventanilla o dictado por un procedimiento— no es un dato que esta relación pueda
    consultar. Lo que el libro guarda es de qué acto nace la fila, no quién la produjo; quien lo
    dice es el documento que la sustenta, y eso es texto.
  `),
  // Rentas · Determinaciones (#577)
  vehicular_calculo: bloque(`
    Determina —o simula— el impuesto al patrimonio vehicular de **un ejercicio**, sobre los
    vehículos que el criterio elija: una placa, o todos los del contribuyente.

    **Un ejercicio, no tres.** La descripción de esta operación prometía «por los tres ejercicios
    en que el vehículo permanece afecto» y devolvía una determinación por vehículo del ejercicio
    pedido (#577). Los tres ejercicios son la **afectación** —estructural, y \`GET
    /rentas/vehiculos\` ya la publica como \`afectoDesde\`/\`afectoHasta\`—, no el alcance de este
    cálculo: determinar tres a la vez asentaría tres deudas por un botón que dice «Calcular». Cada
    fila de la respuesta dice de qué vehículo es, con su \`placa\`.

    **\`baseImponible\` se llamaba \`valorReferencial\` y no lo era**: es el mayor entre el valor
    de adquisición y el referencial del MEF (art. 32 LTM). Los dos operandos que la memoria del
    cálculo compara no viajan, porque la determinación guarda la base y no de qué salió.

    La marca \`simulacion\` del cuerpo es **obligatoria** y distingue calcular de asentar: no hay
    omisión segura en ninguna de las dos direcciones.
  `),
  // Rentas · Determinaciones (#577)
  predial_masivo: bloque(`
    Recalcula el padrón declarado del ejercicio y deja constancia de los contribuyentes
    **observados** que quedan fuera de la emisión (#523).

    El \`alcance\` admite las **cuatro** palabras que el manual dibuja, letra por letra (#577):

    - \`TODOS\` — todo el padrón declarado del ejercicio.
    - \`SECTOR\` — los que tienen al menos un predio en el sector; exige \`sector\`. Se determina
      igual sobre **todos** sus predios: la base es del contribuyente (NEG-05 §1) y recortarla al
      sector produciría un error a la baja. El sector elige a quién se emite, no qué se le cobra.
    - \`RANGO_DE_CODIGO\` — los que caen en un tramo de código de contribuyente; exige
      \`codigoDesde\` y \`codigoHasta\`, extremos incluidos. Se compara como texto, que es el orden
      con que la pantalla lista el padrón.
    - \`OBSERVADOS\` — los que la **última corrida del ejercicio** dejó observados. Sin corrida
      previa no recorre a nadie, y eso es lo correcto: «ninguno quedó observado» y «todavía no se
      ha corrido» son dos cosas distintas.

    Los dos interruptores que la pantalla dibuja y esta corrida no hace —arbitrios y cuponera— se
    **rechazan** con 422 en vez de ignorarse: una corrida «correcta» a quien pidió además los
    arbitrios sólo se notaría al buscar los recibos que nadie generó.
  `),
  // Seguridad (#543)
  permisos: bloque(`
    Fija los niveles de accesibilidad de un grupo (RF-121). Recibe la lista **completa** de
    accesos con sus privilegios y no un cambio incremental: la pantalla es una tabla de
    casillas que se marcan y se desmarcan, y aceptar un delta obligaría a la interfaz a
    calcular qué cambió — y a acertar. Un acceso ausente del cuerpo se queda como estaba;
    para retirar, se manda con la lista de privilegios vacía.

    **Puede contestar 409, y hay que saber contarlo**: un cambio que dejaría a la
    municipalidad sin ningún usuario capaz de administrar permisos se rechaza. No es una
    precaución teórica — el error más caro de esta pantalla es también el más fácil de
    cometer, quitarse a uno mismo (o al grupo del que uno es el único miembro) el privilegio
    que hacía falta para devolvérselo—, y de ahí no se sale por el sistema: hace falta entrar
    por la base de datos. La comprobación corre **después** de escribir el cambio y dentro de
    la misma transacción, porque lo que hay que verificar no es el estado actual sino el que
    quedaría.

    Exige la observación del usuario, obligatoria (RNF-052).
  `),
  // Rentas · Registro (#541)
  arbitrios: bloque(`
    Las cuotas de arbitrio ya determinadas de un ejercicio: una fila por servicio y mes (#31,
    RF-022). **Solo lectura.** La determinación existe —\`DeterminarArbitrios\`— y **no la publica
    ningún controlador ni ningún proceso**, así que hoy esta consulta lee una tabla que ninguna
    instalación llena; publicarla está bloqueada por D-02b, porque sus tasas son de ordenanza
    local.

    El ejercicio se pide con \`ejercicio\`, que es como se llama el dato en el resto del sistema;
    \`anio\` es su alias y hace lo mismo. Sin ninguno de los dos, el ejercicio del reloj del
    servidor.

    **«Zona» y «Uso» no se sirven, y se rechazan con 422 en vez de ignorarse.** No es falta de
    consulta: los valores que la pantalla ofrece —«Zona 1»…«Zona 4», y cinco usos en
    mayúsculas— **no existen en el sistema**. La zona es la del sector del predio y el uso el
    de su ficha catastral, los dos texto libre por municipalidad («Urbana», «Casa habitación»),
    así que ninguna de esas nueve opciones casaría con ningún dato y la respuesta sería la tabla
    vacía, que se lee como «no hay cuotas». Se acota por código predial. La pantalla los dibuja
    bloqueados con su motivo, como los de #322 y #398.
  `),
  // Fiscalizacion (#593)
  resolucion_determinacion_fisc: bloque(`
    El valor que cierra un procedimiento de fiscalización: determina por ejercicio la diferencia
    de tributo y la multa tributaria, y es lo que arranca el plazo del art. 137 para reclamar
    (#52, RF-057). Las cifras salen **nulas** mientras D-02a siga abierta, y eso no es un cero:
    lo que no se puede determinar todavía no se dibuja como «no debe nada» (#198).

    Con \`?formato=PDF|XLS|RTF\` la respuesta es el **documento**; sin él, el JSON que la pantalla
    pinta (#593, RF-132). El papel **no se emite aquí**: la resolución ya se numeró y se guardó al
    transferirla, así que esta ruta entrega los datos guardados dibujados en el formato pedido
    —no recompone nada con datos vivos, que daría otro papel con el mismo número— y no registra
    ninguna reimpresión.
  `),
  // Cuenta corriente (#72)
  constancia: bloque(`
    Vista previa del documento que se entrega al contribuyente. Se imprime con el mismo
    formato en papel membretado. Con \`?formato=PDF|XLS|RTF\` sale como documento (RF-132,
    RNF-081).
  `),
  // Transito (#53)
  transito_valores: bloque(`
    Registra el criterio de una generación masiva de valores por papeletas de tránsito, por
    selección de números o por rango de fechas (#53, RF-066, RF-073). Devuelve la corrida y
    sus candidatos; **no emite ningún valor**: la generación corre después, en el perfil
    batch. El número de cada resolución de multa lo pone \`valor_correlativo\` (#37) y **no
    entra por el cuerpo**.
  `),
  transito_record_conductor: bloque(`
    Historial de infracciones de un conductor, por licencia de conducir o por documento del
    infractor (#53, RF-068). Uno de los dos es obligatorio: sin filtro esto sería el padrón
    entero con otro título. Con \`?formato=PDF|XLS|RTF\` sale como documento (RF-132).
  `),
  transito_record_vehicular: bloque(`
    Historial de papeletas de un vehículo (#53, RF-068). La placa es obligatoria, por el mismo
    motivo que la licencia en el record de conductor. Con \`?formato=PDF|XLS|RTF\` sale como
    documento (RF-132).
  `),
  transito_reportes: bloque(`
    Emisor de los reportes del módulo de tránsito y entrada de su centro de reportes
    (ADR-0014 §5, #396). El campo \`reporte\` elige entre \`PADRON\`, \`PADRON_COACTIVA\`,
    \`PADRON_CONSTANCIAS\`, \`RECORD_CONDUCTOR\`, \`RECORD_VEHICULAR\`, \`RESUMEN_RECAUDACION\`,
    \`RESUMEN_PAPELETAS\`, \`RESUMEN_CODIGO\` y \`RESUMEN_PLACA\`; con \`formato\` la respuesta es
    el documento en PDF, hoja de cálculo o texto enriquecido (RF-132), y sin él, el JSON. No
    hay ninguna consulta nueva detrás: llama a las mismas que los GET. **Un criterio que el
    reporte elegido no usa se rechaza con 422 nombrándolo**, en vez de ignorarse: pedir el
    resumen de recaudación «de una placa» devolvería el de todas y nada lo diría.
  `),
  transito_papeleta_reporte: bloque(`
    Hoja informativa de una papeleta de tránsito: el acta con su desglose, el código del
    catálogo que la sustenta y a quién se le cobra (#396, RF-068). Los seis importes son los
    **del acta**, congelados al registrarla, y por eso \`actualizadoA\` es la fecha de la
    infracción y no la de hoy; \`emitidaEl\` es el día en que sale la hoja, con el que se
    resolvió el domicilio del obligado. **No dice lo que se debe hoy**: esa cifra es del libro
    y la publica \`transito_estado_cuenta\`. Una papeleta que no existe responde **404**, no
    una hoja vacía. Con \`?formato=PDF|XLS|RTF\` sale como documento (RF-132).
  `),
  transito_constancia_libre: bloque(`
    Emite el documento con el que la municipalidad acredita que un vehículo no registra
    papeletas de tránsito pendientes (#53, RF-068). \`verificadaAl\` es el día al que se
    acredita —no el de emisión— y viaja impreso en el papel (regla 9, RNF-075). Si a esa fecha
    hay una sola papeleta pendiente responde **409** con los números que lo impiden. La
    respuesta es el archivo, en el formato pedido.
  `),
  transito_padron: bloque(`
    Padrón de papeletas de tránsito por intervalo de fechas de infracción y estado (#53,
    RF-073). El importe de cada fila es el **del acta**, congelado al registrar la papeleta, y
    viaja con su fecha. Con \`?formato=PDF|XLS|RTF\` sale como documento (RF-132).
  `),
  transito_padron_coactiva: bloque(`
    Padrón de las papeletas que ya tienen su resolución de multa emitida (#53, RF-073). El
    ejecutor y el estado del expediente **no** son columnas de la papeleta: viven en el
    expediente coactivo y ese corte lo sirve \`GET /coactiva/expedientes\`; mandarlos aquí
    responde 422 diciéndolo, en vez de devolver el padrón sin filtrar.
  `),
  transito_padron_constancias: bloque(`
    Padrón de constancias libres de infracciones emitidas (#53, RF-068). Cada fila lleva su
    \`verificadaAl\` junto a la fecha de emisión: son cosas distintas y la que acredita es la
    primera.
  `),
  transito_resumen_recaudacion: bloque(`
    Lo recaudado por papeletas de tránsito, según el **libro de cuenta corriente**: la suma
    exacta de los abonos vivos, desglosada por ejercicio, mes y tipo de cobranza (#53,
    RF-073). No se recompone sumando papeletas pagadas —esa cifra no cuenta los intereses
    cobrados, cuenta entero un pago parcial y sigue contando un recibo anulado—. Además de
    \`lineas\`, la respuesta trae \`porMes\`: una entrada por mes con las fases desglosadas y
    **su total ya sumado en el servidor** (#398), porque recomponerlo en el cliente es lo que
    RNF-083 prohíbe. El filtro por caja no se sirve aquí: la caja es de tesorería (\`GET
    /tesoreria/recaudacion/por-area\`).
  `),
  transito_resumen_papeletas: bloque(`
    Cuántas papeletas hay y por cuánto, agrupadas por **año** —lo que toma por omisión—, mes,
    estado, código o iniciales de placa (#53, RF-073, #398). Cada línea publica \`ano\` cuando
    el agrupador lo determina —\`ANO\` y \`MES\`— y nulo cuando no, porque agrupar por estado o
    por código mezcla años dentro de un grupo. Todos los importes son los **de las actas**, no
    lo cobrado: lo cobrado está en \`transito_resumen_recaudacion\`. Cada línea trae las
    pendientes y las que están en coactiva en columnas separadas, así que no hace falta pedir
    el resumen dos veces.
  `),
  transito_resumen_codigo: bloque(`
    El mismo resumen agrupado por código de infracción, con su descripción (#53, RF-073).
  `),
  transito_resumen_placa: bloque(`
    El mismo resumen agrupado por las dos letras iniciales de la placa (#53, RF-073). El
    filtro por iniciales se resuelve como rango y no como \`LIKE\`: bajo RLS un \`LIKE 'AB%'\` no
    llega nunca al índice.
  `),
  // Infracciones administrativas (#53)
  adm_valores: bloque(`
    Lo mismo que \`transito_valores\` para la familia administrativa, con su propio permiso
    (#53, RF-066). Detrás es el mismo caso de uso con otra familia: quien puede emitir los
    valores administrativos no tiene por qué poder emitir los de tránsito.
  `),
  adm_reportes: bloque(`
    Emisor de los reportes del módulo de infracciones administrativas (#53, RF-074). El campo
    \`reporte\` elige entre \`PADRON_NOTIFICACIONES\`, \`RESUMEN_PAPELETAS\` y
    \`RESUMEN_RECAUDACION\`; con \`formato\` la respuesta es el documento en PDF, hoja de cálculo
    o texto enriquecido (RF-132), y sin él, el JSON. No hay ninguna consulta nueva detrás:
    llama a las mismas que los GET.
  `),
  adm_padron_notificaciones: bloque(`
    Relación de notificaciones administrativas emitidas en un intervalo, con la papeleta que
    las siguió cuando la hay (#53, RF-074). Las tres columnas de la papeleta —número, estado e
    importe del acta— solo tienen valor cuando existe.
  `),
  adm_resumen_recaudacion: bloque(`
    Lo recaudado por multas administrativas, según el libro (#53, RF-074). Mismo criterio que
    el de tránsito: la suma exacta de los abonos vivos.
  `),
  // Tesoreria · Convenios (#606)
  fraccionamiento: bloque(`
    Acoge la deuda marcada a pago fraccionado, o solo **simula** su cronograma: lo decide
    \`simular\` en el cuerpo. Lo que sale de aquí es siempre un **preconvenio**; no acoge deuda ni
    toca el libro hasta que su cuota inicial se cobre en caja (\`POST /tesoreria/caja/cobranza\`
    con \`tipoDePago = PRECONVENIO\`).

    **La cabecera \`Idempotency-Key\` se lee** (#606). Reenviar el mismo intento —el doble clic, el
    reintento tras un tiempo de espera agotado— devuelve **201 con el convenio de la primera vez**,
    con su mismo número, en vez de abrir un segundo preconvenio sobre la misma deuda. Con una clave
    ya usada para **otro contribuyente** responde 409: devolver el convenio de la primera vez
    imprimiría en ventanilla el acuerdo de otra persona. Simular no consume la clave, porque no
    escribe nada. Sin la cabecera, dos envíos siguen siendo dos convenios.

    El interés, el máximo de cuotas y la política con que se redondea cada cuota salen del conjunto
    sellado del ejercicio: ninguno viaja en el cuerpo, y si falta alguno la respuesta es 422
    nombrando la llave o el ejercicio (#547).
  `),
  // Tesoreria · Convenios (#606)
  anulacion_convenio: bloque(`
    Anula, quiebra o reformula un convenio: las tres por la misma ruta, porque en el libro son el
    mismo acto —lo pendiente vuelve a la fase de la que salió, con asientos y no con un \`UPDATE\`
    de fase—. El motivo es obligatorio y queda en el acta. Anular exige además que el recibo de la
    cuota inicial ya esté anulado; quebrar no, porque ese dinero sí entró.

    **La cabecera \`Idempotency-Key\` se lee** (#606). Sin ella, reenviar el mismo intento chocaba
    con \`convenio_movimiento_cierre_uq\` y respondía **409**, que se lee como un fallo nuevo y no
    como «ya estaba hecho»; con ella devuelve **201 con el convenio ya cerrado** y su acta. En una
    reformulación la clave la reclama el acta de cierre, así que el reenvío no abre un segundo
    preconvenio. Con una clave que cerró **otro** convenio responde 409.
  `),
};

/**
 * Lo que un filtro de la pantalla hace de verdad, cuando no es lo que promete.
 *
 * El texto por omision de un filtro es «Filtro «X» de la pantalla», y describe
 * el control, no el comportamiento. Cuando el servicio lo sirve a medias —o no
 * lo sirve— hay que decirlo aqui: un filtro que el backend ignora en silencio es
 * la clase de cosa por la que alguien confia en una lista que no filtro nada.
 */
const DESCRIPCIONES_DE_FILTRO = {
  // Uno de los dos hace falta, y hasta #541 ninguno: sin contribuyente esto
  // respondia 200 con cero filas sobre 14 422 predios, que se lee como «esta
  // persona no tiene predios». Ahora es 422, como su hermana /rentas/vehiculos.
  predios_rentas: {
    contribuyente:
      'Codigo del contribuyente. Uno de los dos —este o «codContribuyente»— es OBLIGATORIO:' +
      ' sin ninguno, 422. Y un codigo que no esta en el padron es 404, no una pagina vacia' +
      ' (#541).',
    codContribuyente:
      'Filtro «Cod. Contribuyente» de la pantalla. Uno de los dos —este o «contribuyente»— es' +
      ' OBLIGATORIO: sin ninguno, 422.',
  },
  // El ejercicio de los arbitrios tiene dos nombres, y el canonico es el de la
  // pantalla (#541): `anio` es el que arrastra el `endpoint` del prototipo. Y los
  // dos desplegables se rechazan en vez de ignorarse, que es el patron de #322.
  arbitrios: {
    anio:
      'Alias de «ejercicio», el que trae el endpoint del prototipo. Si vienen los dos, manda' +
      ' «ejercicio» (ArbitriosController).',
    ejercicio:
      'Filtro «Ejercicio» de la pantalla, y el nombre canonico del dato. Ausente, el ejercicio' +
      ' del reloj del servidor.',
    zona:
      'Filtro «Zona» de la pantalla. NO SE SIRVE: se rechaza con 422 con cualquier valor. La' +
      ' zona de un predio la pone su sector (sector.zona, V1) y es texto libre por' +
      ' municipalidad —la carga real escribe «Urbana»/«Rustica»—, asi que ninguna de las cuatro' +
      ' opciones que el prototipo ofrece casa con ningun dato: filtrar por ellas devolveria la' +
      ' tabla vacia, que se lee como «no hay cuotas». La pantalla lo dibuja bloqueado con su' +
      ' motivo (#322, #398, #541).',
    uso:
      'Filtro «Uso» de la pantalla. NO SE SIRVE, y por lo mismo que «zona»: el uso vive en' +
      ' ficha_catastral.uso, tambien texto libre —«Casa habitacion», «Tienda de artesania»—, y' +
      ' ninguno de los cinco usos en mayusculas del desplegable casa con el (#541).',
  },
  // Los cuatro filtros que el prototipo dibuja para el catalogo vial. Tres se
  // sirven desde #565 y uno se rechaza: `via` (V1) no tiene columna de sector.
  calles: {
    codigoDeVia: 'Filtro «Código de vía» de la pantalla. Por PREFIJO del codigo (#565).',
    nombreDeCalle:
      'Filtro «Nombre de calle» de la pantalla. Por PREFIJO del nombre, sin distinguir' +
      ' mayusculas ni tildes: el catalogo real guarda «Cayetano Heredia» y en ventanilla se' +
      ' teclea «cayetano» (#565).',
    tipoDeVia:
      'Filtro «Tipo de vía» de la pantalla. Por igualdad contra el enumerado TipoVia; un tipo' +
      ' que no existe es 422 nombrandolo, no una pagina vacia (#565).',
    sector:
      'Filtro «Sector» de la pantalla. NO SE SIRVE: se rechaza con 422 con cualquier valor. La' +
      ' tabla `via` (V1) no guarda el sector y `ViaResource` no lo publica —Track 2 de #290—,' +
      ' asi que no hay contra que comparar; ignorarlo devolveria el catalogo entero bajo un' +
      ' filtro tecleado (#565).',
  },
  consulta_fichas: {
    conciliadaConRentas:
      'Filtro «Conciliada con rentas» de la pantalla. Esta ruta no lo resuelve —el estado de' +
      ' conciliación se deriva de `declaracion_jurada`, que es de rentas, y catastro no puede' +
      ' depender de rentas (ADR-0015 §2)—: la petición que lo trae se redirige con 307 a' +
      ' `/catastro/fichas/conciliacion`, que es la misma grilla servida por rentas.',
  },
  consulta_altas_bajas: {
    autoManual:
      'Filtro «Auto / Manual» de la pantalla. El backend todavia no distingue un movimiento' +
      ' automatico de uno manual y lo ignora',
  },
  consulta_vehiculos: {
    estado:
      'Filtro «Estado» de la pantalla. Solo «BAJA» filtra contra el padron; el resto —AFECTO,' +
      ' INAFECTO, EXONERADO— es afectacion calculada y no se traduce todavia',
  },
  costas_procesales_listado: {
    estado: 'Filtro «Estado» de la pantalla. Se derivan del libro: ACTIVA o CANCELADA',
  },
};

/**
 * El vocabulario que un desplegable de la pantalla puede ofrecer, letra por letra.
 *
 * Un filtro de la pantalla sale del contrato como `{ type: string }`, o sea sin
 * decir que palabras admite. Mientras el backend acepte texto libre eso es
 * exacto; en cuanto detras hay un `enum` de Java —y el controlador rechaza con
 * 422 lo que no este en el—, el contrato tiene que decirlo, porque si no cada
 * pantalla inventa su lista y **parecerse no es serlo**: es el cruce que dejo
 * `infracciones_adm` sin conectar hasta #397, el que #427 se nego a traducir
 * («ACTIVA» no es VIGENTE) y el que #546 midio en fiscalizacion —cinco
 * desplegables y hasta cero coincidencias de seis—.
 *
 * Cada entrada nombra el enumerado del que sale, y una prueba del backend
 * (`ParametrosDeLaConsultaTest`) compara este texto contra los `values()` de esa
 * clase: anadirle un valor al enumerado sin publicarlo aqui, o publicar aqui uno
 * que el enumerado no tiene, pone el build en rojo.
 *
 * **No hay traduccion en ninguna direccion.** Lo que se publica es lo que el
 * enumerado declara; si a la pantalla le falta una palabra, la decision es
 * anadirla al enumerado —con su norma o su pantalla del manual— o quitarla del
 * desplegable, nunca mapearla a la que se le parece.
 */
const VOCABULARIOS = {
  // `CondicionFiscalizada` (5). Se DERIVA comparando lo hallado con lo
  // declarado, asi que la lista no puede salir de una columna: sale del
  // enumerado. Omitir el parametro son todas.
  fisc_omisos: {
    condicion: {
      valores: ['CONFORME', 'OMISO', 'SUBVALUADOR', 'USO_DISTINTO', 'NO_UBICADO'],
      enumerado: 'CondicionFiscalizada',
      descripcion:
        'Filtro «Condición» de la pantalla. El vocabulario es el del enumerado' +
        ' «CondicionFiscalizada», letra por letra; cualquier otra palabra se rechaza con 422 en' +
        ' vez de devolver una pagina vacia. Sin el parametro, todas.',
    },
  },
  // La misma `CondicionFiscalizada` bajo el rotulo «Hallazgo» de la pantalla, y
  // `EstadoDeLiquidacion` (5), que se deriva del historial de movimientos. El
  // artboard dibujaba «Determinado», «Notificado», «Reclamado» y «Conforme»: de
  // los cuatro ninguno es un valor del enumerado, y «Reclamado» no existe.
  fisc_resultados: {
    hallazgo: {
      valores: ['CONFORME', 'OMISO', 'SUBVALUADOR', 'USO_DISTINTO', 'NO_UBICADO'],
      enumerado: 'CondicionFiscalizada',
      descripcion:
        'Filtro «Hallazgo» de la pantalla: la condicion del contraste. El vocabulario es el del' +
        ' enumerado «CondicionFiscalizada», letra por letra. Sin el parametro, todas.',
    },
    estado: {
      valores: ['ABIERTA', 'EN_PROCESO', 'LIQUIDADA', 'NOTIFICADA', 'ANULADA'],
      enumerado: 'EstadoDeLiquidacion',
      descripcion:
        'Filtro «Estado» de la pantalla. El vocabulario es el del enumerado' +
        ' «EstadoDeLiquidacion», que se DERIVA del historial de movimientos y no es una columna.' +
        ' No existe «Reclamado». Se admite tambien la etiqueta con espacio, «EN PROCESO».',
    },
  },
  // `Hallazgo` (5 desde #599) es lo que el fiscalizador ANOTA en campo, y sigue
  // sin ser lo mismo que `CondicionFiscalizada` (5), que es lo que el sistema
  // DERIVA comparando los dos lados: uno puede equivocarse y el otro no. Los dos
  // vocabularios coinciden letra por letra desde que el acta tuvo donde
  // consignar el uso observado —`acta_fiscalizacion.uso_hallado`, V76—, que es
  // lo que le faltaba a `USO_DISTINTO` para poder anotarse.
  //
  // Y aqui publicar el vocabulario importa mas que en las lecturas: `hallazgo`
  // es OPCIONAL, asi que una palabra que el enumerado no reconoce no da una
  // lista vacia — daba 422, y una que se omite deja el acta entrando con 201 y
  // SIN hallazgo, que `LiquidarFiscalizacion` ya no liquida (lanza
  // `ActaSinHallazgo`) pero que nadie ve al registrarla.
  // Los SIETE privilegios del manual (cap. 4, RF-121), que es un enumerado del
  // codigo y no una columna. Publicarlo importa aqui mas que en un listado
  // corriente: `privilegio` es OBLIGATORIO y una palabra que no sea una de las
  // siete se rechaza con 422 enumerandolas, en vez de devolver la pagina vacia
  // — que se leeria como «nadie tiene Especial», que es exactamente la lectura
  // plausible y equivocada por la que #427 se nego a traducir «ACTIVA» a
  // «VIGENTE».
  titulares_del_privilegio: {
    privilegio: {
      valores: [
        'EJECUCION',
        'LECTURA',
        'REGISTRO',
        'MODIFICACION',
        'ELIMINACION',
        'IMPRESION',
        'ESPECIAL',
      ],
      enumerado: 'Privilegio',
      descripcion:
        'Cual de los siete privilegios se pregunta. El vocabulario es el del enumerado' +
        ' «Privilegio», letra por letra —se admite en cualquier caja—; cualquier otra palabra se' +
        ' rechaza con 422 enumerando los siete, en vez de devolver una pagina vacia. Es' +
        ' OBLIGATORIO: omitirlo no significa «todos», significa que no se pregunto nada.',
    },
  },
  fisc_vehicular: {
    hallazgo: {
      valores: ['CONFORME', 'OMISO', 'SUBVALUADOR', 'USO_DISTINTO', 'NO_UBICADO'],
      enumerado: 'Hallazgo',
      descripcion:
        'Lo que el fiscalizador encontro en campo. El vocabulario es el del enumerado' +
        ' «Hallazgo», letra por letra. En un acta VEHICULAR «USO_DISTINTO» se rechaza: un' +
        ' vehiculo no declara uso, asi que no hay uso declarado del que difiera, y el acta' +
        ' vehicular no consigna uso hallado. Tambien se admite en el cuerpo, y ahi gana (#425).',
    },
  },
};

/**
 * Operaciones que el backend publica ademas de la que declara la pantalla.
 *
 * Misma razon que `DEL_BACKEND`: cuando el backend ya existe, manda el
 * backend. Una pantalla del prototipo declara **un** `endpoint`, pero
 * `permisos` guarda una matriz que antes hay que poder cargar, y ese `GET` no
 * tiene pantalla propia de la que salir —no puede leerse de
 * `PANTALLAS[id].endpoint`, que ya esta ocupado por el `PUT` que guarda—.
 *
 * Corta a proposito: cada entrada es una pantalla que escribe y no puede leer
 * su propio estado sin esto. El `operationId` es distinto del `id` de la
 * pantalla porque los dos verbos comparten ruta y opcion de menu, y el
 * generador de tipos del frontend exige que cada operationId sea unico.
 *
 * `ruta` es opcional: sin ella, la operacion cuelga de la misma ruta que la
 * pantalla (el caso de `permisos`, dos verbos en una ruta); con ella, de otra
 * —`calles` lee en `/catastro/vias` y edita en `/catastro/vias/{codigo}`—.
 *
 * Lo demas es opcional y solo aparece donde el contrato lo pide:
 *
 * - `filtrosDeLaPantalla`, `parametros`, `paginacion`: una adicional no hereda
 *   los filtros de la pantalla —no es la misma consulta—, pero las grillas que
 *   solo existen porque la pantalla declaro su POST si son exactamente esa
 *   consulta, y entonces lo dicen.
 * - `descripcionesDeRuta`: que identifica el `{tramo}` del camino, cuando no se
 *   deduce de su nombre.
 * - `antes`: la adicional se escribe **antes** que la de la pantalla en la ruta
 *   que comparten. Es el caso de las dos grillas que se leen al abrir una
 *   pantalla cuyo verbo declarado emite: primero se mira, despues se emite.
 * - `tras`: la adicional se escribe despues de la operacion de **otra** pantalla
 *   y no de la suya. Dos grupos del contrato quedaron asi, y el orden de las
 *   claves de un `paths` no significa nada en OpenAPI; lo que si significa es
 *   que este archivo devuelva el contrato tal como esta comprometido (#312).
 */
const OPERACIONES_ADICIONALES = {
  // El trabajo parado por modulo de la pantalla de aterrizaje (#549, RF-130).
  // Sale de `inicio` —es la segunda lectura de esa misma pantalla— y no de
  // ninguna de los cuatro modulos que cuenta: la pregunta es transversal, y
  // partirla en cuatro peticiones dejaria a la interfaz inventando el concepto
  // «parado» y componiendo permisos en el cliente.
  inicio: [
    {
      operationId: 'inicio_trabajo_parado',
      metodo: 'get',
      ruta: '/api/v1/indicadores/trabajo-parado',
      titulo: 'Trabajo parado, por módulo',
      descripcion: literal(`
        Lo que está esperando un acto de la administración y no cobra mientras espera,
        con su recuento por módulo y el importe **cuando se puede cifrar**.

        **La lista trae sólo los frentes que el perfil puede ver.** Cada uno lleva
        detrás el permiso de lectura de la pantalla del módulo donde se desatasca
        —\`transito_padron\`, \`consulta_valores\`, \`coactiva_expedientes\` y
        \`consulta_fichas\`—, y el que no se autoriza **no aparece**: ni vacío, ni con un
        guion, ni con una nota. Una fila vacía ya dice que ahí hay algo que mirar
        (ADR-0016 §2).

        **\`importe\` nulo no es cero.** Sólo el frente de Tránsito se cifra —la misma
        consulta que cuenta las papeletas suma su \`importe_a_pagar\`—; los otros tres
        salen con \`importe: null\` porque la consulta que sostiene su pantalla no suma
        ninguna cifra, y sumarla aquí sería una segunda definición. Un frente cifrado con
        cero filas sí trae \`"0.00"\`, y ésa es la diferencia que hay que poder dibujar.

        Cada recuento sale de **la misma** consulta que la grilla de su módulo, no de una
        segunda: si «sin notificar» significara una cosa aquí y otra allí, las dos cifras
        se contradirían y la de la pantalla de aterrizaje es la que se lee primero.

        **Faltan dos de los seis frentes del manual, y no por descuido.** El de Licencias
        —solicitudes con el plazo agotado— necesita el plazo del silencio positivo, que es
        un valor normativo sin publicar (D-02c, regla 5); el de Fiscalización —actas con
        diferencia sin liquidar— no tiene ninguna consulta de módulo que reutilizar,
        porque ninguna pantalla lista actas.
      `),
      parametros: [
        {
          nombre: 'ejercicio',
          ejemplo: '2026',
          descripcion:
            'Ejercicio contra el que se concilia el padrón de predios; el del reloj si no viene',
        },
      ],
    },
  ],
  // La consulta del ciudadano sobre SU PROPIA situacion (#57, ADR-0020, RF-131).
  // No sale de la pantalla `portal` —esa es la vista del funcionario y sigue sin
  // backend (ADR-0016 §3)— sino de la sesion propia del contribuyente, que no
  // tiene pantalla en el catalogo de las 134 porque no es del back-office: la
  // dibuja `apps/portal`.
  portal: [
    {
      operationId: 'portal_mi_situacion',
      metodo: 'get',
      ruta: '/api/v1/portal/situacion',
      titulo: 'Mi situación en todas las municipalidades',
      descripcion: literal(`
        Lo que el ciudadano autenticado debe y tiene **en todas las municipalidades del
        sistema donde figure**, a **una sola** fecha de corte.

        **Sin ningún parámetro, y eso es la decisión.** El sujeto sale del claim
        \`numero_documento\` del token del realm del ciudadano —emisor distinto del de
        funcionarios—, validado criptográficamente por la cadena que sirve
        \`/rentas/api/v1/portal/**\`. Sustituye a \`GET /portal/deuda?doc=…\`, que contestaba
        «quién es esta persona y cuánto debe» a quien tecleara ocho dígitos.

        El servidor **recorre** el registro de municipalidades activas —una transacción y
        un \`SET LOCAL\` por rama, RLS en cada una—, compone y suma (RNF-083). No es una
        consulta multi-municipalidad: son *N* consultas de una municipalidad cuya unión se
        filtra a un documento firmado.

        \`totalConsolidado\` es \`null\` cuando alguna municipalidad no se pudo leer, y
        entonces \`notaDelTotal\` dice cuáles faltan: un total al que le falta una
        municipalidad es un importe plausible y equivocado.

        Un token de **funcionario** no autentica aquí (401); uno de ciudadano no autentica
        en ninguna otra ruta de la API.
      `),
    },
  ],
  permisos: [
    {
      operationId: 'permisos_de_grupo',
      metodo: 'get',
      titulo: 'Permisos ya otorgados de un grupo',
      descripcion:
        'Los permisos que el grupo ya tiene configurados, para cargar la matriz antes' +
        ' de guardarla (PUT de la misma ruta). No trae las 134 opciones del catalogo:' +
        ' solo las que el grupo ya tiene.',
    },
    // Y la matriz del usuario en curso, que no es una opcion del catalogo y por
    // eso no puede salir de ninguna pantalla (ADR-0013, #12). La interfaz
    // aprende sus permisos de aqui y no del token: es lo unico que sabe lo que
    // el backend concede de verdad.
    {
      operationId: 'permisos_de_la_sesion',
      metodo: 'get',
      ruta: '/api/v1/seguridad/sesion/permisos',
      titulo: 'Permisos efectivos de la sesión',
      descripcion: literal(`
        La matriz de permisos del usuario en curso: por cada opción del catálogo
        sobre la que tiene algún privilegio, la lista de privilegios. La interfaz
        la usa para dibujar el menú y la paleta de comandos.

        Autenticada, pero **no es una opción del catálogo**: leer los permisos
        propios no revela nada que no se pueda enumerar probando cada endpoint
        (REQ-03 §5). Un usuario sin ningún permiso recibe \`{}\`, no un 403.
      `),
    },
    // Y QUIEN es la sesion (#559). Vive aqui por lo mismo que las otras dos
    // —no es una opcion del catalogo, sino la sesion hablando de si misma— y no
    // por afinidad con los permisos.
    {
      operationId: 'identidad_de_la_sesion',
      metodo: 'get',
      ruta: '/api/v1/seguridad/sesion',
      titulo: 'Quién es la sesión',
      descripcion: literal(`
        La persona autenticada, ya resuelta a la fila de \`usuario\` de **esta**
        municipalidad: su \`usuarioId\`, su \`cuenta\`, su \`nombre\` y el ejercicio de
        trabajo que tenga registrado.

        **El \`usuarioId\` es lo que ninguna otra lectura publicaba.**
        \`PUT /seguridad/usuarios/{id}/clave\` sólo admite la clave propia —el servidor
        compara la cuenta del token con la del usuario que ese \`id\` nombra—, y hasta
        aquí la interfaz no sabía cuál era el suyo: las dos únicas operaciones que
        publican un \`usuario.id\` son el listado de usuarios y la matriz de otro, las
        dos detrás de un permiso de administración mucho mayor que «cambiar mi propia
        contraseña».

        **Sin ningún parámetro, y eso es la decisión.** El sujeto sale de la cuenta del
        token y se resuelve dentro del contexto de tenant. Con un identificador esto
        sería el padrón de usuarios sin su permiso, y devolvería el \`id\` de otro —que
        es justo lo que la guarda del cambio de clave existe para rechazar—. Un
        parámetro de más se rechaza con 422 nombrándolo.

        Autenticada, pero **no es una opción del catálogo**: leer quién es uno mismo no
        revela nada que no revele el token que ya se presentó. Cualquier sesión válida
        la lee, tenga los permisos que tenga — el mismo trato que
        \`permisos_de_la_sesion\` y \`municipalidad_de_la_sesion\` (ADR-0013).

        \`ejercicioDeTrabajo\` es **nulo** mientras nadie lo haya fijado con
        \`PUT /seguridad/sesion/ejercicio\`, y eso no es una falta de dato: es la
        respuesta. El año del reloj del servidor ahí afirmaría que alguien lo eligió, y
        lo que hay que poder separar es exactamente eso — el filtro de vista, que es
        local y no necesita permiso, del acto registrado con su observación y su
        privilegio \`ESPECIAL\` sobre \`cambiar_anio\`.
      `),
    },
    // Y a QUIEN pertenece la sesion (#555). Vive aqui por lo mismo que
    // `permisos_de_la_sesion` —no es una opcion del catalogo, sino la sesion
    // hablando de si misma— y no por afinidad con los permisos.
    {
      operationId: 'municipalidad_de_la_sesion',
      metodo: 'get',
      ruta: '/api/v1/seguridad/sesion/municipalidad',
      titulo: 'Municipalidad de la sesión',
      descripcion: literal(`
        A quién pertenecen las cifras de la pantalla: la municipalidad a la que
        pertenece la sesión, con su nombre tal como sale impreso.

        **Sin ningún parámetro, y eso es la decisión.** El identificador sale del claim
        \`municipalidad_id\` del token, se fija una vez con \`SET LOCAL\` y lo compara el
        \`WHERE\` de la consulta (ARQ-03 §3.1). Admitir un identificador convertiría esta
        lectura en un **directorio de municipalidades**: con el token de A no hay forma de
        obtener el nombre de B, y un parámetro de más se rechaza con 422 nombrándolo.

        Autenticada, pero **no es una opción del catálogo**: el rótulo de la entidad no es
        de un módulo, es del sistema entero, y sin él las doce pantallas quedan sin decir
        de quién son sus cifras. Cualquier sesión válida la lee, tenga los permisos que
        tenga — el mismo trato que \`permisos_de_la_sesion\` (ADR-0013).

        \`nombre\` es el nombre **completo**, con su tipo delante —«Municipalidad Distrital
        de Catacaos»—, porque de ahí sale el membrete de las hojas que se imprimen; \`tipo\`
        va aparte para quien necesite distinguir una distrital de una provincial y **no se
        antepone**. \`ubigeo\` son los seis dígitos del distrito.
      `),
    },
    // Y la matriz de OTRO usuario, que es la que se administra (#543). No sale
    // de `permisos_de_la_sesion` —aquella no tiene sujeto, sale del token— ni de
    // `permisos_de_grupo` —aquella devuelve lo configurado de un grupo, no lo
    // efectivo de una persona—.
    {
      operationId: 'permisos_efectivos_de_usuario',
      metodo: 'get',
      ruta: '/api/v1/seguridad/usuarios/{id}/permisos',
      titulo: 'Permisos efectivos de un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      descripcion: literal(`
        Lo que un usuario **puede hacer**, opción por opción, con la precedencia ya
        resuelta por el servidor (#543, RF-121).

        **Cada fila dice de dónde viene**: \`origen: "EXCEPCION"\` cuando manda su
        excepción de usuario, y \`origen: "GRUPO"\` cuando la hereda —con \`grupoId\`
        cuando hay un solo grupo vigente que la otorga—. No es un adorno: una fila de
        excepción **sustituye** al grupo entero para ese acceso, otorgue o niegue, y
        publicar las dos listas por separado obligaría a quien pregunta a
        reimplementar esa regla. Es exactamente la que no se puede equivocar: la
        interfaz la tenía invertida y calculaba la unión, que convierte una excepción
        que **restringe** en una que amplía.

        **Una fila con \`privilegios: []\` no sobra**: sólo la produce una excepción que
        niega, y es lo único que distingue «se le negó expresamente» de «nunca lo
        tuvo». Las opciones sobre las que no hay nada configurado no aparecen.

        Misma regla que el guardia en todo lo demás: vigencia y habilitación se
        comprueban en el usuario, en el grupo y en la pertenencia (RF-123), así que un
        usuario deshabilitado o fuera de vigencia recibe la lista **vacía**. Un \`id\`
        que no existe en esta municipalidad es **404**, no una lista vacía: no tener
        permisos y no existir son dos respuestas distintas.
      `),
    },
    // Y la ESCRITURA de esa excepcion, que es lo que #543 dejo sin poder tocar:
    // el dominio existia y no la llamaba nadie (#585). Misma ruta que la lectura
    // de arriba y otro verbo, como `permisos_de_grupo` sobre la suya.
    {
      operationId: 'fijar_permisos_de_usuario',
      metodo: 'put',
      ruta: '/api/v1/seguridad/usuarios/{id}/permisos',
      titulo: 'Excepción de permisos de un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      descripcion: literal(`
        Fija la **excepción propia** de una cuenta sobre los accesos que viajen en el
        cuerpo (#585, RF-121). Es la única forma de expresar «este cajero está en el
        grupo de caja y a él no se le deja anular recibos» sin sacarlo del grupo ni
        fabricarle un grupo de una persona; hasta aquí sólo se podía por SQL directo,
        que no deja fila de auditoría con quien lo decidió ni pasa por la guarda del
        último administrador.

        **Mismo cuerpo y mismo *upsert* por acceso que \`PUT /seguridad/grupos/{id}/permisos\`**:
        recibe la lista de accesos con sus privilegios, y un acceso **ausente del cuerpo
        se queda como estaba** — una lista parcial no puede traducirse en retirar en
        silencio todo lo demás.

        **Lo que cambia es qué significa \`privilegios: []\`.** En el grupo es «este grupo
        no otorga nada aquí»; en la cuenta es una **negación** que sustituye a lo que su
        grupo le da, y la fila **se escribe en cero, nunca se borra**: sin ella el acceso
        volvería a heredar del grupo, y «se le negó expresamente» y «nunca lo tuvo»
        volverían a ser el mismo JSON — justo lo que el \`GET\` de esta ruta existe para
        distinguir. La clave que **falta** en el cuerpo no es lo mismo que la lista
        vacía: se rechaza con 422, porque retirar siete privilegios por un campo
        olvidado no puede ser el resultado de una omisión.

        Exige la observación del usuario, obligatoria (regla 10, RNF-052), y queda en la
        auditoría como \`PERMISO\` con quien la firmó (ADR-0008 §5). Sin ella, **422**.

        **Puede contestar 409**, con la misma guarda que la matriz del grupo y contando
        con la precedencia del guardia: un cambio que dejara a la municipalidad **sin
        ningún usuario capaz de administrar permisos** se rechaza, y de ahí no se sale
        por el sistema. La comprobación corre **después** de escribir y dentro de la
        misma transacción, así que lo que deshace el cambio es el rollback: no se
        escribe nada.

        Un \`id\` que no existe en esta municipalidad es **404**.
      `),
    },
    // Lo CONFIGURADO de esa misma cuenta, que es otra pregunta y por eso otra
    // ruta: la de arriba no cambia (#583).
    {
      operationId: 'permisos_configurados_de_usuario',
      metodo: 'get',
      ruta: '/api/v1/seguridad/usuarios/{id}/permisos/configurados',
      titulo: 'Permisos configurados de un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      descripcion: literal(`
        Lo que una cuenta tiene **configurado**, surta efecto hoy o no (#583, RF-121).

        **Es otra pregunta, no otra respuesta a la misma.**
        \`GET /seguridad/usuarios/{id}/permisos\` **no cambia**: aplica la misma regla que
        el guardia y a una cuenta deshabilitada o fuera de vigencia le contesta la lista
        vacía, porque enseñar en la matriz privilegios que después responden 403 sería
        peor que no enseñar nada. Su consecuencia es que **«se deshabilitó y conserva
        permisos» y «nunca tuvo ninguno» devolvían el mismo JSON**, y esa distinción es
        justo la que audita quien revisa las cuentas: deshabilitar no retira nada, y
        rehabilitar lo devuelve entero.

        Aquí lo único que se levanta es la guarda del **usuario**. Las del grupo y la
        pertenencia se quedan: lo que se pregunta es qué volvería a poder el día que
        alguien reactive **la cuenta**, y reactivar una cuenta no reactiva un grupo
        inhabilitado ni devuelve a nadie a un grupo del que salió.

        \`surtenEfectoHoy\` **no es un adorno**: las filas son, campo a campo, las mismas
        que las de la matriz efectiva —con su \`origen\` y su \`grupoId\`, misma
        precedencia resuelta por el servidor—, así que sin esa marca las dos lecturas son
        indistinguibles y quien se equivoque de ruta enseñaría como vigente lo que hoy
        responde 403. Por qué no surten efecto lo dicen \`habilitado\` y las dos fechas de
        vigencia que ya publica cada fila de \`GET /seguridad/usuarios\`.

        Un \`id\` que no existe en esta municipalidad es **404**, no una respuesta vacía.
      `),
    },
  ],
  // Y la inversa de la matriz de un usuario: QUE CUENTAS tienen un privilegio
  // sobre un acceso. Contestarla cuenta por cuenta costaba una peticion por
  // usuario del padron, y no se puede componer recorriendo los grupos porque la
  // excepcion propia SUSTITUYE a lo que el grupo da (#583).
  accesos: [
    {
      operationId: 'titulares_del_privilegio',
      metodo: 'get',
      ruta: '/api/v1/seguridad/accesos/{codigo}/usuarios',
      titulo: 'Cuentas que tienen un privilegio sobre un acceso',
      descripcionesDeRuta: {
        codigo:
          'La opción del catálogo, por el `codigo` que publica cada fila de' +
          ' `GET /seguridad/accesos` (NEG-03)',
      },
      parametros: [{ nombre: 'privilegio' }],
      paginacion: true,
      descripcion: literal(`
        Qué cuentas pueden hoy ejercer un privilegio sobre una opción del catálogo
        (#583, RF-121): la pregunta inversa de \`/seguridad/usuarios/{id}/permisos\`.

        **Hasta ahora costaba una petición por cuenta.** Medido contra el backend local:
        la matriz de un usuario devuelve 134 filas y ~21 KB, así que recorrer las 200 que
        la pantalla pide de una vez eran 200 peticiones y ~4,2 MB de JSON para pintar una
        insignia.

        **Y no se puede componer acotando por grupo.** La excepción propia de una cuenta
        **sustituye** a lo que su grupo le da: alguien cuyo grupo no tiene \`ESPECIAL\`
        puede tenerlo por excepción, y al revés. Esa mitad no la encuentra ningún
        recorrido por grupos, y ninguna lectura listaba las excepciones.

        Cada fila dice **de dónde le viene**: \`origen: "EXCEPCION"\` cuando manda su
        excepción propia, y \`origen: "GRUPO"\` con \`grupoId\` cuando lo hereda de un
        solo grupo vigente que lo otorga. Nulo con \`origen: "GRUPO"\` significa que lo
        conceden varios y no hay **uno** que nombrar.

        **Sólo las cuentas que hoy pueden operar**, con la misma regla que el guardia:
        habilitación y vigencia del usuario, del grupo y de la pertenencia (RF-123). Por
        eso ninguna fila publica \`habilitado\` —valdría \`true\` en todas—, y lo que una
        cuenta deshabilitada **conserva** lo contesta
        \`/seguridad/usuarios/{id}/permisos/configurados\`.

        \`privilegio\` es **obligatorio**: omitirlo no significa «todos», significa que no
        se preguntó nada. Un \`codigo\` que no existe en esta municipalidad es **404**
        nombrándolo, no una página vacía.
      `),
    },
  ],
  // Y la pregunta inversa: quien esta EN un grupo. De `miembro` solo habia el
  // POST que afilia, asi que derivarla costaba una peticion por usuario del
  // padron de cuentas (#582).
  miembros: [
    {
      operationId: 'miembros_del_grupo',
      metodo: 'get',
      titulo: 'Usuarios que pertenecen a un grupo',
      paginacion: true,
      descripcion: literal(`
        Quiénes están en un grupo (#582, RF-120): la pregunta inversa de
        \`/seguridad/usuarios/{id}/grupos\`. Hasta ahora no se podía hacer —de esta
        ruta sólo existía el \`POST\` que afilia—, así que responder «quién está
        dentro» obligaba a recorrer el padrón de cuentas preguntando por cada una.

        **Sólo las pertenencias activas.** Una baja no se borra —la fila sigue ahí con
        \`activo\` en falso (RNF-051)—, pero quien salió del grupo ya no está en él.
        Lo que **sí** devuelve son los usuarios **deshabilitados** que siguen
        afiliados: estar en el grupo y poder entrar son cosas distintas, y cada fila
        publica \`habilitado\` para separarlas — es lo que permite contestar qué cuenta
        deshabilitada conserva permisos sin una segunda lectura.

        Un \`grupo\` que no existe en esta municipalidad es **404**; un grupo sin
        miembros es una página vacía con **200**. No tener a nadie y no existir son dos
        respuestas distintas.
      `),
    },
  ],
  // Las cuatro escrituras de grupo que `AdministrarSeguridad` tenia desde #543
  // sin ninguna ruta que llegara a ellas (#572). No dependian de ninguna
  // decision: un grupo es una fila de esta base y de ninguna otra.
  grupos: [
    {
      operationId: 'registrar_grupo',
      metodo: 'post',
      titulo: 'Alta de un grupo de usuarios',
      descripcion: bloque(`
        Da de alta un grupo (#572, RF-120). Los permisos se otorgan al grupo y los usuarios se
        afilian: dar de alta a alguien es meterlo en «Mesa de Partes», no repetirle veinte
        permisos.

        El grupo nace **habilitado**; la vigencia es opcional y sus dos extremos significan cosas
        distintas —sin \`vigenciaDesde\` vale desde siempre, sin \`vigenciaHasta\` vale
        indefinidamente (RF-123)—.

        Un nombre ya usado en esta municipalidad es **409**: lo garantiza \`grupo_nombre_uq\`.
        Exige \`REGISTRO\` sobre \`grupos\` y la observación del usuario, obligatoria (RNF-052).
      `),
    },
    {
      operationId: 'inhabilitar_grupo',
      metodo: 'post',
      ruta: '/api/v1/seguridad/grupos/{id}/baja',
      titulo: 'Baja de un grupo',
      descripcionesDeRuta: {
        id: 'El grupo, por el `id` que publica cada fila de `GET /seguridad/grupos`',
      },
      descripcion: bloque(`
        Inhabilita el grupo: **retira el acceso de todos sus miembros de golpe** y no borra
        ninguna relación (RNF-051). Las filas de \`miembro\` siguen ahí diciendo quién pudo hacer
        qué y hasta cuándo, y volver a habilitarlo devuelve el acceso a los mismos.

        Exige \`ELIMINACION\` sobre \`grupos\` —quita acceso a gente— y la observación del
        usuario.
      `),
    },
    {
      operationId: 'habilitar_grupo',
      metodo: 'post',
      ruta: '/api/v1/seguridad/grupos/{id}/reactivacion',
      titulo: 'Reactivación de un grupo',
      descripcionesDeRuta: {
        id: 'El grupo, por el `id` que publica cada fila de `GET /seguridad/grupos`',
      },
      descripcion:
        'Vuelve a habilitar un grupo inhabilitado: sus miembros recuperan el acceso que tenían,' +
        ' sin repetir un solo permiso. Exige `MODIFICACION` sobre `grupos` y la observación del' +
        ' usuario (RNF-052).',
    },
    {
      operationId: 'fijar_vigencia_de_grupo',
      metodo: 'put',
      ruta: '/api/v1/seguridad/grupos/{id}/vigencia',
      titulo: 'Vigencia de un grupo',
      descripcionesDeRuta: {
        id: 'El grupo, por el `id` que publica cada fila de `GET /seguridad/grupos`',
      },
      descripcion: bloque(`
        Fija desde cuándo y hasta cuándo vale el grupo (RF-123). El cuerpo declara la vigencia
        **entera**: mandar los dos extremos nulos es *quitar* la caducidad, no «no cambiar nada».

        Exige \`MODIFICACION\` sobre \`grupos\` y la observación del usuario (RNF-052).
      `),
    },
  ],
  // La pantalla «Usuarios del sistema» dibuja una columna «Grupo» y su endpoint
  // —el listado— no la puede llenar: la pertenencia vive en `miembro`, y de esa
  // tabla solo habia el POST que afilia (#543).
  usuarios: [
    {
      operationId: 'grupos_del_usuario',
      metodo: 'get',
      ruta: '/api/v1/seguridad/usuarios/{id}/grupos',
      titulo: 'Grupos a los que pertenece un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      paginacion: true,
      descripcion: literal(`
        A qué grupos pertenece un usuario (#543, RF-120). Sin esta lectura no hay
        «heredado» que calcular en la matriz de permisos: se sabía qué da cada grupo y
        no a cuáles pertenece cada persona —\`/seguridad/grupos/{grupo}/miembros\` es
        sólo \`POST\`—.

        **Sólo las pertenencias activas.** Una baja no se borra —la fila sigue ahí con
        \`activo\` en falso (RNF-051)—, pero quien salió de un grupo ya no pertenece a
        él. Lo que **sí** devuelve son los grupos inhabilitados o fuera de vigencia a
        los que se sigue perteneciendo: pertenecer y surtir efecto son cosas distintas,
        y cada grupo publica su estado y su vigencia para separarlas.

        Un \`id\` que no existe en esta municipalidad es **404**; no pertenecer a ningún
        grupo es una página vacía con **200**.
      `),
    },
    {
      operationId: 'registrar_usuario',
      metodo: 'post',
      titulo: 'Alta de un usuario',
      descripcion: bloque(`
        Da de alta **la fila del padrón de usuarios**, y sólo eso (#572, ADR-0012 §5).

        Un usuario del SGTM son **dos mitades**: esta fila y la cuenta del proveedor de identidad
        (ADR-0005). Esta operación escribe la primera; **la segunda se declara aparte**, en
        \`despliegue/identidad/municipalidades/<ubigeo>.json\`, que es la fuente versionada de las
        cuentas y la que las reproduce si el clúster se reconstruye. Mientras esa cuenta no
        exista, la persona figura en los listados, admite permisos y **no puede entrar**.

        **No hay campo de clave, y no puede haberlo**: la credencial vive en el proveedor y este
        sistema no la recibe nunca. Tampoco hay campo para el identificador OIDC del sujeto: sería
        pedir un dato que quien atiende no tiene por qué tener, y tecleado mal enlazaría esta fila
        con nadie (ADR-0012 §5.4). El enlace entre las dos mitades es la **cuenta**, que tiene que
        ser el mismo \`preferred_username\` con el que la persona entra.

        Una cuenta ya usada en esta municipalidad es **409**: lo garantiza \`usuario_cuenta_uq\`.
        Exige \`REGISTRO\` sobre \`usuarios\` y la observación del usuario (RNF-052).
      `),
    },
    {
      operationId: 'inhabilitar_usuario',
      metodo: 'post',
      ruta: '/api/v1/seguridad/usuarios/{id}/baja',
      titulo: 'Baja de un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      descripcion: bloque(`
        Inhabilita la cuenta: deja de poder entrar y **no se borra** (RNF-051). Su fila sigue ahí
        para que la bitácora pueda seguir diciendo quién hizo qué.

        **No toca la cuenta del proveedor de identidad**, que esta aplicación no administra: esa
        persona seguirá autenticando y el guardia le negará todo, que es exactamente lo que una
        baja significa aquí. Retirarle además la cuenta es un acto del proveedor.

        Exige \`ELIMINACION\` sobre \`usuarios\` y la observación del usuario (RNF-052).
      `),
    },
    {
      operationId: 'habilitar_usuario',
      metodo: 'post',
      ruta: '/api/v1/seguridad/usuarios/{id}/reactivacion',
      titulo: 'Reactivación de un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      descripcion:
        'Vuelve a habilitar una cuenta inhabilitada: recupera los permisos que tenía —los del' +
        ' grupo y sus excepciones—, sin repetir ninguno. Exige `MODIFICACION` sobre `usuarios` y' +
        ' la observación del usuario (RNF-052).',
    },
    {
      operationId: 'fijar_vigencia_de_usuario',
      metodo: 'put',
      ruta: '/api/v1/seguridad/usuarios/{id}/vigencia',
      titulo: 'Vigencia de un usuario',
      descripcionesDeRuta: {
        id: 'El usuario, por el `id` que publica cada fila de `GET /seguridad/usuarios`',
      },
      descripcion: bloque(`
        Fija desde cuándo y hasta cuándo vale la cuenta (RF-123). Es lo que el manual pide para el
        personal por contrato: su acceso **caduca solo** el día que termina, sin depender de que
        alguien se acuerde de retirarlo.

        El cuerpo declara la vigencia **entera**: mandar los dos extremos nulos es *quitar* la
        caducidad. Exige \`MODIFICACION\` sobre \`usuarios\` y la observación del usuario
        (RNF-052).
      `),
    },
  ],
  // `internamiento` declara «GET /transito/internamientos» como su endpoint —la
  // grilla del deposito—; sus dos acciones, «Registrar ingreso» y «Liberar
  // vehiculo», necesitan verbo propio (#50, RF-064).
  internamiento: [
    {
      operationId: 'registrar_internamiento',
      metodo: 'post',
      titulo: 'Registro de ingreso al depósito',
      descripcion: bloque(`
        Interna un vehículo en el depósito municipal y emite su acta (#50, RF-064). La
        pantalla «Internamiento vehicular» declara «GET /transito/internamientos» como su
        endpoint —la grilla— y su acción «Registrar ingreso» necesita un verbo aparte. El
        cuerpo lleva la placa, el depósito, el concepto del TUPA con que se cobrará la
        custodia y la observación del usuario, obligatoria (RNF-052).
      `),
    },
    {
      operationId: 'liberar_internamiento',
      metodo: 'post',
      ruta: '/api/v1/transito/internamientos/{placa}/liberacion',
      titulo: 'Liberación del vehículo internado',
      descripcion: bloque(`
        Entrega el vehículo a quien lo retira y emite el acta de liberación (#50, RF-064).
        Exige el recibo con que se pagó la custodia: el backend lo acredita contra
        \`tesoreria\` por su API pública, y sin esa acreditación el vehículo no sale. La
        casilla «Custodia cancelada» de la pantalla no basta —la marca quien entrega el
        vehículo—.
      `),
    },
  ],
  // `transito_rg_ordinaria` declara «POST /transito/resoluciones/ordinaria»
  // —dictarla—; notificarla necesita ruta propia. Infracciones administrativas
  // SI tiene su pantalla de notificacion en el manual; transito no, y sin ella
  // la sancionadora no se puede dictar nunca porque su plazo se cuenta desde
  // que la ordinaria surte efecto (#50, RF-074).
  transito_rg_ordinaria: [
    {
      operationId: 'notificar_resolucion_transito',
      metodo: 'post',
      ruta: '/api/v1/transito/resoluciones/{numero}/notificacion',
      tras: 'transito_rg_sancionadora',
      titulo: 'Notificación de resolución de gerencia de tránsito',
      descripcion: bloque(`
        Cédula de notificación de la resolución ordinaria o sancionadora de tránsito, con
        su acuse (#50, RF-074). **Es de donde sale el derecho a la sancionadora**: la
        diligencia que surte efecto sobre la ordinaria fija, con el plazo parametrizado del
        conjunto sellado, el día desde el que se puede sancionar. Infracciones
        administrativas tiene la suya
        (\`/infracciones/administrativas/resoluciones/{id}/notificacion\`); tránsito no la
        tenía, y sin ella la sancionadora no se puede dictar nunca.
      `),
    },
  ],
  // `calles` declara «GET /catastro/vias» como su endpoint —la lectura del
  // catalogo vial—; el alta y la edicion que pide su pantalla de mantenimiento
  // (RF-008, #290) necesitan un verbo aparte.
  calles: [
    {
      operationId: 'registrar_via',
      metodo: 'post',
      titulo: 'Alta de vía',
      descripcion:
        'Da de alta una vía del catálogo vial (RF-008). El cuerpo lleva tipo, código,' +
        ' nombre y la observación del usuario, obligatoria (RNF-052).',
    },
    {
      operationId: 'editar_via',
      metodo: 'put',
      ruta: '/api/v1/catastro/vias/{codigo}',
      titulo: 'Edición de vía',
      descripcion:
        'Modifica una vía existente o la da de baja (activa=false). No se borra: la baja' +
        ' es la misma fila con otro estado (RNF-051). El código de la ruta identifica la' +
        ' vía y no cambia.',
    },
  ],
  // `sectores` declara «GET /catastro/sectores» como su endpoint —la lectura del
  // catálogo territorial—; el alta y la edición del sector, y el alta de una
  // manzana dentro de él, necesitan sus propios verbos (#290).
  sectores: [
    {
      operationId: 'registrar_sector',
      metodo: 'post',
      titulo: 'Alta de sector',
      descripcion:
        'Da de alta un sector del catastro. El cuerpo lleva código, nombre, la zona' +
        ' —opcional— y la observación del usuario, obligatoria (RNF-052). Un sector nace' +
        ' activo: darlo de baja es el PUT.',
    },
    {
      operationId: 'editar_sector',
      metodo: 'put',
      ruta: '/api/v1/catastro/sectores/{codigo}',
      titulo: 'Edición de sector',
      descripcion:
        'Modifica un sector existente o lo da de baja (activo=false). No se borra: la baja' +
        ' es la misma fila con otro estado (RNF-051), y tiene que serlo porque su código es' +
        ' un tramo del código catastral de sus predios. El código de la ruta identifica el' +
        ' sector y no cambia.',
    },
    {
      operationId: 'listado_de_manzanas',
      metodo: 'get',
      ruta: '/api/v1/catastro/sectores/{codigo}/manzanas',
      paginacion: true,
      titulo: 'Manzanas del sector',
      descripcion:
        'Las manzanas del sector que identifica el código de la ruta, con lo que cuelga de cada' +
        ' una: `predios` —los **activos** de ese sector que la declaran— y `lotes` —cuántos' +
        ' valores de lote distintos hay entre ellos—. Que `lotes` sea menor que `predios` es lo' +
        ' normal: tres departamentos de un mismo lote son tres predios y **un** lote. Hasta #537 el' +
        ' backend sólo publicaba **cuántas** manzanas tiene un sector (`SectorResource.manzanas`) y' +
        ' el alta de una, así que el árbol territorial no podía enumerarlas. **Pagina como el resto' +
        ' de listados**, y hace falta: un sector de una municipalidad grande pasa de mil manzanas.' +
        ' Un código de sector que no existe es **404**, no una página vacía: cero filas significa' +
        ' «ese sector todavía no tiene manzanas», que es lo contrario. Exige LECTURA sobre' +
        ' `sectores`, el mismo acceso que el listado de sectores —las manzanas no tienen pantalla' +
        ' propia en el manual—. **No publica ningún `activa`**: `manzana` no tiene columna de' +
        ' estado, porque una manzana no se edita ni se da de baja (su código es un tramo del código' +
        ' catastral de sus predios), y un `true` constante sería un filtro que no filtra nada.',
      descripcionesDeRuta: {
        codigo: 'El sector, por su código; el mismo que identifica el sector en `PUT` y en el alta',
      },
    },
    {
      operationId: 'registrar_manzana',
      metodo: 'post',
      ruta: '/api/v1/catastro/sectores/{codigo}/manzanas',
      titulo: 'Alta de manzana',
      descripcion:
        'Da de alta una manzana dentro del sector que identifica el código de la ruta. No hay' +
        ' verbo para editarla: el código de una manzana es un tramo del código catastral de' +
        ' sus predios, así que cambiarlo desalinearía el de todos ellos.',
    },
  ],
  // `consulta_fichas` declara «GET /catastro/fichas» como su endpoint —la
  // grilla, que sirve catastro—; la MISMA grilla con la columna «Conciliada»
  // no la puede servir catastro (#344, ADR-0015 §2): el estado de conciliación
  // se deriva de `declaracion_jurada`, que es de rentas, y depender de rentas
  // cerraría un ciclo entre módulos que `verificarArquitectura` rechaza. La
  // sirve rentas, en esta ruta, y la de catastro redirige aquí con 307 la
  // petición que trae el filtro: ignorarlo devolvería el listado sin filtrar,
  // que es un resultado plausible y equivocado.
  //
  // Sus parámetros **no** son los de la pantalla: `ConciliacionController` acepta
  // además `tipo`, que la grilla de catastro no dibuja, y da a
  // `conciliadaConRentas` un significado propio. Van declarados uno a uno, en el
  // orden del controlador.
  consulta_fichas: [
    {
      operationId: 'consulta_fichas_conciliacion',
      metodo: 'get',
      ruta: '/api/v1/catastro/fichas/conciliacion',
      titulo: 'Consulta de fichas con su conciliación con rentas',
      descripcion:
        'La misma grilla de `consulta_fichas` con la columna «Conciliada» y el filtro' +
        ' `conciliadaConRentas`. Un predio está conciliado a un ejercicio cuando existe una' +
        ' declaración jurada de ese ejercicio, con su mismo `predio_id`, en estado PRESENTADA u' +
        ' OBSERVADA (ADR-0015 §1). La sirve `rentas` y no `catastro` —el derivado sale de la' +
        ' declaración jurada y catastro no puede depender de rentas—, pero el acceso que exige es' +
        ' el de la pantalla, `consulta_fichas`. De la declaración jurada no viaja nada: ni su' +
        ' número, ni su tipo, ni sus importes, ni quién la presentó; solo el derivado y el' +
        ' ejercicio al que responde (regla 9). `conciliadaConRentas=No` —la lista de los predios' +
        ' que no generan deuda predial— exige además privilegio de lectura sobre `fisc_omisos` y' +
        ' deja fila en la bitácora con operación ACCESO.',
      parametros: [
        { nombre: 'codRefCatastral', descripcion: 'Filtro «Cod. Ref. Catastral» de la pantalla' },
        { nombre: 'contribuyente', descripcion: 'Filtro «Contribuyente» de la pantalla' },
        { nombre: 'manzana', descripcion: 'Filtro «Manzana» de la pantalla' },
        { nombre: 'lote', descripcion: 'Filtro «Lote» de la pantalla' },
        { nombre: 'tipo', descripcion: 'UNICA | ECONOMICA | BIENES_COMUNES | RURAL' },
        {
          nombre: 'conciliadaConRentas',
          ejemplo: 'Todas',
          descripcion:
            'Todas | Sí | No. «No» exige privilegio de lectura sobre `fisc_omisos` y queda' +
            ' registrado en la bitácora (ADR-0015 §2.3).',
        },
        {
          nombre: 'ejercicio',
          ejemplo: '2026',
          descripcion:
            'A qué ejercicio responde la conciliación; si falta, el de la fecha de corte. La' +
            ' respuesta lo dice siempre (regla 9, RNF-075)',
        },
        {
          nombre: 'fecha',
          ejemplo: '2026-08-28',
          descripcion:
            'Fecha de corte a la que se resuelven la versión de ficha y el titular vigentes; si' +
            ' falta, hoy',
        },
      ],
      paginacion: true,
    },
    // El recuento de la conciliacion (#564). No es la grilla con `tamano=1`: la
    // grilla NO SE PUEDE USAR PARA CONTAR —el filtro se aplica sobre la pagina y
    // `totalElementos` sigue siendo el del padron sin filtrar—, y medido sobre
    // Catacaos los tres valores del filtro devolvian 14 422, o sea el padron
    // entero. El panel de Catastro pintaba con esa cifra «Predios sin conciliar:
    // 14 422» encima de «14 422 predios en el padron».
    //
    // No pagina y no acepta los filtros de la grilla, a proposito: la pregunta es
    // sobre el padron, y aceptarlos obligaria a repetir aqui aquel WHERE.
    {
      operationId: 'conciliacion_resumen',
      metodo: 'get',
      ruta: '/api/v1/catastro/fichas/conciliacion/resumen',
      titulo: 'Recuento de la conciliación con rentas',
      descripcion:
        'Cuántos predios del padrón hay a esa fecha, cuántos declararon ese ejercicio y cuántos' +
        ' no, resuelto en **una** consulta agregada y sin recorrer el padrón (#564). Existe' +
        ' porque la grilla no sirve para contar: su filtro se aplica sobre la página ya devuelta' +
        ' y su `totalElementos` es el del padrón sin filtrar. La población es la misma que lista' +
        ' la grilla —las fichas vigentes a la fecha—, y que lo siga siendo lo comprueba una' +
        ' prueba que compara las dos cifras. A diferencia de `conciliadaConRentas=No`, **no** ' +
        'exige privilegio sobre `fisc_omisos` y **no** deja fila en la bitácora: aquella nombra' +
        ' —es la lista de a quién no le va a llegar recibo— y ésta cuenta.',
      parametros: [
        {
          nombre: 'ejercicio',
          ejemplo: '2026',
          descripcion:
            'A qué ejercicio responde el recuento; si falta, el de la fecha de corte. La' +
            ' respuesta lo dice siempre: no existe «sin conciliar», existe «sin conciliar a' +
            ' 2026» (regla 9, RNF-075)',
        },
        {
          nombre: 'fecha',
          ejemplo: '2026-08-28',
          descripcion:
            'Fecha de corte a la que se resuelve qué versión de ficha rige, igual que en la' +
            ' grilla; si falta, hoy',
        },
      ],
    },
    // El plano catastral (#500, ADR-0022). Cuelga de `/catastro/predios` porque
    // el recurso es el predio, y sale de `consulta_fichas` porque **es esa misma
    // busqueda por otro camino**: el mapa es la forma principal de encontrar un
    // predio que el diseño promueve, «por manzana y lote, que es como la gente
    // lo piensa». El acceso que exige es por tanto el de esta pantalla y no el
    // de actualizar el catastro, que dejaria sin mapa a quien solo mira.
    //
    // NO PAGINA, y es lo unico que la distingue de toda otra lectura del
    // sistema: un plano al que le faltan lotes se lee como un plano donde no
    // hay lotes, asi que cuando el marco no cabe se **niega** con su cifra
    // (ADR-0022 §2). Tampoco tiene «pagina 2» que signifique nada: no hay un
    // orden que convierta una pagina en una porcion del territorio.
    {
      operationId: 'plano_catastral',
      metodo: 'get',
      ruta: '/api/v1/catastro/predios/plano',
      titulo: 'Plano catastral: los lotes de un marco',
      descripcion: literal(`
        Los lotes que caen dentro de un marco, **con su polígono**, para dibujar el plano
        catastral (ADR-0022). La geometría sale de \`predio.geometria\` —\`geography(MultiPolygon,
        4326)\`, ADR-0021— serializada a GeoJSON tal cual: **ni reproyectada ni simplificada**.
        Un vértice movido es un lindero movido, y un lindero movido no se ve.

        **Se acota por marco y se niega antes que recortarse.** \`bbox\` es obligatorio. Si
        dentro caben más lotes que \`limite\`, la respuesta es **422** diciendo cuántos hay:
        una página con los primeros dibujaría un plano al que le faltan lotes, y eso no se
        lee como «faltan», se lee como «ahí no hay nada». Por lo mismo no pagina.

        **\`sinGeometria\` cuenta los predios del padrón que pasan los mismos filtros de
        sector y de manzana y no tienen polígono, sin acotar por el marco**, y la interfaz lo
        dice siempre, incluso cuando es cero. Que no lo acote el marco no es una omisión: un predio
        sin polígono **no tiene sitio en el marco**, así que acotarlo por \`bbox\` daría cero
        siempre, y daría cero justo cuando la cifra más hace falta —hoy, sin un solo lote
        digitalizado—. El único dato que podría situarlo, el perímetro de su manzana, no existe
        en el esquema, y derivarlo de la unión de los lotes ya levantados es lo que ADR-0022 §5
        prohíbe. Sin esta cifra el visor afirma algo que no sabe: hoy no hay una sola
        municipalidad con geometría cargada, así que lo honesto es que el plano vacío diga por
        qué lo está —la carga cartográfica de ADR-0021— y no que parezca un distrito sin
        predios.

        Ni un importe y ni un titular, por lo mismo que \`GET /catastro/predios\`: quién es
        el propietario se resuelve al clic, de un predio cada vez, en
        \`/catastro/predios/{predioId}/titulares\` (ADR-0015 §2.4). Y **ninguna área**: la del
        polígono no es la imponible, y publicarlas juntas invita a compararlas donde no se
        decide nada.
      `),
      parametros: [
        {
          nombre: 'bbox',
          ejemplo: '-80.71,-4.92,-80.66,-4.87',
          descripcion:
            'Marco en grados WGS84, `oeste,sur,este,norte`. Obligatorio: sin él la consulta' +
            ' sería el padrón entero, que es lo que esta operación existe para no hacer',
        },
        {
          nombre: 'codigoDeSector',
          descripcion: 'Filtro «Sector» de la pantalla, por código',
        },
        {
          nombre: 'codigoDeManzana',
          descripcion: 'Filtro «Manzana» de la pantalla, por código',
        },
        {
          nombre: 'limite',
          ejemplo: '2000',
          descripcion:
            'Cuántos lotes se sirven como máximo. Si el marco contiene más, la respuesta es' +
            ' 422 con la cuenta; nunca los primeros `limite`',
        },
      ],
    },
    // El marco de lo levantado (#612). Existe porque `plano_catastral` exige
    // `bbox` —y hace bien: sin el la consulta seria el padron entero— y NINGUNA
    // operacion del contrato decia donde esta la municipalidad: ni su extension,
    // ni la de un sector, ni un centroide, ni un ubigeo resoluble a coordenadas.
    //
    // Cuelga de la misma ruta y hereda su acceso, porque es el encuadre del
    // mismo mapa. No tiene `bbox` —es justo lo que calcula— ni `limite` —su
    // respuesta son cuatro cifras y una cuenta, pese lo que pese el padron—.
    {
      operationId: 'marco_del_plano',
      metodo: 'get',
      ruta: '/api/v1/catastro/predios/plano/marco',
      titulo: 'Dónde está lo levantado: el marco del plano',
      descripcion: literal(`
        El rectángulo que envuelve la geometría **ya cargada**, con los mismos filtros de sector
        y de manzana que \`GET /catastro/predios/plano\` (#612). Es de dónde sale el primer
        \`bbox\`: aquella operación lo exige, y hasta ahora ninguna decía dónde está la
        municipalidad, así que el visor abría sobre un marco declarado del país entero — y con
        geometría cargada eso contesta «hay N lotes, acércate», una respuesta correcta que desde
        la pantalla no se puede obedecer.

        **Sale de la geometría cargada, nunca de una constante.** Se agrega sobre las cuatro
        columnas que \`V65\` deriva del polígono de cada lote, con los **mismos** filtros que el
        plano: un marco calculado sobre otro conjunto de predios encuadraría sobre algo que
        después no se dibuja, y sobre un plano sin base cartográfica un encuadre equivocado **no
        se ve**.

        **\`marco\` es \`null\` cuando no hay ninguno que publicar, y \`notaDelMarco\` dice por
        qué.** Son dos situaciones distintas y se arreglan distinto: \`lotes: 0\` es que no hay
        ni un predio con polígono —el estado de hoy en todas las municipalidades, lo que falta es
        la carga cartográfica de ADR-0021— y \`lotes > 0\` con \`marco: null\` es que todo lo
        levantado cae sobre la misma línea, o sea que su envolvente no es un rectángulo. Nunca
        \`0,0,0,0\`: ese punto está en el golfo de Guinea.

        Lo que publica es **un rectángulo y una cuenta**, y nada más: ni un \`predioId\`, ni un
        código, ni una dirección. Añadir el del lote más al norte la convertiría en una forma de
        recorrer el padrón sin pedir el padrón.
      `),
      parametros: [
        {
          nombre: 'codigoDeSector',
          descripcion: 'Filtro «Sector» de la pantalla, por código. El mismo que el del plano',
        },
        {
          nombre: 'codigoDeManzana',
          descripcion: 'Filtro «Manzana» de la pantalla, por código. El mismo que el del plano',
        },
      ],
    },
  ],
  // `declaracion_jurada` declara «GET /rentas/declaraciones/{djNro}» como su
  // endpoint —consultar la DJ ya presentada—, y hasta #365 eso era todo lo que
  // el sistema publicaba: el acto que la registra existía en el backend y
  // ningún controlador lo exponía, así que presentar una declaración se seguía
  // haciendo por el procedimiento actual, fuera del sistema (ADR-0015 §3).
  //
  // Los cuatro verbos son actos de trámite sobre un documento, no ediciones:
  // `declaracion_jurada` no admite UPDATE desde V54 salvo sobre su `estado`.
  // Ninguno recibe el número de la DJ en el cuerpo: lo pone el sistema, con el
  // correlativo de `dj_correlativo` y la plantilla parametrizada de D-09.
  declaracion_jurada: [
    {
      operationId: 'hoja_de_declaracion_jurada',
      metodo: 'get',
      ruta: '/api/v1/rentas/declaraciones/{djNro}/hoja',
      titulo: 'Hoja resumen de la declaración jurada',
      parametros: [
        { nombre: 'djNro', en: 'path', requerido: true, descripcion: 'Número de la declaración' },
        {
          nombre: 'ano',
          requerido: true,
          ejemplo: '2026',
          descripcion: 'Ejercicio de la declaración',
        },
        {
          nombre: 'fecha',
          ejemplo: '2026-03-01',
          descripcion:
            'Fecha de corte a la que se resuelven el domicilio y la titularidad (regla 9); si' +
            ' falta, hoy',
        },
      ],
      descripcion: bloque(`
        Lo que la hoja resumen consigna: el **declarante** —código, nombre, documento y domicilio
        fiscal **vigente a la fecha**—, sus **predios** con su % de propiedad, y las **cifras** de
        la última determinación predial del ejercicio (#563).

        Es el único documento del módulo pensado para **imprimirse y firmarse** —termina en
        «Declaro bajo juramento…» y dos líneas de firma— y todo lo que consignaba venía del juego
        de datos de la maqueta: el nombre y el DNI de otra persona, dos predios que no son suyos y
        un «total a pagar». Una vez impresa y firmada, una hoja así **no se distingue de una
        correcta**, y a diferencia de una pantalla nadie la vuelve a mirar contra la base.

        **Un campo nulo es un campo que no hay.** Sin determinación del ejercicio no hay autovalúo,
        ni valúo afecto, ni impuesto: publicar cero sería escribir «no debe nada» en un papel que
        alguien firma. Y ni siquiera con determinación viajan el **derecho de emisión** y el
        **total a pagar**: la determinación guarda la base y el impuesto, no el derecho, que es
        \`DERECHO_EMISION_PREDIAL\` del conjunto sellado —cifra de ordenanza local, D-02b—.

        \`faltan\` es una **lista de motivos**, no un booleano: «no se puede imprimir» sin decir por
        qué es lo que hace que alguien lo imprima igual desde otro sitio.

        Una declaración que no existe es **404**, no una hoja vacía.
      `),
    },
    {
      operationId: 'presentar_declaracion_jurada',
      metodo: 'post',
      ruta: '/api/v1/rentas/declaraciones',
      titulo: 'Presentación de la declaración jurada',
      descripcion: bloque(`
        Presenta una declaración jurada nueva —HR, PU, PR o VEHICULAR— y es **el acto que
        concilia** (ADR-0015 §3): a partir de él el predio pertenece al padrón afecto del
        ejercicio, y la columna «Conciliada» de
        \`/catastro/fichas/conciliacion\` lo dice. El cuerpo lleva el ejercicio, el código de
        contribuyente, el tipo, el predio o el vehículo según el tipo, la fecha de presentación
        y la observación del usuario, obligatoria (RNF-052).

        Lo que **no** viaja en el cuerpo, porque lo resuelve el servidor: el **número** —lo pone
        el sistema, con correlativo propio y plantilla parametrizada mientras D-09 siga
        abierta—, la **versión de ficha catastral** vigente a la fecha de presentación, y
        \`fueraDePlazo\`, que sale de comparar esa fecha con el plazo del conjunto sellado. Un
        ejercicio sellado sin ese parámetro responde 422 **nombrando la llave**
        \`PLAZO:DECLARACION_JURADA\`: inventar un plazo clasificaría mal cada declaración que se
        registre (regla 5).

        Ningún importe. Presentar fuera de plazo genera multa tributaria según el manual, pero
        esa multa es D-02c: aquí queda el hecho y nada que multiplique dinero.
      `),
    },
    {
      operationId: 'rectificar_declaracion_jurada',
      metodo: 'post',
      ruta: '/api/v1/rentas/declaraciones/{djNro}/rectificacion',
      titulo: 'Rectificatoria de la declaración jurada',
      descripcion: bloque(`
        Crea la versión nueva de una declaración ya presentada y deja la anterior SUSTITUIDA sin
        tocarle una columna (regla 4): las dos filas quedan en la base y la nueva referencia a la
        que sustituye. **Puede cambiar de predio**, y la conciliación lo contempla — el predio
        que se declaró por error deja de conciliar por esa cadena y el que la rectificatoria
        declara pasa a hacerlo, sin que ninguno cuente dos veces.

        Solo se rectifica una declaración en pie: sobre una ANULADA o una ya SUSTITUIDA responde
        409. El número de la rectificatoria lo pone el sistema, como el de cualquier otra.
      `),
      parametros: [
        {
          nombre: 'ano',
          ejemplo: '2026',
          descripcion: 'Ejercicio de la declaración que se rectifica, como en el GET de la ruta',
        },
      ],
    },
    {
      operationId: 'observar_declaracion_jurada',
      metodo: 'post',
      ruta: '/api/v1/rentas/declaraciones/{djNro}/observacion',
      titulo: 'Observación de la declaración jurada',
      descripcion: bloque(`
        La administración objeta el contenido de una declaración presentada. El cuerpo lleva
        **solo** la observación del usuario (RNF-052): el efecto lo decide el verbo.

        **Observarla no la retira**: el predio sigue conciliando (ADR-0015 §1), porque la
        administración objetó el contenido de una declaración que existe y fue presentada, y
        negarle la conciliación diría «este predio no genera deuda predial» de uno que sí la
        genera. Lo que la observación abre es el camino de la rectificatoria.
      `),
      parametros: [
        {
          nombre: 'ano',
          ejemplo: '2026',
          descripcion: 'Ejercicio de la declaración que se observa, como en el GET de la ruta',
        },
      ],
    },
    {
      operationId: 'anular_declaracion_jurada',
      metodo: 'post',
      ruta: '/api/v1/rentas/declaraciones/{djNro}/anulacion',
      titulo: 'Anulación de la declaración jurada',
      descripcion: bloque(`
        La administración anula una declaración. El cuerpo lleva **solo** la observación del
        usuario (RNF-052).

        Al revés que observarla, anularla **sí** la retira: deja de sustentar nada y el predio
        deja de conciliar por ella. Y es terminal — una anulada no revive: si el contribuyente
        declara otra vez, se presenta otra declaración, con su número. Un acto sobre una
        declaración anulada o ya sustituida responde 409.
      `),
      parametros: [
        {
          nombre: 'ano',
          ejemplo: '2026',
          descripcion: 'Ejercicio de la declaración que se anula, como en el GET de la ruta',
        },
      ],
    },
  ],
  // `parametros` declara «GET /seguridad/parametros» —el listado paginado de los
  // conjuntos—; preguntar por UN ejercicio necesita ruta propia (#605). Cuelga de
  // esta pantalla porque los datos son suyos, pero **no exige su permiso**: el
  // acceso `parametros` es una opción del módulo Seguridad y quien la necesita es
  // quien calcula, que tiene `fraccionamiento` o `predial_individual`.
  parametros: [
    {
      operationId: 'ejercicio_parametrizado',
      metodo: 'get',
      ruta: '/api/v1/seguridad/parametros/ejercicios/{ejercicio}',
      titulo: 'Si el ejercicio tiene parámetros sellados',
      descripcionesDeRuta: {
        ejercicio: 'El año que se pregunta, de 1990 a 2100',
      },
      descripcion: literal(`
        Si un ejercicio tiene conjunto de parámetros **sellado**, y cuál — para poder
        decirlo **antes** de calcular (#605, ADR-0007).

        Hasta aquí ninguna ruta lo publicaba, así que toda pantalla que calcula tenía que
        fallar para enterarse: se rellenaba el formulario entero —y en «Registrar
        preconvenio» también la observación que exige la regla 10— para recibir al final
        el 422 «El ejercicio 2026 no tiene un conjunto de parámetros sellado», que con
        D-02a abierta es lo que contestan hoy **todas** las municipalidades. El mismo
        agujero tienen la determinación predial, la valorización del FUE, la liquidación de
        fiscalización y el resumen anual de licencias.

        **Ninguna cifra.** Sale \`sellado\` y la identidad del conjunto —\`conjuntoId\` y
        \`version\`—, que es lo que \`ConvenioResource.conjuntoDeParametros\` ya publica
        cuando el convenio existe. Los valores siguen detrás del permiso de \`parametros\`
        (REQ-03: quien opera el sistema no publica ni consulta las cifras con las que se
        calcula).

        **«No hay conjunto sellado» es 200 diciendo que no**, no un 404: la pregunta es si
        **hay conjunto sellado** y la respuesta puede ser que no. Conviene no leerla como
        «se puede calcular»: sin conjunto sellado seguro que **no** se puede, pero con él
        el cálculo puede fallar igual si falta dentro alguna llave que la regla pida —el
        422 que nombra la llave (#547, #562)—. Esta lectura adelanta la primera mitad, que
        es la que hoy falla en todas las municipalidades. Un ejercicio **fuera del rango
        1990 a 2100** sí es distinto, y sale 422 nombrando el rango.

        **No deja fila en la bitácora**, a diferencia de las demás lecturas que registran
        \`ACCESO\`: es la única fuera del catálogo, y auditarla pondría una escritura sin
        cota, sobre una tabla que no se poda, al alcance de cualquier token válido.

        Autenticada, pero **no es una opción del catálogo**: exigir \`parametros\` —del
        módulo Seguridad— dejaría esta lectura fuera del alcance de quien la necesita, y
        otorgárselo en cada implantación invertiría la separación de funciones de REQ-03
        para poder leer un booleano. El criterio es el de \`permisos_de_la_sesion\`
        (ADR-0013, REQ-03 §5) y aquí es literal: el 422 de cualquier operación que calcule
        ya dice lo mismo. Lo que sí deja, y las otras lecturas de sesión no, es su fila de
        \`ACCESO\`: ésta admite un parámetro, así que quien recorra 1990 a 2100 deja su
        nombre en cada intento.
      `),
    },
  ],
  // `contribuyentes` declara «GET /rentas/contribuyentes» como su endpoint —el
  // padrón—; resolver quién es el titular de UN predio, con su código, necesita
  // ruta propia (#366, ADR-0015 §2.4). Cuelga de esta pantalla y no de
  // `consulta_fichas` porque el permiso que exige es el del padrón: lo que se
  // pide no es catastro, es el identificador de una persona.
  //
  // La ruta, en cambio, sí es la de la pantalla desde la que se hace clic. Y la
  // sirve `rentas`, que es el único módulo que ve `catastro` y `contribuyentes`
  // a la vez sin cerrar un ciclo —`catastro` ya depende del padrón—; quién la
  // sirve es un detalle de dónde vive el código.
  contribuyentes: [
    {
      operationId: 'titulares_del_predio',
      metodo: 'get',
      ruta: '/api/v1/catastro/predios/{predioId}/titulares',
      titulo: 'Titulares del predio, con su código de contribuyente',
      descripcion:
        'Quién es titular de un predio a una fecha, con el código con el que se entra a su ficha' +
        ' de contribuyente. Es la resolución que la fila de `consulta_fichas` necesita para poder' +
        ' enlazar con la persona: la grilla publica el nombre del titular y no su identificador,' +
        ' y añadirlo allí convertiría «quien puede listar fichas» en «quien puede cosechar la' +
        ' correlación predio→persona de toda la municipalidad» (ADR-0015 §2.4). Por eso se' +
        ' resuelve **al clic, de un predio cada vez**: exige privilegio de lectura sobre' +
        ' `contribuyentes` —el permiso del padrón, no el de la pantalla desde la que se hace' +
        ' clic— y cada resolución deja fila en la bitácora con operación ACCESO, la devuelva o no' +
        ' algún titular. Devuelve la **lista** de cuotas vigentes, no «el» titular: un predio' +
        ' puede tener varios —dos cónyuges, una sucesión, un condominio—, cada uno con su' +
        ' porcentaje. La respuesta dice siempre a qué fecha contesta (regla 9, RNF-075). Del' +
        ' padrón no viaja nada más: ni el identificador interno del contribuyente, ni su' +
        ' documento; y de la titularidad, ni sus fechas ni el documento que la sustenta.',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
      },
      parametros: [
        {
          nombre: 'vigenteA',
          ejemplo: '2026-08-28',
          descripcion:
            'Fecha a la que se resuelve la titularidad; si falta, hoy. La titularidad de marzo no' +
            ' es la de setiembre, y la respuesta dice siempre a cuál contesta (regla 9)',
        },
      ],
    },
  // #488 — el padron se leia y no se escribia. `contribuyentes` declara UN endpoint
    // —el `GET` de la grilla de busqueda— y su alta, su correccion, su baja y toda la
    // ficha que cuelga de el (domicilios, contactos, responsables solidarios) necesitan
    // verbo propio. Hasta aqui los casos de uso existian desde #11 y #15 y ningun
    // controlador los publicaba: una municipalidad recien implantada no podia registrar
    // a su primer contribuyente desde la aplicacion, solo por el proceso de importacion
    // por lotes del perfil `batch`.
    //
    // POR QUE CUELGAN DE `/rentas/` Y NO ESTRENAN `/contribuyentes/`. El prefijo de una
    // ruta de este contrato nombra EL MODULO DEL MANUAL al que pertenece la pantalla, no
    // el contexto acotado que la sirve: `/catastro/contribuyentes/{codigo}/ficha.pdf` lo
    // sirve catastro y `/rentas/contribuyentes` lo sirve el contexto `contribuyentes`, y
    // las dos hablan de contribuyentes. Con esa convencion ya fijada, `/contribuyentes/`
    // no seria «mas coherente con el contexto»: seria una SEGUNDA convencion conviviendo
    // con la primera en el mismo archivo, y la ruta dejaria de decir en que modulo del
    // manual esta la pantalla sin empezar a decir nada mas fiable —el contexto que sirve
    // una ruta no se lee de la ruta, se lee del controlador—. Ademas, todas las
    // escrituras del contrato siguen a su pantalla: `calles` lee en `/catastro/vias` y
    // edita en `/catastro/vias/{codigo}`; `declaracion_jurada` lee en
    // `/rentas/declaraciones/{djNro}` y presenta en `/rentas/declaraciones`. Estrenar el
    // prefijo haria de esta la unica excepcion. El criterio de la casa es que el contrato
    // esta derivado del prototipo (#312); la pantalla del padron vive en «Rentas ·
    // Registro», y ahi se queda.
    {
      operationId: 'registrar_contribuyente',
      metodo: 'post',
      titulo: 'Alta de contribuyente',
      descripcion: bloque(`
        Da de alta a un contribuyente en el padrón (#488, RF-013). El cuerpo lleva el código,
        el tipo y número de documento, el tipo de persona, el nombre o razón social, la
        condición especial si la hay, y la observación del usuario, obligatoria (RNF-052).

        El código o el documento repetidos salen como **409**. La unicidad la exige la base
        —es la única que puede—, pero el caso de uso comprueba antes para poder decir *cuál de
        los dos* se repitió; el mensaje del documento repetido **no dice con quién**, que sería
        revelar que una persona está en el padrón a quien sólo teclea documentos.

        No entran la fecha de nacimiento, el estado civil ni el cónyuge, que la tabla sí
        guarda: la lectura del padrón no los publica —lo que no se publica no se filtra—, y un
        campo que se puede escribir y nunca leer de vuelta es una trampa.
      `),
    },
    {
      operationId: 'modificar_contribuyente',
      metodo: 'put',
      ruta: '/api/v1/rentas/contribuyentes/{id}',
      titulo: 'Corrección o baja del contribuyente',
      descripcion: bloque(`
        Corrige el nombre o la condición especial de un contribuyente ya registrado, o lo da de
        baja (#488). **Lo que no viene, no cambia**; para *quitar* la condición especial se manda
        la cadena vacía, que es una instrucción y no una omisión.

        **El código y el documento no se corrigen por aquí.** Son la identidad: el código enlaza
        sus predios, sus recibos y sus asientos, y el documento es con lo que se le acredita.
        Cambiar cualquiera de los dos no es corregir una ficha sino decidir que dos filas eran
        la misma persona, y eso es otro acto.

        \`activo = false\` es la baja y **no borra** (RNF-051): el código aparece en recibos ya
        emitidos y en asientos del libro. Exige además el privilegio \`ELIMINACION\`.
      `),
    },
    {
      operationId: 'ficha_del_contribuyente',
      metodo: 'get',
      ruta: '/api/v1/rentas/contribuyentes/{id}/ficha',
      titulo: 'Ficha del contribuyente a una fecha',
      parametros: [
        {
          nombre: 'fecha',
          descripcion:
            'Fecha de corte. Ausente, hoy. Lo vigente se resuelve A ESA FECHA, no «lo ultimo».',
        },
      ],
      descripcion: bloque(`
        Dónde está, cómo se le ubica y quién responde con él: domicilio fiscal y procesal
        **vigentes a la fecha**, el historial completo de domicilios, los contactos y los
        responsables solidarios (#488).

        Existe por dos motivos. Las escrituras de la ficha necesitan identificadores que
        ninguna lectura publicaba —dar de baja un contacto exige decir cuál—; y las cuatro
        consultas van en **una sola** transacción (#486): cuatro por separado dejarían sitio
        entre medias a una mudanza, y la ficha saldría diciendo que el contribuyente vive en
        dos sitios y en ninguno.

        \`domicilioFiscal\` puede ser nulo: un contribuyente recién dado de alta todavía no
        tiene ninguno, y decirlo es más honesto que devolver el último que hubo (regla 9).
      `),
    },
    {
      operationId: 'vehiculos_del_contribuyente',
      metodo: 'get',
      ruta: '/api/v1/rentas/vehiculos',
      titulo: 'Vehiculos de un contribuyente',
      parametros: [
        {
          nombre: 'contribuyente',
          descripcion:
            'Codigo del contribuyente. Uno de los dos —este o «codContribuyente»— es ' +
            'OBLIGATORIO: sin ninguno, 422, porque sin criterio esto seria una segunda puerta ' +
            'al padron vehicular entero detras de un permiso mas estrecho que el de Consultas. ' +
            'Y un codigo que no esta en el padron es 404, no una pagina vacia (#595).',
        },
        {
          nombre: 'codContribuyente',
          descripcion:
            'El mismo filtro con el nombre que usa su hermana GET /rentas/predios, con la que ' +
            'esta lectura llena la misma seccion del expediente. Uno de los dos —este o ' +
            '«contribuyente»— es OBLIGATORIO: sin ninguno, 422 (#595).',
        },
        {
          nombre: 'fecha',
          descripcion: 'Fecha de corte de la deuda (regla 9). Ausente, hoy.',
        },
      ],
      descripcion: bloque(`
        Los vehículos de un contribuyente, con su deuda a la fecha (#524).

        **La consulta ya existía y la sirve este mismo contexto** —ConsultaVehiculosController,
        GET /consultas/vehiculos—, pero bajo la opción del módulo **Consultas** y su permiso. El
        expediente del contribuyente de Rentas (#503 F2) no puede tomarla prestada de ahí: las
        conexiones de la interfaz llegan con el trozo de su módulo (#433), y quien tenga Rentas y
        no Consultas vería un aviso de permiso ajeno dentro de su propio expediente.

        Va detrás del permiso de la opción «Ficha de vehículo» —la de Rentas que ya existe— y
        **exige el contribuyente**: sin él, quien sólo tiene ese permiso pasaría de necesitar la
        placa para ver una ficha a poder listar el padrón vehicular entero.

        La fila es la misma que publica /consultas/vehiculos: dos formas distintas de la misma
        lectura dirían dos cosas del mismo vehículo, y la que se leyera en el expediente sería la
        que nadie compara.

        **Un código que no está en el padrón es 404, no una página vacía** (#595). Un 200 con cero
        filas se lee como «esta persona no tiene ningún vehículo», y sobre un código mal tecleado
        —o de otra municipalidad— esa frase es falsa; se leía además junto a la de predios, que
        desde #541 sí dice «ese código no está en el padrón», una debajo de la otra. Lo único que
        sigue siendo 200 con cero filas es un contribuyente del padrón sin vehículos.
      `),
    },
    {
      operationId: 'ultima_corrida_predial',
      metodo: 'get',
      ruta: '/api/v1/rentas/predial/corridas/ultima',
      titulo: 'Estado de la ultima emision del ejercicio',
      parametros: [
        {
          nombre: 'ejercicio',
          descripcion: 'El ejercicio cuya ultima corrida se pide. Ausente, el del reloj.',
        },
      ],
      descripcion: bloque(`
        Lo que hizo la última corrida de emisión anual del predial (#523): sus etapas, cuántos
        contribuyentes se leyeron, cuántos se determinaron, cuánto se emitió y **cuántos quedaron
        observados**.

        Hasta esto la corrida viajaba **sólo en la respuesta del POST que la ejecuta**: cerrar la
        pestaña perdía el resultado de un proceso que toca decenas de miles de cuentas, y volver a
        verlo exigía volver a correrlo. Los observados eran lo único que no se podía recomponer
        leyendo el padrón — un observado es, por definición, el que **no** tiene determinación.

        Devuelve también las **simulaciones**, y lo dice: el campo «simulacion» distingue las dos.
        Esconderlas haría que «ver los observados antes de emitir» —que es lo que hay que hacer
        antes de una emisión— no dejara nada que mirar después.

        Sin corridas del ejercicio contesta **204**, no una cabecera de ceros: «todavía no se ha
        corrido» y «se corrió y no emitió nada» son dos cosas distintas.
      `),
    },
    {
      operationId: 'observados_de_la_corrida',
      metodo: 'get',
      ruta: '/api/v1/rentas/predial/corridas/{corridaId}/observados',
      titulo: 'Los observados de una corrida',
      parametros: [
        {
          nombre: 'corridaId',
          en: 'path',
          requerido: true,
          descripcion: 'La corrida, por el id que devuelve la lectura de la ultima.',
        },
      ],
      descripcion: bloque(`
        Los contribuyentes que quedaron fuera de la emisión, cada uno **con su motivo** (#523). Es
        lo único que convierte «emitió menos de lo esperado» en una lista de cosas que arreglar.

        Van aparte de la cabecera y paginados, no dentro de ella: son cientos, y una portada que
        los trajera siempre sería la petición más pesada del sistema para una cifra que casi nadie
        abre.
      `),
    },
    {
      operationId: 'mudar_contribuyente',
      metodo: 'post',
      ruta: '/api/v1/rentas/contribuyentes/{id}/domicilios',
      titulo: 'Mudanza: cierra el domicilio anterior y abre el nuevo',
      descripcion: bloque(`
        Muda al contribuyente (#488, RF-014). **Es un \`POST\` y no un \`PUT\` porque no
        reemplaza nada**: agrega un tramo de vigencia y cierra el anterior *el día antes* de que
        empiece el nuevo, en la misma transacción. Si fuera una edición, la dirección vieja
        desaparecería y con ella la única prueba de por qué se notificó donde se notificó.

        Que los dos no rijan el mismo día es deliberado: si lo hicieran, preguntar «dónde vivía
        ese día» tendría dos respuestas.

        El índice parcial \`domicilio_fiscal_vigente_uq\` impide que queden dos vigentes aunque
        el código se equivoque; lo que no puede exigir es que quede uno, y de eso se encarga la
        transacción. \`vigenciaDesde\` ausente es hoy.
      `),
    },
    {
      operationId: 'registrar_contacto',
      metodo: 'post',
      ruta: '/api/v1/rentas/contribuyentes/{id}/contactos',
      titulo: 'Alta de contacto del contribuyente',
      descripcion: bloque(`
        Un teléfono, un celular, un correo o un gestor (#488, RF-015). \`nota\` es la observación
        *del contacto* —«llamar después de las 6»— y \`observacion\` es la del usuario que guarda
        (RNF-052): se llaman distinto a propósito, porque la tabla las tiene con el mismo nombre
        y un cuerpo con dos \`observacion\` acabaría escribiendo una en el sitio de la otra.
      `),
    },
    {
      operationId: 'modificar_contacto',
      metodo: 'put',
      ruta: '/api/v1/rentas/contribuyentes/{id}/contactos/{contactoId}',
      titulo: 'Corrección o baja de un contacto',
      descripcion: bloque(`
        Corrige un contacto o lo da de baja (#488). **Lo que no viene, no cambia.**
        \`vigente = false\` es la baja y **no borra** (regla 4): un gestor que ya no lo es aparece
        en notificaciones anteriores, y explicar por qué se le notificó exige que su ficha siga
        ahí. La baja exige además el privilegio \`ELIMINACION\`.
      `),
    },
    {
      operationId: 'registrar_responsable_solidario',
      metodo: 'post',
      ruta: '/api/v1/rentas/contribuyentes/{id}/responsables',
      titulo: 'Alta de responsable solidario',
      descripcion: bloque(`
        Quién responde con el contribuyente y desde cuándo (#488, RF-016). \`responsableId\` es
        **otro contribuyente del mismo padrón**, no un nombre suelto: para notificarle hace falta
        su domicilio, y el domicilio cuelga del padrón.

        \`porcentaje\` viaja como **texto** y sólo lo admiten los vínculos que reparten; en los
        demás, mandarlo es 422 y no un campo ignorado en silencio. Como texto porque un número
        del JSON perdería escala antes de que nadie lo mire (regla 1).
      `),
    },
    {
      operationId: 'cerrar_responsable_solidario',
      metodo: 'put',
      ruta: '/api/v1/rentas/contribuyentes/{id}/responsables/{responsableId}',
      titulo: 'Cierre del vínculo de responsabilidad solidaria',
      descripcion: bloque(`
        Cierra el vínculo en una fecha (#488). **No lo borra** (regla 4): la deuda anterior sigue
        siendo suya, y una notificación de entonces se defiende enseñando que el vínculo regía.

        Sólo se cierra uno vigente hoy; uno ya cerrado sale como 404, que es lo que es —no hay
        tal vínculo abierto que cerrar—. \`vigenciaHasta\` ausente es hoy. Exige además el
        privilegio \`ELIMINACION\`.
      `),
    },
  ],
  // Las cuatro pantallas de ficha declaran «GET /catastro/fichas/…/{codigo}»
  // como su endpoint —la lectura de la ficha de un predio—; darla de alta
  // necesita su propio verbo, y sin parámetro de ruta: el predio todavía no
  // existe (#290).
  //
  // **El alta crea el predio en el mismo acto si no existe.** No es una
  // comodidad: `ficha_catastral.predio_id` es NOT NULL, así que sin el predio
  // no hay ficha; y hacerlo en dos peticiones dejaría predios sin ficha cada
  // vez que la segunda falle.
  ...Object.fromEntries(
    [
      ['ficha_urbana', 'registrar_ficha_urbana', 'urbana', 'urbana individual', 'RF-001'],
      ['ficha_economica', 'registrar_ficha_economica', 'economica', 'económica', 'RF-002'],
      ['ficha_bienes', 'registrar_ficha_bienes', 'bienes-comunes', 'de bienes comunes', 'RF-003'],
      ['ficha_rural', 'registrar_ficha_rural', 'rural', 'rural', 'RF-004'],
    ].map(([id, operationId, tramo, comoSeLlama, requisito]) => [
      id,
      [
        {
          operationId,
          metodo: 'post',
          ruta: `/api/v1/catastro/fichas/${tramo}`,
          titulo: `Alta de ficha ${comoSeLlama}`,
          descripcion:
            `Inscribe la primera versión de la ficha ${comoSeLlama} (${requisito}) de un` +
            ' predio, y da de alta' +
            ' el predio en el mismo acto si todavía no existe. El cuerpo lleva el código de' +
            ' referencia catastral, la ubicación del predio, los datos de la ficha —áreas y' +
            ' categorías, ningún importe—, su titularidad inicial si ya se conoce, y la' +
            ' observación del usuario, obligatoria (RNF-052). Si el predio ya tiene ficha de' +
            ' ese tipo, es 409: lo que toca entonces es actualizarla.',
        },
      ],
    ]),
  ),
  // «Actualización del Catastro» es una sola opción del manual y ya publica el
  // PUT de la ficha urbana como su endpoint. Los otros tres tipos versionan
  // igual y bajo la misma opción —el tipo de ficha no cambia quién puede
  // actualizarla—, pero cada uno se identifica como lo hace su lectura (#290).
  actualizacion_catastro: [
    {
      operationId: 'actualizar_ficha_economica',
      metodo: 'put',
      ruta: '/api/v1/catastro/fichas/economica/{codRefCatastral}/actualizacion',
      titulo: 'Actualización de la ficha económica',
      descripcion:
        'Crea la versión siguiente de la ficha económica y cierra la anterior, que queda entera.' +
        ' Lo que el cuerpo no manda, no cambia: una lista ausente copia la de la versión' +
        ' vigente y una lista presente aunque vacía la reemplaza.',
    },
    {
      operationId: 'actualizar_ficha_bienes',
      metodo: 'put',
      ruta: '/api/v1/catastro/fichas/bienes-comunes/{codEdificacion}/actualizacion',
      titulo: 'Actualización de la ficha de bienes comunes',
      descripcion:
        'Crea la versión siguiente de la ficha de bienes comunes y cierra la anterior. Las áreas' +
        ' comunes y su reparto se copian si el cuerpo no los declara; declararlos los' +
        ' reemplaza.',
    },
    {
      operationId: 'registrar_titular_del_predio',
      metodo: 'post',
      ruta: '/api/v1/catastro/predios/{predioId}/titulares',
      titulo: 'Alta de una cuota de titularidad',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
      },
      descripcion: bloque(`
        Declara **de quién es** el predio (#490, RF-005): el primer titular, o uno más de una
        copropiedad. Hasta aquí la titularidad se podía leer y transferir, pero **el primer titular
        no se podía registrar por HTTP** — sólo se transfiere lo que ya tiene dueño, y lo único que
        daba el primero era la siembra o el bloque de titular del alta de ficha.

        \`condicion\` decide si hace falta \`porcentaje\`: sólo \`PROPIETARIO_UNICO\` lo es por el
        total. Declarar una **copropiedad** es registrar dos o más cuotas.

        **Pasarse del 100 % es 409, y lo dice la base.** La suma de cuotas vigentes la vigila un
        disparador *diferido*, que habla al confirmar; tiene que ser diferido para que una
        transferencia —cerrar una cuota y abrir otra en la misma transacción— sea posible, porque
        entre las dos operaciones el total pasa de 100 a propósito.

        No reabre D-12 ([ADR-0019]): una titularidad que no llega al 100 % se registra igual, y
        determina sólo la porción con titular identificado.

        Exige \`REGISTRO\` sobre \`actualizacion_catastro\` —quien declara de quién es un predio
        está actualizando el catastro, no consultando el padrón— y la observación del usuario
        (RNF-052).
      `),
    },
    {
      operationId: 'inquilinos_del_predio',
      metodo: 'get',
      ruta: '/api/v1/catastro/predios/{predioId}/inquilinos',
      titulo: 'Quién ocupa el predio, a una fecha',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
      },
      parametros: [
        {
          nombre: 'fecha',
          ejemplo: '2026-08-30',
          descripcion:
            'Fecha a la que se resuelve la ocupacion; si falta, hoy. Quien ocupaba el predio en' +
            ' marzo no es necesariamente quien lo ocupa hoy, y una determinacion de arbitrios de' +
            ' marzo se explica con el de marzo (regla 9)',
        },
      ],
      descripcion: bloque(`
        Los ocupantes del predio vigentes a la fecha (#490, #31). El manual los registra para la
        cobranza de arbitrios.

        Existe porque terminar una ocupación exige decir **cuál**, y ninguna lectura publicaba ese
        identificador.
      `),
    },
    {
      operationId: 'registrar_inquilino',
      metodo: 'post',
      ruta: '/api/v1/catastro/predios/{predioId}/inquilinos',
      titulo: 'Alta de inquilino',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
      },
      descripcion: bloque(`
        Registra a quien ocupa el predio sin ser su dueño (#490, #31). El inquilino entra por su
        **código de contribuyente** —para cobrarle hay que poder notificarle, y el domicilio cuelga
        del padrón—, con el documento que sustenta el registro y la observación del usuario.
      `),
    },
    {
      operationId: 'finalizar_inquilino',
      metodo: 'put',
      ruta: '/api/v1/catastro/predios/{predioId}/inquilinos/{inquilinoId}',
      titulo: 'Fin de la ocupación',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
        inquilinoId: 'La ocupación abierta que se termina, por el id que publica su listado',
      },
      descripcion: bloque(`
        Termina la ocupación en una fecha (#490). **No borra** (regla 4, RNF-051): una
        determinación de arbitrios anterior pudo apoyarse en ella, y explicarla exige que la fila
        siga ahí.

        Una ocupación ya cerrada es 404 — no hay tal ocupación abierta que terminar. Exige
        \`ELIMINACION\`, el privilegio que el manual reserva para las bajas lógicas.
      `),
    },
    {
      operationId: 'inscribir_predio',
      metodo: 'post',
      ruta: '/api/v1/catastro/predios',
      titulo: 'Alta de predio, sin ficha',
      descripcion: bloque(`
        Da de alta un predio **sin levantarle ficha** (#489, RF-001), que es el orden natural de
        ventanilla: primero se identifica el predio —su código de referencia catastral, su
        ubicación, su tipo— y después se le levanta la ficha.

        Hasta aquí un predio sólo podía nacer como **efecto secundario** de
        \`POST /catastro/fichas/…\` —que lo crea si no existe— o por la carga cartográfica del
        perfil \`batch\`, así que registrar un lote recién numerado obligaba a inventarle una
        ficha. El predio entra con \`fichado=false\` en \`GET /catastro/predios\`, que es
        justamente la cola de saneamiento.

        Vía, sector y manzana entran por **código** —lo mismo que recibe la corrección del
        predio—, y se resuelven **dentro de la transacción**: una referencia que no existe es 404
        nombrándola, no un predio guardado a medias. Un código ya inscrito es **409**; la unicidad
        la sostiene \`predio_codigo_uq\`.

        Exige \`REGISTRO\` sobre \`actualizacion_catastro\`, y la observación del usuario es
        obligatoria (RNF-052). Ni un importe y ni un titular: el valor sale del cuadro de valores
        unitarios (D-02a) y la titularidad es otro acto.
      `),
    },
    {
      operationId: 'listado_de_predios',
      metodo: 'get',
      ruta: '/api/v1/catastro/predios',
      titulo: 'Padrón de predios del catastro',
      descripcion:
        'Los predios del catastro con su ubicación resuelta a **códigos** —los mismos que la' +
        ' corrección del predio recibe, para que la interfaz no tenga que traducir entre lo que' +
        ' lee y lo que manda—, incluidos **los que nadie ha fichado y los que están dados de' +
        ' baja**. No es la consulta de fichas con otro nombre: aquella lista fichas vigentes a' +
        ' una fecha, así que un predio sale en ella solo si alguien levantó su ficha. Con' +
        ' `fichado=false` da la cola de saneamiento —lo que entra por una carga cartográfica y' +
        ' todavía no tiene ficha—, que ninguna consulta del sistema sabía responder. `fichado`' +
        ' dice si se levantó la ficha alguna vez, no si sigue vigente hoy: eso llevaría fecha' +
        ' (regla 9). Ni un importe y ni un titular: quién es el propietario se resuelve al clic' +
        ' en `/catastro/predios/{predioId}/titulares` (ADR-0015 §2.4). Exige privilegio de' +
        ' LECTURA sobre `actualizacion_catastro`: encontrar el predio es el paso previo de los' +
        ' dos actos de esa pantalla, y pedir el de escribir dejaría sin mirar a quien solo mira.',
      parametros: [
        {
          nombre: 'codRefCatastral',
          ejemplo: '2501010010',
          descripcion:
            'Prefijo del código de referencia catastral, no una igualdad: el código se compone' +
            ' de sector, manzana, lote y unidad, así que preguntar por un sector entero es lo' +
            ' que se hace al sanear una zona',
        },
        {
          nombre: 'codigoDeSector',
          ejemplo: 'SC-1',
          descripcion: 'El sector, por su código; el mismo que la corrección del predio recibe',
        },
        {
          nombre: 'estado',
          ejemplo: 'ACTIVO',
          descripcion:
            'ACTIVO o DADO_DE_BAJA. Si falta, salen los dos: este listado es el del catastro y' +
            ' no el de la emisión, y esconder los retirados sería esconder lo que hay que revisar',
        },
        {
          nombre: 'fichado',
          ejemplo: 'false',
          descripcion:
            '`true` o `false`, y nada más —cualquier otro valor es 422, no un false silencioso—.' +
            ' Si falta, salen los dos',
        },
        {
          nombre: 'titularidad',
          ejemplo: 'SIN_TITULAR',
          descripcion:
            'El censo de saneamiento de titularidad (#690). `SIN_TITULAR` son los predios sin' +
            ' ninguna cuota abierta —en Catacaos, 4 977 de 14 422—; `INCOMPLETA`, los que tienen' +
            ' cuotas y no suman 100 % —otros 304—; `COMPLETA`, los que sí. Importa porque el %' +
            ' de propiedad **pondera la base imponible** del predio (NEG-05 §1): uno cuyas cuotas' +
            ' suman 0,349 % tributa por el 0,349 % de su valor, y ninguna cifra parece mal porque' +
            ' la determinación sale correcta para lo registrado. Se suman las cuotas ABIERTAS,' +
            ' que es lo mismo que suma el disparador que impide pasar de 100. Cualquier otro' +
            ' valor es 422: un filtro ignorado devolvería el padrón entero y se leería como que' +
            ' todo está incompleto',
        },
      ],
    },
    {
      operationId: 'dar_de_baja_predio',
      metodo: 'post',
      ruta: '/api/v1/catastro/predios/{predioId}/baja',
      titulo: 'Baja del predio en el padrón',
      descripcion:
        'Retira el predio del padrón. **No borra nada** (regla 4, RNF-051): sus fichas, su' +
        ' titularidad y las determinaciones que se apoyaron en él quedan como estaban, y el' +
        ' predio deja de admitir fichas nuevas. Exige privilegio de ELIMINACIÓN sobre' +
        ' `actualizacion_catastro` —no el de modificación— porque saca la unidad de toda emisión' +
        ' futura. El cuerpo lleva solo la observación del usuario, obligatoria (RNF-052): el' +
        ' predio y el estado al que se va los dice la ruta. Un predio que ya está dado de baja' +
        ' es 409, no un segundo acto sin efecto.',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
      },
    },
    {
      operationId: 'reactivar_predio',
      metodo: 'post',
      ruta: '/api/v1/catastro/predios/{predioId}/reactivacion',
      titulo: 'Reactivación del predio',
      descripcion:
        'Devuelve al padrón un predio retirado. Existe porque sin ella la baja sería una puerta' +
        ' de un solo sentido: el alta de ficha rechaza a propósito inscribir sobre un predio dado' +
        ' de baja —«reactivarlo es otro acto, con su propia observación»— y ese otro acto no' +
        ' existía. Exige MODIFICACIÓN y no ELIMINACIÓN: restituir no es retirar. El cuerpo lleva' +
        ' solo la observación, obligatoria. Un predio que ya está activo es 409.',
      descripcionesDeRuta: {
        predioId: 'El predio, por el `predioId` que publica cada fila de la consulta de fichas',
      },
    },
    {
      operationId: 'actualizar_ficha_rural',
      metodo: 'put',
      ruta: '/api/v1/catastro/fichas/rural/{codUnidad}/actualizacion',
      titulo: 'Actualización de la ficha rural',
      descripcion:
        'Crea la versión siguiente de la ficha rural y cierra la anterior. Los grupos de tierra' +
        ' van en hectáreas —el arancel rural se publica por hectárea— y se copian si el cuerpo' +
        ' no los declara.',
    },
  ],
  // `fisc_programa` declara «POST /fiscalizacion/programas» —programar— como su
  // unico endpoint, y hasta #431 no habia ninguna lectura: un programa se podia
  // registrar y no se podia volver a encontrar. No lo pagaba solo esa pantalla;
  // lo pagaban las dos actas, que exigen el `programaId` de un programa ya
  // generado y no tenian ninguna fila real de la que sacarlo.
  //
  // Mismo reparto que `certificados` y `costas_procesales`: si el POST
  // devolviera tambien la grilla, abrir la pantalla programaria una
  // fiscalizacion.
  //
  // Declara DOS de los cuatro filtros de la pantalla, y por eso no usa
  // `filtrosDeLaPantalla`: «Tipo» ofrece seis clases donde `TipoDePrograma`
  // tiene dos y «Estado» cuatro donde `EstadoDePrograma` tiene tres. Publicar
  // esos dos seria publicar un filtro que no filtra —o, peor, uno que decide
  // que «PREDIAL MASIVO» es PREDIAL y esconde en silencio los selectivos—.
  fisc_programa: [
    {
      operationId: 'fisc_programas_listado',
      metodo: 'get',
      antes: true,
      parametros: [
        {
          nombre: 'nDePrograma',
          descripcion: 'Filtro «Nº de programa» de la pantalla; es el codigo del programa, exacto',
        },
        {
          nombre: 'ejercicio',
          descripcion:
            'Filtro «Ejercicio» de la pantalla: los programas VIGENTES en ese ejercicio',
        },
      ],
      paginacion: true,
      titulo: 'Programas de fiscalización',
      descripcion:
        'La grilla de programas de fiscalización de la pantalla `fisc_programa`, que declara el' +
        ' POST —programar— como su endpoint y necesita un verbo aparte para listar. Agregada por' +
        ' #431: sin ella un programa se registraba y no se podía volver a encontrar, y las actas' +
        ' predial y vehicular —que exigen el `programaId` de un programa ya generado— no tenían' +
        ' ninguna fila real de la que resolverlo. «Ejercicio» filtra por VIGENCIA y no por el año' +
        ' de inicio: un programa que arranca en diciembre y cierra en marzo es un programa del' +
        ' ejercicio siguiente para quien lo busca. Los otros dos desplegables de la pantalla' +
        ' —«Tipo» y «Estado»— no viajan: nombran clases y situaciones que el sistema no registra.',
    },
    // La muestra sorteada, que es la grilla «Predios seleccionados» de esta
    // pantalla Y la fila de la que `fisc_predial` resuelve sus tres
    // identificadores (#481, AC 2 de #431). Las dos mitades del AC son la misma
    // pieza: el acta no declara ni filtros ni tabla y dibuja sus tres campos de
    // solo lectura, asi que solo se puede abrir desde una fila ya resuelta.
    {
      operationId: 'fisc_programa_muestra',
      metodo: 'get',
      ruta: '/api/v1/fiscalizacion/programas/{id}/muestra',
      parametros: [
        {
          nombre: 'id',
          en: 'path',
          descripcion: 'El programa cuya muestra se lee',
        },
        {
          nombre: 'predio',
          descripcion:
            'Acota la muestra a un predio: es como el acta de inspeccion pide su propia fila',
        },
      ],
      paginacion: true,
      titulo: 'Muestra sorteada del programa',
      descripcion:
        'Los predios que el programa sorteó para inspeccionar, con la condición que la detección' +
        ' concluyó el día del sorteo y las dos superficies que comparó. Es la grilla «Predios' +
        ' seleccionados» de la pantalla y también de donde el acta de inspección resuelve sus tres' +
        ' identificadores —programa, contribuyente y predio—, que su catálogo dibuja de solo' +
        ' lectura. La columna «Estado» se DERIVA de si el predio ya tiene acta en el programa: no' +
        ' es una columna de la fila, porque guardarla dejaría dos verdades sobre lo mismo. Los' +
        ' tres campos del titular —contribuyenteId, codContribuyente y titular— salen NULOS en el' +
        ' predio sin titularidad vigente: es el predio que nadie reclama, el candidato de primer' +
        ' orden, y desde #586 la muestra lo admite en vez de apartarlo. Quien lo visite averigua' +
        ' quién lo ocupa y nombra al contribuyente al levantar el acta.',
    },
    {
      operationId: 'fisc_programa_generar_muestra',
      metodo: 'post',
      ruta: '/api/v1/fiscalizacion/programas/{id}/muestra',
      parametros: [
        {
          nombre: 'id',
          en: 'path',
          descripcion: 'El programa que sortea su muestra',
        },
      ],
      titulo: 'Generar la muestra del programa',
      descripcion:
        'Sortea los predios que el programa va a inspeccionar, aplicando sobre el padrón el' +
        ' ejercicio, el sector y el criterio de riesgo que el propio programa declara. No sortea' +
        ' un predio que otro programa abierto ya se llevó ni uno ya fiscalizado en el ejercicio,' +
        ' así que la muestra depende del orden: el primer programa que se genere se lleva los' +
        ' predios. Responde 409 si el programa ya la sorteó — una muestra es un acto y no se' +
        ' regenera, porque hay actas levantadas sobre ella. La respuesta dice sobre qué padrón se' +
        ' sorteó y no sólo cuántos entraron: detectados, predios, sinTitular —cuántos de los' +
        ' sorteados no tienen titular vigente— y los excluidos POR MOTIVO, de modo que detectados' +
        ' = predios + excluidosPorOtroPrograma + excluidosPorActaDelEjercicio. Un número suelto no' +
        ' distinguiría «otro programa se lo llevó» de «ya se fiscalizó», que se arreglan de' +
        ' maneras distintas (#586).',
    },
  ],
  // `fisc_predial` declara «POST /fiscalizacion/predial/actas» como su endpoint
  // —levantar el acta— y hasta #599 no habia por donde volver a leerla. #546 se
  // nego a publicar la lectura y dejo el motivo escrito: el cuerpo del POST
  // tenia NUEVE campos contra los veintitres controles y las siete filas de
  // contraste que la pantalla del manual dibuja, asi que el listado habria
  // publicado esa misma foto incompleta. Lo que faltaba no era por donde leer,
  // era DONDE GUARDAR, y en concreto el uso hallado.
  //
  // La ruta es una y sirve a las dos familias porque el acta predial y la
  // vehicular comparten tabla, tipo de dominio y recurso; dos listados serian
  // dos copias de la misma consulta. Por eso el acceso lo comparten las dos
  // opciones (`RequiereAcceso.oTambien`, censado en `AccesosCompartidosTest`).
  fisc_predial: [
    {
      operationId: 'fisc_actas_listado',
      metodo: 'get',
      ruta: '/api/v1/fiscalizacion/actas',
      parametros: [
        {
          nombre: 'programa',
          descripcion:
            'Acota a las actas de un programa, por su numero interno; es de donde el embudo saca' +
            ' cuantas se inspeccionaron',
        },
      ],
      paginacion: true,
      titulo: 'Actas de inspección levantadas',
      descripcion:
        'Las actas de inspección de la municipalidad, predial y vehicular en la misma lista: las' +
        ' dos comparten tabla y ciclo de vida, y cuál es cuál lo dice cuál de `predioId` y' +
        ' `vehiculoId` trae valor. Agregada por #599, y no antes a propósito: #546 midió que un' +
        ' listado no habría llenado la pantalla del acta —el cuerpo del POST tenía nueve campos' +
        ' contra los veintitrés que el manual dibuja— y que lo que faltaba era dónde guardar el' +
        ' **uso hallado**, hoy `usoHallado` (V76). **`totalElementos` cuenta todas las actas que' +
        ' el filtro deja pasar, no las de la página**: es de ahí de donde el embudo del programa' +
        ' llena su etapa «Inspeccionados», y sumar `visitado` fila a fila recompondría esa cifra' +
        ' sobre la página que se hubiera pedido. Un solo filtro, y es el único que alguien pide:' +
        ' las dos pantallas del acta no dibujan ninguno —su catálogo no declara ni filtros ni' +
        ' tabla—, así que publicar el predio, el hallazgo o el estado sería inventar promesas que' +
        ' ninguna pantalla hace. Exige LECTURA sobre `fisc_predial` **o** sobre `fisc_vehicular`:' +
        ' un perfil de fiscalización vehicular registraría actas que no podría volver a ver.',
    },
  ],
  // «Resultados y determinaciones» declara «GET /fiscalizacion/resultados» como
  // su endpoint —la grilla—; emitir la liquidación de un acta y reliquidarla
  // (RF-053, #49) necesitan sus propios verbos. Sin ellos la pantalla lista un
  // resultado que nada puede producir.
  fisc_resultados: [
    {
      operationId: 'liquidar_fiscalizacion',
      metodo: 'post',
      ruta: '/api/v1/fiscalizacion/liquidaciones',
      tras: 'fisc_historico',
      titulo: 'Liquidación de un acta de fiscalización',
      descripcion:
        'Emite la liquidación de un acta: el contraste hallado/declarado, una línea por unidad y' +
        ' ejercicio del periodo fiscalizado. Cada línea fija el conjunto de parámetros SELLADO de' +
        ' su ejercicio, de modo que cambiar los parámetros de hoy no altera una liquidación ya' +
        ' emitida. Sin importes: los liquidados y las multas esperan a D-02a (#198). El cuerpo' +
        ' lleva la observación del usuario, obligatoria (RNF-052).',
    },
    {
      operationId: 'reliquidar_fiscalizacion',
      metodo: 'post',
      ruta: '/api/v1/fiscalizacion/liquidaciones/{numero}/reliquidaciones',
      tras: 'fisc_historico',
      titulo: 'Reliquidación',
      descripcion:
        'Corrige una liquidación emitiendo OTRA versión que la referencia. La anterior no cambia' +
        ' ni una columna, las dos quedan, y la respuesta explica qué cambió entre ellas. Las' +
        ' líneas heredan el conjunto sellado de la versión anterior: una reliquidación corrige el' +
        ' contraste, no el marco normativo.',
    },
    // Y transferir el resultado al padrón (#52, RF-054), que es la acción de la
    // misma pantalla y la frontera delicada del sistema: el único camino por el
    // que un dato de fiscalización pasa a ser el dato oficial.
    {
      operationId: 'transferir_a_rentas',
      metodo: 'post',
      ruta: '/api/v1/fiscalizacion/transferencias',
      tras: 'fisc_historico',
      titulo: 'Transferencia a rentas del resultado fiscalizado',
      descripcion:
        'Inscribe lo hallado en el padrón como versión NUEVA de la ficha catastral —con origen' +
        ' FISCALIZACION, el documento que la sustenta y la observación del usuario—, asienta los' +
        ' cargos de la diferencia en la cuenta corriente y emite la resolución de determinación.' +
        ' Los tres pasos van en una transacción: ficha nueva, asientos y resolución, o nada. La' +
        ' versión anterior queda intacta, así que el padrón anterior se reconstruye pidiendo la' +
        ' ficha vigente a una fecha anterior. Sin sustento documental no se transfiere, y' +
        ' transferir dos veces la misma liquidación se rechaza.',
    },
  ],
  // «Histórico de fiscalización predial» declara su GET; mover la liquidación
  // por sus estados —ABIERTA, EN PROCESO, LIQUIDADA, NOTIFICADA, ANULADA, que
  // son los de su propio desplegable— necesita un verbo aparte (#49, RF-056).
  fisc_historico: [
    {
      operationId: 'estado_de_liquidacion',
      metodo: 'patch',
      ruta: '/api/v1/fiscalizacion/liquidaciones/{numero}/estados',
      titulo: 'Estado de una liquidación de fiscalización',
      descripcion:
        'Mueve la liquidación de estado conservando el historial. No actualiza ninguna fila:' +
        ' agrega un movimiento, y el estado se DERIVA de él. Una liquidación anulada no vuelve:' +
        ' corregirla es reliquidar.',
    },
  ],
  // `licencia_funcionamiento` declara «GET /licencias/funcionamiento» como su
  // endpoint —la grilla—; emitir la licencia necesita su propio verbo (#44,
  // RF-110). No hay PUT ni PATCH: una licencia es un acto administrativo que el
  // titular cuelga en su establecimiento, y no se corrige —`licencia_funcionamiento`
  // ni siquiera admite UPDATE desde V37—. Lo que le pasa son las otras dos
  // opciones, que ya tienen su ruta: `/cancelacion` y `/duplicado`.
  licencia_funcionamiento: [
    {
      operationId: 'emitir_licencia',
      metodo: 'post',
      titulo: 'Emisión de licencia de funcionamiento',
      descripcion:
        'Emite una licencia de funcionamiento con sus giros CIIU y su papel (RF-110). El cuerpo' +
        ' lleva el titular, el establecimiento, los giros con su actividad principal, el' +
        ' número del recibo de caja de tasas del derecho de trámite y la observación del' +
        ' usuario, obligatoria (RNF-052). Sin un recibo válido —de caja de tasas, no anulado,' +
        ' del titular y por el concepto del TUPA que corresponde— no se emite. El número de la' +
        ' licencia lo pone el sistema desde su correlativo: no viene en el cuerpo.',
    },
  ],
  // `anuncios` declara «GET /autorizaciones/anuncios» como su endpoint —la
  // grilla—; registrar la autorización y los tres trámites que la pantalla
  // enumera necesitan sus propios verbos (#51, RF-114). No hay PUT ni PATCH:
  // `anuncio` no admite UPDATE desde V45, y renovar, cesar y retirar son ACTOS
  // que producen una fila nueva de `anuncio_movimiento`, no ediciones del
  // formulario.
  anuncios: [
    {
      operationId: 'registrar_anuncio',
      metodo: 'post',
      titulo: 'Registro de autorización de anuncio',
      descripcion:
        'Registra una autorización de anuncio y GENERA SU DEUDA por la tasa (RF-114). El cuerpo' +
        ' lleva el titular, el establecimiento asociado —opcional—, la clase y el tipo del' +
        ' elemento, sus medidas, la ubicación, la vigencia y la observación del usuario,' +
        ' obligatoria (RNF-052). El número de la autorización lo pone el sistema desde su' +
        ' correlativo y la tasa sale del conjunto sellado: ninguno de los dos viene en el cuerpo.' +
        ' La cabecera `Idempotency-Key` se lee: reenviar el mismo registro devuelve 200 con la' +
        ' autorización de la primera vez y no genera un segundo cargo.',
    },
    {
      operationId: 'renovar_anuncio',
      metodo: 'post',
      ruta: '/api/v1/autorizaciones/anuncios/{id}/renovacion',
      titulo: 'Renovación de autorización de anuncio',
      descripcion:
        'Prorroga la autorización por otro ejercicio y devenga otra vez la tasa (RF-114). Un' +
        ' anuncio cesado o retirado no se renueva, y una misma autorización no devenga dos veces' +
        ' el mismo ejercicio. El `id` de la ruta es el número impreso de la autorización.',
    },
    {
      operationId: 'cesar_anuncio',
      metodo: 'post',
      ruta: '/api/v1/autorizaciones/anuncios/{id}/cese',
      titulo: 'Cese de autorización de anuncio',
      descripcion:
        'Deja sin efecto la autorización, con su motivo (RF-114). Detiene la deuda futura —un' +
        ' anuncio cesado no se renueva— y NO toca la ya devengada: no borra ni reversa ningún' +
        ' cargo (regla 4, RNF-051). El cuerpo lleva la fecha, el motivo y la observación del' +
        ' usuario, obligatoria (RNF-052).',
    },
    {
      operationId: 'retirar_anuncio',
      metodo: 'post',
      ruta: '/api/v1/autorizaciones/anuncios/{id}/retiro',
      titulo: 'Retiro del elemento publicitario',
      descripcion:
        'Registra que el elemento se retiró de la calle, comprobado en campo (RF-114). Va después' +
        ' del cese: primero la autorización deja de regir y después el soporte desaparece. Al' +
        ' revés, el padrón diría que se desmontó un anuncio que sigue autorizado.',
    },
  ],
  // `certificados` declara «POST /api/v1/licencias/certificados» como su
  // endpoint —la emisión—; su grilla «Certificados emitidos» y su acción
  // «Imprimir certificado» necesitan verbo propio (#54, RF-115, RF-132).
  //
  // Mismo reparto que `costas_procesales` (#42): hacer que el POST devolviera
  // también la grilla convertiría una consulta en una escritura, y una pantalla
  // que lista al abrirse consumiría un correlativo cada vez.
  //
  // No hay PUT ni PATCH: `certificado` no admite UPDATE desde V51. Uno
  // equivocado se sustituye emitiendo otro, y los dos quedan.
  certificados: [
    {
      operationId: 'certificados_listado',
      metodo: 'get',
      antes: true,
      filtrosDeLaPantalla: true,
      parametros: [
        {
          nombre: 'solicitante',
          descripcion:
            'Filtro por nombre del solicitante; se resuelve contra el padrón por aproximación',
        },
      ],
      paginacion: true,
      titulo: 'Certificados emitidos',
      descripcion:
        'La grilla «Certificados emitidos» de la pantalla `certificados`, que declara el POST' +
        ' —la emisión— como su endpoint y necesita un verbo aparte para listar. Hacer que el POST' +
        ' devolviera también la grilla convertiría una consulta en una escritura, y una pantalla' +
        ' que lista al abrirse consumiría un correlativo cada vez. El estado de cada fila' +
        ' —VIGENTE o CADUCADO— se deriva a la fecha de hoy y viaja con ella (RNF-075).',
    },
    {
      operationId: 'imprimir_certificado',
      metodo: 'post',
      ruta: '/api/v1/licencias/certificados/{numero}/impresion',
      descripcionesDeRuta: { numero: 'El número del certificado, tal como está impreso' },
      titulo: 'Impresión de un certificado emitido',
      descripcion:
        'Vuelve a sacar un certificado ya emitido, con su número original y en el formato que se' +
        ' pida —PDF, hoja de cálculo o texto enriquecido (RF-132)—. El contenido sale de los' +
        ' datos guardados el día de la emisión, no de lo que hoy digan el padrón o el TUPA, y el' +
        ' backend comprueba el SHA-256 antes de entregarlo: si dibujar esos datos ya no da los' +
        ' mismos bytes, la reimpresión falla en lugar de entregar un papel distinto al original' +
        ' con el mismo número. Escribe —cuenta la reimpresión y deja su traza—, así que el cuerpo' +
        ' lleva la observación del usuario, obligatoria (RNF-052). Es la acción «Imprimir' +
        ' certificado» de la pantalla.',
    },
  ],
  // `caja_tributaria` declara «POST /api/v1/tesoreria/caja/cobranza» como su
  // unico endpoint, y ese cuerpo EXIGE el codigo de la caja. Lo mismo el de
  // `caja_tasas` y el de `cierre_caja`, y como parametro de consulta el de
  // `avance_recaudacion` y el del listado de recibos de #548: cinco opciones
  // del catalogo que piden el codigo de una ventanilla antes de poder pedir
  // nada, y ninguna lectura que lo publicara. Medido contra el backend local el
  // 2026-09-01, `GET /api/v1/tesoreria/cajas` contestaba 404 —no estaba sin
  // implementar: no estaba en el contrato—, asi que la interfaz ponia una caja
  // de texto y quien atiende tenia que saberse el codigo de memoria.
  //
  // Cuelga de `caja_tributaria` porque es la opcion cuyo acceso la guarda; las
  // otras cuatro llegan por `oTambien` (#548), censadas en
  // AccesosCompartidosTest.
  //
  // No es la situacion de `GET /tesoreria/tasas`, que #430 midio y decidio no
  // publicar: alli nada en produccion escribe la tabla y sus cifras son D-02b.
  // Aqui `cargar-cajas.sh` es el paso 4 de la siembra desde #460 y la tabla
  // tiene filas en cuanto una municipalidad se implanta.
  caja_tributaria: [
    {
      operationId: 'cajas_listado',
      metodo: 'get',
      antes: true,
      ruta: '/api/v1/tesoreria/cajas',
      paginacion: true,
      titulo: 'Ventanillas de la municipalidad',
      descripcion:
        'El catálogo de cajas: código, rótulo, el área a la que se imputa lo que recaudan y si' +
        ' siguen abiertas. Es lo que llena el desplegable «Caja» de las cinco pantallas de' +
        ' Tesorería que hoy piden ese código tecleado —las dos ventanillas de cobro, el cierre y' +
        ' arqueo, el avance de recaudación y el filtro del listado de recibos—. Sin filtros: una' +
        ' municipalidad tiene cuatro ventanillas, no cuarenta mil, y quien ya sabe el código no' +
        ' necesita el catálogo. **Salen también las dadas de baja**, con `activa: false`, porque' +
        ' sus recibos siguen existiendo (RNF-051) y el filtro «Caja» del duplicado tiene que' +
        ' poder nombrarlas; devolver sólo las abiertas recortaría la lista en silencio. El área' +
        ' viaja legible —código y nombre— y es nula en la caja tributaria general, que no cuelga' +
        ' de ninguna. Una municipalidad recién implantada y todavía sin ventanillas devuelve una' +
        ' página vacía con `totalElementos: 0`, no un 404.',
    },
  ],
  // `duplicado_recibo` declara «GET /api/v1/tesoreria/recibos/{nro}/duplicado»
  // como su unico endpoint, y esa ruta EXIGE el numero impreso. Su pantalla, en
  // cambio, dibuja una grilla —«Recibos localizados»— con filtros por
  // contribuyente, fecha y caja: la busqueda de quien PERDIO el papel, que es
  // exactamente la persona que viene a pedir un duplicado. Hasta #548 esa
  // grilla no tenia con que llenarse.
  //
  // No declara `nroDeRecibo`, y es deliberado: el numero exacto ya resuelve por
  // la otra ruta. Este listado existe para quien no lo tiene.
  //
  // Mismo reparto que `costas_procesales` y `fisc_programa`: verbo aparte y no
  // el mismo, porque la operacion de la pantalla ESCRIBE cuando lleva `formato`
  // —cada reimpresion queda registrada— y abrir la pantalla no puede reimprimir.
  duplicado_recibo: [
    {
      operationId: 'recibos_listado',
      metodo: 'get',
      antes: true,
      ruta: '/api/v1/tesoreria/recibos',
      parametros: [
        {
          nombre: 'codContribuyente',
          descripcion: 'Filtro «Cod. Contribuyente» de la pantalla; el codigo exacto del padron',
        },
        {
          nombre: 'caja',
          ejemplo: 'C-01',
          descripcion: 'Filtro «Caja» de la pantalla: el codigo de la ventanilla que emitio',
        },
        {
          nombre: 'cajero',
          ejemplo: 'jperez',
          descripcion:
            'La cuenta de quien cobro. Del backend: la pantalla no lo dibuja, y sin el no se' +
            ' puede reconstruir lo que emitio un turno',
        },
        {
          nombre: 'desde',
          descripcion: 'Primer dia del rango de emision, inclusive. La pantalla dibuja una sola' +
            ' «Fecha»; el rango es del backend, porque quien perdio el recibo recuerda la semana' +
            ' y no el dia',
        },
        {
          nombre: 'hasta',
          descripcion: 'Ultimo dia del rango, inclusive',
        },
        {
          nombre: 'estado',
          ejemplo: 'EMITIDO',
          esquema: '{ type: string, enum: [EMITIDO, ANULADO] }',
          descripcion:
            'Columna «Estado» de la grilla, usada como filtro. Se DERIVA del movimiento de' +
            ' anulacion (V30): el recibo no guarda ninguna columna de estado, porque no se' +
            ' actualiza. Un valor fuera de las dos palabras se rechaza con 422 en vez de leerse' +
            ' como «todos»',
        },
      ],
      paginacion: true,
      titulo: 'Recibos emitidos',
      descripcion:
        'La grilla «Recibos localizados» de la pantalla de duplicado de recibo: los recibos que' +
        ' cuadran con los filtros, con su número impreso, el instante de emisión, a quién se le' +
        ' cobró, el importe **con la fecha a la que estaba actualizado** (regla 9, RNF-075), el' +
        ' medio de pago, cuántos duplicados se han sacado y si sigue en pie. Agregada por #548:' +
        ' hasta entonces un recibo sólo se podía pedir por su número impreso, así que quien' +
        ' perdía el papel no tenía forma de encontrarlo. No trae el desglose —una página de' +
        ' veinte filas no puede costar veinte lecturas del detalle: para eso está la otra ruta,' +
        ' que ya recibe el número—. Un contribuyente sin recibos devuelve una página vacía con' +
        ' `totalElementos: 0`, no un 404.',
    },
  ],
  // `fraccionamiento_coactivo` declara «POST /coactiva/convenios» —fraccionar—
  // como su unico endpoint, y su cuerpo pide `obligaciones[]` con tributo,
  // ejercicio y predioId/vehiculoId POR FILA. Ninguna lectura del modulo tenia
  // esa granularidad: `coactiva_consulta_deudas` es por expediente y ni siquiera
  // desglosa insoluto de interes. Sin esto, la columna de seleccion de la
  // pantalla no tiene sobre que actuar -exactamente como estaba `baja_deuda`
  // antes de #332, que saca sus filas de `consulta_deuda`-.
  //
  // No cuelga de la ruta de la pantalla sino del expediente, que es de quien es
  // la deuda; y es un GET aparte y no el mismo POST por lo de siempre: si
  // fraccionar devolviera tambien la grilla, abrir la pantalla fraccionaria.
  fraccionamiento_coactivo: [
    {
      operationId: 'coactiva_deuda_del_expediente',
      metodo: 'get',
      antes: true,
      ruta: '/api/v1/coactiva/expedientes/{numero}/deuda',
      descripcionesDeRuta: { numero: 'El numero del expediente coactivo, tal como esta impreso' },
      parametros: [
        {
          nombre: 'fechaDeCalculo',
          descripcion:
            'A que dia se actualizan TODAS las cifras. Sin el, hoy; y viaja de vuelta en la' +
            ' respuesta (regla 9)',
        },
      ],
      titulo: 'Deuda del expediente coactivo, obligación por obligación',
      descripcion:
        'La deuda de un expediente **desglosada por obligación**: una fila por tributo, ejercicio' +
        ' y unidad, con su insoluto, reajuste, interés y gastos. Es la lectura de la que' +
        ' «Fraccionamiento coactivo» saca las filas que se acogen —su cuerpo las pide una a una, y' +
        ' una suma no las tiene—. Sale de la MISMA composición y a la misma fecha que la deuda del' +
        ' expediente que imprime la REC-2, así que la grilla y el papel no pueden discrepar. Las' +
        ' costas del procedimiento viajan marcadas (`esCosta`) y no escondidas: se cobran igual,' +
        ' pero no se acogen como una cuota más. Los tres totales vienen calculados del servidor' +
        ' (RNF-083).',
    },
  ],
  // `costas_procesales` declara «POST /coactiva/liquidaciones-costas» como su
  // endpoint —liquidar—; su grilla «Liquidaciones encontradas» necesita verbo
  // propio (#42). Mismo reparto que `certificados`: si el POST devolviera
  // tambien la grilla, abrir la pantalla liquidaria costas.
  costas_procesales: [
    {
      operationId: 'costas_procesales_listado',
      metodo: 'get',
      antes: true,
      filtrosDeLaPantalla: true,
      paginacion: true,
      titulo: 'Liquidaciones de costas procesales encontradas',
      descripcion:
        'Grilla «Liquidaciones encontradas» de la pantalla de costas procesales, con el' +
        ' pendiente y el estado de cada liquidacion a la fecha de consulta. Agregada por #42:' +
        ' la opcion declaraba solo su accion principal y la pantalla tiene grilla propia con' +
        ' estos mismos filtros.',
    },
  ],
  // `fue_edificacion` declara «GET /api/v1/licencias/edificacion» como su
  // endpoint —la grilla del Formulario Único de Edificaciones—; el FUE se
  // presenta, se completa POR PARTES y sólo entonces se emite (#48, RF-113), y
  // cada uno de esos tres actos necesita su propio verbo.
  //
  // No hay PUT ni PATCH, y no es una omisión: las secciones del FUE se
  // VERSIONAN —cada POST guarda la siguiente y la anterior queda entera— y la
  // cabecera no admite UPDATE desde V43. Corregir un dato del expediente sin
  // dejar el anterior borraría justo lo que explica una observación del
  // evaluador.
  //
  // La AMPLIACIÓN no tiene verbo propio: es un FUE nuevo que nombra la licencia
  // original, así que entra por el mismo POST de presentación (AC 3).
  fue_edificacion: [
    {
      operationId: 'presentar_fue',
      metodo: 'post',
      titulo: 'Presentación del FUE',
      descripcion:
        'Da de alta el expediente del Formulario Único de Edificaciones (RF-113). El cuerpo lleva' +
        ' el expediente, el solicitante, el tipo de trámite, la obra, la modalidad de aprobación,' +
        ' el representante legal —opcional— y la observación del usuario, obligatoria (RNF-052).' +
        ' Presentar NO otorga nada: no numera ninguna licencia ni comprueba ningún derecho de' +
        ' trámite. Una ampliación o una revalidación nombran aquí la licencia original, y la' +
        ' referencian sin sustituirla.',
    },
    {
      operationId: 'completar_seccion_fue',
      metodo: 'post',
      ruta: '/api/v1/licencias/edificacion/{expediente}/secciones',
      descripcionesDeRuta: { expediente: 'Numero de expediente del FUE' },
      titulo: 'Sección del FUE completada',
      descripcion:
        'Completa una sección del FUE: TERRENO, PROYECTO, VALORIZACION, PROFESIONALES o' +
        ' DOCUMENTOS. Se pueden completar en cualquier orden y en visitas distintas; completar una' +
        ' que ya estaba guarda la versión siguiente y deja la anterior entera. La valorización va' +
        ' por pisos y estructuras y NO admite importes: el valor por metro cuadrado sale del cuadro' +
        ' de valores unitarios de edificación.',
    },
    {
      operationId: 'emitir_licencia_edificacion',
      metodo: 'post',
      ruta: '/api/v1/licencias/edificacion/{expediente}/licencia',
      descripcionesDeRuta: { expediente: 'Numero de expediente del FUE' },
      titulo: 'Emisión de licencia de edificación',
      descripcion:
        'Otorga la licencia de edificación del expediente (RF-113). Sólo se emite cuando están las' +
        ' cinco secciones obligatorias, y el error dice cuáles faltan. Sin un recibo válido de caja' +
        ' de tasas —del titular, no anulado y por el concepto del TUPA que corresponde— no se' +
        ' emite. El número de la licencia lo pone el sistema desde su correlativo; la vigencia' +
        ' entra como dato del acto, porque el plazo es una cifra normativa y no se compila.',
    },
    {
      operationId: 'revalidar_licencia_edificacion',
      metodo: 'post',
      ruta: '/api/v1/licencias/edificacion/{expediente}/revalidacion',
      descripcionesDeRuta: { expediente: 'Numero de expediente de la revalidacion' },
      titulo: 'Revalidación de licencia de edificación',
      descripcion:
        'Prorroga el plazo de la licencia que el expediente de revalidación nombra. NO sustituye la' +
        ' vigencia original: agrega el tramo siguiente, y la respuesta devuelve los dos con el acto' +
        ' que concedió cada uno. Se cobra en caja de tasas antes, con su propio concepto del TUPA.',
    },
  ],
  // `prescripcion` declara «POST /coactiva/prescripcion» —el acto que la declara—;
  // #674 le añade la LECTURA por la que quien audita ve qué deuda quedó sin acción
  // de cobro. Misma ruta, otro verbo, como `permisos_de_grupo` sobre la suya.
  prescripcion: [
    {
      operationId: 'prescripciones_declaradas',
      metodo: 'get',
      titulo: 'Prescripciones declaradas',
      paginacion: true,
      parametros: [
        {
          nombre: 'codContribuyente',
          descripcion:
            'Código del contribuyente. Un código que no está en el padrón es 404 nombrándolo,' +
            ' no una relación vacía.',
        },
        { nombre: 'tributo', descripcion: 'Tributo sobre el que se declaró' },
        {
          nombre: 'ejercicio',
          descripcion:
            'Acota a las declaraciones que RESOLVIERON ese ejercicio, hayan prescrito o no:' +
            ' cuáles prescribieron lo dice `ejerciciosPrescritos` de cada fila.',
        },
        {
          nombre: 'resultado',
          descripcion: 'PROCEDE, PROCEDE_EN_PARTE o NO_PROCEDE; otro valor es 422',
        },
      ],
      descripcion: literal(`
        La relación de declaraciones de prescripción: **qué deuda quedó sin acción de
        cobro** (#674, RF-094).

        **La deuda sigue ahí, y eso es la decisión, no un defecto.** Lo que prescribe es
        la *acción* para exigir el pago —art. 43 del TUO del Código Tributario—, no la
        obligación: el libro conserva sus asientos, la deuda sigue siendo cartera
        pendiente y sigue contando como emisión del ejercicio en el panel de recaudación.
        Deja de contar cuando la administración la **da de baja** con RF-044, cuya
        primera causal se llama «PRESCRIPCIÓN DECLARADA» —participio: presupone esta
        declaración, que es su sustento y no la baja misma—.

        Por eso existe esta lectura: si la deuda inexigible no se puede *ver* en ninguna
        parte, quien audita la cartera no sabe qué parte de ella ya no se puede exigir, y
        quien registra la baja no tiene dónde encontrar el acto que la sustenta.

        **Ninguna cifra de dinero**, y no por descuido: la prescripción no extingue un
        importe. \`ejerciciosPrescritos\` es la lista de los que de verdad prescribieron
        —una solicitud pide un rango y se resuelve año por año, así que «procede en
        parte» es el caso corriente— y con el contribuyente y el tributo identifica la
        obligación alcanzada.
      `),
    },
  ],
  // `ciiu` declara «GET /licencias/ciiu» como su endpoint —el catálogo—; RF-112
  // exige que sea extensible por el usuario, y extenderlo necesita su verbo.
  ciiu: [
    {
      operationId: 'registrar_ciiu',
      metodo: 'post',
      titulo: 'Alta de giro CIIU',
      descripcion:
        'Agrega un giro al catálogo CIIU de la municipalidad (RF-112). El cuerpo lleva el' +
        ' código, la descripción, la sección, el nivel de riesgo de la ITSE si ya está' +
        ' clasificado, la zonificación compatible y la observación del usuario, obligatoria' +
        ' (RNF-052). El giro nace activo y marcado como extensión local: la clasificación' +
        ' oficial se carga por otro camino.',
    },
  ],
};

/**
 * Ninguna tabla de este archivo declara dos veces la misma clave.
 *
 * <p>Nace de un defecto que se cometio escribiendo #488: las ocho operaciones nuevas
 * del padron se declararon en un `contribuyentes: [...]` propio, y ya habia otro mas
 * abajo con `titulares_del_predio` (#366). Un objeto literal de JavaScript se queda
 * **con el ultimo**, sin aviso: el generador corrio, dijo «OpenAPI generado» y el YAML
 * no cambio ni una linea. El sintoma de «lo declare y no salio» es exactamente el mismo
 * que el de «no lo declare», y `--comprobar` habria seguido en verde porque el contrato
 * y el generador coincidian —en no tenerlas—.
 *
 * No se puede comprobar sobre el objeto ya construido: para entonces la clave repetida
 * ya se perdio. Se lee el codigo fuente, que es donde todavia estan las dos.
 *
 * Las claves de estas tablas van a dos espacios y el contenido de las descripciones a
 * ocho o mas, asi que la sangria las distingue sin analizar JavaScript.
 */
function clavesRepetidas(fuente, tabla) {
  const inicio = fuente.indexOf(`const ${tabla} = {`);
  if (inicio < 0) throw new Error(`No existe la tabla ${tabla}`);
  const fin = fuente.indexOf('\n};\n', inicio);
  const vistas = new Set();
  const repetidas = [];
  for (const linea of fuente.slice(inicio, fin).split('\n')) {
    const clave = /^ {2}([A-Za-z_][\w]*):/.exec(linea);
    if (!clave) continue;
    if (vistas.has(clave[1])) repetidas.push(clave[1]);
    vistas.add(clave[1]);
  }
  return repetidas;
}

{
  const fuente = readFileSync(fileURLToPath(import.meta.url), 'utf8');
  const tablas = [
    'SUPRIMIDOS',
    'DEL_BACKEND',
    'DESCRIPCIONES',
    'VOCABULARIOS',
    'OPERACIONES_ADICIONALES',
  ];
  const repetidas = tablas.flatMap((tabla) =>
    clavesRepetidas(fuente, tabla).map((clave) => `${tabla}.${clave}`),
  );
  if (repetidas.length) {
    console.error(
      `Clave declarada dos veces, y la segunda se come a la primera sin avisar: ${repetidas.join(
        ', ',
      )}`,
    );
    process.exit(1);
  }
}

/**
 * Respuestas que el contrato describe y este generador no sabria inventar.
 *
 * Lo normal es una respuesta por omision —200 o 201 con un objeto— mas el 403 y
 * el 422 que toda operacion comparte. Aqui estan las tres que no lo son, y las
 * tres lo son por un motivo que se lee en la respuesta misma:
 *
 * - `consulta_fichas` **redirige** cuando le llega un filtro que esta ruta no
 *   resuelve. El 307 es la diferencia entre mandar al cliente donde si se
 *   resuelve y devolverle un listado sin filtrar, que es el resultado plausible
 *   y equivocado (ADR-0015 §2, #344).
 * - `imprimir_certificado` devuelve **el papel**, no un JSON (RF-132).
 * - `permisos_de_la_sesion` es la unica cuya forma la interfaz consume entera:
 *   el guardia dibuja el menu con ella, asi que el contrato la describe hasta el
 *   enum de privilegios (ADR-0013). Y no tiene 422: no recibe cuerpo ni filtros,
 *   de modo que no hay regla de negocio que pueda incumplir.
 */
/**
 * Que operaciones pueden contestar `404`, derivado del codigo (#732).
 *
 * Lo produce `RespuestasDeLaApiTest` leyendo cada controlador —la unica forma de
 * contestar 404 es `ProblemaDeNegocio` con `CodigoDeError.NO_ENCONTRADO`— y se
 * regenera con `-Dsgtm.respuestas.regenerar=true`. **No se escribe a mano**: medio
 * centenar de entradas manuales envejecen solas, que es el defecto que #312 midio.
 *
 * La clave es «VERBO /ruta», la misma que usa `formas-de-la-api.json`.
 */
const RESPUESTAS_DERIVADAS = JSON.parse(
  readFileSync(new URL('./respuestas-de-la-api.json', import.meta.url), 'utf8'),
);

/**
 * Lo que se dice de ese 404 cuando la frase de siempre no basta.
 *
 * Solo las tres lecturas de cuadro de catastro: ahi el 404 no es «esa fila no existe»
 * sino «el ejercicio no tiene conjunto sellado», y su cuerpo puede traer
 * `parametroQueFalta` (#723, #604). Que la lista sea corta es la señal de que el resto
 * dice lo mismo, no de que nadie se haya ocupado.
 */
const DESCRIPCION_DEL_404 = {
  aranceles:
    'El ejercicio no tiene conjunto de parametros sellado, o el cuadro no esta' +
    ' publicado en el. El cuerpo trae `parametroQueFalta` con la llave, que es lo' +
    ' unico que distingue «no hay tabla» de «no hay ejercicio» (#723).',
  depreciacion:
    'El ejercicio no tiene conjunto de parametros sellado, o el cuadro no esta' +
    ' publicado en el. El cuerpo trae `parametroQueFalta` con la llave (#723).',
  valores_unitarios:
    'El ejercicio no tiene conjunto de parametros sellado, o el cuadro no esta' +
    ' publicado en el. El cuerpo trae `parametroQueFalta` con la llave (#723).',
};

/** La frase para el resto: el recurso que la ruta o el filtro identifica no existe. */
const DESCRIPCION_DEL_404_POR_OMISION =
  'Lo que la peticion identifica no existe en esta municipalidad. **No es un fallo del' +
  ' servidor y reintentar no lo cambia**: el cuerpo es `problem+json` con `codigo:' +
  ' NO_ENCONTRADO` y dice que se buscaba.';

const RESPUESTAS = {
  // El 403 del ciudadano no es el de siempre: su token no lleva municipalidad —no
  // pertenece a ninguna— y lo que puede faltarle es el documento acreditado. Y no
  // tiene 422: no recibe cuerpo ni filtros, asi que no hay regla de negocio que
  // pueda incumplir (mismo caso que `permisos_de_la_sesion`).
  portal_mi_situacion: {
    principal: {
      codigo: '200',
      descripcion: 'La situación del ciudadano, municipalidad por municipalidad',
    },
    respuesta403: 'SinDocumentoAcreditado',
    sinValidacion: true,
  },
  consulta_fichas: {
    extra: [
      {
        codigo: '307',
        descripcion:
          'La petición trae `conciliadaConRentas`: se redirige a' +
          ' `/catastro/fichas/conciliacion` conservando la consulta entera. No se ignora el' +
          ' filtro y no se responde sin él (ADR-0015 §2).',
      },
    ],
  },
  imprimir_certificado: {
    principal: {
      codigo: '200',
      tipoDeContenido: 'application/octet-stream',
      esquema: '{ type: string, format: binary }',
    },
  },
  // Sin 422 por lo mismo que `permisos_de_la_sesion`: no recibe cuerpo ni
  // filtros, asi que no hay regla de negocio que pueda incumplir. Y la forma se
  // describe entera porque la interfaz la consume entera: de estos tres campos
  // salen el rotulo de la cabecera, el membrete de las hojas imprimibles y el
  // ubigeo que el alta de predio prefijaba compilado (#555).
  municipalidad_de_la_sesion: {
    principal: {
      codigo: '200',
      descripcion: 'La municipalidad a la que pertenece la sesión',
      esquema: sangrado(`
        type: object
        required: [id, ubigeo, nombre, tipo]
        properties:
          id: { type: integer, format: int64 }
          ubigeo: { type: string, minLength: 6, maxLength: 6 }
          nombre: { type: string }
          tipo: { type: string, enum: [DISTRITAL, PROVINCIAL] }
      `),
      ejemplo: sangrado(`
        id: 1
        ubigeo: "200105"
        nombre: "Municipalidad Distrital de Catacaos"
        tipo: "DISTRITAL"
      `),
    },
    sinValidacion: true,
  },
  permisos_de_la_sesion: {
    principal: {
      codigo: '200',
      descripcion: 'La matriz de permisos efectivos del usuario en curso',
      esquema: sangrado(`
        type: object
        additionalProperties:
          type: array
          items:
            type: string
            enum: [ejecucion, lectura, registro, modificacion, eliminacion, impresion, especial]
      `),
      ejemplo: sangrado(`
        modulos: [lectura, registro, modificacion, eliminacion, impresion, ejecucion, especial]
        contribuyentes: [lectura]
      `),
    },
    sinValidacion: true,
  },
};

/** Un bloque YAML escrito con su sangria relativa, sin la del codigo que lo rodea. */
function sangrado(texto) {
  const lineas = texto
    .replace(/^\n/, '')
    .replace(/\n[ ]*$/, '')
    .split('\n');
  const margen = Math.min(
    ...lineas.filter((linea) => linea.trim()).map((linea) => linea.match(/^ */)[0].length),
  );
  return lineas.map((linea) => linea.slice(margen));
}

/* ── Recoger las operaciones ──────────────────────────────────────────── */

/**
 * Las adicionales que no se escriben junto a su pantalla, sino tras otra.
 *
 * Se recogen antes del recorrido porque la pantalla que esperan puede venir
 * despues en el menu.
 */
const ESPERAN = new Map();
for (const grupo of NAV) {
  for (const [id, etiqueta] of grupo.items) {
    for (const extra of OPERACIONES_ADICIONALES[id] ?? []) {
      if (!extra.tras) continue;
      // `tras` nombra una PANTALLA, no otra operacion adicional. Sin esta guarda,
      // nombrar cualquier otra cosa deja la operacion FUERA del contrato **en
      // silencio**: `ESPERAN` la guarda bajo una clave que ningun bucle visita, y
      // el generador informa un total menor sin decir que perdio nada. Encontrado
      // escribiendo #481, cuya segunda adicional nombraba a la primera.
      if (!PANTALLAS[extra.tras]) {
        throw new Error(
          `OPERACIONES_ADICIONALES['${id}'].${extra.operationId} declara «tras: ${extra.tras}»,` +
            ' que no es ninguna de las 134 pantallas. `tras` ordena una adicional detras de la' +
            ' operacion de OTRA pantalla; para ordenarla dentro de su propio grupo basta el orden' +
            ' del arreglo.',
        );
      }
      if (!ESPERAN.has(extra.tras)) ESPERAN.set(extra.tras, []);
      ESPERAN.get(extra.tras).push({ id, etiqueta, modulo: grupo.label, extra });
    }
  }
}

const operaciones = [];
for (const grupo of NAV) {
  for (const [id, etiqueta] of grupo.items) {
    const pantalla = PANTALLAS[id];
    if (!pantalla || !pantalla.endpoint) continue;

    const [metodo, rutaCompleta] = pantalla.endpoint.split(/\s+/);
    const [ruta, consulta] = rutaCompleta.split('?');

    const parametrosDeRuta = [...ruta.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
    const delBackend = DEL_BACKEND[id] ?? [];

    const deLaPantalla = {
      id,
      operationId: id,
      etiqueta,
      modulo: grupo.label,
      metodo: metodo.toLowerCase(),
      ruta,
      titulo: pantalla.title || etiqueta,
      descripcion: DESCRIPCIONES[id] ?? pantalla.desc ?? '',
      // Parametros de ruta: {codigo}, {numero}, …
      parametrosDeRuta,
      descripcionesDeRuta: {},
      // Parametros de consulta del ejemplo del prototipo, mas los filtros que
      // dibuja la pantalla y —si trae tabla— la paginacion y el orden.
      parametrosDeConsulta: conSuDescripcion(
        id,
        sinLosSuprimidos(
          id,
          reunir(
            parametrosDeRuta,
            intercalar(
              [
                ...delBackend.filter((parametro) => !parametro.tras),
                ...(consulta
                  ? consulta.split('&').map((p) => {
                      const [nombre, ejemplo] = p.split('=');
                      return { nombre, ejemplo: (ejemplo || '').replace(/[{}]/g, '') };
                    })
                  : []),
                ...filtrosDe(pantalla).map((filtro) => ({
                  nombre: filtro.nombre,
                  ejemplo: '',
                  descripcion: `Filtro «${filtro.etiqueta}» de la pantalla`,
                })),
                ...(pantalla.table && paginan(id, metodo) ? PAGINACION : []),
              ],
              delBackend.filter((parametro) => parametro.tras),
            ),
          ),
        ),
      ),
    };

    const propias = (OPERACIONES_ADICIONALES[id] ?? []).filter((extra) => !extra.tras);
    for (const extra of propias.filter((e) => e.antes)) {
      operaciones.push(deLaAdicional({ id, etiqueta, modulo: grupo.label, extra }, pantalla, ruta));
    }
    operaciones.push(deLaPantalla);
    for (const espera of ESPERAN.get(id) ?? []) {
      const suya = PANTALLAS[espera.id];
      const [, rutaSuya] = suya.endpoint.split(/\s+/);
      operaciones.push(deLaAdicional(espera, suya, rutaSuya.split('?')[0]));
    }
    for (const extra of propias.filter((e) => !e.antes)) {
      operaciones.push(deLaAdicional({ id, etiqueta, modulo: grupo.label, extra }, pantalla, ruta));
    }
  }
}

/** Una operacion adicional, con la pantalla de la que sale y la ruta de esa pantalla. */
function deLaAdicional({ id, etiqueta, modulo, extra }, pantalla, ruta) {
  // `ruta` conserva el prefijo /api/v1 igual que la de la pantalla; el
  // serializador lo quita para todas por igual mas abajo.
  //
  // OJO (corte P5A): ese /api/v1 es un MARCADOR INTERNO, no el camino que se sirve. El
  // camino real lo declara `servers.url` —hoy /rentas/api/v1— y se escribe en UN solo
  // sitio. Rebasar estos literales rompe el contrato en silencio: el `replace` de abajo
  // deja de reconocerlos y la ruta sale con el prefijo dentro y duplicado.
  const rutaExtra = extra.ruta ?? ruta;
  const parametrosDeRuta = [...rutaExtra.matchAll(/\{(\w+)\}/g)].map((m) => m[1]);
  return {
    id,
    operationId: extra.operationId,
    etiqueta,
    modulo,
    metodo: extra.metodo,
    ruta: rutaExtra,
    titulo: extra.titulo,
    descripcion: extra.descripcion,
    parametrosDeRuta,
    descripcionesDeRuta: extra.descripcionesDeRuta ?? {},
    parametrosDeConsulta: conSuDescripcion(
      extra.operationId,
      reunir(parametrosDeRuta, [
        ...(extra.filtrosDeLaPantalla
          ? filtrosDe(pantalla).map((filtro) => ({
              nombre: filtro.nombre,
              ejemplo: '',
              descripcion: `Filtro «${filtro.etiqueta}» de la pantalla`,
            }))
          : []),
        ...(extra.parametros ?? []).map((parametro) => ({ ejemplo: '', ...parametro })),
        ...(extra.paginacion ? PAGINACION : []),
      ]),
    ),
  };
}

/** Si la pantalla pagina: las lecturas con tabla, y las que el backend pagina sin serlo. */
function paginan(id, metodo) {
  return metodo.toLowerCase() === 'get' || PAGINAN_SIN_SER_GET.has(id);
}

/**
 * Sin repetidos: un parametro declarado dos veces perderia uno al tiparlo.
 *
 * Y sin los que ya van en la ruta: cuando el filtro se llama igual que el
 * parametro del camino —«Código de edificación» en una pantalla que abre
 * `/bienes-comunes/{codEdificacion}`— no son dos valores, es el mismo, y el que
 * manda es el de la ruta.
 */
function reunir(deLaRuta, parametros) {
  const porNombre = new Map();
  for (const parametro of parametros) {
    if (deLaRuta.includes(parametro.nombre)) continue;
    if (!porNombre.has(parametro.nombre)) porNombre.set(parametro.nombre, parametro);
  }
  return [...porNombre.values()];
}

/** Quita los que `SUPRIMIDOS` retira, y falla si nombra uno que no existe. */
function sinLosSuprimidos(id, parametros) {
  const fuera = SUPRIMIDOS[id];
  if (!fuera) return parametros;
  for (const nombre of fuera) {
    if (!parametros.some((parametro) => parametro.nombre === nombre)) {
      // Un nombre que ya no esta es una supresion que dejo de suprimir algo, y
      // callarlo dejaria la tabla creciendo con entradas muertas.
      throw new Error(`SUPRIMIDOS['${id}'] nombra «${nombre}», que esa operacion no declara`);
    }
  }
  return parametros.filter((parametro) => !fuera.includes(parametro.nombre));
}

/** Los que declaran `tras`, detras del parametro que nombran. */
function intercalar(parametros, intercalados) {
  const lista = [...parametros];
  for (const parametro of intercalados) {
    const donde = lista.findIndex((p) => p.nombre === parametro.tras);
    lista.splice(donde === -1 ? lista.length : donde + 1, 0, parametro);
  }
  return lista;
}

/** El texto por omision de un filtro, salvo donde el servicio hace otra cosa. */
function conSuDescripcion(operationId, parametros) {
  const propias = DESCRIPCIONES_DE_FILTRO[operationId] ?? {};
  const vocabularios = VOCABULARIOS[operationId] ?? {};
  return parametros.map((parametro) => {
    const vocabulario = vocabularios[parametro.nombre];
    if (vocabulario) {
      return {
        ...parametro,
        descripcion: vocabulario.descripcion,
        esquema: `{ type: string, enum: [${vocabulario.valores.join(', ')}] }`,
      };
    }
    return propias[parametro.nombre]
      ? { ...parametro, descripcion: propias[parametro.nombre] }
      : parametro;
  });
}

/* ── Serializar a YAML, sin dependencias ──────────────────────────────── */

const comillas = (texto) => `"${String(texto).replace(/\\/g, '\\\\').replace(/"/g, '\\"')}"`;
const unaLinea = (texto) => String(texto).replace(/\s+/g, ' ').trim();

/**
 * `description:` de una linea o en bloque, segun lo que traiga el texto.
 *
 * Un texto suelto va entre comillas en una sola linea; uno construido con
 * `bloque` o `literal` conserva el corte de linea que se escribio arriba,
 * porque ese corte es el que esta en el contrato.
 */
function escribirDescripcion(lineas, sangria, valor) {
  const margen = ' '.repeat(sangria);
  if (typeof valor === 'string') {
    lineas.push(`${margen}description: ${comillas(unaLinea(valor))}`);
    return;
  }
  lineas.push(`${margen}description: ${valor.marca}`);
  for (const linea of valor.lineas) lineas.push(linea === '' ? '' : `${margen}  ${linea}`);
}

const porRuta = new Map();
for (const op of operaciones) {
  if (!porRuta.has(op.ruta)) porRuta.set(op.ruta, []);
  porRuta.get(op.ruta).push(op);
}

const lineas = [];
lineas.push('# ARCHIVO GENERADO — no editar a mano.');
lineas.push('# Origen: los `endpoint` de design/sgtm-data-{1..5}.js.');
lineas.push('# Regenerar con: node docs/50-api/generar-openapi.mjs');
lineas.push('#');
lineas.push('# Es el contrato PROPUESTO: define verbo, ruta y parametros de las 134');
lineas.push('# operaciones que la interfaz espera. Los esquemas de cuerpo y respuesta se');
lineas.push('# escriben cuando se implementa cada operacion.');
lineas.push('openapi: 3.1.0');
lineas.push('info:');
lineas.push('  title: SGTM — Sistema de Gestion Tributaria Municipal');
lineas.push('  version: 1.0.0-borrador');
lineas.push('  description: |');
lineas.push('    Contrato de la API del SGTM, derivado de las pantallas del prototipo de');
lineas.push('    interfaz. Una operacion por opcion del menu.');
lineas.push('');
lineas.push('    El identificador de municipalidad NO viaja en ninguna ruta, parametro ni');
lineas.push('    cuerpo: sale del claim `municipalidad_id` del token validado (ADR-0005).');
lineas.push('    Una peticion que lo mande por otro camino no lo consigue.');
lineas.push('');
lineas.push('    Los importes son cadenas decimales, nunca numeros de coma flotante');
lineas.push('    (RNF-055), y toda cifra de deuda indica a que fecha esta actualizada');
lineas.push('    (RNF-075).');
lineas.push('servers:');
lineas.push('  - url: /rentas/api/v1');
lineas.push('    description: Camino base');
lineas.push('security:');
lineas.push('  - tokenDeAcceso: []');
lineas.push('tags:');
for (const grupo of NAV) {
  lineas.push(`  - name: ${comillas(grupo.label)}`);
  lineas.push(`    description: ${comillas(`${grupo.items.length} opciones del manual`)}`);
}
lineas.push('paths:');

for (const [ruta, ops] of porRuta) {
  // El servidor ya sirve bajo /api/v1: la ruta del contrato es la relativa.
  const rutaRelativa = ruta.replace(/^\/api\/v1/, '') || '/';
  lineas.push(`  ${comillas(rutaRelativa)}:`);
  for (const op of ops) {
    lineas.push(`    ${op.metodo}:`);
    lineas.push(`      operationId: ${op.operationId}`);
    lineas.push(`      summary: ${comillas(op.titulo)}`);
    if (op.descripcion) escribirDescripcion(lineas, 6, op.descripcion);
    lineas.push(`      tags: [${comillas(op.modulo)}]`);
    if (op.parametrosDeRuta.length || op.parametrosDeConsulta.length) {
      lineas.push('      parameters:');
      for (const nombre of op.parametrosDeRuta) {
        lineas.push(`        - name: ${nombre}`);
        lineas.push('          in: path');
        lineas.push('          required: true');
        const suya = op.descripcionesDeRuta[nombre];
        if (suya) escribirDescripcion(lineas, 10, suya);
        lineas.push('          schema: { type: string }');
      }
      for (const p of op.parametrosDeConsulta) {
        lineas.push(`        - name: ${p.nombre}`);
        lineas.push('          in: query');
        lineas.push('          required: false');
        if (p.descripcion) escribirDescripcion(lineas, 10, p.descripcion);
        lineas.push(`          schema: ${p.esquema ?? '{ type: string }'}`);
        if (p.ejemplo) lineas.push(`          example: ${comillas(p.ejemplo)}`);
      }
    }
    if (op.metodo !== 'get') {
      lineas.push('      requestBody:');
      lineas.push('        required: true');
      lineas.push('        content:');
      lineas.push('          application/json:');
      lineas.push('            schema: { type: object }');
    }
    const respuestas = RESPUESTAS[op.operationId] ?? {};
    const principal = respuestas.principal ?? {};
    lineas.push('      responses:');
    lineas.push(`        ${principal.codigo ?? (op.metodo === 'post' ? '201' : '200')}:`);
    lineas.push(
      principal.descripcion
        ? `          description: ${comillas(principal.descripcion)}`
        : '          description: Operacion realizada',
    );
    lineas.push('          content:');
    lineas.push(`            ${principal.tipoDeContenido ?? 'application/json'}:`);
    if (Array.isArray(principal.esquema)) {
      lineas.push('              schema:');
      for (const linea of principal.esquema) lineas.push(`                ${linea}`);
    } else {
      lineas.push(`              schema: ${principal.esquema ?? '{ type: object }'}`);
    }
    if (principal.ejemplo) {
      lineas.push('              example:');
      for (const linea of principal.ejemplo) lineas.push(`                ${linea}`);
    }
    for (const otra of respuestas.extra ?? []) {
      lineas.push(`        ${comillas(otra.codigo)}:`);
      escribirDescripcion(lineas, 10, otra.descripcion);
    }
    // El 404, derivado del codigo y no escrito aqui (#732).
    const derivadas = RESPUESTAS_DERIVADAS[`${op.metodo.toUpperCase()} ${rutaRelativa}`] ?? [];
    if (derivadas.includes('404')) {
      lineas.push('        "404":');
      escribirDescripcion(
        lineas,
        10,
        DESCRIPCION_DEL_404[op.operationId] ?? DESCRIPCION_DEL_404_POR_OMISION,
      );
      lineas.push('          content:');
      lineas.push('            application/problem+json:');
      lineas.push('              schema: { $ref: "#/components/schemas/Error" }');
    }
    lineas.push('        "403":');
    lineas.push(
      `          $ref: "#/components/responses/${respuestas.respuesta403 ?? 'SinMunicipalidad'}"`,
    );
    if (!respuestas.sinValidacion) {
      lineas.push('        "422":');
      lineas.push('          $ref: "#/components/responses/ErrorDeValidacion"');
    }
  }
}

lineas.push('components:');
lineas.push('  securitySchemes:');
lineas.push('    tokenDeAcceso:');
lineas.push('      type: http');
lineas.push('      scheme: bearer');
lineas.push('      bearerFormat: JWT');
lineas.push('      description: |');
lineas.push('        Token OIDC validado, de **uno de los dos emisores** (ADR-0005, ADR-0020).');
lineas.push('');
lineas.push('        - Realm de **funcionarios**: debe traer el claim `municipalidad_id`; sin');
lineas.push('          el, la peticion recibe 403 y no llega al controlador (RNF-032). Vale');
lineas.push('          para toda la API **salvo** `/portal/**`.');
lineas.push('        - Realm del **ciudadano**: debe traer `numero_documento` y no lleva');
lineas.push('          municipalidad. Vale **solo** para `/portal/**`.');
lineas.push('');
lineas.push('        Son dos cadenas de seguridad con dos decodificadores, cada uno apuntando');
lineas.push('        a un solo emisor: un token de una poblacion no autentica en la otra.');
lineas.push('  schemas:');
lineas.push('    Importe:');
lineas.push('      type: string');
lineas.push('      pattern: "^-?[0-9]+\\\\.[0-9]{2}$"');
lineas.push('      description: |');
lineas.push('        Decimal exacto como cadena. Nunca numero JSON: el `number` de');
lineas.push('        JavaScript es binario de doble precision y pierde centimos (RNF-055).');
lineas.push('      example: "1234.50"');
lineas.push('    Error:');
lineas.push('      type: object');
lineas.push('      required: [codigo, mensaje]');
lineas.push('      properties:');
lineas.push('        codigo: { type: string, example: "DEUDA_YA_CANCELADA" }');
lineas.push('        mensaje: { type: string, description: "En castellano; se muestra al usuario" }');
lineas.push('        detalles: { type: array, items: { type: string } }');
lineas.push('        parametroQueFalta:');
lineas.push('          type: object');
lineas.push('          description: |');
lineas.push('            **Solo** cuando el 422 se debe a una cifra normativa que no esta');
lineas.push('            publicada en el conjunto de parametros sellado (#604, #691). Es el');
lineas.push('            discriminador que separa dos errores que hasta ahora salian con el');
lineas.push('            mismo `codigo` y el mismo `mensaje` y piden acciones distintas:');
lineas.push('');
lineas.push('            - **sin** este miembro: falta un campo de la peticion o un valor no');
lineas.push('              vale. Lo arregla quien atiende, en la misma pantalla.');
lineas.push('            - **con** este miembro: falta publicar una cifra o sellar el');
lineas.push('              conjunto del ejercicio. No lo arregla nadie desde la pantalla');
lineas.push('              (D-02a, D-02b, D-03c).');
lineas.push('');
lineas.push('            La interfaz reacciona a la **presencia** del miembro, nunca al texto');
lineas.push('            del mensaje: el texto se reescribe y el contrato no.');
lineas.push('');
lineas.push('            Vale en **toda** la API y no solo en las rutas de convenios: lo');
lineas.push('            publican los veinticinco puntos de traduccion de los ocho modulos');
lineas.push('            que leen el conjunto sellado, y una guarda del backend impide que');
lineas.push('            nazca uno sin el (#691). Por eso su **ausencia** significa algo en');
lineas.push('            cualquier operacion: ahi lo que falta es un campo de la peticion.');
lineas.push('          required: [ejercicio]');
lineas.push('          properties:');
lineas.push('            ejercicio:');
lineas.push('              type: integer');
lineas.push('              description: El ejercicio de cuyo conjunto sellado se trata.');
lineas.push('              example: 2026');
lineas.push('            llave:');
lineas.push('              type: string');
lineas.push('              description: |');
lineas.push('                Lo que hay que publicar: `TIPO:CLAVE` cuando falta una sola fila,');
lineas.push('                o el `TIPO` solo cuando falta el bloque entero. **Ausente** cuando');
lineas.push('                lo que falta es el conjunto sellado: no hay donde publicar nada.');
lineas.push('                Nunca una clave inventada para rellenar el hueco.');
lineas.push('              example: "INTERES_FRACCIONAMIENTO:ORDINARIO"');
lineas.push('  responses:');
lineas.push('    SinMunicipalidad:');
lineas.push('      description: |');
lineas.push('        El token no identifica una municipalidad. No hay valor por omision ni');
lineas.push('        modo sin municipalidad.');
lineas.push('      content:');
lineas.push('        application/json:');
lineas.push('          schema: { $ref: "#/components/schemas/Error" }');
lineas.push('    SinDocumentoAcreditado:');
lineas.push('      description: |');
lineas.push('        El token del ciudadano no identifica un documento. No hay valor por');
lineas.push('        omision ni modo sin documento (ADR-0020).');
lineas.push('      content:');
lineas.push('        application/json:');
lineas.push('          schema: { $ref: "#/components/schemas/Error" }');
lineas.push('    ErrorDeValidacion:');
lineas.push('      description: La peticion no cumple una regla de negocio');
lineas.push('      content:');
lineas.push('        application/json:');
lineas.push('          schema: { $ref: "#/components/schemas/Error" }');
lineas.push('');

/* ── Escribir, o comprobar que ya estaba escrito ──────────────────────── */

/**
 * `--comprobar` no escribe: regenera en memoria y exige que cuadre.
 *
 * Es el peldano que impide que la deriva de #312 se vuelva a abrir. Sin el, un
 * YAML afinado a mano sigue pasando todas las pruebas —el contrato es valido,
 * el backend lo cumple, el frontend genera sus tipos de el— y lo unico que se
 * pierde es la capacidad de reproducirlo, que no la mide nadie hasta que
 * alguien regenera y se lleva por delante quince issues de trabajo.
 *
 * Dice **que** linea no cuadra, no solo que algo no cuadra: la primera
 * diferencia con su numero de linea y las dos versiones.
 */
const salida = lineas.join('\n');
const archivo = fileURLToPath(destino);

if (process.argv.includes('--comprobar')) {
  let comprometido = null;
  try {
    comprometido = readFileSync(archivo, 'utf8');
  } catch {
    comprometido = null;
  }
  if (comprometido !== salida) {
    const suyas = (comprometido ?? '').split('\n');
    const nuestras = salida.split('\n');
    const donde = nuestras.findIndex((linea, i) => linea !== suyas[i]);
    const distintas = nuestras.filter((linea, i) => linea !== suyas[i]).length;
    console.error(
      [
        '',
        '✗ El contrato comprometido y lo que este generador produce no cuadran.',
        '',
        `  contrato:  ${archivo}`,
        `  lineas:    ${suyas.length} comprometidas, ${nuestras.length} generadas` +
          ` (${distintas} no cuadran)`,
        `  primera divergencia, linea ${donde + 1}:`,
        `    contrato: ${JSON.stringify(suyas[donde] ?? '(el archivo acaba aqui)')}`,
        `    generado: ${JSON.stringify(nuestras[donde] ?? '(la salida acaba aqui)')}`,
        '',
        '  El YAML es la verdad y el generador tiene que devolverlo tal cual (#312).',
        '  Si el cambio del YAML es el que querias, llevalo a este generador; si no,',
        '  corre «node docs/50-api/generar-openapi.mjs» y anade el resultado.',
        '',
      ].join('\n'),
    );
    process.exit(1);
  }
  console.log(
    `El contrato y el generador cuadran: ${operaciones.length} operaciones en ${porRuta.size} rutas`,
  );
} else {
  writeFileSync(archivo, salida, 'utf8');
  console.log(`OpenAPI generado: ${operaciones.length} operaciones en ${porRuta.size} rutas`);
}

package kamayuk.rentas.verificaciones;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Lo que el backend publica y lo que el contrato promete tienen que ser lo mismo.
 *
 * <p>{@code docs/50-api/openapi/rentas-v1.yaml} no es documentacion escrita despues: esta derivado
 * de los {@code endpoint} que declara cada una de las 134 pantallas del prototipo, y el frontend lo
 * consume. Un endpoint que publique una ruta que el contrato no tiene es una ruta que ninguna
 * pantalla va a llamar; una ruta del contrato con una forma distinta a la implementada es una
 * pantalla que se rompe en integracion, semanas despues de escribir las dos mitades.
 *
 * <p>La prueba cubre <b>las dos direcciones</b>:
 *
 * <ul>
 *   <li>Toda ruta publicada tiene que estar en el contrato. Si alguien inventa una, falla.
 *   <li>Toda ruta de {@link #IMPLEMENTADAS} tiene que estar publicada. Esa lista es el registro
 *       explicito de lo que ya existe: no se puede publicar un endpoint sin anotarlo ahi, ni
 *       retirarlo sin quitarlo. Las operaciones restantes del contrato estan pendientes, y no se
 *       pueden exigir todavia sin dejar el build en rojo permanente —que es la forma segura de que
 *       nadie vuelva a mirar esta prueba—. Hoy quedan <b>dos</b>, y ninguna de las dos va a dejar
 *       de quedar: {@code GET /portal/deuda} es la vista del funcionario, y ningun controlador la
 *       va a servir (ADR-0016 §3).
 * </ul>
 */
@DisplayName("Contrato de la API (docs/50-api)")
class ContratoDeApiTest {

    /**
     * Las operaciones del contrato que ya estan implementadas.
     *
     * <p>Se agrega una linea por endpoint nuevo. Es deliberado que cueste una linea: asi el diff de
     * un endpoint nuevo dice que operacion del manual cubre.
     */
    /**
     * Las de {@code catastro} salieron con P5C, y no porque dejaran de existir.
     *
     * <p>`V6` retiro de esta base las quince tablas del sistema del predio y sus controladores se
     * fueron con ellas: las sirve el repositorio {@code catastro}, en {@code /catastro/api/v1}. Sus
     * rutas <b>se quedan en el contrato</b> —igual que {@code GET /portal/deuda} y que {@code GET
     * /seguridad/parametros} tras P5B— porque el contrato describe lo que la interfaz pide, y la
     * interfaz las sigue pidiendo; lo que ya no es cierto es que las publique ESTE backend.
     *
     * <p>Son 40 entradas, y son exactamente las que empiezan por {@code /catastro/} mas el resumen
     * predial.
     */
    private static final Set<String> IMPLEMENTADAS =
            Set.of(
                    // EL CAMINO DEL DINERO, PARTIDO EN DOS (P5D, ADR-0026). La primera es la
                    // que la ventanilla llama al marcar deuda: compone las ordenes contra el
                    // libro y `caja` las cobra despues. Las otras dos son el buzon de entrada, y
                    // son las dos unicas operaciones de este contrato que NINGUNA PANTALLA LLAMA:
                    // quien las llama es el publicador de `caja`, despues de su COMMIT. Estan
                    // aqui igual porque este backend las publica de verdad, y lo que esta lista
                    // promete es eso.
                    "POST /ordenes-de-cobro",
                    "POST /pagos",
                    "GET /pagos/conciliacion",
                    // Las TRES de `/catastro/` que este backend SI publica, y que no se fueron con
                    // P5C: la conciliacion y su recuento son un derivado de `declaracion_jurada`
                    // —de rentas— que catastro no puede mirar sin depender de rentas (ADR-0015), y
                    // los titulares de un predio los sirve rentas porque `contribuyentes` es la
                    // base del grafo y catastro ya depende de el (#366, ADR-0015 §2.4).
                    "GET /catastro/fichas/conciliacion",
                    "GET /catastro/fichas/conciliacion/resumen",
                    "GET /catastro/predios/{predioId}/titulares",
                    "GET /rentas/vehiculos/{placa}",
                    "GET /rentas/vehiculos",
                    // #489 — el alta del predio, sin ficha. `RegistrarPredio.registrar` existia
                    // desde #16 y ningun endpoint la llamaba: un predio solo nacia como efecto
                    // secundario de inscribir su ficha, o por la carga cartografica de #487.
                    // #490 — la titularidad y la ocupacion. `registrarTitularidad`,
                    // `registrarInquilino` y `finalizarInquilino` existian desde #16 y #31 y
                    // ninguno se publicaba: el primer titular de un predio no se podia registrar
                    // por HTTP, solo transferir lo que ya tenia dueno.
                    // #536 — el plano catastral. El contrato la declaraba desde #500 (ADR-0022) y
                    // ningun controlador la servia: era una de las DOS operaciones sin nadie que
                    // las atendiera, y la otra —`GET /portal/deuda`— no va a tenerlo (ADR-0016
                    // §3).
                    // #612 — donde esta lo levantado. `plano` exige `bbox` y ninguna operacion
                    // decia donde esta la municipalidad, asi que el visor abria sobre el pais
                    // entero: con geometria cargada eso contesta «acercate» y desde la pantalla
                    // no se sabe hacia donde.
                    "GET /rentas/contribuyentes",
                    // #488 — el padron se leia y no se escribia: `RegistrarContribuyente` y
                    // `ActualizarFicha` existian desde #11 y #15 y ningun controlador los
                    // publicaba, asi que una municipalidad recien implantada no podia registrar a
                    // su primer contribuyente sino por el proceso batch de importacion. Las ocho
                    // cuelgan de `/rentas/` y no estrenan `/contribuyentes/`: el prefijo de este
                    // contrato nombra el modulo del manual de la pantalla, no el contexto que la
                    // sirve, y toda escritura sigue a su pantalla (razonado en el generador).
                    "POST /rentas/contribuyentes",
                    "PUT /rentas/contribuyentes/{id}",
                    "GET /rentas/contribuyentes/{id}/ficha",
                    "POST /rentas/contribuyentes/{id}/domicilios",
                    "POST /rentas/contribuyentes/{id}/contactos",
                    "PUT /rentas/contribuyentes/{id}/contactos/{contactoId}",
                    "POST /rentas/contribuyentes/{id}/responsables",
                    "PUT /rentas/contribuyentes/{id}/responsables/{responsableId}",
                    "GET /rentas/beneficios",
                    "GET /rentas/arbitrios",
                    "GET /rentas/declaraciones/{djNro}",
                    "GET /rentas/declaraciones/{djNro}/hoja",
                    // #365 — ADR-0015 §3: la escritura de la declaracion jurada, el acto que
                    // concilia. Las cuatro son adiciones al contrato: la pantalla
                    // `declaracion_jurada` declara UN endpoint —el GET que consulta la DJ ya
                    // presentada— y presentarla, rectificarla, observarla y anularla necesitan
                    // verbo propio. Hasta aqui el caso de uso existia y ningun controlador lo
                    // exponia, asi que el acto se seguia haciendo fuera del sistema.
                    "POST /rentas/declaraciones",
                    "POST /rentas/declaraciones/{djNro}/rectificacion",
                    "POST /rentas/declaraciones/{djNro}/observacion",
                    "POST /rentas/declaraciones/{djNro}/anulacion",
                    "POST /rentas/transferencias/predio",
                    "POST /rentas/transferencias/vehiculo",
                    "POST /rentas/vehicular/calculo",
                    // #395 — la capa web de la determinacion predial. #30 dejo la regla de negocio
                    // y su prueba; ningun controlador la publicaba, asi que el calculo del predial
                    // se seguia haciendo fuera del sistema. `predial_individual` y
                    // `predial_masivo` distinguen simular de asentar por el cuerpo, como
                    // `vehicular_calculo` desde #32; `predios_rentas` publica el padron predial
                    // que la pantalla de Rentas · Registro dibuja.
                    "GET /rentas/predios",
                    "POST /rentas/predial/calculo-individual",
                    "POST /rentas/predial/calculo-masivo",
                    "GET /rentas/predial/corridas/ultima",
                    "GET /rentas/predial/corridas/{corridaId}/observados",
                    "POST /rentas/alcabala",
                    "POST /rentas/espectaculos",
                    "GET /consultas/cuenta-corriente/{codigo}",
                    "GET /consultas/deuda",
                    "GET /consultas/altas-bajas",
                    "GET /consultas/constancias/no-adeudo",
                    "GET /consultas/vehiculos",
                    "GET /consultas/pagos",
                    "GET /consultas/predios",
                    "GET /consultas/valores",
                    "GET /consultas/unificada",
                    // #57 — ADR-0020: la unica operacion del portal del contribuyente, y la
                    // unica de toda la API que se sirve con el token del realm del ciudadano.
                    // Sustituye a `GET /portal/deuda?doc=…`, que el mismo issue retira del
                    // contrato: el documento deja de ser un parametro y pasa a ser un claim
                    // firmado. Sin parametros a proposito —el servidor recorre, compone y suma
                    // en una sola ida y vuelta (RNF-083)—.
                    "GET /portal/situacion",
                    // #72: la ultima opcion de Consultas. Simula el acogimiento de la deuda a una
                    // campana de beneficio; las campanas y lo que descuentan son dato del conjunto
                    // sellado (D-02b, D-02c), no un enum.
                    "GET /consultas/deudas-con-beneficio",
                    "POST /rentas/deuda/altas",
                    "POST /rentas/deuda/bajas",
                    "GET /seguridad/modulos",
                    "GET /seguridad/accesos",
                    "GET /seguridad/grupos",
                    "GET /seguridad/usuarios",
                    // #543 — la matriz de permisos efectivos de un usuario no se podia
                    // reconstruir: no habia lectura de pertenencia a grupo (la ruta de
                    // miembros era solo POST) ni de la excepcion de usuario, y sin `origen`
                    // el cliente tendria que reimplementar la precedencia.
                    "GET /seguridad/usuarios/{id}/grupos",
                    "GET /seguridad/usuarios/{id}/permisos",
                    // #585 — y la ESCRITURA de esa excepcion, que #543 dejo sin poder tocar:
                    // `AdministrarPermisos.fijarParaUsuario` existia —transaccional, con su
                    // Observacion y con la guarda del ultimo administrador— y no la llamaba
                    // nadie, asi que negarle un privilegio a una persona sin sacarla de su
                    // grupo solo se podia por SQL directo, sin fila de auditoria y sin pasar
                    // por esa guarda.
                    "PUT /seguridad/usuarios/{id}/permisos",
                    // #583 — las dos preguntas que la matriz efectiva no puede contestar.
                    // La primera es lo CONFIGURADO de una cuenta: la efectiva aplica la
                    // regla del guardia, asi que a una cuenta deshabilitada le contesta la
                    // lista vacia tanto si conserva permisos como si nunca los tuvo, y las
                    // dos respuestas eran el mismo JSON. La segunda es la inversa —que
                    // cuentas tienen un privilegio sobre un acceso—, que costaba una
                    // peticion por usuario del padron y no se puede componer acotando por
                    // grupo, porque la excepcion propia SUSTITUYE a lo que el grupo da.
                    "GET /seguridad/usuarios/{id}/permisos/configurados",
                    "GET /seguridad/accesos/{codigo}/usuarios",
                    "POST /seguridad/grupos/{grupo}/miembros",
                    // #582 — la pregunta inversa: quien esta EN un grupo. De esta misma
                    // ruta solo habia el POST que afilia, asi que derivarla costaba una
                    // peticion por usuario del padron de cuentas.
                    "GET /seguridad/grupos/{grupo}/miembros",
                    "PUT /seguridad/grupos/{id}/permisos",
                    "GET /seguridad/grupos/{id}/permisos",
                    // #572 — las ocho escrituras que #543 dejo censadas sin ruta. Las de
                    // grupo solo esperaban su controlador; las de usuario esperaban la
                    // decision de ADR-0012 §5 sobre como se coordinan las dos mitades de
                    // una persona: esta pantalla escribe la fila del padron y el archivo
                    // declarativo de `despliegue/identidad/` crea la cuenta del proveedor.
                    "POST /seguridad/grupos",
                    "POST /seguridad/grupos/{id}/baja",
                    "POST /seguridad/grupos/{id}/reactivacion",
                    "PUT /seguridad/grupos/{id}/vigencia",
                    "POST /seguridad/usuarios",
                    "POST /seguridad/usuarios/{id}/baja",
                    "POST /seguridad/usuarios/{id}/reactivacion",
                    "PUT /seguridad/usuarios/{id}/vigencia",
                    "GET /seguridad/sesion/permisos",
                    // #555 — a quien pertenecen las cifras de la pantalla. Ninguna
                    // operacion publicaba el nombre de la municipalidad de la sesion, asi
                    // que la interfaz lo llevaba compilado y podia poner el membrete de una
                    // municipalidad sobre los datos de otra.
                    "GET /seguridad/sesion/municipalidad",
                    // #559 — quien es la sesion. Ninguna lectura publicaba el `usuario.id`
                    // propio, asi que la interfaz no podia llamar a `PUT
                    // /seguridad/usuarios/{id}/clave` —que solo admite la propia— sin
                    // deducirlo del listado de usuarios, que exige un permiso mucho mayor.
                    "GET /seguridad/sesion",
                    "PUT /seguridad/sesion/ejercicio",
                    "PUT /seguridad/usuarios/{id}/clave",
                    "GET /seguridad/auditoria",
                    "POST /seguridad/respaldos",
                    // «GET /seguridad/parametros» —el listado paginado de los conjuntos— SE FUE
                    // en P5B y por eso ya no esta aqui. La pantalla que administra los juegos de
                    // valores es de `normativa`, que es donde estan: servirla desde este sistema
                    // solo podria enumerar los conjuntos DESCARGADOS, y eso diria «estos son los
                    // conjuntos de la municipalidad» cuando lo cierto seria «estos son los que
                    // hemos bajado». Es la clase de cifra plausible y equivocada que no se
                    // distingue de la correcta (ADR-0025 §5).
                    //
                    // La ruta se queda EN EL CONTRATO —el contrato esta derivado del prototipo
                    // (#312) y no se edita a mano— y sale de esta lista, que es exactamente lo que
                    // esta lista significa: «lo que este backend promete implementar». Es la
                    // segunda de las dos que quedan sin controlador, con `GET /portal/deuda`.
                    // #605 — si un ejercicio tiene conjunto sellado, para poder decirlo ANTES
                    // de calcular. Es una ruta que la pantalla `parametros` no declara —declara
                    // su listado— y entra por OPERACIONES_ADICIONALES del generador.
                    "GET /seguridad/parametros/ejercicios/{ejercicio}",
                    "GET /transito/codigos",
                    "GET /infracciones/cuis",
                    "GET /infracciones/administrativas/codigos/reporte",
                    // #431: la lectura del programa, que faltaba. `/fiscalizacion/programas`
                    // declaraba solo `post` desde el prototipo, asi que un programa se podia
                    // registrar y no se podia volver a encontrar —ni por su pantalla, ni por las
                    // dos actas, que exigen el `programaId` de un programa ya generado—. Es una
                    // ruta que la pantalla no declara —declara su POST— y entra por
                    // OPERACIONES_ADICIONALES del generador del contrato.
                    "GET /fiscalizacion/programas",
                    "POST /fiscalizacion/programas",
                    // #481: la muestra sorteada, que es la grilla «Predios seleccionados» de
                    // `fisc_programa` y tambien la fila de la que el acta predial resuelve sus
                    // tres identificadores -su catalogo los dibuja de solo lectura y no declara
                    // ni filtros ni tabla, asi que solo se puede abrir desde una fila ya resuelta.
                    "GET /fiscalizacion/programas/{id}/muestra",
                    "POST /fiscalizacion/programas/{id}/muestra",
                    "POST /fiscalizacion/predial/actas",
                    "POST /fiscalizacion/vehicular",
                    // #599: la lectura de actas, que faltaba. Hasta que el acta tuvo donde
                    // consignar el uso hallado (V76) no habia nada honesto que publicar, y
                    // eso es lo que #546 midio al negarse a esta misma lectura.
                    "GET /fiscalizacion/actas",
                    // #49: la liquidacion, su reliquidacion y su estado, mas las cuatro
                    // consultas del modulo. Las tres primeras son rutas que la pantalla no
                    // declara —una pantalla declara UN endpoint— y entran por
                    // OPERACIONES_ADICIONALES del generador del contrato.
                    "POST /fiscalizacion/liquidaciones",
                    "POST /fiscalizacion/liquidaciones/{numero}/reliquidaciones",
                    "PATCH /fiscalizacion/liquidaciones/{numero}/estados",
                    "GET /fiscalizacion/resultados",
                    "GET /fiscalizacion/omisos",
                    "GET /fiscalizacion/estado-cuenta",
                    "GET /fiscalizacion/predial/historico",
                    // #52: la transferencia a rentas —la frontera delicada, RF-054— y la
                    // resolucion de determinacion que la materializa (RF-057). La primera es una
                    // ruta que la pantalla no declara —`fisc_resultados` declara su grilla— y
                    // entra por OPERACIONES_ADICIONALES del generador; la segunda ya estaba en el
                    // contrato desde el prototipo y no la servia nadie.
                    "POST /fiscalizacion/transferencias",
                    "GET /fiscalizacion/resoluciones/{numero}",
                    "GET /transito/papeletas",
                    "GET /transito/papeletas/busqueda",
                    "PATCH /transito/papeletas/{numero}/codigo",
                    "GET /transito/estado-cuenta",
                    "POST /infracciones/administrativas/notificaciones",
                    "GET /infracciones/actas",
                    "GET /infracciones/administrativas/estado-cuenta",
                    "GET /infracciones/administrativas/reportes/vencidas",
                    "GET /infracciones/administrativas/reportes/por-contribuyente",
                    "POST /valores",
                    "GET /valores",
                    "POST /valores/masivo",
                    "POST /valores/{nro}/notificacion",
                    "POST /coactiva/prescripcion",
                    // #674: la relacion de prescripciones declaradas. La decision de ese
                    // issue es que declarar la prescripcion NO toca el libro —lo que
                    // prescribe es la accion de cobro, no la obligacion—, asi que la deuda
                    // sigue en la cartera hasta que alguien la de de baja con RF-044. Esta
                    // lectura es la contrapartida: sin ella, la deuda inexigible no se ve
                    // en ninguna parte y la decision no se distingue de un descuido.
                    "GET /coactiva/prescripcion",
                    "POST /valores/{numero}/movimientos",
                    // Las NUEVE de la caja salieron con P5D, y no porque dejaran de existir:
                    // `GET /tesoreria/cajas` (#618), `GET /tesoreria/recibos` y sus dos actos
                    // (#548), las dos cobranzas, el cierre y las dos de recaudacion. `V7` retiro
                    // de esta base las diez tablas del dinero y sus cinco controladores se fueron
                    // con ellas: las sirve el repositorio `caja`, en su propia raiz. Sus rutas SE
                    // QUEDAN en el contrato —igual que las de `catastro` tras P5C y `GET
                    // /seguridad/parametros` tras P5B— porque el contrato describe lo que la
                    // interfaz pide, y la interfaz las sigue pidiendo; lo que ya no es cierto es
                    // que las publique ESTE backend.
                    //
                    // Las DOS que se quedan son las del CONVENIO, y no es una excepcion sino la
                    // particion de ADR-0026 §5: un convenio es deuda reprogramada —tiene interes,
                    // tiene quiebre y tiene consecuencias coactivas— asi que se queda en `rentas`
                    // aunque se firme en la ventanilla de caja.
                    "POST /tesoreria/fraccionamientos",
                    "GET /tesoreria/convenios",
                    "POST /tesoreria/convenios/{numero}/anulacion",
                    "GET /coactiva/expedientes",
                    // #426: la deuda del expediente OBLIGACION POR OBLIGACION. Es la
                    // lectura de la que `fraccionamiento_coactivo` saca sus filas —su
                    // cuerpo pide tributo, ejercicio y predioId/vehiculoId una a una, y
                    // ninguna lectura del modulo tenia esa granularidad—. Sale de la
                    // MISMA composicion que la deuda que imprime la REC-2.
                    "GET /coactiva/expedientes/{numero}/deuda",
                    "POST /coactiva/expedientes/importacion",
                    "PATCH /coactiva/expedientes/{numero}/estados",
                    "PATCH /coactiva/expedientes/{numero}/direccion-referencial",
                    "POST /coactiva/rec/impresion",
                    "GET /coactiva/expedientes/{numero}/proceso",
                    "POST /coactiva/expedientes/{numero}/actos",
                    "POST /coactiva/notificaciones",
                    // #42 — RF-104, RF-105 y RF-107: las costas como cargo del libro, el
                    // fraccionamiento coactivo y las dos consultas de deuda.
                    "POST /coactiva/liquidaciones-costas",
                    "GET /coactiva/liquidaciones-costas",
                    "POST /coactiva/convenios",
                    "GET /coactiva/deudas",
                    "GET /coactiva/deudas-en-beneficio",
                    // #44 — RF-110..RF-113: la licencia de funcionamiento y su catalogo CIIU.
                    "GET /licencias/funcionamiento",
                    "POST /licencias/funcionamiento",
                    "POST /licencias/funcionamiento/{id}/cancelacion",
                    "POST /licencias/funcionamiento/{id}/duplicado",
                    "GET /licencias/ciiu",
                    "POST /licencias/ciiu",
                    // #50 — descargos, internamiento y resoluciones de gerencia (RF-064, RF-065,
                    // RF-074). Las tres ultimas son adiciones al contrato: la pantalla
                    // `internamiento` declara solo su grilla y sus dos acciones necesitan verbo
                    // propio, y transito no tenia ruta para notificar su resolucion de gerencia
                    // -sin ella la sancionadora no se puede dictar nunca, porque su plazo se
                    // cuenta desde que la ordinaria surte efecto-.
                    "POST /transito/descargos",
                    "GET /transito/internamientos",
                    "POST /transito/internamientos",
                    "POST /transito/internamientos/{placa}/liberacion",
                    "POST /transito/resoluciones/ordinaria",
                    "POST /transito/resoluciones/sancionadora",
                    "POST /transito/resoluciones/{numero}/notificacion",
                    "GET /transito/papeletas/{numero}/actos",
                    "POST /infracciones/administrativas/resoluciones",
                    "POST /infracciones/administrativas/resoluciones/{id}/notificacion",
                    // #48 — RF-113 y RF-115: el FUE completo, sus secciones por partes, la
                    // emision de la licencia de edificacion, su revalidacion y el reporte.
                    "GET /licencias/edificacion",
                    "POST /licencias/edificacion",
                    "POST /licencias/edificacion/{expediente}/secciones",
                    "POST /licencias/edificacion/{expediente}/licencia",
                    "POST /licencias/edificacion/{expediente}/revalidacion",
                    "GET /licencias/edificacion/reportes/general",
                    // #51 — RF-114: anuncios y propaganda, con la deuda por la tasa generada al
                    // registrar. Los tres POST de acto son tramites, no ediciones: `anuncio` no
                    // admite UPDATE desde V45.
                    "GET /autorizaciones/anuncios",
                    "POST /autorizaciones/anuncios",
                    "POST /autorizaciones/anuncios/{id}/renovacion",
                    "POST /autorizaciones/anuncios/{id}/cese",
                    "POST /autorizaciones/anuncios/{id}/retiro",
                    "POST /autorizaciones/anuncios/reportes",
                    // #53 — RF-066, RF-068, RF-073 y RF-074: la generacion masiva de valores por
                    // papeletas, la constancia libre de infracciones, los tres padrones, los dos
                    // records y los cinco resumenes. Ninguna es una adicion al contrato: las
                    // quince ya estaban declaradas desde que el contrato se derivo del prototipo,
                    // y lo que este issue hace es publicarlas.
                    "POST /transito/valores/generacion-masiva",
                    "POST /infracciones/administrativas/valores/generacion-masiva",
                    "POST /transito/constancias-libres",
                    "GET /transito/reportes/padron",
                    "GET /transito/reportes/padron-coactiva",
                    "GET /transito/reportes/padron-constancias",
                    "GET /transito/reportes/record-conductor",
                    "GET /transito/reportes/record-vehicular",
                    "GET /transito/reportes/resumen-recaudacion",
                    "GET /transito/reportes/resumen-papeletas",
                    "GET /transito/reportes/resumen-por-codigo",
                    "GET /transito/reportes/resumen-por-placa",
                    "GET /infracciones/administrativas/reportes/padron-notificaciones",
                    "GET /infracciones/administrativas/reportes/resumen-recaudacion",
                    "POST /infracciones/administrativas/reportes",
                    // #54 — RF-115 y RF-132: los padrones de licencias y los certificados de
                    // numeracion y zonificacion. Las dos ultimas son adiciones al contrato: la
                    // pantalla `certificados` declara UN endpoint —el POST que emite— y su grilla
                    // y su accion «Imprimir certificado» necesitan verbo propio.
                    "POST /licencias/funcionamiento/reportes/padron",
                    "GET /licencias/funcionamiento/reportes/resumen-anual",
                    "GET /licencias/certificados",
                    "POST /licencias/certificados",
                    "POST /licencias/certificados/{numero}/impresion",
                    // #56 — RF-130: el panel de recaudacion, la pantalla de inicio. Ya
                    // estaba declarada en el contrato desde que se derivo del prototipo;
                    // lo que este issue hace es publicarla. No tiene modelo propio: agrega
                    // lo que cuentacorriente y tesoreria publican.
                    "GET /indicadores/recaudacion",
                    // #549 — RF-130: el trabajo parado por modulo, la SEGUNDA lectura de la
                    // misma pantalla. Entra por OPERACIONES_ADICIONALES del generador, porque
                    // no sale de ninguno de los cuatro modulos que cuenta sino de `inicio`.
                    "GET /indicadores/trabajo-parado",
                    // #344 — ADR-0015: la conciliacion catastro-rentas. Es una adicion al
                    // contrato: `consulta_fichas` declara «GET /catastro/fichas» —la grilla, que
                    // sirve catastro— y la misma grilla CON la columna «Conciliada» no la puede
                    // servir catastro, porque el derivado sale de `declaracion_jurada` y
                    // dependerlo cerraria el ciclo de modulos. La sirve rentas, en esta ruta, y
                    // la de catastro redirige alli la peticion que trae el filtro.
                    // #366 — ADR-0015 §2.4: el titular del predio, resuelto al clic. La grilla
                    // sigue publicando el nombre y no el identificador; quien quiera el codigo
                    // del contribuyente lo pide aqui, de un predio cada vez, con el permiso del
                    // padron y dejando fila de ACCESO. La sirve rentas por lo mismo que la
                    // conciliacion: es el unico modulo que ve catastro y contribuyentes a la vez
                    // sin cerrar un ciclo.
                    // #396 — las dos ultimas operaciones de Transito que el contrato declaraba y
                    // ningun controlador servia. Ninguna es una adicion: las dos estaban desde que
                    // el contrato se derivo del prototipo, y #53 las dejo fuera.
                    // `transito_reportes`
                    // es el emisor del modulo —la entrada del centro de reportes de ADR-0014 §5— y
                    // no trae ninguna consulta nueva: llama a las mismas que los GET.
                    "POST /transito/reportes",
                    "GET /transito/papeletas/{numero}/hoja-informativa");

    /** Una ruta del contrato: {@code "/ruta":} con dos espacios de sangria, nada mas. */
    private static final Pattern RUTA_DEL_CONTRATO = Pattern.compile("  \"(/[^\"]*)\":");

    /**
     * Un verbo dentro de la ruta actual: {@code verbo:} con cuatro espacios de sangria.
     *
     * <p>Una ruta puede declarar mas de un verbo —{@code permisos} lee y guarda en la misma ruta,
     * {@code GET} para cargar la matriz y {@code PUT} para guardarla—, asi que esto no puede ser
     * parte de un solo regex por ruta: hay que seguir mirando lineas hasta la siguiente ruta.
     */
    private static final Pattern VERBO_DEL_CONTRATO =
            Pattern.compile("    (get|post|put|patch|delete):");

    @Test
    @DisplayName("el contrato se lee, y trae las 134 operaciones del manual")
    void elContratoSeLee() throws IOException {
        Set<String> contrato = operacionesDelContrato();

        // Si el analisis del YAML devolviera vacio, las dos pruebas de abajo pasarian
        // sin comparar nada. Ha pasado en otros proyectos con un cambio de formato.
        assertThat(contrato)
                .as("el contrato declara una operacion por opcion del menu")
                .hasSizeGreaterThan(100);
        assertThat(contrato).contains("GET /catastro/vias");
    }

    @Test
    @DisplayName("ninguna ruta publicada falta en el contrato")
    void ningunaRutaPublicadaFaltaEnElContrato() throws IOException {
        Set<String> contrato = operacionesDelContrato();
        Set<String> publicadas = operacionesPublicadas();

        assertThat(publicadas).as("sin endpoints publicados no hay nada que comparar").isNotEmpty();

        Set<String> fueraDelContrato = new TreeSet<>(publicadas);
        fueraDelContrato.removeAll(contrato);

        assertThat(fueraDelContrato)
                .as(
                        "estas rutas se publican y el contrato no las tiene: ninguna pantalla las va"
                                + " a llamar. O se agregan al prototipo y se regenera el contrato, o"
                                + " sobran")
                .isEmpty();
    }

    @Test
    @DisplayName("toda operacion declarada implementada esta realmente publicada")
    void todaOperacionDeclaradaImplementadaEstaPublicada() throws IOException {
        Set<String> publicadas = operacionesPublicadas();

        assertThat(publicadas)
                .as("lo que IMPLEMENTADAS promete tiene que existir de verdad")
                .containsAll(IMPLEMENTADAS);
        assertThat(operacionesDelContrato())
                .as("y tiene que ser una operacion que el contrato declare")
                .containsAll(IMPLEMENTADAS);
        assertThat(publicadas)
                .as(
                        "hay endpoints publicados que no estan en IMPLEMENTADAS: un endpoint nuevo"
                                + " se anota ahi, para que el diff diga que opcion del manual cubre")
                .isSubsetOf(IMPLEMENTADAS);
    }

    // ------------------------------------------------------------------

    private static Set<String> operacionesDelContrato() throws IOException {
        List<String> lineas =
                Files.readAllLines(
                        raizDelRepositorio().resolve("docs/50-api/openapi/rentas-v1.yaml"),
                        StandardCharsets.UTF_8);

        Set<String> operaciones = new TreeSet<>();
        String rutaActual = null;
        for (String linea : lineas) {
            Matcher ruta = RUTA_DEL_CONTRATO.matcher(linea);
            if (ruta.matches()) {
                rutaActual = ruta.group(1);
                continue;
            }
            Matcher verbo = VERBO_DEL_CONTRATO.matcher(linea);
            if (verbo.matches() && rutaActual != null) {
                operaciones.add(
                        verbo.group(1).toUpperCase(java.util.Locale.ROOT) + " " + rutaActual);
            }
        }
        return operaciones;
    }

    /**
     * Las operaciones que los controladores publican.
     *
     * <p>El recorrido vive en {@link EndpointsPublicados} porque lo miran dos pruebas: esta compara
     * <b>que rutas</b> hay contra el contrato y {@link FormasDeLaApiTest} compara <b>que
     * devuelve</b> cada una. Escrito dos veces, los dos empiezan iguales y acaban discrepando en el
     * caso raro —un metodo sin verbo, dos mapeos sobre la misma ruta—, y entonces una de las dos
     * mide algo que la otra no ve.
     */
    private static Set<String> operacionesPublicadas() {
        return EndpointsPublicados.operaciones();
    }

    /** El contrato vive en docs/, fuera del build de Gradle. */
    private static Path raizDelRepositorio() {
        Path actual = Path.of("").toAbsolutePath();
        while (actual != null) {
            if (Files.exists(actual.resolve("docs/50-api/openapi/rentas-v1.yaml"))) {
                return actual;
            }
            actual = actual.getParent();
        }
        throw new IllegalStateException("No se encontro el contrato de la API");
    }
}

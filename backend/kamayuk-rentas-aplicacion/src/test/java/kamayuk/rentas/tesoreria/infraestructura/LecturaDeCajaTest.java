package kamayuk.rentas.tesoreria.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.LocalDate;
import java.util.Map;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.tesoreria.AnulacionesDeRecibo;
import kamayuk.rentas.tesoreria.AvanceDeCaja;
import kamayuk.rentas.tesoreria.RecaudadoEnCaja;
import kamayuk.rentas.verificaciones.ContratoQueConsumeDeCaja;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * La ida y vuelta con {@code caja}: la respuesta que el proveedor publica, leida por el adaptador
 * de produccion (#41 AC-4).
 *
 * <h2>Por que la respuesta se FABRICA a partir del contrato</h2>
 *
 * <p>Que el archivo comprometido sea el que produce {@code ContratoQueConsumeDeCaja} no dice nada
 * sobre si ese contrato describe lo que el adaptador lee: los dos salen de este repositorio. Lo que
 * lo ata es esto: la respuesta se compone <b>de las formas declaradas</b> y se pasa por {@code
 * AvanceDeCajaHttp}, {@code CobrosDeTasasHttp} y {@code RecibosDeTramiteHttp} de verdad. Cambiar el
 * adaptador para leer {@code dia} donde el contrato dice {@code desde} deja la respuesta fabricada
 * sin ese campo, y la lectura falla nombrandolo.
 *
 * <h2>Y una copia literal, ademas</h2>
 *
 * <p>{@link #laRespuestaCompletaDeCajaSeLee} usa el cuerpo <b>entero</b> de {@code
 * RecaudacionResource.Avance} —con {@code filas}, {@code neto} y {@code turno}, que este lado no
 * lee— para medir la otra mitad: que los campos que el proveedor publica de mas no estorban, que es
 * la direccion de contencion que {@code ContratoDelConsumidor} declara.
 *
 * <h2>Lo que esto existe para impedir</h2>
 *
 * <p>Antes de #41 el adaptador leia {@code path("cobrado").asString("0")} sobre un campo que {@code
 * caja} publica como <b>objeto</b> {@code {importe, actualizadoA}}. Eso no es un error: {@code
 * asString} sobre un {@code ObjectNode} devuelve el valor por omision. O sea que en cuanto los
 * parametros de #27 se arreglaran, el panel habria publicado <b>«0,00 cobrado hoy»</b> con la
 * ventanilla cobrando y sin un solo error — de ruidoso a mudo.
 */
@DisplayName("Lo que caja publica, leido por los adaptadores de verdad")
class LecturaDeCajaTest {

    private static final JsonMapper JSON = new JsonMapper();
    private static final LocalDate DIA = LocalDate.of(2026, 9, 6);

    // ------------------------------------------------------------------
    //  El avance del dia
    // ------------------------------------------------------------------

    @Test
    @DisplayName("las CUATRO cifras del avance salen, y ninguna es un valor por omision")
    void elAvanceSaleCompleto() {
        RecaudadoEnCaja avance = new AvanceDeCajaHttp(cajaConElAvance()).delDia(DIA, DIA);

        assertThat(avance.cobrado()).isEqualTo(Dinero.de("3084.20"));
        assertThat(avance.anulado()).isEqualTo(Dinero.de("612.00"));
        assertThat(avance.dia()).isEqualTo(DIA);
        assertThat(avance.aLaFecha()).isEqualTo(DIA);
        assertThat(avance.neto()).isEqualTo(Dinero.de("2472.20"));
    }

    @Test
    @DisplayName("y con el cuerpo ENTERO que caja publica: lo que sobra no estorba")
    void laRespuestaCompletaDeCajaSeLee() {
        // Copiado campo a campo de `RecaudacionResource.Avance` del clon de `caja`: los ocho
        // componentes, con `filas`, `neto` y `turno` que este lado no lee. Un proveedor puede
        // publicar de mas —anadir un campo no rompe a nadie—, y esa direccion hay que medirla:
        // sin esta prueba, un adaptador que exigiera la forma exacta pasaria en verde aqui y
        // reventaria contra la instalacion.
        String entero =
                """
                {"desde":"2026-09-06","hasta":"2026-09-06","aLaFecha":"2026-09-06",
                 "filas":[{"tributo":"PREDIAL",
                           "cobrado":{"importe":"2000.00","actualizadoA":"2026-09-06"},
                           "anulado":{"importe":"0.00","actualizadoA":"2026-09-06"},
                           "neto":{"importe":"2000.00","actualizadoA":"2026-09-06"}}],
                 "cobrado":{"importe":"3084.20","actualizadoA":"2026-09-06"},
                 "anulado":{"importe":"612.00","actualizadoA":"2026-09-06"},
                 "neto":{"importe":"2472.20","actualizadoA":"2026-09-06"},
                 "turno":null}
                """;

        RecaudadoEnCaja avance =
                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> JSON.readTree(entero)))
                        .delDia(DIA, DIA);

        assertThat(avance.cobrado()).isEqualTo(Dinero.de("3084.20"));
        assertThat(avance.anulado()).isEqualTo(Dinero.de("612.00"));
        assertThat(avance.aLaFecha()).isEqualTo(DIA);
    }

    @Test
    @DisplayName("un importe que llega como OBJETO no se degrada a cero: falla nombrandolo")
    void elImporteObjetoNoSeLeeComoCero() {
        // Es la forma exacta que `caja` publica —`ImporteActualizado`—, y la que el adaptador
        // leia con `asString("0")` sin que nada fallara.
        ObjectNode cuerpo = avanceBase();
        cuerpo.set("cobrado", JSON.createObjectNode().put("noEsElImporte", "3084.20"));

        assertThatThrownBy(
                        () ->
                                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("cobrado.importe");
    }

    @Test
    @DisplayName("y un importe que no esta tampoco: no hay «0» por omision (AC-2)")
    void elImporteAusenteNoSeLeeComoCero() {
        ObjectNode cuerpo = avanceBase();
        cuerpo.remove("anulado");

        assertThatThrownBy(
                        () ->
                                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("anulado.importe")
                .hasMessageContaining("nada: el campo no esta");
    }

    @Test
    @DisplayName("el rango que vuelve se comprueba contra el que se pidio (AC-3)")
    void elRangoQueVuelveSeComprueba() {
        ObjectNode cuerpo = avanceBase();
        cuerpo.put("desde", "2026-09-01");

        assertThatThrownBy(
                        () ->
                                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("se pidio el rango [2026-09-06, 2026-09-06]")
                .hasMessageContaining("[2026-09-01, 2026-09-06]");
    }

    @Test
    @DisplayName("y la fecha con la que caja contesta, tambien (AC-3)")
    void laFechaQueVuelveSeComprueba() {
        ObjectNode cuerpo = avanceBase();
        cuerpo.put("aLaFecha", "2026-09-05");
        cuerpo.set("cobrado", importe("3084.20", "2026-09-05"));
        cuerpo.set("anulado", importe("612.00", "2026-09-05"));

        assertThatThrownBy(
                        () ->
                                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("se pidio a la fecha 2026-09-06")
                .hasMessageContaining("leyo a la fecha 2026-09-05");
    }

    @Test
    @DisplayName("y dos cifras del mismo avance con dos fechas no se restan")
    void lasDosCifrasVanFechadasIgual() {
        ObjectNode cuerpo = avanceBase();
        cuerpo.set("anulado", importe("612.00", "2026-09-05"));

        assertThatThrownBy(
                        () ->
                                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("anulado.actualizadoA");
    }

    @Test
    @DisplayName("lo que sale es la excepcion del PUERTO, que es la que el panel caza (AC-5)")
    void loQueSaleEsLaExcepcionDelPuerto() {
        // `PanelDeRecaudacion.avanceDelDiaSiSePuede` caza `AvanceDeCaja.CajaInalcanzable` y
        // deja el avance en nulo, con las otras tres cifras del libro intactas (#25); que el
        // panel no publique ceros con eso lo mide `PanelDeRecaudacionTest`. Lo que faltaba es
        // que una respuesta ILEGIBLE saliera por ahi: hasta #41 salia un
        // `DateTimeParseException` crudo de `LocalDate.parse("")`, que nadie caza — y `GET
        // /rentas/api/v1/indicadores/recaudacion` contestaba 500.
        ObjectNode cuerpo = avanceBase();
        cuerpo.remove("aLaFecha");

        assertThatThrownBy(
                        () ->
                                new AvanceDeCajaHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .delDia(DIA, DIA))
                .isInstanceOf(AvanceDeCaja.CajaInalcanzable.class)
                .isNotInstanceOf(java.time.format.DateTimeParseException.class)
                .isNotInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class);
    }

    // ------------------------------------------------------------------
    //  Las otras tres lecturas de la frontera
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el recibo de tramite sale completo, y su total no se degrada")
    void elReciboDeTramiteSaleCompleto() {
        ObjectNode cuerpo = desdeElContrato(ContratoQueConsumeDeCaja.RECIBO_DE_TRAMITE);
        cuerpo.put("total", "35.00");
        cuerpo.put("fechaDePago", DIA.toString());
        cuerpo.put("actualizadoA", DIA.toString());
        cuerpo.putArray("conceptos").add("TUPA-01");

        var recibo =
                new RecibosDeTramiteHttp(new CajaQueNoContesta(ruta -> cuerpo))
                        .porNumeroImpreso("001-0000123")
                        .orElseThrow();

        assertThat(recibo.total()).isEqualTo(Dinero.de("35.00"));
        assertThat(recibo.fechaDePago()).isEqualTo(DIA);
        assertThat(recibo.conceptos()).containsExactly("TUPA-01");
    }

    @Test
    @DisplayName("y un total ausente en el recibo falla nombrandolo, no vale «0»")
    void elTotalDelReciboNoSeDegrada() {
        ObjectNode cuerpo = desdeElContrato(ContratoQueConsumeDeCaja.RECIBO_DE_TRAMITE);
        cuerpo.put("fechaDePago", DIA.toString());
        cuerpo.put("actualizadoA", DIA.toString());
        cuerpo.remove("total");

        assertThatThrownBy(
                        () ->
                                new RecibosDeTramiteHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .porNumeroImpreso("001-0000123"))
                .isInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("«total»");
    }

    @Test
    @DisplayName("lo recaudado por una tasa sale con sus dos importes y sus dos fechas")
    void laRecaudacionDeUnaTasaSaleCompleta() {
        ObjectNode cuerpo = desdeElContrato(ContratoQueConsumeDeCaja.RECAUDACION_DE_TASA);
        cuerpo.put("codigoDeTasa", "TUPA-01");
        cuerpo.put("cobrado", "1200.00");
        cuerpo.put("anulado", "200.00");
        cuerpo.put("desde", DIA.toString());
        cuerpo.put("hasta", DIA.toString());

        var recaudado =
                new CobrosDeTasasHttp(new CajaQueNoContesta(ruta -> cuerpo))
                        .recaudado("TUPA-01", DIA, DIA);

        assertThat(recaudado.cobrado()).isEqualTo(Dinero.de("1200.00"));
        assertThat(recaudado.anulado()).isEqualTo(Dinero.de("200.00"));
        assertThat(recaudado.desde()).isEqualTo(DIA);
    }

    // ------------------------------------------------------------------

    /** El avance que `caja` publica, compuesto de las formas que el contrato declara. */
    private static ObjectNode avanceBase() {
        ObjectNode cuerpo = JSON.createObjectNode();
        cuerpo.put("desde", DIA.toString());
        cuerpo.put("hasta", DIA.toString());
        cuerpo.put("aLaFecha", DIA.toString());
        cuerpo.set("cobrado", importe("3084.20", DIA.toString()));
        cuerpo.set("anulado", importe("612.00", DIA.toString()));
        return cuerpo;
    }

    private static CajaQueNoContesta cajaConElAvance() {
        ObjectNode cuerpo = avanceBase();
        // Las claves del avance son las del contrato, y no una copia escrita al lado: si el
        // contrato dejara de declarar una, esto se pondria rojo.
        assertThat(nombresDe(cuerpo))
                .as("el avance fabricado tiene que traer lo que el contrato declara")
                .containsAll(ContratoQueConsumeDeCaja.AVANCE_DEL_RANGO.keySet());
        return new CajaQueNoContesta(ruta -> cuerpo);
    }

    private static ObjectNode importe(String cuanto, String aLaFecha) {
        return JSON.createObjectNode().put("importe", cuanto).put("actualizadoA", aLaFecha);
    }

    /** Un cuerpo con las claves que el contrato declara, todas como texto vacio. */
    private static ObjectNode desdeElContrato(Map<String, Object> forma) {
        ObjectNode cuerpo = JSON.createObjectNode();
        forma.forEach(
                (campo, tipo) -> {
                    switch (String.valueOf(tipo)) {
                        case "entero" -> cuerpo.put(campo, 1L);
                        case "booleano" -> cuerpo.put(campo, false);
                        case "texto" -> cuerpo.put(campo, "");
                        default -> cuerpo.putArray(campo);
                    }
                });
        return cuerpo;
    }

    private static java.util.Set<String> nombresDe(JsonNode cuerpo) {
        return new java.util.TreeSet<>(cuerpo.propertyNames());
    }

    // ------------------------------------------------------------------
    //  El estado de un recibo por su IDENTIFICADOR (#40)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un recibo anulado se lee como anulado, y uno vigente como vigente")
    void elEstadoDelReciboSeLee() {
        assertThat(new AnulacionesDeReciboHttp(cajaConElRecibo(true)).estaAnulado(77)).isTrue();
        assertThat(new AnulacionesDeReciboHttp(cajaConElRecibo(false)).estaAnulado(77)).isFalse();
    }

    @Test
    @DisplayName("y un «anulado» que no esta NO se lee como false: falla nombrandolo (AC-2)")
    void elAnuladoAusenteNoSeLeeComoFalse() {
        // Es el sintoma mudo de C-1 en su forma mas cara: `asBoolean(false)` sobre un campo que
        // no esta no da error, da `false` — y `false` significa «ese recibo sigue vigente», que
        // es la respuesta que IMPIDE anular el convenio. Un campo renombrado del otro lado se
        // veria como una regla de negocio que empieza a rechazar siempre.
        ObjectNode cuerpo = JSON.createObjectNode().put("reciboId", 77L);
        cuerpo.put("estaAnulado", true);

        Throwable error =
                catchThrowable(
                        () ->
                                new AnulacionesDeReciboHttp(new CajaQueNoContesta(ruta -> cuerpo))
                                        .estaAnulado(77));

        assertThat(error)
                .as(
                        "leido con `asBoolean(false)` esto NO lanza: devuelve false, o sea «ese"
                                + " recibo sigue vigente». Un campo renombrado al otro lado de la"
                                + " frontera no se veria como un fallo sino como una regla de negocio"
                                + " que empieza a rechazar SIEMPRE, indistinguible de la de verdad")
                .isInstanceOf(ClienteHttpDeCaja.CajaInalcanzable.class)
                .hasMessageContaining("«anulado»")
                .hasMessageContaining("nada: el campo no esta");
    }

    @Test
    @DisplayName("un 404 NO es «no esta anulado»: es que ese recibo no consta (AC-2)")
    void elCuatrocientosCuatroNoEsFalse() {
        // Con `catchThrowable` y no con `assertThatThrownBy`: cuando NO se lanza nada, el
        // segundo revienta antes de aplicar el `.as(...)` y el rojo se queda en «Expecting code
        // to raise a throwable», que no dice que se rompio ni lo que cuesta. Medido con la
        // rotura puesta.
        Throwable error =
                catchThrowable(
                        () ->
                                new AnulacionesDeReciboHttp(
                                                CajaQueNoContesta.queContesta(404, "{}"))
                                        .estaAnulado(77));

        assertThat(error)
                .as(
                        "si esto devuelve «false» —que es lo que hace un Optional.empty() leido"
                                + " como dato—, «ese recibo sigue vigente» pasa a significar «caja no"
                                + " lo tiene», y CerrarConvenio anula el convenio con su cuota inicial"
                                + " cobrada y VIVA: dinero recibido por un acto que ya no existe. Es"
                                + " el hecho que esa guarda existe para impedir, producido por la"
                                + " guarda misma, y `caja` lo avisa por escrito en el javadoc de esa"
                                + " ruta")
                .isInstanceOf(AnulacionesDeRecibo.ReciboQueNoConsta.class)
                .hasMessageContaining("recibo 77")
                .hasMessageContaining("`caja` no tiene ningun recibo con ese identificador");
    }

    @Test
    @DisplayName("y una caja que no contesta sale por el PUERTO, no como el recibo que no consta")
    void laCajaCaidaSeDistingueDelReciboQueNoConsta() {
        assertThatThrownBy(
                        () ->
                                new AnulacionesDeReciboHttp(
                                                CajaQueNoContesta.queContesta(503, "{}"))
                                        .estaAnulado(77))
                .isInstanceOf(AnulacionesDeRecibo.CajaInalcanzable.class)
                .isNotInstanceOf(AnulacionesDeRecibo.ReciboQueNoConsta.class);
    }

    /** El contrato tiene que declarar las seis operaciones que estos adaptadores piden. */
    @Test
    @DisplayName("y el contrato declara las seis operaciones de esta frontera")
    void elContratoDeclaraLasSeis() {
        ContratoDelConsumidor contrato = new ContratoQueConsumeDeCaja().contrato();
        assertThat(contrato.operaciones().keySet())
                .containsExactlyInAnyOrder(
                        "GET /recaudacion/avance",
                        "GET /recibos/{numero}",
                        "GET /recibos/por-id/{reciboId}",
                        "GET /tasas/{codigo}/cobros/{numero}",
                        "GET /tasas/{codigo}/recaudacion",
                        "POST /ordenes-de-cobro");
    }

    /** El estado del recibo, compuesto de la forma que el contrato declara. */
    private static CajaQueNoContesta cajaConElRecibo(boolean anulado) {
        ObjectNode cuerpo = desdeElContrato(ContratoQueConsumeDeCaja.ESTADO_DEL_RECIBO);
        cuerpo.put("reciboId", 77L);
        cuerpo.put("anulado", anulado);
        // Las claves salen del contrato y no de una copia escrita al lado: si el contrato
        // dejara de declarar «anulado», esto se pondria rojo.
        assertThat(nombresDe(cuerpo))
                .containsAll(ContratoQueConsumeDeCaja.ESTADO_DEL_RECIBO.keySet());
        return new CajaQueNoContesta(ruta -> cuerpo);
    }
}

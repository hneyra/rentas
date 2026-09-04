package kamayuk.rentas.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.persistencia.OrdenSeguro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * RFC 9457 y, sobre todo, lo que <b>no</b> sale en la respuesta.
 *
 * <p>Un mensaje del motor devuelto tal cual —{@code duplicate key value violates unique constraint
 * "via_codigo_uq"}— dice como se llama la tabla, como se llama la restriccion y que columnas la
 * componen. Con veinte peticiones mal formadas se reconstruye buena parte del esquema, y con el
 * esquema se escriben inyecciones dirigidas en lugar de a ciegas.
 */
@DisplayName("Capa web — Errores en problem+json")
class ManejadorDeErroresTest {

    private final ManejadorDeErrores manejador = new ManejadorDeErrores();

    @Test
    @DisplayName("un error del motor no filtra la tabla, la restriccion ni el SQL")
    void unErrorDelMotorNoFiltraNada() {
        String mensajeDelMotor =
                "ERROR: duplicate key value violates unique constraint \"via_codigo_uq\"\n"
                        + "  Detail: Key (municipalidad_id, codigo)=(41, V-1) already exists.\n"
                        + "  Where: INSERT INTO via_de_prueba (municipalidad_id, codigo) VALUES ($1, $2)";

        ResponseEntity<ProblemDetail> respuesta =
                manejador.accesoADatos(new DataIntegrityViolationException(mensajeDelMotor));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

        String cuerpo = String.valueOf(respuesta.getBody());
        assertThat(cuerpo)
                .as("ni el nombre de la tabla, ni el de la restriccion, ni una linea de SQL")
                .doesNotContain("via_codigo_uq")
                .doesNotContain("INSERT")
                .doesNotContain("municipalidad_id")
                .doesNotContainIgnoringCase("duplicate key");

        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .containsEntry(ManejadorDeErrores.CAMPO_CODIGO, CodigoDeError.ERROR_INTERNO.name())
                .containsKey(ManejadorDeErrores.CAMPO_INCIDENCIA);
    }

    @Test
    @DisplayName("el identificador de incidencia permite diagnosticar sin filtrar")
    void elIdentificadorDeIncidenciaPermiteDiagnosticar() {
        ResponseEntity<ProblemDetail> primera =
                manejador.cualquierOtra(new IllegalStateException("algo"));
        ResponseEntity<ProblemDetail> segunda =
                manejador.cualquierOtra(new IllegalStateException("algo"));

        assertThat(primera.getBody()).isNotNull();
        assertThat(segunda.getBody()).isNotNull();
        assertThat(primera.getBody().getProperties().get(ManejadorDeErrores.CAMPO_INCIDENCIA))
                .as("uno por incidencia: es lo que se pide por telefono y se busca en el registro")
                .isNotEqualTo(
                        segunda.getBody().getProperties().get(ManejadorDeErrores.CAMPO_INCIDENCIA));
    }

    @Test
    @DisplayName("un problema de negocio sale con su codigo del catalogo y su estado")
    void unProblemaDeNegocioSaleConSuCodigo() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.problemaDeNegocio(
                        new ProblemaDeNegocio(
                                CodigoDeError.CONFLICTO,
                                "El recibo ya fue anulado",
                                List.of("Recibo 2026-000123")));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .containsEntry(ManejadorDeErrores.CAMPO_CODIGO, "CONFLICTO")
                .containsEntry(ManejadorDeErrores.CAMPO_MENSAJE, "El recibo ya fue anulado")
                .containsEntry(ManejadorDeErrores.CAMPO_DETALLES, List.of("Recibo 2026-000123"));
    }

    // ------------------------------------------------ #604: el discriminador de lo que falta

    @Test
    @DisplayName("#604 — el problema que trae la cifra sin publicar sale con su miembro")
    void elProblemaDeLaCifraSinPublicarSaleConSuMiembro() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.problemaDeNegocio(
                        new ProblemaDeNegocio(
                                CodigoDeError.VALIDACION,
                                "El conjunto sellado del ejercicio 2026 no tiene el parametro"
                                        + " INTERES_FRACCIONAMIENTO:ORDINARIO",
                                ParametroQueFalta.llave(
                                        2026, "INTERES_FRACCIONAMIENTO:ORDINARIO")));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .as("el ejercicio y la llave, en el orden en que se componen")
                .containsEntry(
                        ManejadorDeErrores.CAMPO_PARAMETRO_QUE_FALTA,
                        Map.of("ejercicio", 2026, "llave", "INTERES_FRACCIONAMIENTO:ORDINARIO"));
    }

    @Test
    @DisplayName("#604 — sin conjunto sellado no hay llave, y el miembro NO la inventa")
    void sinConjuntoSelladoElMiembroNoInventaLlave() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.problemaDeNegocio(
                        new ProblemaDeNegocio(
                                CodigoDeError.VALIDACION,
                                "El ejercicio 2027 no tiene un conjunto de parametros sellado",
                                ParametroQueFalta.conjuntoDelEjercicio(2027)));

        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .as(
                        "una llave nula seria un valor presente: quien pregunte por ella la vera,"
                                + " y lo que hay que decir es que ahi no hay ninguna")
                .containsEntry(
                        ManejadorDeErrores.CAMPO_PARAMETRO_QUE_FALTA, Map.of("ejercicio", 2027));
    }

    @Test
    @DisplayName("#604 — CONTRASTE: un problema corriente sigue sin el miembro")
    void unProblemaCorrienteSigueSinElMiembro() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.problemaDeNegocio(
                        new ProblemaDeNegocio(
                                CodigoDeError.VALIDACION, "Falta el campo 'nroDeCuotas'"));

        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .as("el miembro solo significa algo mientras no salga en todos")
                .doesNotContainKey(ManejadorDeErrores.CAMPO_PARAMETRO_QUE_FALTA);
    }

    @Test
    @DisplayName("#604 — y las dos formas de siempre de construirlo siguen sin llevarlo")
    void lasDosFormasDeSiempreSiguenIguales() {
        ResponseEntity<ProblemDetail> conDetalles =
                manejador.problemaDeNegocio(
                        new ProblemaDeNegocio(
                                CodigoDeError.CONFLICTO,
                                "El recibo ya fue anulado",
                                List.of("Recibo 2026-000123")));

        assertThat(conDetalles.getBody()).isNotNull();
        assertThat(conDetalles.getBody().getProperties())
                .as("el mecanismo es aditivo: 675 usos de los dos constructores no cambian")
                .containsEntry(ManejadorDeErrores.CAMPO_DETALLES, List.of("Recibo 2026-000123"))
                .doesNotContainKey(ManejadorDeErrores.CAMPO_PARAMETRO_QUE_FALTA);
    }

    @Test
    @DisplayName("un orden no admitido es 422 y dice que campo se pidio, no que columnas hay")
    void unOrdenNoAdmitidoEs422() {
        OrdenSeguro orden = OrdenSeguro.sobre("codigo", "nombre");
        OrdenSeguro.OrdenNoAdmitido error = null;
        try {
            orden.clausula(Paginacion.de(0, 10, "(SELECT 1)"));
        } catch (OrdenSeguro.OrdenNoAdmitido e) {
            error = e;
        }

        assertThat(error).isNotNull();
        ResponseEntity<ProblemDetail> respuesta = manejador.ordenNoAdmitido(error);

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .containsEntry(ManejadorDeErrores.CAMPO_CODIGO, "ORDEN_NO_ADMITIDO");
    }

    @Test
    @DisplayName("la validacion de un objeto de valor llega al usuario, porque habla del dato")
    void laValidacionDeUnObjetoDeValorLlegaAlUsuario() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.validacion(
                        new IllegalArgumentException(
                                "El codigo de referencia catastral debe tener 23 posiciones"));

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getDetail())
                .as("este mensaje lo escribimos nosotros y habla del dato, no del esquema")
                .contains("23 posiciones");
    }

    @Test
    @DisplayName("una ruta que ningun controlador mapea es 404, no una incidencia 500")
    void unaRutaSinControladorEs404() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.rutaNoEncontrada(
                        new NoResourceFoundException(
                                HttpMethod.GET,
                                "/rentas/api/v1/indicadores/recaudacion",
                                "No static resource api/v1/indicadores/recaudacion"));

        assertThat(respuesta.getStatusCode())
                .as("un endpoint del contrato aun sin implementar no es un error del servidor")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getProperties())
                .containsEntry(ManejadorDeErrores.CAMPO_CODIGO, CodigoDeError.NO_ENCONTRADO.name())
                .as("no es una incidencia: no lleva identificador que buscar en el registro")
                .doesNotContainKey(ManejadorDeErrores.CAMPO_INCIDENCIA);
    }

    @Test
    @DisplayName("una excepcion sin mensaje no deja un null en la respuesta")
    void unaExcepcionSinMensajeNoDejaNull() {
        ResponseEntity<ProblemDetail> respuesta =
                manejador.validacion(new IllegalArgumentException());

        assertThat(respuesta.getBody()).isNotNull();
        assertThat(respuesta.getBody().getDetail()).isEqualTo(CodigoDeError.VALIDACION.mensaje());
    }
}

package kamayuk.rentas.seguridad.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.seguridad.dominio.RegistroAuditado;
import kamayuk.rentas.seguridad.dominio.Respaldo;
import kamayuk.rentas.seguridad.dominio.Sesion;
import kamayuk.rentas.web.ConfiguracionDeJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cada hora que publica el modulo de seguridad sale con <b>el desfase</b> de la zona del producto,
 * campo por campo (#317).
 *
 * <h2>Por que no bastaba con el tipo</h2>
 *
 * <p>{@code NingunaHoraSePublicaSinSuDesfaseTest} (#188) prohibe que un {@code Resource} declare un
 * {@code Instant}. Mira el <b>tipo</b>, y el tipo no basta: {@code
 * sesion.inicio().atOffset(ZoneOffset.UTC)} en lugar de {@code ZonaHoraria.conSuDesfase(...)} deja
 * el campo como {@code OffsetDateTime} —el tipo correcto— y lo publica con {@code Z}. Medido en
 * #317: con esa mutacion en {@code SesionResource.de}, {@code :kamayuk-rentas-seguridad:test} y la
 * guarda de #188 salian <b>verdes</b> (142 y 4 pruebas, 0 fallos). De las diez horas que publica la
 * API, cinco ya tenian una prueba que afirmaba el {@code -05:00} (el panel, el trabajo parado, el
 * historial de placas, los dos del pago y la restauracion verificada); <b>las cuatro de aqui no la
 * tenian</b>: el inicio de la sesion, la fecha de la bitacora y el inicio y el fin de un respaldo.
 *
 * <h2>La siembra que distingue</h2>
 *
 * <p>Las 20:00 del 4 de marzo en el Peru, que en UTC es la 01:00 <b>del dia 5</b>. Una hora de la
 * mañana saldria con el mismo dia en las dos zonas y la prueba solo veria las cinco horas; esta
 * cambia tambien <b>el dia</b>, que es lo que la bitacora enseña. Y se escribe en UTC, no compuesta
 * con {@code ZonaHoraria}: una muestra que se compusiera con la misma constante que verifica no
 * verificaria nada.
 *
 * <p>Se serializa con el mismo {@link JsonMapper} que monta la aplicacion y se lee el texto: lo que
 * importa es lo que viaja, no el record.
 */
@DisplayName("#317 — Las horas de seguridad salen con su desfase, campo por campo")
class LasHorasDeSeguridadLlevanSuDesfaseTest {

    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .addModule(new ConfiguracionDeJson().moduloDeObjetosDeValor())
                    .build();

    /** Las 20:00 del 4 de marzo en el Peru. En UTC ya es el dia 5. */
    private static final Instant LAS_OCHO_DE_LA_NOCHE_DEL_4 = Instant.parse("2026-03-05T01:00:00Z");

    /** Lo que tiene que viajar: el dia 4, las 20:00 y el desfase a la vista. */
    private static final String EN_LA_HORA_DE_AQUI = "2026-03-04T20:00:00-05:00";

    /** Una hora que no es la de arriba, para que cada campo del respaldo se lea por separado. */
    private static final Instant LAS_ONCE_Y_MEDIA_DEL_4 = Instant.parse("2026-03-05T04:30:00Z");

    private static final String LAS_ONCE_Y_MEDIA_DE_AQUI = "2026-03-04T23:30:00-05:00";

    @Test
    @DisplayName("PUT /seguridad/sesion/ejercicio → «inicio»")
    void elInicioDeLaSesion() {
        Sesion sesion = new Sesion(1L, 7L, LAS_OCHO_DE_LA_NOCHE_DEL_4, null, null, null, null);

        assertThat(texto(SesionController.SesionResource.de(sesion), "inicio"))
                .as(
                        "la sesion abierta a las 20:00 del 4. Con atOffset(UTC) salia"
                                + " «2026-03-05T01:00:00Z»: otro dia y sin el desfase")
                .isEqualTo(EN_LA_HORA_DE_AQUI);
    }

    @Test
    @DisplayName("GET /seguridad/auditoria → «fecha»")
    void laFechaDeLaBitacora() {
        RegistroAuditado registro =
                new RegistroAuditado(
                        11L,
                        new Ejercicio(2026),
                        "recibo",
                        "R-0001",
                        "ANULACION",
                        "jcardenas",
                        null,
                        null,
                        LAS_OCHO_DE_LA_NOCHE_DEL_4,
                        "Recibo emitido por error",
                        null,
                        null);

        assertThat(texto(SesionController.AuditoriaResource.de(registro), "fecha"))
                .as(
                        "el recibo anulado a las 20:00 del 4 es la pregunta que esta bitacora"
                                + " contesta (#188); con atOffset(UTC) constaba el 5 a la 01:00")
                .isEqualTo(EN_LA_HORA_DE_AQUI);
    }

    @Test
    @DisplayName("POST /seguridad/respaldos → «inicio», «fin» y «ultimaRestauracionVerificada»")
    void lasTresHorasDelRespaldo() {
        Respaldo respaldo =
                new Respaldo(
                        3L,
                        LAS_OCHO_DE_LA_NOCHE_DEL_4,
                        LAS_ONCE_Y_MEDIA_DEL_4,
                        "EXITOSO",
                        "respaldos/2026-03-04",
                        1024L,
                        null,
                        LAS_ONCE_Y_MEDIA_DEL_4,
                        "verificador");

        SesionController.RespaldoResource publicado =
                SesionController.RespaldoResource.de(respaldo);

        assertThat(texto(publicado, "inicio")).isEqualTo(EN_LA_HORA_DE_AQUI);
        assertThat(texto(publicado, "fin")).isEqualTo(LAS_ONCE_Y_MEDIA_DE_AQUI);
        assertThat(texto(publicado, "ultimaRestauracionVerificada"))
                .isEqualTo(LAS_ONCE_Y_MEDIA_DE_AQUI);
    }

    private static String texto(Object recurso, String campo) {
        JsonNode publicado = JSON.readTree(JSON.writeValueAsString(recurso));
        JsonNode valor = publicado.get(campo);
        assertThat(valor).as("el campo «%s» tiene que viajar", campo).isNotNull();
        return valor.asString();
    }
}

package kamayuk.rentas.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que contesto el otro sistema, leido de verdad y sin llevarse un secreto por delante (#66,
 * #166).
 */
@DisplayName("Lo que contesto el otro sistema")
class RespuestaAjenaTest {

    /** El tope de {@link RespuestaAjena}, para no repetir el numero. */
    private static final int TOPE = RespuestaAjena.TOPE;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** El cuerpo literal que `identidad` contesto en `stg` el 2026-09-11 (#66). */
    private static final String LA_CUENTA_NO_ESTA_DADA_DE_ALTA =
            "{\"detail\":\"La cuenta «1c5e82f7-a3da-4ad2-9a5a-82b48deea3ca» no esta dada de alta"
                    + " en este sistema. No es que le falte un privilegio: no tiene ninguna ficha"
                    + " aqui\",\"codigo\":\"SIN_PRIVILEGIO\"}";

    @Test
    @DisplayName("un problem+json: se leen su «codigo» y su «detail», y los dos viajan")
    void elCodigoYElDetalle() {
        RespuestaAjena contesto = RespuestaAjena.de(JSON, LA_CUENTA_NO_ESTA_DADA_DE_ALTA);

        assertThat(contesto.codigo()).isEqualTo("SIN_PRIVILEGIO");
        assertThat(contesto.detalle()).contains("no esta dada de alta");
        assertThat(contesto.sinDecirPorQue()).isFalse();
        assertThat(contesto.dice("NO ESTA DADA DE ALTA"))
                .as("[se busca sin mirar mayusculas: el emisor escribe en castellano, no en clave]")
                .isTrue();
        assertThat(contesto.comoTexto())
                .contains("SIN_PRIVILEGIO")
                .contains("1c5e82f7-a3da-4ad2-9a5a-82b48deea3ca");
    }

    @Test
    @DisplayName("un cuerpo que no es JSON no se pierde: no hay codigo, pero se dice que mando")
    void loQueNoEsJson() {
        RespuestaAjena contesto =
                RespuestaAjena.de(JSON, "<html><title>503 Service Unavailable</title>");

        assertThat(contesto.codigo()).isEmpty();
        assertThat(contesto.sinDecirPorQue()).isTrue();
        assertThat(contesto.comoTexto())
                .as("[un HTML delante es un proxy contestando por el, y eso hay que poder verlo]")
                .contains("503 Service Unavailable");
    }

    @Test
    @DisplayName("un cuerpo vacio lo DICE, en vez de dejar la frase a medias")
    void elCuerpoVacio() {
        RespuestaAjena contesto = RespuestaAjena.de(JSON, "");

        assertThat(contesto.sinDecirPorQue()).isTrue();
        assertThat(contesto.cuerpo()).isEmpty();
        assertThat(contesto.comoTexto()).contains("VACIO");
    }

    /**
     * <b>La afirmacion que no se puede romper</b>: incluir el cuerpo no puede filtrar el token.
     *
     * <p>El {@code problem+json} de los cuatro sistemas no lleva secretos, pero no es lo unico que
     * puede contestar: delante hay un Traefik, y una pagina de error que devuelva el eco de la
     * peticion trae dentro la cabecera {@code Authorization} — que es el token de servicio de esta
     * municipalidad, y el mensaje acaba en un registro.
     */
    @Test
    @DisplayName("un eco de la peticion NO se lleva el token ni la clave al mensaje")
    void ningunSecretoViaja() {
        String elEcoDeUnProxy =
                "{\"error\":\"rejected\",\"request\":{\"headers\":{\"Authorization\":\"Bearer"
                        + " eyJhbGciOiJSUzI1NiJ9.ELCUERPODELTOKEN.LAFIRMA\"}},"
                        + "\"client_secret\":\"la-clave-del-cliente\","
                        + "\"clave\":\"la-otra-clave\"}";

        RespuestaAjena contesto = RespuestaAjena.de(JSON, elEcoDeUnProxy);

        assertThat(contesto.cuerpo())
                .as("[esto acaba en un log, y un token de servicio en un log es un incidente]")
                .doesNotContain("ELCUERPODELTOKEN")
                .doesNotContain("LAFIRMA")
                .doesNotContain("la-clave-del-cliente")
                .doesNotContain("la-otra-clave");
        assertThat(contesto.cuerpo())
                .as(
                        "[y se ve QUE se tacho: un cuerpo mutilado en silencio no se distingue de uno"
                                + " que de verdad no traia nada]")
                .contains("…")
                .contains("rejected");
    }

    @Test
    @DisplayName("un esquema que no es Bearer se tacha igual: no se lee la lista de esquemas")
    void tambienLoQueNoEsBearer() {
        RespuestaAjena contesto =
                RespuestaAjena.de(
                        JSON, "Authorization: Digest username=\"x\", response=\"SECRETO\"");

        assertThat(contesto.cuerpo()).doesNotContain("SECRETO");
    }

    @DisplayName("se recorta al tope y en UNA linea: un HTML de 60 kB no se lleva el registro")
    void elTopeYLaLinea() {
        String enorme = "{\"detail\":\"" + "a".repeat(RespuestaAjena.TOPE * 3) + "\"}";

        RespuestaAjena contesto = RespuestaAjena.de(JSON, enorme);

        assertThat(contesto.cuerpo()).hasSize(RespuestaAjena.TOPE + 1).endsWith("…");
        assertThat(contesto.detalle()).hasSize(RespuestaAjena.TOPE + 1);

        RespuestaAjena conSaltos = RespuestaAjena.de(JSON, "no\nes\r\njson\n  ni de lejos");
        assertThat(conSaltos.cuerpo())
                .as("[una respuesta de varias lineas tiene que seguir siendo UNA linea del log]")
                .isEqualTo("no es json ni de lejos");
    }

    @Test
    @DisplayName("«dice» mira el codigo y el detalle, y NO el eco de la peticion")
    void diceNoMiraElCuerpoEntero() {
        RespuestaAjena contesto =
                RespuestaAjena.de(
                        JSON,
                        "{\"codigo\":\"SIN_MUNICIPALIDAD\",\"detail\":\"El token no identifica una"
                                + " municipalidad\",\"peticion\":\"...no esta dada de alta...\"}");

        assertThat(contesto.dice("SIN_MUNICIPALIDAD")).isTrue();
        assertThat(contesto.dice("no esta dada de alta"))
                .as("[si «dice» mirara el cuerpo entero, un eco elegiria la rama por el emisor]")
                .isFalse();
    }
}

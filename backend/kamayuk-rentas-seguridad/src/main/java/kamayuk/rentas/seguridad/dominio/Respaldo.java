package kamayuk.rentas.seguridad.dominio;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * El estado de una copia de seguridad (RF-126).
 *
 * <p>La aplicacion <b>no hace copias y no debe poder hacerlas</b>: se conecta como {@code
 * sgtm_app}, que no tiene DDL ni es superusuario (ARQ-03 §4). Quien las hace es el proceso de
 * despliegue y quien escribe esta tabla es {@code sgtm_owner}. Aqui solo se lee.
 *
 * <p>{@code ultimaRestauracionVerificada} es la columna que la pantalla existe para enseñar (#558):
 * una copia sin restauracion probada no es una copia (RNF-079). <b>Nulo significa «nunca se
 * probo»</b>, nunca «hoy» y nunca «no hace falta»; el dia que valga algo lo habra escrito el
 * simulacro de restauracion (INF-08 §5), que es el unico proceso que restaura de verdad.
 */
public record Respaldo(
        long id,
        Instant inicio,
        @Nullable Instant fin,
        String resultado,
        String destino,
        @Nullable Long tamanoBytes,
        @Nullable String detalle,
        @Nullable Instant ultimaRestauracionVerificada,
        @Nullable String ultimaRestauracionVerificadaPor) {

    public boolean enCurso() {
        return "EN_CURSO".equals(resultado);
    }

    public boolean exitoso() {
        return "EXITOSO".equals(resultado);
    }

    /**
     * Si esta copia se restauro alguna vez y se comprobo lo restaurado.
     *
     * <p>No se deriva de {@link #exitoso()}: una copia se toma bien y no se prueba nunca, que es el
     * estado de casi todas. Confundir las dos preguntas es lo que este dato existe para impedir.
     */
    public boolean restauracionVerificada() {
        return ultimaRestauracionVerificada != null;
    }
}

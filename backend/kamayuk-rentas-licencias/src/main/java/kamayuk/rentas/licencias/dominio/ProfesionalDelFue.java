package kamayuk.rentas.licencias.dominio;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Un profesional que firma el FUE: un proyectista o el responsable de obra (#48, RF-113).
 *
 * @param id nulo mientras no se haya guardado
 * @param fueId el expediente al que pertenece
 * @param version la version de la seccion de profesionales
 * @param tipo su papel en el expediente
 * @param nombre su nombre
 * @param colegio el colegio profesional, {@code CAP} o {@code CIP}; con la colegiatura o ninguno
 * @param colegiatura el numero de colegiatura, con el que se verifica su habilitacion
 */
public record ProfesionalDelFue(
        @Nullable Long id,
        long fueId,
        int version,
        TipoDeProfesional tipo,
        String nombre,
        @Nullable String colegio,
        @Nullable String colegiatura) {

    public ProfesionalDelFue {
        Objects.requireNonNull(tipo, "El profesional tiene un papel en el expediente");
        Objects.requireNonNull(nombre, "El profesional tiene nombre");

        nombre = nombre.strip();
        colegio = vacioEsNulo(colegio);
        colegiatura = vacioEsNulo(colegiatura);

        if (nombre.isEmpty()) {
            throw new IllegalArgumentException("El nombre del profesional no puede estar vacio");
        }
        if (version < 1) {
            throw new IllegalArgumentException(
                    "La primera version de una seccion es la 1; llego " + version);
        }
        if ((colegio == null) != (colegiatura == null)) {
            throw new IllegalArgumentException(
                    "El colegio y la colegiatura van juntos o no va ninguno: un numero sin colegio"
                            + " no se puede verificar, y el colegio sin numero tampoco");
        }
        if (colegio != null) {
            colegio = colegio.toUpperCase(java.util.Locale.ROOT);
            if (!"CAP".equals(colegio) && !"CIP".equals(colegio)) {
                throw new IllegalArgumentException(
                        "El colegio profesional va entre CAP y CIP: '" + colegio + "'");
            }
        }
    }

    private static @Nullable String vacioEsNulo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        String limpio = texto.strip();
        return limpio.isEmpty() ? null : limpio;
    }
}

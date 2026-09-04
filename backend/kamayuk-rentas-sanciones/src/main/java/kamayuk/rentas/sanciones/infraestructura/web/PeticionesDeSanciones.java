package kamayuk.rentas.sanciones.infraestructura.web;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;

/**
 * Las conversiones que los cuatro controladores de #50 comparten: la observación obligatoria, las
 * fechas, los enumerados y los campos que no pueden faltar.
 *
 * <p>Vive aparte porque la alternativa es repetirlas cuatro veces, y la primera copia que alguien
 * toque dejará de exigir la observación en uno solo de los cuatro caminos —que es exactamente el
 * defecto que la regla 10 existe para impedir, y el que menos se nota—.
 *
 * <p>Todo lo que falla aquí es <b>422</b>, no 500: lo mandó mal el cliente, y el mensaje dice qué.
 */
final class PeticionesDeSanciones {

    private PeticionesDeSanciones() {}

    /** La observación del usuario. Sin ella no se guarda (regla 10, RNF-052). */
    static Observacion observacionDe(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "Toda modificacion exige la observacion del usuario: sin ella no se guarda");
        }
        try {
            return Observacion.de(texto);
        } catch (IllegalArgumentException invalida) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(invalida));
        }
    }

    static String exigir(@Nullable String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new ProblemaDeNegocio(CodigoDeError.VALIDACION, "Falta el campo '" + campo + "'");
        }
        return valor.strip();
    }

    static LocalDate fechaDe(@Nullable String texto, String campo) {
        return fechaOpcional(exigir(texto, campo), campo);
    }

    static LocalDate fechaOpcional(@Nullable String texto, String campo) {
        try {
            return LocalDate.parse(exigir(texto, campo));
        } catch (DateTimeParseException malFormada) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '" + campo + "' va en formato AAAA-MM-DD: '" + texto + "'");
        }
    }

    static @Nullable LocalDate fechaSiViene(@Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return fechaOpcional(texto, campo);
    }

    /** Un enumerado que el cliente manda por su nombre. */
    static <T extends Enum<T>> T enumeradoDe(Class<T> tipo, @Nullable String texto, String campo) {
        String limpio = exigir(texto, campo);
        try {
            return Enum.valueOf(tipo, limpio.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException noExiste) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.VALIDACION,
                    "El campo '"
                            + campo
                            + "' admite "
                            + java.util.Arrays.toString(tipo.getEnumConstants())
                            + ": llego '"
                            + texto
                            + "'");
        }
    }

    static <T extends Enum<T>> @Nullable T enumeradoSiViene(
            Class<T> tipo, @Nullable String texto, String campo) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return enumeradoDe(tipo, texto, campo);
    }

    static @Nullable String vacioEsNulo(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        return texto.strip();
    }

    static String mensajeDe(RuntimeException excepcion) {
        String mensaje = excepcion.getMessage();
        return mensaje == null ? "El valor recibido no es valido" : mensaje;
    }

    /** Traduce a 422 lo que el dominio rechaza por mal formado. */
    static ProblemaDeNegocio invalido(RuntimeException causa) {
        return new ProblemaDeNegocio(CodigoDeError.VALIDACION, mensajeDe(causa));
    }
}

package kamayuk.rentas.contribuyentes.dominio;

import java.time.LocalDate;
import java.util.Objects;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import org.jspecify.annotations.Nullable;

/**
 * El sujeto de la obligacion tributaria, con el <b>codigo unico</b> del manual (cap. 2 §Registro de
 * Contribuyentes).
 *
 * <p>Ese codigo es la clave con la que se enlazan predios, vehiculos, papeletas, licencias y la
 * cuenta corriente. Por eso {@code contribuyentes} no referencia a ningun otro contexto y todos lo
 * referencian a el (ARQ-01 §3.1): es el unico que puede ir primero.
 *
 * <p>{@code municipalidadId} no aparece aqui ni en el repositorio (regla 2). Que dos
 * municipalidades usen el mismo codigo no es un choque: son dos padrones distintos, y la unicidad
 * de la tabla es por {@code (municipalidad_id, codigo_contribuyente)}.
 *
 * <p><b>No hay ninguna regla de calculo aqui.</b> {@link CondicionEspecial} se registra, no se
 * aplica: cuanto deduce un pensionista es D-02a.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param conyugeId el otro miembro de la sociedad conyugal, si la hay
 */
public record Contribuyente(
        @Nullable Long id,
        CodigoContribuyente codigo,
        DocumentoIdentidad documento,
        TipoPersona tipoPersona,
        String nombreRazonSocial,
        @Nullable CondicionEspecial condicionEspecial,
        @Nullable LocalDate fechaNacimiento,
        @Nullable String estadoCivil,
        @Nullable Long conyugeId,
        boolean activo) {

    private static final int NOMBRE_MAXIMO = 240;
    private static final int ESTADO_CIVIL_MAXIMO = 20;

    public Contribuyente {
        Objects.requireNonNull(codigo, "El contribuyente necesita su codigo unico");
        Objects.requireNonNull(documento, "El contribuyente necesita su documento de identidad");
        Objects.requireNonNull(tipoPersona, "El contribuyente necesita su tipo de persona");
        Objects.requireNonNull(nombreRazonSocial, "El contribuyente necesita su nombre");

        nombreRazonSocial = nombreRazonSocial.strip();
        if (nombreRazonSocial.isEmpty() || nombreRazonSocial.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException(
                    "El nombre o razon social va de 1 a "
                            + NOMBRE_MAXIMO
                            + " caracteres; se recibieron "
                            + nombreRazonSocial.length());
        }
        if (estadoCivil != null) {
            estadoCivil = estadoCivil.strip();
            if (estadoCivil.length() > ESTADO_CIVIL_MAXIMO) {
                throw new IllegalArgumentException(
                        "El estado civil excede " + ESTADO_CIVIL_MAXIMO + " caracteres");
            }
        }
        // Una empresa no nace ni se casa. Guardarselo seria admitir un dato que despues
        // alguien lee como si significara algo.
        if (tipoPersona.esJuridica() && fechaNacimiento != null) {
            throw new IllegalArgumentException(
                    "Una persona juridica no tiene fecha de nacimiento; para una empresa la fecha"
                            + " que importa es la de constitucion, y va en otro campo");
        }
        if (tipoPersona.esJuridica() && condicionEspecial != null) {
            throw new IllegalArgumentException(
                    "Una persona juridica no puede ser pensionista, adulto mayor ni tener"
                            + " discapacidad: esas condiciones son de una persona natural");
        }
    }

    /** Un contribuyente que todavia no esta en la base. */
    public static Contribuyente nuevo(
            CodigoContribuyente codigo,
            DocumentoIdentidad documento,
            TipoPersona tipoPersona,
            String nombreRazonSocial) {
        return new Contribuyente(
                null,
                codigo,
                documento,
                tipoPersona,
                nombreRazonSocial,
                null,
                null,
                null,
                null,
                true);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /**
     * Dar de baja, nunca borrar (RNF-051): su codigo aparece en recibos ya emitidos y en asientos
     * del libro que no se tocan.
     */
    public Contribuyente dadoDeBaja() {
        return new Contribuyente(
                id,
                codigo,
                documento,
                tipoPersona,
                nombreRazonSocial,
                condicionEspecial,
                fechaNacimiento,
                estadoCivil,
                conyugeId,
                false);
    }

    public Contribuyente conNombre(String otroNombre) {
        return new Contribuyente(
                id,
                codigo,
                documento,
                tipoPersona,
                otroNombre,
                condicionEspecial,
                fechaNacimiento,
                estadoCivil,
                conyugeId,
                activo);
    }

    public Contribuyente conCondicion(@Nullable CondicionEspecial otraCondicion) {
        return new Contribuyente(
                id,
                codigo,
                documento,
                tipoPersona,
                nombreRazonSocial,
                otraCondicion,
                fechaNacimiento,
                estadoCivil,
                conyugeId,
                activo);
    }
}

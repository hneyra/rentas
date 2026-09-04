package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * El criterio de una generacion masiva de valores, congelado al registrarse (V27, {@code
 * valor_masivo}, RF-091, #38).
 *
 * <p><b>La primera de las tres etapas que el manual describe</b> -criterio, generacion e impresion,
 * no un boton-. Este tipo es exactamente eso: la etapa "criterio" hecha persistente, sin ningun
 * valor todavia emitido. {@link EstadoDeItemMasivo#PENDIENTE} en cada {@link ValorMasivoItem} de
 * esta corrida es lo que la etapa "generacion" va a resolver, y no antes.
 *
 * <p><b>{@code fechaCriterio} no es "hoy".</b> Es la fecha a la que se evalua la deuda de cada
 * candidato (RNF-075), fijada una vez al registrar la corrida. Si la generacion se interrumpe y se
 * reanuda dias despues, sigue evaluando esa misma fecha -nunca la del dia en que se reanuda-: de lo
 * contrario, dos corridas de la misma corrida podrian ver deuda distinta para el mismo
 * contribuyente segun cuando se ejecuten, y "sin duplicar valores ni saltar correlativos" (AC de
 * #38) dejaria de ser una garantia y pasaria a ser una coincidencia.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param tipo OP o RD; una corrida masiva formaliza un solo tipo (RM no aplica: una multa nace de
 *     un acta, no de un padron de deuda)
 * @param tributo el tributo que filtra los candidatos; {@code null} significa todos
 * @param ejercicioDesde el primer ejercicio que entra en la evaluacion
 * @param ejercicioHasta el ultimo ejercicio que entra en la evaluacion; nunca antes de {@code
 *     ejercicioDesde}
 * @param fechaCriterio a que fecha se evalua la deuda de cada candidato (RNF-075)
 * @param origen si la lista de candidatos vino de una seleccion o de un archivo importado
 * @param totalCandidatos cuantos contribuyentes entraron a la corrida
 * @param usuarioRegistro quien registro el criterio
 * @param fechaRegistro cuando se registro
 * @param observacion por que se registra esta corrida (regla 10)
 */
public record ValorMasivo(
        @Nullable Long id,
        TipoValor tipo,
        @Nullable String tributo,
        Ejercicio ejercicioDesde,
        Ejercicio ejercicioHasta,
        LocalDate fechaCriterio,
        OrigenDeCriterio origen,
        int totalCandidatos,
        @Nullable String usuarioRegistro,
        @Nullable OffsetDateTime fechaRegistro,
        Observacion observacion) {

    /** El ancho de {@code tributo varchar(20)}. */
    private static final int TRIBUTO_MAXIMO = 20;

    public ValorMasivo {
        Objects.requireNonNull(tipo, "La corrida necesita el tipo de valor que emite");
        if (tipo == TipoValor.RESOLUCION_DE_MULTA) {
            // Coincide con el prototipo del manual (pantalla valores_masivo): su
            // "Tipo de valor" solo ofrece OP y RD. Una multa nace de un acta de
            // fiscalizacion puntual, no de recorrer un padron de deuda vencida.
            throw new IllegalArgumentException(
                    "La generacion masiva emite OP o RD; una RM nace de un acta, no de un padron de"
                            + " deuda");
        }
        if (tributo != null) {
            tributo = tributo.strip().toUpperCase(java.util.Locale.ROOT);
            if (tributo.isEmpty()) {
                tributo = null;
            } else if (tributo.length() > TRIBUTO_MAXIMO) {
                throw new IllegalArgumentException(
                        "El tributo va de 1 a "
                                + TRIBUTO_MAXIMO
                                + " caracteres: '"
                                + tributo
                                + "'");
            }
        }
        Objects.requireNonNull(ejercicioDesde, "La corrida necesita el ejercicio desde");
        Objects.requireNonNull(ejercicioHasta, "La corrida necesita el ejercicio hasta");
        if (ejercicioDesde.compareTo(ejercicioHasta) > 0) {
            throw new IllegalArgumentException(
                    "El ejercicio desde ("
                            + ejercicioDesde
                            + ") no puede ser posterior al hasta ("
                            + ejercicioHasta
                            + ")");
        }
        Objects.requireNonNull(fechaCriterio, "Toda cifra de deuda indica su fecha (RNF-075)");
        Objects.requireNonNull(origen, "La corrida necesita saber de donde salio su lista");
        if (totalCandidatos < 0) {
            throw new IllegalArgumentException("El total de candidatos no puede ser negativo");
        }
        Objects.requireNonNull(
                observacion, "Toda corrida masiva exige la observacion del usuario (regla 10)");
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Si la obligacion coincide con el filtro de tributo de esta corrida. */
    public boolean coincideTributo(String tributoDeLaObligacion) {
        return tributo == null || tributo.equalsIgnoreCase(tributoDeLaObligacion);
    }

    /** Si el ejercicio de la obligacion cae dentro del rango de esta corrida. */
    public boolean coincideEjercicio(Ejercicio ejercicioDeLaObligacion) {
        return ejercicioDeLaObligacion.compareTo(ejercicioDesde) >= 0
                && ejercicioDeLaObligacion.compareTo(ejercicioHasta) <= 0;
    }
}

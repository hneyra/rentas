package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import org.jspecify.annotations.Nullable;

/**
 * La declaracion de prescripcion de la accion de cobro (V28, #39, RF-094).
 *
 * <h2>No borra deuda: la marca</h2>
 *
 * <p>Declarar la prescripcion no toca el libro de asientos —no hay una sola sentencia contra {@code
 * cuenta_corriente_asiento} en todo este camino— ni borra ninguna fila (regla 4). Lo que hace es
 * dejar este acto y mover a {@link EstadoDeValor#PRESCRITO} los valores alcanzados. La deuda sigue
 * asentada, y el dia que alguien pregunte por que dejo de cobrarse, la respuesta es esta fila con
 * su computo y sus hechos.
 *
 * <h2>Por ejercicio, no por rango</h2>
 *
 * <p>La solicitud pide un rango, pero el computo se resuelve ejercicio por ejercicio: por eso
 * {@link #ejercicios} es una lista y {@link #resultado} puede ser {@link
 * ResultadoDeLaSolicitud#PROCEDE_EN_PARTE}.
 *
 * @param id nulo mientras no se ha guardado
 * @param contribuyenteId quien solicita
 * @param tributo sobre que tributo
 * @param ejercicioDesde primero del rango solicitado
 * @param ejercicioHasta ultimo del rango solicitado
 * @param fechaPresentacion cuando se presento; es la fecha a la que se resuelve el computo, no
 *     "hoy"
 * @param causal cual de los plazos del art. 43 aplica
 * @param plazo el plazo leido del conjunto sellado; jamas una constante (regla 5)
 * @param conjuntoId de que conjunto sellado salio
 * @param resultado como se resolvio el rango
 * @param resolucion el numero de la resolucion que la declara, si ya se emitio
 * @param ejercicios el computo de cada ejercicio solicitado
 * @param hechos las interrupciones y suspensiones alegadas
 * @param usuarioRegistro quien la registro; nulo mientras no se ha guardado
 * @param observacion por que se declara (regla 10)
 */
public record Prescripcion(
        @Nullable Long id,
        long contribuyenteId,
        String tributo,
        Ejercicio ejercicioDesde,
        Ejercicio ejercicioHasta,
        LocalDate fechaPresentacion,
        CausalDePrescripcion causal,
        Plazo plazo,
        long conjuntoId,
        ResultadoDeLaSolicitud resultado,
        @Nullable String resolucion,
        List<ComputoDeEjercicio> ejercicios,
        List<HechoDelComputo> hechos,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    private static final int TRIBUTO_MAXIMO = 20;
    private static final int RESOLUCION_MAXIMA = 40;

    public Prescripcion {
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Una prescripcion se declara a alguien: el identificador debe ser positivo");
        }
        Objects.requireNonNull(tributo, "La solicitud necesita el tributo");
        tributo = tributo.strip();
        if (tributo.isEmpty() || tributo.length() > TRIBUTO_MAXIMO) {
            throw new IllegalArgumentException(
                    "El tributo va de 1 a " + TRIBUTO_MAXIMO + " caracteres: '" + tributo + "'");
        }
        Objects.requireNonNull(ejercicioDesde, "La solicitud necesita su ejercicio inicial");
        Objects.requireNonNull(ejercicioHasta, "La solicitud necesita su ejercicio final");
        if (ejercicioDesde.compareTo(ejercicioHasta) > 0) {
            throw new IllegalArgumentException(
                    "El rango va de menor a mayor: " + ejercicioDesde + " a " + ejercicioHasta);
        }
        Objects.requireNonNull(fechaPresentacion, "La solicitud necesita su fecha de presentacion");
        Objects.requireNonNull(causal, "Sin causal no se puede sustentar que plazo se aplico");
        Objects.requireNonNull(plazo, "El plazo entra por parametro, no por constante (regla 5)");
        if (conjuntoId <= 0) {
            throw new IllegalArgumentException(
                    "El plazo sale de un conjunto sellado, y su identificador queda en la fila"
                            + " (ARQ-09 §3)");
        }
        Objects.requireNonNull(resultado, "La solicitud necesita su resultado");
        if (resolucion != null) {
            resolucion = resolucion.strip();
            if (resolucion.isEmpty()) {
                resolucion = null;
            } else if (resolucion.length() > RESOLUCION_MAXIMA) {
                throw new IllegalArgumentException(
                        "El numero de resolucion no admite mas de "
                                + RESOLUCION_MAXIMA
                                + " caracteres");
            }
        }
        Objects.requireNonNull(ejercicios, "La solicitud necesita el computo de sus ejercicios");
        if (ejercicios.isEmpty()) {
            throw new IllegalArgumentException("Una solicitud sin ejercicios no resuelve nada");
        }
        ejercicios = List.copyOf(ejercicios);
        hechos = List.copyOf(Objects.requireNonNull(hechos, "La lista de hechos puede ir vacia"));
        if (usuarioRegistro != null) {
            usuarioRegistro = usuarioRegistro.strip();
            if (usuarioRegistro.isEmpty()) {
                usuarioRegistro = null;
            }
        }
        Objects.requireNonNull(
                observacion, "Toda modificacion de datos exige la observacion (regla 10)");
    }

    public boolean esNueva() {
        return id == null;
    }

    /** Los ejercicios cuyo plazo ya habia vencido a la fecha de presentacion. */
    public List<Ejercicio> ejerciciosPrescritos() {
        return ejercicios.stream()
                .filter(ComputoDeEjercicio::prescrita)
                .map(ComputoDeEjercicio::ejercicio)
                .toList();
    }
}

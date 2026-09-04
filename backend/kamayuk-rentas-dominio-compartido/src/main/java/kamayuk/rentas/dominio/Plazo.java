package kamayuk.rentas.dominio;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * Una cantidad con su unidad: "20 DIAS_HABILES", "4 ANIOS".
 *
 * <p><b>Ninguna instancia de este tipo se construye desde un literal en {@code src/main}</b> (regla
 * 5). El plazo de reclamacion de un valor, el de la prescripcion y el desfase del inicio del
 * computo son cifras normativas: viven en los parametros sellados y entran por {@link #de(String)}.
 * Lo que este tipo aporta es que la cantidad no pueda viajar sin su unidad —veinte dias habiles y
 * veinte calendario no son lo mismo, y de esa diferencia depende si un expediente coactivo nacio
 * antes de tiempo—.
 *
 * <p>El computo es una funcion pura, sin reloj y sin base (regla 6): el calendario de dias habiles
 * entra como argumento.
 *
 * <p><b>Vive en el dominio compartido desde #41, y no en {@code valores}.</b> Nacio ahi con #39
 * porque el unico plazo del sistema era el de reclamacion de un valor; el de los siete dias del
 * art. 14.1 de la Ley 26979 —el que la REC-1 concede al obligado antes de la medida cautelar— es el
 * mismo concepto contado igual, y {@code coactiva} no puede importar {@code valores.dominio}
 * (Spring Modulith trata como interno todo subpaquete, ARQ-01 §4). La alternativa era un segundo
 * {@code Plazo} en coactiva: dos escrituras de la misma cuenta de dias habiles, que es como se
 * llega a que la exigibilidad de un valor y la de una REC caigan en dias distintos.
 */
public record Plazo(int cantidad, UnidadDePlazo unidad) {

    public Plazo {
        if (cantidad < 0) {
            throw new IllegalArgumentException("Un plazo no puede ser negativo: " + cantidad);
        }
        Objects.requireNonNull(unidad, "Una cantidad sin unidad no es un plazo: " + cantidad);
    }

    /**
     * El plazo tal como viaja en un parametro sellado: la cantidad, un espacio y la unidad.
     *
     * @throws IllegalArgumentException si el texto no tiene esa forma, o si la unidad no existe.
     *     Deliberadamente no hay una lectura tolerante: un parametro mal escrito que se
     *     interpretara "lo mejor posible" produciria un plazo plausible y equivocado, que es el
     *     modo de falla que nadie detecta
     */
    public static Plazo de(String texto) {
        Objects.requireNonNull(texto, "El plazo parametrizado no puede faltar");
        String[] partes = texto.strip().split("\\s+");
        if (partes.length != 2) {
            throw new IllegalArgumentException(
                    "Un plazo se escribe 'cantidad UNIDAD', por ejemplo '20 DIAS_HABILES': '"
                            + texto
                            + "'");
        }
        int cantidad;
        try {
            cantidad = Integer.parseInt(partes[0]);
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "La cantidad del plazo no es un numero entero: '" + partes[0] + "'",
                    noEsNumero);
        }
        UnidadDePlazo unidad;
        try {
            unidad = UnidadDePlazo.valueOf(partes[1].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocida) {
            throw new IllegalArgumentException(
                    "Unidad de plazo desconocida: '"
                            + partes[1]
                            + "'. Se admite DIAS_HABILES, DIAS_CALENDARIO o ANIOS",
                    desconocida);
        }
        return new Plazo(cantidad, unidad);
    }

    /**
     * El dia en que este plazo vence, contado desde {@code inicio} sin contarlo a el.
     *
     * @param calendario que dias cuentan; solo lo usa {@link UnidadDePlazo#DIAS_HABILES}
     */
    public LocalDate vencimientoDesde(LocalDate inicio, CalendarioHabil calendario) {
        Objects.requireNonNull(inicio, "Un plazo se cuenta desde un dia");
        Objects.requireNonNull(calendario, "El computo en dias habiles necesita su calendario");
        return switch (unidad) {
            case DIAS_HABILES -> calendario.sumarHabiles(inicio, cantidad);
            case DIAS_CALENDARIO -> inicio.plusDays(cantidad);
            case ANIOS -> inicio.plusYears(cantidad);
        };
    }

    @Override
    public String toString() {
        return cantidad + " " + unidad;
    }
}

package kamayuk.rentas.cuentacorriente.dominio;

import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Lo que identifica una obligacion dentro del libro, y por tanto una fila de {@code
 * saldo_proyectado}: contribuyente, tributo, ejercicio, periodo y unidad.
 *
 * <p>Son exactamente las columnas de {@code saldo_uq} (V2), y en el mismo orden. Que sea un tipo y
 * no seis argumentos sueltos es lo que impide que la proyeccion se agrupe por unas columnas y la
 * reconstruccion por otras: si difirieran, la conciliacion reportaria divergencias que no existen
 * —o peor, no veria las que si—.
 *
 * <p>{@code periodo} no es nulo aqui aunque lo sea en {@link Asiento}: la columna de la proyeccion
 * es {@code NOT NULL DEFAULT 0}, y 0 significa «anual». Traducirlo en un solo sitio —{@link
 * #de(Asiento)}— evita que la mitad del codigo agrupe los nulos aparte de los ceros.
 */
public record ClaveDeSaldo(
        long contribuyenteId,
        String tributo,
        Ejercicio ejercicio,
        int periodo,
        @Nullable Long predioId,
        @Nullable Long vehiculoId) {

    /**
     * {@code periodo smallint}: 0 (anual) a 12 (mensual), igual que en el asiento.
     *
     * <p>Publico porque {@link RangoDeCuotas} acota contra el mismo limite (#538): un rango de
     * cuotas y la clave que identifica cada una tienen que admitir exactamente los mismos periodos,
     * y dos copias del 12 son dos sitios donde se puede corregir uno solo.
     */
    public static final int PERIODO_MAXIMO = 12;

    public ClaveDeSaldo {
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException(
                    "Un saldo tiene titular: el identificador de contribuyente debe ser positivo");
        }
        Objects.requireNonNull(tributo, "El saldo necesita saber de que tributo es");
        tributo = tributo.strip().toUpperCase(Locale.ROOT);
        if (tributo.isEmpty()) {
            throw new IllegalArgumentException("El tributo no puede estar vacio");
        }
        Objects.requireNonNull(ejercicio, "El saldo necesita su ejercicio");
        if (periodo < 0 || periodo > PERIODO_MAXIMO) {
            throw new IllegalArgumentException(
                    "Periodo fuera de rango: "
                            + periodo
                            + ". Se admite de 0 (anual) a "
                            + PERIODO_MAXIMO);
        }
    }

    /**
     * La clave de la obligacion a la que un asiento pertenece.
     *
     * <p>Es el unico sitio donde el {@code periodo} nulo del asiento se traduce al 0 de la
     * proyeccion.
     */
    public static ClaveDeSaldo de(Asiento asiento) {
        Objects.requireNonNull(asiento, "No hay clave de un asiento nulo");
        return new ClaveDeSaldo(
                asiento.contribuyenteId(),
                asiento.tributo(),
                asiento.ejercicio(),
                asiento.periodo() == null ? 0 : asiento.periodo(),
                asiento.predioId(),
                asiento.vehiculoId());
    }
}

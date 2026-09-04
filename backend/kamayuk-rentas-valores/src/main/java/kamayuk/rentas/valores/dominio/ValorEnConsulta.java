package kamayuk.rentas.valores.dominio;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una fila de la grilla de {@code consulta_valores} (RF-041, #25).
 *
 * <p>Es una proyeccion, no el valor con su detalle: lleva la cabecera y los tres datos que la
 * grilla necesita y la cabecera no guarda —que tributo formaliza, cuando se notifico y si ya paso a
 * coactiva—. Traer las filas de {@code valor_detalle}, las de {@code notificacion} y las de {@code
 * valor_movimiento} de veinte valores para pintar veinte lineas seria traer cientos de filas hijas
 * que nadie va a mirar.
 *
 * <p><b>El importe no se recalcula.</b> Sale de {@link Valor#total()}, que es la suma del desglose
 * <b>congelado</b> al emitir, y su fecha es {@link Valor#proyectadoA()} —no la fecha de hoy—:
 * reimprimir un valor dos anios despues devuelve los mismos importes (AC de #37), y decir que estan
 * actualizados a hoy seria mentir sobre una cifra que no se ha movido.
 *
 * <p><b>{@link #tributos} viene agregado por la base</b> (RNF-083): un valor puede formalizar
 * varias obligaciones —el predial de tres ejercicios, por ejemplo—, y la columna «Tributo» de la
 * pantalla es una sola. La agregacion la hace el servidor para que dos pantallas no la escriban
 * distinto.
 *
 * @param valor la cabecera, tal como esta guardada
 * @param tributos los tributos distintos del detalle, en orden y separados por {@code " / "}; nulo
 *     si el valor no tiene detalle, que no deberia pasar y por eso no se disfraza de cadena vacia
 * @param ejercicioDesde el menor ejercicio del detalle; nulo en el mismo caso
 * @param ejercicioHasta el mayor ejercicio del detalle; nulo en el mismo caso
 * @param notificadoEl la fecha de la primera diligencia que surtio efecto; nulo si ninguna lo hizo
 * @param exigibleDesde desde cuando la deuda es exigible, copiado de esa diligencia; nulo igual
 * @param enCoactiva si el valor ya tiene su pase (PCO)
 * @param situacionA desde que dia se miro la situacion (regla 9)
 */
public record ValorEnConsulta(
        Valor valor,
        @Nullable String tributos,
        @Nullable Integer ejercicioDesde,
        @Nullable Integer ejercicioHasta,
        @Nullable LocalDate notificadoEl,
        @Nullable LocalDate exigibleDesde,
        boolean enCoactiva,
        LocalDate situacionA) {

    public ValorEnConsulta {
        Objects.requireNonNull(valor, "La fila de la grilla es la de un valor");
        Objects.requireNonNull(
                situacionA, "Toda situacion indica a que fecha se miro (regla 9, RNF-075)");
    }

    /** En que punto de la cobranza esta, a {@link #situacionA}. */
    public SituacionDelValor situacion() {
        return SituacionDelValor.de(valor.estado(), exigibleDesde, enCoactiva, situacionA);
    }

    /**
     * El periodo que el valor formaliza, tal como lo pinta la columna «Periodo»: un ejercicio, o el
     * rango cuando formaliza varios.
     *
     * <p>Se compone aqui y no en la interfaz (RNF-083): dos pantallas que unan los dos numeros por
     * su cuenta acaban escribiendo dos guiones distintos.
     */
    public @Nullable String periodo() {
        if (ejercicioDesde == null || ejercicioHasta == null) {
            return null;
        }
        return ejercicioDesde.equals(ejercicioHasta)
                ? String.valueOf(ejercicioDesde)
                : ejercicioDesde + " — " + ejercicioHasta;
    }
}

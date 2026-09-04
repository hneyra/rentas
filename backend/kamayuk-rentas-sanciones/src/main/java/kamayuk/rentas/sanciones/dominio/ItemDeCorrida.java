package kamayuk.rentas.sanciones.dominio;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Una papeleta candidata de una corrida masiva, con su estado (#53; V47 §2).
 *
 * <p>{@link #valorNumero} se guarda junto a {@link #valorId} y no se relee: es lo que el padrón
 * imprime y lo que el operador teclea, y pedírselo al puerto público de {@code valores} por cada
 * fila de un padrón de miles sería una consulta por fila.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param corridaId a qué corrida pertenece
 * @param papeletaId qué papeleta es candidata
 * @param estado en qué punto está
 * @param valorId el valor emitido; solo si está {@link EstadoDeItemDeCorrida#GENERADO}
 * @param valorNumero su número impreso; va con el identificador o no va
 * @param motivo por qué no procedió; solo si está {@link EstadoDeItemDeCorrida#NO_PROCEDE}
 * @param procesadoEn cuándo se resolvió; nulo mientras siga pendiente
 */
public record ItemDeCorrida(
        @Nullable Long id,
        long corridaId,
        long papeletaId,
        EstadoDeItemDeCorrida estado,
        @Nullable Long valorId,
        @Nullable String valorNumero,
        @Nullable String motivo,
        @Nullable Instant procesadoEn) {

    /** {@code papeleta_masivo_item.motivo varchar(200)}. */
    public static final int MOTIVO_MAXIMO = 200;

    public ItemDeCorrida {
        if (corridaId <= 0) {
            throw new IllegalArgumentException("El candidato pertenece a una corrida");
        }
        if (papeletaId <= 0) {
            throw new IllegalArgumentException("El candidato es una papeleta");
        }
        Objects.requireNonNull(estado, "El candidato necesita su estado");

        // Las mismas dos condiciones que papeleta_masivo_item_valor_ck y ..._motivo_ck (V47),
        // aqui para que fallen al construir el objeto en vez de al llegar al motor.
        boolean generado = estado == EstadoDeItemDeCorrida.GENERADO;
        boolean conValor = valorId != null && valorNumero != null;
        if (generado != conValor) {
            throw new IllegalArgumentException(
                    generado
                            ? "Un candidato GENERADO lleva el valor que se le emitio, con su numero"
                            : "Solo un candidato GENERADO lleva valor: "
                                    + estado
                                    + " no puede traer uno");
        }
        if ((estado == EstadoDeItemDeCorrida.NO_PROCEDE) != (motivo != null)) {
            throw new IllegalArgumentException(
                    estado == EstadoDeItemDeCorrida.NO_PROCEDE
                            ? "Un candidato que no procede dice por que: sin el motivo, quien opera"
                                    + " no sabe si tiene que dictar, notificar o esperar"
                            : "Solo un candidato NO_PROCEDE lleva motivo");
        }
        if (motivo != null && motivo.length() > MOTIVO_MAXIMO) {
            throw new IllegalArgumentException("El motivo excede " + MOTIVO_MAXIMO + " caracteres");
        }
    }

    /** Un candidato recién registrado, todavía sin procesar. */
    public static ItemDeCorrida pendiente(long corridaId, long papeletaId) {
        return new ItemDeCorrida(
                null,
                corridaId,
                papeletaId,
                EstadoDeItemDeCorrida.PENDIENTE,
                null,
                null,
                null,
                null);
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long identificador() {
        return Objects.requireNonNull(id, "El candidato todavia no se ha guardado");
    }
}

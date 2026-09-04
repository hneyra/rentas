package kamayuk.rentas.valores;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import org.jspecify.annotations.Nullable;

/**
 * Un valor visto desde coactiva, con lo que hace falta para decidir si se puede importar (#40,
 * RF-100).
 *
 * <p>Es la segunda proyeccion publica de {@code valores}, y no reusa {@link ValorDelContribuyente}
 * por dos motivos que no son de gusto:
 *
 * <ol>
 *   <li><b>Lleva el identificador.</b> {@code expediente_valor.valor_id} es una clave foranea, y
 *       {@link ValorDelContribuyente} solo publica el numero impreso. Guardar el numero en vez del
 *       identificador dejaria la carpeta apuntando a un texto.
 *   <li><b>Lleva sus obligaciones.</b> Sin ellas el expediente no puede preguntarle a {@code
 *       cuentacorriente} cuanto se debe hoy por lo que sus valores formalizan, y la unica cifra que
 *       podria mostrar seria la congelada al emitir (regla 9).
 * </ol>
 *
 * <h2>Las cifras estan congeladas, y su fecha lo dice</h2>
 *
 * <p>{@link #proyectadoA} es la fecha del desglose, y <b>no es la de hoy</b>: es lo que se congelo
 * al emitir el valor. La deuda actualizada del expediente no se calcula sumando esto (ver {@link
 * ObligacionDelValor}).
 *
 * <h2>La situacion si depende de la fecha</h2>
 *
 * <p>{@link #situacion} es el nombre de {@code SituacionDelValor} —que vive en {@code .dominio} y
 * no cruza— mirado a {@link #situacionA}. Un valor notificado el 3 de abril con plazo hasta el 4 de
 * mayo no es exigible el 10 de abril y si lo es el 10 de mayo, sin que ninguna fila haya cambiado.
 *
 * @param id el identificador con el que el expediente lo referencia
 * @param tipo OP, RD o RM
 * @param numero el numero impreso
 * @param ejercicio el ejercicio de emision de la cabecera
 * @param fechaEmision el dia en que se emitio
 * @param contribuyenteId a quien se le emitio
 * @param situacion en que punto de la cobranza esta, a {@code situacionA}
 * @param situacionA desde que dia se miro la situacion (regla 9)
 * @param exigibleDesde desde cuando la deuda es exigible; nulo si ninguna diligencia surtio efecto
 * @param conPaseACoactiva si ya tiene su movimiento PCO (#39, V28)
 * @param total la suma del desglose congelado
 * @param proyectadoA la fecha a la que esta {@code total} (regla 9, RNF-075)
 * @param obligaciones que obligaciones formaliza; nunca vacia en un valor bien emitido
 */
public record ValorParaCoactiva(
        long id,
        String tipo,
        String numero,
        Ejercicio ejercicio,
        LocalDate fechaEmision,
        long contribuyenteId,
        String situacion,
        LocalDate situacionA,
        @Nullable LocalDate exigibleDesde,
        boolean conPaseACoactiva,
        Dinero total,
        LocalDate proyectadoA,
        List<ObligacionDelValor> obligaciones) {

    public ValorParaCoactiva {
        if (id <= 0) {
            throw new IllegalArgumentException(
                    "Un valor que cruza a coactiva ya esta guardado: su identificador es positivo");
        }
        Objects.requireNonNull(tipo, "El valor necesita su tipo");
        Objects.requireNonNull(numero, "El valor necesita su numero");
        Objects.requireNonNull(ejercicio, "El valor necesita su ejercicio");
        Objects.requireNonNull(fechaEmision, "El valor necesita su fecha de emision");
        Objects.requireNonNull(situacion, "El valor necesita su situacion");
        Objects.requireNonNull(
                situacionA, "Toda situacion indica a que fecha se miro (regla 9, RNF-075)");
        Objects.requireNonNull(total, "El valor necesita su total");
        Objects.requireNonNull(
                proyectadoA, "Toda cifra indica a que fecha esta (RNF-075, regla 9)");
        obligaciones = List.copyOf(obligaciones);
    }
}

package kamayuk.rentas.rentas.dominio.predial;

import java.util.Objects;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * Un predio tal como entra en la base del contribuyente, con lo que hace falta para explicarlo ante
 * una impugnacion: de donde es, para que se usa, que parte es de quien y cuanto puso (#395).
 *
 * <p>Es {@link DetalleDeterminacionPredio} —lo que se guarda— mas lo que identifica al predio ante
 * una persona —codigo, direccion, uso—, que vive en catastro y no se copia a rentas. Se compone al
 * leer y no se persiste: duplicar la direccion en {@code determinacion_predio_detalle} la dejaria
 * envejecer aparte de la del padron.
 *
 * <p><b>El porcentaje no se teclea.</b> Sale de {@code titularidad} a la fecha de calculo, que es
 * la unica fuente de quien es dueno de que parte. Es lo que impide que la base del contribuyente se
 * pueda inflar o desinflar desde la peticion.
 *
 * @param predioId el predio
 * @param codigoReferenciaCatastral como se le nombra en el padron
 * @param direccion donde esta
 * @param uso para que se usa segun la ficha vigente; nulo si el predio no tiene ficha
 * @param porcentajePropiedad la cuota del contribuyente sobre este predio, de {@code titularidad}
 * @param autovaluo el autovaluo declarado del predio (RT-010: terreno + construccion + obras)
 * @param valuoExonerado la parte del autovaluo que no esta afecta
 * @param baseImponiblePredio lo que este predio aporta a la base, ya ponderado por el porcentaje
 * @param porcentajeRegistradoDelPredio lo que suman <b>todas</b> las cuotas del predio a la fecha
 *     de calculo, las de este contribuyente y las de los demas (#690). Cuando es menor que 100, la
 *     base de este predio sale ponderada por una titularidad que <b>no cubre el predio entero</b>:
 *     en Catacaos eso pasa en 304 predios, con cincuenta sumas distintas por debajo de cien y una
 *     de 0,349 %. La determinacion se hace igual —es correcta para lo registrado, y no determinar
 *     dejaria sin emitir a un tercio del padron—, pero sale <b>dicho</b>: una cifra ponderada por
 *     una titularidad incompleta no se distingue de una correcta si nada la acompaña
 */
public record PredioEnLaBase(
        long predioId,
        String codigoReferenciaCatastral,
        String direccion,
        @Nullable String uso,
        Porcentaje porcentajePropiedad,
        Dinero autovaluo,
        Dinero valuoExonerado,
        Dinero baseImponiblePredio,
        Porcentaje porcentajeRegistradoDelPredio) {

    /** La forma anterior a #690, que da el predio por completo. */
    public PredioEnLaBase(
            long predioId,
            String codigoReferenciaCatastral,
            String direccion,
            @Nullable String uso,
            Porcentaje porcentajePropiedad,
            Dinero autovaluo,
            Dinero valuoExonerado,
            Dinero baseImponiblePredio) {
        this(
                predioId,
                codigoReferenciaCatastral,
                direccion,
                uso,
                porcentajePropiedad,
                autovaluo,
                valuoExonerado,
                baseImponiblePredio,
                new Porcentaje(java.math.BigDecimal.valueOf(100)));
    }

    /** Las cuotas del predio cubren el predio entero a la fecha de calculo. */
    public boolean titularidadCompleta() {
        return porcentajeRegistradoDelPredio.valor().compareTo(java.math.BigDecimal.valueOf(100))
                == 0;
    }

    public PredioEnLaBase {
        if (predioId <= 0) {
            throw new IllegalArgumentException(
                    "El predio de la base tiene identificador: " + predioId);
        }
        Objects.requireNonNull(codigoReferenciaCatastral, "El predio necesita su codigo");
        Objects.requireNonNull(direccion, "El predio necesita su direccion");
        Objects.requireNonNull(porcentajePropiedad, "El predio necesita el % de propiedad");
        Objects.requireNonNull(autovaluo, "El predio necesita su autovaluo");
        Objects.requireNonNull(valuoExonerado, "El predio necesita su valuo exonerado");
        Objects.requireNonNull(baseImponiblePredio, "El predio necesita lo que aporta a la base");
        Objects.requireNonNull(
                porcentajeRegistradoDelPredio,
                "Hace falta saber cuanto del predio esta registrado, no solo la cuota propia");
        if (valuoExonerado.esNegativo()) {
            throw new IllegalArgumentException("El valuo exonerado no puede ser negativo");
        }
        if (valuoExonerado.esMayorQue(autovaluo)) {
            throw new IllegalArgumentException(
                    "El valuo exonerado del predio "
                            + predioId
                            + " ("
                            + valuoExonerado
                            + ") supera su autovaluo ("
                            + autovaluo
                            + "): la parte exonerada es una parte del autovaluo, no otra cifra");
        }
    }

    /** La parte del autovaluo que si esta afecta, antes de ponderar por el % de propiedad. */
    public Dinero valuoAfecto() {
        return autovaluo.menos(valuoExonerado);
    }

    /** El detalle que se guarda de este predio. */
    public DetalleDeterminacionPredio comoDetalle() {
        return DetalleDeterminacionPredio.nuevo(
                predioId, autovaluo, valuoExonerado, porcentajePropiedad, baseImponiblePredio);
    }
}

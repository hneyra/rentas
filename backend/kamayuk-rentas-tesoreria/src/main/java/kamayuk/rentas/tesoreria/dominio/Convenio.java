package kamayuk.rentas.tesoreria.dominio;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;

/**
 * Un convenio de fraccionamiento, con su deuda acogida y su cronograma (V31, RF-084).
 *
 * <h2>No se edita, y su estado no es una columna</h2>
 *
 * <p>V31 le retira a {@code kamayuk_app} el privilegio de {@code UPDATE} sobre {@code convenio} y
 * sobre {@code convenio_cuota}, y el escaner de fuentes rechaza cualquier {@code UPDATE convenio
 * SET} antes de que llegue a ejecutarse. Es la misma decision que V29 y V30 tomaron para el recibo,
 * por el mismo motivo: un convenio es un acto administrativo con numeracion propia que el
 * contribuyente firma y se lleva, y corregirlo en el sitio deja al papel y a la base diciendo cosas
 * distintas.
 *
 * <p>Por eso no hay aqui ningun campo {@code estado}: lo derivan {@link
 * EstadoDeConvenio#deLosMovimientos} y {@code convenio_movimiento}.
 *
 * <h2>Toda cifra con su fecha</h2>
 *
 * <p>{@link #fechaCorte} es la fecha a la que se leyo la deuda que {@link #acogida} congela (regla
 * 9, RNF-075). No es la fecha del convenio: entre la simulacion y la firma la deuda devenga, y el
 * papel tiene que decir con que corte se calculo.
 *
 * @param id nulo mientras no se haya guardado
 * @param numero el numero del convenio, por ejercicio
 * @param contribuyenteId a quien se le fracciona
 * @param tipo si es ordinario o coactivo
 * @param fecha el dia en que se registro; entra como argumento, no sale del reloj (regla 6)
 * @param fechaCorte a que fecha esta la deuda acogida
 * @param condiciones el interes, el maximo de cuotas y el conjunto sellado del que salieron
 * @param acogida la deuda original, congelada, con la fase de la que salio cada cuota
 * @param cronograma la cuota inicial y las cuotas, congeladas
 * @param tipoGarantia el ofrecimiento de garantia, si lo hubo; solo constancia (D-02b)
 * @param detalleGarantia la descripcion del bien o documento ofrecido
 * @param resolucion la resolucion que lo aprueba, si consta
 * @param convenioOrigenId el convenio que este reformula, si sale de una reformulacion
 * @param registradoEn el instante de registro; sale del reloj inyectado, no de un {@code now()}
 * @param usuarioRegistro quien lo registro; nulo mientras no se haya guardado
 * @param observacion por que se registro (regla 10, RNF-052)
 */
public record Convenio(
        @Nullable Long id,
        NumeroDeConvenio numero,
        long contribuyenteId,
        TipoDeConvenio tipo,
        LocalDate fecha,
        LocalDate fechaCorte,
        CondicionesDelConvenio condiciones,
        List<DeudaAcogida> acogida,
        List<CuotaDeConvenio> cronograma,
        @Nullable TipoDeGarantia tipoGarantia,
        @Nullable String detalleGarantia,
        @Nullable String resolucion,
        @Nullable Long convenioOrigenId,
        Instant registradoEn,
        @Nullable String usuarioRegistro,
        Observacion observacion) {

    /** {@code convenio.detalle_garantia varchar(500)}. */
    private static final int DETALLE_MAXIMO = 500;

    /** {@code convenio.resolucion varchar(40)}. */
    private static final int RESOLUCION_MAXIMA = 40;

    public Convenio {
        Objects.requireNonNull(numero, "Un convenio sin numero no es un convenio");
        Objects.requireNonNull(tipo, "El convenio dice bajo que procedimiento se firmo");
        Objects.requireNonNull(fecha, "El convenio es de un dia concreto (regla 6)");
        Objects.requireNonNull(
                fechaCorte, "Toda cifra indica a que fecha esta calculada (RNF-075, regla 9)");
        Objects.requireNonNull(condiciones, "El convenio necesita sus condiciones (regla 5)");
        Objects.requireNonNull(acogida, "La deuda acogida es una lista vacia, no nula");
        Objects.requireNonNull(cronograma, "El cronograma es una lista vacia, no nula");
        Objects.requireNonNull(registradoEn, "El convenio dice cuando se registro");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");
        if (contribuyenteId <= 0) {
            throw new IllegalArgumentException("El convenio es de un contribuyente concreto");
        }
        acogida = List.copyOf(acogida);
        cronograma = List.copyOf(cronograma);
        if (acogida.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un convenio sin deuda acogida no fracciona nada: no se registra");
        }
        if (cronograma.isEmpty()) {
            throw new IllegalArgumentException(
                    "Un convenio sin cronograma no compromete a nada: no se registra");
        }
        detalleGarantia = recortar(detalleGarantia, DETALLE_MAXIMO, "El detalle de la garantia");
        resolucion = recortar(resolucion, RESOLUCION_MAXIMA, "La resolucion");
    }

    /**
     * Lo acogido: la suma de la deuda original congelada, a la fecha de corte.
     *
     * <p>Se calcula y no se guarda como campo independiente, por lo mismo que {@code Recibo#total}:
     * es lo que impide que el total y su detalle puedan discrepar. En la base, {@code
     * convenio.monto_total} guarda esta misma suma porque las consultas la necesitan sin recorrer
     * el detalle, y {@code convenio_deuda_desglose_ck} comprueba fila a fila que cuadre.
     */
    public Dinero montoTotal() {
        Dinero total = Dinero.CERO;
        for (DeudaAcogida cuota : acogida) {
            total = total.mas(cuota.total());
        }
        return total;
    }

    /** La cuota inicial: la que hay que cobrar en caja para que el convenio exista. */
    public Dinero cuotaInicial() {
        return Cronograma.inicialDe(cronograma);
    }

    /** Cuantas cuotas tiene el cronograma sin contar la inicial. */
    public int numeroDeCuotas() {
        return (int) cronograma.stream().filter(cuota -> !cuota.esInicial()).count();
    }

    /** El total comprometido: capital mas interes de fraccionamiento mas gastos. */
    public Dinero totalDelCronograma() {
        return Cronograma.total(cronograma);
    }

    public boolean esNuevo() {
        return id == null;
    }

    /** El identificador, exigiendo que ya se haya guardado. */
    public long idGuardado() {
        Long guardado = id;
        if (guardado == null) {
            throw new IllegalStateException("Un convenio sin guardar todavia no tiene movimientos");
        }
        return guardado;
    }

    private static @Nullable String recortar(@Nullable String valor, int maximo, String que) {
        if (valor == null) {
            return null;
        }
        String limpio = valor.strip();
        if (limpio.isEmpty()) {
            return null;
        }
        if (limpio.length() > maximo) {
            throw new IllegalArgumentException(que + " excede " + maximo + " caracteres");
        }
        return limpio;
    }
}

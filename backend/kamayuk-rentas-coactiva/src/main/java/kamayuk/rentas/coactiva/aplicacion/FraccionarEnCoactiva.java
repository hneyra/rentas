package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.ConvenioCoactivo;
import kamayuk.rentas.tesoreria.FraccionamientoCoactivo;
import kamayuk.rentas.tesoreria.SolicitudDeConvenioCoactivo;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suscribe un convenio de fraccionamiento sobre la deuda de un expediente coactivo (#42, RF-105).
 *
 * <h2>El mecanismo es el de #35, y esta clase no lo reescribe</h2>
 *
 * <p>Todo lo que hace un convenio —releer la deuda del libro, congelarla con su fase de origen,
 * leer el interes y el maximo de cuotas del conjunto sellado, calcular el cronograma, numerarlo y
 * dejarlo en preconvenio hasta que la caja cobre su inicial— ya estaba escrito y probado. Aqui se
 * llama, por el puerto {@link FraccionamientoCoactivo}, y se le agregan las <b>dos guardas propias
 * de coactiva</b> que el mecanismo general no puede tener porque no sabe que existen los
 * expedientes.
 *
 * <p>La consecuencia mas importante viene gratis: <b>si el convenio se quiebra, la deuda vuelve a
 * COACTIVA</b>. No porque esta clase haga nada, sino porque {@code convenio_deuda.fase_origen}
 * (V31) guarda de donde salio cada cuota y {@code CerrarConvenio} la devuelve ahi. #42 lo
 * <b>verifica</b> asiento por asiento en vez de suponerlo: el mecanismo ya lo hacia, y una prueba
 * que lo demuestre es lo que impide que alguien «simplifique» devolviendo siempre a ordinaria.
 *
 * <h2>Las dos guardas propias</h2>
 *
 * <ol>
 *   <li><b>El expediente tiene que estar vivo.</b> Fraccionar la deuda de un procedimiento
 *       concluido no significa nada: no hay nada que suspender ni que reanudar, y el convenio
 *       quedaria colgando de una carpeta cerrada.
 *   <li><b>Lo que se acoge tiene que venir de coactiva.</b> Se comprueba sobre la simulacion, que
 *       no escribe nada, mirando la fase de origen de cada cuota. Sin esta guarda, la pantalla de
 *       coactiva podria fraccionar deuda ordinaria —que tiene su propia pantalla, su propio
 *       privilegio y su propia autoridad— y el quiebre la devolveria a ordinaria sin que nadie
 *       hubiera decidido meterla ahi.
 * </ol>
 *
 * <p>La comprobacion se hace sobre la simulacion y el registro <b>relee</b>: entre las dos, una
 * cuota podria cambiar de fase. La ventana es la misma que #35 tiene por diseño, y la fase de
 * origen la vuelve inocua: lo que se acoja se devolvera exactamente a donde estaba.
 *
 * <h2>Lo que este caso de uso NO hace, y por que</h2>
 *
 * <p><b>No suspende el procedimiento.</b> Registrar el convenio no agrega ningun movimiento al
 * historial del expediente ni lo deja en {@code 041 — SUSPENDIDO}. Tres motivos, en orden de peso:
 *
 * <ul>
 *   <li>La suspension es un <b>acto del ejecutor</b> (art. 16 de la Ley 26979), con su resolucion
 *       firmada y su documento emitido. {@code RegistrarActoCoactivo} ya la dicta —{@code
 *       TipoDeActoCoactivo.SUSPENSION} lleva al estado 041—, con su motivo y su papel. Que el
 *       sistema la dictara solo, al registrar un preconvenio que todavia no esta formalizado, seria
 *       el sistema firmando un acto administrativo que nadie firmo.
 *   <li>Y <b>cual</b> es el efecto exacto no esta decidido: si el fraccionamiento suspende el
 *       procedimiento o lo concluye, y bajo que inciso, es una cuestion normativa que ninguna de
 *       las fuentes de este repositorio cierra. Elegir una por comodidad la dejaria aplicada a todo
 *       el padron coactivo.
 *   <li>El prototipo no lo pide: el desplegable «Nuevo estado» de {@code expediente_historial}
 *       ofrece los seis estados del manual y ninguno es «FRACCIONADO», y la pantalla {@code
 *       fraccionamiento_coactivo} no tiene ninguna accion que mueva el expediente.
 * </ul>
 *
 * <p>Lo que si ocurre, y es visible sin inventar nada: la deuda acogida pasa a fase {@code
 * CONVENIO} en el libro cuando la caja cobra la inicial, de modo que deja de contarse como deuda
 * coactiva exigible y el expediente aparece sin deuda que ejecutar. Eso es un hecho del libro, no
 * una etiqueta.
 */
@Service
public class FraccionarEnCoactiva {

    /** La fase de la que tiene que venir lo que se acoge; texto, porque asi cruza la frontera. */
    private static final String FASE_COACTIVA = "COACTIVA";

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final FraccionamientoCoactivo fraccionamiento;

    public FraccionarEnCoactiva(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            FraccionamientoCoactivo fraccionamiento) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.fraccionamiento = fraccionamiento;
    }

    /**
     * El cronograma que saldria, sin registrar nada.
     *
     * @throws CambiarEstadoDelExpediente.ExpedienteInexistente si no hay expediente con ese numero
     * @throws CambiarEstadoDelExpediente.ExpedienteConcluido si el procedimiento ya termino
     * @throws DeudaAjenaAlProcedimiento si alguna cuota no viene de la fase coactiva
     */
    @Transactional(readOnly = true)
    public ConvenioCoactivo simular(Peticion peticion) {
        Objects.requireNonNull(peticion, "No se simula sin peticion");
        ExpedienteCoactivo expediente = expedienteVivo(peticion.numeroDeExpediente());
        return exigirQueVengaDeCoactiva(
                expediente, fraccionamiento.simular(solicitudDe(peticion, expediente)));
    }

    /**
     * Registra el preconvenio coactivo.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Peticion}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @throws CambiarEstadoDelExpediente.ExpedienteInexistente si no hay expediente con ese numero
     * @throws CambiarEstadoDelExpediente.ExpedienteConcluido si el procedimiento ya termino
     * @throws DeudaAjenaAlProcedimiento si alguna cuota no viene de la fase coactiva
     * @throws FraccionamientoCoactivo.SinDeudaCoactivaQueFraccionar si no hay deuda a esa fecha
     */
    @Transactional
    public ConvenioCoactivo fraccionar(Peticion peticion, Observacion observacion) {
        return fraccionar(peticion, null, observacion);
    }

    /**
     * El mismo acto, con la clave de idempotencia del intento (#606).
     *
     * <p>La sobrecarga de arriba conserva la firma que ya usaban las pruebas y quien no manda
     * cabecera; con {@code null} cada envio es un intento distinto, que es lo que era antes.
     */
    public ConvenioCoactivo fraccionar(
            Peticion peticion,
            @org.jspecify.annotations.Nullable String claveDeIdempotencia,
            Observacion observacion) {
        Objects.requireNonNull(peticion, "No se fracciona sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ExpedienteCoactivo expediente = expedienteVivo(peticion.numeroDeExpediente());
        SolicitudDeConvenioCoactivo solicitud = solicitudDe(peticion, expediente);

        // La comprobacion va sobre la simulacion, que no escribe: si una cuota no viniera de
        // coactiva, el registro no llega a ocurrir y no hay nada que deshacer.
        exigirQueVengaDeCoactiva(expediente, fraccionamiento.simular(solicitud));

        return fraccionamiento.registrar(solicitud, claveDeIdempotencia, observacion);
    }

    // ------------------------------------------------------------------

    private ExpedienteCoactivo expedienteVivo(String numero) {
        ExpedienteCoactivo expediente =
                expedientes
                        .porNumero(numero)
                        .orElseThrow(
                                () -> new CambiarEstadoDelExpediente.ExpedienteInexistente(numero));
        EstadoDelExpediente estado =
                EstadoDelExpediente.delHistorial(
                        movimientos.deExpediente(expediente.identificador()));
        if (estado.estaConcluido()) {
            throw new CambiarEstadoDelExpediente.ExpedienteConcluido(expediente.numero());
        }
        return expediente;
    }

    private static SolicitudDeConvenioCoactivo solicitudDe(
            Peticion peticion, ExpedienteCoactivo expediente) {
        return new SolicitudDeConvenioCoactivo(
                expediente.contribuyenteId(),
                peticion.obligaciones(),
                peticion.fecha(),
                peticion.fechaDeCorte(),
                peticion.cuotas(),
                peticion.porcentajeInicial(),
                peticion.primeraCuotaVence(),
                peticion.resolucion());
    }

    private static ConvenioCoactivo exigirQueVengaDeCoactiva(
            ExpedienteCoactivo expediente, ConvenioCoactivo convenio) {
        List<String> ajenas = new ArrayList<>();
        for (DeudaAcogida cuota : convenio.deudaAcogida()) {
            if (!FASE_COACTIVA.equals(cuota.faseOrigen().toUpperCase(Locale.ROOT))) {
                ajenas.add(
                        cuota.tributo()
                                + " "
                                + cuota.ejercicio().valor()
                                + "/"
                                + cuota.periodo()
                                + " ("
                                + cuota.faseOrigen()
                                + ")");
            }
        }
        if (!ajenas.isEmpty()) {
            throw new DeudaAjenaAlProcedimiento(expediente.numero(), ajenas);
        }
        return convenio;
    }

    /**
     * Lo que la pantalla {@code fraccionamiento_coactivo} manda.
     *
     * <p><b>El contribuyente no esta aqui</b>, y es deliberado: sale del expediente. Admitirlo por
     * separado permitiria fraccionar la deuda de una persona bajo el expediente de otra.
     *
     * @param numeroDeExpediente el numero impreso del expediente
     * @param obligaciones las deudas marcadas en la grilla
     * @param fecha el dia del convenio (regla 6)
     * @param fechaDeCorte a que fecha se lee la deuda que se acoge (regla 9)
     * @param cuotas cuantas cuotas se piden, sin contar la inicial
     * @param porcentajeInicial que parte se paga en el acto
     * @param primeraCuotaVence cuando vence la primera cuota
     * @param resolucion la resolucion que lo aprueba, si consta
     */
    public record Peticion(
            String numeroDeExpediente,
            List<SeleccionDeObligacion> obligaciones,
            LocalDate fecha,
            LocalDate fechaDeCorte,
            int cuotas,
            Alicuota porcentajeInicial,
            LocalDate primeraCuotaVence,
            @Nullable String resolucion) {

        public Peticion {
            Objects.requireNonNull(numeroDeExpediente, "Falta el numero de expediente");
            Objects.requireNonNull(obligaciones, "La lista es vacia, no nula");
            obligaciones = List.copyOf(obligaciones);
            Objects.requireNonNull(fecha, "El convenio es de un dia concreto (regla 6)");
            Objects.requireNonNull(fechaDeCorte, "La deuda se lee a una fecha (regla 9)");
            Objects.requireNonNull(porcentajeInicial, "Falta el porcentaje de cuota inicial");
            Objects.requireNonNull(primeraCuotaVence, "La primera cuota vence en una fecha");
        }
    }

    /**
     * Se marco deuda que no esta en cobranza coactiva.
     *
     * <p>El mensaje nombra las cuotas y su fase: quien opera tiene que poder ver <b>cual</b> sobra,
     * no que «algo» sobra.
     */
    public static final class DeudaAjenaAlProcedimiento extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DeudaAjenaAlProcedimiento(String expediente, List<String> ajenas) {
            super(
                    "El fraccionamiento del expediente "
                            + expediente
                            + " solo acoge deuda en cobranza coactiva, y estas cuotas estan en otra"
                            + " fase: "
                            + String.join(", ", ajenas)
                            + ". La deuda ordinaria se fracciona en su propia pantalla, con su"
                            + " propio privilegio; acogerla aqui la devolveria a ordinaria al"
                            + " quebrar sin que nadie hubiera decidido meterla en coactiva");
        }
    }
}

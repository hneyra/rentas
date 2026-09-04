package kamayuk.rentas.tesoreria.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.cuentacorriente.AcogimientoAConvenio;
import kamayuk.rentas.cuentacorriente.DeudaAcogida;
import kamayuk.rentas.cuentacorriente.SeleccionDeObligacion;
import kamayuk.rentas.dominio.Alicuota;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.tesoreria.dominio.CondicionesDelConvenio;
import kamayuk.rentas.tesoreria.dominio.Convenio;
import kamayuk.rentas.tesoreria.dominio.ConvenioRepository;
import kamayuk.rentas.tesoreria.dominio.Cronograma;
import kamayuk.rentas.tesoreria.dominio.CuotaDeConvenio;
import kamayuk.rentas.tesoreria.dominio.NumeroDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeConvenio;
import kamayuk.rentas.tesoreria.dominio.TipoDeGarantia;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra el <b>preconvenio</b>: la deuda seleccionada, congelada, con su cronograma (#35,
 * RF-084).
 *
 * <h2>Lo que este caso de uso NO hace</h2>
 *
 * <p><b>No mueve nada en el libro.</b> Un preconvenio no acoge deuda: es la simulacion aceptada,
 * con su numero y su cronograma, esperando que se cobre su cuota inicial. Acoger aqui dejaria la
 * deuda en fase de convenio de todo el que se acercara a preguntar cuanto le saldria fraccionar, y
 * bastaria con no volver para dejar de aparecer en la cobranza ordinaria.
 *
 * <p><b>No decide cuanto se debe.</b> ARQ-01 §3.8: «tesoreria asienta abonos; nunca determina». La
 * composicion de la deuda —que cuotas, en que fase, con que desglose— la resuelve {@link
 * AcogimientoAConvenio#deudaAcogible} releyendo su propio libro. Aqui solo se congela lo que
 * devolvio.
 *
 * <p><b>No inventa el interes ni el maximo de cuotas.</b> Los lee {@link CondicionesParametrizadas}
 * del conjunto sellado, y el convenio guarda de que conjunto salieron (regla 5, ARQ-09 §3).
 *
 * <h2>Sin cuota inicial pagada en caja no hay convenio</h2>
 *
 * <p>Es el criterio de aceptacion central de #35, y aqui se cumple por construccion: este caso de
 * uso <b>no</b> puede dejar un convenio vigente. El estado se deriva de {@code convenio_movimiento}
 * y este metodo no escribe ninguno, asi que lo que sale de aqui es siempre un {@code PRECONVENIO}.
 * La formalizacion es otro acto, con su recibo, y vive en {@link FormalizarConvenio}.
 *
 * <h2>Reejecutar no duplica, pero reenviar el mismo intento tampoco (#606)</h2>
 *
 * <p>Cada llamada <b>con una clave distinta</b> produce un convenio nuevo, con su propio numero: no
 * hay «regenerar el cronograma de este». Y dentro de un convenio, que sus cuotas y su deuda acogida
 * no se puedan escribir dos veces lo garantizan {@code convenio_cuota_uq} (V3) y {@code
 * convenio_deuda_uq} (V31), en la base y no en un {@code if}.
 *
 * <p>Lo que faltaba era el <b>reenvio del mismo intento</b>: tras un 500 o un tiempo de espera
 * agotado, quien atiende no sabe si escribio, y repetir abria otro preconvenio con otro numero
 * sobre la misma deuda. Con la cabecera {@code Idempotency-Key}, el reenvio devuelve el convenio de
 * la primera vez.
 *
 * <p><b>La guarda es la base, no el {@code if}.</b> Quitar la lectura previa de {@link
 * ConvenioRepository#porClaveDeIdempotencia} dejando el indice sigue produciendo <b>un solo</b>
 * convenio —{@code convenio_idempotencia_uq} (V70) rechaza el segundo {@code INSERT}—; lo unico que
 * se pierde es poder contestar con el convenio de la primera vez en vez de con un 409. Es la
 * leccion de #188: la restriccion sostiene la regla, la lectura sostiene el mensaje. Y no se puede
 * al reves: entre el {@code SELECT} y el {@code INSERT} cabe otra peticion, asi que dos envios
 * simultaneos pasarian los dos por cualquier comprobacion escrita en Java.
 */
@Service
public class RegistrarPreconvenio {

    private final ConvenioRepository convenios;
    private final AcogimientoAConvenio acogimiento;
    private final CondicionesParametrizadas condiciones;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarPreconvenio(
            ConvenioRepository convenios,
            AcogimientoAConvenio acogimiento,
            CondicionesParametrizadas condiciones,
            Auditoria auditoria,
            Clock reloj) {
        this.convenios = convenios;
        this.acogimiento = acogimiento;
        this.condiciones = condiciones;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Simula el cronograma sin registrar nada.
     *
     * <p>Es el boton «Imprimir simulacion» de la pantalla, y no escribe: ni numera un convenio, ni
     * toca el libro, ni deja auditoria. Que exista como metodo aparte —en vez de como una bandera
     * de {@link #registrar}— es lo que impide que una simulacion consuma un correlativo.
     *
     * @throws Cronograma.NadaQueFraccionar si la seleccion no tiene deuda a esa fecha
     */
    @Transactional(readOnly = true)
    public Simulacion simular(Peticion peticion) {
        Objects.requireNonNull(peticion, "No se simula sin peticion");
        List<DeudaAcogida> acogible =
                acogimiento.deudaAcogible(
                        peticion.contribuyenteId(),
                        peticion.obligaciones(),
                        peticion.fechaDeCorte());
        if (acogible.isEmpty()) {
            throw new SinDeudaQueFraccionar(peticion.fechaDeCorte());
        }
        CondicionesParametrizadas.Vigentes vigentes =
                condiciones.aLaFechaDe(peticion.fecha(), peticion.porcentajeInicial());
        CondicionesDelConvenio condicionesDelConvenio = vigentes.condiciones();
        Dinero total = totalDe(acogible);
        List<CuotaDeConvenio> cronograma =
                Cronograma.de(
                        total,
                        condicionesDelConvenio,
                        peticion.cuotas(),
                        peticion.primeraCuotaVence(),
                        vigentes.redondeoDeLaCuota());
        return new Simulacion(
                acogible, cronograma, condicionesDelConvenio, total, peticion.fechaDeCorte());
    }

    /**
     * Registra el preconvenio con su numero, su deuda congelada y su cronograma.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Peticion}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional. Escondida dentro de un objeto de peticion, la comprobacion no la
     * encuentra y la regla dejaria de proteger nada.
     *
     * <p>La clave de idempotencia va <b>aparte</b> de {@link Peticion} y no dentro: {@code
     * Peticion} es lo que la pantalla de fraccionamiento pide, y la clave es una cabecera del
     * transporte —el mismo reparto que {@code RegistrarAnuncio} y que {@code
     * ReciboRepository#emitir}—.
     *
     * @param claveDeIdempotencia la cabecera {@code Idempotency-Key}; opcional
     * @throws SinDeudaQueFraccionar si la seleccion no tiene deuda a la fecha de corte
     * @throws CondicionesDelConvenio.DemasiadasCuotas si se piden mas de las que admite la
     *     ordenanza
     * @throws CondicionesParametrizadas.CondicionSinParametrizar si falta el interes o el maximo
     * @throws ClaveDeOtraPeticion si esa clave registro el convenio de otro contribuyente
     * @throws ConvenioRepository.ClaveRepetida si dos envios del mismo intento corren a la vez
     */
    @Transactional
    public Convenio registrar(
            Peticion peticion, @Nullable String claveDeIdempotencia, Observacion observacion) {
        Objects.requireNonNull(peticion, "No se registra sin peticion");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        String clave = limpiar(claveDeIdempotencia);
        if (clave != null) {
            Optional<Convenio> yaRegistrado = convenios.porClaveDeIdempotencia(clave);
            if (yaRegistrado.isPresent()) {
                // El reenvio: mismo doble clic, mismo convenio, ningun numero de mas. La lectura
                // esta para poder contestar algo util; quien impide de verdad el duplicado es
                // `convenio_idempotencia_uq`, porque entre este SELECT y el INSERT cabe otra
                // peticion.
                Convenio anterior = yaRegistrado.get();
                if (anterior.contribuyenteId() != peticion.contribuyenteId()) {
                    // Una clave reusada para otro intento no es un reenvio: devolver el convenio
                    // de la primera vez imprimiria en ventanilla el acuerdo de OTRA persona.
                    throw new ClaveDeOtraPeticion(anterior.numero().impreso());
                }
                return anterior;
            }
        }

        Simulacion simulada = simular(peticion);
        NumeroDeConvenio numero = convenios.siguienteNumero(Ejercicio.de(peticion.fecha()));

        Convenio convenio =
                new Convenio(
                        null,
                        numero,
                        peticion.contribuyenteId(),
                        peticion.tipo(),
                        peticion.fecha(),
                        peticion.fechaDeCorte(),
                        simulada.condiciones(),
                        simulada.acogible(),
                        simulada.cronograma(),
                        peticion.tipoGarantia(),
                        peticion.detalleGarantia(),
                        peticion.resolucion(),
                        peticion.convenioOrigenId(),
                        reloj.instant(),
                        null,
                        observacion);

        Convenio guardado = convenios.registrar(convenio, clave);
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fecha(),
                                "convenio",
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado)));
        return guardado;
    }

    // ------------------------------------------------------------------

    /** Una cabecera vacia o en blanco es no traer clave, no traer la cadena vacia. */
    private static @Nullable String limpiar(@Nullable String clave) {
        if (clave == null) {
            return null;
        }
        String limpia = clave.strip();
        return limpia.isEmpty() ? null : limpia;
    }

    private static Dinero totalDe(List<DeudaAcogida> acogible) {
        Dinero total = Dinero.CERO;
        for (DeudaAcogida cuota : acogible) {
            total = total.mas(cuota.total());
        }
        return total;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(Convenio convenio) {
        return "{\"numero\":\""
                + convenio.numero().impreso()
                + "\",\"tipo\":\""
                + convenio.tipo()
                + "\",\"montoTotal\":"
                + convenio.montoTotal().valor().toPlainString()
                + ",\"cuotaInicial\":"
                + convenio.cuotaInicial().valor().toPlainString()
                + ",\"cuotas\":"
                + convenio.numeroDeCuotas()
                + ",\"conjuntoId\":"
                + convenio.condiciones().conjuntoId()
                + ",\"fechaCorte\":\""
                + convenio.fechaCorte()
                + "\"}";
    }

    /**
     * Lo que la pantalla de fraccionamiento pide.
     *
     * <p>Un tipo y no once argumentos: es la frontera donde mas facil es intercambiar dos
     * parametros del mismo tipo sin que el compilador diga nada.
     *
     * @param contribuyenteId a quien se le fracciona; lo resolvio el borde HTTP
     * @param obligaciones las deudas marcadas en la grilla
     * @param tipo si el convenio es ordinario o coactivo
     * @param fecha el dia del convenio; entra como argumento (regla 6)
     * @param fechaDeCorte a que fecha se lee la deuda que se acoge (regla 9)
     * @param cuotas cuantas cuotas se piden, sin contar la inicial
     * @param porcentajeInicial que parte se paga en el acto; lo elige la ventanilla
     * @param primeraCuotaVence cuando vence la primera cuota
     * @param tipoGarantia el ofrecimiento de garantia, si lo hubo
     * @param detalleGarantia la descripcion del bien ofrecido
     * @param resolucion la resolucion que lo aprueba, si consta
     * @param convenioOrigenId el convenio que este reformula, si sale de una reformulacion
     */
    public record Peticion(
            long contribuyenteId,
            List<SeleccionDeObligacion> obligaciones,
            TipoDeConvenio tipo,
            LocalDate fecha,
            LocalDate fechaDeCorte,
            int cuotas,
            Alicuota porcentajeInicial,
            LocalDate primeraCuotaVence,
            @Nullable TipoDeGarantia tipoGarantia,
            @Nullable String detalleGarantia,
            @Nullable String resolucion,
            @Nullable Long convenioOrigenId) {

        public Peticion {
            Objects.requireNonNull(obligaciones, "La lista es vacia, no nula");
            Objects.requireNonNull(tipo, "Hay que decir que clase de convenio es");
            Objects.requireNonNull(fecha, "El convenio es de un dia concreto (regla 6)");
            Objects.requireNonNull(fechaDeCorte, "La deuda se lee a una fecha (regla 9)");
            Objects.requireNonNull(
                    porcentajeInicial, "Hay que decir que porcentaje se paga de inicial");
            Objects.requireNonNull(primeraCuotaVence, "La primera cuota vence en una fecha");
            obligaciones = List.copyOf(obligaciones);
            if (obligaciones.isEmpty()) {
                throw new IllegalArgumentException(
                        "Hay que marcar al menos una deuda: un convenio sin deuda acogida no"
                                + " fracciona nada");
            }
            if (contribuyenteId <= 0) {
                throw new IllegalArgumentException("El convenio es de un contribuyente concreto");
            }
            if (primeraCuotaVence.isBefore(fecha)) {
                throw new IllegalArgumentException(
                        "La primera cuota no puede vencer antes de firmarse el convenio: "
                                + primeraCuotaVence
                                + " es anterior a "
                                + fecha);
            }
        }

        /**
         * La misma peticion, sabiendo de que convenio sale.
         *
         * <p>Solo lo usa la reformulacion: el convenio de origen no se conoce hasta que {@code
         * CerrarConvenio} lo ha leido, y dejarlo entrar por el borde HTTP permitiria encadenar un
         * preconvenio a cualquier convenio ajeno.
         */
        public Peticion conOrigen(long convenioOrigen) {
            return new Peticion(
                    contribuyenteId,
                    obligaciones,
                    tipo,
                    fecha,
                    fechaDeCorte,
                    cuotas,
                    porcentajeInicial,
                    primeraCuotaVence,
                    tipoGarantia,
                    detalleGarantia,
                    resolucion,
                    convenioOrigen);
        }
    }

    /**
     * El cronograma simulado y la deuda que lo sustenta, sin nada escrito.
     *
     * @param acogible la deuda que se acogeria, cuota por cuota y con su fase de origen
     * @param cronograma la cuota inicial y las cuotas
     * @param condiciones las condiciones con que se calculo, y de que conjunto salieron
     * @param total lo acogido a la fecha de corte
     * @param aLaFecha la fecha de corte con que se leyo (regla 9, RNF-075)
     */
    public record Simulacion(
            List<DeudaAcogida> acogible,
            List<CuotaDeConvenio> cronograma,
            CondicionesDelConvenio condiciones,
            Dinero total,
            LocalDate aLaFecha) {

        public Simulacion {
            acogible = List.copyOf(acogible);
            cronograma = List.copyOf(cronograma);
            Objects.requireNonNull(condiciones, "La simulacion dice con que condiciones se hizo");
            Objects.requireNonNull(total, "La simulacion dice cuanto acoge");
            Objects.requireNonNull(aLaFecha, "Toda cifra indica su fecha (RNF-075, regla 9)");
        }
    }

    /**
     * Esa clave de idempotencia registro el convenio de otro contribuyente.
     *
     * <p>Un reenvio es el <b>mismo</b> intento repetido. Si la clave viene con otro sujeto, quien
     * la manda no esta reintentando: esta reusando una clave vieja, y devolverle el convenio de la
     * primera vez le imprimiria en ventanilla el acuerdo de otra persona. Quien llama responde 409.
     */
    public static final class ClaveDeOtraPeticion extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        ClaveDeOtraPeticion(String numero) {
            super(
                    "Esa clave de idempotencia ya registro el convenio "
                            + numero
                            + ", que es de otro contribuyente: reenviar un intento devuelve lo de"
                            + " la primera vez, no lo de otra peticion. Use una clave nueva");
        }
    }

    /** La seleccion no tiene deuda a esa fecha: no hay nada que fraccionar. */
    public static final class SinDeudaQueFraccionar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinDeudaQueFraccionar(LocalDate fechaDeCorte) {
            super(
                    "Ninguna de las obligaciones marcadas tenia deuda al "
                            + fechaDeCorte
                            + ": o ya se pagaron, o nunca se determinaron. Un convenio sobre cero"
                            + " no es un convenio");
        }
    }
}

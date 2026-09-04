package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibre;
import kamayuk.rentas.sanciones.dominio.ConstanciaLibreRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.PadronDePapeletasRepository;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite la constancia con que la municipalidad acredita que un vehículo no registra papeletas de
 * tránsito pendientes (#53, RF-068).
 *
 * <h2>Se niega si hay una sola papeleta pendiente, y dice cuáles</h2>
 *
 * <p>Es el segundo criterio de aceptación de #53. La negativa no es un «no se puede»: enumera los
 * números de las papeletas que lo impiden, porque quien vino a pedir la constancia lo que necesita
 * saber es qué tiene que pagar. Una negativa sin la lista lo manda a otra ventanilla a preguntar lo
 * que este mismo servicio ya sabe.
 *
 * <h2>«Pendiente» es pendiente <b>a una fecha</b>, y la fecha entra como argumento</h2>
 *
 * <p>Regla 9, RNF-075. {@code verificadaAl} acota qué infracciones cuentan: las cometidas hasta ese
 * día. Resolverla con el reloj dentro de la consulta —en vez de recibirla— haría que una constancia
 * pedida «al 30 de abril» contara una papeleta del 10 de mayo, y la constancia diría lo contrario
 * de lo que se le preguntó.
 *
 * <p>Lo que la fecha <b>no</b> puede reconstruir es el estado de cobranza de aquel día: {@code
 * papeleta.estado} es el de ahora. Por eso {@code verificada_al} se guarda con la constancia y sale
 * impresa: el papel afirma lo que se comprobó, y dice cuándo se comprobó.
 *
 * <h2>El documento nace con la constancia, en la misma transacción</h2>
 *
 * <p>El número de la constancia <b>es</b> el del documento emitido, como en {@code
 * resolucion_gerencia} (V41 §3) y {@code acto_coactivo} (V34). No hay correlativo propio: dos
 * numeraciones para el mismo papel divergen.
 */
@Service
public class EmitirConstanciaLibre {

    private static final String TABLA_AUDITADA = "constancia_libre";

    /** El tipo con el que se numera la constancia en {@code documento_emitido}. */
    static final String TIPO_DE_DOCUMENTO = "CLI";

    /**
     * Cuántas papeletas pendientes se enumeran en la negativa.
     *
     * <p>Un tope, no un filtro: quien tiene cuarenta papeletas no necesita verlas todas para
     * entender que no le van a dar la constancia, y la respuesta dice cuántas hay en total.
     */
    private static final int PENDIENTES_QUE_SE_ENUMERAN = 20;

    private final PadronDePapeletasRepository padron;
    private final ConstanciaLibreRepository constancias;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public EmitirConstanciaLibre(
            PadronDePapeletasRepository padron,
            ConstanciaLibreRepository constancias,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.padron = padron;
        this.constancias = constancias;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Comprueba y emite.
     *
     * @param peticion la placa, quién la pide y a qué fecha se acredita
     * @param formato en qué formato sale el papel (RF-132)
     * @param observacion por qué se emite (regla 10, RNF-052)
     * @throws HayPapeletasPendientes si el vehículo debe alguna a esa fecha
     */
    @Transactional
    public Emitida emitir(Peticion peticion, FormatoDeDocumento formato, Observacion observacion) {

        Objects.requireNonNull(peticion, "No hay constancia que emitir");
        Objects.requireNonNull(formato, "La constancia sale en un formato");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        LocalDate verificadaAl = peticion.verificadaAl();
        List<PapeletaDelPadron> pendientes = pendientesDe(peticion.placa(), verificadaAl);
        if (!pendientes.isEmpty()) {
            throw new HayPapeletasPendientes(peticion.placa(), verificadaAl, pendientes);
        }

        LocalDate hoy = LocalDate.now(reloj);
        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(hoy),
                        peticion.placa(),
                        ModeloDeLaConstanciaLibre.de(
                                peticion.placa(), peticion.solicitante(), verificadaAl, hoy),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        ConstanciaLibre guardada =
                constancias.registrar(
                        ConstanciaLibre.nueva(
                                emision.registro().numero(),
                                documentoId,
                                peticion.placa(),
                                peticion.vehiculoId(),
                                peticion.solicitanteId(),
                                verificadaAl,
                                hoy,
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                hoy,
                                TABLA_AUDITADA,
                                String.valueOf(guardada.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada)));

        return new Emitida(guardada, emision);
    }

    // ------------------------------------------------------------------

    /**
     * Las papeletas de tránsito de esa placa que siguen debiéndose, contando solo las cometidas
     * hasta {@code verificadaAl}.
     *
     * <p>Se leen por lote acotado y no enteras: para decidir la negativa basta con que haya una, y
     * para enumerarla basta con las primeras. Un vehículo con doscientas papeletas no tiene por qué
     * traerlas todas a memoria para que le digan que no.
     */
    private List<PapeletaDelPadron> pendientesDe(String placa, LocalDate verificadaAl) {
        CriterioDePadron criterio =
                new CriterioDePadron(
                        Familia.TRANSITO,
                        null,
                        verificadaAl,
                        null,
                        null,
                        placa,
                        null,
                        null,
                        null,
                        null,
                        true);
        return padron.siguientes(criterio, 0, PENDIENTES_QUE_SE_ENUMERAN);
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(ConstanciaLibre constancia) {
        return "{\"numero\":\""
                + constancia.numero()
                + "\",\"placa\":\""
                + constancia.placa()
                + "\",\"verificadaAl\":\""
                + constancia.verificadaAl()
                + "\"}";
    }

    /**
     * Lo que la pantalla manda para pedir una constancia.
     *
     * @param placa el vehículo sobre el que se acredita
     * @param vehiculoId el vehículo del padrón, si está registrado; una placa que no lo está
     *     también puede pedir la constancia
     * @param solicitanteId quién la pide, si se identificó
     * @param solicitante su nombre, para el papel
     * @param verificadaAl el día al que se acredita (regla 9)
     */
    public record Peticion(
            String placa,
            @Nullable Long vehiculoId,
            @Nullable Long solicitanteId,
            @Nullable String solicitante,
            LocalDate verificadaAl) {

        public Peticion {
            Objects.requireNonNull(placa, "Falta la placa del vehiculo");
            Objects.requireNonNull(
                    verificadaAl,
                    "Falta a que dia se acredita: «no tiene papeletas pendientes» es cierto o falso"
                            + " segun el dia (regla 9, RNF-075)");
            placa = placa.strip().toUpperCase(java.util.Locale.ROOT);
            if (placa.isEmpty()) {
                throw new IllegalArgumentException("La placa no puede estar en blanco");
            }
        }
    }

    /** La constancia guardada y los bytes que se entregan. */
    public record Emitida(ConstanciaLibre constancia, EmitirDocumento.Emision emision) {}

    /** El vehículo debe papeletas a esa fecha: no se le puede acreditar lo contrario. */
    public static final class HayPapeletasPendientes extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final List<String> numeros;

        HayPapeletasPendientes(
                String placa, LocalDate verificadaAl, List<PapeletaDelPadron> pendientes) {
            super(
                    "El vehiculo "
                            + placa
                            + " registra papeletas de transito pendientes al "
                            + verificadaAl
                            + ": "
                            + String.join(", ", numerosDe(pendientes))
                            + ". No se puede emitir una constancia que diga lo contrario");
            this.numeros = numerosDe(pendientes);
        }

        /** Los números que impiden la constancia, para que la respuesta los enumere. */
        public List<String> numeros() {
            return numeros;
        }

        private static List<String> numerosDe(List<PapeletaDelPadron> pendientes) {
            return pendientes.stream().map(PapeletaDelPadron::numero).toList();
        }
    }
}

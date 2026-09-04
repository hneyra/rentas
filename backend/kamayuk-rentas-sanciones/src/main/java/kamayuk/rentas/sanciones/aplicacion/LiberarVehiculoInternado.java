package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.EstadoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Internamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoRepository;
import kamayuk.rentas.sanciones.dominio.MovimientoDeInternamiento;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeMovimientoDeInternamiento;
import kamayuk.rentas.tesoreria.CobrosDeTasas;
import kamayuk.rentas.tesoreria.TasaCobrada;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Libera un vehículo del depósito municipal, o declara su abandono (#50, RF-064).
 *
 * <h2>La custodia pagada se comprueba contra la caja, no contra una casilla</h2>
 *
 * <p>Es el AC 3 de #50, y aquí está entero. El prototipo dibuja «Custodia cancelada» como una
 * casilla que quien libera marca; una casilla no es una comprobación —quien entrega el vehículo es
 * quien la marca—. Lo que este caso de uso hace es pedirle a {@code tesoreria}, <b>por su API
 * pública</b> ({@link CobrosDeTasas}), que acredite que ese recibo existe, sigue vigente —no
 * anulado— y cobró el concepto del TUPA con el que este internamiento devenga custodia. Si no lo
 * acredita, el vehículo no sale.
 *
 * <p>La otra mitad de la guarda está en la base: {@code internamiento_liberacion_ck} (V41) exige
 * que la fila de tipo {@code LIBERACION} traiga el recibo, los días, quién retira y su documento.
 * La base no puede comprobar que ese recibo exista —vive en otro módulo, y eso es un {@code JOIN}
 * entre contextos—; este código no puede impedir que alguien escriba la fila por SQL directo. Las
 * dos juntas, como siempre.
 *
 * <h2>Se libera agregando, nunca editando</h2>
 *
 * <p>V41 le retira a {@code internamiento} la columna {@code fecha_salida} y el privilegio de
 * {@code UPDATE}: la salida es una fila de {@code internamiento_movimiento} con su acta, y el
 * {@link EstadoDeInternamiento} se deriva de ahí. Dos liberaciones del mismo internamiento las
 * rechaza {@code internamiento_liberacion_uq}, no un {@code if}.
 */
@Service
public class LiberarVehiculoInternado {

    private static final String TABLA_AUDITADA = "internamiento_movimiento";

    private final InternamientoRepository internamientos;
    private final PapeletaRepository papeletas;
    private final CobrosDeTasas cobros;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public LiberarVehiculoInternado(
            InternamientoRepository internamientos,
            PapeletaRepository papeletas,
            CobrosDeTasas cobros,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.internamientos = internamientos;
        this.papeletas = papeletas;
        this.cobros = cobros;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Entrega el vehículo a quien lo retira y emite el acta de liberación.
     *
     * @param peticion lo que la pantalla manda
     * @param formato en qué formato sale el acta
     * @param observacion por qué se libera (regla 10, RNF-052)
     * @throws VehiculoNoInternado si la placa no tiene ningún internamiento vigente
     * @throws CustodiaSinPagar si el recibo no acredita el pago del concepto de custodia
     * @throws LiberacionAnteriorAlIngreso si la fecha de salida es anterior a la de entrada
     */
    @Transactional
    public Liberado liberar(
            Peticion peticion, FormatoDeDocumento formato, Observacion observacion) {

        Internamiento internamiento = vigenteDe(peticion.placa());
        LocalDate ingreso = LocalDate.ofInstant(internamiento.fechaIngreso(), ZoneOffset.UTC);
        if (peticion.fecha().isBefore(ingreso)) {
            throw new LiberacionAnteriorAlIngreso(internamiento, peticion.fecha());
        }

        // LA COMPROBACION DEL AC 3. Contra tesoreria, y por el concepto con el que ESTE
        // internamiento devenga custodia: acreditar cualquier recibo dejaria salir un vehiculo
        // con el recibo del derecho de tramite de otra cosa.
        TasaCobrada custodia =
                cobros.acreditar(peticion.reciboCustodia(), internamiento.tasaCustodia())
                        .orElseThrow(
                                () ->
                                        new CustodiaSinPagar(
                                                internamiento, peticion.reciboCustodia()));

        int dias = (int) Math.max(0, ChronoUnit.DAYS.between(ingreso, peticion.fecha()));
        Papeleta papeleta = papeletaDe(internamiento);

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TipoDeMovimientoDeInternamiento.LIBERACION.tipoDeDocumento(),
                        Ejercicio.de(peticion.fecha()),
                        internamiento.placa(),
                        ModeloDelActaDeInternamiento.delMovimiento(
                                internamiento,
                                papeleta == null ? null : papeleta.numero(),
                                TipoDeMovimientoDeInternamiento.LIBERACION,
                                peticion.fecha(),
                                dias,
                                peticion.personaRetira(),
                                peticion.documentoRetira(),
                                peticion.soatAcreditado(),
                                custodia),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        MovimientoDeInternamiento guardado =
                internamientos.registrar(
                        MovimientoDeInternamiento.liberacion(
                                internamiento.identificador(),
                                peticion.fecha(),
                                emision.registro().numero(),
                                documentoId,
                                custodia.numeroDeRecibo(),
                                dias,
                                peticion.personaRetira(),
                                peticion.documentoRetira(),
                                peticion.soatAcreditado(),
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fecha(),
                                TABLA_AUDITADA,
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(internamiento, guardado)));

        List<MovimientoDeInternamiento> historial =
                internamientos.movimientosDe(internamiento.identificador());
        return new Liberado(
                internamiento,
                guardado,
                emision,
                custodia,
                EstadoDeInternamiento.delHistorial(historial));
    }

    // ------------------------------------------------------------------

    private Internamiento vigenteDe(String placa) {
        return internamientos
                .vigenteDePlaca(placa)
                .orElseThrow(() -> new VehiculoNoInternado(placa));
    }

    private @Nullable Papeleta papeletaDe(Internamiento internamiento) {
        Long papeletaId = internamiento.papeletaId();
        if (papeletaId == null) {
            return null;
        }
        return papeletas.porId(papeletaId).orElse(null);
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(
            Internamiento internamiento, MovimientoDeInternamiento movimiento) {
        return "{\"placa\":\""
                + internamiento.placa()
                + "\",\"tipo\":\""
                + movimiento.tipo().name()
                + "\",\"acta\":\""
                + movimiento.acta()
                + "\",\"dias\":"
                + movimiento.diasCustodia()
                + "}";
    }

    /**
     * Lo que la pantalla manda para liberar un vehículo.
     *
     * @param placa la placa del vehículo que se retira
     * @param fecha el día de la liberación
     * @param reciboCustodia el recibo con que se pagó la custodia, como está impreso
     * @param personaRetira quién retira el vehículo
     * @param documentoRetira su documento de identidad
     * @param soatAcreditado si se acreditó el SOAT vigente
     */
    public record Peticion(
            String placa,
            LocalDate fecha,
            String reciboCustodia,
            String personaRetira,
            String documentoRetira,
            boolean soatAcreditado) {

        public Peticion {
            Objects.requireNonNull(placa, "Falta la placa del vehiculo");
            Objects.requireNonNull(fecha, "Falta la fecha de liberacion");
            Objects.requireNonNull(reciboCustodia, "Falta el recibo de la custodia");
            Objects.requireNonNull(personaRetira, "Falta quien retira el vehiculo");
            Objects.requireNonNull(documentoRetira, "Falta el documento de quien retira");
        }
    }

    /**
     * El vehículo liberado, con el acta que salió.
     *
     * @param internamiento el ingreso que se cierra
     * @param movimiento la fila registrada
     * @param acta los bytes del acta y su registro
     * @param custodia lo que la caja acreditó, con su fecha (regla 9, RNF-075)
     * @param estado el estado en que queda el internamiento
     */
    public record Liberado(
            Internamiento internamiento,
            MovimientoDeInternamiento movimiento,
            EmitirDocumento.Emision acta,
            TasaCobrada custodia,
            EstadoDeInternamiento estado) {}

    /** No hay ningún internamiento vigente con esa placa. */
    public static final class VehiculoNoInternado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        VehiculoNoInternado(String placa) {
            super(
                    "El vehiculo de placa "
                            + placa
                            + " no tiene ningun internamiento vigente: o nunca entro al deposito, o"
                            + " ya se libero");
        }
    }

    /** El recibo no acredita el pago de la custodia. */
    public static final class CustodiaSinPagar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        CustodiaSinPagar(Internamiento internamiento, String recibo) {
            super(
                    "El recibo "
                            + recibo
                            + " no acredita el pago del concepto "
                            + internamiento.tasaCustodia()
                            + ": o no existe, o esta anulado, o cobro otra cosa. El vehiculo de"
                            + " placa "
                            + internamiento.placa()
                            + " no sale del deposito sin la custodia cancelada");
        }
    }

    /** La liberación es anterior al ingreso. */
    public static final class LiberacionAnteriorAlIngreso extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        LiberacionAnteriorAlIngreso(Internamiento internamiento, LocalDate fecha) {
            super(
                    "El vehiculo de placa "
                            + internamiento.placa()
                            + " entro el "
                            + internamiento.fechaIngreso()
                            + ": no se pudo liberar el "
                            + fecha);
        }
    }
}

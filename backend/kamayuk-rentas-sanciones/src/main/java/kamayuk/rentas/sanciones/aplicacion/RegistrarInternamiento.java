package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Internamiento;
import kamayuk.rentas.sanciones.dominio.InternamientoRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra el ingreso de un vehículo al depósito municipal, con su acta (#50, RF-064).
 *
 * <h2>El acta nace con el ingreso, no después</h2>
 *
 * <p>El documento se emite en la <b>misma transacción</b>, con {@link EmitirDocumento}: el número
 * del acta <b>es</b> el del documento, y el documento guarda los datos con que se dibujó más el
 * SHA-256 de lo que salió. Un vehículo internado sin acta es un vehículo retenido sin papel, y el
 * conductor se va sin nada que enseñar.
 *
 * <h2>Un vehículo no entra dos veces sin haber salido</h2>
 *
 * <p>Si la placa ya tiene un internamiento vigente —uno sin liberación—, este caso de uso lo
 * rechaza. No es una comprobación cosmética: con dos ingresos abiertos, {@code
 * LiberarVehiculoInternado} tendría que elegir cuál libera, y la elección la haría el orden de las
 * filas.
 *
 * <h2>El concepto de la custodia, no su tarifa</h2>
 *
 * <p>Lo que se guarda es el <b>código</b> del concepto del TUPA con que se cobrará la custodia
 * diaria. La tarifa vive en {@code tasa} con su vigencia (regla 5, ADR-0007) y la pone la caja al
 * cobrar; copiarla aquí la pondría en dos sitios y uno de los dos mentiría el día que la ordenanza
 * la cambie.
 */
@Service
public class RegistrarInternamiento {

    private static final String TABLA_AUDITADA = "internamiento";

    /** El tipo con el que se numera el acta de ingreso en {@code documento_emitido}. */
    static final String TIPO_DE_DOCUMENTO = "ACTA_INTERNAMIENTO";

    private final InternamientoRepository internamientos;
    private final PapeletaRepository papeletas;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarInternamiento(
            InternamientoRepository internamientos,
            PapeletaRepository papeletas,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.internamientos = internamientos;
        this.papeletas = papeletas;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Interna el vehículo y emite su acta.
     *
     * @param peticion lo que la pantalla manda
     * @param formato en qué formato sale el acta
     * @param observacion por qué se interna (regla 10, RNF-052)
     * @throws VehiculoYaInternado si la placa tiene un internamiento sin liberar
     * @throws RegistrarDescargo.PapeletaInexistente si se enlaza a una papeleta que no existe
     */
    @Transactional
    public Internado internar(
            Peticion peticion, FormatoDeDocumento formato, Observacion observacion) {

        internamientos
                .vigenteDePlaca(peticion.placa())
                .ifPresent(
                        abierto -> {
                            throw new VehiculoYaInternado(abierto);
                        });

        Papeleta papeleta = papeletaDe(peticion);
        LocalDate dia = LocalDate.ofInstant(peticion.fechaIngreso(), ZoneOffset.UTC);
        Instant ahora = reloj.instant();

        // El acta se emite ANTES de insertar la fila porque su numero ES el de la fila: el
        // internamiento no puede existir sin documento (internamiento.documento_id NOT NULL), y
        // emitir despues obligaria a un UPDATE que V41 no concede. Por eso el modelo se compone de
        // los datos de la peticion y NO lleva dentro el numero del acta: conocerlo antes de
        // pedirlo exigiria una segunda numeracion propia, que es lo que V41 retiro.
        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        Ejercicio.de(dia),
                        peticion.placa(),
                        ModeloDelActaDeInternamiento.delIngreso(
                                peticion.placa(),
                                peticion.deposito(),
                                peticion.fechaIngreso(),
                                peticion.tasaCustodia(),
                                papeleta == null ? null : papeleta.numero(),
                                dia,
                                peticion.motivo()),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        Internamiento guardado =
                internamientos.registrar(
                        Internamiento.nuevo(
                                papeleta == null ? null : papeleta.identificador(),
                                peticion.vehiculoId(),
                                peticion.placa(),
                                peticion.deposito(),
                                peticion.fechaIngreso(),
                                emision.registro().numero(),
                                documentoId,
                                peticion.tasaCustodia(),
                                ahora,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                dia,
                                TABLA_AUDITADA,
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardado)));

        return new Internado(guardado, emision);
    }

    // ------------------------------------------------------------------

    private @Nullable Papeleta papeletaDe(Peticion peticion) {
        String numero = peticion.numeroDePapeleta();
        if (numero == null || numero.isBlank()) {
            return null;
        }
        return papeletas
                .porNumero(Familia.TRANSITO, numero)
                .orElseThrow(
                        () -> new RegistrarDescargo.PapeletaInexistente(Familia.TRANSITO, numero));
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(Internamiento internamiento) {
        return "{\"placa\":\""
                + internamiento.placa()
                + "\",\"deposito\":\""
                + internamiento.deposito()
                + "\",\"acta\":\""
                + internamiento.acta()
                + "\"}";
    }

    /**
     * Lo que la pantalla manda para internar un vehículo.
     *
     * @param placa la placa del vehículo
     * @param vehiculoId el vehículo del padrón, si está registrado
     * @param numeroDePapeleta la papeleta que dispuso la medida preventiva, si la hubo
     * @param deposito dónde queda
     * @param fechaIngreso cuándo entró
     * @param tasaCustodia el código del concepto del TUPA con que se cobra la custodia
     * @param motivo por qué se interna, para el acta
     */
    public record Peticion(
            String placa,
            @Nullable Long vehiculoId,
            @Nullable String numeroDePapeleta,
            String deposito,
            Instant fechaIngreso,
            String tasaCustodia,
            String motivo) {

        public Peticion {
            Objects.requireNonNull(placa, "Falta la placa del vehiculo");
            Objects.requireNonNull(deposito, "Falta el deposito");
            Objects.requireNonNull(fechaIngreso, "Falta la fecha de ingreso");
            Objects.requireNonNull(tasaCustodia, "Falta el concepto con que se cobra la custodia");
            Objects.requireNonNull(motivo, "Falta el motivo del internamiento");
        }
    }

    /**
     * El vehículo internado, con el acta que salió.
     *
     * @param internamiento la fila registrada
     * @param acta los bytes del acta y su registro
     */
    public record Internado(Internamiento internamiento, EmitirDocumento.Emision acta) {}

    /** La placa ya tiene un internamiento sin liberar. */
    public static final class VehiculoYaInternado extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        VehiculoYaInternado(Internamiento abierto) {
            super(
                    "El vehiculo de placa "
                            + abierto.placa()
                            + " ya esta internado desde el "
                            + abierto.fechaIngreso()
                            + " con el acta "
                            + abierto.acta()
                            + ": primero se libera, y despues se vuelve a internar");
        }
    }
}

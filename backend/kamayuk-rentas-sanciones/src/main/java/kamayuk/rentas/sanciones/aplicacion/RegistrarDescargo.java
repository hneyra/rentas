package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.sanciones.dominio.Descargo;
import kamayuk.rentas.sanciones.dominio.DescargoRepository;
import kamayuk.rentas.sanciones.dominio.EstadoDePapeleta;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import kamayuk.rentas.sanciones.dominio.TipoDeRecurso;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra el descargo que un administrado presenta contra una papeleta (#50, RF-064).
 *
 * <h2>El plazo se resuelve aquí, una vez, y la fila lo copia</h2>
 *
 * <p>El día hasta el que el recurso era admisible se deriva de la fecha de la papeleta y del plazo
 * <b>parametrizado</b> del conjunto sellado vigente entonces ({@link
 * PlazosDeSancionesParametrizados}), con el calendario de días hábiles de ese mismo conjunto. El
 * resultado —y el conjunto del que salió— quedan escritos en la fila. Releerlos dentro de dos años
 * daría otra fecha el día que el plazo cambie, y entonces un recurso admitido pasaría a estar fuera
 * de plazo sin que nadie hubiera tocado nada (ARQ-09 §3).
 *
 * <h2>Un recurso tardío se registra, no se rechaza</h2>
 *
 * <p>Lo que corresponde con un descargo fuera de plazo es declararlo <b>improcedente</b>, y eso es
 * una resolución de gerencia: para dictarla hay que poder registrar el escrito. Lo que este caso de
 * uso no permite es que la fila mienta sobre si llegó a tiempo; de eso se encarga el propio {@link
 * Descargo} y, detrás, {@code descargo_plazo_ck} (V41).
 *
 * <h2>Sobre una papeleta que siga viva</h2>
 *
 * <p>No se descarga contra una papeleta anulada ni prescrita: no hay nada que impugnar. Sí contra
 * una pagada —el manual admite el reclamo por pago indebido— y contra una que ya esté en coactiva,
 * porque el recurso es justamente lo que puede suspender el procedimiento.
 */
@Service
public class RegistrarDescargo {

    private static final String TABLA_AUDITADA = "descargo";

    private final PapeletaRepository papeletas;
    private final DescargoRepository descargos;
    private final PlazosDeSancionesParametrizados plazos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarDescargo(
            PapeletaRepository papeletas,
            DescargoRepository descargos,
            PlazosDeSancionesParametrizados plazos,
            Auditoria auditoria,
            Clock reloj) {
        this.papeletas = papeletas;
        this.descargos = descargos;
        this.plazos = plazos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Registra el escrito.
     *
     * @param familia de qué familia es la papeleta impugnada
     * @param numeroDePapeleta el número impreso de la papeleta
     * @param peticion lo que la pantalla manda
     * @param observacion por qué se registra (regla 10, RNF-052)
     * @throws PapeletaInexistente si no hay ninguna papeleta con ese número en esa familia
     * @throws PapeletaSinNadaQueImpugnar si la papeleta está anulada o prescrita
     */
    @Transactional
    public Registrado registrar(
            Familia familia, String numeroDePapeleta, Peticion peticion, Observacion observacion) {

        Papeleta papeleta =
                papeletas
                        .porNumero(familia, numeroDePapeleta)
                        .orElseThrow(() -> new PapeletaInexistente(familia, numeroDePapeleta));

        if (papeleta.estado() == EstadoDePapeleta.ANULADA
                || papeleta.estado() == EstadoDePapeleta.PRESCRITA) {
            throw new PapeletaSinNadaQueImpugnar(papeleta);
        }

        PlazosDeSancionesParametrizados.Vigentes vigentes =
                plazos.aLaFechaDe(papeleta.fechaInfraccion());
        Plazo plazo = vigentes.paraDescargar();
        CalendarioHabil calendario = vigentes.calendario();
        LocalDate presentadoHasta =
                plazo.vencimientoDesde(
                        calendario.siguienteHabil(papeleta.fechaInfraccion()), calendario);

        Descargo guardado =
                descargos.insertar(
                        Descargo.nuevo(
                                papeleta.identificador(),
                                peticion.numeroExpediente(),
                                peticion.fechaPresentacion(),
                                peticion.tipoRecurso(),
                                peticion.sustento(),
                                presentadoHasta,
                                vigentes.conjuntoId(),
                                reloj.instant(),
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                peticion.fechaPresentacion(),
                                TABLA_AUDITADA,
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(papeleta, guardado)));

        return new Registrado(guardado, papeleta, plazo);
    }

    // ------------------------------------------------------------------

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(Papeleta papeleta, Descargo descargo) {
        return "{\"papeleta\":\""
                + papeleta.numero()
                + "\",\"expediente\":\""
                + descargo.numeroExpediente()
                + "\",\"recurso\":\""
                + descargo.tipoRecurso()
                + "\",\"enPlazo\":"
                + descargo.enPlazo()
                + "}";
    }

    /**
     * Lo que la pantalla manda para registrar un descargo.
     *
     * @param numeroExpediente el número con que entra por mesa de partes
     * @param fechaPresentacion el día en que se presentó
     * @param tipoRecurso qué recurso es
     * @param sustento el fundamento del administrado
     */
    public record Peticion(
            String numeroExpediente,
            LocalDate fechaPresentacion,
            TipoDeRecurso tipoRecurso,
            String sustento) {

        public Peticion {
            java.util.Objects.requireNonNull(numeroExpediente, "Falta el numero de expediente");
            java.util.Objects.requireNonNull(fechaPresentacion, "Falta la fecha de presentacion");
            java.util.Objects.requireNonNull(tipoRecurso, "Falta el tipo de recurso");
            java.util.Objects.requireNonNull(sustento, "Falta el fundamento del administrado");
        }
    }

    /**
     * El descargo registrado, con la papeleta que impugna y el plazo con que se calculó.
     *
     * @param descargo la fila guardada
     * @param papeleta la papeleta impugnada
     * @param plazo el plazo parametrizado que se aplicó; se devuelve para que la pantalla pueda
     *     decir «5 días hábiles» sin que nadie lo escriba a mano
     */
    public record Registrado(Descargo descargo, Papeleta papeleta, Plazo plazo) {}

    /** No hay ninguna papeleta con ese número en esa familia. */
    public static final class PapeletaInexistente extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PapeletaInexistente(Familia familia, String numero) {
            super(
                    "No hay ninguna papeleta "
                            + familia
                            + " con el numero '"
                            + numero
                            + "' en esta municipalidad");
        }
    }

    /** La papeleta ya está anulada o prescrita: no queda nada que impugnar. */
    public static final class PapeletaSinNadaQueImpugnar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PapeletaSinNadaQueImpugnar(Papeleta papeleta) {
            super(
                    "La papeleta "
                            + papeleta.numero()
                            + " esta "
                            + papeleta.estado()
                            + ": un recurso contra una multa que ya no existe no tiene objeto");
        }
    }
}

package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.CalendarioHabil;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.OrdenDeLosActos;
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
 *
 * <p><b>Y hasta #267 esta guarda no descartaba nada</b>, medido: los dos valores que mira eran los
 * dos que ningún camino de producción podía escribir —el único {@code UPDATE papeleta} de {@code
 * src/main} era {@code SET numero}—, así que estaba escrita, probada con dobles, e inerte. Lo que
 * la vuelve real es {@link AnularPapeleta}; {@code PRESCRITA} sigue siendo un valor que sólo un
 * padrón migrado trae, y {@code EstadoDePapeleta} lleva la medida de por qué.
 *
 * <p><b>Y desde #385 tampoco contra una cuya multa dejó sin efecto una resolución de gerencia.</b>
 * Su estado sigue {@code IMPUESTA} —la resolución no lo toca—, así que esto no lo podía ver mirando
 * la columna: registraba otro descargo contra una multa que ya no existe y dejaba dictar otra RIS
 * sobre ella. Lo pregunta al repositorio con {@code EstadoDePapeleta.DEJADA_SIN_EFECTO}, el mismo
 * predicado con que la fase y los padrones la derivan, y la guarda es una sola para los dos casos
 * de uso: {@link #exigirQueQuedeAlgoQueImpugnar}.
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
     * @throws PapeletaSinNadaQueImpugnar si la papeleta está anulada o prescrita, o una resolución
     *     dejó su multa sin efecto (#385)
     * @throws kamayuk.rentas.dominio.ActoFueraDeOrden si se presentó antes de la infracción o
     *     después de hoy (#402)
     */
    @Transactional
    public Registrado registrar(
            Familia familia, String numeroDePapeleta, Peticion peticion, Observacion observacion) {

        Papeleta papeleta =
                papeletas
                        .porNumero(familia, numeroDePapeleta)
                        .orElseThrow(() -> new PapeletaInexistente(familia, numeroDePapeleta));

        exigirQueQuedeAlgoQueImpugnar(papeleta, papeletas);

        // #402: `Descargo` solo exige que `enPlazo` cuadre con `presentadoHasta`, asi que un
        // escrito del 20 de febrero contra una infraccion del 4 de marzo entraba «en plazo».
        OrdenDeLosActos.exigir(
                "la presentacion del recurso " + peticion.numeroExpediente(),
                peticion.fechaPresentacion(),
                LocalDate.now(reloj),
                papeleta.laInfraccion());

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
                                TABLA_AUDITADA,
                                String.valueOf(guardado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(papeleta, guardado)));

        return new Registrado(guardado, papeleta, plazo);
    }

    // ------------------------------------------------------------------

    /**
     * La guarda de los dos casos de uso que impugnan o resuelven una papeleta: este y {@link
     * ResolverConResolucionDeGerencia}.
     *
     * <p>Una sola, para que no haya dos sitios donde olvidar la mitad (#385). Mira el estado
     * —{@code ANULADA} y {@code PRESCRITA}, que sí se escriben o se migran en la columna— y
     * pregunta a la base si una resolución dejó la multa sin efecto, que es lo que la columna no
     * dice.
     *
     * @throws PapeletaSinNadaQueImpugnar si no queda multa que impugnar
     */
    static void exigirQueQuedeAlgoQueImpugnar(Papeleta papeleta, PapeletaRepository papeletas) {
        if (papeleta.estado() == EstadoDePapeleta.ANULADA
                || papeleta.estado() == EstadoDePapeleta.PRESCRITA) {
            throw new PapeletaSinNadaQueImpugnar(papeleta);
        }
        if (papeletas.dejadaSinEfecto(papeleta.identificador())) {
            throw new PapeletaSinNadaQueImpugnar(
                    papeleta, "una resolucion de gerencia dejo su multa sin efecto");
        }
    }

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

    /**
     * La papeleta ya está anulada o prescrita, o su multa quedó sin efecto por una resolución de
     * gerencia (#385): no queda nada que impugnar.
     */
    public static final class PapeletaSinNadaQueImpugnar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PapeletaSinNadaQueImpugnar(Papeleta papeleta) {
            this(papeleta, "esta " + papeleta.estado());
        }

        /**
         * Con el motivo dicho, porque el estado no siempre lo dice: la dejada sin efecto sigue
         * {@code IMPUESTA}, y «esta IMPUESTA: no tiene objeto» sería un mensaje que se contradice.
         */
        PapeletaSinNadaQueImpugnar(Papeleta papeleta, String porque) {
            super(
                    "La papeleta "
                            + papeleta.numero()
                            + " "
                            + porque
                            + ": un recurso contra una multa que ya no existe no tiene objeto");
        }
    }
}

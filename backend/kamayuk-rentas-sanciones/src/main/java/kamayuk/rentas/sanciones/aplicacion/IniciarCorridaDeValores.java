package kamayuk.rentas.sanciones.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.sanciones.dominio.CorridaDeValores;
import kamayuk.rentas.sanciones.dominio.CorridaDeValoresRepository;
import kamayuk.rentas.sanciones.dominio.CriterioDePadron;
import kamayuk.rentas.sanciones.dominio.Familia;
import kamayuk.rentas.sanciones.dominio.OrigenDeLaCorrida;
import kamayuk.rentas.sanciones.dominio.PadronDePapeletasRepository;
import kamayuk.rentas.sanciones.dominio.Papeleta;
import kamayuk.rentas.sanciones.dominio.PapeletaDelPadron;
import kamayuk.rentas.sanciones.dominio.PapeletaRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La primera etapa de una generación masiva de valores por papeletas: registrar el criterio y sus
 * candidatos (#53, RF-066, RF-073).
 *
 * <h2>Esta clase no emite ni un valor, y no numera nada</h2>
 *
 * <p>Registra <b>a quiénes</b> se les va a emitir. La emisión es la etapa siguiente ({@link
 * ProcesarPapeletaDeLaCorrida}) y corre en el perfil batch: una corrida de cuatro mil papeletas
 * puede tardar minutos, y esa espera no tiene por qué competir con la ventanilla por el mismo
 * proceso. Es el mismo reparto que #38 hizo para la corrida por contribuyente.
 *
 * <h2>{@code fechaCriterio} se congela aquí</h2>
 *
 * <p>Es la fecha a la que se mirará la deuda de cada candidato <b>y</b> a la que se comprobará que
 * el plazo de su resolución venció. Reanudar la generación tres días después tiene que emitir
 * exactamente lo mismo que si hubiera terminado el primer día; con «hoy» no lo haría (regla 9,
 * RNF-075).
 *
 * <h2>Por qué la lista de candidatos sí cabe en memoria</h2>
 *
 * <p>Se recorre el padrón por cursor y se guardan solo los identificadores. Cuarenta mil {@code
 * long} son trescientos kilobytes; cuarenta mil papeletas con su código, su obligado y su importe,
 * no. Es la misma frontera que {@code ImprimirCorridaMasiva} dejó escrita en #38: la lista de
 * identificadores cabe, los agregados no.
 */
@Service
public class IniciarCorridaDeValores {

    private static final String TABLA_AUDITADA = "papeleta_masivo";

    /** Cuántos candidatos se leen del padrón por vuelta. No es un límite de negocio: es memoria. */
    private static final int LOTE = 500;

    private final PapeletaRepository papeletas;
    private final PadronDePapeletasRepository padron;
    private final CorridaDeValoresRepository corridas;
    private final Auditoria auditoria;
    private final Clock reloj;

    public IniciarCorridaDeValores(
            PapeletaRepository papeletas,
            PadronDePapeletasRepository padron,
            CorridaDeValoresRepository corridas,
            Auditoria auditoria,
            Clock reloj) {
        this.papeletas = papeletas;
        this.padron = padron;
        this.corridas = corridas;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Registra una corrida con las papeletas que el operador marcó, por número.
     *
     * @throws CandidatosInvalidos si alguno de los números no es una papeleta de esa familia
     * @throws SinCandidatos si la lista queda vacía
     */
    @Transactional
    public CorridaDeValores porSeleccion(
            Familia familia,
            List<String> numeros,
            LocalDate fechaCriterio,
            Observacion observacion) {

        Objects.requireNonNull(familia, "La corrida necesita su familia");
        Objects.requireNonNull(numeros, "La lista es vacia, no nula");
        Objects.requireNonNull(fechaCriterio, "La corrida congela su fecha de criterio (regla 9)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        List<Long> candidatos = new ArrayList<>();
        List<String> desconocidos = new ArrayList<>();
        LocalDate primera = null;
        LocalDate ultima = null;

        // Sin repetidos: marcar dos veces la misma papeleta en la grilla no la emite dos
        // veces, pero reventaria papeleta_masivo_item_uq y se llevaria por delante la
        // corrida entera.
        for (String numero : new LinkedHashSet<>(numeros)) {
            Papeleta papeleta = papeletas.porNumero(familia, numero).orElse(null);
            if (papeleta == null) {
                desconocidos.add(numero);
                continue;
            }
            candidatos.add(papeleta.identificador());
            LocalDate fecha = papeleta.fechaInfraccion();
            primera = primera == null || fecha.isBefore(primera) ? fecha : primera;
            ultima = ultima == null || fecha.isAfter(ultima) ? fecha : ultima;
        }

        if (!desconocidos.isEmpty()) {
            throw new CandidatosInvalidos(familia, desconocidos);
        }
        if (candidatos.isEmpty()) {
            throw new SinCandidatos(
                    "No se marco ninguna papeleta: una corrida sin candidatos no emite nada y deja"
                            + " un criterio registrado que nadie va a mirar");
        }

        return registrar(
                CorridaDeValores.nueva(
                        familia,
                        Objects.requireNonNull(primera),
                        Objects.requireNonNull(ultima),
                        fechaCriterio,
                        OrigenDeLaCorrida.SELECCION,
                        candidatos.size(),
                        reloj.instant(),
                        observacion),
                candidatos);
    }

    /**
     * Registra una corrida con todas las papeletas de la familia en el rango que <b>todavía no
     * tienen valor</b> y siguen debiéndose.
     *
     * @throws SinCandidatos si en ese rango no queda ninguna por formalizar
     */
    @Transactional
    public CorridaDeValores porRango(
            Familia familia,
            LocalDate desde,
            LocalDate hasta,
            LocalDate fechaCriterio,
            Observacion observacion) {

        Objects.requireNonNull(familia, "La corrida necesita su familia");
        Objects.requireNonNull(fechaCriterio, "La corrida congela su fecha de criterio (regla 9)");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        CriterioDePadron criterio = CriterioDePadron.candidatos(familia, desde, hasta);
        List<Long> candidatos = new ArrayList<>();
        long cursor = 0;
        List<PapeletaDelPadron> lote;
        while (!(lote = padron.siguientes(criterio, cursor, LOTE)).isEmpty()) {
            for (PapeletaDelPadron fila : lote) {
                candidatos.add(fila.papeletaId());
            }
            cursor = lote.get(lote.size() - 1).papeletaId();
        }

        if (candidatos.isEmpty()) {
            throw new SinCandidatos(
                    "Entre el "
                            + desde
                            + " y el "
                            + hasta
                            + " no queda ninguna papeleta por formalizar: o ya tienen su resolucion"
                            + " de multa, o no se deben");
        }

        return registrar(
                CorridaDeValores.nueva(
                        familia,
                        desde,
                        hasta,
                        fechaCriterio,
                        OrigenDeLaCorrida.RANGO,
                        candidatos.size(),
                        reloj.instant(),
                        observacion),
                candidatos);
    }

    // ------------------------------------------------------------------

    private CorridaDeValores registrar(CorridaDeValores corrida, List<Long> candidatos) {
        CorridaDeValores guardada = corridas.iniciar(corrida, candidatos);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                TABLA_AUDITADA,
                                String.valueOf(guardada.identificador()),
                                Operacion.ALTA,
                                guardada.observacion())
                        .con(null, descripcion(guardada)));

        return guardada;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoría. */
    private static String descripcion(CorridaDeValores corrida) {
        return "{\"familia\":\""
                + corrida.familia()
                + "\",\"origen\":\""
                + corrida.origen()
                + "\",\"fechaCriterio\":\""
                + corrida.fechaCriterio()
                + "\",\"candidatos\":"
                + corrida.totalCandidatos()
                + "}";
    }

    /** La corrida no seleccionó ninguna papeleta. */
    public static final class SinCandidatos extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinCandidatos(String mensaje) {
            super(mensaje);
        }
    }

    /** Alguno de los números marcados no es una papeleta de esa familia. */
    public static final class CandidatosInvalidos extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final List<String> numeros;

        CandidatosInvalidos(Familia familia, List<String> numeros) {
            super(
                    "No hay papeleta de "
                            + familia
                            + " con estos numeros: "
                            + String.join(", ", numeros));
            this.numeros = List.copyOf(numeros);
        }

        /** Los números que no se encontraron, para que la respuesta los enumere uno a uno. */
        public Set<String> numeros() {
            return new LinkedHashSet<>(numeros);
        }
    }
}

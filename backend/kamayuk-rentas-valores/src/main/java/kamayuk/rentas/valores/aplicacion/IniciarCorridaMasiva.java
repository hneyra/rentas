package kamayuk.rentas.valores.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.aplicacion.LectorDeCsv.FilaCsv;
import kamayuk.rentas.valores.dominio.OrigenDeCriterio;
import kamayuk.rentas.valores.dominio.TipoValor;
import kamayuk.rentas.valores.dominio.ValorMasivo;
import kamayuk.rentas.valores.dominio.ValorMasivoRepository;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registra el criterio de una generacion masiva: la primera de las tres etapas del manual (RF-091,
 * #38).
 *
 * <h2>Todo o nada (RF-133)</h2>
 *
 * <p>Ya sea que la lista de candidatos venga escrita a mano o importada de una hoja de calculo, se
 * resuelve <b>completa</b> antes de guardar nada: si un solo codigo de contribuyente no existe, no
 * se guarda ninguno, y el rechazo dice exactamente cuales fallaron -en una importacion, con su
 * numero de fila-. No hay un camino donde noventa y nueve candidatos entran y el centesimo se
 * descarta en silencio.
 *
 * <h2>Lo que este servicio NO decide</h2>
 *
 * <p>Que un candidato tenga o no deuda a la fecha del criterio no se evalua aqui: eso es la etapa
 * "generacion" ({@link GenerarCorridaMasiva}), y evaluarlo ahora obligaria a consultar la deuda de
 * cada candidato dos veces -una para decidir si entra, otra para congelarla al emitir-. Un
 * candidato sin deuda entra igual a la corrida y sale {@code SIN_DEUDA} cuando se procese (AC de
 * #38).
 */
@Service
public class IniciarCorridaMasiva {

    private final ValorMasivoRepository repositorio;
    private final DirectorioDeContribuyentes contribuyentes;
    private final Clock reloj;

    public IniciarCorridaMasiva(
            ValorMasivoRepository repositorio,
            DirectorioDeContribuyentes contribuyentes,
            Clock reloj) {
        this.repositorio = repositorio;
        this.contribuyentes = contribuyentes;
        this.reloj = reloj;
    }

    /**
     * Registra la corrida con una lista de codigos elegida a mano en la pantalla.
     *
     * @throws SinCandidatos si {@code codigos} llega vacia
     * @throws CandidatosInvalidos si algun codigo no corresponde a ningun contribuyente
     */
    @Transactional
    public ValorMasivo porSeleccion(
            TipoValor tipo,
            @Nullable String tributo,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            @Nullable LocalDate fechaCriterio,
            List<String> codigos,
            Observacion observacion) {

        if (codigos.isEmpty()) {
            throw new SinCandidatos();
        }

        List<String> invalidos = new ArrayList<>();
        Set<Long> vistos = new LinkedHashSet<>();
        for (String codigo : codigos) {
            resolver(codigo).ifPresentOrElse(id -> vistos.add(id), () -> invalidos.add(codigo));
        }
        if (!invalidos.isEmpty()) {
            throw new CandidatosInvalidos(mensajesSinFila(invalidos));
        }

        return registrar(
                tipo,
                tributo,
                ejercicioDesde,
                ejercicioHasta,
                fechaCriterio,
                OrigenDeCriterio.SELECCION,
                List.copyOf(vistos),
                observacion);
    }

    /**
     * Registra la corrida con los codigos de una hoja de calculo (RF-091: "importada de hoja de
     * calculo"). Una columna, {@code codContribuyente}, un candidato por fila.
     *
     * @throws SinCandidatos si el archivo no trae ninguna fila de datos
     * @throws CandidatosInvalidos si alguna fila trae un codigo que no corresponde a ningun
     *     contribuyente; el mensaje nombra la fila (RF-133: "dice cual")
     */
    @Transactional
    public ValorMasivo porImportacion(
            TipoValor tipo,
            @Nullable String tributo,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            @Nullable LocalDate fechaCriterio,
            Reader archivo,
            Observacion observacion)
            throws IOException {

        List<FilaCsv> filas = LectorDeCsv.leer(archivo);
        if (filas.isEmpty()) {
            throw new SinCandidatos();
        }

        List<String> invalidas = new ArrayList<>();
        Set<Long> vistos = new LinkedHashSet<>();
        for (FilaCsv fila : filas) {
            resolver(fila.codigo())
                    .ifPresentOrElse(
                            id -> vistos.add(id),
                            () ->
                                    invalidas.add(
                                            "Fila "
                                                    + fila.numeroDeLinea()
                                                    + ": no existe ningun contribuyente con el"
                                                    + " codigo '"
                                                    + fila.codigo()
                                                    + "'"));
        }
        if (!invalidas.isEmpty()) {
            throw new CandidatosInvalidos(invalidas);
        }

        return registrar(
                tipo,
                tributo,
                ejercicioDesde,
                ejercicioHasta,
                fechaCriterio,
                OrigenDeCriterio.IMPORTACION,
                List.copyOf(vistos),
                observacion);
    }

    private Optional<Long> resolver(@Nullable String codigo) {
        if (codigo == null || codigo.isBlank()) {
            return Optional.empty();
        }
        return contribuyentes.porCodigo(codigo.strip()).map(ResumenDeContribuyente::id);
    }

    private static List<String> mensajesSinFila(List<String> codigos) {
        List<String> mensajes = new ArrayList<>(codigos.size());
        for (String codigo : codigos) {
            mensajes.add("No existe ningun contribuyente con el codigo '" + codigo + "'");
        }
        return mensajes;
    }

    private ValorMasivo registrar(
            TipoValor tipo,
            @Nullable String tributo,
            Ejercicio ejercicioDesde,
            Ejercicio ejercicioHasta,
            @Nullable LocalDate fechaCriterio,
            OrigenDeCriterio origen,
            List<Long> contribuyenteIds,
            Observacion observacion) {

        LocalDate fecha = fechaCriterio != null ? fechaCriterio : LocalDate.now(reloj);
        ValorMasivo corrida =
                new ValorMasivo(
                        null,
                        tipo,
                        tributo,
                        ejercicioDesde,
                        ejercicioHasta,
                        fecha,
                        origen,
                        contribuyenteIds.size(),
                        null,
                        null,
                        observacion);
        return repositorio.iniciar(corrida, contribuyenteIds);
    }

    /** No hay ningun candidato con el que iniciar la corrida. */
    public static final class SinCandidatos extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        SinCandidatos() {
            super("Una corrida masiva necesita al menos un candidato");
        }
    }

    /**
     * Uno o mas codigos de la lista no corresponden a ningun contribuyente. Rechaza la corrida
     * entera (RF-133): ninguno de los candidatos validos se guarda.
     */
    public static final class CandidatosInvalidos extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final List<String> motivos;

        CandidatosInvalidos(List<String> motivos) {
            super(
                    "La lista de candidatos tiene "
                            + motivos.size()
                            + " codigo(s) invalido(s); no se registro ninguno");
            this.motivos = List.copyOf(motivos);
        }

        public List<String> motivos() {
            return motivos;
        }
    }
}

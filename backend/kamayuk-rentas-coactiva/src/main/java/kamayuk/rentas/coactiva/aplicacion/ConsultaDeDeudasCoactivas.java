package kamayuk.rentas.coactiva.aplicacion;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.CriterioDeExpedientes;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.rentas.BeneficioRegistrado;
import kamayuk.rentas.rentas.BeneficiosDelContribuyente;
import kamayuk.rentas.valores.ObligacionDelValor;
import kamayuk.rentas.valores.ValorParaCoactiva;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las dos consultas de deuda en coactiva (#42, RF-107): {@code coactiva_consulta_deudas} y {@code
 * coactiva_deudas_beneficio}.
 *
 * <h2>Ninguna cifra se recompone aqui</h2>
 *
 * <p>La deuda y las costas de cada expediente las da {@link ConsultaDeExpedientes}, que es quien
 * sabe componerlas releyendo el libro a la fecha pedida. Esta clase agrega lo que las dos pantallas
 * necesitan alrededor —los tributos que el expediente agrupa, su ultima actuacion, los beneficios
 * registrados— y nada mas. Sumar por segunda vez lo que ya esta sumado es como dos pantallas acaban
 * mostrando cifras distintas de lo mismo.
 *
 * <h2>Una fila por expediente, no por tributo</h2>
 *
 * <p>La grilla del prototipo tiene una columna «Tributo» en singular y una «Deuda S/». Un
 * expediente agrupa varios valores y estos varios tributos, asi que la columna lista <b>los
 * tributos que el expediente agrupa</b> y las cifras son las del expediente entero. La alternativa
 * —una fila por tributo— obligaria a repartir las costas del procedimiento entre ellos, y las
 * costas no son de ningun tributo: son del procedimiento.
 *
 * <h2>Deudas en beneficio: se listan, no se descuentan</h2>
 *
 * <p>La segunda consulta responde <b>que deuda coactiva tiene un obligado con beneficio
 * registrado</b>, nombrando el beneficio, su clase, su base legal y el porcentaje o el importe que
 * la norma declara. Lo que <b>no</b> devuelve es la columna «Con beneficio S/» del prototipo, y no
 * por falta de ganas:
 *
 * <ul>
 *   <li>Sobre que se aplica un descuento —¿solo el insoluto? ¿tambien el interes? ¿tambien las
 *       costas?—, en que orden respecto del fraccionamiento y con que redondeo es <b>D-02b</b>
 *       (#191). Ninguna de esas tres decisiones esta tomada.
 *   <li>Es la misma linea que #33 trazo para la caja: {@code recibo.campania_beneficio} guarda cual
 *       se declaro y «hoy es SOLO constancia: el importe que se cobra es el que se debe». Calcular
 *       aqui un descuento que la ventanilla no aplica dejaria a la consulta prometiendo una cifra
 *       que nadie va a cobrar.
 *   <li>Y una consulta que devolviera un total «con beneficio» inventado es peor que una que no lo
 *       devuelve: la primera se imprime y se entrega en ventanilla.
 * </ul>
 *
 * <p>Lo que si viaja es el porcentaje <b>declarado</b> por la norma, que es dato transcrito al
 * registrar el beneficio y no un calculo. La pantalla puede escribir «AMNISTIA COACTIVA 2026 — 50 %
 * (Ordenanza 015-2026)» junto a la deuda sin fingir haber recalculado nada.
 *
 * <p>Cuando D-02b se cierre, el sitio donde poner el efecto es este y solo este.
 */
@Service
public class ConsultaDeDeudasCoactivas {

    private final ConsultaDeExpedientes expedientesConDeuda;
    private final ExpedienteRepository expedientes;
    private final ActoCoactivoRepository actos;
    private final ValoresEnCoactiva valores;
    private final BeneficiosDelContribuyente beneficios;

    public ConsultaDeDeudasCoactivas(
            ConsultaDeExpedientes expedientesConDeuda,
            ExpedienteRepository expedientes,
            ActoCoactivoRepository actos,
            ValoresEnCoactiva valores,
            BeneficiosDelContribuyente beneficios) {
        this.expedientesConDeuda = expedientesConDeuda;
        this.expedientes = expedientes;
        this.actos = actos;
        this.valores = valores;
        this.beneficios = beneficios;
    }

    /**
     * La deuda en cobranza coactiva por expediente, a la fecha (RF-107).
     *
     * <p>Solo las filas con deuda: la pantalla se llama «Consulta de deudas en coactiva» y un
     * expediente sin nada que cobrar no es una deuda. Se descartan <b>despues</b> de componer la
     * pagina —la deuda se releee del libro y no hay columna por la que filtrar en SQL—, y la pagina
     * dice cuantos expedientes cumplian el criterio.
     */
    @Transactional(readOnly = true)
    public Pagina<DeudaEnCoactiva> deudas(
            CriterioDeExpedientes criterio, LocalDate aLaFecha, Paginacion paginacion) {

        Objects.requireNonNull(aLaFecha, "Toda cifra se pide a una fecha (regla 9)");
        Pagina<ConsultaDeExpedientes.ExpedienteConDeuda> pagina =
                expedientesConDeuda.buscar(criterio, aLaFecha, paginacion);

        Map<Long, List<ValorParaCoactiva>> porContribuyente = new HashMap<>();
        List<DeudaEnCoactiva> filas = new ArrayList<>();
        for (ConsultaDeExpedientes.ExpedienteConDeuda fila : pagina.contenido()) {
            if (!fila.deuda().total().esPositivo()) {
                continue;
            }
            filas.add(componer(fila, aLaFecha, porContribuyente));
        }
        return new Pagina<>(filas, pagina.pagina(), pagina.tamano(), pagina.totalElementos());
    }

    /**
     * La deuda coactiva de los obligados con beneficio registrado y vigente a la fecha (RF-107).
     *
     * <p><b>Sin descuento aplicado.</b> Vease el javadoc de la clase: el efecto de un beneficio es
     * D-02b (#191), y lo que aqui se devuelve es que beneficio esta registrado y que dice la norma,
     * nunca cuanto rebaja.
     */
    @Transactional(readOnly = true)
    public Pagina<DeudaConBeneficio> enBeneficio(
            CriterioDeExpedientes criterio, LocalDate aLaFecha, Paginacion paginacion) {

        Objects.requireNonNull(aLaFecha, "Toda cifra se pide a una fecha (regla 9)");
        Pagina<DeudaEnCoactiva> conDeuda = deudas(criterio, aLaFecha, paginacion);

        Map<Long, List<BeneficioRegistrado>> porContribuyente = new HashMap<>();
        List<DeudaConBeneficio> filas = new ArrayList<>();
        for (DeudaEnCoactiva fila : conDeuda.contenido()) {
            long contribuyente = fila.expediente().contribuyenteId();
            List<BeneficioRegistrado> suyos =
                    porContribuyente.computeIfAbsent(
                            contribuyente, id -> beneficios.vigentesA(id, aLaFecha));
            if (suyos.isEmpty()) {
                continue;
            }
            filas.add(new DeudaConBeneficio(fila, suyos));
        }
        return new Pagina<>(filas, conDeuda.pagina(), conDeuda.tamano(), conDeuda.totalElementos());
    }

    // ------------------------------------------------------------------

    private DeudaEnCoactiva componer(
            ConsultaDeExpedientes.ExpedienteConDeuda fila,
            LocalDate aLaFecha,
            Map<Long, List<ValorParaCoactiva>> porContribuyente) {

        ExpedienteCoactivo expediente = fila.fila().expediente();
        List<ActoCoactivo> actuaciones = actos.deExpediente(expediente.identificador());
        @Nullable ActoCoactivo ultima =
                actuaciones.isEmpty() ? null : actuaciones.get(actuaciones.size() - 1);

        return new DeudaEnCoactiva(
                expediente,
                fila.fila().estado(),
                tributosDe(expediente, aLaFecha, porContribuyente),
                fila.deuda(),
                ultima,
                aLaFecha);
    }

    /** Los tributos que los valores del expediente formalizan, sin repetir y en orden estable. */
    private List<String> tributosDe(
            ExpedienteCoactivo expediente,
            LocalDate aLaFecha,
            Map<Long, List<ValorParaCoactiva>> porContribuyente) {

        Set<Long> suyos = new HashSet<>();
        for (ValorDelExpediente valor : expedientes.valoresDe(expediente.identificador())) {
            suyos.add(valor.valorId());
        }
        if (suyos.isEmpty()) {
            return List.of();
        }
        List<ValorParaCoactiva> delContribuyente =
                porContribuyente.computeIfAbsent(
                        expediente.contribuyenteId(), id -> valores.delContribuyente(id, aLaFecha));

        Set<String> tributos = new TreeSet<>();
        for (ValorParaCoactiva valor : delContribuyente) {
            if (!suyos.contains(valor.id())) {
                continue;
            }
            for (ObligacionDelValor obligacion : valor.obligaciones()) {
                tributos.add(obligacion.tributo());
            }
        }
        return List.copyOf(tributos);
    }

    /**
     * Una fila de {@code coactiva_consulta_deudas}.
     *
     * @param expediente la carpeta
     * @param estado en que punto esta el procedimiento, derivado de su historial
     * @param tributos los tributos que agrupa, sin repetir
     * @param deuda cuanto se debe y cuanto de costas, con la fecha a la que estan (regla 9)
     * @param ultimaActuacion el ultimo acto dictado, si hubo alguno
     * @param aLaFecha la fecha con la que se respondio
     */
    public record DeudaEnCoactiva(
            ExpedienteCoactivo expediente,
            EstadoDelExpediente estado,
            List<String> tributos,
            DeudaDelExpediente deuda,
            @Nullable ActoCoactivo ultimaActuacion,
            LocalDate aLaFecha) {

        public DeudaEnCoactiva {
            Objects.requireNonNull(expediente, "La fila es la de un expediente");
            Objects.requireNonNull(estado, "El estado se deriva, pero nunca falta");
            tributos = List.copyOf(tributos);
            Objects.requireNonNull(deuda, "Toda cifra viaja con su fecha (regla 9)");
            Objects.requireNonNull(aLaFecha, "Toda cifra viaja con su fecha (regla 9)");
        }
    }

    /**
     * Una fila de {@code coactiva_deudas_beneficio}: la misma deuda y los beneficios registrados.
     *
     * <p><b>No hay ninguna cifra «con beneficio»</b>, y no se puede añadir sin cerrar D-02b (#191).
     * Que el tipo no la tenga es lo que impide que aparezca por descuido en un {@code Resource} de
     * la capa web.
     *
     * @param deuda la deuda coactiva, con su fecha
     * @param beneficios los beneficios registrados que rigen a esa fecha; nunca vacia
     */
    public record DeudaConBeneficio(DeudaEnCoactiva deuda, List<BeneficioRegistrado> beneficios) {

        public DeudaConBeneficio {
            Objects.requireNonNull(deuda, "La fila lleva la deuda que el beneficio alcanzaria");
            beneficios = List.copyOf(beneficios);
            if (beneficios.isEmpty()) {
                throw new IllegalArgumentException(
                        "Una fila de «deudas en beneficio» sin beneficio registrado no dice nada:"
                                + " esa deuda ya sale en la consulta general");
            }
        }
    }
}

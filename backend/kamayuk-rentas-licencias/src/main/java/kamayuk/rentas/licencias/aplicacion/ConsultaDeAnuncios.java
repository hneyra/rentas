package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.licencias.dominio.Anuncio;
import kamayuk.rentas.licencias.dominio.AnuncioRepository;
import kamayuk.rentas.licencias.dominio.CriterioDeAnuncios;
import kamayuk.rentas.licencias.dominio.EstadoDelAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncio;
import kamayuk.rentas.licencias.dominio.MovimientoDeAnuncioRepository;
import kamayuk.rentas.licencias.dominio.ResumenDelPadron;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La grilla, la ficha y el padron de las opciones {@code anuncios} y {@code anuncios_reportes}
 * (#51, RF-114).
 *
 * <h2>Lleva su propia transaccion, y hace falta</h2>
 *
 * <p>{@code @Transactional(readOnly = true)} es lo que abre la transaccion donde {@code
 * TenantTransactionManager} emite el {@code SET LOCAL app.municipalidad_id} que las politicas RLS
 * consultan. Un controlador que llamara al repositorio directamente leeria sin contexto, y eso no
 * devuelve vacio: <b>falla</b>, con un mensaje que no se parece a su causa. Es el defecto que la
 * marcha blanca de seguridad destapo en {@code GET /catastro/vias}.
 *
 * <h2>El estado se deriva, y en una sola consulta</h2>
 *
 * <p>Una pagina de veinte anuncios necesita veinte estados y veinte vigencias vigentes. Se leen los
 * movimientos de las veinte de golpe ({@code deAnuncios}) y se derivan en memoria: con una lectura
 * por fila serian veintiuna consultas, y eso no se nota en la prueba y si en el padron de una
 * provincia.
 *
 * <h2>«A la fecha», tambien aqui, y con mas motivo</h2>
 *
 * <p>El estado depende del dia y la tasa acumulada tambien: un padron emitido hoy y otro emitido
 * manana pueden diferir, y los dos tienen que decir de cuando son (regla 9, RNF-075). Por eso la
 * fecha entra como argumento y viaja en la respuesta, junto al {@link Padron#resumen()}.
 */
@Service
public class ConsultaDeAnuncios {

    /**
     * Cuantos contribuyentes se resuelven como mucho al filtrar por nombre.
     *
     * <p>El filtro busca por aproximacion, asi que «GARCIA» puede encontrar cientos. El tope evita
     * armar un {@code IN} de tamano ilimitado; quien busque un titular concreto escribe mas.
     */
    private static final int TITULARES_MAXIMOS = 200;

    private final AnuncioRepository anuncios;
    private final MovimientoDeAnuncioRepository movimientos;
    private final DirectorioDeContribuyentes contribuyentes;

    public ConsultaDeAnuncios(
            AnuncioRepository anuncios,
            MovimientoDeAnuncioRepository movimientos,
            DirectorioDeContribuyentes contribuyentes) {
        this.anuncios = anuncios;
        this.movimientos = movimientos;
        this.contribuyentes = contribuyentes;
    }

    /**
     * La grilla, paginada, con el estado de cada fila derivado a {@code aLaFecha}.
     *
     * @param nombreDelTitular el filtro por nombre del contribuyente; se resuelve contra el padron
     */
    @Transactional(readOnly = true)
    public Pagina<AnuncioEnConsulta> buscar(
            CriterioDeAnuncios criterio,
            @Nullable String nombreDelTitular,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        CriterioDeAnuncios conTitulares = conTitularesResueltos(criterio, nombreDelTitular);
        if (conTitulares.sinTitularPosible()) {
            // Se filtro por titular y no hay ninguno que se parezca. Devolver la pagina entera
            // aqui —que es lo que pasa si se deja el criterio sin el conjunto— convertiria un
            // nombre inexistente en «todos los anuncios», que es el defecto que la consulta de
            // fichas ya cometio una vez y que #44 volvio a cazar.
            return Pagina.vacia(paginacion);
        }

        Pagina<Anuncio> pagina = anuncios.buscar(conTitulares, paginacion);
        if (pagina.estaVacia()) {
            return Pagina.vacia(paginacion);
        }

        Set<Long> ids = new HashSet<>();
        Set<Long> titulares = new HashSet<>();
        for (Anuncio anuncio : pagina.contenido()) {
            ids.add(anuncio.identificador());
            titulares.add(anuncio.contribuyenteId());
        }
        Map<Long, List<MovimientoDeAnuncio>> historiales = movimientos.deAnuncios(ids);
        Map<Long, ResumenDeContribuyente> padron = contribuyentes.porIds(titulares);

        return pagina.mapear(
                anuncio ->
                        componer(
                                anuncio,
                                historiales.getOrDefault(anuncio.identificador(), List.of()),
                                padron.get(anuncio.contribuyenteId()),
                                aLaFecha,
                                /* conHistorial= */ false));
    }

    /** La ficha completa de una autorizacion: su historial y sus cargos. */
    @Transactional(readOnly = true)
    public Optional<AnuncioEnConsulta> porNumero(String numero, LocalDate aLaFecha) {
        return anuncios.porNumero(numero)
                .map(
                        anuncio -> {
                            List<MovimientoDeAnuncio> historial =
                                    movimientos.deAnuncio(anuncio.identificador());
                            Map<Long, ResumenDeContribuyente> padron =
                                    contribuyentes.porIds(Set.of(anuncio.contribuyenteId()));
                            return componer(
                                    anuncio,
                                    historial,
                                    padron.get(anuncio.contribuyenteId()),
                                    aLaFecha,
                                    /* conHistorial= */ true);
                        });
    }

    /**
     * El padron de la opcion {@code anuncios_reportes}: la misma consulta con su resumen.
     *
     * <p>El resumen se calcula sobre <b>todas</b> las autorizaciones que el criterio encuentra y no
     * sobre la pagina devuelta. Sumar la pagina daria una cifra que parece un total y no lo es —el
     * defecto que #25 destapo en la consulta unificada, donde el resumen decia 300,00 donde debia
     * decir 1 220,00—.
     */
    @Transactional(readOnly = true)
    public Padron padron(
            CriterioDeAnuncios criterio,
            @Nullable String nombreDelTitular,
            LocalDate aLaFecha,
            Paginacion paginacion) {

        Pagina<AnuncioEnConsulta> pagina = buscar(criterio, nombreDelTitular, aLaFecha, paginacion);

        CriterioDeAnuncios conTitulares = conTitularesResueltos(criterio, nombreDelTitular);
        ResumenDelPadron resumen =
                conTitulares.sinTitularPosible()
                        ? ResumenDelPadron.vacio()
                        : anuncios.resumen(conTitulares, aLaFecha);

        return new Padron(pagina, resumen, aLaFecha);
    }

    // ------------------------------------------------------------------

    private static AnuncioEnConsulta componer(
            Anuncio anuncio,
            List<MovimientoDeAnuncio> historial,
            @Nullable ResumenDeContribuyente titular,
            LocalDate aLaFecha,
            boolean conHistorial) {
        LocalDate vigencia = EstadoDelAnuncio.vigenciaSegun(historial, aLaFecha);
        return new AnuncioEnConsulta(
                anuncio,
                EstadoDelAnuncio.derivarDe(historial, vigencia, aLaFecha),
                vigencia,
                devengadoHasta(historial, aLaFecha),
                aLaFecha,
                titular,
                conHistorial ? historial : List.of());
    }

    /**
     * Lo que el anuncio ha devengado hasta esa fecha, sumando los actos que devengan.
     *
     * <p>Se suman los importes <b>copiados en los movimientos</b>, no los que la ordenanza de hoy
     * diria: cada acto guarda lo que se le cargo al contribuyente cuando se le cargo, y esa es la
     * cifra que la ventanilla tiene que poder explicar. No es la deuda —eso lo dice el libro, que
     * es de otro contexto—: es lo que esta autorizacion genero.
     */
    private static Dinero devengadoHasta(List<MovimientoDeAnuncio> historial, LocalDate aLaFecha) {
        Dinero total = Dinero.CERO;
        for (MovimientoDeAnuncio movimiento : historial) {
            Dinero tasa = movimiento.tasa();
            if (tasa != null && !movimiento.fecha().isAfter(aLaFecha)) {
                total = total.mas(tasa);
            }
        }
        return total;
    }

    /**
     * El criterio con el filtro por nombre ya traducido a identificadores.
     *
     * <p>La traduccion se hace <b>aqui</b> y no en el repositorio porque el padron es de otro
     * contexto: {@code licencias} no puede unir {@code anuncio} con {@code contribuyente} en un
     * {@code JOIN} sin cruzar el limite que Spring Modulith vigila.
     */
    private CriterioDeAnuncios conTitularesResueltos(
            CriterioDeAnuncios criterio, @Nullable String nombreDelTitular) {
        String buscado = nombreDelTitular == null ? "" : nombreDelTitular.strip();
        if (buscado.isEmpty()) {
            return criterio;
        }
        Set<Long> encontrados = new HashSet<>();
        for (ResumenDeContribuyente resumen : contribuyentes.buscar(buscado, TITULARES_MAXIMOS)) {
            encontrados.add(resumen.id());
        }
        return criterio.conTitulares(encontrados);
    }

    // ------------------------------------------------------------------

    /**
     * Una autorizacion tal como la pantalla la pinta.
     *
     * @param anuncio la fila
     * @param estado el derivado de sus movimientos
     * @param vigenciaHasta hasta cuando rige segun el ultimo acto que la movio; nulo si no tiene
     *     plazo
     * @param devengado lo que esta autorizacion ha generado en tasas hasta {@code aLaFecha}
     * @param aLaFecha el dia al que se derivo todo lo anterior (regla 9)
     * @param titular el resumen del padron; nulo si el contribuyente ya no esta
     * @param historial sus movimientos; vacio en la grilla, completo en la ficha
     */
    public record AnuncioEnConsulta(
            Anuncio anuncio,
            EstadoDelAnuncio estado,
            @Nullable LocalDate vigenciaHasta,
            Dinero devengado,
            LocalDate aLaFecha,
            @Nullable ResumenDeContribuyente titular,
            List<MovimientoDeAnuncio> historial) {

        public AnuncioEnConsulta {
            historial = List.copyOf(historial);
        }

        /** El nombre del titular, o vacio si el padron ya no lo tiene. */
        public String nombreDelTitular() {
            ResumenDeContribuyente resumen = titular;
            return resumen == null ? "" : resumen.nombre();
        }

        /** El codigo del titular, o vacio si el padron ya no lo tiene. */
        public String codigoDelTitular() {
            ResumenDeContribuyente resumen = titular;
            return resumen == null ? "" : resumen.codigo();
        }

        /** El documento del titular, o vacio si el padron ya no lo tiene. */
        public String documentoDelTitular() {
            ResumenDeContribuyente resumen = titular;
            return resumen == null ? "" : resumen.documento();
        }
    }

    /**
     * El padron de anuncios con su resumen.
     *
     * @param pagina las filas pedidas
     * @param resumen lo que TODAS las autorizaciones del criterio suman, no solo las de esta pagina
     * @param aLaFecha el dia de corte del padron (regla 9, RNF-075)
     */
    public record Padron(
            Pagina<AnuncioEnConsulta> pagina, ResumenDelPadron resumen, LocalDate aLaFecha) {}
}

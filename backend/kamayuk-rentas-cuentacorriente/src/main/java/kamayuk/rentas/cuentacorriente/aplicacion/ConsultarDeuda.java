package kamayuk.rentas.cuentacorriente.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.cuentacorriente.dominio.Agregacion;
import kamayuk.rentas.cuentacorriente.dominio.Asiento;
import kamayuk.rentas.cuentacorriente.dominio.AsientoRepository;
import kamayuk.rentas.cuentacorriente.dominio.CalculoDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeObligacion;
import kamayuk.rentas.cuentacorriente.dominio.ClaveDeSaldo;
import kamayuk.rentas.cuentacorriente.dominio.ConstanciaDeNoAdeudo;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeuda;
import kamayuk.rentas.cuentacorriente.dominio.CriterioDeDeudaPorContribuyente;
import kamayuk.rentas.cuentacorriente.dominio.DeudaActualizada;
import kamayuk.rentas.cuentacorriente.dominio.Fase;
import kamayuk.rentas.cuentacorriente.dominio.ObligacionConDeuda;
import kamayuk.rentas.cuentacorriente.dominio.SaldoProyectado;
import kamayuk.rentas.cuentacorriente.dominio.SaldoRepository;
import kamayuk.rentas.dominio.PoliticaDeRedondeo;
import kamayuk.rentas.web.CodigoDeError;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code consulta_deuda}: trae los asientos de una obligacion —o de todas las de un contribuyente—
 * y les aplica {@link CalculoDeDeuda#deudaActualizadaA} (RF-041, RF-042).
 *
 * <p>Este servicio es el unico sitio de este contexto que conoce el reloj —para la fecha de corte
 * por omision, cuando quien consulta no pide una fecha pasada— y la {@link PoliticaDeRedondeo}
 * vigente. {@link CalculoDeDeuda} sigue sin conocer ninguno de los dos: los recibe como argumento,
 * y por eso su prueba no necesita levantar Spring ni el reloj del sistema (regla 6).
 */
@Service
public class ConsultarDeuda {

    /** Como {@link Fase} declara sus valores en el orden de la cobranza: la mas avanzada gana. */
    private static final Comparator<Fase> FASE_MAS_AVANZADA = Comparator.naturalOrder();

    private static final Set<String> ORDEN_ADMITIDO = Set.of("ejercicio", "tributo");

    private final AsientoRepository repositorio;
    private final SaldoRepository saldos;
    private final CalculoDeDeuda calculo;
    private final PoliticaDeRedondeo redondeo;
    private final Clock reloj;

    public ConsultarDeuda(
            AsientoRepository repositorio,
            SaldoRepository saldos,
            CalculoDeDeuda calculo,
            PoliticaDeRedondeo redondeo,
            Clock reloj) {
        this.repositorio = repositorio;
        this.saldos = saldos;
        this.calculo = calculo;
        this.redondeo = redondeo;
        this.reloj = reloj;
    }

    /**
     * La deuda de una obligacion, a la fecha de corte del criterio.
     *
     * <p>La fecha no la elige este metodo: la trae {@link CriterioDeDeuda#fecha()}, que quien llama
     * ya resolvio —a hoy, con {@link #hoy()}, o a una fecha pasada—.
     */
    @Transactional(readOnly = true)
    public DeudaActualizada deudaActualizadaA(CriterioDeDeuda criterio) {
        List<Asiento> asientos = repositorio.paraDeuda(criterio);
        return calculo.deudaActualizadaA(asientos, criterio.fecha(), redondeo);
    }

    /**
     * El identificador del contribuyente por su codigo, o vacio si no esta en el padron (#622).
     *
     * <p>Vacio no es una peticion mal formada: es un padron sin ese contribuyente, y quien pregunta
     * por su deuda necesita distinguirlo de «no debe nada». Las dos frases se decian igual —200 con
     * cero filas— y una de las dos es falsa.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> contribuyentePorCodigo(String codigo) {
        return repositorio.contribuyentePorCodigo(codigo);
    }

    /**
     * La deuda de <b>todas</b> las obligaciones del contribuyente, a la fecha de corte del
     * criterio, paginada (RF-041): una fila por tributo/ejercicio/unidad, con los periodos
     * agregados y la fase mas avanzada entre ellos —o una fila por <b>cuota</b>, si el criterio
     * pide {@link Agregacion#POR_PERIODO} (#551).
     *
     * <p>Un codigo que no existe en esta municipalidad da una pagina vacia, igual que {@code
     * cuenta_corriente} (#21): no es una entrada mal formada, es un padron sin esa fila.
     *
     * <p>{@link SaldoRepository#deContribuyente} solo sirve aqui para <b>descubrir</b> que
     * obligaciones tiene el contribuyente —es un indice, no la cifra—: el importe de cada fila sale
     * siempre de recorrer sus asientos con {@link CalculoDeDeuda#deudaActualizadaA}, porque el
     * saldo proyectado es una cache de <i>hoy</i> (#23) y una fecha de corte pasada puede pedir
     * otra cosa.
     */
    @Transactional(readOnly = true)
    public Pagina<ObligacionConDeuda> porContribuyente(
            CriterioDeDeudaPorContribuyente criterio, Paginacion paginacion) {
        Optional<Long> contribuyenteId =
                repositorio.contribuyentePorCodigo(criterio.codigoContribuyente());
        if (contribuyenteId.isEmpty()) {
            return Pagina.vacia(paginacion);
        }

        Map<Renglon, List<SaldoProyectado>> agrupados =
                agrupar(contribuyenteId.get(), criterio.agregacion());

        List<Renglon> seleccionados = new ArrayList<>();
        for (Map.Entry<Renglon, List<SaldoProyectado>> grupo : agrupados.entrySet()) {
            Fase faseDelGrupo = faseMasAvanzadaDe(grupo.getValue());
            if (criterio.fase() == null || criterio.fase() == faseDelGrupo) {
                seleccionados.add(grupo.getKey());
            }
        }
        ordenar(seleccionados, paginacion);

        long total = seleccionados.size();
        int desde = Math.min(paginacion.desplazamiento(), seleccionados.size());
        int hasta = Math.min(desde + paginacion.tamano(), seleccionados.size());
        List<Renglon> deLaPagina = seleccionados.subList(desde, hasta);

        Map<ClaveDeObligacion, Map<Integer, DeudaActualizada>> porPeriodo =
                deudaPorPeriodoDe(deLaPagina, criterio.fecha());

        List<ObligacionConDeuda> contenido = new ArrayList<>();
        for (Renglon renglon : deLaPagina) {
            List<SaldoProyectado> delGrupo =
                    Objects.requireNonNull(
                            agrupados.get(renglon), "el renglon viene de agrupados.entrySet()");
            contenido.add(filaDe(criterio, renglon, delGrupo, porPeriodo));
        }

        return Pagina.de(contenido, paginacion, total);
    }

    /** La fecha de hoy, del reloj inyectado y no de {@code LocalDate.now()} (regla 6). */
    public LocalDate hoy() {
        return LocalDate.now(reloj);
    }

    /**
     * Si se puede emitir la constancia de no adeudo del contribuyente a la fecha de corte (RF-049,
     * RNF-084, #25): se niega si <b>alguna</b> obligacion tiene saldo pendiente a esa fecha, en
     * cualquier fase.
     *
     * <p>«Incluida la que esta en coactiva o en convenio vigente» (criterio de aceptacion de #25)
     * no exige consultar a {@code coactiva} ni a un contexto de convenios: {@link Fase#COACTIVA} y
     * {@link Fase#CONVENIO} ya son un valor mas de la fase de la propia obligacion, y este metodo
     * agrupa <b>todas</b> las obligaciones sin filtrar por fase —a diferencia de {@link
     * #porContribuyente}, que la deja elegir—. Es exactamente la regla 2 (ARQ-01 §4):
     * cuentacorriente no necesita conocer a nadie para responder esto.
     *
     * <p>A diferencia de {@link #porContribuyente}, un codigo que no existe <b>no</b> da un
     * resultado vacio silencioso: la constancia es un documento sobre una persona concreta, y «no
     * debe nada» seria una afirmacion falsa sobre alguien que no esta en el padron de esta
     * municipalidad.
     *
     * @throws ProblemaDeNegocio {@code NO_ENCONTRADO} si el codigo no identifica a ningun
     *     contribuyente de la municipalidad activa
     */
    @Transactional(readOnly = true)
    public ConstanciaDeNoAdeudo constanciaDeNoAdeudo(String codigoContribuyente, LocalDate fecha) {
        CriterioDeDeudaPorContribuyente criterio =
                new CriterioDeDeudaPorContribuyente(
                        codigoContribuyente, fecha, null, Agregacion.POR_OBLIGACION);
        Optional<Long> contribuyenteId =
                repositorio.contribuyentePorCodigo(criterio.codigoContribuyente());
        if (contribuyenteId.isEmpty()) {
            throw new ProblemaDeNegocio(
                    CodigoDeError.NO_ENCONTRADO,
                    "No hay ningun contribuyente con el codigo " + criterio.codigoContribuyente());
        }

        List<ObligacionConDeuda> obligaciones =
                new ArrayList<>(todasLasObligacionesDe(contribuyenteId.get(), fecha));
        obligaciones.sort(
                Comparator.comparing((ObligacionConDeuda o) -> o.ejercicio().valor())
                        .thenComparing(ObligacionConDeuda::tributo));

        return ConstanciaDeNoAdeudo.de(criterio.codigoContribuyente(), fecha, obligaciones);
    }

    /**
     * Todas las obligaciones con deuda del contribuyente, sin paginar y sin filtrar por fase —a
     * diferencia de {@link #porContribuyente}, que pagina y deja elegir la fase—: la lista
     * completa, en cualquier orden.
     *
     * <p>Es lo que necesitan tanto {@link #constanciaDeNoAdeudo} como el puerto publico de este
     * contexto ({@code kamayuk.rentas.cuentacorriente.ConsultaDeDeudaPublica}, ARQ-01 §4): «cuanto
     * debe en total» no tiene pagina, la tiene una grilla.
     *
     * <p>A diferencia de {@link #filaDe} —que resuelve cada obligacion con su propia consulta a
     * {@link AsientoRepository#paraDeuda}—, agrupa <b>en memoria</b> los asientos de una sola
     * llamada a {@link AsientoRepository#deContribuyente}: no hay pagina que acote cuantos grupos
     * se resuelven, asi que una consulta por obligacion aqui podria ser cualquier numero de ellas.
     * {@link CalculoDeDeuda#deudaActualizadaA} filtra el corte por su cuenta (ve {@code
     * fechaValor}), asi que agrupar sin filtrar por fecha primero es seguro.
     */
    @Transactional(readOnly = true)
    public List<ObligacionConDeuda> todasLasObligacionesDe(long contribuyenteId, LocalDate fecha) {
        Map<ClaveDeObligacion, List<Asiento>> agrupados = new LinkedHashMap<>();
        for (Asiento asiento : repositorio.deContribuyente(contribuyenteId)) {
            agrupados
                    .computeIfAbsent(ClaveDeObligacion.de(asiento), k -> new ArrayList<>())
                    .add(asiento);
        }

        List<ObligacionConDeuda> obligaciones = new ArrayList<>();
        for (Map.Entry<ClaveDeObligacion, List<Asiento>> grupo : agrupados.entrySet()) {
            List<Asiento> delGrupo = grupo.getValue();
            ClaveDeObligacion clave = grupo.getKey();
            int periodoDesde =
                    delGrupo.stream().mapToInt(ConsultarDeuda::periodoDe).min().orElseThrow();
            int periodoHasta =
                    delGrupo.stream().mapToInt(ConsultarDeuda::periodoDe).max().orElseThrow();
            Fase fase = delGrupo.stream().map(Asiento::fase).max(FASE_MAS_AVANZADA).orElseThrow();
            DeudaActualizada deuda = calculo.deudaActualizadaA(delGrupo, fecha, redondeo);
            obligaciones.add(
                    new ObligacionConDeuda(
                            clave.tributo(),
                            clave.ejercicio(),
                            clave.predioId(),
                            clave.vehiculoId(),
                            periodoDesde,
                            periodoHasta,
                            fase,
                            deuda));
        }
        return obligaciones;
    }

    /** {@code periodo} nulo es anual, igual que en {@link ClaveDeSaldo#de(Asiento)}. */
    private static int periodoDe(Asiento asiento) {
        return asiento.periodo() == null ? 0 : asiento.periodo();
    }

    /**
     * Lo que ocupa una fila del listado (#551).
     *
     * <p>Con {@link Agregacion#POR_OBLIGACION} el {@code periodo} es nulo y la fila agrega todas
     * las cuotas de la obligacion; con {@link Agregacion#POR_PERIODO} la fila <b>es</b> una cuota y
     * el periodo la identifica. Que sea un tipo y no dos caminos paralelos es lo que hace que el
     * filtro de fase, el orden y la paginacion trabajen igual en los dos: si se separaran, la
     * pagina de una forma podria contener obligaciones que la otra no.
     */
    private record Renglon(ClaveDeObligacion obligacion, @Nullable Integer periodo) {

        /** El periodo con el que se ordena: el 0 no es «sin dato», es la obligacion anual. */
        int periodoParaOrdenar() {
            return periodo == null ? 0 : periodo;
        }
    }

    /**
     * Los saldos del contribuyente, agrupados con la granularidad que el criterio pide.
     *
     * <p>Las claves salen siempre de {@code saldo_proyectado}, cuya columna {@code periodo} es
     * {@code NOT NULL DEFAULT 0}: por eso la obligacion <b>anual</b> —la que el libro guarda con
     * {@code periodo} nulo— tiene aqui su fila como cualquier otra cuota, y no hace falta que
     * ninguna consulta compare un nulo con un cero (el hueco que #247 §2 documenta con {@code =}
     * frente a {@code IS NOT DISTINCT FROM}).
     */
    private Map<Renglon, List<SaldoProyectado>> agrupar(
            long contribuyenteId, Agregacion agregacion) {
        Map<Renglon, List<SaldoProyectado>> agrupados = new LinkedHashMap<>();
        for (SaldoProyectado saldo : saldos.deContribuyente(contribuyenteId)) {
            ClaveDeObligacion obligacion = ClaveDeObligacion.de(saldo.clave());
            Renglon renglon =
                    agregacion == Agregacion.POR_PERIODO
                            ? new Renglon(obligacion, saldo.clave().periodo())
                            : new Renglon(obligacion, null);
            agrupados.computeIfAbsent(renglon, k -> new ArrayList<>()).add(saldo);
        }
        return agrupados;
    }

    /**
     * Lo que debe cada cuota de las obligaciones que caen en esta pagina, o el mapa vacio si las
     * filas agregan.
     *
     * <p><b>Una consulta por obligacion, no por cuota</b>: {@link
     * AsientoRepository#deTodosLosPeriodosDe} trae los asientos de todos sus periodos de una vez y
     * {@link CalculoDeDeuda#deudaPorPeriodoA} los reparte en memoria. Una pagina de doce cuotas del
     * mismo arbitrio cuesta <b>una</b> consulta, no doce.
     *
     * <p>Y la cuenta es <b>la misma</b> que hace {@code RegistrarMovimientoDeDeuda} al repartir una
     * baja (#598): es la misma funcion pura sobre los mismos asientos. Escribirla dos veces dejaria
     * que lo que se lee en pantalla y lo que el acto puede extinguir divergieran sin que ninguna
     * cifra pareciera mal (#397).
     */
    private Map<ClaveDeObligacion, Map<Integer, DeudaActualizada>> deudaPorPeriodoDe(
            List<Renglon> deLaPagina, LocalDate fecha) {
        Map<ClaveDeObligacion, Map<Integer, DeudaActualizada>> porObligacion =
                new LinkedHashMap<>();
        for (Renglon renglon : deLaPagina) {
            if (renglon.periodo() == null || porObligacion.containsKey(renglon.obligacion())) {
                continue;
            }
            porObligacion.put(
                    renglon.obligacion(),
                    calculo.deudaPorPeriodoA(
                            repositorio.deTodosLosPeriodosDe(renglon.obligacion()),
                            fecha,
                            redondeo));
        }
        return porObligacion;
    }

    private ObligacionConDeuda filaDe(
            CriterioDeDeudaPorContribuyente criterio,
            Renglon renglon,
            List<SaldoProyectado> delGrupo,
            Map<ClaveDeObligacion, Map<Integer, DeudaActualizada>> porPeriodo) {

        ClaveDeObligacion clave = renglon.obligacion();
        int periodoDesde = delGrupo.stream().mapToInt(s -> s.clave().periodo()).min().orElseThrow();
        int periodoHasta = delGrupo.stream().mapToInt(s -> s.clave().periodo()).max().orElseThrow();

        return new ObligacionConDeuda(
                clave.tributo(),
                clave.ejercicio(),
                clave.predioId(),
                clave.vehiculoId(),
                periodoDesde,
                periodoHasta,
                faseMasAvanzadaDe(delGrupo),
                deudaDe(criterio, renglon, porPeriodo));
    }

    /**
     * El desglose de la fila: el de su cuota si la fila es una cuota, y el de la obligacion entera
     * si agrega.
     *
     * <p>Una cuota que tiene fila en la proyeccion y ningun asiento a la fecha de corte —lo que
     * ocurre si se pregunta por una fecha anterior a su primer movimiento— vale <b>cero en las
     * cuatro partes</b>, y esa cifra sale de la misma funcion con la lista vacia: inventarla aqui
     * seria una quinta forma de netear.
     */
    private DeudaActualizada deudaDe(
            CriterioDeDeudaPorContribuyente criterio,
            Renglon renglon,
            Map<ClaveDeObligacion, Map<Integer, DeudaActualizada>> porPeriodo) {

        Integer periodo = renglon.periodo();
        if (periodo != null) {
            Map<Integer, DeudaActualizada> deLaObligacion =
                    porPeriodo.getOrDefault(renglon.obligacion(), Map.of());
            DeudaActualizada delPeriodo = deLaObligacion.get(periodo);
            return delPeriodo != null
                    ? delPeriodo
                    : calculo.deudaActualizadaA(List.of(), criterio.fecha(), redondeo);
        }

        ClaveDeObligacion clave = renglon.obligacion();
        // periodo=null trae los asientos de TODOS los periodos de la obligacion (ver
        // AsientoRepositoryJdbc#paraDeuda): es lo que permite agregar arbitrios de enero a
        // diciembre en una sola fila. fase=null a proposito: filtrar aqui dejaria fuera los
        // asientos de los periodos que todavia no llegaron a esa fase, y la fila subestimaria
        // la deuda de la obligacion.
        CriterioDeDeuda criterioDeLaObligacion =
                new CriterioDeDeuda(
                        criterio.codigoContribuyente(),
                        clave.tributo(),
                        clave.ejercicio(),
                        null,
                        clave.predioId(),
                        clave.vehiculoId(),
                        null,
                        null,
                        criterio.fecha());
        return calculo.deudaActualizadaA(
                repositorio.paraDeuda(criterioDeLaObligacion), criterio.fecha(), redondeo);
    }

    private static Fase faseMasAvanzadaDe(List<SaldoProyectado> saldos) {
        return saldos.stream().map(SaldoProyectado::fase).max(FASE_MAS_AVANZADA).orElseThrow();
    }

    /**
     * El orden de las filas, con el periodo como <b>ultimo desempate</b>.
     *
     * <p>Sin el, dos cuotas de la misma obligacion empatan en las cinco columnas anteriores y el
     * orden deja de ser total: dos paginas consecutivas pueden repetir una cuota y omitir otra, que
     * es lo que #548 midio en el listado de recibos. Con {@link Agregacion#POR_OBLIGACION} el
     * desempate no puede actuar —todos los renglones traen el periodo nulo— y por eso no cambia
     * nada de lo que ya habia.
     *
     * <p><b>Y hoy es inerte tambien con {@link Agregacion#POR_PERIODO}, medido</b>: quitarlo deja
     * las diez pruebas de {@code DeudaPorPeriodoFronteraTest} en VERDE, porque {@link
     * SaldoRepository#deContribuyente} ya devuelve {@code ORDER BY tributo, ejercicio, periodo},
     * {@link #agrupar} conserva ese orden en un {@code LinkedHashMap} y {@code List.sort} es
     * <b>estable</b>. Es decir: el orden sale bien <b>por lo que dice un {@code ORDER BY} de otra
     * clase</b>, que es el «determinista por accidente» de #548.
     *
     * <p>Lo que el desempate compra se midio cambiando ese {@code ORDER BY} a {@code periodo DESC}:
     * sin el desempate la pagina sale «4, 3, 2, 1, 0» y 2 pruebas se ponen rojas; con el, las diez
     * siguen en verde. Se queda por eso, y queda escrito para que nadie lo retire leyendo que «no
     * hace falta».
     */
    private static void ordenar(List<Renglon> renglones, Paginacion paginacion) {
        if (!ORDEN_ADMITIDO.contains(paginacion.ordenarPor())) {
            throw new IllegalArgumentException(
                    "consulta_deuda no admite ordenar por '"
                            + paginacion.ordenarPor()
                            + "'. Se admite: "
                            + ORDEN_ADMITIDO);
        }
        Comparator<Renglon> primario =
                "tributo".equals(paginacion.ordenarPor())
                        ? Comparator.comparing(r -> r.obligacion().tributo())
                        : Comparator.comparing((Renglon r) -> r.obligacion().ejercicio().valor());
        if (paginacion.direccion() == Paginacion.Direccion.DESCENDENTE) {
            primario = primario.reversed();
        }
        renglones.sort(
                primario.thenComparing((Renglon r) -> r.obligacion().ejercicio().valor())
                        .thenComparing(r -> r.obligacion().tributo())
                        .thenComparing(
                                r ->
                                        r.obligacion().predioId() == null
                                                ? 0L
                                                : r.obligacion().predioId())
                        .thenComparing(
                                r ->
                                        r.obligacion().vehiculoId() == null
                                                ? 0L
                                                : r.obligacion().vehiculoId())
                        .thenComparing(Renglon::periodoParaOrdenar));
    }
}

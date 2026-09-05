package kamayuk.rentas.nucleo.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.LectorDeCaracteristicas;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.PrediosDelContribuyente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.PoliticasDeRedondeo;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.dominio.PuntoDeRedondeo;
import kamayuk.rentas.nucleo.dominio.predial.AporteDeTramo;
import kamayuk.rentas.nucleo.dominio.predial.CronogramaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.CuotaDelPredial;
import kamayuk.rentas.nucleo.dominio.predial.DetalleDeterminacionPredio;
import kamayuk.rentas.nucleo.dominio.predial.Determinacion;
import kamayuk.rentas.nucleo.dominio.predial.DeterminacionPredialCalculada;
import kamayuk.rentas.nucleo.dominio.predial.PredioEnLaBase;
import kamayuk.rentas.nucleo.dominio.predial.Tramo;
import kamayuk.rentas.nucleo.dominio.predial.TramosProgresivosAcumulativos;
import org.springframework.stereotype.Service;

/**
 * El calculo individual del impuesto predial de un contribuyente, con todo lo que hace falta para
 * explicarlo (#395: {@code POST /rentas/predial/calculo-individual}).
 *
 * <p>Junta las tres piezas que ya existian por separado y no tenian quien las llamara: los predios
 * del contribuyente y su titularidad —de catastro—, el cuadro del articulo 13 —del conjunto
 * sellado, {@link CuadroPredialParametrizado}— y la regla de #30 —{@link
 * RegistrarDeterminacionPredial}—.
 *
 * <h2>La base es del contribuyente, no de cada predio</h2>
 *
 * <p>NEG-05 §1. Cada predio aporta su valuo afecto <b>ponderado por el % de propiedad</b>, y los
 * tramos progresivos se aplican una sola vez sobre la suma. Calcular predio por predio y sumar los
 * impuestos produce un error sistematico a la baja en todo el padron —los tramos bajos se aplican
 * tantas veces como predios—, y ese error no se ve en ninguna cifra.
 *
 * <h2>El % de propiedad no lo manda quien pide</h2>
 *
 * <p>Sale de {@code titularidad} a la fecha de calculo, por {@link PrediosDelContribuyente}. Es lo
 * unico que impide que la base se pueda inflar o desinflar desde el cuerpo de la peticion, y por
 * eso {@link PredioDeclarado} no tiene campo para el.
 *
 * <h2>El autovaluo, en cambio, se declara</h2>
 *
 * <p>Y no es un descuido: <b>el sistema no sabe valorizar un predio todavia</b>. Llegar al
 * autovaluo exige el cuadro de valores unitarios y la tabla de depreciacion —a las dos les falta
 * una dimension que la norma si tiene (GOB-03, H-14 y H-15)—, los aranceles de la ordenanza (D-02b)
 * y el {@code % actualizacion}, que sigue <b>sin fuente identificada</b> (D-11). Por eso {@code
 * determinacion_predio_detalle} (V20) guarda el autovaluo en vez de derivarlo, y por eso {@link
 * RegistrarDeterminacionPredial} lo recibe ya declarado.
 *
 * <p>La consecuencia practica es que un predio sin autovaluo declarado <b>no se determina</b>: se
 * responde nombrandolo. Tomar el autovaluo del ejercicio anterior seria aplicar en silencio un
 * {@code % actualizacion} de cero, que es exactamente lo que D-11 advierte que no es neutro.
 *
 * <h2>Simular no asienta</h2>
 *
 * <p>El contrato declara una sola operacion por pantalla, asi que la diferencia va en el cuerpo,
 * como ya hacia {@code VehicularController} (#32). Aqui es <b>obligatoria</b>: una peticion que no
 * diga si simula o determina se rechaza en vez de suponer. Suponer «determina» emitiria deuda al
 * pulsar un boton que dice «Simular»; suponer «simula» dejaria de emitirla al pulsar el que dice
 * «Calcular», y ninguna de las dos equivocaciones avisa.
 */
@Service
public class DeterminarPredial {

    /** La modalidad por omision del cronograma: el articulo 15 la escribe en cuatro trimestres. */
    public static final String MODALIDAD_TRIMESTRAL = "TRIMESTRAL";

    private final PadronPredialDelEjercicio yaDeclarados;
    private final PrediosDelContribuyente predios;
    private final LectorDeCaracteristicas caracteristicas;
    private final DirectorioDeContribuyentes directorio;
    private final CuadroPredialParametrizado cuadro;
    private final RegistrarDeterminacionPredial registro;
    private final Clock reloj;

    public DeterminarPredial(
            PadronPredialDelEjercicio yaDeclarados,
            PrediosDelContribuyente predios,
            LectorDeCaracteristicas caracteristicas,
            DirectorioDeContribuyentes directorio,
            CuadroPredialParametrizado cuadro,
            RegistrarDeterminacionPredial registro,
            Clock reloj) {
        this.yaDeclarados = yaDeclarados;
        this.predios = predios;
        this.caracteristicas = caracteristicas;
        this.directorio = directorio;
        this.cuadro = cuadro;
        this.registro = registro;
        this.reloj = reloj;
    }

    /**
     * Determina —o simula— el predial de un contribuyente.
     *
     * <p>No abre transaccion propia: la abre {@link RegistrarDeterminacionPredial#registrar}, que
     * es quien escribe. Envolver esto en una del anfitrion es la trampa que #54 y #72 documentan:
     * los colaboradores ajenos traen la suya, y una excepcion capturada dentro de la del anfitrion
     * la deja marcada como <i>rollback-only</i> y revienta al confirmarla.
     *
     * @param peticion que se determina y con que autovaluos
     * @param observacion por que (regla 10); se exige tambien al simular
     */
    public DeterminacionPredialCalculada determinar(Peticion peticion, Observacion observacion) {
        Objects.requireNonNull(peticion, "Hace falta la peticion");
        Objects.requireNonNull(observacion, "Toda modificacion exige la observacion (regla 10)");

        LocalDate hoy = LocalDate.now(reloj);
        ResumenDeContribuyente contribuyente =
                directorio
                        .porCodigo(peticion.codContribuyente())
                        .orElseThrow(
                                () -> new ContribuyenteInexistente(peticion.codContribuyente()));

        CuadroPredialParametrizado.Vigente vigente = cuadro.vigenteEn(peticion.ejercicio());
        PoliticasDeRedondeo redondeo = vigente.redondeo();

        List<PredioEnLaBase> enLaBase = componerLaBase(contribuyente, peticion, hoy, redondeo);

        List<Tramo> tramos = vigente.tramos();
        Dinero minimo = vigente.minimoImponible();
        List<DetalleDeterminacionPredio> detalle =
                enLaBase.stream().map(PredioEnLaBase::comoDetalle).toList();

        Determinacion cabecera =
                registro.registrar(
                        peticion.ejercicio(),
                        contribuyente.id(),
                        detalle,
                        tramos,
                        minimo,
                        peticion.simulacion(),
                        observacion);

        List<AporteDeTramo> aportes =
                TramosProgresivosAcumulativos.desglosar(cabecera.baseImponible(), tramos);
        Dinero derechoDeEmision = vigente.derechoDeEmision();
        String modalidad = peticion.modalidad();
        List<CuotaDelPredial> cuotas =
                CronogramaDelPredial.repartir(
                        cabecera.montoDeterminado(), vigente.vencimientos(modalidad), redondeo);

        return new DeterminacionPredialCalculada(
                cabecera,
                enLaBase,
                sumar(enLaBase, PredioEnLaBase::autovaluo),
                sumar(enLaBase, PredioEnLaBase::valuoExonerado),
                sumar(enLaBase, PredioEnLaBase::valuoAfecto),
                vigente.uit(),
                aportes,
                minimo,
                cabecera.montoDeterminado(),
                derechoDeEmision,
                cuotas,
                modalidad,
                vigente.nombreDelConjunto(),
                contribuyente.codigo(),
                contribuyente.nombre(),
                hoy);
    }

    private List<PredioEnLaBase> componerLaBase(
            ResumenDeContribuyente contribuyente,
            Peticion peticion,
            LocalDate hoy,
            PoliticasDeRedondeo redondeo) {
        List<PredioDelContribuyente> suyos = predios.de(contribuyente.id(), hoy);
        if (suyos.isEmpty()) {
            throw new SinPrediosEnElPadron(contribuyente.codigo());
        }

        List<PredioDeclarado> pedidos = autovaluosDe(contribuyente, peticion);

        Map<Long, PredioDeclarado> declarados = new LinkedHashMap<>();
        for (PredioDeclarado declarado : pedidos) {
            if (declarados.put(declarado.predioId(), declarado) != null) {
                throw new PredioRepetido(declarado.predioId());
            }
        }
        for (PredioDelContribuyente predio : suyos) {
            declarados.remove(predio.predioId());
        }
        if (!declarados.isEmpty()) {
            throw new PredioAjeno(contribuyente.codigo(), declarados.keySet().iterator().next());
        }

        Map<Long, PredioDeclarado> porPredio = new LinkedHashMap<>();
        for (PredioDeclarado declarado : pedidos) {
            porPredio.put(declarado.predioId(), declarado);
        }

        List<PredioEnLaBase> base = new ArrayList<>();
        for (PredioDelContribuyente predio : suyos) {
            PredioDeclarado declarado = porPredio.get(predio.predioId());
            if (declarado == null) {
                throw new PredioSinAutovaluo(predio);
            }
            Optional<CaracteristicasDelPredio> rasgos = caracteristicas.de(predio.predioId(), hoy);
            Dinero exonerado =
                    declarado.valuoExonerado() == null ? Dinero.CERO : declarado.valuoExonerado();
            Dinero afecto = declarado.autovaluo().menos(exonerado);
            Porcentaje cuota = predio.porcentajeTitularidad();
            Dinero ponderado =
                    afecto.por(cuota.valor().movePointLeft(2))
                            .redondeadoEn(PuntoDeRedondeo.BASE_IMPONIBLE_DEL_PREDIO, redondeo);
            base.add(
                    new PredioEnLaBase(
                            predio.predioId(),
                            predio.codigoReferenciaCatastral(),
                            predio.direccion(),
                            rasgos.map(CaracteristicasDelPredio::uso).orElse(null),
                            cuota,
                            declarado.autovaluo(),
                            exonerado,
                            ponderado,
                            // Lo que suma la titularidad ENTERA del predio, no solo esta cuota
                            // (#690): si es menor que 100, la base sale ponderada por un predio
                            // que no tiene dueño completo, y eso hay que poder decirlo.
                            predio.porcentajeRegistradoDelPredio()));
        }
        return List.copyOf(base);
    }

    /**
     * Los autovaluos con los que se determina: los que trae la peticion, y si no trae ninguno, los
     * que ya se declararon en <b>este mismo</b> ejercicio.
     *
     * <p>Lo segundo es lo que hace que recalcular no obligue a volver a teclear el padron entero
     * —cambio el conjunto sellado, cambio una titularidad, se corrigio una alicuota— y es la misma
     * lectura que usa la corrida masiva. Del mismo ejercicio y de ningun otro: arrastrar el
     * autovaluo del ano pasado seria aplicar en silencio un {@code % actualizacion} de cero, el
     * factor que D-11 deja sin fuente y que NEG-05 §0.1 advierte que multiplica importes.
     *
     * <p>Si no hay ninguno de los dos, no se inventa: cada predio sin autovaluo se nombra al
     * componer la base.
     */
    private List<PredioDeclarado> autovaluosDe(
            ResumenDeContribuyente contribuyente, Peticion peticion) {
        if (!peticion.predios().isEmpty()) {
            return peticion.predios();
        }
        List<PredioDeclarado> delEjercicio = new ArrayList<>();
        for (DetalleDeterminacionPredio detalle :
                yaDeclarados.autovaluosDeclaradosDe(peticion.ejercicio(), contribuyente.id())) {
            delEjercicio.add(
                    new PredioDeclarado(
                            detalle.predioId(), detalle.autovaluo(), detalle.valuoExonerado()));
        }
        return List.copyOf(delEjercicio);
    }

    private static Dinero sumar(
            List<PredioEnLaBase> predios,
            java.util.function.Function<PredioEnLaBase, Dinero> cual) {
        Dinero total = Dinero.CERO;
        for (PredioEnLaBase predio : predios) {
            total = total.mas(cual.apply(predio));
        }
        return total;
    }

    /**
     * Lo que se pide determinar.
     *
     * @param ejercicio el ejercicio que se determina
     * @param codContribuyente el codigo del contribuyente en el padron
     * @param predios el autovaluo declarado de cada uno de sus predios; hacen falta todos
     * @param modalidad el cronograma que se aplica; {@link #MODALIDAD_TRIMESTRAL} si no se dice
     * @param simulacion si esto no se guarda
     */
    public record Peticion(
            Ejercicio ejercicio,
            String codContribuyente,
            List<PredioDeclarado> predios,
            String modalidad,
            boolean simulacion) {

        public Peticion {
            Objects.requireNonNull(ejercicio, "La determinacion necesita su ejercicio");
            Objects.requireNonNull(codContribuyente, "La determinacion necesita el contribuyente");
            predios =
                    List.copyOf(
                            Objects.requireNonNull(
                                    predios, "La lista de predios es vacia," + " no nula"));
            modalidad =
                    modalidad == null || modalidad.isBlank()
                            ? MODALIDAD_TRIMESTRAL
                            : modalidad.strip().toUpperCase(Locale.ROOT);
        }
    }

    /**
     * El autovaluo declarado de un predio.
     *
     * @param predioId el predio, que tiene que ser del contribuyente
     * @param autovaluo terreno + construccion + obras complementarias (RT-010)
     * @param valuoExonerado la parte no afecta; {@code null} se lee como ninguna
     */
    public record PredioDeclarado(
            long predioId,
            Dinero autovaluo,
            @org.jspecify.annotations.Nullable Dinero valuoExonerado) {

        public PredioDeclarado {
            if (predioId <= 0) {
                throw new IllegalArgumentException(
                        "El predio declarado necesita su identificador: " + predioId);
            }
            Objects.requireNonNull(autovaluo, "El predio declarado necesita su autovaluo");
            if (autovaluo.esNegativo()) {
                throw new IllegalArgumentException("El autovaluo no puede ser negativo");
            }
        }
    }

    /** Ese codigo no esta en el padron de contribuyentes. */
    public static final class ContribuyenteInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteInexistente(String codigo) {
            super("No hay ningun contribuyente con codigo '" + codigo + "' en esta municipalidad");
        }
    }

    /** El contribuyente existe y no tiene ningun predio a su nombre a la fecha de calculo. */
    public static final class SinPrediosEnElPadron extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinPrediosEnElPadron(String codigo) {
            super(
                    "El contribuyente "
                            + codigo
                            + " no tiene ningun predio a su nombre a la fecha de calculo: un"
                            + " contribuyente sin predios no tiene base imponible cero, no tiene"
                            + " determinacion (NEG-05 §1)");
        }
    }

    /**
     * Falta el autovaluo de uno de los predios del contribuyente.
     *
     * <p>No se determina con los demas: los tramos son progresivos sobre la base del
     * <b>conjunto</b>, asi que dejar un predio fuera no produce una determinacion incompleta sino
     * una <b>equivocada</b> y mas barata, sin ningun error de por medio (NEG-05 §1).
     */
    public static final class PredioSinAutovaluo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        private final long predioId;

        PredioSinAutovaluo(PredioDelContribuyente predio) {
            super(
                    "El predio "
                            + predio.codigoReferenciaCatastral()
                            + " (id "
                            + predio.predioId()
                            + ") entra en la base y no trae autovaluo declarado. El sistema no lo"
                            + " puede derivar todavia —faltan el cuadro de valores unitarios y la"
                            + " tabla de depreciacion (GOB-03), los aranceles de la ordenanza"
                            + " (D-02b) y el % actualizacion, que sigue sin fuente (D-11)—, y"
                            + " determinar sin el deja la base del contribuyente por debajo de lo"
                            + " que es");
            this.predioId = predio.predioId();
        }

        public long predioId() {
            return predioId;
        }
    }

    /** Se declaro dos veces el mismo predio. */
    public static final class PredioRepetido extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioRepetido(long predioId) {
            super(
                    "El predio "
                            + predioId
                            + " se declara dos veces: no se puede saber cual de los dos autovaluos"
                            + " entra en la base");
        }
    }

    /** Se declaro un predio que no es del contribuyente a la fecha de calculo. */
    public static final class PredioAjeno extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        PredioAjeno(String codigo, long predioId) {
            super(
                    "El predio "
                            + predioId
                            + " no esta a nombre del contribuyente "
                            + codigo
                            + " a la fecha de calculo: la titularidad sale del padron, no de la"
                            + " peticion");
        }
    }
}

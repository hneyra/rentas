package kamayuk.rentas.nucleo.aplicacion;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichas;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.nucleo.dominio.DeclaracionJurada;
import kamayuk.rentas.nucleo.dominio.DeclaracionJuradaRepository;
import kamayuk.rentas.nucleo.dominio.EstadoDeDeclaracion;
import kamayuk.rentas.nucleo.dominio.PlantillaDeNumeroDeDeclaracion;
import kamayuk.rentas.nucleo.dominio.TipoDeDeclaracion;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametroSinPublicar;
import kamayuk.rentas.parametros.ParametrosSellados;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los cuatro actos de la declaracion jurada (RF-023, #28, #365).
 *
 * <p>Presentar una DJ es <b>el acto que concilia</b> (ADR-0015 §3): conciliar no es escribir un
 * codigo en la ficha —el codigo ya lo tiene— sino incorporar el predio al padron afecto, y lo que
 * lo incorpora es que exista una declaracion de ese ejercicio sobre el. La lectura que lo publica
 * es {@link ConsultaDeConciliacion} (#344); este caso de uso es lo que la alimenta.
 *
 * <p>Sigue la plantilla de {@code RegistrarBeneficio}: la {@link Observacion} esta en la firma y en
 * la fila (regla 10). Lo propio de este caso de uso son las tres resoluciones que hace antes de
 * construir el dominio, y que {@link DeclaracionJurada} deliberadamente no hace por su cuenta:
 *
 * <ul>
 *   <li><b>El numero lo pone el sistema</b>, no el llamador: {@link
 *       DeclaracionJuradaRepository#siguienteCorrelativo} reserva el ordinal en una sentencia
 *       atomica y {@link PlantillaDeNumeroDeDeclaracion} lo compone. Es la doctrina de la casa
 *       mientras D-09 siga abierta —{@code PlantillaDeNumeroDeLicencia} (#44), {@code
 *       PlantillaDeNumeroDeCertificado} (#54)— y es como opera la administracion tributaria
 *       municipal peruana: el sistema genera el numero de referencia y el administrado se lo lleva
 *       en el papel.
 *   <li>{@code fechaLimite} sale de {@link LectorDeParametros#vigenteEn}, nunca de un literal
 *       (regla 5). Es la lectura «para determinaciones nuevas»: una DJ que se presenta hoy se
 *       compara contra el plazo vigente hoy, no contra el de un conjunto que una rectificatoria
 *       futura pudiera sellar distinto.
 *   <li>{@code fichaCatastralId} sale de {@link LectorDeFichas#fichaVigenteEn}, a la fecha de
 *       presentacion —no a hoy—: es la version que regia cuando se declaro.
 * </ul>
 *
 * <h2>La maquina de estados, entera</h2>
 *
 * <pre>
 *   PRESENTADA ──observar──▶ OBSERVADA ──rectificar──▶ SUSTITUIDA (terminal)
 *       │  │                   │  │
 *       │  └──rectificar───────┼──┴─────────────────▶ SUSTITUIDA (terminal)
 *       └─────anular───────────┴────────────────────▶ ANULADA    (terminal)
 * </pre>
 *
 * <p>Quien decide si un paso es legal es el dominio ({@code observada()}, {@code anulada()}, {@code
 * sustituida()}), no un {@code if} de esta clase; y lo que ven dos peticiones simultaneas que
 * leyeron las dos el mismo estado anterior es el disparador {@code
 * declaracion_jurada_estado_terminal} de V54.
 *
 * <p><b>Ningun importe.</b> Presentar fuera de plazo genera multa tributaria segun el manual, pero
 * esa multa es D-02c (#198): aqui queda el hecho —{@code fueraDePlazo}, derivado de dos fechas— y
 * nada que multiplique dinero. Ninguna determinacion tampoco: declarar y determinar son pasos
 * distintos, y la determinacion espera a D-02a.
 */
@Service
public class RegistrarDeclaracionJurada {

    /** Tipo de {@code parametro_tributario} bajo el que vive el plazo de la DJ (ADR-0007). */
    private static final String TIPO_PARAMETRO_PLAZO = "PLAZO";

    private static final String CLAVE_PLAZO_DJ = "DECLARACION_JURADA";

    private final DeclaracionJuradaRepository repositorio;
    private final PlantillaDeNumeroDeDeclaracion plantilla;
    private final LectorDeParametros parametros;
    private final LectorDeFichas fichas;
    private final DirectorioDeContribuyentes padron;
    private final Auditoria auditoria;

    public RegistrarDeclaracionJurada(
            DeclaracionJuradaRepository repositorio,
            PlantillaDeNumeroDeDeclaracion plantilla,
            LectorDeParametros parametros,
            LectorDeFichas fichas,
            DirectorioDeContribuyentes padron,
            Auditoria auditoria) {
        this.repositorio = repositorio;
        this.plantilla = plantilla;
        this.parametros = parametros;
        this.fichas = fichas;
        this.padron = padron;
        this.auditoria = auditoria;
    }

    /**
     * Presenta una DJ nueva: HR, PU, PR o VEHICULAR.
     *
     * <p>El contribuyente entra por su <b>codigo</b> y no por su identificador, y se resuelve
     * <b>dentro</b> de esta transaccion: resolverlo en el controlador dejaria esa lectura sin
     * {@code SET LOCAL}, que es el defecto que la marcha blanca destapo en {@code GET
     * /catastro/vias} y que {@code ConsultaUnificada} ya evita del mismo modo.
     */
    @Transactional
    public DeclaracionJurada registrar(
            Ejercicio ejercicio,
            String codigoContribuyente,
            TipoDeDeclaracion tipo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            LocalDate fechaPresentacion,
            Observacion observacion) {

        long contribuyenteId = contribuyenteDe(codigoContribuyente);

        DeclaracionJurada nueva =
                DeclaracionJurada.nueva(
                        numeroDe(tipo, ejercicio),
                        ejercicio,
                        contribuyenteId,
                        tipo,
                        predioId,
                        vehiculoId,
                        fichaVigenteA(predioId, fechaPresentacion),
                        fechaPresentacion,
                        fechaLimiteDe(ejercicio),
                        observacion);

        DeclaracionJurada guardada = repositorio.insertar(nueva);
        auditar(guardada, Operacion.ALTA, observacion, null);
        return guardada;
    }

    /**
     * Rectifica una DJ ya presentada: crea la version nueva y deja la anterior {@code SUSTITUIDA},
     * sin tocar su contenido (regla 4). Las dos filas quedan en la base.
     *
     * <p><b>Puede cambiar de predio</b>, y la conciliacion lo contempla: el predio que se declaro
     * por error deja de conciliar por esta cadena y el que la rectificatoria declara pasa a
     * hacerlo, sin que ninguno de los dos cuente dos veces (ADR-0015 §1, #344).
     *
     * <p>Solo se rectifica una declaracion <b>vigente</b>. Una anulada no revive rectificandola, y
     * una sustituida ya tiene quien la sustituya: rectificarla otra vez dejaria dos rectificatorias
     * vivas sobre la misma DJ, y ninguna consulta podria decir cual es la que el contribuyente
     * declara hoy. Lo dice esta clase, y lo sostiene {@code dj_rectifica_uq} (V54) cuando dos
     * peticiones simultaneas leen las dos que la anterior estaba en pie.
     */
    @Transactional
    public DeclaracionJurada rectificar(
            String numeroAnterior,
            Ejercicio ejercicio,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            LocalDate fechaPresentacion,
            Observacion observacion) {

        DeclaracionJurada anterior = exigirDeclaracion(numeroAnterior, ejercicio);

        DeclaracionJurada rectificatoria =
                anterior.rectificadaPor(
                        numeroDe(TipoDeDeclaracion.RECTIFICATORIA, anterior.ejercicio()),
                        predioId,
                        vehiculoId,
                        fichaVigenteA(predioId, fechaPresentacion),
                        fechaPresentacion,
                        fechaLimiteDe(anterior.ejercicio()),
                        observacion);

        DeclaracionJurada guardada = repositorio.insertar(rectificatoria);
        DeclaracionJurada sustituida =
                repositorio.marcar(idDe(anterior), EstadoDeDeclaracion.SUSTITUIDA);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fechaPresentacion,
                                "declaracion_jurada",
                                String.valueOf(idDe(anterior)),
                                Operacion.MODIFICACION,
                                observacion)
                        .con(descripcion(anterior), descripcion(sustituida)));
        auditar(guardada, Operacion.ALTA, observacion, null);

        return guardada;
    }

    /**
     * La administracion objeta el contenido de una declaracion presentada (#365).
     *
     * <p>Observarla <b>no la retira</b>: el predio sigue conciliando (ADR-0015 §1), porque negarle
     * la conciliacion diria «este predio no genera deuda predial» de un predio que si la genera. Lo
     * que la observacion abre es el camino de la rectificatoria.
     */
    @Transactional
    public DeclaracionJurada observar(String numero, Ejercicio ejercicio, Observacion observacion) {
        return actoDeLaAdministracion(
                numero, ejercicio, EstadoDeDeclaracion.OBSERVADA, observacion);
    }

    /**
     * La administracion anula una declaracion (#365).
     *
     * <p>Al reves que observarla, anularla si la retira: deja de sustentar nada y el predio deja de
     * conciliar por ella. Y es terminal: <b>una anulada no revive</b>. Si el contribuyente declara
     * otra vez, se presenta otra declaracion, con su numero.
     */
    @Transactional
    public DeclaracionJurada anular(String numero, Ejercicio ejercicio, Observacion observacion) {
        return actoDeLaAdministracion(numero, ejercicio, EstadoDeDeclaracion.ANULADA, observacion);
    }

    // ------------------------------------------------------------------

    private DeclaracionJurada actoDeLaAdministracion(
            String numero,
            Ejercicio ejercicio,
            EstadoDeDeclaracion nuevo,
            Observacion observacion) {

        DeclaracionJurada antes = exigirDeclaracion(numero, ejercicio);
        DeclaracionJurada despues = repositorio.marcar(idDe(antes), nuevo);
        auditar(despues, Operacion.MODIFICACION, observacion, descripcion(antes));
        return despues;
    }

    /** El numero, compuesto sobre el correlativo que la base acaba de reservar. */
    private String numeroDe(TipoDeDeclaracion tipo, Ejercicio ejercicio) {
        return plantilla.componer(tipo, ejercicio, repositorio.siguienteCorrelativo(ejercicio));
    }

    private DeclaracionJurada exigirDeclaracion(String numero, Ejercicio ejercicio) {
        return repositorio
                .porNumero(numero, ejercicio)
                .orElseThrow(() -> new DeclaracionInexistente(numero, ejercicio));
    }

    private static long idDe(DeclaracionJurada declaracion) {
        Long id = declaracion.id();
        if (id == null) {
            throw new IllegalStateException(
                    "La declaracion leida de la base no trae identificador");
        }
        return id;
    }

    private long contribuyenteDe(String codigo) {
        ResumenDeContribuyente contribuyente =
                padron.porCodigo(codigo.strip())
                        .orElseThrow(() -> new ContribuyenteInexistente(codigo));
        return contribuyente.id();
    }

    private @Nullable Long fichaVigenteA(@Nullable Long predioId, LocalDate fecha) {
        if (predioId == null) {
            return null;
        }
        return fichas.fichaVigenteEn(predioId, fecha).orElse(null);
    }

    private LocalDate fechaLimiteDe(Ejercicio ejercicio) {
        ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
        String texto =
                sellados.texto(TIPO_PARAMETRO_PLAZO, CLAVE_PLAZO_DJ)
                        .orElseThrow(() -> new PlazoSinParametrizar(ejercicio));
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new IllegalStateException(
                    "El plazo parametrizado del ejercicio "
                            + ejercicio
                            + " no es una fecha valida: '"
                            + texto
                            + "'",
                    malFormada);
        }
    }

    private void auditar(
            DeclaracionJurada guardada,
            Operacion operacion,
            Observacion observacion,
            @Nullable String antes) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                guardada.fechaPresentacion(),
                                "declaracion_jurada",
                                String.valueOf(idDe(guardada)),
                                operacion,
                                observacion)
                        .con(antes, descripcion(guardada)));
    }

    private static String descripcion(DeclaracionJurada declaracion) {
        return "{\"contribuyenteId\":"
                + declaracion.contribuyenteId()
                + ",\"tipo\":\""
                + declaracion.tipo()
                + "\",\"numero\":\""
                + declaracion.numero()
                + "\",\"estado\":\""
                + declaracion.estado()
                + "\",\"fueraDePlazo\":"
                + declaracion.fueraDePlazo()
                + "}";
    }

    /** No hay ninguna DJ con ese numero en ese ejercicio, o es de otra municipalidad. */
    public static final class DeclaracionInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        DeclaracionInexistente(String numero, Ejercicio ejercicio) {
            super(
                    "No hay ninguna declaracion jurada con numero "
                            + numero
                            + " del ejercicio "
                            + ejercicio
                            + " en esta municipalidad");
        }
    }

    /** El codigo de contribuyente que trae la peticion no esta en el padron. */
    public static final class ContribuyenteInexistente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        ContribuyenteInexistente(String codigo) {
            super(
                    "No hay ningun contribuyente con el codigo '"
                            + codigo
                            + "' en esta municipalidad");
        }
    }

    /**
     * El ejercicio no tiene parametrizado el plazo de presentacion. No hay valor por omision: un
     * plazo inventado clasificaria mal cada DJ que se registre.
     *
     * <p>El mensaje <b>nombra la llave</b>, {@code PLAZO:DECLARACION_JURADA}: quien recibe el 422
     * en ventanilla no puede hacer nada con «falta un parametro», y quien carga los parametros
     * necesita saber cual (regla 5).
     */
    public static final class PlazoSinParametrizar extends RuntimeException
            implements ParametroSinPublicar {
        @java.io.Serial private static final long serialVersionUID = 1L;

        // El aviso [serial] no aplica: `Ejercicio` es un record del dominio que no
        // implementa Serializable, y una excepcion de negocio nunca se serializa —se
        // lanza, se traduce a problem+json y muere ahi (ManejadorDeErrores)—.
        @SuppressWarnings("serial")
        private final Ejercicio ejercicio;

        PlazoSinParametrizar(Ejercicio ejercicio) {
            super(
                    "El conjunto sellado del ejercicio "
                            + ejercicio
                            + " no tiene parametrizado el plazo de declaracion jurada ("
                            + TIPO_PARAMETRO_PLAZO
                            + ":"
                            + CLAVE_PLAZO_DJ
                            + ")");
            this.ejercicio = ejercicio;
        }

        @Override
        public Ejercicio ejercicio() {
            return ejercicio;
        }

        @Override
        public Optional<String> llave() {
            return Optional.of(TIPO_PARAMETRO_PLAZO + ":" + CLAVE_PLAZO_DJ);
        }
    }
}

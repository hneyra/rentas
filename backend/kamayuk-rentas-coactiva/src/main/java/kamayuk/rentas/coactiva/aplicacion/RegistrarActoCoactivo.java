package kamayuk.rentas.coactiva.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dominio.ActoCoactivo;
import kamayuk.rentas.coactiva.dominio.ActoCoactivoRepository;
import kamayuk.rentas.coactiva.dominio.DeudaDelExpediente;
import kamayuk.rentas.coactiva.dominio.EstadoDelExpediente;
import kamayuk.rentas.coactiva.dominio.ExpedienteCoactivo;
import kamayuk.rentas.coactiva.dominio.ExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpediente;
import kamayuk.rentas.coactiva.dominio.MovimientoDelExpedienteRepository;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactiva;
import kamayuk.rentas.coactiva.dominio.NotificacionCoactivaRepository;
import kamayuk.rentas.coactiva.dominio.TipoDeActoCoactivo;
import kamayuk.rentas.coactiva.dominio.TipoDeMedidaCautelar;
import kamayuk.rentas.coactiva.dominio.ValorDelExpediente;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.documentos.ModeloDeDocumento;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Plazo;
import kamayuk.rentas.valores.ValorParaCoactiva;
import kamayuk.rentas.valores.ValoresEnCoactiva;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Dicta un acto del procedimiento coactivo, emite su documento y mueve el expediente (#41, RF-101,
 * RF-102).
 *
 * <h2>Un solo camino de escritura para los diez tipos de acto</h2>
 *
 * <p>La REC-1, la REC-2 y los demas actos comparten todas las guardas —el expediente existe, no
 * esta concluido, todavia hay deuda que cobrar— y las tres pantallas que los dictan ({@code
 * rec_impresion}, {@code actos_coactivos}, {@code proceso_coactivo}) son vistas del mismo acto
 * administrativo. Escribirlas por separado seria tener tres sitios donde olvidar una guarda, y el
 * olvido no se veria: el acto entraria y el papel saldria.
 *
 * <h2>Lo que la base decide, y lo que decide este codigo</h2>
 *
 * <ul>
 *   <li><b>La base</b>: que no haya dos REC-1 del mismo expediente ({@code acto_rec1_uq}), que la
 *       REC-2 lleve su sustento entero ({@code acto_rec2_sustento_ck}) y que no sea anterior al dia
 *       en que vence el plazo ({@code acto_rec2_plazo_ck}). Son las tres que un {@code CHECK} o un
 *       indice pueden expresar, y por eso van ahi: dos peticiones simultaneas pasan las dos por
 *       cualquier {@code if}.
 *   <li><b>Este codigo</b>: que la notificacion que sustenta la REC-2 sea la de la REC-1 <b>de este
 *       expediente</b> y que haya surtido efecto —eso exige un {@code JOIN}, y un {@code CHECK} no
 *       puede hacerlo—, y que quede deuda viva.
 * </ul>
 *
 * <h2>El pago total cierra la puerta, menos a los actos que lo reconocen</h2>
 *
 * <p>La deuda no se lee de los importes congelados de los valores: se le pregunta a {@code
 * cuentacorriente} <b>a la fecha del acto</b>, por los puertos publicos, exactamente como hace
 * {@link ConsultaDeExpedientes} (regla 9). Si no queda nada, dictar un embargo seria embargar a
 * quien ya pago. Los tres actos que <b>reconocen</b> ese hecho —conclusion, suspension y
 * levantamiento— quedan exentos: si no lo estuvieran, un expediente pagado no se podria concluir
 * nunca ({@link TipoDeActoCoactivo#exigeDeudaViva()}).
 *
 * <h2>El acto y su papel nacen juntos</h2>
 *
 * <p>El documento se emite en la <b>misma transaccion</b>, con {@link EmitirDocumento}: el numero
 * del acto <b>es</b> el del documento, y el documento guarda los datos con que se dibujo mas el
 * SHA-256 de lo que salio. Reimprimir la REC dentro de diez anios vuelve a dibujar esos datos y
 * comprueba que el resumen coincide (RF-132). Un acto sin documento no se puede notificar; un
 * documento sin acto no tiene procedimiento que lo explique.
 */
@Service
public class RegistrarActoCoactivo {

    private final ExpedienteRepository expedientes;
    private final MovimientoDelExpedienteRepository movimientos;
    private final ActoCoactivoRepository actos;
    private final NotificacionCoactivaRepository notificaciones;
    private final ConsultaDeExpedientes consulta;
    private final ValoresEnCoactiva valores;
    private final DirectorioDeContribuyentes contribuyentes;
    private final PlazosCoactivosParametrizados plazos;
    private final EmitirDocumento documentos;
    private final Auditoria auditoria;
    private final Clock reloj;

    public RegistrarActoCoactivo(
            ExpedienteRepository expedientes,
            MovimientoDelExpedienteRepository movimientos,
            ActoCoactivoRepository actos,
            NotificacionCoactivaRepository notificaciones,
            ConsultaDeExpedientes consulta,
            ValoresEnCoactiva valores,
            DirectorioDeContribuyentes contribuyentes,
            PlazosCoactivosParametrizados plazos,
            EmitirDocumento documentos,
            Auditoria auditoria,
            Clock reloj) {
        this.expedientes = expedientes;
        this.movimientos = movimientos;
        this.actos = actos;
        this.notificaciones = notificaciones;
        this.consulta = consulta;
        this.valores = valores;
        this.contribuyentes = contribuyentes;
        this.plazos = plazos;
        this.documentos = documentos;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Dicta el acto, emite su documento y deja el expediente en el estado que el acto produce.
     *
     * @param peticion que expediente, que acto, con que fecha y con que glosa
     * @param formato en que formato sale el papel
     * @param observacion por que se dicta (regla 10, RNF-052)
     * @throws CambiarEstadoDelExpediente.ExpedienteInexistente si no hay ningun expediente con ese
     *     numero
     * @throws CambiarEstadoDelExpediente.ExpedienteConcluido si el procedimiento ya termino
     * @throws DeudaExtinguida si no queda nada que cobrar y el acto exige deuda viva
     * @throws Rec1SinNotificar si se pide la REC-2 y la REC-1 no esta notificada
     * @throws PlazoDeLaRec1EnCurso si se pide la REC-2 y el plazo todavia corre
     */
    @Transactional
    public ActoDictado dictar(
            Peticion peticion, FormatoDeDocumento formato, Observacion observacion) {

        ExpedienteCoactivo expediente =
                expedientes
                        .porNumero(peticion.numeroDeExpediente())
                        .orElseThrow(
                                () ->
                                        new CambiarEstadoDelExpediente.ExpedienteInexistente(
                                                peticion.numeroDeExpediente()));

        List<MovimientoDelExpediente> historial =
                movimientos.deExpediente(expediente.identificador());
        EstadoDelExpediente actual = EstadoDelExpediente.delHistorial(historial);
        if (actual.estaConcluido()) {
            throw new CambiarEstadoDelExpediente.ExpedienteConcluido(expediente.numero());
        }

        LocalDate fecha = peticion.fecha();
        // La cifra que decide y la cifra que se imprime son LA MISMA. Dos fechas distintas -una
        // para la guarda y otra para el papel- dejarian que la resolucion dijera 535,50 mientras
        // la guarda leia cero, o al reves; y la que el obligado tiene en la mano es la impresa.
        LocalDate pedida = peticion.proyectarDeudaAl();
        LocalDate proyeccion = pedida == null ? fecha : pedida;
        DeudaDelExpediente deuda = consulta.deudaDe(expediente, proyeccion);
        if (peticion.tipo().exigeDeudaViva() && !deuda.total().esPositivo()) {
            throw new DeudaExtinguida(expediente.numero(), peticion.tipo(), proyeccion);
        }

        Sustento sustento = sustentoDe(expediente, peticion.tipo(), fecha);
        Instant ahora = reloj.instant();

        ResumenDeContribuyente obligado = obligadoDe(expediente);
        ModeloDeDocumento modelo =
                ModeloDelActoCoactivo.de(
                        expediente,
                        peticion.tipo(),
                        peticion.medida(),
                        peticion.descripcion(),
                        obligado.nombre(),
                        obligado.codigo(),
                        direccionVigenteDe(expediente),
                        peticion.tipo() == TipoDeActoCoactivo.REC1 ? plazoDeLaRec1(fecha) : null,
                        deuda,
                        valoresDelExpediente(expediente, proyeccion));

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        peticion.tipo().name(),
                        expediente.ejercicio(),
                        expediente.numero(),
                        modelo,
                        formato,
                        observacion);

        long documentoId =
                java.util.Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        ActoCoactivo registrado =
                actos.registrar(
                        peticion.tipo() == TipoDeActoCoactivo.REC2
                                ? ActoCoactivo.rec2(
                                        expediente.identificador(),
                                        emision.registro().numero(),
                                        fecha,
                                        peticion.descripcion(),
                                        exigirMedida(peticion),
                                        sustento.exigirNotificacion(),
                                        sustento.exigirDesde(),
                                        documentoId,
                                        ahora,
                                        observacion)
                                : ActoCoactivo.nuevo(
                                        expediente.identificador(),
                                        peticion.tipo(),
                                        emision.registro().numero(),
                                        fecha,
                                        peticion.descripcion(),
                                        documentoId,
                                        ahora,
                                        observacion));

        EstadoDelExpediente nuevo =
                avanzar(expediente, actual, registrado, fecha, ahora, observacion);

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                fecha,
                                "acto_coactivo",
                                String.valueOf(registrado.id()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(expediente, registrado, nuevo)));

        return new ActoDictado(registrado, emision, nuevo, deuda);
    }

    // ------------------------------------------------------------------

    /**
     * El sustento de la REC-2: la diligencia que notifico la REC-1 y el dia desde el que la medida
     * se puede dictar.
     *
     * <p>Las tres condiciones se comprueban por separado porque las tres se arreglan de maneras
     * distintas: dictar la REC-1, notificarla, o esperar. Un unico «no procede» dejaria a quien
     * opera adivinando cual de las tres le falta.
     */
    private Sustento sustentoDe(
            ExpedienteCoactivo expediente, TipoDeActoCoactivo tipo, LocalDate fecha) {
        if (!tipo.exigeRec1Vencida()) {
            return Sustento.NINGUNO;
        }
        ActoCoactivo rec1 =
                actos.rec1De(expediente.identificador())
                        .orElseThrow(() -> new Rec1SinDictar(expediente.numero()));
        NotificacionCoactiva diligencia =
                notificaciones
                        .queSurtioEfecto(rec1.identificador())
                        .orElseThrow(
                                () -> new Rec1SinNotificar(expediente.numero(), rec1.numero()));
        LocalDate desde =
                java.util.Objects.requireNonNull(
                        diligencia.exigibleDesde(),
                        "Una diligencia que surtio efecto siempre trae su exigibilidad (V28)");
        if (fecha.isBefore(desde)) {
            throw new PlazoDeLaRec1EnCurso(expediente.numero(), rec1.numero(), desde, fecha);
        }
        return new Sustento(diligencia.identificador(), desde);
    }

    /**
     * Deja el expediente en el estado que el acto produce, agregando un movimiento.
     *
     * <p>Si el acto no mueve el estado —una tasacion, un remate— o si el expediente ya estaba donde
     * el acto lo dejaria, no se agrega nada: el historial es la traza del <b>procedimiento</b>, y
     * llenarlo de filas que no cambian nada lo vuelve ilegible. El acto queda igual en {@code
     * acto_coactivo}, que es donde vive.
     */
    private EstadoDelExpediente avanzar(
            ExpedienteCoactivo expediente,
            EstadoDelExpediente actual,
            ActoCoactivo acto,
            LocalDate fecha,
            Instant ahora,
            Observacion observacion) {

        EstadoDelExpediente destino = acto.tipo().estadoQueProduce();
        if (destino == null || destino == actual) {
            return actual;
        }
        // El documento de respaldo del movimiento es el propio acto: su fecha y su numero. Van los
        // dos o no va ninguno -lo exige `expediente_movimiento_documento_ck` (V33)-, y aqui van
        // los dos, que es lo que permite leer el historial y saber que papel lo sustenta.
        movimientos.registrar(
                MovimientoDelExpediente.cambioDeEstado(
                        expediente.identificador(),
                        destino,
                        fecha,
                        acto.tipo().titulo(),
                        acto.fecha(),
                        acto.numero(),
                        ahora,
                        observacion));
        return destino;
    }

    private Plazo plazoDeLaRec1(LocalDate fecha) {
        return plazos.aLaFechaDe(fecha).paraCumplirLaRec1();
    }

    private ResumenDeContribuyente obligadoDe(ExpedienteCoactivo expediente) {
        ResumenDeContribuyente enElPadron =
                contribuyentes
                        .porIds(Set.of(expediente.contribuyenteId()))
                        .get(expediente.contribuyenteId());
        if (enElPadron == null) {
            throw new IllegalStateException(
                    "El expediente "
                            + expediente.numero()
                            + " se sigue contra un contribuyente que el padron no tiene");
        }
        return enElPadron;
    }

    private @Nullable String direccionVigenteDe(ExpedienteCoactivo expediente) {
        return movimientos
                .ultimoCambioDeDireccion(expediente.identificador())
                .map(MovimientoDelExpediente::direccionNueva)
                .orElseGet(expediente::direccionReferencial);
    }

    /** Los valores que el expediente agrupa, tal como {@code valores} los publica. */
    private List<ValorParaCoactiva> valoresDelExpediente(
            ExpedienteCoactivo expediente, LocalDate fecha) {
        Set<Long> suyos = new HashSet<>();
        for (ValorDelExpediente valor : expedientes.valoresDe(expediente.identificador())) {
            suyos.add(valor.valorId());
        }
        List<ValorParaCoactiva> impresos = new ArrayList<>();
        for (ValorParaCoactiva valor :
                valores.delContribuyente(expediente.contribuyenteId(), fecha)) {
            if (suyos.contains(valor.id())) {
                impresos.add(valor);
            }
        }
        return impresos;
    }

    private static TipoDeMedidaCautelar exigirMedida(Peticion peticion) {
        TipoDeMedidaCautelar medida = peticion.medida();
        if (medida == null) {
            throw new IllegalArgumentException(
                    "La REC-2 declara en que forma se traba la medida cautelar: retencion,"
                            + " inscripcion, deposito o intervencion (art. 33 de la Ley 26979)");
        }
        return medida;
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            ExpedienteCoactivo expediente, ActoCoactivo acto, EstadoDelExpediente estado) {
        return "{\"expediente\":\""
                + expediente.numero()
                + "\",\"acto\":\""
                + acto.tipo().name()
                + "\",\"numero\":\""
                + acto.numero()
                + "\",\"estado\":\""
                + estado.name()
                + "\"}";
    }

    /** La diligencia que sustenta la REC-2 y el dia desde el que se puede dictar. */
    private record Sustento(@Nullable Long notificacionId, @Nullable LocalDate exigibleDesde) {

        static final Sustento NINGUNO = new Sustento(null, null);

        long exigirNotificacion() {
            return java.util.Objects.requireNonNull(
                    notificacionId, "Solo la REC-2 pide su sustento, y ahi nunca falta");
        }

        LocalDate exigirDesde() {
            return java.util.Objects.requireNonNull(
                    exigibleDesde, "Solo la REC-2 pide su sustento, y ahi nunca falta");
        }
    }

    /**
     * Lo que la pantalla manda para dictar un acto.
     *
     * @param numeroDeExpediente el numero impreso del expediente
     * @param tipo que acto se dicta
     * @param fecha el dia del acto; entra como argumento para que un acto dispuesto por una
     *     resolucion se registre con la fecha de la resolucion
     * @param descripcion la glosa
     * @param medida la forma de la medida cautelar; obligatoria en la REC-2
     * @param proyectarDeudaAl a que dia se proyecta la deuda que se imprime —«Proyectar interes al»
     *     de la pantalla {@code rec_impresion}—; nulo significa el dia del acto. Es tambien la
     *     fecha con la que se comprueba que quede algo que cobrar: la cifra que decide y la que se
     *     imprime tienen que ser la misma
     */
    public record Peticion(
            String numeroDeExpediente,
            TipoDeActoCoactivo tipo,
            LocalDate fecha,
            String descripcion,
            @Nullable TipoDeMedidaCautelar medida,
            @Nullable LocalDate proyectarDeudaAl) {

        public Peticion {
            java.util.Objects.requireNonNull(numeroDeExpediente, "Falta el numero de expediente");
            java.util.Objects.requireNonNull(tipo, "Falta el tipo de acto");
            java.util.Objects.requireNonNull(fecha, "Falta la fecha del acto");
            java.util.Objects.requireNonNull(descripcion, "Falta la glosa del acto");
        }
    }

    /**
     * El acto dictado, con el papel que salio.
     *
     * @param acto la fila registrada
     * @param emision los bytes del documento y su registro
     * @param estado el estado en que queda el expediente
     * @param deuda cuanto se debia el dia del acto, con su fecha (regla 9)
     */
    public record ActoDictado(
            ActoCoactivo acto,
            EmitirDocumento.Emision emision,
            EstadoDelExpediente estado,
            DeudaDelExpediente deuda) {}

    /** El expediente ya no debe nada: no hay nada que ejecutar. */
    public static final class DeudaExtinguida extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        DeudaExtinguida(String numero, TipoDeActoCoactivo tipo, LocalDate fecha) {
            super(
                    "El expediente "
                            + numero
                            + " no tiene deuda al "
                            + fecha
                            + ": dictar "
                            + tipo.titulo()
                            + " sobre quien ya pago es lo que produce embargos indebidos. Lo que"
                            + " corresponde es concluir el procedimiento");
        }
    }

    /** Se pidio la REC-2 y el expediente todavia no tiene REC-1. */
    public static final class Rec1SinDictar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        Rec1SinDictar(String numero) {
            super(
                    "El expediente "
                            + numero
                            + " no tiene REC-1: la medida cautelar se dicta despues de la"
                            + " resolucion que inicia el procedimiento (art. 14.1 de la Ley"
                            + " 26979), no antes");
        }
    }

    /** La REC-1 existe pero ninguna diligencia surtio efecto. */
    public static final class Rec1SinNotificar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        Rec1SinNotificar(String expediente, String rec1) {
            super(
                    "La REC-1 "
                            + rec1
                            + " del expediente "
                            + expediente
                            + " no esta notificada: el plazo que da derecho a la medida cautelar"
                            + " se cuenta desde la notificacion, y sin ella no ha empezado a"
                            + " correr");
        }
    }

    /** La REC-1 esta notificada pero el plazo todavia corre. */
    public static final class PlazoDeLaRec1EnCurso extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        PlazoDeLaRec1EnCurso(
                String expediente, String rec1, LocalDate exigibleDesde, LocalDate pedida) {
            super(
                    "El plazo de la REC-1 "
                            + rec1
                            + " del expediente "
                            + expediente
                            + " vence el "
                            + exigibleDesde.minusDays(1)
                            + ": la medida cautelar no se puede dictar el "
                            + pedida
                            + ", sino desde el "
                            + exigibleDesde);
        }
    }
}

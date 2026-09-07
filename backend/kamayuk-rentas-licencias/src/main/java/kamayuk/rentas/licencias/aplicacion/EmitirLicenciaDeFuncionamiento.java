package kamayuk.rentas.licencias.aplicacion;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CiiuRepository;
import kamayuk.rentas.licencias.dominio.CompatibilidadConLaZona;
import kamayuk.rentas.licencias.dominio.ComprobacionDelTerritorio;
import kamayuk.rentas.licencias.dominio.GiroDeLaLicencia;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.LicenciaRepository;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicenciaRepository;
import kamayuk.rentas.licencias.dominio.PlantillaDeNumeroDeLicencia;
import kamayuk.rentas.licencias.dominio.RespuestaDelTerritorio;
import kamayuk.rentas.licencias.dominio.TerritorioDeLaLicencia;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Emite una licencia de funcionamiento con sus giros CIIU, su papel y su movimiento de emision
 * (#44, RF-110, RF-111).
 *
 * <h2>Lo que la base decide, y lo que decide este codigo</h2>
 *
 * <ul>
 *   <li><b>La base</b>: que haya recibo ({@code recibo_id NOT NULL}), que haya documento ({@code
 *       documento_id NOT NULL}), que el numero no se repita ({@code licencia_numero_uq}), que haya
 *       un solo giro principal ({@code licencia_giro_principal_uq}) y que la emision no se registre
 *       dos veces ({@code licencia_movimiento_emision_uq}). Son las que un indice o un {@code
 *       CHECK} pueden expresar, y por eso van ahi: dos peticiones simultaneas pasan las dos por
 *       cualquier {@code if}.
 *   <li><b>Este codigo</b>: que el recibo sea de caja de tasas, no este anulado, sea del titular y
 *       cubra el concepto del TUPA que el conjunto sellado nombra —eso exige un {@code JOIN} contra
 *       otro contexto, y un {@code CHECK} no puede hacerlo—; y que los giros existan en el
 *       catalogo.
 * </ul>
 *
 * <h2>La licencia y su papel nacen juntos</h2>
 *
 * <p>El documento se emite en la <b>misma transaccion</b>, con {@link EmitirDocumento}, y guarda
 * los datos con que se dibujo mas el SHA-256 de lo que salio. Es lo que hace que un duplicado
 * pedido en 2034 sea el <b>mismo</b> papel (RF-132) y no uno nuevo con el mismo numero. Una
 * licencia sin documento no se puede entregar; un documento sin licencia no tiene acto que lo
 * explique.
 *
 * <h2>El territorio se pregunta ANTES de autorizar, y la ausencia no autoriza (#43)</h2>
 *
 * <p>Hasta este issue la licencia se emitia contra una zona <b>tecleada</b> en la solicitud, que no
 * se comparaba con nada, y sin mirar el riesgo del suelo ni el ITSE. {@code catastro} publicaba las
 * tres cosas —{@code /urbano/zonificacion}, {@code /grd/riesgo} y {@code /grd/itse}— y no las
 * llamaba nadie. Ahora las llama {@link ComprobarElTerritorio}, con la fecha de emision, y lo que
 * conteste decide:
 *
 * <ul>
 *   <li><b>Riesgo no mitigable comprobado ⇒ NO SE EMITE</b>, y no hay autorizacion que lo salve. Es
 *       el unico desenlace sin salida, y es porque el hecho esta <b>medido</b> y el dano no se
 *       corrige con una resolucion posterior: un local abierto sobre un suelo que se mueve ya esta
 *       abierto.
 *   <li><b>El giro no cabe en la zona ⇒ hace falta una autorizacion explicita</b>, con su
 *       observacion. No se rechaza en seco porque {@code ciiu.zonificacion_compatible} es <b>texto
 *       libre</b> —el indice de usos es ordenanza local, D-02b— y el vocabulario de los dos lados
 *       no esta normalizado: negar sobre una comparacion de cadenas denegaria licencias que la
 *       ordenanza permite. Lo que no puede seguir es no preguntar.
 *   <li><b>Alguna consulta no contesto ⇒ hace falta la misma autorizacion explicita.</b> «No se
 *       pudo preguntar» NO es «no hay riesgo» (AC-4). Y no se rechaza en seco por una razon medida:
 *       hoy <b>no hay ni un poligono cargado en ninguna instalacion</b>, asi que rechazar dejaria
 *       el modulo de licencias sin poder emitir una sola — y un sistema que no se puede usar se
 *       acaba desactivando, que es como se pierden las guardas.
 *   <li><b>Todo comprobado y favorable ⇒ se emite</b> sin pedirle nada mas a nadie.
 * </ul>
 *
 * <p>Las tres razones por las que puede hacer falta la autorizacion <b>se distinguen en el
 * mensaje</b> y llegan a quien opera como tres cosas distintas (AC-5): no consta el predio, no se
 * pudo preguntar, o el giro no cabe. Se arreglan de tres maneras —dar de alta el predio o cargar el
 * plano, levantar el despliegue, y revisar el indice de usos— y decir la equivocada manda a quien
 * atiende a buscar donde no es.
 *
 * <p>Y <b>las dos zonas se guardan</b>: la declarada en {@code zonificacion} y la del territorio en
 * {@code zona_del_territorio}, con {@code zona_origen} diciendo cual sostiene el acto (V14).
 *
 * <h2>Ningun cargo en la cuenta corriente, y es una decision</h2>
 *
 * <p>Emitir una licencia <b>no</b> genera deuda. El derecho de tramite ya se pago en caja de tasas
 * —y por eso hay un recibo que comprobar—, y un derecho de tramite no es deuda tributaria: no se
 * determina, no devenga interes y no prescribe (ver {@code CobrarTasa}). Lo unico que una licencia
 * podria generar es la deuda de <b>arbitrios del establecimiento</b>, y esa la determina {@code
 * rentas} con las tablas de la ordenanza, que estan bloqueadas por D-02b. Cuando llegue, entrara
 * por {@code cuentacorriente.GeneradorDeCargos} como entra todo cargo de otro contexto (ARQ-01 §4
 * regla 2) — asi entra ya la tasa de anuncios (#51), que es por lo que el modulo depende hoy de
 * {@code cuentacorriente}; esta emision sigue sin tocarlo.
 */
@Service
public class EmitirLicenciaDeFuncionamiento {

    /** El {@code tipo} con que se guarda el papel de la licencia en {@code documento_emitido}. */
    public static final String TIPO_DE_DOCUMENTO = "LICENCIA_FUNCIONAMIENTO";

    private final LicenciaRepository licencias;
    private final MovimientoDeLicenciaRepository movimientos;
    private final CiiuRepository catalogo;
    private final RecibosDeTramite recibos;
    private final DirectorioDeContribuyentes contribuyentes;
    private final LectorDeFichasEconomicas fichas;
    private final ComprobarElTerritorio territorio;
    private final DerechosDeTramiteParametrizados derechos;
    private final EmitirDocumento documentos;
    private final PlantillaDeNumeroDeLicencia plantilla;
    private final Auditoria auditoria;
    private final Clock reloj;

    public EmitirLicenciaDeFuncionamiento(
            LicenciaRepository licencias,
            MovimientoDeLicenciaRepository movimientos,
            CiiuRepository catalogo,
            RecibosDeTramite recibos,
            DirectorioDeContribuyentes contribuyentes,
            LectorDeFichasEconomicas fichas,
            ComprobarElTerritorio territorio,
            DerechosDeTramiteParametrizados derechos,
            EmitirDocumento documentos,
            PlantillaDeNumeroDeLicencia plantilla,
            Auditoria auditoria,
            Clock reloj) {
        this.licencias = licencias;
        this.movimientos = movimientos;
        this.catalogo = catalogo;
        this.recibos = recibos;
        this.contribuyentes = contribuyentes;
        this.fichas = fichas;
        this.territorio = territorio;
        this.derechos = derechos;
        this.documentos = documentos;
        this.plantilla = plantilla;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /**
     * Emite la licencia.
     *
     * <p>La {@link Observacion} va en la firma y no dentro de {@link Solicitud}: la regla 10 exige
     * que se vea en el punto donde se escribe, y ArchUnit la comprueba mirando los parametros del
     * metodo transaccional.
     *
     * @throws TitularDesconocido si el codigo de contribuyente no esta en el padron
     * @throws GiroDesconocido si algun giro no esta en el catalogo CIIU
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho (RF-110)
     * @throws DerechosDeTramiteParametrizados.DerechoSinParametrizar si el conjunto sellado no dice
     *     que concepto del TUPA cobra el derecho
     * @throws RiesgoNoMitigable si el lote cruza una zona de riesgo no mitigable comprobada (#43)
     * @throws TerritorioSinAutorizar si algo del territorio no se pudo comprobar —o el giro no cabe
     *     en la zona— y la solicitud no trae la autorizacion explicita que lo asume
     */
    @Transactional
    public LicenciaEmitida emitir(
            Solicitud solicitud, FormatoDeDocumento formato, Observacion observacion) {

        Objects.requireNonNull(solicitud, "No se emite sin solicitud");
        Objects.requireNonNull(formato, "Hay que decir en que formato sale el papel");
        Objects.requireNonNull(observacion, "Sin observacion no se guarda (regla 10, RNF-052)");

        ResumenDeContribuyente titular =
                contribuyentes
                        .porCodigo(solicitud.codigoContribuyente())
                        .orElseThrow(() -> new TitularDesconocido(solicitud.codigoContribuyente()));

        String concepto = derechos.aLaFechaDe(solicitud.fechaEmision()).paraLaLicencia();
        ReciboDeTramite recibo =
                ComprobacionDelDerecho.exigir(
                        recibos,
                        solicitud.numeroDeRecibo(),
                        titular.id(),
                        concepto,
                        "registro de licencia de funcionamiento");

        List<GiroDeLaLicencia> giros = resolverGiros(solicitud);

        // EL TERRITORIO SE PREGUNTA AQUI, antes de numerar y antes de dibujar el papel. Despues
        // seria descubrirlo con el documento ya emitido y su huella ya calculada, y una licencia
        // no se edita (V37): corregirla es otro acto.
        //
        // La zona compatible que decide es la del giro PRINCIPAL, y no la union de los giros: lo
        // dice `LicenciaDeFuncionamiento` con todas las letras —«la actividad principal es la que
        // decide el riesgo de la ITSE y la compatibilidad con la zonificacion»— y tomar la union
        // dejaria que un giro secundario compatible autorizara al principal que no lo es.
        Ciiu principal = catalogo.porCodigo(solicitud.giroPrincipal()).orElseThrow();
        ComprobacionDelTerritorio comprobacion =
                territorio.de(
                        solicitud.predioId(),
                        solicitud.fechaEmision(),
                        principal.zonificacionCompatible());
        exigirQueElTerritorioLoPermita(solicitud, comprobacion, principal);

        Ejercicio ejercicio = Ejercicio.de(solicitud.fechaEmision());
        String numero = plantilla.componer(ejercicio, licencias.siguienteCorrelativo(ejercicio));

        // La ficha economica se pide a `catastro` por su puerto publico, con la fecha de emision:
        // la licencia queda enlazada a la version que regia ese dia, no a «la ultima» (regla 9).
        Long fichaId =
                solicitud.predioId() == null
                        ? null
                        : fichas.fichaEconomicaVigenteEn(
                                        solicitud.predioId(), solicitud.fechaEmision())
                                .orElse(null);

        Instant ahora = reloj.instant();
        LicenciaDeFuncionamiento sinGuardar =
                new LicenciaDeFuncionamiento(
                        null,
                        numero,
                        titular.id(),
                        solicitud.predioId(),
                        fichaId,
                        solicitud.nombreComercial(),
                        solicitud.direccion(),
                        solicitud.areaSolicitada(),
                        solicitud.tipoLicencia(),
                        solicitud.zonificacion(),
                        solicitud.aforo(),
                        solicitud.fechaEmision(),
                        solicitud.vigenciaHasta(),
                        recibo.reciboId(),
                        // Se rellena abajo con el documento recien emitido: el papel se dibuja con
                        // los datos de la licencia, asi que la licencia tiene que existir como
                        // objeto antes que el, y el identificador del documento antes que la fila.
                        0L,
                        solicitud.expediente(),
                        solicitud.fechaExpediente(),
                        ahora,
                        null,
                        observacion,
                        giros,
                        TerritorioDeLaLicencia.de(comprobacion));

        EmitirDocumento.Emision emision =
                documentos.emitir(
                        TIPO_DE_DOCUMENTO,
                        ejercicio,
                        numero,
                        ModeloDeLaLicencia.de(
                                sinGuardar,
                                titular.nombre(),
                                titular.codigo(),
                                titular.documento(),
                                recibo.numero(),
                                giros),
                        formato,
                        observacion);

        long documentoId =
                Objects.requireNonNull(
                        emision.registro().id(),
                        "Un documento recien emitido siempre vuelve con su identificador");

        LicenciaDeFuncionamiento guardada = licencias.emitir(conDocumento(sinGuardar, documentoId));

        MovimientoDeLicencia emisionRegistrada =
                movimientos.registrar(
                        MovimientoDeLicencia.emision(
                                guardada.identificador(),
                                solicitud.fechaEmision(),
                                documentoId,
                                emision.registro().numero(),
                                ahora,
                                observacion));

        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                solicitud.fechaEmision(),
                                "licencia_funcionamiento",
                                String.valueOf(guardada.identificador()),
                                Operacion.ALTA,
                                observacion)
                        .con(null, descripcion(guardada, recibo, giros)));

        return new LicenciaEmitida(guardada, emisionRegistrada, emision, titular);
    }

    // ------------------------------------------------------------------

    /**
     * Los giros pedidos, resueltos contra el catalogo.
     *
     * <p>Se leen <b>todos de una vez</b> y se comprueban aqui, antes de escribir nada: un giro que
     * no existe tiene que producir «ese giro no esta en el catalogo» y no un fallo de clave foranea
     * a mitad de la insercion, que no dice cual de los tres era.
     */
    private List<GiroDeLaLicencia> resolverGiros(Solicitud solicitud) {
        Set<String> codigos = new LinkedHashSet<>();
        for (String codigo : solicitud.girosCiiu()) {
            codigos.add(codigo.strip().toUpperCase(java.util.Locale.ROOT));
        }
        if (!codigos.contains(solicitud.giroPrincipal())) {
            codigos.add(solicitud.giroPrincipal());
        }

        List<GiroDeLaLicencia> giros = new ArrayList<>(codigos.size());
        for (String codigo : codigos) {
            Ciiu giro = catalogo.porCodigo(codigo).orElseThrow(() -> new GiroDesconocido(codigo));
            if (!giro.activo()) {
                throw new GiroDesconocido(codigo);
            }
            giros.add(
                    new GiroDeLaLicencia(
                            giro.identificador(),
                            giro.codigo(),
                            giro.descripcion(),
                            codigo.equals(solicitud.giroPrincipal()),
                            true));
        }
        return giros;
    }

    private static LicenciaDeFuncionamiento conDocumento(
            LicenciaDeFuncionamiento licencia, long documentoId) {
        return new LicenciaDeFuncionamiento(
                licencia.id(),
                licencia.numero(),
                licencia.contribuyenteId(),
                licencia.predioId(),
                licencia.fichaId(),
                licencia.nombreComercial(),
                licencia.direccion(),
                licencia.areaSolicitada(),
                licencia.tipoLicencia(),
                licencia.zonificacion(),
                licencia.aforo(),
                licencia.fechaEmision(),
                licencia.vigenciaHasta(),
                licencia.reciboId(),
                documentoId,
                licencia.expediente(),
                licencia.fechaExpediente(),
                licencia.registradoEn(),
                licencia.usuarioRegistro(),
                licencia.observacion(),
                licencia.giros(),
                licencia.territorio());
    }

    /**
     * Se niega, exige que alguien lo asuma por escrito, o deja pasar (#43, AC-2, AC-4 y AC-5).
     *
     * <p>El orden importa y es este: <b>primero lo que no tiene salida</b>. Si el riesgo no
     * mitigable se comprobo, no se llega a mirar si hay autorizacion — porque no hay autorizacion
     * que valga, y decir «falta autorizar» mandaria a quien atiende a pedir una firma que nadie
     * puede dar.
     */
    private static void exigirQueElTerritorioLoPermita(
            Solicitud solicitud, ComprobacionDelTerritorio comprobacion, Ciiu principal) {

        if (comprobacion.riesgoNoMitigableComprobado()) {
            throw new RiesgoNoMitigable(solicitud.predioId(), comprobacion.aLaFecha());
        }

        if (comprobacion.todoComprobadoYFavorable()
                || comprobacion.zona() == RespuestaDelTerritorio.NO_SE_PREGUNTO) {
            return;
        }

        if (solicitud.autorizacionDelTerritorio() != null) {
            return;
        }

        throw new TerritorioSinAutorizar(
                solicitud.predioId(),
                principal.codigo(),
                comprobacion,
                queHayQueHacer(comprobacion));
    }

    /**
     * Que tiene que hacer quien atiende, segun cual de las tres cosas paso.
     *
     * <p>Las tres se arreglan de maneras distintas —dar de alta el predio o cargar el plano,
     * levantar el despliegue, revisar el indice de usos— y colapsarlas en «no se pudo comprobar el
     * territorio» manda a mirar donde no es. Es la distincion que {@code catastro} construyo a
     * proposito y que #9 transporto hasta aqui; borrarla en la ultima capa la desperdicia entera.
     */
    private static String queHayQueHacer(ComprobacionDelTerritorio comprobacion) {
        if (comprobacion.zona() == RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR
                || comprobacion.riesgo() == RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR
                || comprobacion.itse() == RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR) {
            return "No se pudo preguntar a `catastro`. Esto NO es «no hay riesgo»: es que no se"
                    + " sabe. Se arregla levantando el despliegue de `catastro`, y hasta entonces"
                    + " emitir exige que una persona lo asuma por escrito";
        }
        if (comprobacion.zona() == RespuestaDelTerritorio.NO_CONSTA
                || comprobacion.riesgo() == RespuestaDelTerritorio.NO_CONSTA) {
            return "El predio no consta en el territorio: no esta en el padron de `catastro`, no"
                    + " tiene poligono levantado, o ningun plan de zonificacion vigente lo cubre."
                    + " Hoy es el caso normal, porque no hay cartografia cargada. Se arregla dando"
                    + " de alta el predio, cargando el plano o aprobando la zonificacion";
        }
        if (comprobacion.compatibilidad() == CompatibilidadConLaZona.INCOMPATIBLE) {
            return "El giro principal no cabe en la zona "
                    + comprobacion.zonaDelTerritorio()
                    + " segun el indice de usos del catalogo CIIU. Se revisa el catalogo, o se"
                    + " autoriza por excepcion diciendo por que";
        }
        return "El giro principal no declara en que zonas cabe (`ciiu.zonificacion_compatible` esta"
                + " vacio, D-02b), asi que no hay con que decidir la compatibilidad. Se rellena el"
                + " catalogo, o se autoriza diciendo por que";
    }

    /** Sin datos personales: esto acaba en la columna JSON de la auditoria. */
    private static String descripcion(
            LicenciaDeFuncionamiento licencia,
            ReciboDeTramite recibo,
            List<GiroDeLaLicencia> giros) {
        return "{\"numero\":\""
                + licencia.numero()
                + "\",\"tipo\":\""
                + licencia.tipoLicencia()
                + "\",\"recibo\":\""
                + recibo.numero()
                + "\",\"giros\":"
                + giros.size()
                + ",\"fichaEconomica\":"
                + (licencia.fichaId() == null ? "null" : licencia.fichaId())
                + "}";
    }

    // ------------------------------------------------------------------

    /**
     * Lo que se pide para emitir una licencia.
     *
     * @param codigoContribuyente el titular, tal como lo teclea la pantalla
     * @param predioId el establecimiento; opcional
     * @param nombreComercial la denominacion comercial
     * @param direccion la direccion del establecimiento
     * @param areaSolicitada el area declarada
     * @param tipoLicencia definitiva, temporal o cesionaria
     * @param zonificacion la zona declarada
     * @param aforo el aforo autorizado
     * @param fechaEmision el dia de la emision; entra como argumento (regla 6)
     * @param vigenciaHasta hasta cuando rige
     * @param numeroDeRecibo el numero impreso del recibo del derecho, como esta en el papel
     * @param girosCiiu los codigos CIIU autorizados
     * @param giroPrincipal cual de ellos es la actividad principal
     * @param expediente el numero del expediente del tramite
     * @param fechaExpediente su fecha
     * @param autorizacionDelTerritorio por que se emite aunque el territorio no lo respalde; {@code
     *     null} cuando no hace falta. NO salva un riesgo no mitigable comprobado: eso no se
     *     autoriza. Es una {@link Observacion} y no una bandera a proposito — «lo autorizo» sin
     *     decir por que no es una autorizacion, es un permiso silencioso (regla 10)
     */
    public record Solicitud(
            String codigoContribuyente,
            @Nullable Long predioId,
            String nombreComercial,
            String direccion,
            AreaM2 areaSolicitada,
            TipoDeLicencia tipoLicencia,
            @Nullable String zonificacion,
            @Nullable Integer aforo,
            LocalDate fechaEmision,
            @Nullable LocalDate vigenciaHasta,
            String numeroDeRecibo,
            List<String> girosCiiu,
            String giroPrincipal,
            @Nullable String expediente,
            @Nullable LocalDate fechaExpediente,
            @Nullable Observacion autorizacionDelTerritorio) {

        public Solicitud {
            Objects.requireNonNull(codigoContribuyente, "La licencia es de un titular");
            Objects.requireNonNull(nombreComercial, "La licencia necesita su denominacion");
            Objects.requireNonNull(direccion, "La licencia necesita su direccion");
            Objects.requireNonNull(areaSolicitada, "La licencia necesita el area declarada");
            Objects.requireNonNull(tipoLicencia, "La licencia necesita su tipo");
            Objects.requireNonNull(fechaEmision, "La fecha entra como argumento (regla 6)");
            Objects.requireNonNull(numeroDeRecibo, "Sin recibo no hay licencia (RF-110)");
            Objects.requireNonNull(girosCiiu, "La lista de giros es vacia, no nula");
            Objects.requireNonNull(giroPrincipal, "Hay que decir cual es la actividad principal");
            girosCiiu = List.copyOf(girosCiiu);
            giroPrincipal = giroPrincipal.strip().toUpperCase(java.util.Locale.ROOT);
            if (giroPrincipal.isEmpty()) {
                throw new IllegalArgumentException(
                        "La actividad principal decide el riesgo de la ITSE: no puede faltar");
            }
        }
    }

    /**
     * La licencia recien emitida, su movimiento, su papel y su titular.
     *
     * @param licencia la fila guardada, con sus giros
     * @param emision el movimiento de emision
     * @param documento los bytes del papel y el registro que los respalda
     * @param titular el resumen del padron, para que el borde no lo tenga que releer
     */
    public record LicenciaEmitida(
            LicenciaDeFuncionamiento licencia,
            MovimientoDeLicencia emision,
            EmitirDocumento.Emision documento,
            ResumenDeContribuyente titular) {}

    /** El codigo de contribuyente no esta en el padron de esta municipalidad. */
    public static final class TitularDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        TitularDesconocido(String codigo) {
            super(
                    "No hay ningun contribuyente con codigo '"
                            + codigo
                            + "' en esta municipalidad: una licencia se emite a un titular del"
                            + " padron");
        }
    }

    /**
     * El lote cruza una zona de riesgo NO MITIGABLE, comprobada. No se emite, y no hay excepcion.
     *
     * <p>Es el unico desenlace de #43 sin salida, y el motivo es que el hecho esta <b>medido</b>:
     * {@code catastro} publica {@code hayRiesgoNoMitigable} derivado y arriba (`catastro`#5), no
     * recalculado aqui. Autorizar un local sobre un suelo asi es un dano que no se corrige con una
     * resolucion posterior — el local ya esta abierto—, asi que no se ofrece la autorizacion por
     * escrito que si se ofrece cuando algo <b>no se pudo comprobar</b>.
     */
    public static final class RiesgoNoMitigable extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        RiesgoNoMitigable(@Nullable Long predioId, LocalDate aLaFecha) {
            super(
                    "No se emite la licencia: el predio "
                            + predioId
                            + " cruzaba el "
                            + aLaFecha
                            + " una zona de riesgo NO MITIGABLE, segun la carta de peligro que"
                            + " `catastro` publica. No hay autorizacion que lo salve: un local"
                            + " abierto sobre ese suelo no se cierra con una resolucion posterior");
        }
    }

    /**
     * El territorio no respalda la emision y nadie la ha asumido por escrito.
     *
     * <p>Tres cosas distintas llegan aqui —no consta el predio, no se pudo preguntar, o el giro no
     * cabe en la zona— y el mensaje <b>dice cual</b> y que hacer con ella. Colapsarlas borraria la
     * distincion que {@code catastro} construyo a proposito (AC-5).
     */
    public static final class TerritorioSinAutorizar extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        private final transient ComprobacionDelTerritorio comprobacion;

        TerritorioSinAutorizar(
                @Nullable Long predioId,
                String giroPrincipal,
                ComprobacionDelTerritorio comprobacion,
                String queHacer) {
            super(
                    "No se emite la licencia del predio "
                            + predioId
                            + " con el giro principal "
                            + giroPrincipal
                            + " sin autorizacion expresa: "
                            + queHacer
                            + ". Lo que el territorio contesto al "
                            + comprobacion.aLaFecha()
                            + ": zona "
                            + comprobacion.zona()
                            + ", riesgo "
                            + comprobacion.riesgo()
                            + ", ITSE "
                            + comprobacion.itse()
                            + ", compatibilidad "
                            + comprobacion.compatibilidad());
            this.comprobacion = comprobacion;
        }

        /** Lo que se pudo comprobar, para que el borde lo publique sin analizar el mensaje. */
        public ComprobacionDelTerritorio comprobacion() {
            return comprobacion;
        }
    }

    /** Ese giro no esta en el catalogo CIIU, o esta dado de baja. */
    public static final class GiroDesconocido extends RuntimeException {

        @java.io.Serial private static final long serialVersionUID = 1L;

        GiroDesconocido(String codigo) {
            super(
                    "El giro '"
                            + codigo
                            + "' no esta activo en el catalogo CIIU de esta municipalidad. El"
                            + " catalogo se mantiene en la opcion `ciiu` y es extensible (RF-112)");
        }
    }
}

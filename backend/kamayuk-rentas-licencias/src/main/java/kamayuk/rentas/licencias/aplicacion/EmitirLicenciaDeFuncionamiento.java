package kamayuk.rentas.licencias.aplicacion;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import kamayuk.rentas.catastro.LectorDeFichasEconomicas;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.documentos.EmitirDocumento;
import kamayuk.rentas.documentos.FormatoDeDocumento;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.licencias.dominio.Ciiu;
import kamayuk.rentas.licencias.dominio.CompatibilidadConLaZona;
import kamayuk.rentas.licencias.dominio.ComprobacionDelTerritorio;
import kamayuk.rentas.licencias.dominio.LicenciaDeFuncionamiento;
import kamayuk.rentas.licencias.dominio.MovimientoDeLicencia;
import kamayuk.rentas.licencias.dominio.RespuestaDelTerritorio;
import kamayuk.rentas.licencias.dominio.TipoDeLicencia;
import kamayuk.rentas.tesoreria.ReciboDeTramite;
import kamayuk.rentas.tesoreria.RecibosDeTramite;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

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
 *   <li><b>Las dos juntas</b> (#383): que el recibo no haya pagado ya otro acto. Lo anota {@link
 *       GastoDelDerecho} en {@code recibo_aplicado}, y dos emisiones simultaneas con el mismo papel
 *       las separa {@code recibo_aplicado_uq}, no un {@code if}.
 * </ul>
 *
 * <h2>La licencia y su papel nacen juntos</h2>
 *
 * <p>El documento se emite en la <b>misma transaccion</b> que la fila, con {@link EmitirDocumento}:
 * la de {@link RegistrarLicenciaDeFuncionamiento}, que tiene el motivo entero.
 *
 * <h2>Esta clase pregunta; la que escribe es otra (#450)</h2>
 *
 * <p>Hasta #450 este metodo era {@code @Transactional} entero y dentro hacia seis viajes de red
 * —{@code normativa}, {@code caja} y cuatro a {@code catastro}— con la conexion de la peticion
 * tomada. {@link ComprobarElTerritorio} lo decia al reves: «no abre transaccion», y era cierto,
 * pero su unico llamador ya la tenia abierta. Y la ficha economica salia <b>despues</b> de numerar,
 * con el candado de {@code licencia_correlativo} tomado: con {@code catastro} lento, la ventanilla
 * de la municipalidad entera se ponia en fila detras de el.
 *
 * <p>Ahora el reparto es el de {@link ConsultaDeFue} y {@link ResumenAnualDeLicencias}, por otro
 * motivo —alli era el <i>rollback-only</i>, aqui el pool—: esta clase <b>no abre transaccion</b>,
 * pregunta a los vecinos y reune lo que contestan en una {@link
 * RegistrarLicenciaDeFuncionamiento.EmisionComprobada}; {@link RegistrarLicenciaDeFuncionamiento}
 * la recibe y solo escribe. <b>Anadir aqui una lectura suelta de un repositorio la rompe</b>, y no
 * devolviendo vacio: sin transaccion no hay {@code SET LOCAL} y la politica RLS no se puede evaluar
 * (#486). Las lecturas de la base van a {@link GirosDeLaSolicitud} o al escritor.
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
 *   <li><b>El giro exige la ITSE previa y no hay ningun certificado vigente ⇒ la misma autorizacion
 *       explicita</b> (#416). El TUPA tramita la licencia de riesgo ALTO y MUY ALTO «Con ITSE
 *       previa». No se rechaza en seco porque rechazarla es una decision que nadie ha escrito
 *       todavia; lo que no puede seguir es que salga sin que nadie lo asuma, y sin rastro.
 *   <li><b>Alguna consulta no contesto ⇒ hace falta la misma autorizacion explicita.</b> «No se
 *       pudo preguntar» NO es «no hay riesgo» (AC-4) — y eso vale tambien para el ITSE solo, que
 *       hasta #416 no contaba. Y no se rechaza en seco por una razon medida: hoy <b>no hay ni un
 *       poligono cargado en ninguna instalacion</b>, asi que rechazar dejaria el modulo de
 *       licencias sin poder emitir una sola — y un sistema que no se puede usar se acaba
 *       desactivando, que es como se pierden las guardas.
 *   <li><b>Todo comprobado y favorable ⇒ se emite</b> sin pedirle nada mas a nadie.
 * </ul>
 *
 * <p>Las razones por las que puede hacer falta la autorizacion <b>se distinguen en el mensaje</b> y
 * llegan a quien opera como cosas distintas (AC-5): no consta el predio, no se pudo preguntar, el
 * giro no cabe, o falta la ITSE previa (#416). Se arreglan de maneras distintas —dar de alta el
 * predio o cargar el plano, levantar el despliegue, revisar el indice de usos, obtener el
 * certificado— y decir la equivocada manda a quien atiende a buscar donde no es.
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

    private final DirectorioDeContribuyentes contribuyentes;
    private final DerechosDeTramiteParametrizados derechos;
    private final RecibosDeTramite recibos;
    private final GirosDeLaSolicitud giros;
    private final ComprobarElTerritorio territorio;
    private final LectorDeFichasEconomicas fichas;
    private final RegistrarLicenciaDeFuncionamiento registro;

    public EmitirLicenciaDeFuncionamiento(
            DirectorioDeContribuyentes contribuyentes,
            DerechosDeTramiteParametrizados derechos,
            RecibosDeTramite recibos,
            GirosDeLaSolicitud giros,
            ComprobarElTerritorio territorio,
            LectorDeFichasEconomicas fichas,
            RegistrarLicenciaDeFuncionamiento registro) {
        this.contribuyentes = contribuyentes;
        this.derechos = derechos;
        this.recibos = recibos;
        this.giros = giros;
        this.territorio = territorio;
        this.fichas = fichas;
        this.registro = registro;
    }

    /**
     * Emite la licencia.
     *
     * <p><b>Sin {@code @Transactional}, y hace falta que no lo tenga</b> (#450): aqui se pregunta a
     * los vecinos, y cada pregunta con una transaccion abierta es una conexion del pool esperando a
     * la red. Las lecturas de esta base traen la suya —el padron y {@link GirosDeLaSolicitud}—, y
     * la escritura la abre {@link RegistrarLicenciaDeFuncionamiento}, que recibe lo comprobado y
     * solo escribe. Por eso la {@link Observacion} viaja hasta alli: la regla 10 la busca en los
     * parametros del metodo transaccional.
     *
     * @throws TitularDesconocido si el codigo de contribuyente no esta en el padron
     * @throws GiroDesconocido si algun giro no esta en el catalogo CIIU
     * @throws ComprobacionDelDerecho.DerechoNoPagado si el recibo no respalda el derecho (RF-110)
     * @throws kamayuk.rentas.tesoreria.ReciboYaAplicado si el recibo ya pago otro acto (#383)
     * @throws DerechosDeTramiteParametrizados.DerechoSinParametrizar si el conjunto sellado no dice
     *     que concepto del TUPA cobra el derecho
     * @throws RiesgoNoMitigable si el lote cruza una zona de riesgo no mitigable comprobada (#43)
     * @throws TerritorioSinAutorizar si algo del territorio no se pudo comprobar —o el giro no cabe
     *     en la zona, o exige la ITSE previa y no hay ningun certificado vigente (#416)— y la
     *     solicitud no trae la autorizacion explicita que lo asume
     */
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

        GirosDeLaSolicitud.Resueltos resueltos = giros.resolver(solicitud);

        // EL TERRITORIO SE PREGUNTA AQUI, antes de numerar y antes de dibujar el papel. Despues
        // seria descubrirlo con el documento ya emitido y su huella ya calculada, y una licencia
        // no se edita (V37): corregirla es otro acto.
        //
        // La zona compatible que decide es la del giro PRINCIPAL, y no la union de los giros: lo
        // dice `LicenciaDeFuncionamiento` con todas las letras —«la actividad principal es la que
        // decide el riesgo de la ITSE y la compatibilidad con la zonificacion»— y tomar la union
        // dejaria que un giro secundario compatible autorizara al principal que no lo es.
        ComprobacionDelTerritorio comprobacion =
                territorio.de(
                        solicitud.predioId(),
                        solicitud.fechaEmision(),
                        resueltos.principal().zonificacionCompatible(),
                        resueltos.principal().riesgoItse());
        exigirQueElTerritorioLoPermita(solicitud, comprobacion, resueltos.principal());

        // La ficha economica se pide a `catastro` por su puerto publico, con la fecha de emision:
        // la licencia queda enlazada a la version que regia ese dia, no a «la ultima» (regla 9).
        //
        // Y se pide AQUI, antes de numerar (#450). Hasta #450 salia despues de
        // `siguienteCorrelativo`, con la fila de `licencia_correlativo` bloqueada hasta el commit:
        // una emision esperaba el candado mientras otra esperaba a `catastro`.
        Long fichaId =
                solicitud.predioId() == null
                        ? null
                        : fichas.fichaEconomicaVigenteEn(
                                        solicitud.predioId(), solicitud.fechaEmision())
                                .orElse(null);

        return registro.registrar(
                solicitud,
                new RegistrarLicenciaDeFuncionamiento.EmisionComprobada(
                        titular, resueltos.giros(), concepto, recibo, comprobacion, fichaId),
                formato,
                observacion);
    }

    // ------------------------------------------------------------------

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

        if (!comprobacion.exigeAutorizacion() || solicitud.autorizacionDelTerritorio() != null) {
            return;
        }

        throw new TerritorioSinAutorizar(
                solicitud.predioId(),
                principal.codigo(),
                comprobacion,
                queHayQueHacer(comprobacion));
    }

    /**
     * Que tiene que hacer quien atiende, segun cual de las cosas paso.
     *
     * <p>Se arreglan de maneras distintas —dar de alta el predio o cargar el plano, levantar el
     * despliegue, revisar el indice de usos, obtener el certificado ITSE (#416)— y colapsarlas en
     * «no se pudo comprobar el territorio» manda a mirar donde no es. Es la distincion que {@code
     * catastro} construyo a proposito y que #9 transporto hasta aqui; borrarla en la ultima capa la
     * desperdicia entera.
     */
    private static String queHayQueHacer(ComprobacionDelTerritorio comprobacion) {
        if (comprobacion.zona() == RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR
                || comprobacion.riesgo() == RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR
                || comprobacion.itse() == RespuestaDelTerritorio.NO_SE_PUDO_PREGUNTAR) {
            return "No se pudo preguntar a `catastro`. Esto NO es «no hay riesgo»: es que no se"
                    + " sabe. Se arregla levantando el despliegue de `catastro`, y hasta entonces"
                    + " emitir exige que una persona lo asuma por escrito";
        }
        // El ITSE entra en las dos primeras desde #416: hasta entonces un ITSE caido o que no
        // constaba no llegaba aqui solo, y cuando por fin llega tiene que decir lo que es y no caer
        // en la ultima frase, que manda a revisar el catalogo CIIU (AC-5).
        if (comprobacion.zona() == RespuestaDelTerritorio.NO_CONSTA
                || comprobacion.riesgo() == RespuestaDelTerritorio.NO_CONSTA
                || comprobacion.itse() == RespuestaDelTerritorio.NO_CONSTA) {
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
        if (comprobacion.faltaLaItsePrevia()) {
            return "El giro principal es de riesgo ITSE "
                    + comprobacion.riesgoItseDelGiro()
                    + " y el predio no tenia ningun certificado ITSE vigente ese dia: el TUPA"
                    + " tramita esa licencia «Con ITSE previa». Se arregla obteniendo el"
                    + " certificado antes de emitir, o se autoriza por escrito diciendo por que";
        }
        return "El giro principal no declara en que zonas cabe (`ciiu.zonificacion_compatible` esta"
                + " vacio, D-02b), asi que no hay con que decidir la compatibilidad. Se rellena el"
                + " catalogo, o se autoriza diciendo por que";
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
     * <p>Cuatro cosas distintas llegan aqui —no consta el predio, no se pudo preguntar, el giro no
     * cabe en la zona, o falta la ITSE previa (#416)— y el mensaje <b>dice cual</b> y que hacer con
     * ella. Colapsarlas borraria la distincion que {@code catastro} construyo a proposito (AC-5).
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

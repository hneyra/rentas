package kamayuk.rentas.verificaciones;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.comun.verificaciones.ConfiguracionDeLasVerificaciones;

/**
 * Lo que `rentas` declara de si mismo a las barreras de {@code comun-verificaciones}.
 *
 * <p>La descubre {@link java.util.ServiceLoader}: el descriptor esta en {@code
 * src/test/resources/META-INF/services/}. Si se borra, las barreras <b>no corren en silencio</b> —
 * fallan nombrando lo que falta, que es lo que este mecanismo compra frente a pasar la
 * configuracion por constructor.
 *
 * <p>Hoy este repositorio no tiene ni una clase de negocio, y lo declara: {@link
 * #sinCodigoDeProduccionTodavia()}. <b>Es una exencion que caduca sola</b> — las barreras exigen que
 * en efecto no haya NADA, asi que la primera clase que llegue las pone en rojo pidiendo que se
 * retire la linea.
 */
public final class ConfiguracionDeRentas implements ConfiguracionDeLasVerificaciones {

    /**
     * El reparto de tablas de GOB-05 §2, ENTERO y no solo el de este sistema.
     *
     * <p>La regla {@code NINGUN_SQL_CRUZA_LA_FRONTERA_DE_rentas} necesita saber de quien es la
     * tabla ajena para poder decir a que frontera pertenece el cruce; con solo las propias, una
     * consulta a {@code predio} desde {@code caja} seria «una tabla que nadie repartio» y pasaria
     * sin ruido.
     *
     * <p>Las transversales y las de seguridad se replican en los cuatro (§2.5 y §2.6), y por eso
     * van con {@link #SISTEMA_REPLICADO}: leerlas nunca es cruzar nada.
     */
    private static final Set<String> DE_RENTAS =
            Set.of(
                    "acta_fiscalizacion",
                    "acto_coactivo",
                    "anuncio",
                    "anuncio_correlativo",
                    "anuncio_movimiento",
                    "beneficio",
                    "certificado",
                    "certificado_correlativo",
                    "ciiu",
                    "codigo_infraccion",
                    "constancia_libre",
                    "contacto",
                    "contribuyente",
                    "convenio",
                    "convenio_correlativo",
                    "convenio_cuota",
                    "convenio_deuda",
                    "convenio_movimiento",
                    "corrida_predial",
                    "corrida_predial_observado",
                    "costa_obligacion",
                    "costa_procesal",
                    "cuenta_corriente_asiento",
                    "cuenta_corriente_asiento_2026",
                    "cuenta_corriente_asiento_2027",
                    "declaracion_jurada",
                    "descargo",
                    "determinacion",
                    "determinacion_2026",
                    "determinacion_2027",
                    "determinacion_arbitrio",
                    "determinacion_arbitrio_2026",
                    "determinacion_arbitrio_2027",
                    "determinacion_predio_detalle",
                    "determinacion_predio_detalle_2026",
                    "determinacion_predio_detalle_2027",
                    "dj_correlativo",
                    "domicilio",
                    "edificacion_correlativo",
                    "edificacion_estructura",
                    "edificacion_movimiento",
                    "edificacion_profesional",
                    "edificacion_proyecto",
                    "edificacion_requisito",
                    "edificacion_terreno",
                    "edificacion_vigencia",
                    "espectaculo",
                    "expediente_coactivo",
                    "expediente_correlativo",
                    "expediente_movimiento",
                    "expediente_valor",
                    "internamiento",
                    "internamiento_movimiento",
                    "licencia_correlativo",
                    "licencia_duplicado",
                    "licencia_edificacion",
                    "licencia_funcionamiento",
                    "licencia_giro",
                    "licencia_movimiento",
                    "liquidacion_correlativo",
                    "liquidacion_costas",
                    "liquidacion_costas_correlativo",
                    "liquidacion_detalle",
                    "liquidacion_fiscalizacion",
                    "liquidacion_movimiento",
                    "notificacion",
                    "notificacion_administrativa",
                    "papeleta",
                    "papeleta_cambio_numero",
                    "papeleta_masivo",
                    "papeleta_masivo_item",
                    "prescripcion",
                    "prescripcion_ejercicio",
                    "prescripcion_hecho",
                    "programa_fiscalizacion",
                    "programa_muestra",
                    "resolucion_determinacion",
                    "resolucion_gerencia",
                    "responsable_solidario",
                    "saldo_proyectado",
                    "transferencia",
                    "valor",
                    "valor_correlativo",
                    "valor_detalle",
                    "valor_masivo",
                    "valor_masivo_item",
                    "valor_movimiento",
                    "vehiculo");

    private static final Set<String> DE_CATASTRO =
            Set.of(
                    "actividad_economica",
                    "arancel",
                    "bien_comun",
                    "colindante_rural",
                    "construccion",
                    "ficha_catastral",
                    "inquilino",
                    "manzana",
                    "otra_instalacion",
                    "participacion_comun",
                    "predio",
                    "sector",
                    "tierra_rural",
                    "titularidad",
                    "via");

    private static final Set<String> DE_NORMATIVA =
            Set.of(
                    "conjunto_parametro_detalle",
                    "conjunto_parametros",
                    "depreciacion",
                    "parametro_tributario",
                    "valor_referencial_vehiculo",
                    "valor_unitario_edificacion");

    private static final Set<String> DE_CAJA =
            Set.of(
                    "area",
                    "caja",
                    "cierre_caja",
                    "cierre_turno",
                    "cierre_turno_detalle",
                    "recibo",
                    "recibo_correlativo",
                    "recibo_detalle",
                    "recibo_movimiento",
                    "tasa");

    private static final Set<String> REPLICADAS =
            Set.of(
                    "acceso",
                    "auditoria",
                    "auditoria_2026",
                    "auditoria_2027",
                    "documento_emitido",
                    "grupo",
                    "miembro",
                    "modulo_sistema",
                    "municipalidad",
                    "permiso",
                    "respaldo",
                    "sesion",
                    "usuario");

    @Override
    public String paqueteRaiz() {
        return "kamayuk.rentas";
    }

    @Override
    public String sistema() {
        return "rentas";
    }

    @Override
    public Map<String, String> sistemaDeCadaTabla() {
        Map<String, String> reparto = new HashMap<>();
        DE_RENTAS.forEach(t -> reparto.put(t, "rentas"));
        DE_CATASTRO.forEach(t -> reparto.put(t, "catastro"));
        DE_NORMATIVA.forEach(t -> reparto.put(t, "normativa"));
        DE_CAJA.forEach(t -> reparto.put(t, "caja"));
        REPLICADAS.forEach(t -> reparto.put(t, SISTEMA_REPLICADO));
        return Map.copyOf(reparto);
    }

    /**
     * Vacia, y tiene que estarlo: sin codigo no puede haber ningun cruce que consentir.
     *
     * <p>{@code FronteraDeSistemaTest} lo comprueba. Cuando P5 traiga las clases del monolito, los
     * cruces que le tocan a este sistema entran aqui con su issue —los de {@code sgtm} estan en
     * {@code CrucesConsentidosDelSgtm}, con quien los cierra—, y en P5E esta lista tiene que volver
     * a quedar vacia.
     */
    @Override
    public List<CruceConsentido> crucesConsentidos() {
        return List.of();
    }

    /**
     * Vacias hasta que llegue el baseline (ADR-0032).
     *
     * <p>Declarar aqui una tabla que este sistema todavia no tiene seria peor que no declararla: la
     * lista dejaria de leerse como el inventario de lo que hay que cuidar. Las de este sistema
     * salen de GOB-05 §2 y entran con su migracion.
     */
    @Override
    public Set<String> tablasProtegidas() {
        return Set.of();
    }

    @Override
    public Set<String> tablasInmutables() {
        return Set.of();
    }

    @Override
    public Set<String> componenElAreaAManoConMotivo() {
        return Set.of();
    }

    /**
     * El unico paquete que hoy tiene clases: el del migrador.
     *
     * <p>No es una formalidad. Sin nombrarlo, «hay clases que revisar» se conforma con que haya
     * <b>algo</b>, y el dia que {@code kamayuk-verificaciones} dejara de depender de {@code
     * kamayuk-esquema} —una linea del {@code build.gradle.kts}— ArchUnit no veria nada y las
     * dieciocho reglas pasarian en verde sin haber mirado un solo byte.
     */
    @Override
    public Set<String> paquetesQueTienenQueExistir() {
        return Set.of("kamayuk.rentas.esquema");
    }

    /** Todavia no hay ni un contexto acotado. Caduca sola: ver el javadoc de la clase. */
    @Override
    public boolean sinContextosAcotadosTodavia() {
        return true;
    }

    /**
     * Dos: el migrador y {@code crear-roles.sql}. Es todo el {@code src/main} que hay hoy.
     *
     * <p>El minimo existe para que el escaner no pase sin revisar nada, asi que tiene que ser el
     * numero de verdad y no un cero: si manana desaparecieran esos dos archivos, el escaner lo
     * diria en vez de quedarse en verde.
     */
    @Override
    public int minimoDeFuentesDeProduccion() {
        return 2;
    }

    /** Las cinco de este modulo mas la de aislamiento. Mismo motivo que el minimo de arriba. */
    @Override
    public int minimoDePruebas() {
        return 6;
    }

    /**
     * Los dos ambitos que solo existen en {@code rentas}, declarados ausentes.
     *
     * <p>Sin esto, las dos reglas acotadas a ellos —la frontera de {@code fiscalizacion} y el panel
     * de recaudacion— correrian con {@code allowEmptyShould(true)} y nadie miraria. La declaracion
     * NO las apaga: {@code ArquitecturaTestBase} exige que el ambito declarado ausente lo este de
     * verdad, asi que el dia que aparezca una clase suya la prueba se pone roja.
     */
    @Override
    public Set<String> ambitosAusentes() {
        return Set.of("fiscalizacion", "indicadores");
    }
}

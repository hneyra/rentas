package kamayuk.rentas.verificaciones;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import kamayuk.comun.verificaciones.ConfiguracionDeLasVerificaciones;

/**
 * Lo que {@code sgtm} declara de si mismo a las barreras de {@code comun-verificaciones}.
 *
 * <p>La descubre {@link java.util.ServiceLoader}: el descriptor esta en {@code
 * src/test/resources/META-INF/services/}. Si se borra, las barreras no corren en silencio — fallan
 * nombrando lo que falta, que es lo que este mecanismo compra frente a pasar la configuracion por
 * constructor.
 *
 * <h2>Por que aqui el sistema depende del ARCHIVO y no del repositorio</h2>
 *
 * <p>{@code sgtm} es el monolito: los cuatro sistemas futuros conviven en el, y sus 132 tablas
 * estan en la misma base. Declarar «este repositorio es rentas» acusaria a {@code
 * kamayuk-rentas-catastro} de leer sus propias tablas; declarar «es catastro» dejaria pasar todo lo
 * demas. Lo que hay es un reparto por modulo Gradle —GOB-05 §1— y eso es {@link
 * #sistemaDelArchivo(String)}.
 *
 * <p>Es lo que hace que {@code NINGUN_SQL_CRUZA_LA_FRONTERA_DE_SISTEMA} sirva <b>antes</b> del
 * corte: encuentra los cruces de GOB-05 §6 hoy, con todo junto y funcionando, que es la unica
 * ventana en la que arreglarlos cuesta barato.
 */
public final class ConfiguracionDelSgtm implements ConfiguracionDeLasVerificaciones {

    /**
     * El reparto por modulo Gradle (GOB-05 §1).
     *
     * <p>Los cinco que no son contextos acotados —{@code dominio-compartido}, {@code esquema},
     * {@code plataforma}, {@code seguridad} y {@code aplicacion}— van a {@link #SISTEMA_REPLICADO}:
     * no estan a ningun lado de la frontera, asi que no pueden cruzarla. {@code
     * kamayuk-rentas-esquema} entra ahi por el mismo motivo y por uno mas: sus migraciones crean
     * las tablas de los cuatro, y ADR-0032 §1 dice que no se reparten sino que se rehacen como un
     * baseline por sistema.
     */
    private static final Map<String, String> SISTEMA_DEL_MODULO =
            Map.ofEntries(
                    Map.entry("kamayuk-rentas-contribuyentes", "rentas"),
                    Map.entry("kamayuk-rentas-catastro", "catastro"),
                    Map.entry("kamayuk-rentas-rentas", "rentas"),
                    Map.entry("kamayuk-rentas-parametros", "normativa"),
                    Map.entry("kamayuk-rentas-fiscalizacion", "rentas"),
                    Map.entry("kamayuk-rentas-sanciones", "rentas"),
                    Map.entry("kamayuk-rentas-cuentacorriente", "rentas"),
                    // Se PARTIO en P5D, y lo que queda aqui es `rentas`. Hasta entonces se
                    // declaraba `caja` —era la mayoria: 84 clases contra 33— y las 33 del convenio
                    // se nombraban una a una en CLASES_QUE_NO_SIGUEN_A_SU_MODULO. `V7` retiro las
                    // diez tablas del dinero y con ellas las 84 clases; lo que se quedo es el
                    // convenio de fraccionamiento —deuda reprogramada, ADR-0026 §5— mas el
                    // adaptador cliente de `caja`, que no tiene una sola consulta.
                    //
                    // El reparto se invierte entero, y por eso la lista de excepciones se vacia.
                    Map.entry("kamayuk-rentas-tesoreria", "rentas"),
                    Map.entry("kamayuk-rentas-valores", "rentas"),
                    Map.entry("kamayuk-rentas-coactiva", "rentas"),
                    Map.entry("kamayuk-rentas-licencias", "rentas"),
                    Map.entry("kamayuk-rentas-indicadores", "rentas"),
                    Map.entry("kamayuk-rentas-dominio-compartido", SISTEMA_REPLICADO),
                    Map.entry("kamayuk-rentas-esquema", SISTEMA_REPLICADO),
                    Map.entry("kamayuk-rentas-plataforma", SISTEMA_REPLICADO),
                    Map.entry("kamayuk-rentas-seguridad", SISTEMA_REPLICADO),
                    Map.entry("kamayuk-rentas-aplicacion", SISTEMA_REPLICADO));

    /**
     * Las clases que no siguen al sistema de su modulo. <b>Desde P5D no hay ninguna, y esa es la
     * noticia.</b>
     *
     * <p>Hasta P5D tenia dos entradas —{@code ConvenioRepositoryJdbc} y {@code
     * MovimientoDeConvenioRepositoryJdbc}—, porque {@code kamayuk-rentas-tesoreria} se declaraba
     * {@code caja} y sus dos repositorios del convenio se iban a {@code rentas}. La lista existia
     * para que no salieran acusados de leer {@code contribuyente}, que no es un cruce: el convenio
     * y el padron van los dos a {@code rentas}.
     *
     * <p>`V7` retiro las diez tablas del dinero y el modulo pasa a declararse {@code rentas}
     * entero, asi que los dos repositorios ya coinciden con su modulo y la excepcion sobra. <b>Se
     * conserva vacia y no se borra el mecanismo</b>: es el unico sitio donde se puede declarar que
     * una clase concreta pertenece a otro sistema que su modulo, y esa situacion vuelve cada vez
     * que un modulo se parte —le paso a {@code tesoreria} y le puede pasar al siguiente—. Una lista
     * vacia dice «hoy no hay ninguna», que es una afirmacion; no tener el mecanismo diria que el
     * caso no existe.
     */
    private static final Map<String, String> CLASES_QUE_NO_SIGUEN_A_SU_MODULO = Map.of();

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

    /**
     * Las diez tablas de {@code caja}. <b>Se conservan aunque `V7` las quitara de esta base</b>, y
     * conviene decir por que.
     *
     * <p>Este censo no dice «que tablas hay aqui» sino <b>de que sistema es cada tabla</b>: es lo
     * que {@code NINGUN_SQL_CRUZA_LA_FRONTERA_DE_SISTEMA} consulta para decidir si un {@code
     * SELECT} cruza. Vaciarlo dejaria que una consulta nueva contra {@code recibo} o {@code caja}
     * pasara sin ruido —una tabla que nadie repartio no es un cruce, y esa es la salida por omision
     * del escaner (#437)—, justo el dia en que ya no existe y el fallo seria en produccion.
     *
     * <p>Es el mismo criterio con que P5C dejo {@link #DE_CATASTRO} y P5B {@link #DE_NORMATIVA}
     * despues de que sus tablas se fueran: <b>el reparto es del corte entero, no del esquema de
     * hoy</b>.
     */
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

    /** Transversales (§2.5) y las siete de seguridad (§2.6): se replican en los cuatro. */
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

    /**
     * No hay uno solo: {@code sgtm} es el monolito.
     *
     * <p>Se devuelve {@code rentas} porque es el que se lleva 88 de las 132 tablas, pero lo que de
     * verdad decide es {@link #sistemaDelArchivo(String)}. Este valor solo se usa si alguien lo
     * pregunta sin dar un archivo, y ahi la respuesta menos equivocada es la mayoritaria.
     */
    @Override
    public String sistema() {
        return "rentas";
    }

    /**
     * La raiz de la API de este sistema tras el corte (ADR-0030): {@code /rentas/api/v1}.
     *
     * <p>Es {@link kamayuk.rentas.web.Api#RAIZ}, y se deriva de ahi en vez de repetirse: el camino
     * base vive en un solo sitio, y la regla del centinela del ciudadano compara contra el mismo
     * que publican los controladores.
     */
    @Override
    public String raizDeLaApi() {
        return kamayuk.rentas.web.Api.RAIZ;
    }

    @Override
    public String sistemaDelArchivo(String rutaRelativa) {
        String normalizada = rutaRelativa.replace('\\', '/');
        String clase = claseDe(normalizada);
        String porClase = CLASES_QUE_NO_SIGUEN_A_SU_MODULO.get(clase);
        if (porClase != null) {
            return porClase;
        }
        int barra = normalizada.indexOf('/');
        String modulo = barra < 0 ? normalizada : normalizada.substring(0, barra);
        return SISTEMA_DEL_MODULO.getOrDefault(modulo, SISTEMA_REPLICADO);
    }

    private static String claseDe(String ruta) {
        int barra = ruta.lastIndexOf('/');
        String nombre = barra < 0 ? ruta : ruta.substring(barra + 1);
        int punto = nombre.lastIndexOf('.');
        return punto < 0 ? nombre : nombre.substring(0, punto);
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

    @Override
    public List<CruceConsentido> crucesConsentidos() {
        return CrucesConsentidosDelSgtm.LISTA;
    }

    @Override
    public Set<String> tablasProtegidas() {
        return TablasDelSgtm.PROTEGIDAS;
    }

    @Override
    public Set<String> tablasInmutables() {
        return TablasDelSgtm.INMUTABLES;
    }

    @Override
    public Set<String> componenElAreaAManoConMotivo() {
        return Set.of(
                // Los cuatro modelos de documento: la unidad va en el rotulo.
                "ModeloDelFue",
                "ModeloDeLaLicencia",
                "ModeloDeLaResolucionDeDeterminacion",
                "ModeloDeLaFichaDelContribuyente",
                // Las dos descripciones que van a la columna JSON de la auditoria. El motivo no es
                // «no llega al cliente» —`GET /seguridad/auditoria` las publica verbatim— sino que
                // ahi el area no es un campo tipado sino una instantanea de texto libre, y se
                // escribe SIN la unidad para que diga lo mismo que el resto (#607).
                "RegistrarAnuncio",
                "ActualizarFichaCatastral");
    }

    @Override
    public Set<String> paquetesQueTienenQueExistir() {
        return Set.of(
                "kamayuk.rentas.compartido",
                "kamayuk.rentas.plataforma.tenant",
                "kamayuk.rentas.dominio");
    }

    @Override
    public Set<String> tiposAjenosQueFiscalizacionSoloLee() {
        return Set.of(
                // La ficha que sustenta un acta y la que sustenta una declaracion (#45, #49).
                // Devuelven identificador y area: ni un metodo que escriba.
                ".catastro.LectorDeFichas",
                // El uso y las caracteristicas del predio a una fecha (#49).
                ".catastro.LectorDeCaracteristicas",
                ".catastro.CaracteristicasDelPredio",
                // Quien es titular de un predio a una fecha, por lote, para poner el nombre en la
                // fila de omisos (#545).
                ".catastro.TitularesDelPredio",
                ".catastro.TitularDelPredio",
                // Lo que la transferencia DEVOLVIO. Es un registro de resultado, no una puerta: no
                // tiene un metodo que escriba, y lo lee tambien quien dibuja el papel.
                ".catastro.VersionTransferida",
                // Y su excepcion: atraparla no es escribir. La captura la capa web, que traduce a
                // 422 «el predio no tiene ficha vigente».
                ".catastro.TransferenciaDeFiscalizacion$SinFichaQueVersionar",
                // Si un predio declaro en un ejercicio, por lote (RF-055).
                ".rentas.DeclaracionesDelEjercicio",
                ".rentas.DeclaracionDelEjercicio",
                // Cuanto se debe a una fecha, para el estado de cuenta de fiscalizacion (RF-056).
                // Arista al reves de las otras: la excepcion de ARQ-01 §4 regla 2.
                ".cuentacorriente.ConsultaDeDeudaPublica",
                ".cuentacorriente.ObligacionPublica",
                // Como se llaman los tributos del libro (#553). Es un enumerado: lo que aporta es
                // que fiscalizacion no declare su propio literal.
                ".cuentacorriente.TributoDelLibro");
    }

    @Override
    public Set<String> escriturasSinUsuarioQueObserve() {
        return Set.of(
                // Reconstruye saldo_proyectado desde el libro (#23). Es un cache derivado: no
                // modifica ningun dato, lo recalcula. El libro no se toca.
                ".cuentacorriente.aplicacion.ReconstruirSaldo.deContribuyente(long)",
                // La lista de predios SIN declaracion jurada (ADR-0015 §2.3, #344). Es una
                // CONSULTA.
                // Lo unico que escribe es su propia fila de ACCESO, y esa observacion no la puede
                // dar el usuario porque nadie escribe un motivo para mirar una grilla.
                ".rentas.aplicacion.ConsultaDeConciliacion.noConciliadas("
                        + "kamayuk.rentas.catastro.BusquedaDeFichas, kamayuk.rentas.dominio.Ejercicio,"
                        + " java.time.LocalDate, kamayuk.rentas.compartido.Paginacion)",
                // El titular de un predio, resuelto al clic (ADR-0015 §2.4, #366). Misma forma.
                ".rentas.aplicacion.ConsultaDeTitulares.resolver(long, java.time.LocalDate)",
                // La rama del portal del contribuyente (ADR-0020, #57). Misma forma y un motivo
                // mas fuerte: aqui el usuario ni siquiera es un funcionario.
                ".rentas.aplicacion.RamaDelCiudadano.leer(java.time.LocalDate)",
                // La descarga del conjunto sellado de `normativa` (P5B, ADR-0025 §1). Es el caso
                // mas claro de la lista: lo que escribe es una COPIA de un dato que este sistema no
                // produjo, ya sellado en el otro y verificado por su sha256. No hay ningun usuario
                // que la pida —la dispara la primera lectura que necesita el conjunto— y no hay
                // ningun «por que» que dar: la copia es identica a la fuente por construccion, y si
                // no lo fuera no se guardaria. Exigir una observacion aqui produciria la cadena
                // fija que el javadoc de la regla advierte.
                ".parametros.aplicacion.DescargaDeNormativa.asegurarDescargado("
                        + "long, java.lang.String)");
    }
}

package kamayuk.rentas.esquema;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Siembra una fila en <b>cada</b> tabla de tenant, para las dos municipalidades de la prueba.
 *
 * <p>La cobertura completa no es adorno: la verificacion "con contexto de A no se ve ninguna fila
 * de B" es vacia si en la tabla no hay filas de B. Una tabla sin datos sembrados pasaria en verde
 * sin probar nada, que es justamente el modo de fallo contra el que existe esta prueba. Por eso la
 * prueba exige ademas que cada tabla de tenant tenga al menos una fila propia.
 *
 * <p><b>Al agregar una tabla de tenant hay que sembrarla aqui.</b> Si no, el build se pone rojo con
 * el mensaje de que la municipalidad A no ve filas suyas en esa tabla.
 *
 * <p>Los importes son BigDecimal (regla 1 de ARQ-04 §2) y no representan ninguna regla tributaria:
 * son datos de relleno. Ninguna cifra de aqui debe leerse como un parametro del predial, que sigue
 * bloqueado por D-02.
 */
public final class DatosDePrueba {

    private static final LocalDate VIGENCIA = LocalDate.of(2026, 1, 1);
    private static final short EJERCICIO = 2026;
    private static final BigDecimal CIEN = new BigDecimal("100.00");
    private static final BigDecimal MIL = new BigDecimal("1000.00");

    /**
     * El modelo minimo que {@code documento_emitido.datos} admite: un {@code ModeloDeDocumento}.
     */
    private static final String MODELO_DE_DOCUMENTO =
            "{\"titulo\":\"Documento de prueba\",\"subtitulo\":null,\"aLaFecha\":\"2026-01-01\","
                    + "\"cabecera\":[],\"tablas\":[],\"pie\":[],\"duplicado\":null}";

    private DatosDePrueba() {}

    /** El alta de una municipalidad es una operacion de implantacion: la hace el owner. */
    public static long crearMunicipalidad(BaseDeDatosDePrueba base, String ubigeo, String nombre)
            throws SQLException {
        try (Connection owner = base.conexion(BaseDeDatosDePrueba.OWNER)) {
            long id =
                    insertar(
                            owner,
                            "INSERT INTO municipalidad (ubigeo, nombre, tipo)"
                                    + " VALUES (?, ?, 'DISTRITAL') RETURNING id",
                            ubigeo,
                            nombre);
            owner.commit();
            return id;
        }
    }

    /**
     * Catalogo nacional: lo carga su propio rol, no la aplicacion.
     *
     * <p>Desde D-13 (ADR-0017, V55) aqui entran tambien las tres tablas de valuacion —el cuadro de
     * valores unitarios, la depreciacion y los valores referenciales del MEF—, que dejaron de ser
     * municipales. Se siembran <b>una sola vez para las dos municipalidades</b>, que es exactamente
     * lo que la decision afirma: una copia nacional no puede divergir de si misma. Y se siembran
     * como {@code rol_carga_parametros}, porque {@code sgtm_app} ya no tiene {@code INSERT} sobre
     * ellas.
     *
     * @return el identificador del parametro de relleno que las tablas de tenant componen
     */
    public static long crearParametroNacional(BaseDeDatosDePrueba base) throws SQLException {
        try (Connection carga = base.conexion(BaseDeDatosDePrueba.CARGA_PARAMETROS)) {
            long id =
                    insertar(
                            carga,
                            "INSERT INTO parametro_tributario_de_prueba"
                                    + " (municipalidad_id, tipo, clave, valor_numerico, vigencia_desde,"
                                    + "  documento_fuente, usuario_carga)"
                                    + " VALUES (NULL, 'PRUEBA', 'valor-de-relleno', 1.000000, ?,"
                                    + "         'fixture de la prueba de aislamiento', 'prueba')"
                                    + " RETURNING id",
                            VIGENCIA);
            sembrarValuacionNacional(carga);
            carga.commit();
            return id;
        }
    }

    /**
     * La cabecera de una edicion nacional y una fila de cada uno de los tres cuadros. La cabecera
     * es un {@code parametro_tributario} mas: es lo que un conjunto municipal compone por {@code
     * conjunto_parametro_detalle} para congelar que edicion uso.
     */
    private static void sembrarValuacionNacional(Connection carga) throws SQLException {
        long edicion =
                insertar(
                        carga,
                        "INSERT INTO parametro_tributario_de_prueba"
                                + " (municipalidad_id, tipo, clave, valor_texto, vigencia_desde,"
                                + "  documento_fuente, usuario_carga, usuario_aprueba)"
                                + " VALUES (NULL, 'PRUEBA_EDICION', 'valuacion', 'edicion de"
                                + " prueba', ?, 'fixture de la prueba de aislamiento', 'prueba',"
                                + " 'otra persona')"
                                + " RETURNING id",
                        VIGENCIA);
        ejecutar(
                carga,
                "INSERT INTO valor_unitario_de_prueba (publicacion_id, partida, categoria,"
                        + " anio_construccion_desde, valor_m2, documento_fuente)"
                        + " VALUES (?, 'MUROS', 'C', 2000, 1.000000, 'fixture de la prueba')",
                edicion);
        ejecutar(
                carga,
                "INSERT INTO depreciacion_de_prueba (publicacion_id, uso, material, estado_conservacion,"
                        + " antiguedad_hasta, porcentaje, documento_fuente)"
                        + " VALUES (?, '01', 'CONCRETO', 'BUENO', 10, 1.0000, 'fixture de la"
                        + " prueba')",
                edicion);
        ejecutar(
                carga,
                "INSERT INTO valor_referencial_de_prueba (publicacion_id, ejercicio, categoria,"
                        + " marca, modelo, anio_fabricacion, valor, documento_fuente)"
                        + " VALUES (?, ?, 'A1', 'MARCA', 'MODELO', 2020, 1000.00,"
                        + "         'fixture de la prueba')",
                edicion,
                EJERCICIO);
    }

    /**
     * Siembra todas las tablas de tenant como {@code sgtm_app} y con el contexto de la
     * municipalidad fijado. Sembrar con el rol de la aplicacion, y no con el owner, verifica de
     * paso que la clausula {@code WITH CHECK} deja pasar lo que debe dejar pasar.
     */
    public static void sembrarTenant(
            BaseDeDatosDePrueba base, long muni, long parametroId, String sufijo)
            throws SQLException {
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);

            long conjuntoId = sembrarParametros(app, muni, parametroId);
            long[] contribuyentes = sembrarContribuyentes(app, muni, sufijo);
            long titular = contribuyentes[0];
            long segundo = contribuyentes[1];
            long predioId = sembrarCatastro(app, muni, sufijo, titular, conjuntoId);
            long vehiculoId =
                    sembrarRentas(app, muni, sufijo, titular, segundo, predioId, conjuntoId);
            long reciboId = sembrarTesoreria(app, muni, sufijo, titular, conjuntoId);
            long valorId = sembrarValoresYCoactiva(app, muni, sufijo, titular, conjuntoId);
            sembrarSanciones(
                    app, muni, sufijo, titular, segundo, vehiculoId, predioId, conjuntoId, valorId);
            sembrarLicencias(app, muni, sufijo, titular, predioId, reciboId);
            sembrarSeguridad(app, muni, sufijo);

            // Constancia de que los identificadores encadenados se usaron: el valor
            // sembrado es el que entra al expediente coactivo.
            if (valorId <= 0) {
                throw new IllegalStateException("No se sembro ningun valor");
            }

            // El trigger diferido de titularidad se evalua aqui.
            app.commit();

            // Y AHORA la proyeccion, con OTRA conexion y OTRO rol.
            //
            // Las dos cosas son deliberadas y ninguna es comodidad de la prueba. El rol, porque
            // `sgtm_app` no tiene INSERT sobre la proyeccion (V4) — intentarlo con esta misma
            // conexion da «permission denied for table catastro_evento_aplicado», que es
            // exactamente lo que ADR-0027 §3 promete. Y despues del commit, porque el ingestor
            // es otro proceso: no puede ver lo que esta transaccion todavia no ha confirmado, y
            // sembrar como si pudiera esconderia el desfase que la proyeccion tiene por
            // construccion.
            sembrarLaProyeccionDeCatastro(base, muni, predioId);
        }
    }

    // ------------------------------------------------------------------
    // Parametros
    // ------------------------------------------------------------------

    private static long sembrarParametros(Connection app, long muni, long parametroId)
            throws SQLException {
        long conjuntoId =
                insertar(
                        app,
                        "INSERT INTO conjunto_parametros_de_prueba (municipalidad_id, ejercicio, version)"
                                + " VALUES (?, ?, 1) RETURNING id",
                        muni,
                        EJERCICIO);
        ejecutar(
                app,
                "INSERT INTO conjunto_parametro_detalle_de_prueba (municipalidad_id, conjunto_id,"
                        + " parametro_id) VALUES (?, ?, ?)",
                muni,
                conjuntoId,
                parametroId);
        sembrarCacheDeNormativa(app, muni, conjuntoId);
        return conjuntoId;
    }

    /**
     * La cache local del conjunto sellado (`V3`, P5B).
     *
     * <p>Son cinco tablas de tenant, asi que {@code AislamientoMultiTenantTest} exige que la
     * municipalidad A vea filas suyas en las cinco: una tabla vacia haria que «no se ve nada de B»
     * fuera cierto sin probar nada, que es el modo de fallo contra el que existe esa prueba.
     *
     * <p>Las cifras son de relleno y estan marcadas como tales en su documento fuente. Ninguna se
     * puede leer como un valor normativo: los de verdad los publica {@code normativa}.
     */
    private static void sembrarCacheDeNormativa(Connection app, long muni, long conjuntoId)
            throws SQLException {
        ejecutar(
                app,
                "INSERT INTO normativa_conjunto (municipalidad_id, conjunto_id, ejercicio, version,"
                        + " ambito, sha256, filas, origen, descargado_en)"
                        + " VALUES (?, ?, ?, 1, 'OBLIGACION', repeat('0', 64), 4,"
                        + "         'fixture de la prueba de aislamiento', now())",
                muni,
                conjuntoId,
                EJERCICIO);
        ejecutar(
                app,
                "INSERT INTO normativa_parametro (municipalidad_id, conjunto_id, tipo, clave,"
                        + " valor_numerico, vigencia_desde, documento_fuente)"
                        + " VALUES (?, ?, 'PRUEBA', 'valor-de-relleno', 1.000000, ?,"
                        + "         'fixture de la prueba de aislamiento')",
                muni,
                conjuntoId,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO normativa_valor_unitario (municipalidad_id, conjunto_id, partida,"
                        + " categoria, anio_construccion_desde, valor_m2, documento_fuente)"
                        + " VALUES (?, ?, 'MUROS', 'C', 2000, 1.000000,"
                        + "         'fixture de la prueba de aislamiento')",
                muni,
                conjuntoId);
        ejecutar(
                app,
                "INSERT INTO normativa_depreciacion (municipalidad_id, conjunto_id, uso, material,"
                        + " estado_conservacion, antiguedad_hasta, porcentaje, documento_fuente)"
                        + " VALUES (?, ?, '01', 'CONCRETO', 'BUENO', 10, 1.0000,"
                        + "         'fixture de la prueba de aislamiento')",
                muni,
                conjuntoId);
        ejecutar(
                app,
                "INSERT INTO normativa_valor_referencial (municipalidad_id, conjunto_id, ejercicio,"
                        + " categoria, marca, modelo, anio_fabricacion, valor, documento_fuente)"
                        + " VALUES (?, ?, ?, 'A1', 'MARCA', 'MODELO', 2020, 1000.00,"
                        + "         'fixture de la prueba de aislamiento')",
                muni,
                conjuntoId,
                EJERCICIO);
    }

    // ------------------------------------------------------------------
    // Contribuyentes
    // ------------------------------------------------------------------

    /** Dos contribuyentes: uno titular y otro para transferencias y papeletas. */
    private static long[] sembrarContribuyentes(Connection app, long muni, String sufijo)
            throws SQLException {
        long titular = crearContribuyente(app, muni, sufijo, "1");
        long segundo = crearContribuyente(app, muni, sufijo, "2");

        ejecutar(
                app,
                "INSERT INTO domicilio (municipalidad_id, contribuyente_id, tipo, direccion,"
                        + " vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, 'FISCAL', ?, ?, 'DJ-001')",
                muni,
                titular,
                "Av. Siempre Viva " + sufijo,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO contacto (municipalidad_id, contribuyente_id, tipo, valor)"
                        + " VALUES (?, ?, 'EMAIL', ?)",
                muni,
                titular,
                "contribuyente" + sufijo + "@ejemplo.pe");
        // El segundo responde solidariamente por el titular: sirve para la prueba de
        // aislamiento y de paso deja sembrado el caso que la cobranza consulta.
        ejecutar(
                app,
                "INSERT INTO responsable_solidario (municipalidad_id, contribuyente_id,"
                        + " responsable_id, vinculo, vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, ?, 'CONYUGE', ?, 'Partida de matrimonio')",
                muni,
                titular,
                segundo,
                VIGENCIA);
        return new long[] {titular, segundo};
    }

    private static long crearContribuyente(Connection app, long muni, String sufijo, String orden)
            throws SQLException {
        return insertar(
                app,
                "INSERT INTO contribuyente (municipalidad_id, codigo_contribuyente, tipo_documento,"
                        + " numero_documento, tipo_persona, nombre_razon_social, usuario_registro)"
                        + " VALUES (?, ?, 'DNI', ?, 'NATURAL', ?, 'prueba') RETURNING id",
                muni,
                "C-" + sufijo + orden,
                "1000000" + sufijo + orden,
                "Contribuyente " + sufijo + orden);
    }

    // ------------------------------------------------------------------
    // Catastro
    // ------------------------------------------------------------------

    private static long sembrarCatastro(
            Connection app, long muni, String sufijo, long titular, long conjuntoId)
            throws SQLException {
        long viaId =
                insertar(
                        app,
                        "INSERT INTO via_de_prueba (municipalidad_id, codigo, tipo_via, nombre)"
                                + " VALUES (?, ?, 'AVENIDA', ?) RETURNING id",
                        muni,
                        "V-" + sufijo,
                        "Avenida Grau " + sufijo);
        long sectorId =
                insertar(
                        app,
                        "INSERT INTO sector_de_prueba (municipalidad_id, codigo, nombre)"
                                + " VALUES (?, ?, ?) RETURNING id",
                        muni,
                        "S-" + sufijo,
                        "Sector " + sufijo);
        long manzanaId =
                insertar(
                        app,
                        "INSERT INTO manzana_de_prueba (municipalidad_id, sector_id, codigo)"
                                + " VALUES (?, ?, ?) RETURNING id",
                        muni,
                        sectorId,
                        "M-" + sufijo);
        long predioId =
                insertar(
                        app,
                        "INSERT INTO predio_de_prueba (municipalidad_id, codigo_ref_catastral, tipo, via_id,"
                                + " direccion, sector_id, manzana_id, lote)"
                                + " VALUES (?, ?, 'URBANO', ?, ?, ?, ?, '01') RETURNING id",
                        muni,
                        codigoCatastral(sufijo),
                        viaId,
                        "Jr. Union " + sufijo,
                        sectorId,
                        manzanaId);

        long fichaId =
                insertar(
                        app,
                        "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                                + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                                + " observacion, usuario_registro)"
                                + " VALUES (?, ?, 'UNICA', 1, 120.00, 'CASA_HABITACION', ?,"
                                + "         'DECLARACION_JURADA', 'DJ-001', 'ficha inicial de prueba',"
                                + "         'prueba') RETURNING id",
                        muni,
                        predioId,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO construccion_de_prueba (municipalidad_id, ficha_id, piso, area_construida,"
                        + " anio_construccion, material_estructural, estado_conservacion,"
                        + " categoria_muros)"
                        + " VALUES (?, ?, '1', 80.00, 2010, 'CONCRETO', 'BUENO', 'C')",
                muni,
                fichaId);
        ejecutar(
                app,
                "INSERT INTO otra_instalacion_de_prueba (municipalidad_id, ficha_id, descripcion,"
                        + " unidad_medida, cantidad)"
                        + " VALUES (?, ?, 'Cerco perimetrico', 'ML', 25.00)",
                muni,
                fichaId);

        // Los otros tres tipos de ficha (#19). Van sobre el mismo predio a proposito: el indice
        // parcial admite una vigente de cada tipo, y sembrarlas juntas lo comprueba de paso.
        long economica =
                insertar(
                        app,
                        "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                                + " area_terreno, uso, informacion_complementaria, vigencia_desde,"
                                + " origen, documento_origen, observacion, usuario_registro)"
                                + " VALUES (?, ?, 'ECONOMICA', 1, 120.00, 'COMERCIO',"
                                + "         'ficha economica de prueba', ?, 'FISCALIZACION',"
                                + "         'ACTA-001', 'ficha economica de prueba', 'prueba')"
                                + " RETURNING id",
                        muni,
                        predioId,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO actividad_economica_de_prueba (municipalidad_id, ficha_id, conductor,"
                        + " nombre_comercial, ciiu, licencia_numero, licencia_fecha)"
                        + " VALUES (?, ?, 'Conductor de prueba', 'Bodega de prueba', '4711',"
                        + "         'LIC-001', ?)",
                muni,
                economica,
                VIGENCIA);

        long comunes =
                insertar(
                        app,
                        "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                                + " area_terreno, uso, denominacion, vigencia_desde, origen,"
                                + " documento_origen, observacion, usuario_registro)"
                                + " VALUES (?, ?, 'BIENES_COMUNES', 1, 120.00, 'MULTIFAMILIAR',"
                                + "         'Edificio de prueba', ?, 'DECLARACION_JURADA',"
                                + "         'DJ-002', 'ficha de bienes comunes de prueba', 'prueba')"
                                + " RETURNING id",
                        muni,
                        predioId,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO bien_comun_de_prueba (municipalidad_id, ficha_id, descripcion, area,"
                        + " material_estructural, estado_conservacion)"
                        + " VALUES (?, ?, 'Escalera comun', 30.00, 'CONCRETO', 'BUENO')",
                muni,
                comunes);
        ejecutar(
                app,
                "INSERT INTO participacion_comun_de_prueba (municipalidad_id, ficha_id, predio_id,"
                        + " porcentaje) VALUES (?, ?, ?, 100)",
                muni,
                comunes,
                predioId);

        long rural =
                insertar(
                        app,
                        "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                                + " area_terreno, uso, denominacion, vigencia_desde, origen,"
                                + " documento_origen, observacion, usuario_registro)"
                                + " VALUES (?, ?, 'RURAL', 1, 120.00, 'AGRICOLA',"
                                + "         'Fundo de prueba', ?, 'DECLARACION_JURADA', 'DJ-003',"
                                + "         'ficha rural de prueba', 'prueba') RETURNING id",
                        muni,
                        predioId,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO tierra_rural_de_prueba (municipalidad_id, ficha_id, clasificacion, riego,"
                        + " cantidad_hectareas) VALUES (?, ?, 'CULTIVO_TRANSITORIO', 'SECANO',"
                        + "         2.5000)",
                muni,
                rural);
        ejecutar(
                app,
                "INSERT INTO colindante_rural_de_prueba (municipalidad_id, ficha_id, orientacion,"
                        + " descripcion) VALUES (?, ?, 'NORTE', 'Predio de prueba colindante')",
                muni,
                rural);

        ejecutar(
                app,
                "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                        + " referencia, datos, formato, resumen, fecha_emision, usuario_emision,"
                        + " observacion)"
                        + " VALUES (?, 'FICHA_CONTRIBUYENTE', 'FICHA_CONTRIBUYENTE-2026-000001',"
                        + "         2026, 'C-0001', CAST(? AS jsonb), 'PDF', repeat('a', 64),"
                        + "         ?, 'siembra', 'documento de prueba')",
                muni,
                "{\"titulo\":\"Documento de prueba\",\"subtitulo\":null,\"aLaFecha\":\"2026-01-01\","
                        + "\"cabecera\":[],\"tablas\":[],\"pie\":[],\"duplicado\":null}",
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO titularidad_de_prueba (municipalidad_id, predio_id, contribuyente_id, condicion,"
                        + " porcentaje, vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, ?, 'PROPIETARIO_UNICO', 100, ?, 'MINUTA-001')",
                muni,
                predioId,
                titular,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO inquilino_de_prueba (municipalidad_id, predio_id, contribuyente_id,"
                        + " vigencia_desde, documento_origen)"
                        + " VALUES (?, ?, ?, ?, 'CONTRATO-001')",
                muni,
                predioId,
                titular,
                VIGENCIA);

        // Tablas de valuacion. Valores de relleno: los normativos siguen en D-02. Cuelgan del
        // conjunto de parametros sembrado por sembrarParametros, no de un ejercicio suelto (#17).
        ejecutar(
                app,
                "INSERT INTO arancel_de_prueba (municipalidad_id, conjunto_id, via_id, valor_m2,"
                        + " documento_fuente)"
                        + " VALUES (?, ?, ?, 1.000000, 'fixture de la prueba')",
                muni,
                conjuntoId,
                viaId);
        // valor_unitario_edificacion y depreciacion ya no se siembran aqui: desde D-13 (V55) son
        // nacionales, las carga rol_carga_parametros y viven en crearParametroNacional. El arancel
        // si se queda: se carga y se corrige por municipalidad.
        return predioId;
    }

    /** Codigo de referencia catastral de relleno; la longitud exacta es D-10. */
    private static String codigoCatastral(String sufijo) {
        String digitos = Integer.toString(Math.abs(sufijo.hashCode() % 100) + 10);
        return ("2006010101500101010" + digitos + "000000").substring(0, 21);
    }

    // ------------------------------------------------------------------
    // Rentas y cuenta corriente
    // ------------------------------------------------------------------

    /**
     * La proyeccion local de {@code catastro} (P5C, `V4`), sembrada como la escribiria el ingestor.
     *
     * <p>Se escribe con la conexion del INGESTOR, que es la unica que puede: `V4` le da a
     * `sgtm_app` solo `SELECT`. Y se hace despues del commit del escenario, porque el ingestor es
     * otro proceso y no ve lo que otra transaccion no ha confirmado.
     */
    private static void sembrarLaProyeccionDeCatastro(
            BaseDeDatosDePrueba base, long muni, long predioId) throws SQLException {
        // Se LEE con la conexion de la aplicacion y se ESCRIBE con la del ingestor, y el rodeo
        // es fiel: el ingestor no lee `predio` —no tiene privilegio, y no debe tenerlo—, sino
        // que recibe los datos DENTRO del evento. Aqui el "evento" son estas dos consultas.
        List<Object[]> fichas = new ArrayList<>();
        Object[] predio;
        try (Connection app = base.conexion(BaseDeDatosDePrueba.APP)) {
            ContextoDeTenant.fijar(app, muni);
            try (PreparedStatement consulta =
                    app.prepareStatement(
                            "SELECT p.codigo_ref_catastral, p.direccion, s.codigo, p.estado"
                                    + "   FROM predio_de_prueba p"
                                    + "   LEFT JOIN sector_de_prueba s ON s.municipalidad_id ="
                                    + "        p.municipalidad_id AND s.id = p.sector_id"
                                    + "  WHERE p.municipalidad_id = ? AND p.id = ?")) {
                consulta.setLong(1, muni);
                consulta.setLong(2, predioId);
                try (ResultSet fila = consulta.executeQuery()) {
                    fila.next();
                    predio =
                            new Object[] {
                                fila.getString(1),
                                fila.getString(2),
                                fila.getString(3),
                                fila.getString(4)
                            };
                }
            }
            try (PreparedStatement consulta =
                    app.prepareStatement(
                            "SELECT id, tipo, version, vigencia_desde, vigencia_hasta,"
                                    + " area_terreno, uso FROM ficha_catastral_de_prueba"
                                    + " WHERE municipalidad_id = ? AND predio_id = ?")) {
                consulta.setLong(1, muni);
                consulta.setLong(2, predioId);
                try (ResultSet filas = consulta.executeQuery()) {
                    while (filas.next()) {
                        fichas.add(
                                new Object[] {
                                    filas.getLong(1),
                                    filas.getString(2),
                                    filas.getInt(3),
                                    filas.getDate(4),
                                    filas.getDate(5),
                                    filas.getBigDecimal(6),
                                    filas.getString(7)
                                });
                    }
                }
            }
            app.rollback();
        }

        try (Connection ingestor = base.conexion(BaseDeDatosDePrueba.INGESTOR_CATASTRO)) {
            ContextoDeTenant.fijar(ingestor, muni);
            // Un evento por hecho, y su procedencia copiada en cada fila que produce (`V9`).
            // El uuid se genera AQUI y no con `gen_random_uuid()` en el `INSERT` porque la fila
            // proyectada tiene que llevar el MISMO: ese es el punto de la clave foranea al buzon.
            String eventoDelPredio = evento(ingestor, muni, "PREDIO_PROYECTADO", predioId, 1);
            ejecutar(
                    ingestor,
                    "INSERT INTO predio_ref (municipalidad_id, predio_id, codigo_ref_catastral,"
                            + " direccion, sector_codigo, estado, secuencia, proyectado_en,"
                            + " evento_id, huella)"
                            + " VALUES (?, ?, ?, ?, ?, ?, 1, now(), CAST(? AS uuid), ?)",
                    muni,
                    predioId,
                    predio[0],
                    predio[1],
                    predio[2],
                    predio[3],
                    eventoDelPredio,
                    huellaDe(eventoDelPredio));
            for (Object[] ficha : fichas) {
                ejecutar(
                        ingestor,
                        "INSERT INTO ficha_ref (municipalidad_id, ficha_id, predio_id, tipo,"
                                + " version, vigencia_desde, vigencia_hasta, area_terreno, uso,"
                                + " secuencia, proyectado_en, evento_id, huella)"
                                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now(),"
                                + " CAST(? AS uuid), ?)",
                        muni,
                        ficha[0],
                        predioId,
                        ficha[1],
                        ficha[2],
                        ficha[3],
                        ficha[4],
                        ficha[5],
                        ficha[6],
                        eventoDelPredio,
                        huellaDe(eventoDelPredio));
            }
            sembrarLaValuacion(ingestor, muni, predioId);
            sembrarUnHechoSinAplicar(ingestor, muni);
            ingestor.commit();
        }
    }

    /**
     * Una valuacion sellada y el cierre de su corrida (P5C, `V5`).
     *
     * <p>La valuacion sale SIN cifras y con su motivo, que es el estado real de hoy: el sistema no
     * sabe valorizar un predio todavia —faltan el cuadro de valores unitarios y la depreciacion
     * (GOB-03 H-14/H-15), los aranceles (D-02b) y el % actualizacion (D-11)—. `V5` obliga a elegir
     * una de las dos cosas con un `CHECK`, y sembrar un cero habria sido inventar el dato que ese
     * `CHECK` existe para impedir.
     *
     * <p>La huella agregada se calcula con LA MISMA expresion que {@code
     * ValuacionRecibidaJdbc.huellaDeLoRecibido}. Escribirla a mano dejaria al candado comparando
     * contra un valor inventado, y entonces pasaria en verde diga lo que diga.
     */
    private static void sembrarLaValuacion(Connection ingestor, long muni, long predioId)
            throws SQLException {
        String eventoDeLaValuacion = evento(ingestor, muni, "VALUACION_SELLADA", predioId, 2);
        ejecutar(
                ingestor,
                "INSERT INTO valuacion_predio (municipalidad_id, ejercicio, predio_id,"
                        + " fecha_de_corte, motivo, conjunto_id, reglas_version,"
                        + " reglas_aplicadas, huella, evento_id, secuencia, recibida_en)"
                        + " VALUES (?, 2026, ?, DATE '2025-12-31',"
                        + " 'El sistema no sabe valorizar un predio todavia (D-02a, D-11)',"
                        + " 1, 'v1', '', encode(sha256(convert_to('v-' || ?, 'UTF8')), 'hex'),"
                        + " CAST(? AS uuid), 2, now())",
                muni,
                predioId,
                predioId,
                eventoDeLaValuacion);
        String eventoDelCierre = evento(ingestor, muni, "CORRIDA_CERRADA", null, 3);
        ejecutar(
                ingestor,
                "INSERT INTO valuacion_corrida (municipalidad_id, ejercicio, corrida_id,"
                        + " conjunto_id, fecha_de_corte, reglas_version, conteo, huella,"
                        + " cerrada_en, recibida_en, evento_id, secuencia)"
                        + " SELECT ?, 2026, 1, 1, DATE '2025-12-31', 'v1', count(*),"
                        + "        encode(sha256(convert_to("
                        + "          coalesce(string_agg(v.huella, ',' ORDER BY v.predio_id), ''),"
                        + "          'UTF8')), 'hex'),"
                        + "        now(), now(), CAST(? AS uuid), 3"
                        + "   FROM valuacion_predio v"
                        + "  WHERE v.ejercicio = 2026",
                muni,
                eventoDelCierre);
    }

    /**
     * Un hecho apartado en la cola de muertos (`V12`, C-8), para que su aislamiento se pueda medir.
     *
     * <p>La prueba de aislamiento no comprueba solo que A no vea filas de B: comprueba
     * <b>ademas</b> que A vea las suyas, y sin una fila sembrada las dos cosas darian cero y se
     * leerian igual.
     *
     * <p>Su {@code evento_id} <b>no esta en el buzon a proposito</b>, y por eso esta tabla no tiene
     * clave foranea hacia el: sus filas son exactamente los hechos que nunca llegaron a aplicarse.
     */
    private static void sembrarUnHechoSinAplicar(Connection ingestor, long muni)
            throws SQLException {
        String eventoQueNoSeAplico = UUID.randomUUID().toString();
        ejecutar(
                ingestor,
                "INSERT INTO catastro_evento_muerto (municipalidad_id, evento_id, secuencia, tipo,"
                        + " predio_id, ejercicio, cuerpo, huella, motivo, recibido_en)"
                        + " VALUES (?, CAST(? AS uuid), 99, 'PREDIO_PROYECTADO', NULL, NULL,"
                        + "         '{\"prueba\": \"aislamiento\"}', ?,"
                        + "         'Sembrado por la prueba de aislamiento: no se aplico nunca',"
                        + "         now())",
                muni,
                eventoQueNoSeAplico,
                huellaDe(eventoQueNoSeAplico));
    }

    private static long sembrarRentas(
            Connection app,
            long muni,
            String sufijo,
            long titular,
            long segundo,
            long predioId,
            long conjuntoId)
            throws SQLException {
        long vehiculoId =
                insertar(
                        app,
                        "INSERT INTO vehiculo (municipalidad_id, placa, contribuyente_id, marca,"
                                + " modelo, categoria, anio_fabricacion, anio_inscripcion)"
                                + " VALUES (?, ?, ?, 'MARCA', 'MODELO', 'M1', 2020, 2021)"
                                + " RETURNING id",
                        muni,
                        "ABC-" + numeroDePlaca(sufijo),
                        titular);
        // valor_referencial_vehiculo tampoco se siembra aqui desde D-13 (V55): la tabla del MEF es
        // nacional y se carga una vez para todas, en crearParametroNacional. Que edicion uso una
        // determinacion lo dice su conjunto, componiendola por conjunto_parametro_detalle.
        ejecutar(
                app,
                "INSERT INTO declaracion_jurada (municipalidad_id, numero, ejercicio,"
                        + " contribuyente_id, tipo, predio_id, fecha_presentacion, fecha_limite,"
                        + " usuario_registro, observacion)"
                        + " VALUES (?, ?, ?, ?, 'HR', ?, ?, ?, 'prueba', 'declaracion de prueba')",
                muni,
                "DJ-" + sufijo,
                EJERCICIO,
                titular,
                predioId,
                VIGENCIA,
                VIGENCIA.plusMonths(2));
        // El correlativo del ejercicio (#365, V54). En operacion lo crea la primera peticion, por
        // encima del mayor numero historico; aqui se siembra a 1 porque la prueba de aislamiento
        // exige que cada tabla con RLS tenga al menos una fila de cada municipalidad.
        ejecutar(
                app,
                "INSERT INTO dj_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, ?, 1)",
                muni,
                EJERCICIO);
        ejecutar(
                app,
                "INSERT INTO beneficio (municipalidad_id, contribuyente_id, predio_id, tipo,"
                        + " tributo, clase, porcentaje, vigencia_desde, base_legal,"
                        + " documento_origen, observacion, usuario_registro)"
                        + " VALUES (?, ?, ?, 'PENSIONISTA', 'PREDIAL', 'DEDUCCION', 1.0000, ?,"
                        + "         'fixture de la prueba', 'RES-001', 'beneficio de prueba',"
                        + "         'prueba')",
                muni,
                titular,
                predioId,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO transferencia (municipalidad_id, objeto, predio_id, transferente_id,"
                        + " adquiriente_id, tipo_transferencia, fecha_transferencia,"
                        + " valor_transferencia, porcentaje_transferido, afecta_alcabala,"
                        + " documento_origen, observacion, usuario_registro)"
                        + " VALUES (?, 'PREDIO', ?, ?, ?, 'COMPRA_VENTA', ?, ?, 100, true,"
                        + "         'MINUTA-002', 'transferencia de prueba', 'prueba')",
                muni,
                predioId,
                titular,
                segundo,
                VIGENCIA,
                MIL);
        ejecutar(
                app,
                "INSERT INTO espectaculo (municipalidad_id, contribuyente_id, denominacion, tipo,"
                        + " lugar, fecha_evento, usuario_registro)"
                        + " VALUES (?, ?, 'Evento de prueba', 'CONCIERTO', 'Coliseo', ?, 'prueba')",
                muni,
                titular,
                VIGENCIA);

        // Sin predio_id: el predial se determina por contribuyente, nunca por un solo
        // predio (NEG-05 §1); determinacion_predial_sin_predio_ck (V20) lo exige. El
        // aporte del predio va en determinacion_predio_detalle (V20).
        long determinacionId =
                insertar(
                        app,
                        "INSERT INTO determinacion (municipalidad_id, ejercicio, tributo, periodo,"
                                + " contribuyente_id, conjunto_id, base_imponible,"
                                + " monto_determinado, reglas_aplicadas, usuario_calculo)"
                                + " VALUES (?, ?, 'PREDIAL', 1, ?, ?, ?, ?,"
                                + "         ARRAY['RT-000']::varchar(200)[], 'prueba') RETURNING id",
                        muni,
                        EJERCICIO,
                        titular,
                        conjuntoId,
                        MIL,
                        CIEN);
        ejecutar(
                app,
                // `valuo_exonerado` en cero, y dicho: desde V56 la columna es NOT NULL sin
                // valor por omision, para que toda escritura diga que parte del autovaluo no
                // esta afecta en vez de dejarla suponer (#395).
                "INSERT INTO determinacion_predio_detalle (municipalidad_id, ejercicio,"
                        + " determinacion_id, predio_id, autovaluo, valuo_exonerado,"
                        + " porcentaje_propiedad, base_imponible_predio)"
                        + " VALUES (?, ?, ?, ?, ?, 0, 100, ?)",
                muni,
                EJERCICIO,
                determinacionId,
                predioId,
                MIL,
                MIL);
        ejecutar(
                app,
                "INSERT INTO cuenta_corriente_asiento (municipalidad_id, ejercicio,"
                        + " contribuyente_id, tributo, concepto, tipo, periodo, predio_id, monto,"
                        + " fecha_valor, documento_origen, usuario_id)"
                        + " VALUES (?, ?, ?, 'PREDIAL', 'INSOLUTO', 'CARGO', 1, ?, ?, ?,"
                        + "         'EM-2026-0001', 'prueba')",
                muni,
                EJERCICIO,
                titular,
                predioId,
                CIEN,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO saldo_proyectado (municipalidad_id, contribuyente_id, predio_id,"
                        + " tributo, ejercicio, periodo, insoluto_saldo)"
                        + " VALUES (?, ?, ?, 'PREDIAL', ?, 1, ?)",
                muni,
                titular,
                predioId,
                EJERCICIO,
                CIEN);
        ejecutar(
                app,
                "INSERT INTO determinacion_arbitrio (municipalidad_id, ejercicio, servicio,"
                        + " periodo, contribuyente_id, predio_id, conjunto_id, monto,"
                        + " parametro_aplicado, fecha_calculo, usuario_calculo)"
                        + " VALUES (?, ?, 'LIMPIEZA_PUBLICA', 1, ?, ?, ?, ?,"
                        + "         'TASA_LIMPIEZA_PUBLICA:S-01:CASA_HABITACION', ?, 'prueba')",
                muni,
                EJERCICIO,
                titular,
                predioId,
                conjuntoId,
                CIEN,
                VIGENCIA);
        return vehiculoId;
    }

    private static String numeroDePlaca(String sufijo) {
        return Integer.toString(100 + Math.abs(sufijo.hashCode() % 800));
    }

    // ------------------------------------------------------------------
    // Tesoreria
    // ------------------------------------------------------------------

    /**
     * El recibo de {@code caja} al que apuntan las filas que lo referencian (P5D).
     *
     * <p>Hasta `V7` el fixture <b>emitia un recibo de verdad</b> —con su area, su ventanilla, su
     * turno, su serie, su detalle, su duplicado, su cierre y la reversion del cierre— porque las
     * diez tablas estaban en esta base y `licencia_funcionamiento.recibo_id` tenia clave foranea
     * hacia ellas. `V7` retiro las diez y las cinco claves foraneas: el recibo vive en {@code
     * caja}.
     *
     * <p>Asi que lo que se siembra es un <b>identificador de otro sistema</b>, y que se pueda es
     * exactamente lo que `V7` cambio. Se elige un numero alto y fijo, no un correlativo: no hay
     * ninguna secuencia local que lo produzca, y fingir una diria que este sistema los genera.
     */
    private static final long RECIBO_DE_CAJA = 9_000_001L;

    /**
     * El convenio de fraccionamiento, que es lo que de {@code tesoreria} se quedo (ADR-0026 §5).
     *
     * <p>Devuelve el identificador del recibo que lo formalizo —{@link #RECIBO_DE_CAJA}— porque las
     * licencias lo necesitan para su propia siembra: el derecho de tramite se paga con un recibo, y
     * ese enlace se conserva aunque el motor ya no lo garantice.
     */
    private static long sembrarTesoreria(
            Connection app, long muni, String sufijo, long titular, long conjuntoId)
            throws SQLException {
        long reciboId = RECIBO_DE_CAJA;

        // Y una fila del BUZON DE ENTRADA DE PAGOS (P5D, `V8`), que es lo que llega en lugar de
        // los recibos que se fueron. Se siembra aqui y no aparte porque el pago y el convenio son
        // las dos mitades de lo que `tesoreria` quedo siendo: lo que la caja publica y lo que este
        // sistema hace con ello.
        //
        // Con `estado = 'EN_TRANSITO'`, que es el estado en el que un pago pasa mas tiempo siendo
        // interesante: entre los dos COMMIT. Sembrarlo APLICADO dejaria la prueba de aislamiento
        // sin ninguna fila del estado que la consulta de deuda lee.
        ejecutar(
                app,
                "INSERT INTO pago_recibido (municipalidad_id, pago_id, tipo, sistema_caja,"
                        + " recibo_numero, contribuyente_id, fecha_pago, total, cuerpo, estado,"
                        + " asientos, recibido_en)"
                        + " VALUES (?, gen_random_uuid(), 'PAGO_REGISTRADO', 'caja', ?, ?, ?, ?,"
                        + "         CAST(? AS jsonb), 'EN_TRANSITO', 0, now())",
                muni,
                "001-" + sufijo,
                titular,
                VIGENCIA,
                CIEN,
                "{\"pagoId\":\"sembrado\",\"sufijo\":\"" + sufijo + "\"}");

        // Un convenio de fraccionamiento (V31, #35), con su correlativo, su cronograma, la
        // deuda que acogio y su formalizacion.
        //
        // Se siembra la FORMALIZACION y no un cierre a proposito: el cierre es unico por
        // convenio y ademas es el que devuelve la deuda a su fase, asi que sembrarlo dejaria
        // el fixture describiendo un convenio ya muerto.
        ejecutar(
                app,
                "INSERT INTO convenio_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, ?, 1)",
                muni,
                EJERCICIO);
        long convenioId =
                insertar(
                        app,
                        "INSERT INTO convenio (municipalidad_id, numero, contribuyente_id, tipo,"
                                + " fecha, fecha_corte, conjunto_id, interes_mensual,"
                                + " porcentaje_inicial, maximo_cuotas, monto_total, cuota_inicial,"
                                + " numero_cuotas, usuario_registro, observacion, fecha_registro)"
                                + " VALUES (?, ?, ?, 'ORDINARIO', ?, ?, ?, 1.0000, 10.0000, 12, ?,"
                                + "         ?, 6, 'prueba', 'convenio sembrado por el fixture',"
                                + "         now()) RETURNING id",
                        muni,
                        "CV-" + sufijo,
                        titular,
                        VIGENCIA,
                        VIGENCIA,
                        conjuntoId,
                        MIL,
                        CIEN);
        ejecutar(
                app,
                "INSERT INTO convenio_cuota (municipalidad_id, convenio_id, numero, vencimiento,"
                        + " monto, capital) VALUES (?, ?, 1, ?, ?, ?)",
                muni,
                convenioId,
                VIGENCIA.plusMonths(1),
                CIEN,
                CIEN);
        ejecutar(
                app,
                "INSERT INTO convenio_deuda (municipalidad_id, convenio_id, tributo, ejercicio,"
                        + " periodo, fase_origen, insoluto, monto, fecha_corte)"
                        + " VALUES (?, ?, 'PREDIAL', ?, 1, 'ORDINARIA', ?, ?, ?)",
                muni,
                convenioId,
                EJERCICIO,
                MIL,
                MIL,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO convenio_movimiento (municipalidad_id, convenio_id, tipo, fecha,"
                        + " recibo_id, cuota, importe, asientos, usuario_registro, fecha_registro,"
                        + " observacion)"
                        + " VALUES (?, ?, 'FORMALIZACION', ?, ?, 0, ?, 2, 'prueba', now(),"
                        + "         'formalizacion sembrada por el fixture')",
                muni,
                convenioId,
                VIGENCIA,
                reciboId,
                MIL);
        return reciboId;
    }

    // ------------------------------------------------------------------
    // Valores y coactiva
    // ------------------------------------------------------------------

    private static long sembrarValoresYCoactiva(
            Connection app, long muni, String sufijo, long titular, long conjuntoId)
            throws SQLException {
        long valorId =
                insertar(
                        app,
                        "INSERT INTO valor (municipalidad_id, tipo, numero, ejercicio,"
                                + " contribuyente_id, fecha_emision, base_legal, monto_insoluto,"
                                + " monto_total, proyectado_a, usuario_registro, observacion)"
                                + " VALUES (?, 'OP', ?, ?, ?, ?, 'ART. 78 DEL CODIGO TRIBUTARIO', ?, ?,"
                                + "         ?, 'prueba', 'valor de prueba') RETURNING id",
                        muni,
                        "OP-" + sufijo,
                        EJERCICIO,
                        titular,
                        VIGENCIA,
                        CIEN,
                        CIEN,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO valor_detalle (municipalidad_id, valor_id, tributo, ejercicio, periodo,"
                        + " insoluto) VALUES (?, ?, 'PREDIAL', ?, 1, ?)",
                muni,
                valorId,
                EJERCICIO,
                CIEN);
        ejecutar(
                app,
                "INSERT INTO valor_correlativo (municipalidad_id, tipo, ejercicio, ultimo)"
                        + " VALUES (?, 'OP', ?, 1)",
                muni,
                EJERCICIO);

        // #38: una corrida masiva ya resuelta -el mismo valor de arriba, referenciado
        // desde su item- y una todavia pendiente, para que la prueba de aislamiento
        // tenga fila que ver en los dos estados de valor_masivo_item.
        long corridaMasivaId =
                insertar(
                        app,
                        "INSERT INTO valor_masivo (municipalidad_id, tipo, ejercicio_desde,"
                                + " ejercicio_hasta, fecha_criterio, origen, total_candidatos,"
                                + " usuario_registro, observacion)"
                                + " VALUES (?, 'OP', ?, ?, ?, 'SELECCION', 1, 'prueba',"
                                + "         'corrida masiva de prueba') RETURNING id",
                        muni,
                        EJERCICIO,
                        EJERCICIO,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO valor_masivo_item (municipalidad_id, corrida_id, contribuyente_id,"
                        + " estado, valor_id, fecha_procesado)"
                        + " VALUES (?, ?, ?, 'GENERADO', ?, now())",
                muni,
                corridaMasivaId,
                titular,
                valorId);
        // #523: una corrida de emision predial con su observado. Las dos tablas llevan
        // `municipalidad_id NOT NULL`, asi que la prueba de aislamiento les exige RLS
        // sola —y exige ademas que A vea fila propia: sin sembrarla, «no se ve nada de
        // B» seria cierto y no probaria nada—.
        long corridaPredialId =
                insertar(
                        app,
                        "INSERT INTO corrida_predial (municipalidad_id, ejercicio, alcance,"
                                + " modalidad, simulacion, conjunto, leidos, determinados,"
                                + " monto_emitido, fecha_calculo, usuario_registro,"
                                + " fecha_registro, observacion)"
                                + " VALUES (?, ?, 'TODOS', 'TRIMESTRAL', false, 'conjunto de"
                                + "         prueba', 2, 1, 100.00, ?, 'prueba', now(),"
                                + "         'corrida de emision de prueba') RETURNING id",
                        muni,
                        EJERCICIO,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO corrida_predial_observado (municipalidad_id, corrida_id,"
                        + " cod_contribuyente, nombre, motivo)"
                        + " VALUES (?, ?, 'C-PRUEBA', 'OBSERVADO, DE PRUEBA',"
                        + "         'uno de sus predios esta sin arancel')",
                muni,
                corridaPredialId);
        // #39: dos diligencias sobre el mismo valor. La primera no ubico el domicilio
        // -y por eso no lleva exigibilidad-; la segunda notifico. Las dos filas conviven:
        // es el reintento que la tabla tiene que admitir sin borrar el intento anterior.
        ejecutar(
                app,
                "INSERT INTO notificacion (municipalidad_id, objeto, objeto_id, numero, intento,"
                        + " fecha_notificacion, modalidad, resultado, notificador, direccion,"
                        + " usuario_registro, observacion)"
                        + " VALUES (?, 'VALOR', ?, ?, 1, ?, 'PERSONAL', 'NO_UBICADO', 'prueba',"
                        + "         'domicilio de prueba', 'prueba', 'no se ubico el domicilio')",
                muni,
                valorId,
                "NT1-" + sufijo,
                VIGENCIA);
        long notificacionId =
                insertar(
                        app,
                        "INSERT INTO notificacion (municipalidad_id, objeto, objeto_id, numero,"
                                + " intento, fecha_notificacion, modalidad, resultado, notificador,"
                                + " direccion, receptor, exigible_desde, conjunto_id,"
                                + " usuario_registro, observacion)"
                                + " VALUES (?, 'VALOR', ?, ?, 2, ?, 'PERSONAL', 'NOTIFICADO',"
                                + "         'prueba', 'domicilio de prueba', 'quien recibe', ?, ?,"
                                + "         'prueba', 'notificacion de prueba') RETURNING id",
                        muni,
                        valorId,
                        "NT2-" + sufijo,
                        VIGENCIA,
                        VIGENCIA,
                        conjuntoId);
        ejecutar(
                app,
                "INSERT INTO valor_movimiento (municipalidad_id, valor_id, tipo, fecha,"
                        + " notificacion_id, exigible_desde, usuario_registro, observacion)"
                        + " VALUES (?, ?, 'PCO', ?, ?, ?, 'prueba', 'pase de prueba')",
                muni,
                valorId,
                VIGENCIA,
                notificacionId,
                VIGENCIA);

        long prescripcionId =
                insertar(
                        app,
                        "INSERT INTO prescripcion (municipalidad_id, contribuyente_id, tributo,"
                                + " ejercicio_desde, ejercicio_hasta, fecha_presentacion, causal,"
                                + " plazo_anios, conjunto_id, resultado, usuario_registro,"
                                + " observacion)"
                                + " VALUES (?, ?, 'PREDIAL', ?, ?, ?, 'DECLARACION_PRESENTADA', 4,"
                                + "         ?, 'NO_PROCEDE', 'prueba', 'solicitud de prueba')"
                                + " RETURNING id",
                        muni,
                        titular,
                        EJERCICIO,
                        EJERCICIO,
                        VIGENCIA,
                        conjuntoId);
        ejecutar(
                app,
                "INSERT INTO prescripcion_ejercicio (municipalidad_id, prescripcion_id, ejercicio,"
                        + " inicio_computo, inicio_vigente, fecha_prescripcion, prescrita)"
                        + " VALUES (?, ?, ?, ?, ?, ?, false)",
                muni,
                prescripcionId,
                EJERCICIO,
                VIGENCIA,
                VIGENCIA,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO prescripcion_hecho (municipalidad_id, prescripcion_id, clase, causal,"
                        + " fecha_desde)"
                        + " VALUES (?, ?, 'INTERRUPCION', 'pago parcial de la deuda', ?)",
                muni,
                prescripcionId,
                VIGENCIA);

        ejecutar(
                app,
                "INSERT INTO expediente_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, ?, 1)",
                muni,
                EJERCICIO);
        long expedienteId =
                insertar(
                        app,
                        "INSERT INTO expediente_coactivo (municipalidad_id, numero, ejercicio,"
                                + " correlativo, contribuyente_id, ejecutor, fecha_apertura,"
                                + " usuario_registro, fecha_registro, observacion)"
                                + " VALUES (?, ?, ?, 1, ?, 'ejecutor', ?, 'prueba', now(),"
                                + "         'expediente de prueba')"
                                + " RETURNING id",
                        muni,
                        "EXP-" + sufijo,
                        EJERCICIO,
                        titular,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO expediente_valor (municipalidad_id, expediente_id, valor_id,"
                        + " fecha_importacion)"
                        + " VALUES (?, ?, ?, ?)",
                muni,
                expedienteId,
                valorId,
                VIGENCIA);
        // La apertura del expediente: sin ella su estado no se puede derivar (V33, #40).
        ejecutar(
                app,
                "INSERT INTO expediente_movimiento (municipalidad_id, expediente_id, tipo, estado,"
                        + " fecha, motivo, usuario_registro, fecha_registro, observacion)"
                        + " VALUES (?, ?, 'APERTURA', 'INICIADO', ?, 'importacion de prueba',"
                        + "         'prueba', now(), 'apertura de prueba')",
                muni,
                expedienteId,
                VIGENCIA);
        // El acto coactivo se materializa en un documento emitido (V34, #41): el numero del acto
        // ES el del documento, y sin la fila del documento el acto no se puede reimprimir. Por eso
        // la siembra crea las dos, en ese orden.
        long documentoDeLaRec =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'REC1', ?, 2026, ?, CAST(? AS jsonb), 'PDF',"
                                + "         repeat('b', 64), ?, 'siembra',"
                                + "         'REC de prueba') RETURNING id",
                        muni,
                        "REC1-2026-" + sufijo,
                        "EXP-" + sufijo,
                        "{\"titulo\":\"RESOLUCION DE EJECUCION COACTIVA\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        VIGENCIA);
        long actoDeLaRec =
                insertar(
                        app,
                        "INSERT INTO acto_coactivo (municipalidad_id, expediente_id, tipo, numero,"
                                + " fecha, descripcion, documento_id, usuario_registro,"
                                + " fecha_registro, observacion)"
                                + " VALUES (?, ?, 'REC1', ?, ?, 'Resolucion de ejecucion"
                                + " coactiva', ?, 'prueba', now(), 'REC de prueba')"
                                + " RETURNING id",
                        muni,
                        expedienteId,
                        "REC1-2026-" + sufijo,
                        VIGENCIA,
                        documentoDeLaRec);
        // La costa cuelga de una liquidacion y tarifa UN acto (V35, #42): antes colgaba del
        // expediente y nada mas, y asi «la liquidacion 000123» no tenia sujeto.
        ejecutar(
                app,
                "INSERT INTO liquidacion_costas_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, ?, 1)",
                muni,
                EJERCICIO);
        long liquidacionId =
                insertar(
                        app,
                        "INSERT INTO liquidacion_costas (municipalidad_id, numero, ejercicio,"
                                + " correlativo, expediente_id, contribuyente_id, tributo, fecha,"
                                + " conjunto_id, total, usuario_registro, fecha_registro,"
                                + " observacion)"
                                + " VALUES (?, ?, ?, 1, ?, ?, 'COSTAS PROCESALES', ?, ?, ?,"
                                + "         'prueba', now(), 'liquidacion de prueba')"
                                + " RETURNING id",
                        muni,
                        "LC-" + sufijo,
                        EJERCICIO,
                        expedienteId,
                        titular,
                        VIGENCIA,
                        conjuntoId,
                        CIEN);
        ejecutar(
                app,
                "INSERT INTO costa_procesal (municipalidad_id, liquidacion_id, expediente_id,"
                        + " acto_id, acto_tipo, concepto, tributo, monto, fecha, arancel_fuente,"
                        + " arancel_conjunto_id)"
                        + " VALUES (?, ?, ?, ?, 'REC1', 'Notificacion', 'COSTAS PROCESALES', ?, ?,"
                        + "         'fixture de la prueba', ?)",
                muni,
                liquidacionId,
                expedienteId,
                actoDeLaRec,
                CIEN,
                VIGENCIA,
                conjuntoId);
        // Y la obligacion de costas queda reclamada por este expediente (V35 §3): sin la fila, dos
        // expedientes del mismo obligado compartirian obligacion sin que nada fallara.
        ejecutar(
                app,
                "INSERT INTO costa_obligacion (municipalidad_id, contribuyente_id, tributo,"
                        + " ejercicio, expediente_id)"
                        + " VALUES (?, ?, 'COSTAS PROCESALES', ?, ?)"
                        + " ON CONFLICT DO NOTHING",
                muni,
                titular,
                EJERCICIO,
                expedienteId);
        return valorId;
    }

    // ------------------------------------------------------------------
    // Sanciones y fiscalizacion
    // ------------------------------------------------------------------

    private static void sembrarSanciones(
            Connection app,
            long muni,
            String sufijo,
            long titular,
            long segundo,
            long vehiculoId,
            long predioId,
            long conjuntoId,
            long valorId)
            throws SQLException {
        long codigoId =
                insertar(
                        app,
                        "INSERT INTO codigo_infraccion (municipalidad_id, familia, codigo,"
                                + " descripcion, porcentaje_uit, base_legal, vigencia_desde)"
                                + " VALUES (?, 'TRANSITO', ?, 'Infraccion de prueba', 1.0000,"
                                + "         'fixture de la prueba', ?) RETURNING id",
                        muni,
                        "G-" + sufijo,
                        VIGENCIA);
        long notificacionId =
                insertar(
                        app,
                        "INSERT INTO notificacion_administrativa (municipalidad_id, numero, fecha,"
                                + " contribuyente_id, predio_id, direccion, motivo,"
                                + " usuario_registro)"
                                + " VALUES (?, ?, ?, ?, ?, 'Jr. Union', 'motivo de prueba',"
                                + "         'prueba') RETURNING id",
                        muni,
                        "NA-" + sufijo,
                        VIGENCIA,
                        titular,
                        predioId);
        long papeletaId =
                insertar(
                        app,
                        "INSERT INTO papeleta (municipalidad_id, familia, numero,"
                                + " codigo_infraccion_id, fecha_infraccion, lugar, placa, vehiculo_id,"
                                + " infractor_id, propietario_id, obligado_id, base_imponible,"
                                + " porcentaje_infraccion, importe_infraccion, porcentaje_a_cobrar,"
                                + " importe_a_pagar, usuario_registro, observacion)"
                                + " VALUES (?, 'TRANSITO', ?, ?, ?, 'Av. Grau', ?, ?, ?, ?, ?, ?,"
                                + "         8.0000, ?, 100.0000, ?, 'prueba', 'papeleta de prueba')"
                                + " RETURNING id",
                        muni,
                        "PT-" + sufijo,
                        codigoId,
                        VIGENCIA,
                        "ABC-" + numeroDePlaca(sufijo),
                        vehiculoId,
                        segundo,
                        titular,
                        // El obligado: a quien se le asienta el cargo de la multa (V41 §1). Aqui,
                        // el propietario del vehiculo, que es el caso corriente.
                        titular,
                        MIL,
                        CIEN,
                        CIEN);
        ejecutar(
                app,
                "INSERT INTO papeleta_cambio_numero (municipalidad_id, papeleta_id, numero_anterior,"
                        + " numero_nuevo, usuario, motivo)"
                        + " VALUES (?, ?, ?, ?, 'prueba', 'correccion de prueba')",
                muni,
                papeletaId,
                "PT-VIEJO-" + sufijo,
                "PT-" + sufijo);
        // El descargo lleva el dia hasta el que era admisible y el conjunto sellado del que salio
        // ese plazo (V41 §2): releerlo daria otra fecha el dia que el plazo cambie (ARQ-09 §3).
        ejecutar(
                app,
                "INSERT INTO descargo (municipalidad_id, papeleta_id, numero_expediente, fecha,"
                        + " tipo_recurso, sustento, presentado_hasta, conjunto_id, en_plazo,"
                        + " fecha_registro, usuario_registro, observacion)"
                        + " VALUES (?, ?, ?, ?, 'DESCARGO', 'sustento de prueba', ?, ?, true,"
                        + "         now(), 'prueba', 'descargo de prueba')",
                muni,
                papeletaId,
                "EXP-DES-" + sufijo,
                VIGENCIA,
                VIGENCIA.plusDays(5),
                conjuntoId);

        // El acta de internamiento se materializa en un documento emitido, igual que la REC
        // (V41 §7): sin la fila del documento el acta no se puede reimprimir.
        long documentoDelActa =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'ACTA_INTERNAMIENTO', ?, 2026, ?, CAST(? AS jsonb),"
                                + "         'PDF', repeat('c', 64), ?, 'siembra',"
                                + "         'acta de prueba') RETURNING id",
                        muni,
                        "ACTA_INTERNAMIENTO-2026-" + sufijo,
                        "ABC-" + numeroDePlaca(sufijo),
                        "{\"titulo\":\"Acta de internamiento de vehiculo\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        VIGENCIA);
        long internamientoId =
                insertar(
                        app,
                        "INSERT INTO internamiento (municipalidad_id, papeleta_id, vehiculo_id,"
                                + " placa, deposito, fecha_ingreso, acta, documento_id,"
                                + " tasa_custodia, fecha_registro, usuario_registro, observacion)"
                                + " VALUES (?, ?, ?, ?, 'Deposito municipal', ?, ?, ?, 'CUSTODIA',"
                                + "         now(), 'prueba', 'internamiento de prueba')"
                                + " RETURNING id",
                        muni,
                        papeletaId,
                        vehiculoId,
                        "ABC-" + numeroDePlaca(sufijo),
                        OffsetDateTime.of(VIGENCIA.atStartOfDay(), ZoneOffset.UTC),
                        "ACTA_INTERNAMIENTO-2026-" + sufijo,
                        documentoDelActa);

        // Lo que le pasa al vehiculo despues del ingreso es una fila aparte (V41 §5): el estado
        // del internamiento se DERIVA de aqui, nunca de una columna que habria que actualizar.
        long documentoDelAbandono =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'ACTA_ABANDONO', ?, 2026, ?, CAST(? AS jsonb),"
                                + "         'PDF', repeat('d', 64), ?, 'siembra',"
                                + "         'acta de prueba') RETURNING id",
                        muni,
                        "ACTA_ABANDONO-2026-" + sufijo,
                        "ABC-" + numeroDePlaca(sufijo),
                        "{\"titulo\":\"Declaracion de abandono\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO internamiento_movimiento (municipalidad_id, internamiento_id, tipo,"
                        + " fecha, acta, documento_id, dias_custodia, fecha_registro,"
                        + " usuario_registro, observacion)"
                        + " VALUES (?, ?, 'ABANDONO', ?, ?, ?, 30, now(), 'prueba',"
                        + "         'abandono de prueba')",
                muni,
                internamientoId,
                VIGENCIA,
                "ACTA_ABANDONO-2026-" + sufijo,
                documentoDelAbandono);

        // La resolucion de gerencia se materializa en su documento, igual que la REC (V41 §7).
        long documentoDeLaResolucion =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'RGO', ?, 2026, ?, CAST(? AS jsonb), 'PDF',"
                                + "         repeat('e', 64), ?, 'siembra',"
                                + "         'resolucion de prueba') RETURNING id",
                        muni,
                        "RGO-2026-" + sufijo,
                        "PT-" + sufijo,
                        "{\"titulo\":\"Resolucion de gerencia ordinaria\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO resolucion_gerencia (municipalidad_id, papeleta_id, tipo, numero,"
                        + " documento_id, fecha, sustento, fecha_registro, usuario_registro,"
                        + " observacion)"
                        + " VALUES (?, ?, 'ORDINARIA', ?, ?, ?, 'sustento de prueba', now(),"
                        + "         'prueba', 'resolucion de prueba')",
                muni,
                papeletaId,
                "RGO-2026-" + sufijo,
                documentoDeLaResolucion,
                VIGENCIA);

        long programaId =
                insertar(
                        app,
                        "INSERT INTO programa_fiscalizacion (municipalidad_id, codigo, descripcion,"
                                + " tipo, fecha_inicio)"
                                + " VALUES (?, ?, 'Programa de prueba', 'PREDIAL', ?) RETURNING id",
                        muni,
                        "PF-" + sufijo,
                        VIGENCIA);
        // La muestra sorteada del programa (#481): un predio, con la condicion que la deteccion
        // concluyo el dia del sorteo. La lleva `programa_muestra`, que es de tenant y por eso la
        // prueba de aislamiento le exige RLS sola.
        insertar(
                app,
                "INSERT INTO programa_muestra (municipalidad_id, programa_id, predio_id,"
                        + " cod_ref_catastral, contribuyente_id, condicion, area_catastral,"
                        + " area_declarada, sector_codigo, fecha_sorteo, observacion,"
                        + " usuario_registro, fecha_registro)"
                        + " VALUES (?, ?, ?, (SELECT codigo_ref_catastral FROM predio_de_prueba WHERE id = ?),"
                        + "         ?, 'OMISO', 300.00, 120.00, NULL, ?, 'muestra de prueba',"
                        + "         'prueba', now()) RETURNING id",
                muni,
                programaId,
                predioId,
                predioId,
                titular,
                VIGENCIA);
        long actaId =
                insertar(
                        app,
                        "INSERT INTO acta_fiscalizacion (municipalidad_id, programa_id, version,"
                                + " contribuyente_id, predio_id, fecha_visita, fiscalizador,"
                                + " hallazgo, observacion, usuario_registro)"
                                + " VALUES (?, ?, 1, ?, ?, ?, 'fiscalizador', 'CONFORME',"
                                + "         'acta de prueba', 'prueba') RETURNING id",
                        muni,
                        programaId,
                        titular,
                        predioId,
                        VIGENCIA);

        // La liquidacion del acta (#49): cabecera, contraste y apertura. Sin importes —son D-02a,
        // #198—, con el conjunto SELLADO del ejercicio copiado en la linea.
        long liquidacionId =
                insertar(
                        app,
                        "INSERT INTO liquidacion_fiscalizacion (municipalidad_id, numero,"
                                + " ejercicio, correlativo, acta_id, version, ejercicio_desde,"
                                + " ejercicio_hasta, tipo_fiscalizacion, motivo_determinante,"
                                + " fecha, usuario_registro, fecha_registro, observacion)"
                                + " VALUES (?, ?, 2026, 1, ?, 1, 2026, 2026, 'CIERTA',"
                                + "         'motivo de prueba', ?, 'prueba', now(),"
                                + "         'liquidacion de prueba') RETURNING id",
                        muni,
                        "LIQ-" + sufijo,
                        actaId,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO liquidacion_detalle (municipalidad_id, liquidacion_id, ejercicio,"
                        + " conjunto_id, predio_id, condicion, area_declarada, area_hallada)"
                        + " VALUES (?, ?, 2026, ?, ?, 'CONFORME', 120.00, 120.00)",
                muni,
                liquidacionId,
                conjuntoId,
                predioId);
        ejecutar(
                app,
                "INSERT INTO liquidacion_movimiento (municipalidad_id, liquidacion_id, tipo,"
                        + " estado, fecha, motivo, usuario_registro, fecha_registro, observacion)"
                        + " VALUES (?, ?, 'APERTURA', 'ABIERTA', ?, 'emitida', 'prueba', now(),"
                        + "         'apertura de prueba')",
                muni,
                liquidacionId,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO liquidacion_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, 2026, 1)",
                muni);

        // La transferencia a rentas de esa liquidacion (#52, V49). Se siembra COMO OCURRE de
        // verdad, y no con dos filas cualesquiera: se cierra la version vigente de la ficha
        // unica, se abre la siguiente con `origen = FISCALIZACION`, y la resolucion apunta a las
        // dos. Sembrarla de otro modo dejaria la fila cumpliendo sus CHECK y describiendo un
        // padron imposible —dos versiones abiertas del mismo predio, o una transferencia predial
        // sin ficha nueva—, y la prueba de aislamiento estaria aislando datos que no existen.
        //
        // Y aun asi lo describia: la version nueva empezaba EL MISMO DIA en que se cerraba la
        // anterior, asi que las dos cubrian ese dia y «la ficha vigente al 1 de enero» devolvia
        // dos filas del mismo predio. Nada podia decirlo —`ficha_vigente_uq` es parcial y solo
        // mira las abiertas— hasta que `ficha_vigencias_no_se_pisan` (V72, #669) lo rechazo en la
        // primera corrida. La version nueva empieza al dia SIGUIENTE, que es lo que
        // `ActualizarFichaCatastral` hace en produccion (cierra la anterior el dia antes).
        long fichaAnterior =
                insertar(
                        app,
                        "UPDATE ficha_catastral_de_prueba SET vigencia_hasta = ?"
                                + " WHERE predio_id = ? AND tipo = 'UNICA'"
                                + "   AND vigencia_hasta IS NULL RETURNING id",
                        VIGENCIA,
                        predioId);
        long fichaNueva =
                insertar(
                        app,
                        "INSERT INTO ficha_catastral_de_prueba (municipalidad_id, predio_id, tipo, version,"
                                + " area_terreno, uso, vigencia_desde, origen, documento_origen,"
                                + " observacion, usuario_registro)"
                                + " VALUES (?, ?, 'UNICA', 2, 300.00, 'CASA_HABITACION', ?,"
                                + "         'FISCALIZACION', ?, 'version por fiscalizacion de"
                                + " prueba', 'prueba') RETURNING id",
                        muni,
                        predioId,
                        VIGENCIA.plusDays(1),
                        "LIQ-" + sufijo);
        long documentoDeLaDeterminacion =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'RDF', ?, 2026, ?, CAST(? AS jsonb), 'PDF',"
                                + "         repeat('f', 64), ?, 'siembra',"
                                + "         'resolucion de determinacion de prueba') RETURNING id",
                        muni,
                        "RDF-2026-" + sufijo,
                        "LIQ-" + sufijo,
                        "{\"titulo\":\"Resolucion de determinacion\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO resolucion_determinacion (municipalidad_id, numero, documento_id,"
                        + " liquidacion_id, contribuyente_id, predio_id, ficha_anterior_id,"
                        + " ficha_nueva_id, fecha, documento_sustento, sustento, base_legal,"
                        + " usuario_registro, fecha_registro, observacion)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'sustento de prueba',"
                        + "         'Codigo Tributario, arts. 76 y 77', 'prueba', now(),"
                        + "         'transferencia de prueba')",
                muni,
                "RDF-2026-" + sufijo,
                documentoDeLaDeterminacion,
                liquidacionId,
                titular,
                predioId,
                fichaAnterior,
                fichaNueva,
                VIGENCIA,
                "ACTA-" + sufijo);

        // #53 — la corrida masiva de valores por papeletas, con su unico candidato ya resuelto,
        // y la constancia libre de infracciones. Las tres tablas son de V47 y llevan
        // `municipalidad_id NOT NULL`: sin filas, la prueba de aislamiento no tendria nada que
        // aislar en ellas.
        long corridaId =
                insertar(
                        app,
                        "INSERT INTO papeleta_masivo (municipalidad_id, familia, desde, hasta,"
                                + " fecha_criterio, origen, total_candidatos, usuario_registro,"
                                + " fecha_registro, observacion)"
                                + " VALUES (?, 'TRANSITO', ?, ?, ?, 'RANGO', 1, 'prueba', now(),"
                                + "         'corrida de papeletas de prueba') RETURNING id",
                        muni,
                        VIGENCIA,
                        VIGENCIA,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO papeleta_masivo_item (municipalidad_id, corrida_id, papeleta_id,"
                        + " estado, valor_id, valor_numero, fecha_procesado)"
                        + " VALUES (?, ?, ?, 'GENERADO', ?, ?, now())",
                muni,
                corridaId,
                papeletaId,
                valorId,
                "OP-" + sufijo);

        long documentoDeLaConstancia =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'CLI', ?, 2026, ?, CAST(? AS jsonb), 'PDF',"
                                + "         repeat('a', 64), ?, 'siembra',"
                                + "         'constancia de prueba') RETURNING id",
                        muni,
                        "CLI-2026-" + sufijo,
                        "ABC-" + numeroDePlaca(sufijo),
                        "{\"titulo\":\"Constancia libre de infracciones\",\"subtitulo\":null,"
                                + "\"aLaFecha\":\"2026-01-01\",\"cabecera\":[],\"tablas\":[],"
                                + "\"pie\":[],\"duplicado\":null}",
                        VIGENCIA);
        ejecutar(
                app,
                // Sin `vehiculo_id` y con otra placa: la constancia se emite para un vehiculo
                // SIN papeleta pendiente, y el de esta siembra tiene una. Un vehiculo que no
                // esta en el padron tambien puede pedirla, y por eso la columna es opcional.
                "INSERT INTO constancia_libre (municipalidad_id, numero, documento_id, placa,"
                        + " vehiculo_id, solicitante_id, verificada_al, fecha_emision,"
                        + " usuario_registro, fecha_registro, observacion)"
                        + " VALUES (?, ?, ?, ?, NULL, ?, ?, ?, 'prueba', now(),"
                        + "         'constancia de prueba')",
                muni,
                "CLI-2026-" + sufijo,
                documentoDeLaConstancia,
                "XYZ-" + numeroDePlaca(sufijo),
                segundo,
                VIGENCIA,
                VIGENCIA);

        if (notificacionId <= 0) {
            throw new IllegalStateException("No se sembro la notificacion administrativa");
        }
    }

    // ------------------------------------------------------------------
    // Licencias
    // ------------------------------------------------------------------

    private static void sembrarLicencias(
            Connection app, long muni, String sufijo, long titular, long predioId, long reciboId)
            throws SQLException {
        // V37 (#44) le puso a `ciiu` su traza —quien lo agrego, por que y cuando— y a la
        // licencia y su duplicado el documento que los materializa. La siembra los rellena: son
        // columnas NOT NULL, y una siembra que las esquivara dejaria la prueba de aislamiento sin
        // filas de licencias que aislar.
        long ciiuId =
                insertar(
                        app,
                        "INSERT INTO ciiu (municipalidad_id, codigo, descripcion, seccion,"
                                + " riesgo_itse, requiere_sectorial, usuario_registro,"
                                + " observacion, fecha_registro)"
                                + " VALUES (?, ?, 'Actividad de prueba', 'G', 'BAJO', false,"
                                + "         'prueba', 'giro de prueba', ?) RETURNING id",
                        muni,
                        "4711-" + sufijo,
                        VIGENCIA);
        long documentoDeLaLicencia =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'LICENCIA_FUNCIONAMIENTO', ?, 2026, ?,"
                                + "         CAST(? AS jsonb), 'PDF', repeat('c', 64), ?,"
                                + "         'siembra', 'licencia de prueba') RETURNING id",
                        muni,
                        "LICENCIA_FUNCIONAMIENTO-2026-" + sufijo,
                        "LF-" + sufijo,
                        MODELO_DE_DOCUMENTO,
                        VIGENCIA);
        long documentoDelDuplicado =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'RES_DUPLICADO_LICENCIA', ?, 2026, ?,"
                                + "         CAST(? AS jsonb), 'PDF', repeat('d', 64), ?,"
                                + "         'siembra', 'resolucion de duplicado') RETURNING id",
                        muni,
                        "RES_DUPLICADO_LICENCIA-2026-" + sufijo,
                        "LF-" + sufijo,
                        MODELO_DE_DOCUMENTO,
                        VIGENCIA);
        long licenciaId =
                insertar(
                        app,
                        "INSERT INTO licencia_funcionamiento (municipalidad_id, numero,"
                                + " contribuyente_id, predio_id, nombre_comercial, direccion,"
                                + " area_solicitada, tipo_licencia, fecha_emision, recibo_id,"
                                + " documento_id, fecha_registro, usuario_registro, observacion)"
                                + " VALUES (?, ?, ?, ?, 'Bodega de prueba', 'Jr. Union', 40.00,"
                                + "         'DEFINITIVA', ?, ?, ?, ?, 'prueba',"
                                + "         'licencia de prueba')"
                                + " RETURNING id",
                        muni,
                        "LF-" + sufijo,
                        titular,
                        predioId,
                        VIGENCIA,
                        reciboId,
                        documentoDeLaLicencia,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO licencia_giro (municipalidad_id, licencia_id, ciiu_id, principal)"
                        + " VALUES (?, ?, ?, true)",
                muni,
                licenciaId,
                ciiuId);
        ejecutar(
                app,
                "INSERT INTO licencia_duplicado (municipalidad_id, licencia_id, numero, fecha,"
                        + " motivo, recibo_id, documento_id, reimpresion, usuario_registro,"
                        + " fecha_registro, observacion)"
                        + " VALUES (?, ?, 1, ?, 'deterioro del original', ?, ?, 1, 'prueba', ?,"
                        + "         'duplicado de prueba')",
                muni,
                licenciaId,
                VIGENCIA,
                reciboId,
                documentoDelDuplicado,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO licencia_movimiento (municipalidad_id, licencia_id, tipo, fecha,"
                        + " documento_id, documento_numero, usuario_registro, fecha_registro,"
                        + " observacion)"
                        + " VALUES (?, ?, 'EMISION', ?, ?, ?, 'prueba', ?,"
                        + "         'emision de prueba')",
                muni,
                licenciaId,
                VIGENCIA,
                documentoDeLaLicencia,
                "LICENCIA_FUNCIONAMIENTO-2026-" + sufijo,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO licencia_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, 2026, 1)",
                muni);
        // El FUE, con la forma que V43 le dio: el expediente identifica el tramite mientras no hay
        // licencia, el estado se deriva y NO hay columna de valor de obra —la valorizacion se
        // calcula contra el cuadro de #17 y guardarla aqui la duplicaria (#48 AC 2)—.
        long fueId =
                insertar(
                        app,
                        "INSERT INTO licencia_edificacion (municipalidad_id, expediente,"
                                + " fecha_declaracion, contribuyente_id, predio_id, tipo_tramite,"
                                + " tipo_obra, modalidad, solicitante_propietario,"
                                + " usuario_registro, fecha_registro, observacion)"
                                + " VALUES (?, ?, ?, ?, ?, 'LICENCIA_DE_OBRA',"
                                + "         'EDIFICACION_NUEVA', 'A', true, 'prueba', now(),"
                                + "         'expediente de edificacion de prueba')"
                                + " RETURNING id",
                        muni,
                        "EXP-LE-" + sufijo,
                        VIGENCIA,
                        titular,
                        predioId);
        ejecutar(
                app,
                "INSERT INTO edificacion_terreno (municipalidad_id, fue_id, version, direccion,"
                        + " manzana, lote, area_terreno, zonificacion, usuario_registro,"
                        + " fecha_registro, observacion)"
                        + " VALUES (?, ?, 1, 'Jr. Union 100', 'A', '3', 200.00, 'RDM', 'prueba',"
                        + "         now(), 'terreno de prueba')",
                muni,
                fueId);
        ejecutar(
                app,
                "INSERT INTO edificacion_proyecto (municipalidad_id, fue_id, version, uso,"
                        + " numero_pisos, area_techada, usuario_registro, fecha_registro,"
                        + " observacion)"
                        + " VALUES (?, ?, 1, 'VIVIENDA UNIFAMILIAR', 2, 160.00, 'prueba', now(),"
                        + "         'proyecto de prueba')",
                muni,
                fueId);
        // La valorizacion, SIN importe: solo partida, categoria y area. Cuanto vale la letra lo
        // dice valor_unitario_edificacion, y solo ahi (#48 AC 2).
        ejecutar(
                app,
                "INSERT INTO edificacion_estructura (municipalidad_id, fue_id, version, piso,"
                        + " partida, categoria, area)"
                        + " VALUES (?, ?, 1, 1, 'MUROS', 'A', 40.00)",
                muni,
                fueId);
        ejecutar(
                app,
                "INSERT INTO edificacion_profesional (municipalidad_id, fue_id, version, tipo,"
                        + " nombre, colegio, colegiatura)"
                        + " VALUES (?, ?, 1, 'RESPONSABLE_OBRA', 'ROJAS, JULIO', 'CIP', '67890')",
                muni,
                fueId);
        ejecutar(
                app,
                "INSERT INTO edificacion_requisito (municipalidad_id, fue_id, version, requisito,"
                        + " presentado, folios)"
                        + " VALUES (?, ?, 1, 'FUE FIRMADO POR EL SOLICITANTE', true, 2)",
                muni,
                fueId);
        long movimientoDelFue =
                insertar(
                        app,
                        "INSERT INTO edificacion_movimiento (municipalidad_id, fue_id, tipo, fecha,"
                                + " numero_licencia, recibo_id, documento_id, documento_numero,"
                                + " usuario_registro, fecha_registro, observacion)"
                                + " VALUES (?, ?, 'EMISION', ?, ?, ?, ?, ?, 'prueba', now(),"
                                + "         'emision de edificacion de prueba')"
                                + " RETURNING id",
                        muni,
                        fueId,
                        VIGENCIA,
                        "LE-" + sufijo,
                        reciboId,
                        documentoDeLaLicencia,
                        "LICENCIA_EDIFICACION-2026-" + sufijo);
        ejecutar(
                app,
                "INSERT INTO edificacion_vigencia (municipalidad_id, licencia_id, movimiento_id,"
                        + " orden, desde, hasta)"
                        + " VALUES (?, ?, ?, 1, ?, ?)",
                muni,
                fueId,
                movimientoDelFue,
                VIGENCIA,
                VIGENCIA.plusYears(3));
        ejecutar(
                app,
                "INSERT INTO edificacion_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, 2026, 1)",
                muni);
        // V45 (#51) le puso a `anuncio` su clase -de la que sale la tasa-, su establecimiento, su
        // traza y su clave de idempotencia, y le quito la columna `estado`, que ahora se deriva de
        // `anuncio_movimiento`. La siembra rellena las NOT NULL nuevas: sin ellas, la prueba de
        // aislamiento no tendria filas de anuncios que aislar.
        long anuncioId =
                insertar(
                        app,
                        "INSERT INTO anuncio (municipalidad_id, numero, contribuyente_id,"
                                + " predio_id, licencia_id, clase, tipo, emplazamiento, forma,"
                                + " denominacion, ubicacion, area, lados, cantidad,"
                                + " fecha_autorizacion, vigencia_hasta, expediente,"
                                + " clave_idempotencia, usuario_registro, fecha_registro,"
                                + " observacion)"
                                + " VALUES (?, ?, ?, ?, ?, 'PANEL', 'AVISO_LUMINOSO',"
                                + "         'FACHADA', 'ADOSADO', 'BODEGA SAN MARTIN',"
                                + "         'AV. GRAU 100', 6.00, 2, 1, ?, ?, ?, ?, 'prueba', ?,"
                                + "         'anuncio de prueba') RETURNING id",
                        muni,
                        "AN-" + sufijo,
                        titular,
                        predioId,
                        licenciaId,
                        VIGENCIA,
                        VIGENCIA,
                        "EXPA-" + sufijo,
                        "idem-anuncio-" + sufijo,
                        VIGENCIA);
        // El acto que la creo, con la referencia del cargo que pidio al libro. Es la fila que
        // `anuncio_movimiento_cargo_uq` protege, y el importe va copiado del acto -no es una cifra
        // normativa sembrada, es lo que se asento aquel dia-.
        ejecutar(
                app,
                "INSERT INTO anuncio_movimiento (municipalidad_id, anuncio_id, tipo, fecha,"
                        + " ejercicio, referencia_cargo, tasa, vigencia_hasta, usuario_registro,"
                        + " fecha_registro, observacion)"
                        + " VALUES (?, ?, 'AUTORIZACION', ?, 2026, ?, 90.00, ?, 'prueba', ?,"
                        + "         'autorizacion de prueba')",
                muni,
                anuncioId,
                VIGENCIA,
                "ANUNCIO-AN-" + sufijo + "-2026",
                VIGENCIA,
                VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO anuncio_correlativo (municipalidad_id, ejercicio, ultimo)"
                        + " VALUES (?, 2026, 1)",
                muni);
        // V51 (#54): el certificado de numeracion y zonificacion, con su papel, su vigencia
        // copiada y el derecho que su recibo cobro. Se siembra para que la prueba de aislamiento
        // tenga filas de certificados que aislar; sin ella la tabla estaria vacia y la comprobacion
        // de RLS pasaria sin comparar nada.
        //
        // El importe del derecho NO es una cifra normativa sembrada: es lo que un recibo cobro
        // aquel dia, igual que `anuncio_movimiento.tasa`.
        long documentoDelCertificado =
                insertar(
                        app,
                        "INSERT INTO documento_emitido (municipalidad_id, tipo, numero, ejercicio,"
                                + " referencia, datos, formato, resumen, fecha_emision,"
                                + " usuario_emision, observacion)"
                                + " VALUES (?, 'CERTIFICADO', ?, 2026, ?,"
                                + "         CAST(? AS jsonb), 'PDF', repeat('e', 64), ?,"
                                + "         'siembra', 'certificado de prueba') RETURNING id",
                        muni,
                        "CERTIFICADO-2026-" + sufijo,
                        "CN-" + sufijo,
                        MODELO_DE_DOCUMENTO,
                        VIGENCIA);
        ejecutar(
                app,
                "INSERT INTO certificado (municipalidad_id, numero, tipo, predio_id,"
                        + " contribuyente_id, codigo_predial, direccion, expediente,"
                        + " fecha_emision, vigencia_hasta, recibo_id, derecho, derecho_a,"
                        + " documento_id, documento_numero, zonificacion, altura_maxima,"
                        + " clave_idempotencia, usuario_registro, fecha_registro, observacion)"
                        + " SELECT ?, ?, 'ZONIFICACION_VIAS', p.id, ?, p.codigo_ref_catastral,"
                        + "        p.direccion, ?, ?, ?, ?, 35.00, ?, ?, ?, 'RDM', '3 pisos', ?,"
                        + "        'prueba', ?, 'certificado de prueba'"
                        + "   FROM predio_de_prueba p WHERE p.municipalidad_id = ? AND p.id = ?",
                muni,
                "CN-" + sufijo,
                titular,
                "EXPC-" + sufijo,
                VIGENCIA,
                VIGENCIA.plusYears(3),
                reciboId,
                VIGENCIA,
                documentoDelCertificado,
                "CERTIFICADO-2026-" + sufijo,
                "idem-certificado-" + sufijo,
                VIGENCIA,
                muni,
                predioId);
        ejecutar(
                app,
                "INSERT INTO certificado_correlativo (municipalidad_id, tipo, ejercicio, ultimo)"
                        + " VALUES (?, 'ZONIFICACION_VIAS', 2026, 1)",
                muni);
    }

    // ------------------------------------------------------------------
    // Seguridad y auditoria
    // ------------------------------------------------------------------

    private static void sembrarSeguridad(Connection app, long muni, String sufijo)
            throws SQLException {
        long moduloId =
                insertar(
                        app,
                        "INSERT INTO modulo_sistema (municipalidad_id, codigo, nombre)"
                                + " VALUES (?, ?, 'Rentas') RETURNING id",
                        muni,
                        "MOD-" + sufijo);
        long accesoId =
                insertar(
                        app,
                        "INSERT INTO acceso (municipalidad_id, modulo_id, tipo, codigo, nombre)"
                                + " VALUES (?, ?, 'OPCION_MENU', ?, 'Contribuyentes') RETURNING id",
                        muni,
                        moduloId,
                        "contribuyentes-" + sufijo);
        long grupoId =
                insertar(
                        app,
                        "INSERT INTO grupo (municipalidad_id, nombre, descripcion)"
                                + " VALUES (?, ?, 'Grupo de prueba') RETURNING id",
                        muni,
                        "Cajeros " + sufijo);
        long usuarioId =
                insertar(
                        app,
                        "INSERT INTO usuario (municipalidad_id, cuenta, nombre)"
                                + " VALUES (?, ?, 'Usuario de prueba') RETURNING id",
                        muni,
                        "usuario-" + sufijo);
        ejecutar(
                app,
                "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id, usuario_alta)"
                        + " VALUES (?, ?, ?, 'prueba')",
                muni,
                grupoId,
                usuarioId);
        ejecutar(
                app,
                "INSERT INTO permiso (municipalidad_id, acceso_id, grupo_id, lectura, registro,"
                        + " usuario_registro) VALUES (?, ?, ?, true, true, 'prueba')",
                muni,
                accesoId,
                grupoId);
        ejecutar(
                app,
                "INSERT INTO sesion (municipalidad_id, usuario_id, origen_equipo, origen_ip,"
                        + " ejercicio_trabajo)"
                        + " VALUES (?, ?, 'PC-PRUEBA', CAST(? AS inet), ?)",
                muni,
                usuarioId,
                "10.0.0.1",
                EJERCICIO);
        ejecutar(
                app,
                "INSERT INTO auditoria (municipalidad_id, ejercicio, tabla, clave, operacion,"
                        + " usuario_id, origen_equipo, origen_ip, observacion)"
                        + " VALUES (?, ?, 'contribuyente', '1', 'ALTA', 'prueba', 'PC-PRUEBA',"
                        + "         CAST(? AS inet), 'alta inicial de la prueba de aislamiento')",
                muni,
                EJERCICIO,
                "10.0.0.1");
    }

    /** Identificador del contribuyente titular sembrado en una municipalidad. */
    public static long contribuyenteDe(BaseDeDatosDePrueba base, long municipalidadId)
            throws SQLException {
        try (Connection admin = base.conexionAdmin();
                PreparedStatement sentencia =
                        admin.prepareStatement(
                                "SELECT id FROM contribuyente WHERE municipalidad_id = ?"
                                        + " ORDER BY id LIMIT 1")) {
            sentencia.setLong(1, municipalidadId);
            return unicoLong(sentencia);
        }
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static long insertar(Connection conexion, String sql, Object... valores)
            throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            fijar(sentencia, valores);
            return unicoLong(sentencia);
        }
    }

    /**
     * Anota un evento en el buzon y devuelve su identificador, para que las filas que produzca lo
     * lleven (`V9`).
     *
     * <p>El uuid se genera en Java y no con {@code gen_random_uuid()} dentro del {@code INSERT}
     * porque hace falta a los dos lados: en la fila del buzon y en la fila proyectada que lo
     * referencia. Con el uuid del motor no habria forma de escribir la segunda sin volver a leerlo,
     * y una procedencia que hay que ir a buscar no es la misma que una que se escribe.
     */
    private static String evento(
            Connection ingestor, long muni, String tipo, Long predioId, long secuencia)
            throws SQLException {
        String eventoId = UUID.randomUUID().toString();
        ejecutar(
                ingestor,
                "INSERT INTO catastro_evento_aplicado (municipalidad_id, evento_id, secuencia,"
                        + " tipo, predio_id, aplicado_en, huella)"
                        + " VALUES (?, CAST(? AS uuid), ?, ?, ?, now(), ?)",
                muni,
                eventoId,
                secuencia,
                tipo,
                predioId,
                huellaDe(eventoId));
        return eventoId;
    }

    /**
     * La huella del cuerpo del evento.
     *
     * <p><b>Este fixture no la recibe: la fabrica</b>, y hay que decirlo. En produccion la emite
     * {@code catastro} sobre el cuerpo que mando, y `V9` dice que no se recalcula aqui. Lo que esta
     * siembra puede sostener es la FORMA —64 caracteres hexadecimales, no nula— y no que el valor
     * signifique nada.
     */
    private static String huellaDe(String semilla) {
        try {
            byte[] resumen =
                    MessageDigest.getInstance("SHA-256")
                            .digest(semilla.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : resumen) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException imposible) {
            throw new IllegalStateException("SHA-256 es obligatorio en toda JVM", imposible);
        }
    }

    private static void ejecutar(Connection conexion, String sql, Object... valores)
            throws SQLException {
        try (PreparedStatement sentencia = conexion.prepareStatement(sql)) {
            fijar(sentencia, valores);
            sentencia.executeUpdate();
        }
    }

    private static void fijar(PreparedStatement sentencia, Object... valores) throws SQLException {
        for (int i = 0; i < valores.length; i++) {
            sentencia.setObject(i + 1, valores[i]);
        }
    }

    private static long unicoLong(PreparedStatement sentencia) throws SQLException {
        try (ResultSet resultado = sentencia.executeQuery()) {
            if (!resultado.next()) {
                throw new IllegalStateException("La sentencia no devolvio ninguna fila");
            }
            return resultado.getLong(1);
        }
    }
}

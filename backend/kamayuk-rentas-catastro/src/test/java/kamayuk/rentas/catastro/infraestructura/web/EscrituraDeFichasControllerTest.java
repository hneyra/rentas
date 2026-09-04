package kamayuk.rentas.catastro.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.aplicacion.ActualizarCatastro;
import kamayuk.rentas.catastro.aplicacion.ActualizarFichaCatastral;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeFichas;
import kamayuk.rentas.catastro.aplicacion.ConsultaDeLaFichaVigente;
import kamayuk.rentas.catastro.aplicacion.ConsultaDePredios;
import kamayuk.rentas.catastro.aplicacion.InscribirFicha;
import kamayuk.rentas.catastro.aplicacion.RegistrarPredio;
import kamayuk.rentas.catastro.dominio.ActividadEconomica;
import kamayuk.rentas.catastro.dominio.BienComun;
import kamayuk.rentas.catastro.dominio.CatastroRepository;
import kamayuk.rentas.catastro.dominio.CategoriasConstructivas;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.CriterioDeVia;
import kamayuk.rentas.catastro.dominio.DetalleDeBienesComunes;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.DetalleEconomico;
import kamayuk.rentas.catastro.dominio.DetalleRural;
import kamayuk.rentas.catastro.dominio.EstadoDeConservacion;
import kamayuk.rentas.catastro.dominio.EstadoPredio;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.FichaEncontrada;
import kamayuk.rentas.catastro.dominio.FiltroDeFichas;
import kamayuk.rentas.catastro.dominio.FiltroDePredios;
import kamayuk.rentas.catastro.dominio.Inquilino;
import kamayuk.rentas.catastro.dominio.Manzana;
import kamayuk.rentas.catastro.dominio.ManzanaConConteos;
import kamayuk.rentas.catastro.dominio.MaterialEstructural;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.Predio;
import kamayuk.rentas.catastro.dominio.PredioDelCatastro;
import kamayuk.rentas.catastro.dominio.Riego;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.catastro.dominio.SectorConConteos;
import kamayuk.rentas.catastro.dominio.TierraRural;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.catastro.dominio.TipoPredio;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Titularidad;
import kamayuk.rentas.catastro.dominio.VersionDeLaFicha;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.catastro.dominio.ViaRepository;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.contribuyentes.DirectorioDeContribuyentes;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.CodigoReferenciaCatastral;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Medida;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.web.ConfiguracionDeJson;
import kamayuk.rentas.web.ManejadorDeErrores;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * La escritura de las cuatro fichas por HTTP de verdad: el alta ({@code POST}) y la actualizacion
 * versionada ({@code PUT}).
 *
 * <p>Espejo de {@code SectorControllerTest} y por lo mismo: sin base de datos, con repositorios en
 * memoria. Lo que se verifica aqui es el <b>transporte</b> —forma del JSON, estados HTTP,
 * traduccion de errores, la observacion obligatoria y la semantica trivaluada de las listas—; lo
 * que solo la base puede verificar —el aislamiento, la auditoria escrita en la misma transaccion y
 * los disparadores— tiene sus pruebas en {@code FichasDeTodoTipoTest}, contra PostgreSQL real.
 *
 * <p><b>Con una diferencia deliberada respecto de las otras pruebas de capa web: aqui si hay
 * transacciones.</b> Los tres casos de uso van envueltos en un {@link TransactionInterceptor} sobre
 * un gestor de juguete que cuenta cuantas transacciones fisicas se abren y <b>deshace lo
 * escrito</b> cuando una se revierte. Sin eso, «el predio, la ficha y la titularidad van en una
 * sola transaccion» seria una afirmacion del javadoc que ninguna prueba sostiene: los repositorios
 * en memoria escriben igual con una transaccion que con tres, y la version encadenada —dos casos de
 * uso llamados uno detras de otro desde el controlador— pasaria en verde dejando predios huerfanos.
 */
@DisplayName("Capa web — alta y actualizacion de las cuatro fichas")
class EscrituraDeFichasControllerTest {

    /** 23 posiciones, la plantilla del manual. Los ultimos tres digitos distinguen la unidad. */
    private static final String TRAMO_COMUN = "25010100100100101010";

    private static final String PREDIO_NUEVO = TRAMO_COMUN + "001";
    private static final String PREDIO_CON_FICHA = TRAMO_COMUN + "002";
    private static final String PREDIO_SIN_FICHA = TRAMO_COMUN + "003";
    private static final String PREDIO_DE_BAJA = TRAMO_COMUN + "004";
    private static final String PREDIO_INEXISTENTE = TRAMO_COMUN + "999";

    private static final LocalDate ALTA = LocalDate.of(2026, 1, 1);

    /** La fecha no importa al transporte; se fija para no depender del dia de ejecucion. */
    private final Clock reloj =
            Clock.fixed(Instant.parse("2026-08-27T10:00:00Z"), ZoneId.of("America/Lima"));

    private final PrediosEnMemoria predios = new PrediosEnMemoria();
    private final FichasEnMemoria fichas = new FichasEnMemoria();
    private final ViasEnMemoria vias = new ViasEnMemoria();
    private final PadronEnMemoria padron = new PadronEnMemoria();

    /** Lo que la auditoria recibio, para poder afirmar sobre lo que se asento. */
    private final List<RegistroDeAuditoria> asentado = new ArrayList<>();

    private final Transacciones transacciones = new Transacciones();

    private final RegistrarPredio registrarPredio =
            envolver(new RegistrarPredio(predios, asentado::add, reloj));

    private final ActualizarFichaCatastral actualizarFicha =
            envolver(new ActualizarFichaCatastral(fichas, asentado::add, reloj));

    private final InscribirFicha inscribirFicha =
            envolver(new InscribirFicha(predios, vias, padron, registrarPredio, actualizarFicha));

    private final ActualizarCatastro actualizarCatastro =
            envolver(new ActualizarCatastro(predios, vias, registrarPredio, actualizarFicha));

    private final MockMvc mvc =
            MockMvcBuilders.standaloneSetup(
                            new FichaController(
                                    actualizarFicha,
                                    new ConsultaDeFichas(fichas, padron),
                                    inscribirFicha,
                                    envolver(new ConsultaDeLaFichaVigente(predios, fichas)),
                                    reloj),
                            new ActualizacionController(actualizarCatastro, reloj),
                            new PredioController(
                                    actualizarCatastro,
                                    envolver(new ConsultaDePredios(predios)),
                                    inscribirFicha))
                    .setControllerAdvice(new ManejadorDeErrores())
                    .setMessageConverters(
                            new JacksonJsonHttpMessageConverter(
                                    JsonMapper.builder()
                                            .addModule(
                                                    new ConfiguracionDeJson()
                                                            .moduloDeObjetosDeValor())
                                            .build()))
                    .build();

    /**
     * El predio 002 llega con su ficha urbana ya registrada, con una construccion y una obra
     * complementaria: es lo que permite distinguir «no mande la lista» de «mande la lista vacia».
     *
     * <p>Se siembra sin pasar por el caso de uso para que el contador de transacciones empiece en
     * cero y las pruebas de atomicidad cuenten solo lo que la peticion hizo.
     */
    @BeforeEach
    void sembrar() {
        fichas.sembrar(
                FichaCatastral.primera(
                                predios.idDe(PREDIO_CON_FICHA),
                                TipoFicha.UNICA,
                                AreaM2.de("150.00"),
                                "CASA HABITACION",
                                ALTA,
                                OrigenDeLaFicha.DECLARACION_JURADA,
                                "DJ 200-2026",
                                Observacion.de("Alta de la ficha urbana del predio"))
                        .con(
                                List.of(
                                        Construccion.en(
                                                "1",
                                                AreaM2.de("90.00"),
                                                CategoriasConstructivas.todas('C'))))
                        .conInstalaciones(
                                List.of(
                                        OtraInstalacion.de(
                                                "Cerco perimetrico", Medida.de("30.00", "ML")))));
        asentado.clear();
    }

    @SuppressWarnings("unchecked")
    private <T> T envolver(T objetivo) {
        ProxyFactory fabrica = new ProxyFactory(objetivo);
        fabrica.setProxyTargetClass(true);
        fabrica.addAdvice(
                new TransactionInterceptor(
                        transacciones, new AnnotationTransactionAttributeSource()));
        return (T) fabrica.getProxy();
    }

    // ── El alta ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Alta de ficha (POST): el predio nace en el mismo acto")
    class Alta {

        @Test
        @DisplayName("responde 201, da de alta el predio y su primera version, sin municipalidad")
        void elAltaResponde201() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "direccion":"AV. GRAU 100",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026",
                                                     "construcciones":[
                                                       {"piso":"1","areaConstruida":"90.00",
                                                        "categoriaMuros":"C"}],
                                                     "observacion":"Alta por levantamiento catastral"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo)
                    .contains("\"tipo\":\"UNICA\"")
                    .contains("\"version\":1")
                    .contains("\"vigente\":true")
                    .contains("\"uso\":\"CASA HABITACION\"")
                    .contains("\"piso\":\"1\"");
            assertThat(cuerpo)
                    .as("el identificador de municipalidad no sale ni entra por HTTP (ADR-0005)")
                    .doesNotContain("municipalidad");

            assertThat(predios.porCodigo(PREDIO_NUEVO))
                    .as("sin predio no hay ficha: predio_id es NOT NULL")
                    .isPresent();
            assertThat(fichas.delPredio(PREDIO_NUEVO, predios)).hasSize(1);
        }

        @Test
        @DisplayName("las tres escrituras del acto se asientan con la MISMA observacion")
        void lasTresEscriturasCompartenObservacion() throws Exception {
            mvc.perform(
                            post("/rentas/api/v1/catastro/fichas/urbana")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(altaConTitular("CT-0001")))
                    .andReturn();

            assertThat(asentado).hasSize(3);
            assertThat(asentado.stream().map(RegistroDeAuditoria::tabla))
                    .containsExactly("predio", "ficha_catastral", "titularidad");
            assertThat(asentado.stream().map(RegistroDeAuditoria::operacion))
                    .allMatch(operacion -> operacion == Operacion.ALTA);
            assertThat(asentado.stream().map(registro -> registro.observacion().texto()).distinct())
                    .as("es un acto, no tres: la observacion del usuario es una sola (regla 10)")
                    .hasSize(1);
        }

        @Test
        @DisplayName("el acto entero abre UNA transaccion, no una por escritura")
        void elActoEnteroEsUnaTransaccion() throws Exception {
            mvc.perform(
                            post("/rentas/api/v1/catastro/fichas/urbana")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(altaConTitular("CT-0001")))
                    .andReturn();

            assertThat(transacciones.abiertas)
                    .as(
                            "predio, ficha y titularidad son un acto: encadenar dos casos de uso"
                                    + " desde el controlador dejaria el predio escrito cuando la ficha"
                                    + " falla, y ese predio no se ve en ninguna pantalla")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("un titular que no existe es 404 y NO deja ni el predio ni la ficha")
        void unTitularInexistenteDeshaceElActoEntero() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(altaConTitular("NO-EXISTE")))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"NO_ENCONTRADO\"")
                    .contains("contribuyente")
                    .contains("NO-EXISTE");

            assertThat(predios.porCodigo(PREDIO_NUEVO))
                    .as("el predio se escribio antes que la titularidad, y se deshace con ella")
                    .isEmpty();
            assertThat(fichas.delPredio(PREDIO_CON_FICHA, predios))
                    .as("lo sembrado sigue: lo que se deshace es el acto, no la base")
                    .hasSize(1);
            assertThat(asentado).as("no queda constancia de algo que no paso").isEmpty();
        }

        @Test
        @DisplayName("sobre un predio que ya existe no se crea otro: se usa el que hay")
        void sobreUnPredioExistenteNoSeCreaOtro() throws Exception {
            long yaEstaba = predios.idDe(PREDIO_CON_FICHA);

            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/economica")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010002",
                                                     "direccion":"AV. SANCHEZ CERRO 200",
                                                     "areaTerreno":"150.00",
                                                     "uso":"COMERCIO",
                                                     "documentoOrigen":"DJ 210-2026",
                                                     "economico":{"actividades":[
                                                       {"conductor":"Juan Perez","ciiu":"4711"}]},
                                                     "observacion":"El predio ya fichado abre su ficha economica"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"predioId\":" + yaEstaba)
                    .contains("\"tipo\":\"ECONOMICA\"")
                    .contains("Juan Perez");
            assertThat(predios.todos()).as("un predio, no dos").hasSize(3);
            assertThat(asentado.stream().map(RegistroDeAuditoria::tabla))
                    .as("no hay ALTA de predio: el predio no nacio en este acto")
                    .containsExactly("ficha_catastral");
        }

        @Test
        @DisplayName("un predio que ya tiene ficha de ese tipo es 409, no otra primera version")
        void unSegundoAltaDelMismoTipoEs409() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010002",
                                                     "direccion":"AV. SANCHEZ CERRO 200",
                                                     "areaTerreno":"150.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 201-2026",
                                                     "observacion":"Intento de segunda primera version"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("el estado actual no admite la operacion: lo que toca es actualizarla")
                    .isEqualTo(409);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo)
                    .as(
                            "hay dos motivos de 409 en esta ruta —el predio repetido y la ficha"
                                    + " repetida— y el mensaje tiene que decir cual es: sin la guarda"
                                    + " de YaTieneFicha el conflicto llega igual, desde el indice"
                                    + " unico, pero diciendo que el repetido es el predio")
                    .contains("\"codigo\":\"CONFLICTO\"")
                    .contains(PREDIO_CON_FICHA)
                    .contains("ya tiene ficha UNICA");
            assertThat(cuerpo)
                    .as("ni tabla, ni restriccion, ni SQL")
                    .doesNotContain("ficha_vigente_uq")
                    .doesNotContain("duplicate key");
            assertThat(fichas.delPredio(PREDIO_CON_FICHA, predios)).hasSize(1);
        }

        @Test
        @DisplayName("un alta sin observacion es 422: sin ella no se guarda (regla 10)")
        void unAltaSinObservacionEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "direccion":"AV. GRAU 100",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"VALIDACION\"")
                    .contains("observacion");
            assertThat(predios.porCodigo(PREDIO_NUEVO)).as("no se guardo nada").isEmpty();
            assertThat(asentado).isEmpty();
        }

        @Test
        @DisplayName("las cuatro rutas inscriben su tipo, cada una con su detalle")
        void lasCuatroRutasInscribenSuTipo() throws Exception {
            Map<String, String> porRuta = new LinkedHashMap<>();
            porRuta.put("urbana", "");
            porRuta.put(
                    "economica",
                    "\"economico\":{\"actividades\":[{\"conductor\":\"Bodega\","
                            + "\"ciiu\":\"4711\"}]},");
            porRuta.put(
                    "bienes-comunes",
                    "\"bienesComunes\":{\"bienes\":[{\"descripcion\":\"Escalera\","
                            + "\"area\":\"30.00\"}]},");
            porRuta.put(
                    "rural",
                    "\"rural\":{\"tierras\":[{\"clasificacion\":\"CULTIVO_TRANSITORIO\","
                            + "\"riego\":\"BAJO_RIEGO\",\"hectareas\":\"1.5000\"}]},");

            int unidad = 100;
            for (Map.Entry<String, String> ruta : porRuta.entrySet()) {
                String codigo = TRAMO_COMUN + (unidad++);
                MvcResult resultado =
                        mvc.perform(
                                        post("/rentas/api/v1/catastro/fichas/" + ruta.getKey())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        "{\"codRefCatastral\":\""
                                                                + codigo
                                                                + "\","
                                                                + "\"direccion\":\"AV. CUATRO"
                                                                + " TIPOS 100\","
                                                                + "\"areaTerreno\":\"300.00\","
                                                                + "\"uso\":\"MIXTO\","
                                                                + "\"documentoOrigen\":\"DJ"
                                                                + " 300-2026\","
                                                                + ruta.getValue()
                                                                + "\"observacion\":\"Alta de la"
                                                                + " ficha "
                                                                + ruta.getKey()
                                                                + "\"}"))
                                .andReturn();

                assertThat(resultado.getResponse().getStatus())
                        .as("ruta %s", ruta.getKey())
                        .isEqualTo(201);
            }

            assertThat(fichas.versiones.stream().map(FichaCatastral::tipo))
                    .contains(
                            TipoFicha.UNICA,
                            TipoFicha.ECONOMICA,
                            TipoFicha.BIENES_COMUNES,
                            TipoFicha.RURAL);
        }

        @Test
        @DisplayName("un bloque de detalle de otro tipo es 422, no un campo ignorado en silencio")
        void unDetalleDeOtroTipoEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/rural")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "tipoPredio":"RUSTICO",
                                                     "direccion":"FUNDO LA ESPERANZA",
                                                     "areaTerreno":"25000.00",
                                                     "uso":"AGRICOLA",
                                                     "documentoOrigen":"DJ 400-2026",
                                                     "economico":{"actividades":[
                                                       {"conductor":"Bodega","ciiu":"4711"}]},
                                                     "observacion":"Detalle economico en una ficha rural"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "ignorarlo responderia 201 con lo declarado en ningun sitio, que es el"
                                    + " peor de los dos resultados")
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"VALIDACION\"")
                    .contains("RURAL")
                    .contains("ECONOMICA");
            assertThat(predios.porCodigo(PREDIO_NUEVO)).isEmpty();
        }

        @Test
        @DisplayName("el sector, la manzana y la via entran por codigo y se resuelven")
        void laUbicacionSeResuelvePorCodigo() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "direccion":"AV. GRAU 100",
                                                     "codigoDeSector":"SC-1",
                                                     "codigoDeManzana":"001",
                                                     "codigoDeVia":"VIA-1",
                                                     "lote":"12",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026",
                                                     "observacion":"Alta con ubicacion completa"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            Predio guardado = predios.porCodigo(PREDIO_NUEVO).orElseThrow();
            assertThat(guardado.sectorId()).isEqualTo(1L);
            assertThat(guardado.manzanaId()).isEqualTo(1L);
            assertThat(guardado.viaId()).isEqualTo(1L);
            assertThat(guardado.lote()).isEqualTo("12");
        }

        @Test
        @DisplayName("un sector que no existe es 404 y no deja el predio a medio ubicar")
        void unSectorInexistenteEs404() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "direccion":"AV. GRAU 100",
                                                     "codigoDeSector":"SC-NADA",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026",
                                                     "observacion":"Alta con un sector inexistente"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"NO_ENCONTRADO\"")
                    .contains("sector")
                    .contains("SC-NADA");
            assertThat(predios.porCodigo(PREDIO_NUEVO)).isEmpty();
        }

        @Test
        @DisplayName("un predio dado de baja no admite ficha nueva: 409")
        void unPredioDadoDeBajaEs409() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010004",
                                                     "direccion":"AV. RETIRADA 400",
                                                     "areaTerreno":"100.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 400-2026",
                                                     "observacion":"Ficha sobre un predio retirado"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"CONFLICTO\"")
                    .contains("dado de baja");
            assertThat(fichas.delPredio(PREDIO_DE_BAJA, predios)).isEmpty();
        }

        @Test
        @DisplayName("el codigo repetido que se cuela entre la lectura y el INSERT es 409")
        void elCodigoRepetidoEnLaCarreraEs409() throws Exception {
            // La lectura dice que no esta y el INSERT choca: es la carrera entre dos peticiones
            // simultaneas. La unicidad la exige la base —es la unica que puede—, pero su mensaje
            // nombra la tabla y la restriccion, asi que se traduce.
            predios.simularCarrera = true;

            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "direccion":"AV. GRAU 100",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026",
                                                     "observacion":"Dos peticiones a la vez"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo).contains("\"codigo\":\"CONFLICTO\"").contains(PREDIO_NUEVO);
            assertThat(cuerpo)
                    .as("ni tabla, ni restriccion, ni SQL: eso reconstruye el esquema")
                    .doesNotContain("predio_codigo_uq")
                    .doesNotContain("duplicate key")
                    .doesNotContain("incidencia");
        }

        @Test
        @DisplayName("un codigo de referencia catastral mal compuesto es 422")
        void unCodigoMalCompuestoEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"250101",
                                                     "direccion":"AV. GRAU 100",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026",
                                                     "observacion":"Codigo corto"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"VALIDACION\"")
                    .contains("posiciones");
        }

        @Test
        @DisplayName("la superficie rural sale en hectareas, con su unidad")
        void laSuperficieRuralSaleEnHectareas() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/rural")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "tipoPredio":"RUSTICO",
                                                     "direccion":"FUNDO LA ESPERANZA",
                                                     "areaTerreno":"25000.00",
                                                     "uso":"AGRICOLA",
                                                     "documentoOrigen":"DJ 400-2026",
                                                     "rural":{"tierras":[
                                                       {"clasificacion":"CULTIVO_TRANSITORIO",
                                                        "riego":"BAJO_RIEGO","hectareas":"1.5000"}],
                                                       "colindantes":[
                                                        {"orientacion":"NORTE","descripcion":"Fundo San Juan"}]},
                                                     "observacion":"Alta de la ficha rural del fundo"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(201);
            assertThat(resultado.getResponse().getContentAsString())
                    .as(
                            "el arancel rural se publica por hectarea; un numero suelto se leeria"
                                    + " como metros y valorizaria diez mil veces de menos")
                    .contains("\"hectareasTotales\":\"1.5000 HA\"")
                    .contains("Fundo San Juan");
        }

        @Test
        @DisplayName("el titular es opcional: se ficha antes de saber de quien es el predio")
        void elTitularEsOpcional() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010001",
                                                     "direccion":"AV. GRAU 100",
                                                     "areaTerreno":"200.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 100-2026",
                                                     "observacion":"Levantamiento sin titular identificado"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "exigirlo obligaria al tecnico a inventarse un titular para poder"
                                    + " guardar (DAT-01 §4.2)")
                    .isEqualTo(201);
            assertThat(predios.titularidades).isEmpty();
        }

        @Test
        @DisplayName("el titular entra por su codigo del padron, no por identificador interno")
        void elTitularEntraPorCodigo() throws Exception {
            mvc.perform(
                            post("/rentas/api/v1/catastro/fichas/urbana")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(altaConTitular("CT-0001")))
                    .andReturn();

            assertThat(predios.titularidades).hasSize(1);
            assertThat(predios.titularidades.get(0).contribuyenteId()).isEqualTo(77L);
            assertThat(predios.titularidades.get(0).porcentaje().esTotal())
                    .as("un propietario unico lo es por el total")
                    .isTrue();
        }
    }

    // ── La actualizacion versionada ────────────────────────────────────

    @Nested
    @DisplayName("Actualizacion (PUT): la version siguiente, sin sobrescribir")
    class Actualizacion {

        @Test
        @DisplayName("versiona: cierra la vigente y abre la siguiente, sin municipalidad")
        void elPutVersiona() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_CON_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 090-2026",
                                                     "origen":"FISCALIZACION",
                                                     "vigenciaDesde":"2026-07-01",
                                                     "observacion":"Se rectifica lo declarado en la inspeccion"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            String cuerpo = resultado.getResponse().getContentAsString();
            assertThat(cuerpo)
                    .contains("\"version\":2")
                    .contains("\"vigente\":true")
                    .contains("\"origen\":\"FISCALIZACION\"");
            assertThat(cuerpo).doesNotContain("municipalidad");

            List<FichaCatastral> historial = fichas.delPredio(PREDIO_CON_FICHA, predios);
            assertThat(historial).as("la anterior sigue entera, no se sobrescribio").hasSize(2);
            assertThat(unaVersion(historial, 1).vigenciaHasta())
                    .as("se cierra el dia antes de que empiece la nueva")
                    .isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        @DisplayName("no mandar construcciones las COPIA de la version vigente")
        void noMandarConstruccionesLasCopia() throws Exception {
            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 090-2026",
                                             "observacion":"Solo cambia el documento de origen"}
                                            """))
                    .andReturn();

            assertThat(vigenteDe(PREDIO_CON_FICHA, TipoFicha.UNICA).construcciones())
                    .as(
                            "un campo ausente no es una instruccion de borrado: si lo fuera, este"
                                    + " predio perderia sus 90 m2 construidos sin que ningun DELETE"
                                    + " apareciera en el diff")
                    .hasSize(1);
        }

        @Test
        @DisplayName("mandar la lista vacia SI las borra: es una instruccion, no una omision")
        void mandarListaVaciaLasBorra() throws Exception {
            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 091-2026",
                                             "construcciones":[],
                                             "observacion":"Se demolio lo construido"}
                                            """))
                    .andReturn();

            assertThat(vigenteDe(PREDIO_CON_FICHA, TipoFicha.UNICA).construcciones()).isEmpty();
            assertThat(unaVersion(fichas.delPredio(PREDIO_CON_FICHA, predios), 1).construcciones())
                    .as("y la version anterior conserva las suyas: por eso se versiona")
                    .hasSize(1);
        }

        @Test
        @DisplayName("las instalaciones siguen la misma regla: ausente copia, vacia borra")
        void lasInstalacionesSiguenLaMismaRegla() throws Exception {
            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 092-2026",
                                             "vigenciaDesde":"2026-07-01",
                                             "observacion":"Sin tocar las obras complementarias"}
                                            """))
                    .andReturn();

            assertThat(vigenteDe(PREDIO_CON_FICHA, TipoFicha.UNICA).instalaciones()).hasSize(1);

            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 093-2026",
                                             "vigenciaDesde":"2026-09-01",
                                             "instalaciones":[],
                                             "observacion":"Se retiro el cerco perimetrico"}
                                            """))
                    .andReturn();

            assertThat(vigenteDe(PREDIO_CON_FICHA, TipoFicha.UNICA).instalaciones()).isEmpty();
        }

        @Test
        @DisplayName("no mandar el bloque de detalle lo COPIA; mandarlo lo reemplaza")
        void elDetalleSigueLaMismaRegla() throws Exception {
            fichas.sembrarRural(predios.idDe(PREDIO_SIN_FICHA));

            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/rural/"
                                            + PREDIO_SIN_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 500-2026",
                                             "vigenciaDesde":"2026-07-01",
                                             "observacion":"Se rectifica el uso, no la tierra"}
                                            """))
                    .andReturn();

            DetalleDeLaFicha copiado = vigenteDe(PREDIO_SIN_FICHA, TipoFicha.RURAL).detalle();
            assertThat(copiado)
                    .as("el detalle ausente se copia, igual que las construcciones")
                    .isInstanceOf(DetalleRural.class);
            assertThat(((DetalleRural) copiado).hectareasTotales().toString())
                    .isEqualTo("1.5000 HA");

            MvcResult reemplazo =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/rural/"
                                                    + PREDIO_SIN_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 501-2026",
                                                     "vigenciaDesde":"2026-10-01",
                                                     "rural":{"tierras":[
                                                       {"clasificacion":"PASTOS_NATURALES",
                                                        "riego":"SECANO","hectareas":"3.0000"}]},
                                                     "observacion":"Se reclasifica la tierra tras la inspeccion"}
                                                    """))
                            .andReturn();

            assertThat(reemplazo.getResponse().getStatus()).isEqualTo(200);
            assertThat(reemplazo.getResponse().getContentAsString())
                    .contains("\"hectareasTotales\":\"3.0000 HA\"")
                    .contains("PASTOS_NATURALES");
        }

        @Test
        @DisplayName("un bloque de otro tipo tambien es 422 en el PUT")
        void unBloqueDeOtroTipoEnElPutEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_CON_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 094-2026",
                                                     "rural":{"tierras":[]},
                                                     "observacion":"Grupos de tierra en una ficha urbana"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("UNICA");
            assertThat(fichas.delPredio(PREDIO_CON_FICHA, predios))
                    .as("no se versiono nada")
                    .hasSize(1);
        }

        @Test
        @DisplayName("un PUT sin observacion es 422 (regla 10)")
        void unPutSinObservacionEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_CON_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{\"documentoOrigen\":\"Acta 095-2026\"}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("observacion");
            assertThat(fichas.delPredio(PREDIO_CON_FICHA, predios)).hasSize(1);
            assertThat(asentado).isEmpty();
        }

        @Test
        @DisplayName("un predio sin ficha de ese tipo es 404, no una incidencia")
        void unPredioSinFichaDeEseTipoEs404() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/economica/"
                                                    + PREDIO_SIN_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 096-2026",
                                                     "observacion":"Actualizar lo que todavia no existe"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as("lo que falta es la PRIMERA version, y esa se registra con el POST")
                    .isEqualTo(404);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"NO_ENCONTRADO\"")
                    .contains("ECONOMICA");
        }

        @Test
        @DisplayName("un predio que no existe es 404")
        void unPredioInexistenteEs404() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/rural/"
                                                    + PREDIO_INEXISTENTE
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 097-2026",
                                                     "observacion":"Actualizar un predio inexistente"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("codigo de referencia");
        }

        @Test
        @DisplayName("las cuatro rutas versionan su tipo, y solo el suyo")
        void lasCuatroRutasVersionanSuTipo() throws Exception {
            fichas.sembrarDeCadaTipo(predios.idDe(PREDIO_SIN_FICHA));

            Map<String, TipoFicha> porRuta = new LinkedHashMap<>();
            porRuta.put(PREDIO_SIN_FICHA, TipoFicha.UNICA);
            porRuta.put("economica/" + PREDIO_SIN_FICHA, TipoFicha.ECONOMICA);
            porRuta.put("bienes-comunes/" + PREDIO_SIN_FICHA, TipoFicha.BIENES_COMUNES);
            porRuta.put("rural/" + PREDIO_SIN_FICHA, TipoFicha.RURAL);

            for (Map.Entry<String, TipoFicha> ruta : porRuta.entrySet()) {
                MvcResult resultado =
                        mvc.perform(
                                        put("/rentas/api/v1/catastro/fichas/"
                                                        + ruta.getKey()
                                                        + "/actualizacion")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        """
                                                        {"documentoOrigen":"Acta 600-2026",
                                                         "vigenciaDesde":"2026-07-01",
                                                         "observacion":"Version siguiente de la ficha"}
                                                        """))
                                .andReturn();

                assertThat(resultado.getResponse().getStatus())
                        .as("ruta %s", ruta.getKey())
                        .isEqualTo(200);
                assertThat(resultado.getResponse().getContentAsString())
                        .as("ruta %s", ruta.getKey())
                        .contains("\"tipo\":\"" + ruta.getValue().name() + "\"")
                        .contains("\"version\":2");
            }
        }
    }

    // ── La correccion del predio, y su baja ────────────────────────────

    @Nested
    @DisplayName("Correccion del predio (PUT): los datos que hasta ahora solo se escribian al alta")
    class CorreccionDelPredio {

        @Test
        @DisplayName("corrige el predio y versiona la ficha en UN acto, con una sola observacion")
        void corrigeYVersionaEnUnActo() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_CON_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 700-2026",
                                                     "observacion":"Corregir la direccion mal tecleada al fichar",
                                                     "predio":{"direccion":"AV. SANCHEZ CERRO 250",
                                                               "numeroMunicipal":"250"}}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);

            Predio corregido = predios.porCodigo(PREDIO_CON_FICHA).orElseThrow();
            assertThat(corregido.direccion()).isEqualTo("AV. SANCHEZ CERRO 250");
            assertThat(corregido.numeroMunicipal()).isEqualTo("250");
            assertThat(vigenteDe(PREDIO_CON_FICHA, TipoFicha.UNICA).version()).isEqualTo(2);

            assertThat(transacciones.abiertas)
                    .as(
                            "corregir el predio y versionar su ficha son un acto: dos casos de uso"
                                    + " encadenados desde el controlador dejarian el predio movido"
                                    + " cuando la ficha falla, y nada lo diria")
                    .isEqualTo(1);
            assertThat(asentado)
                    .extracting(RegistroDeAuditoria::observacion)
                    .as("un acto, una observacion (regla 10)")
                    .containsOnly(Observacion.de("Corregir la direccion mal tecleada al fichar"));
            assertThat(asentado)
                    .extracting(RegistroDeAuditoria::tabla)
                    .as("el predio se corrige una vez; la ficha asienta el cierre y la apertura")
                    .containsExactly("predio", "ficha_catastral", "ficha_catastral");
        }

        @Test
        @DisplayName("lo que el bloque no manda, no cambia")
        void loQueNoMandaNoCambia() throws Exception {
            predios.reemplazar(
                    predios.porCodigo(PREDIO_CON_FICHA)
                            .orElseThrow()
                            .enLaVia(1L, "200")
                            .ubicadoEn(1L, 1L, "12"));

            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 701-2026",
                                             "observacion":"Solo la direccion",
                                             "predio":{"direccion":"CALLE NUEVA 10"}}
                                            """))
                    .andReturn();

            Predio corregido = predios.porCodigo(PREDIO_CON_FICHA).orElseThrow();
            assertThat(corregido.direccion()).isEqualTo("CALLE NUEVA 10");
            assertThat(corregido.numeroMunicipal())
                    .as("mandar el bloque para arreglar la direccion no borra lo demas")
                    .isEqualTo("200");
            assertThat(corregido.lote()).isEqualTo("12");
            assertThat(corregido.viaId()).isEqualTo(1L);
            assertThat(corregido.sectorId()).isEqualTo(1L);
            assertThat(corregido.manzanaId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("la cadena vacia SI borra: es una instruccion, no una omision")
        void laCadenaVaciaBorra() throws Exception {
            predios.reemplazar(
                    predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().enLaVia(1L, "200"));

            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 702-2026",
                                             "observacion":"El predio no tiene numero municipal",
                                             "predio":{"numeroMunicipal":""}}
                                            """))
                    .andReturn();

            assertThat(predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().numeroMunicipal())
                    .as("sin la vacia, un numero municipal equivocado no se podria quitar nunca")
                    .isNull();
        }

        @Test
        @DisplayName("si la ficha falla, la correccion del predio NO sobrevive")
        void siLaFichaFallaLaCorreccionNoSobrevive() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_SIN_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 703-2026",
                                                     "observacion":"Corregir un predio sin ficha vigente",
                                                     "predio":{"direccion":"CALLE FANTASMA 1"}}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
            assertThat(predios.porCodigo(PREDIO_SIN_FICHA).orElseThrow().direccion())
                    .as("el predio se corrige antes que la ficha, y se deshace con ella")
                    .isEqualTo("AV. SIN FICHA 300");
            assertThat(asentado).as("no queda constancia de algo que no paso").isEmpty();
        }

        @Test
        @DisplayName("un sector que no existe es 404 y no deja el predio a medio mover")
        void unSectorInexistenteEnLaCorreccionEs404() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_CON_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 704-2026",
                                                     "observacion":"Mover a un sector inexistente",
                                                     "predio":{"direccion":"CALLE OTRA 5",
                                                               "codigoDeSector":"SC-NADA"}}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"codigo\":\"NO_ENCONTRADO\"")
                    .contains("sector")
                    .contains("SC-NADA");
            assertThat(predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().direccion())
                    .isEqualTo("AV. SANCHEZ CERRO 200");
        }

        @Test
        @DisplayName("cambiar de sector conservando la manzana del anterior es 422, no un arrastre")
        void cambiarDeSectorConservandoLaManzanaEs422() throws Exception {
            predios.reemplazar(
                    predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().ubicadoEn(1L, 1L, "12"));

            MvcResult resultado =
                    mvc.perform(
                                    put("/rentas/api/v1/catastro/fichas/"
                                                    + PREDIO_CON_FICHA
                                                    + "/actualizacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"documentoOrigen":"Acta 705-2026",
                                                     "observacion":"Mover el predio de sector",
                                                     "predio":{"codigoDeSector":"SC-2"}}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "arrastrar la manzana 001 del sector 1 al sector 2 dejaria el predio"
                                    + " colgando de una manzana que no es de su sector")
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("manzana");
            assertThat(predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().sectorId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("declarando la manzana nueva, el cambio de sector si entra")
        void conLaManzanaNuevaElCambioDeSectorEntra() throws Exception {
            predios.reemplazar(
                    predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().ubicadoEn(1L, 1L, "12"));

            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 706-2026",
                                             "observacion":"Mover el predio al sector norte",
                                             "predio":{"codigoDeSector":"SC-2","codigoDeManzana":"002"}}
                                            """))
                    .andReturn();

            Predio movido = predios.porCodigo(PREDIO_CON_FICHA).orElseThrow();
            assertThat(movido.sectorId()).isEqualTo(2L);
            assertThat(movido.manzanaId()).isEqualTo(2L);
        }

        @Test
        @DisplayName("el codigo de referencia catastral no se puede cambiar: identifica al predio")
        void elCodigoNoSeCambia() throws Exception {
            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 707-2026",
                                             "observacion":"Intentar renombrar el predio",
                                             "predio":{"codRefCatastral":"25010100100100101010777",
                                                       "direccion":"AV. RENOMBRADA 1"}}
                                            """))
                    .andReturn();

            assertThat(predios.porCodigo(PREDIO_CON_FICHA))
                    .as("cambiar el codigo no es corregir un predio, es declarar otro")
                    .isPresent();
            assertThat(predios.porCodigo("25010100100100101010777")).isEmpty();
        }

        @Test
        @DisplayName("sin bloque de predio, el predio no se toca ni deja asiento")
        void sinBloqueElPredioNoSeToca() throws Exception {
            mvc.perform(
                            put("/rentas/api/v1/catastro/fichas/"
                                            + PREDIO_CON_FICHA
                                            + "/actualizacion")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            """
                                            {"documentoOrigen":"Acta 708-2026",
                                             "observacion":"Solo versionar la ficha"}
                                            """))
                    .andReturn();

            assertThat(predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().direccion())
                    .isEqualTo("AV. SANCHEZ CERRO 200");
            assertThat(asentado)
                    .extracting(RegistroDeAuditoria::tabla)
                    .as("el acto corriente sigue siendo versionar la ficha, y solo eso")
                    .containsOnly("ficha_catastral");
        }
    }

    @Nested
    @DisplayName("Listado de predios (GET): los filtros que la capa web compone")
    class ListadoDePredios {

        @Test
        @DisplayName("los cuatro filtros viajan tal como se declaran")
        void losCuatroFiltrosViajan() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    get("/rentas/api/v1/catastro/predios")
                                            .param("codRefCatastral", "2501010010")
                                            .param("codigoDeSector", "SC-1")
                                            .param("estado", "DADO_DE_BAJA")
                                            .param("fichado", "false"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            FiltroDePredios filtro = Objects.requireNonNull(predios.ultimoFiltro);
            assertThat(filtro.codRefCatastral()).isEqualTo("2501010010");
            assertThat(filtro.codigoDeSector()).isEqualTo("SC-1");
            assertThat(filtro.estado()).isEqualTo(EstadoPredio.DADO_DE_BAJA);
            assertThat(filtro.fichado()).isFalse();
        }

        @Test
        @DisplayName("sin filtros, ninguno viaja: el padron entero es una pregunta legitima")
        void sinFiltrosNingunoViaja() throws Exception {
            mvc.perform(get("/rentas/api/v1/catastro/predios")).andReturn();

            FiltroDePredios filtro = Objects.requireNonNull(predios.ultimoFiltro);
            assertThat(filtro.codRefCatastral()).isNull();
            assertThat(filtro.codigoDeSector()).isNull();
            assertThat(filtro.estado()).isNull();
            assertThat(filtro.fichado()).isNull();
        }

        @Test
        @DisplayName("un 'fichado' que no es true ni false es 422, no un false en silencio")
        void unFichadoQueNoEsBooleanoEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/predios").param("fichado", "si"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus())
                    .as(
                            "con parseBoolean, 'si' devolveria la cola de saneamiento entera"
                                    + " cuando se pedia lo contrario, y sin un solo mensaje")
                    .isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("fichado");
        }

        @Test
        @DisplayName("un estado desconocido es 422 y dice cual llego")
        void unEstadoDesconocidoEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/predios").param("estado", "ANULADO"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(resultado.getResponse().getContentAsString()).contains("ANULADO");
        }
    }

    @Nested
    @DisplayName("Baja y reactivacion del predio (POST): retirar no es borrar")
    class BajaDelPredio {

        @Test
        @DisplayName("la baja retira el predio del padron y lo asienta, sin borrar su ficha")
        void laBajaRetiraElPredio() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/predios/"
                                                    + predios.idDe(PREDIO_CON_FICHA)
                                                    + "/baja")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"observacion":"Predio demolido segun acta 900-2026"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"estado\":\"DADO_DE_BAJA\"");
            assertThat(predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().estaActivo()).isFalse();
            assertThat(fichas.delPredio(PREDIO_CON_FICHA, predios))
                    .as("se retira, no se borra: la ficha aparece en determinaciones ya emitidas")
                    .hasSize(1);
            assertThat(asentado)
                    .singleElement()
                    .extracting(RegistroDeAuditoria::operacion)
                    .isEqualTo(Operacion.BAJA);
        }

        @Test
        @DisplayName("un predio ya dado de baja es 409, no un segundo acto sin efecto")
        void unPredioYaDadoDeBajaEs409() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/predios/"
                                                    + predios.idDe(PREDIO_DE_BAJA)
                                                    + "/baja")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"observacion":"Volver a retirar lo retirado"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
            assertThat(asentado).isEmpty();
        }

        @Test
        @DisplayName("una baja sin observacion es 422: sin ella no se guarda (regla 10)")
        void unaBajaSinObservacionEs422() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/predios/"
                                                    + predios.idDe(PREDIO_CON_FICHA)
                                                    + "/baja")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(422);
            assertThat(predios.porCodigo(PREDIO_CON_FICHA).orElseThrow().estaActivo()).isTrue();
        }

        @Test
        @DisplayName("un predio que no existe es 404")
        void unPredioInexistenteEs404() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/predios/99999/baja")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"observacion":"Retirar lo que no esta"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(404);
        }

        @Test
        @DisplayName("la reactivacion devuelve el predio al padron y le vuelve a admitir ficha")
        void laReactivacionDevuelveElPredio() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/predios/"
                                                    + predios.idDe(PREDIO_DE_BAJA)
                                                    + "/reactivacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"observacion":"La baja fue un error de captura"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"estado\":\"ACTIVO\"");

            MvcResult conFicha =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/fichas/urbana")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"codRefCatastral":"25010100100100101010004",
                                                     "direccion":"AV. RETIRADA 400",
                                                     "areaTerreno":"120.00",
                                                     "uso":"CASA HABITACION",
                                                     "documentoOrigen":"DJ 900-2026",
                                                     "observacion":"Fichar el predio reactivado"}
                                                    """))
                            .andReturn();

            assertThat(conFicha.getResponse().getStatus())
                    .as(
                            "sin la reactivacion, la baja seria una puerta de un solo sentido: el"
                                    + " alta rechaza a proposito fichar un predio retirado")
                    .isEqualTo(201);
        }

        @Test
        @DisplayName("reactivar un predio que ya esta activo es 409")
        void reactivarUnoActivoEs409() throws Exception {
            MvcResult resultado =
                    mvc.perform(
                                    post("/rentas/api/v1/catastro/predios/"
                                                    + predios.idDe(PREDIO_CON_FICHA)
                                                    + "/reactivacion")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    """
                                                    {"observacion":"Reactivar lo que nunca se retiro"}
                                                    """))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(409);
            assertThat(asentado).isEmpty();
        }
    }

    // ------------------------------------------------------------------

    private FichaCatastral vigenteDe(String codigoDePredio, TipoFicha tipo) {
        return fichas.vigenteDe(predios.idDe(codigoDePredio), tipo).orElseThrow();
    }

    private static FichaCatastral unaVersion(List<FichaCatastral> historial, int version) {
        return historial.stream()
                .filter(ficha -> ficha.version() == version)
                .findFirst()
                .orElseThrow();
    }

    // ── La lectura: lo que el recurso publica ──────────────────────────

    /**
     * Lo que la ficha guarda y el recurso publica (#290).
     *
     * <p>Todo lo que se afirma aqui estaba ya en el dominio y en la base —se escribia al inscribir
     * la ficha y se copiaba al versionar— y no salia por HTTP: la pantalla que lo declaraba no
     * podia volver a verlo. Cada prueba siembra el dato y comprueba que llega al JSON; si un campo
     * deja de publicarse, la suya se pone roja diciendo cual.
     */
    @Nested
    @DisplayName("Lectura (GET): la ficha publica lo que guarda")
    class Lectura {

        @Test
        @DisplayName("la cabecera lleva frontis, condicion de propiedad y tipo de edificacion")
        void laCabeceraLlevaSusTresCampos() throws Exception {
            fichas.sembrar(fichaCompleta(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/fichas/urbana/" + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getStatus()).isEqualTo(200);
            assertThat(resultado.getResponse().getContentAsString())
                    .as("el frontis sale con su unidad: son metros lineales, no cuadrados")
                    .contains("\"frontis\":\"12.50 ML\"")
                    .contains("\"condicionPropiedad\":\"PROPIETARIO UNICO\"")
                    .contains("\"tipoEdificacion\":\"CASA HABITACION\"");
        }

        @Test
        @DisplayName(
                "las obras complementarias salen enteras: que es, cuanto, unidad, ano y estado")
        void lasObrasComplementariasSalenEnteras() throws Exception {
            fichas.sembrar(fichaCompleta(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/fichas/urbana/" + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getContentAsString())
                    .as(
                            "sin la lista, el cerco que el tecnico midio en campo se guardaba y no"
                                    + " se podia volver a mirar")
                    .contains("\"instalaciones\"")
                    .contains("\"descripcion\":\"Cerco perimetrico\"")
                    .contains("\"unidad\":\"ML\"")
                    .contains("\"cantidad\":\"30.00 ML\"")
                    .contains("\"anioConstruccion\":2019")
                    .contains("\"estadoConservacion\":\"BUENO\"");
        }

        @Test
        @DisplayName("cada construccion lleva su porcentaje construido, con su signo")
        void cadaConstruccionLlevaSuPorcentaje() throws Exception {
            fichas.sembrar(fichaCompleta(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/fichas/urbana/" + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getContentAsString())
                    .as("un piso construido a medias no es un piso completo, y la ficha lo sabia")
                    .contains("\"porcentajeConstruido\":\"60.00 %\"");
        }

        @Test
        @DisplayName("la actividad lleva la fecha del anuncio y desde cuando se declara")
        void laActividadLlevaSusFechas() throws Exception {
            fichas.sembrar(fichaEconomica(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/fichas/economica/" + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getContentAsString())
                    .as("una fiscalizacion sin fecha no se sostiene (regla 9)")
                    .contains("\"anuncioNumero\":\"AN-77\"")
                    .contains("\"anuncioFecha\":\"2025-03-10\"")
                    .contains("\"vigenciaDesde\":\"2025-01-02\"");
        }

        @Test
        @DisplayName("el bien comun lleva su ano de construccion, que es lo que lo deprecia")
        void elBienComunLlevaSuAnio() throws Exception {
            fichas.sembrar(fichaDeBienesComunes(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(
                                    get(
                                            "/rentas/api/v1/catastro/fichas/bienes-comunes/"
                                                    + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getContentAsString())
                    .contains("\"descripcion\":\"Escalera comun\"")
                    .contains("\"anioConstruccion\":2008");
        }

        @Test
        @DisplayName("el grupo de tierra lleva la superficie comun que le toca, en hectareas")
        void elGrupoDeTierraLlevaSuAreaComun() throws Exception {
            fichas.sembrar(fichaRuralConAreaComun(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/fichas/rural/" + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getContentAsString())
                    .as("con su unidad: leer hectareas como metros calcularia diez mil veces menos")
                    .contains("\"hectareas\":\"4.0000 HA\"")
                    .contains("\"hectareasComunes\":\"0.5000 HA\"");
        }

        @Test
        @DisplayName("y con todo eso publicado, sigue sin salir un solo importe (regla 5)")
        void sigueSinSalirNingunImporte() throws Exception {
            fichas.sembrar(fichaCompleta(predios.idDe(PREDIO_SIN_FICHA)));

            MvcResult resultado =
                    mvc.perform(get("/rentas/api/v1/catastro/fichas/urbana/" + PREDIO_SIN_FICHA))
                            .andReturn();

            assertThat(resultado.getResponse().getContentAsString())
                    .as(
                            "el valor de una obra complementaria sale de un valor unitario, el 5 %, la"
                                    + " depreciacion y un factor sin fuente (D-11): nada de eso se"
                                    + " publica")
                    .doesNotContain("\"valor")
                    .doesNotContain("arancel")
                    .doesNotContain("autovaluo")
                    .doesNotContain("S/");
        }
    }

    /** Una ficha urbana con todo lo que la cabecera, la construccion y la obra pueden llevar. */
    private static FichaCatastral fichaCompleta(long predioId) {
        return new FichaCatastral(
                null,
                predioId,
                TipoFicha.UNICA,
                1,
                AreaM2.de("200.00"),
                "CASA HABITACION",
                Medida.enMetrosLineales("12.50"),
                "PROPIETARIO UNICO",
                "CASA HABITACION",
                "Casa de los Rojas",
                ALTA,
                null,
                OrigenDeLaFicha.DECLARACION_JURADA,
                "DJ 900-2026",
                Observacion.de("Alta de la ficha con todos sus campos declarados"),
                List.of(
                        new Construccion(
                                null,
                                null,
                                "1",
                                AreaM2.de("90.00"),
                                new Ejercicio(2015),
                                MaterialEstructural.LADRILLO,
                                EstadoDeConservacion.BUENO,
                                CategoriasConstructivas.todas('C'),
                                Porcentaje.de("60.00"))),
                List.of(
                        new OtraInstalacion(
                                null,
                                null,
                                "Cerco perimetrico",
                                Medida.de("30.00", "ML"),
                                new Ejercicio(2019),
                                EstadoDeConservacion.BUENO)),
                null);
    }

    /** Una ficha economica cuya actividad declara anuncio con fecha y desde cuando rige. */
    private static FichaCatastral fichaEconomica(long predioId) {
        return FichaCatastral.primera(
                        predioId,
                        TipoFicha.ECONOMICA,
                        AreaM2.de("120.00"),
                        "COMERCIO",
                        ALTA,
                        OrigenDeLaFicha.DECLARACION_JURADA,
                        "DJ 901-2026",
                        Observacion.de("Alta de la ficha economica del local"))
                .conDetalle(
                        DetalleEconomico.de(
                                new ActividadEconomica(
                                        null,
                                        null,
                                        "ROJAS DIAZ, ANA",
                                        "Bodega Ana",
                                        "4711",
                                        AreaM2.de("40.00"),
                                        "LIC-55",
                                        LocalDate.of(2025, 2, 1),
                                        "AN-77",
                                        LocalDate.of(2025, 3, 10),
                                        LocalDate.of(2025, 1, 2))));
    }

    /** Una ficha de bienes comunes cuyo bien declara de que ano es. */
    private static FichaCatastral fichaDeBienesComunes(long predioId) {
        return FichaCatastral.primera(
                        predioId,
                        TipoFicha.BIENES_COMUNES,
                        AreaM2.de("600.00"),
                        "EDIFICIO MULTIFAMILIAR",
                        ALTA,
                        OrigenDeLaFicha.DECLARACION_JURADA,
                        "DJ 902-2026",
                        Observacion.de("Alta de la ficha de bienes comunes del edificio"))
                .conDetalle(
                        DetalleDeBienesComunes.de(
                                new BienComun(
                                        null,
                                        null,
                                        "Escalera comun",
                                        AreaM2.de("35.00"),
                                        MaterialEstructural.CONCRETO,
                                        EstadoDeConservacion.BUENO,
                                        new Ejercicio(2008))));
    }

    /** Una ficha rural cuyo grupo de tierra lleva ademas su parte de las areas comunes. */
    private static FichaCatastral fichaRuralConAreaComun(long predioId) {
        return FichaCatastral.primera(
                        predioId,
                        TipoFicha.RURAL,
                        AreaM2.de("40000.00"),
                        "AGRICOLA",
                        ALTA,
                        OrigenDeLaFicha.DECLARACION_JURADA,
                        "DJ 903-2026",
                        Observacion.de("Alta de la ficha rural con area comun declarada"))
                .conDetalle(
                        DetalleRural.de(
                                new TierraRural(
                                        null,
                                        null,
                                        "CULTIVO_TRANSITORIO",
                                        "A2",
                                        Riego.BAJO_RIEGO,
                                        TierraRural.enHectareas("4.0000"),
                                        TierraRural.enHectareas("0.5000"))));
    }

    private static String altaConTitular(String codigoContribuyente) {
        return """
               {"codRefCatastral":"25010100100100101010001",
                "direccion":"AV. GRAU 100",
                "areaTerreno":"200.00",
                "uso":"CASA HABITACION",
                "documentoOrigen":"DJ 100-2026",
                "titular":{"codigoContribuyente":"%s",
                           "condicion":"PROPIETARIO_UNICO",
                           "documentoOrigen":"Partida registral 11223344"},
                "observacion":"Alta del predio con su titular"}
               """
                .formatted(codigoContribuyente);
    }

    /**
     * Gestor de transacciones de juguete: cuenta cuantas se abren y <b>deshace</b> lo escrito
     * cuando una se revierte.
     *
     * <p>No imita a PostgreSQL: imita lo unico que estas pruebas necesitan saber de el, que es que
     * las escrituras de una transaccion revertida no quedan.
     *
     * <p>Cuenta por <b>profundidad</b>: una llamada anidada a un metodo {@code @Transactional} se
     * une a la transaccion en curso (propagacion {@code REQUIRED}) en lugar de abrir otra, que es
     * justo lo que hace atomico al acto.
     */
    private final class Transacciones implements PlatformTransactionManager {

        /** Cuantas transacciones fisicas se abrieron desde que empezo la peticion. */
        private int abiertas;

        private int profundidad;
        private List<Predio> fotoDePredios = List.of();
        private List<Titularidad> fotoDeTitularidades = List.of();
        private List<FichaCatastral> fotoDeFichas = List.of();
        private List<RegistroDeAuditoria> fotoDeAuditoria = List.of();

        @Override
        public TransactionStatus getTransaction(@Nullable TransactionDefinition definicion) {
            boolean nueva = profundidad++ == 0;
            if (nueva) {
                abiertas++;
                fotoDePredios = List.copyOf(predios.predios);
                fotoDeTitularidades = List.copyOf(predios.titularidades);
                fotoDeFichas = List.copyOf(fichas.versiones);
                fotoDeAuditoria = List.copyOf(asentado);
            }
            return new SimpleTransactionStatus(nueva);
        }

        @Override
        public void commit(TransactionStatus estado) {
            profundidad--;
        }

        @Override
        public void rollback(TransactionStatus estado) {
            if (--profundidad == 0) {
                reemplazar(predios.predios, fotoDePredios);
                reemplazar(predios.titularidades, fotoDeTitularidades);
                reemplazar(fichas.versiones, fotoDeFichas);
                reemplazar(asentado, fotoDeAuditoria);
            }
        }

        private <T> void reemplazar(List<T> lista, List<T> foto) {
            lista.clear();
            lista.addAll(foto);
        }
    }

    /**
     * Predios, catalogo territorial y titularidad en memoria.
     *
     * <p>Imita la <b>unicidad del codigo de referencia catastral</b> con la misma {@link
     * DuplicateKeyException} que Spring traduce de PostgreSQL: sin ella, la traduccion a {@code
     * 409} del controlador no tendria nada que traducir. Lo que no imita es la politica RLS ni el
     * disparador diferido de la titularidad: eso lo verifica {@code FichasDeTodoTipoTest} contra la
     * base.
     */
    private static final class PrediosEnMemoria implements CatastroRepository {

        private final List<Sector> sectores =
                new ArrayList<>(
                        List.of(
                                new Sector(1L, "SC-1", "Sector Centro", "Zona A", true),
                                new Sector(2L, "SC-2", "Sector Norte", "Zona B", true)));

        private final List<Manzana> manzanas =
                new ArrayList<>(List.of(new Manzana(1L, 1L, "001"), new Manzana(2L, 2L, "002")));

        private final List<Predio> predios =
                new ArrayList<>(
                        List.of(
                                activo(2L, PREDIO_CON_FICHA, "AV. SANCHEZ CERRO 200"),
                                activo(3L, PREDIO_SIN_FICHA, "AV. SIN FICHA 300"),
                                new Predio(
                                        4L,
                                        CodigoReferenciaCatastral.de(PREDIO_DE_BAJA),
                                        TipoPredio.URBANO,
                                        null,
                                        null,
                                        "AV. RETIRADA 400",
                                        null,
                                        null,
                                        null,
                                        null,
                                        EstadoPredio.DADO_DE_BAJA)));

        private final List<Titularidad> titularidades = new ArrayList<>();

        /** Cuando esta puesto, el {@code INSERT} choca aunque la lectura previa no viera nada. */
        private boolean simularCarrera;

        /** El ultimo filtro recibido: lo que la capa web compuso a partir de la consulta. */
        private @Nullable FiltroDePredios ultimoFiltro;

        private long siguientePredio = 10L;
        private long siguienteTitularidad = 1L;

        private static Predio activo(long id, String codigo, String direccion) {
            return new Predio(
                    id,
                    CodigoReferenciaCatastral.de(codigo),
                    TipoPredio.URBANO,
                    null,
                    null,
                    direccion,
                    null,
                    null,
                    null,
                    null,
                    EstadoPredio.ACTIVO);
        }

        List<Predio> todos() {
            return List.copyOf(predios);
        }

        Optional<Predio> porCodigo(String codigo) {
            return predioPorCodigo(CodigoReferenciaCatastral.de(codigo));
        }

        /** Cambia una fila sembrada sin pasar por el caso de uso ni contar transacciones. */
        void reemplazar(Predio predio) {
            predios.replaceAll(fila -> Objects.equals(fila.id(), predio.id()) ? predio : fila);
        }

        long idDe(String codigo) {
            return Objects.requireNonNull(porCodigo(codigo).orElseThrow().id());
        }

        @Override
        public Optional<Predio> predioPorCodigo(CodigoReferenciaCatastral codigo) {
            return predios.stream()
                    .filter(predio -> predio.codigo().valor().equals(codigo.valor()))
                    .findFirst();
        }

        @Override
        public Predio guardar(Predio predio) {
            if (predio.esNuevo()) {
                if (simularCarrera || predioPorCodigo(predio.codigo()).isPresent()) {
                    throw new DuplicateKeyException(
                            "duplicate key value violates unique constraint"
                                    + " \"predio_codigo_uq\"");
                }
                Predio guardado =
                        new Predio(
                                siguientePredio++,
                                predio.codigo(),
                                predio.tipo(),
                                predio.viaId(),
                                predio.numeroMunicipal(),
                                predio.direccion(),
                                predio.sectorId(),
                                predio.manzanaId(),
                                predio.lote(),
                                predio.ubigeo(),
                                predio.estado());
                predios.add(guardado);
                return guardado;
            }
            predios.removeIf(otro -> otro.id() != null && otro.id().equals(predio.id()));
            predios.add(predio);
            return predio;
        }

        @Override
        public Titularidad guardar(Titularidad titularidad) {
            Titularidad guardada =
                    new Titularidad(
                            titularidad.esNueva() ? siguienteTitularidad++ : titularidad.id(),
                            titularidad.predioId(),
                            titularidad.contribuyenteId(),
                            titularidad.condicion(),
                            titularidad.porcentaje(),
                            titularidad.vigenciaDesde(),
                            titularidad.vigenciaHasta(),
                            titularidad.documentoOrigen());
            titularidades.removeIf(otra -> otra.id() != null && otra.id().equals(guardada.id()));
            titularidades.add(guardada);
            return guardada;
        }

        @Override
        public Optional<Sector> sectorPorCodigo(String codigo) {
            return sectores.stream().filter(sector -> sector.codigo().equals(codigo)).findFirst();
        }

        @Override
        public List<Manzana> manzanasDe(long sectorId) {
            return manzanas.stream().filter(manzana -> manzana.sectorId() == sectorId).toList();
        }

        @Override
        public Pagina<ManzanaConConteos> manzanas(Sector sector, Paginacion paginacion) {
            throw new UnsupportedOperationException(
                    "La escritura de fichas no lista las manzanas de un sector");
        }

        // ---------- Lo que estos controladores no tocan ----------

        @Override
        public Pagina<SectorConConteos> sectores(Paginacion paginacion) {
            throw new UnsupportedOperationException("La escritura de fichas no lista sectores");
        }

        @Override
        public Optional<Sector> sectorPorId(long id) {
            throw new UnsupportedOperationException("El sector se resuelve por su codigo");
        }

        @Override
        public Sector guardar(Sector sector) {
            throw new UnsupportedOperationException("La escritura de fichas no crea sectores");
        }

        @Override
        public Manzana guardar(Manzana manzana) {
            throw new UnsupportedOperationException("La escritura de fichas no crea manzanas");
        }

        @Override
        public Optional<Predio> predio(long id) {
            return predios.stream().filter(fila -> Objects.equals(fila.id(), id)).findFirst();
        }

        @Override
        public Pagina<PredioDelCatastro> predios(FiltroDePredios filtro, Paginacion paginacion) {
            ultimoFiltro = filtro;
            return Pagina.vacia(paginacion);
        }

        @Override
        public void asignarGeometria(long predioId, String wkt) {
            throw new UnsupportedOperationException("La escritura de fichas no dibuja planos");
        }

        @Override
        public java.util.List<kamayuk.rentas.catastro.dominio.LoteDelPlano> lotesDelPlano(
                kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro, int tope) {
            throw new UnsupportedOperationException("La escritura de fichas no dibuja planos");
        }

        @Override
        public long lotesEnElMarco(kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro) {
            throw new UnsupportedOperationException("La escritura de fichas no dibuja planos");
        }

        @Override
        public long prediosSinGeometria(kamayuk.rentas.catastro.dominio.FiltroDelPlano filtro) {
            throw new UnsupportedOperationException("La escritura de fichas no dibuja planos");
        }

        @Override
        public kamayuk.rentas.catastro.dominio.MarcoDeLoLevantado marcoDeLoLevantado(
                kamayuk.rentas.catastro.dominio.AcotacionDelPlano acotacion) {
            throw new UnsupportedOperationException("La escritura de fichas no dibuja planos");
        }

        @Override
        public Optional<String> geometriaDe(long predioId) {
            return Optional.empty();
        }

        @Override
        public List<Titularidad> titularesDe(long predioId, LocalDate fecha) {
            throw new UnsupportedOperationException("La escritura de fichas no lee titulares");
        }

        @Override
        public java.util.Map<Long, List<Titularidad>> titularesDeVarios(
                java.util.Collection<Long> predioIds, LocalDate fecha) {
            throw new UnsupportedOperationException("La escritura de fichas no lee titulares");
        }

        @Override
        public List<Titularidad> prediosDe(long contribuyenteId, LocalDate fecha) {
            throw new UnsupportedOperationException("La escritura de fichas no lee titulares");
        }

        @Override
        public Optional<Titularidad> titularidad(long id) {
            throw new UnsupportedOperationException("La escritura de fichas no lee titulares");
        }

        @Override
        public List<Inquilino> inquilinosDe(long predioId, LocalDate fecha) {
            throw new UnsupportedOperationException("La escritura de fichas no lee inquilinos");
        }

        @Override
        public Optional<Inquilino> inquilino(long id) {
            throw new UnsupportedOperationException("La escritura de fichas no lee inquilinos");
        }

        @Override
        public Inquilino guardar(Inquilino inquilino) {
            throw new UnsupportedOperationException("La escritura de fichas no crea inquilinos");
        }
    }

    /**
     * Versiones de ficha en memoria.
     *
     * <p>Imita {@code ficha_vigente_uq} —un indice unico parcial: una sola version vigente por
     * predio y tipo—, que es lo que obliga a cerrar antes de abrir. Sin eso, versionar
     * «funcionaria» tambien en el orden equivocado y la prueba no diria nada del orden real.
     */
    private static final class FichasEnMemoria implements FichaCatastralRepository {

        @Override
        public java.util.Optional<kamayuk.rentas.catastro.dominio.FichaCatastral> porId(
                long fichaId) {
            throw new UnsupportedOperationException("esta prueba no lee una version por id");
        }

        private final List<FichaCatastral> versiones = new ArrayList<>();
        private long siguiente = 1L;

        List<FichaCatastral> delPredio(String codigo, PrediosEnMemoria predios) {
            Optional<Predio> predio = predios.porCodigo(codigo);
            return predio.isEmpty()
                    ? List.of()
                    : versiones.stream()
                            .filter(ficha -> ficha.predioId() == predios.idDe(codigo))
                            .toList();
        }

        Optional<FichaCatastral> vigenteDe(long predioId, TipoFicha tipo) {
            return versiones.stream()
                    .filter(
                            ficha ->
                                    ficha.predioId() == predioId
                                            && ficha.tipo() == tipo
                                            && ficha.estaVigente())
                    .findFirst();
        }

        void sembrar(FichaCatastral ficha) {
            insertar(ficha);
        }

        void sembrarRural(long predioId) {
            sembrar(
                    FichaCatastral.primera(
                                    predioId,
                                    TipoFicha.RURAL,
                                    AreaM2.de("25000.00"),
                                    "AGRICOLA",
                                    ALTA,
                                    OrigenDeLaFicha.DECLARACION_JURADA,
                                    "DJ 500-2026",
                                    Observacion.de("Alta de la ficha rural del fundo"))
                            .conDetalle(
                                    DetalleRural.de(
                                            TierraRural.de(
                                                    "CULTIVO_TRANSITORIO",
                                                    Riego.BAJO_RIEGO,
                                                    "1.5000"))));
        }

        void sembrarDeCadaTipo(long predioId) {
            for (TipoFicha tipo : TipoFicha.values()) {
                sembrar(
                        FichaCatastral.primera(
                                predioId,
                                tipo,
                                AreaM2.de("300.00"),
                                "MIXTO",
                                ALTA,
                                OrigenDeLaFicha.DECLARACION_JURADA,
                                "DJ 600-2026",
                                Observacion.de("Alta de la ficha " + tipo)));
            }
        }

        @Override
        public FichaCatastral insertar(FichaCatastral ficha) {
            if (ficha.estaVigente() && vigenteDe(ficha.predioId(), ficha.tipo()).isPresent()) {
                throw new DuplicateKeyException(
                        "duplicate key value violates unique constraint \"ficha_vigente_uq\"");
            }
            FichaCatastral guardada = conIdentificador(ficha, siguiente++);
            versiones.add(guardada);
            return guardada;
        }

        @Override
        public FichaCatastral cerrar(FichaCatastral ficha) {
            versiones.removeIf(otra -> otra.id() != null && otra.id().equals(ficha.id()));
            versiones.add(ficha);
            return ficha;
        }

        @Override
        public Optional<FichaCatastral> vigenteA(long predioId, TipoFicha tipo, LocalDate fecha) {
            return versiones.stream()
                    .filter(
                            ficha ->
                                    ficha.predioId() == predioId
                                            && ficha.tipo() == tipo
                                            && ficha.rigeEn(fecha))
                    .findFirst();
        }

        @Override
        public List<FichaCatastral> historial(long predioId, TipoFicha tipo) {
            return versiones.stream()
                    .filter(ficha -> ficha.predioId() == predioId && ficha.tipo() == tipo)
                    .sorted(Comparator.comparingInt(FichaCatastral::version).reversed())
                    .toList();
        }

        @Override
        public Optional<FichaCatastral> ultimaVersion(long predioId, TipoFicha tipo) {
            return historial(predioId, tipo).stream().findFirst();
        }

        private static FichaCatastral conIdentificador(FichaCatastral ficha, long id) {
            return new FichaCatastral(
                    id,
                    ficha.predioId(),
                    ficha.tipo(),
                    ficha.version(),
                    ficha.areaTerreno(),
                    ficha.uso(),
                    ficha.frontis(),
                    ficha.condicionPropiedad(),
                    ficha.tipoEdificacion(),
                    ficha.denominacion(),
                    ficha.vigenciaDesde(),
                    ficha.vigenciaHasta(),
                    ficha.origen(),
                    ficha.documentoOrigen(),
                    ficha.observacion(),
                    ficha.construcciones(),
                    ficha.instalaciones(),
                    ficha.detalle());
        }

        // ---------- Lo que estos controladores no tocan ----------

        @Override
        public List<Construccion> construccionesDe(long fichaId) {
            throw new UnsupportedOperationException("La version llega entera desde vigenteA");
        }

        @Override
        public List<OtraInstalacion> instalacionesDe(long fichaId) {
            throw new UnsupportedOperationException("La version llega entera desde vigenteA");
        }

        @Override
        public Optional<DetalleDeLaFicha> detalleDe(long fichaId, TipoFicha tipo) {
            throw new UnsupportedOperationException("La version llega entera desde vigenteA");
        }

        @Override
        public Pagina<FichaEncontrada> consultar(
                FiltroDeFichas filtro,
                List<Long> titulares,
                LocalDate fecha,
                Paginacion paginacion) {
            throw new UnsupportedOperationException("La escritura de fichas no consulta la grilla");
        }

        @Override
        public List<VersionDeLaFicha> versionesDe(long predioId, TipoFicha tipo) {
            throw new UnsupportedOperationException("El historico no se pide en una escritura");
        }
    }

    /** Catalogo vial en memoria: una via, la que el alta con ubicacion completa referencia. */
    private static final class ViasEnMemoria implements ViaRepository {

        private final List<Via> catalogo =
                List.of(new Via(1L, "VIA-1", TipoVia.AVENIDA, "GRAU", null, true));

        @Override
        public Optional<Via> findByCodigo(String codigo) {
            return catalogo.stream().filter(via -> via.codigo().equals(codigo)).findFirst();
        }

        @Override
        public Optional<Via> findById(long id) {
            throw new UnsupportedOperationException("La via se resuelve por su codigo");
        }

        @Override
        public Pagina<Via> buscar(CriterioDeVia criterio, Paginacion paginacion) {
            throw new UnsupportedOperationException("La escritura de fichas no lista vias");
        }

        @Override
        public Via save(Via via) {
            throw new UnsupportedOperationException("La escritura de fichas no crea vias");
        }
    }

    /** Padron en memoria: un contribuyente, con su codigo y su identificador interno. */
    private static final class PadronEnMemoria implements DirectorioDeContribuyentes {

        @Override
        public Optional<ResumenDeContribuyente> porCodigo(String codigo) {
            return "CT-0001".equals(codigo)
                    ? Optional.of(
                            new ResumenDeContribuyente(77L, "CT-0001", "PEREZ GARCIA, JUAN", "DNI"))
                    : Optional.empty();
        }

        @Override
        public List<ResumenDeContribuyente> buscar(String texto, int maximo) {
            throw new UnsupportedOperationException("La escritura de fichas no busca en el padron");
        }

        @Override
        public Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids) {
            throw new UnsupportedOperationException("La escritura de fichas no resuelve nombres");
        }

        @Override
        public Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha) {
            throw new UnsupportedOperationException("La escritura de fichas no lee domicilios");
        }
    }
}

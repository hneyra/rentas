package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import kamayuk.comun.verificaciones.contrato.ContratoDelConsumidor;
import kamayuk.rentas.catastro.AcotacionPorPredio;
import kamayuk.rentas.catastro.BusquedaDeFichas;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.verificaciones.ContratoQueConsumeDeCatastro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * La otra mitad de la ida y vuelta: lo que el adaptador PIDE, contra lo que el contrato declara
 * (C-1).
 *
 * <p>{@code LecturaDeCatastroTest} ata los campos que este backend <b>lee</b> de la respuesta a los
 * que el contrato declara. Nada ataba los que <b>manda</b>: el contrato enumera sus parametros a
 * mano y el adaptador construye la URL a mano, asi que los dos podian discrepar sin que nada se
 * pusiera rojo — y el CI del proveedor comprobaria un contrato que su unico cliente no cumple.
 *
 * <p>Es exactamente el defecto que C-1 cerro tres veces: {@code ?aLaFecha=} contra {@code fecha},
 * {@code ?ejercicio=} contra {@code anio}, y {@code soloPredio}/{@code exceptoPredio} declarados y
 * no leidos. Los tres se descartan en silencio con 200 delante, y el sintoma —la grilla del padron
 * entero, la ficha de hoy en vez de la de marzo— no se parece a la causa.
 *
 * <p>Vive en el paquete del adaptador por lo mismo que su hermana: {@code pedir(...)} es de
 * paquete, y se prefiere una prueba dentro a abrir un metodo de produccion para poder probarlo.
 */
@DisplayName("El adaptador de catastro pide exactamente los parametros que el contrato declara")
class PeticionesACatastroTest {

    private static final LocalDate A_LA_FECHA = LocalDate.of(2026, 3, 15);

    @Test
    @DisplayName("la grilla de fichas: los diez, contando las dos formas de la acotacion")
    void laGrillaDeFichasMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        var adaptador = new FichasDelPadronHttp(espia);

        BusquedaDeFichas criterio =
                new BusquedaDeFichas(
                        "270101001",
                        "PEREZ",
                        "M-01",
                        "L-03",
                        "UNICA",
                        AcotacionPorPredio.ninguna());

        // Las dos formas de acotar, porque son dos parametros distintos y una sola peticion
        // solo puede llevar uno: `soloPredio` y `exceptoPredio` se excluyen (catastro los
        // rechaza juntos con 422, y con razon).
        adaptador.buscar(
                criterio.acotadaA(AcotacionPorPredio.soloEstos(List.of(11L, 12L))),
                A_LA_FECHA,
                Paginacion.de(3, 50, "codRefCatastral"));
        adaptador.buscar(
                criterio.acotadaA(AcotacionPorPredio.todosMenosEstos(List.of(13L))),
                A_LA_FECHA,
                Paginacion.de(0, 20, "codRefCatastral"));

        assertThat(mandados(espia))
                .as(
                        "el contrato declara lo que este backend le exige a catastro. Un parametro"
                                + " que el adaptador manda y el contrato no declara NO lo comprueba"
                                + " nadie: viaja en la URL y se descarta en silencio")
                .isEqualTo(declaradosPara("GET /catastro/fichas"));
    }

    @Test
    @DisplayName("y el espia de verdad captura: sin peticiones, esta prueba no diria nada")
    void elEspiaCaptura() {
        CatastroQueNoContesta espia = respuestaDe();
        new ValoresUnitariosHttp(espia).valoresUnitariosVigentesEn(new Ejercicio(2026));
        assertThat(espia.rutas)
                .as(
                        "una prueba que no captura ninguna URL compara el conjunto vacio contra el"
                                + " conjunto vacio y pasa en verde sin haber mirado nada")
                .hasSize(1);
    }

    @Test
    @DisplayName("el cuadro de valores unitarios: `ejercicio`, que es como catastro lo lee")
    void elCuadroMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new ValoresUnitariosHttp(espia).valoresUnitariosVigentesEn(new Ejercicio(2026));

        assertThat(mandados(espia))
                .as(
                        "hasta C-1 mandaba «ejercicio» contra un `@RequestParam int anio`"
                                + " OBLIGATORIO: la peticion no llegaba a 200, salia 400 y el"
                                + " cliente la traducia a «catastro no responde»")
                .isEqualTo(declaradosPara("GET /catastro/tablas/valores-unitarios"));
    }

    @Test
    @DisplayName("lo inscrito de un predio: solo `fecha`, y obligatoria (C-5)")
    void loInscritoMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new CaracteristicasDelPredioHttp(espia).de(11L, A_LA_FECHA);

        assertThat(mandados(espia))
                .as(
                        "sin `fecha` en la URL, catastro resolveria con su reloj y la ficha de"
                                + " marzo saldria siendo la de hoy (#24, #366, C-1)")
                .isEqualTo(declaradosPara("GET /catastro/predios/{predioId}/caracteristicas"));
    }

    @Test
    @DisplayName("si el predio esta en el padron: ningun parametro, y tampoco fecha")
    void elPadronNoLlevaParametros() {
        CatastroQueNoContesta espia = respuestaDe();
        new TitularesDelPredioHttp(espia).estaEnElPadron(11L);

        assertThat(espia.rutas).containsExactly("/catastro/predios/11");
        assertThat(mandados(espia)).isEqualTo(declaradosPara("GET /catastro/predios/{predioId}"));
    }

    @Test
    @DisplayName("el area de una version: por su identificador, sin fecha")
    void elAreaDeLaVersionNoLlevaFecha() {
        CatastroQueNoContesta espia = respuestaDe();
        new CaracteristicasDelPredioHttp(espia).areaDeLaVersion(7L);

        assertThat(espia.rutas).containsExactly("/catastro/fichas/7/area");
        assertThat(mandados(espia))
                .isEqualTo(declaradosPara("GET /catastro/fichas/{fichaId}/area"));
    }

    @Test
    @DisplayName("los titulares de varios predios: UNA peticion con `predio` repetido")
    void losTitularesVanEnUnaPeticion() {
        CatastroQueNoContesta espia = respuestaDe();
        new TitularesDelPredioHttp(espia).deVarios(List.of(11L, 12L, 13L), A_LA_FECHA);

        assertThat(espia.rutas)
                .as(
                        "la forma del puerto se conservo para esto: una pagina de veinte omisos"
                                + " cuesta una peticion y no veinte")
                .hasSize(1);
        assertThat(espia.rutas.get(0))
                .contains("predio=11")
                .contains("predio=12")
                .contains("predio=13");
        assertThat(mandados(espia)).isEqualTo(declaradosPara("GET /catastro/titularidad"));
    }

    @Test
    @DisplayName("y sin ningun predio no sale ninguna peticion")
    void sinPrediosNoSeMandaNada() {
        CatastroQueNoContesta espia = respuestaDe();

        assertThat(new TitularesDelPredioHttp(espia).deVarios(List.of(), A_LA_FECHA)).isEmpty();
        assertThat(espia.rutas)
                .as(
                        "un parametro repetido cero veces llega igual que uno ausente: la peticion"
                                + " seria indistinguible de «no acotes», y catastro la rechaza")
                .isEmpty();
    }

    @Test
    @DisplayName("la cuota de un titular: predio, contribuyente y fecha")
    void laCuotaMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new TitularidadHttp(espia).vigenteDe(11L, 22L, A_LA_FECHA);

        assertThat(mandados(espia)).isEqualTo(declaradosPara("GET /catastro/titularidad/cuota"));
    }

    @Test
    @DisplayName("los predios de un contribuyente: contribuyente y fecha")
    void losPrediosDelTitularMandanLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new PrediosDelContribuyenteHttp(espia).de(22L, A_LA_FECHA);

        assertThat(mandados(espia)).isEqualTo(declaradosPara("GET /catastro/titularidad/predios"));
    }

    // ------------------------------------------------------------------
    // #9 — las cinco operaciones de la etapa 1.

    @Test
    @DisplayName("la zona: `predioId` y `aLaFecha`, que es como ESTA operacion nombra la fecha")
    void laZonaMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new ZonificacionDelPredioHttp(espia).zonaDe(11L, A_LA_FECHA);

        assertThat(mandados(espia))
                .as(
                        "las siete rutas de C-1 leen «fecha» y esta lee «aLaFecha»: el adaptador"
                                + " manda lo que el otro lado lee, no como lo llama el puerto")
                .isEqualTo(declaradosPara("GET /urbano/zonificacion"));
    }

    @Test
    @DisplayName("el riesgo: `predioId` y `aLaFecha`, que `catastro`#18 estreno en esta ruta")
    void elRiesgoMandaLaFecha() {
        CatastroQueNoContesta espia = respuestaDe();
        new RiesgoYItseDelPredioHttp(espia).riesgoDe(11L, A_LA_FECHA);

        assertThat(espia.rutas).containsExactly("/grd/riesgo?predioId=11&aLaFecha=" + A_LA_FECHA);
        assertThat(mandados(espia))
                .as(
                        "hasta #18 esta operacion no leia la fecha y mandarla habria sido el"
                                + " defecto de C-1 al reves —viajar en la URL y descartarse en"
                                + " silencio—; ahora la lee, y NO mandarla seria contestar con lo"
                                + " vigente hoy a quien pregunta por 2024")
                .isEqualTo(declaradosPara("GET /grd/riesgo"));
    }

    @Test
    @DisplayName("el ITSE: `predioId` y `aLaFecha`, porque un certificado vence")
    void elItseMandaLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new RiesgoYItseDelPredioHttp(espia).itseVigenteEn(11L, A_LA_FECHA);

        assertThat(mandados(espia)).isEqualTo(declaradosPara("GET /grd/itse"));
    }

    @Test
    @DisplayName("los frentes: ningun parametro, y el predio va en la ruta")
    void losFrentesNoLlevanParametros() {
        CatastroQueNoContesta espia = respuestaDe();
        new FrentesDelPredioHttp(espia).delPredio(11L);

        assertThat(espia.rutas).containsExactly("/catastro/predios/11/frentes");
        assertThat(mandados(espia))
                .isEqualTo(declaradosPara("GET /catastro/predios/{predioId}/frentes"));
    }

    @Test
    @DisplayName("los hallazgos: `pagina` y `tamano`, y NO el orden, que es lista blanca ajena")
    void losHallazgosMandanLoDeclarado() {
        CatastroQueNoContesta espia = respuestaDe();
        new HallazgosDelPredioHttp(espia).deLaCampania(3L, Paginacion.de(2, 50, "verificadoEn"));

        assertThat(espia.rutas)
                .containsExactly("/fiscalizacion/campanias/3/hallazgos?pagina=2&tamano=50");
        assertThat(mandados(espia))
                .as(
                        "cual campo admite ordenar depende de la tabla, y la tabla es de catastro:"
                                + " pedir uno que no admita seria un 422 sobre una consulta buena")
                .isEqualTo(declaradosPara("GET /fiscalizacion/campanias/{campaniaId}/hallazgos"));
    }

    @Test
    @DisplayName("los hallazgos de UN predio: ningun parametro, y el predio va en la ruta")
    void losHallazgosDelPredioNoLlevanParametros() {
        CatastroQueNoContesta espia = respuestaDe();
        new HallazgosDelPredioHttp(espia).de(11L);

        assertThat(espia.rutas).containsExactly("/fiscalizacion/predios/11/hallazgos");
        assertThat(mandados(espia))
                .as(
                        "no hay fecha que resolver: el hallazgo lleva dentro la version de ficha"
                                + " contra la que se contrasto y el dia en que se verifico")
                .isEqualTo(declaradosPara("GET /fiscalizacion/predios/{predioId}/hallazgos"));
    }

    // ------------------------------------------------------------------

    /**
     * Un catastro de mentira que contesta lo minimo que cada adaptador sabe leer sin caerse.
     *
     * <p>Lo que se mide aqui es la PETICION, no lo que vuelve: de eso se ocupa {@code
     * LecturaDeCatastroTest}, que usa el mismo doble con una respuesta fabricada del contrato.
     */
    private static CatastroQueNoContesta respuestaDe() {
        JsonMapper json = new JsonMapper();
        return new CatastroQueNoContesta(
                ruta -> {
                    // El cuadro sellado sale como ARRAY y la grilla como sobre paginado
                    // (C-1, desajuste 6). Los dos, vacios: aqui se mide la peticion.
                    if (ruta.startsWith("/catastro/tablas/")) {
                        return json.createArrayNode();
                    }
                    ObjectNode cuerpo = json.createObjectNode();
                    // Lo que los adaptadores de C-5 COMPRUEBAN antes de leer una fila: la fecha
                    // con la que se resolvio y el sujeto por el que se pregunto. Se devuelve lo
                    // que se pidio, porque aqui se mide la peticion y no la respuesta — de eso se
                    // ocupa `LecturaDeCatastroTest`.
                    //
                    // Y una fecha por omision: hay operaciones cuya respuesta la trae siempre
                    // aunque la peticion no la mande, asi que sin esto el adaptador fallaria por
                    // falta de un campo obligatorio antes de que esta prueba llegara a mirar la
                    // URL. Desde `catastro`#18 `GET /grd/riesgo` SI la manda, y entonces manda el
                    // eco de abajo, que es lo que su adaptador compara.
                    cuerpo.put("aLaFecha", A_LA_FECHA.toString());
                    eco(ruta, "fecha").ifPresent(fecha -> cuerpo.put("aLaFecha", fecha));
                    eco(ruta, "aLaFecha").ifPresent(fecha -> cuerpo.put("aLaFecha", fecha));
                    // Y la vigencia de la zona (#9), por lo mismo: es obligatoria en la respuesta
                    // de `GET /urbano/zonificacion`, y lo que aqui se mide es la peticion.
                    cuerpo.put("vigenciaDesde", A_LA_FECHA.toString());
                    eco(ruta, "predio").ifPresent(p -> numero(cuerpo, "predioId", p));
                    // Y el predio que va en la RUTA y no en un parametro, para la lectura de
                    // hallazgos por predio (`catastro`#17): su adaptador comprueba el sobre antes
                    // de leer una fila, asi que sin este eco fallaria por la respuesta antes de
                    // que esta prueba llegara a mirar la URL.
                    java.util.regex.Matcher enLaRuta =
                            java.util.regex.Pattern.compile("/predios/(\\d+)/hallazgos")
                                    .matcher(ruta);
                    if (enLaRuta.find()) {
                        cuerpo.put("predioId", Long.parseLong(enLaRuta.group(1)));
                    }
                    eco(ruta, "contribuyente").ifPresent(c -> numero(cuerpo, "contribuyenteId", c));
                    cuerpo.put("enElPadron", false);
                    cuerpo.put("existe", false);
                    cuerpo.put("tieneCuota", false);
                    cuerpo.putArray("predios");
                    cuerpo.putArray("contenido");
                    cuerpo.put("totalElementos", 0);
                    return cuerpo;
                });
    }

    /**
     * El eco numerico, cuando el valor lo es.
     *
     * <p>La grilla de fichas manda {@code ?contribuyente=PEREZ} —ahi el filtro es el NOMBRE— y las
     * rutas de C-5 mandan el identificador. Es el mismo nombre de parametro para dos cosas
     * distintas en dos operaciones distintas, y este doble contesta a las dos.
     */
    private static void numero(ObjectNode cuerpo, String campo, String valor) {
        try {
            cuerpo.put(campo, Long.parseLong(valor));
        } catch (NumberFormatException noEsUnIdentificador) {
            // La grilla de fichas: aqui no se mide su respuesta.
        }
    }

    /** El valor que la URL lleva para ese parametro, si lo lleva. */
    private static java.util.Optional<String> eco(String ruta, String parametro) {
        int interrogacion = ruta.indexOf('?');
        if (interrogacion < 0) {
            return java.util.Optional.empty();
        }
        for (String par : ruta.substring(interrogacion + 1).split("&")) {
            String[] partes = par.split("=", 2);
            if (partes.length == 2 && partes[0].equals(parametro)) {
                return java.util.Optional.of(partes[1]);
            }
        }
        return java.util.Optional.empty();
    }

    /** Los nombres de parametro de todas las URL que el adaptador construyo. */
    private static Set<String> mandados(CatastroQueNoContesta espia) {
        Set<String> nombres = new TreeSet<>();
        for (String ruta : espia.rutas) {
            int interrogacion = ruta.indexOf('?');
            if (interrogacion < 0) {
                continue;
            }
            for (String par : ruta.substring(interrogacion + 1).split("&")) {
                if (!par.isBlank()) {
                    nombres.add(par.split("=", 2)[0]);
                }
            }
        }
        return nombres;
    }

    /** Lo que el contrato comprometido declara para esa operacion. */
    private static Set<String> declaradosPara(String operacion) {
        ContratoDelConsumidor contrato = new ContratoQueConsumeDeCatastro().contrato();
        ContratoDelConsumidor.OperacionEsperada esperada = contrato.operaciones().get(operacion);
        assertThat(esperada).as("el contrato no declara «%s»", operacion).isNotNull();
        return new TreeSet<>(esperada.parametros());
    }
}

package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.CaracteristicasDelPredio;
import kamayuk.rentas.catastro.CuotaDeTitularidad;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.catastro.FrentesInscritos;
import kamayuk.rentas.catastro.HallazgoCatastral;
import kamayuk.rentas.catastro.ItseDelPredio;
import kamayuk.rentas.catastro.PredioDelContribuyente;
import kamayuk.rentas.catastro.RiesgoDelPredio;
import kamayuk.rentas.catastro.TitularDelPredio;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.catastro.ZonaDelPredio;
import kamayuk.rentas.compartido.Pagina;
import kamayuk.rentas.compartido.Paginacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.verificaciones.ContratoQueConsumeDeCatastro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * La ida y vuelta que hace que el contrato con {@code catastro} no sea otra copia a mano.
 *
 * <p>{@code ContratoQueConsumeDeCatastro} publica lo que este backend espera, y su prueba comprueba
 * que el archivo comprometido sea el que produce esa declaracion. Eso, solo, <b>no puede fallar por
 * lo que importa</b>: los dos lados salen del mismo repositorio, que es exactamente lo que P5E §6.3
 * se nego a escribir.
 *
 * <p>Lo que lo sostiene es esto: se fabrica una respuesta con <b>los campos que el contrato
 * declara</b> —ni uno mas, y eso se afirma— y se pasa por {@link ClienteHttpDeCatastro#ficha}, que
 * es codigo de produccion. Si alguien cambia el adaptador para leer un nombre que el contrato no
 * declara, ese campo no esta en la respuesta fabricada y sale un cero o una cadena vacia.
 *
 * <p>Vive en este paquete y no en {@code verificaciones} porque {@code ficha(...)} es de paquete:
 * se prefiere una prueba en el paquete del adaptador a abrir un metodo de produccion para poder
 * probarlo.
 */
@DisplayName("El adaptador de catastro lee exactamente lo que el contrato declara")
class LecturaDeCatastroTest {

    @Test
    @DisplayName("la ficha se lee entera de una respuesta con la forma declarada")
    void laFichaSeLeeEnteraDeLaFormaDeclarada() {
        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("fichaId", 7);
        fabricada.put("predioId", 11);
        fabricada.put("codRefCatastral", "200105-01-02-003");
        fabricada.put("direccion", "AV. CAYETANO HEREDIA 100");
        fabricada.put("manzana", "M-01");
        fabricada.put("lote", "L-03");
        fabricada.put("tipo", "URBANA");
        fabricada.put("version", 4);
        fabricada.put("areaTerreno", "180.50");
        fabricada.put("areaConstruida", "95.25");
        fabricada.put("uso", "CASA HABITACION");
        fabricada.put("vigenciaDesde", "2026-03-15");
        fabricada.put("titular", "PENA GARCIA, MARIA");

        assertThat(fabricada.keySet())
                .as(
                        "la respuesta fabricada tiene que tener EXACTAMENTE los campos del contrato:"
                                + " uno de mas la haria pasar aunque el contrato no lo declarase")
                .isEqualTo(ContratoQueConsumeDeCatastro.FILA_DE_FICHA.keySet());

        JsonNode fila = new JsonMapper().valueToTree(fabricada);
        FichaDelPadron ficha = ClienteHttpDeCatastro.ficha(fila);

        // Ni un cero ni una cadena vacia: cada uno de estos es un campo que el adaptador
        // encontro con el nombre que el contrato promete.
        assertThat(ficha.fichaId()).isEqualTo(7L);
        assertThat(ficha.predioId()).isEqualTo(11L);
        assertThat(ficha.codigoReferenciaCatastral()).isEqualTo("200105-01-02-003");
        assertThat(ficha.direccion()).isEqualTo("AV. CAYETANO HEREDIA 100");
        assertThat(ficha.manzana()).isEqualTo("M-01");
        assertThat(ficha.lote()).isEqualTo("L-03");
        assertThat(ficha.tipo()).isEqualTo("URBANA");
        assertThat(ficha.version()).isEqualTo(4);
        assertThat(ficha.areaTerreno().valor()).isEqualByComparingTo("180.50");
        assertThat(ficha.areaConstruida()).isNotNull();
        assertThat(ficha.uso()).isEqualTo("CASA HABITACION");
        assertThat(ficha.vigenciaDesde()).isEqualTo(LocalDate.of(2026, 3, 15));
        assertThat(ficha.titular()).isEqualTo("PENA GARCIA, MARIA");
    }

    @Test
    @DisplayName("el cuadro sellado se lee entero de un ARRAY con la forma declarada (C-1)")
    void elCuadroSeLeeEnteroDeLaFormaDeclarada() {
        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("partida", "MUROS");
        fabricada.put("categoria", "C");
        fabricada.put("anioConstruccionDesde", 1990);
        fabricada.put("anioConstruccionHasta", 2000);
        fabricada.put("valorM2", "123.45");

        assertThat(fabricada.keySet())
                .as(
                        "la respuesta fabricada tiene que tener EXACTAMENTE los campos del"
                                + " contrato: uno de mas la haria pasar aunque el contrato no lo"
                                + " declarase")
                .isEqualTo(ContratoQueConsumeDeCatastro.FILA_DE_VALOR_UNITARIO.keySet());

        JsonMapper json = new JsonMapper();
        ArrayNode cuadro = json.createArrayNode();
        cuadro.add(json.valueToTree(fabricada));

        List<ValorUnitarioPublicado> filas =
                new ValoresUnitariosHttp(new CatastroQueNoContesta(ruta -> cuadro))
                        .valoresUnitariosVigentesEn(new Ejercicio(2026));

        // El defecto que C-1 cerro no daba error: `path("contenido")` sobre un array devuelve
        // un nodo ausente, asi que el cuadro salia VACIO con un 200 delante — que se lee como
        // «este ejercicio no tiene cuadro publicado», que es lo contrario de lo que pasa.
        assertThat(filas).hasSize(1);
        ValorUnitarioPublicado fila = filas.get(0);
        assertThat(fila.partida()).isEqualTo("MUROS");
        assertThat(fila.categoria()).isEqualTo('C');
        assertThat(fila.anioConstruccionDesde()).isEqualTo(1990);
        assertThat(fila.anioConstruccionHasta()).isEqualTo(2000);
        assertThat(fila.valorM2().valor()).isEqualByComparingTo("123.45");
    }

    @Test
    @DisplayName(
            "y una respuesta que NO es un array falla en voz alta, no devuelve el cuadro vacio")
    void unCuadroQueNoEsUnArrayFallaEnVozAlta() {
        JsonMapper json = new JsonMapper();
        ObjectNode sobre = json.createObjectNode();
        sobre.putArray("contenido");

        assertThatThrownBy(
                        () ->
                                new ValoresUnitariosHttp(new CatastroQueNoContesta(ruta -> sobre))
                                        .valoresUnitariosVigentesEn(new Ejercicio(2026)))
                .as(
                        "un cuadro vacio se leeria como «este ejercicio no tiene cuadro» y la obra"
                                + " saldria valorizada en 0,00 (#48)")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class);
    }

    // ------------------------------------------------------------------
    // C-5 — las cinco lecturas que P5C dejo sin ruta. La misma ida y vuelta: se fabrica una
    // respuesta con EXACTAMENTE los campos del contrato y se pasa por el adaptador de produccion.

    private static final LocalDate AL_30_DE_JUNIO = LocalDate.of(2024, 6, 30);

    @Test
    @DisplayName("lo inscrito de un predio se lee entero de la forma declarada")
    void loInscritoSeLeeEnteroDeLaFormaDeclarada() {
        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("enElPadron", true);
        fabricada.put("fichaId", 7);
        fabricada.put("fichaEconomicaId", 9);
        fabricada.put("uso", "CASA HABITACION");
        fabricada.put("sectorCodigo", "S-05");
        fabricada.put("areaTerreno", "120.00");
        fabricada.put("aLaFecha", AL_30_DE_JUNIO.toString());

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.CARACTERISTICAS_DEL_PREDIO.keySet());

        CaracteristicasDelPredioHttp adaptador = new CaracteristicasDelPredioHttp(doble(fabricada));
        assertThat(adaptador.fichaVigenteEn(11L, AL_30_DE_JUNIO)).contains(7L);
        assertThat(adaptador.fichaEconomicaVigenteEn(11L, AL_30_DE_JUNIO)).contains(9L);

        CaracteristicasDelPredio caracteristicas = adaptador.de(11L, AL_30_DE_JUNIO).orElseThrow();
        assertThat(caracteristicas.uso()).isEqualTo("CASA HABITACION");
        assertThat(caracteristicas.sectorCodigo()).isEqualTo("S-05");
        assertThat(caracteristicas.areaTerreno()).isNotNull();
        assertThat(caracteristicas.areaTerreno().valor()).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("y una respuesta resuelta con OTRA fecha falla en voz alta, no devuelve la ficha")
    void unaRespuestaDeOtraFechaFallaEnVozAlta() {
        Map<String, Object> deOtroDia = new LinkedHashMap<>();
        deOtroDia.put("predioId", 11);
        deOtroDia.put("enElPadron", true);
        deOtroDia.put("fichaId", 7);
        deOtroDia.put("fichaEconomicaId", null);
        deOtroDia.put("uso", "COMERCIO");
        deOtroDia.put("sectorCodigo", "S-05");
        deOtroDia.put("areaTerreno", "180.50");
        // Lo que pasaria si el parametro viajara con otro nombre y catastro resolviera con su
        // reloj: la respuesta llega con 200 y es de otro dia (C-1, #24, #366).
        deOtroDia.put("aLaFecha", "2026-08-30");

        assertThatThrownBy(
                        () ->
                                new CaracteristicasDelPredioHttp(doble(deOtroDia))
                                        .fichaVigenteEn(11L, AL_30_DE_JUNIO))
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .hasMessageContaining("2024-06-30")
                .hasMessageContaining("2026-08-30");
    }

    @Test
    @DisplayName("el area de una version se lee entera de la forma declarada")
    void elAreaDeLaVersionSeLeeEnteraDeLaFormaDeclarada() {
        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("fichaId", 7);
        fabricada.put("existe", true);
        fabricada.put("areaTerreno", "120.00");

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.AREA_DE_LA_VERSION.keySet());

        assertThat(
                        new CaracteristicasDelPredioHttp(doble(fabricada))
                                .areaDeLaVersion(7L)
                                .orElseThrow()
                                .valor())
                .isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("«esta en el padron» se lee del campo, no del codigo de estado")
    void elPadronSeLeeDelCampo() {
        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("enElPadron", true);

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.PREDIO_EN_EL_PADRON.keySet());

        assertThat(new TitularesDelPredioHttp(doble(fabricada)).estaEnElPadron(11L)).isTrue();
    }

    @Test
    @DisplayName("las cuotas de un predio se leen enteras de la forma declarada")
    void lasCuotasSeLeenEnterasDeLaFormaDeclarada() {
        Map<String, Object> cuota = new LinkedHashMap<>();
        cuota.put("contribuyenteId", 22);
        cuota.put("condicion", "COPROPIETARIO");
        cuota.put("porcentaje", "50.0000");

        assertThat(cuota.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.CUOTA_DE_UN_TITULAR.keySet());

        JsonMapper json = new JsonMapper();
        ObjectNode fila = json.createObjectNode();
        fila.put("predioId", 11);
        fila.set("cuotas", json.createArrayNode().add(json.valueToTree(cuota)));
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.put("aLaFecha", AL_30_DE_JUNIO.toString());
        cuerpo.set("predios", json.createArrayNode().add(fila));

        List<TitularDelPredio> cuotas =
                new TitularesDelPredioHttp(new CatastroQueNoContesta(ruta -> cuerpo))
                        .de(11L, AL_30_DE_JUNIO);

        assertThat(cuotas).hasSize(1);
        assertThat(cuotas.get(0).contribuyenteId()).isEqualTo(22L);
        assertThat(cuotas.get(0).condicion()).isEqualTo("COPROPIETARIO");
        assertThat(cuotas.get(0).porcentaje().valor()).isEqualByComparingTo("50.0000");
    }

    @Test
    @DisplayName("la cuota de un titular trae el identificador con el que se transfiere")
    void laCuotaDelTitularSeLeeEnteraDeLaFormaDeclarada() {
        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("contribuyenteId", 22);
        fabricada.put("aLaFecha", AL_30_DE_JUNIO.toString());
        fabricada.put("tieneCuota", true);
        fabricada.put("titularidadId", 33);
        fabricada.put("porcentaje", "100.0000");

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.CUOTA_DEL_TITULAR.keySet());

        CuotaDeTitularidad cuota =
                new TitularidadHttp(doble(fabricada))
                        .vigenteDe(11L, 22L, AL_30_DE_JUNIO)
                        .orElseThrow();
        assertThat(cuota.titularidadId()).isEqualTo(33L);
        assertThat(cuota.predioId()).isEqualTo(11L);
        assertThat(cuota.contribuyenteId()).isEqualTo(22L);
        assertThat(cuota.porcentaje().valor()).isEqualByComparingTo("100.0000");
    }

    @Test
    @DisplayName("los predios de un contribuyente se leen enteros, con los DOS porcentajes")
    void losPrediosDelTitularSeLeenEnterosDeLaFormaDeclarada() {
        Map<String, Object> predio = new LinkedHashMap<>();
        predio.put("predioId", 11);
        predio.put("codRefCatastral", "200105-01-02-003");
        predio.put("tipo", "URBANO");
        predio.put("direccion", "AV. CAYETANO HEREDIA 100");
        predio.put("porcentajeTitularidad", "50.0000");
        predio.put("porcentajeRegistradoDelPredio", "100.0000");

        assertThat(predio.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.PREDIO_DEL_TITULAR.keySet());

        JsonMapper json = new JsonMapper();
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.put("contribuyenteId", 22);
        cuerpo.put("aLaFecha", AL_30_DE_JUNIO.toString());
        cuerpo.set("predios", json.createArrayNode().add(json.valueToTree(predio)));

        List<PredioDelContribuyente> predios =
                new PrediosDelContribuyenteHttp(new CatastroQueNoContesta(ruta -> cuerpo))
                        .de(22L, AL_30_DE_JUNIO);

        assertThat(predios).hasSize(1);
        assertThat(predios.get(0).porcentajeTitularidad().valor()).isEqualByComparingTo("50.0000");
        assertThat(predios.get(0).porcentajeRegistradoDelPredio().valor())
                .as("sin el segundo, el aviso de titularidad incompleta deja de existir (#690)")
                .isEqualByComparingTo("100.0000");
        assertThat(predios.get(0).titularidadCompleta()).isTrue();
    }

    @Test
    @DisplayName("y una respuesta de OTRO contribuyente falla: seria determinar con predios ajenos")
    void unaRespuestaDeOtroContribuyenteFalla() {
        JsonMapper json = new JsonMapper();
        ObjectNode deOtro = json.createObjectNode();
        deOtro.put("contribuyenteId", 999);
        deOtro.put("aLaFecha", AL_30_DE_JUNIO.toString());
        deOtro.putArray("predios");

        assertThatThrownBy(
                        () ->
                                new PrediosDelContribuyenteHttp(
                                                new CatastroQueNoContesta(ruta -> deOtro))
                                        .de(22L, AL_30_DE_JUNIO))
                .as("el guardia de #298: sin comprobar la fila se determina el predial de otro")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .hasMessageContaining("999");
    }

    // ------------------------------------------------------------------
    // #9 — las cinco operaciones de la etapa 1. La misma ida y vuelta: se fabrica una respuesta
    // con EXACTAMENTE los campos del contrato y se pasa por el adaptador de produccion.

    @Test
    @DisplayName("la zona del predio se lee entera de la forma declarada, con sus parametros")
    void laZonaSeLeeEnteraDeLaFormaDeclarada() {
        Map<String, Object> parametro = new LinkedHashMap<>();
        parametro.put("clave", "ALTURA_MAXIMA");
        parametro.put("valor", "4");
        parametro.put("unidad", "PISOS");
        assertThat(parametro.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.PARAMETRO_URBANISTICO.keySet());

        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("aLaFecha", AL_30_DE_JUNIO.toString());
        fabricada.put("codigo", "RDM");
        fabricada.put("nombre", "RESIDENCIAL DE DENSIDAD MEDIA");
        fabricada.put("plan", "PDU-2026-DEMO");
        fabricada.put("ordenanza", "OM-012-2026-MPS");
        fabricada.put("vigenciaDesde", "2024-01-01");
        fabricada.put("vigenciaHasta", null);
        fabricada.put("parametros", List.of(parametro));

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.ZONA_DEL_PREDIO.keySet());

        ZonaDelPredio zona =
                new ZonificacionDelPredioHttp(doble(fabricada)).zonaDe(11L, AL_30_DE_JUNIO);

        assertThat(zona.aLaFecha()).isEqualTo(AL_30_DE_JUNIO);
        assertThat(zona.codigo()).isEqualTo("RDM");
        assertThat(zona.nombre()).isEqualTo("RESIDENCIAL DE DENSIDAD MEDIA");
        assertThat(zona.plan()).isEqualTo("PDU-2026-DEMO");
        assertThat(zona.ordenanza())
                .as("sin la norma que la aprobo, una denegacion por zona no se puede notificar")
                .isEqualTo("OM-012-2026-MPS");
        assertThat(zona.vigenciaDesde()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(zona.vigenciaHasta()).isNull();
        assertThat(zona.parametros()).hasSize(1);
        assertThat(zona.parametros().get(0).clave()).isEqualTo("ALTURA_MAXIMA");
        assertThat(zona.parametros().get(0).valor()).isEqualTo("4");
        assertThat(zona.parametros().get(0).unidad()).isEqualTo("PISOS");
    }

    @Test
    @DisplayName("y una zona resuelta con OTRA fecha falla en voz alta")
    void unaZonaDeOtraFechaFallaEnVozAlta() {
        Map<String, Object> deOtroDia = new LinkedHashMap<>();
        deOtroDia.put("aLaFecha", "2026-08-30");
        deOtroDia.put("codigo", "RDM");
        deOtroDia.put("nombre", "RESIDENCIAL DE DENSIDAD MEDIA");
        deOtroDia.put("plan", "PDU-2026-DEMO");
        deOtroDia.put("ordenanza", "OM-012-2026-MPS");
        deOtroDia.put("vigenciaDesde", "2024-01-01");
        deOtroDia.put("vigenciaHasta", null);
        deOtroDia.put("parametros", List.of());

        assertThatThrownBy(
                        () ->
                                new ZonificacionDelPredioHttp(doble(deOtroDia))
                                        .zonaDe(11L, AL_30_DE_JUNIO))
                .as("un plan se sustituye por otro: la zona de 2026 no es la de 2024")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .hasMessageContaining("2024-06-30")
                .hasMessageContaining("2026-08-30");
    }

    @Test
    @DisplayName("el riesgo del suelo se lee entero: zonas, fajas y el derivado que decide")
    void elRiesgoSeLeeEnteroDeLaFormaDeclarada() {
        Map<String, Object> zona = new LinkedHashMap<>();
        zona.put("id", 5);
        zona.put("codigo", "ZR-INUND-01");
        zona.put("fenomeno", "INUNDACION");
        zona.put("nivel", "MUY_ALTO");
        zona.put("mitigable", false);
        zona.put("fuente", "CENEPRED");
        zona.put("documentoOrigen", "Carta de peligro 2025-01");
        zona.put("vigenciaDesde", "2025-01-01");
        zona.put("vigenciaHasta", null);
        assertThat(zona.keySet()).isEqualTo(ContratoQueConsumeDeCatastro.ZONA_DE_RIESGO.keySet());

        Map<String, Object> faja = new LinkedHashMap<>();
        faja.put("id", 9);
        faja.put("codigo", "FM-CHIRA-03");
        faja.put("cuerpoDeAgua", "RIO CHIRA");
        faja.put("anchoM", "25.00");
        faja.put("fuente", "ANA");
        faja.put("documentoOrigen", "R.D. 0123-2025-ANA");
        faja.put("vigenciaDesde", "2025-03-01");
        faja.put("vigenciaHasta", null);
        assertThat(faja.keySet()).isEqualTo(ContratoQueConsumeDeCatastro.FAJA_MARGINAL.keySet());

        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("aLaFecha", AL_30_DE_JUNIO.toString());
        fabricada.put("hayRiesgoNoMitigable", true);
        fabricada.put("zonas", List.of(zona));
        fabricada.put("fajasMarginales", List.of(faja));

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.RIESGO_DEL_PREDIO.keySet());

        RiesgoDelPredio riesgo = new RiesgoYItseDelPredioHttp(doble(fabricada)).riesgoDe(11L);

        assertThat(riesgo.predioId()).isEqualTo(11L);
        assertThat(riesgo.aLaFecha()).isEqualTo(AL_30_DE_JUNIO);
        assertThat(riesgo.hayRiesgoNoMitigable())
                .as("es el dato que decide, y viene derivado: aqui no se recalcula")
                .isTrue();
        assertThat(riesgo.zonas()).hasSize(1);
        assertThat(riesgo.zonas().get(0).nivel()).isEqualTo("MUY_ALTO");
        assertThat(riesgo.zonas().get(0).mitigable()).isFalse();
        assertThat(riesgo.zonas().get(0).documentoOrigen()).isEqualTo("Carta de peligro 2025-01");
        assertThat(riesgo.fajasMarginales()).hasSize(1);
        assertThat(riesgo.fajasMarginales().get(0).cuerpoDeAgua()).isEqualTo("RIO CHIRA");
        assertThat(riesgo.fajasMarginales().get(0).ancho().magnitud())
                .isEqualByComparingTo("25.00");
        assertThat(riesgo.fajasMarginales().get(0).ancho().unidad())
                .as("la unidad viaja en el NOMBRE del campo, y este lado la mete dentro del dato")
                .isEqualTo("ML");
    }

    @Test
    @DisplayName("el ITSE vigente se lee entero, con la fecha a la que se contesto")
    void elItseSeLeeEnteroDeLaFormaDeclarada() {
        Map<String, Object> certificado = new LinkedHashMap<>();
        certificado.put("id", 3);
        certificado.put("numero", "ITSE-2024-001");
        certificado.put("nivelRiesgo", "BAJO");
        certificado.put("modalidad", "POSTERIOR");
        certificado.put("vigenciaDesde", "2024-01-10");
        certificado.put("vigenciaHasta", "2026-01-09");
        certificado.put("fechaAnulacion", null);
        assertThat(certificado.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.CERTIFICADO_ITSE.keySet());

        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("aLaFecha", AL_30_DE_JUNIO.toString());
        fabricada.put("vigentes", List.of(certificado));

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.ITSE_DEL_PREDIO.keySet());

        ItseDelPredio itse =
                new RiesgoYItseDelPredioHttp(doble(fabricada)).itseVigenteEn(11L, AL_30_DE_JUNIO);

        assertThat(itse.aLaFecha())
                .as("no se contesta «tiene ITSE» sino «tenia ITSE ese dia» (regla 9)")
                .isEqualTo(AL_30_DE_JUNIO);
        assertThat(itse.vigentes()).hasSize(1);
        assertThat(itse.vigentes().get(0).numero()).isEqualTo("ITSE-2024-001");
        assertThat(itse.vigentes().get(0).nivelRiesgo()).isEqualTo("BAJO");
        assertThat(itse.vigentes().get(0).vigenciaHasta()).isEqualTo(LocalDate.of(2026, 1, 9));
        assertThat(itse.vigentes().get(0).fechaAnulacion()).isNull();
    }

    @Test
    @DisplayName("los frentes se leen enteros, y la longitud conserva su unidad")
    void losFrentesSeLeenEnterosDeLaFormaDeclarada() {
        Map<String, Object> frente = new LinkedHashMap<>();
        frente.put("id", 4);
        frente.put("viaId", 21);
        frente.put("viaCodigo", "AV-0007");
        frente.put("viaNombre", "AV. CAYETANO HEREDIA");
        frente.put("longitud", "18.50 ML");
        frente.put("longitudEstado", "CONFIRMADA");
        frente.put("esPrincipal", true);
        frente.put("numeracion", "100");
        frente.put("retiro", "3.00 ML");
        frente.put("confirmadoPor", "jperez");
        frente.put("confirmadoEn", "2026-02-03T10:15:30Z");
        assertThat(frente.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.FRENTE_DEL_PREDIO.keySet());

        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("frentes", List.of(frente));
        fabricada.put("derivadoEn", "2026-02-01T08:00:00Z");
        fabricada.put("frentesDerivados", 1);
        fabricada.put("motivoDeLaDerivacion", null);

        assertThat(fabricada.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.FRENTES_DEL_PREDIO.keySet());

        FrentesInscritos frentes = new FrentesDelPredioHttp(doble(fabricada)).delPredio(11L);

        assertThat(frentes.predioId()).isEqualTo(11L);
        assertThat(frentes.derivadoEn())
                .as("«no da a ninguna calle» y «nadie lo ha derivado» son la misma lista vacia")
                .isEqualTo("2026-02-01T08:00:00Z");
        assertThat(frentes.frentesDerivados()).isEqualTo(1);
        assertThat(frentes.frentes()).hasSize(1);
        assertThat(frentes.frentes().get(0).viaNombre()).isEqualTo("AV. CAYETANO HEREDIA");
        assertThat(frentes.frentes().get(0).longitud().magnitud()).isEqualByComparingTo("18.50");
        assertThat(frentes.frentes().get(0).longitud().unidad())
                .as("el barrido va sobre metros LINEALES y el recojo sobre cuadrados")
                .isEqualTo("ML");
        assertThat(frentes.frentes().get(0).longitudEstado()).isEqualTo("CONFIRMADA");
        assertThat(frentes.frentes().get(0).confirmadoPor()).isEqualTo("jperez");
        assertThat(frentes.frentes().get(0).retiro()).isNotNull();
    }

    @Test
    @DisplayName("y una longitud sin unidad se rechaza, no se le supone ML")
    void unaLongitudSinUnidadSeRechaza() {
        Map<String, Object> frente = new LinkedHashMap<>();
        frente.put("id", 4);
        frente.put("viaId", 21);
        frente.put("viaCodigo", "AV-0007");
        frente.put("viaNombre", "AV. CAYETANO HEREDIA");
        frente.put("longitud", "18.50");
        frente.put("longitudEstado", "PROPUESTA");
        frente.put("esPrincipal", true);
        frente.put("numeracion", null);
        frente.put("retiro", null);
        frente.put("confirmadoPor", null);
        frente.put("confirmadoEn", null);

        Map<String, Object> fabricada = new LinkedHashMap<>();
        fabricada.put("predioId", 11);
        fabricada.put("frentes", List.of(frente));
        fabricada.put("derivadoEn", null);
        fabricada.put("frentesDerivados", null);
        fabricada.put("motivoDeLaDerivacion", null);

        assertThatThrownBy(() -> new FrentesDelPredioHttp(doble(fabricada)).delPredio(11L))
                .as("suponerle ML es escribir la confusion que separa el barrido del recojo")
                .isInstanceOf(ClienteHttpDeCatastro.CatastroInalcanzable.class)
                .hasMessageContaining("sin su unidad");
    }

    @Test
    @DisplayName("los hallazgos se leen enteros: dos superficies, su resta, y ningun importe")
    void losHallazgosSeLeenEnterosDeLaFormaDeclarada() {
        Map<String, Object> hallazgo = new LinkedHashMap<>();
        hallazgo.put("id", 31);
        hallazgo.put("candidatoId", 77);
        hallazgo.put("clase", "SUBVALUACION");
        hallazgo.put("predioId", 11);
        hallazgo.put("fichaId", 7);
        hallazgo.put("areaDeLaFicha", "120.00");
        hallazgo.put("areaVerificada", "180.50");
        hallazgo.put("excesoVerificado", "60.50");
        hallazgo.put("inspector", "mlopez");
        hallazgo.put("verificadoEn", "2026-05-12");
        hallazgo.put("estado", "FIRME");

        assertThat(hallazgo.keySet())
                .isEqualTo(ContratoQueConsumeDeCatastro.FILA_DE_HALLAZGO.keySet());

        JsonMapper json = new JsonMapper();
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.set("contenido", json.createArrayNode().add(json.valueToTree(hallazgo)));
        cuerpo.put("totalElementos", 1);

        Pagina<HallazgoCatastral> pagina =
                new HallazgosDelPredioHttp(new CatastroQueNoContesta(ruta -> cuerpo))
                        .deLaCampania(3L, Paginacion.de(0, 20, "verificadoEn"));

        assertThat(pagina.totalElementos()).isEqualTo(1L);
        assertThat(pagina.contenido()).hasSize(1);
        HallazgoCatastral leido = pagina.contenido().get(0);
        assertThat(leido.id()).isEqualTo(31L);
        assertThat(leido.candidatoId()).isEqualTo(77L);
        assertThat(leido.clase()).isEqualTo("SUBVALUACION");
        assertThat(leido.predioId()).isEqualTo(11L);
        assertThat(leido.fichaId())
                .as("sin la version contra la que se comparo, el exceso no significa nada")
                .isEqualTo(7L);
        assertThat(leido.areaDeLaFicha()).isNotNull();
        assertThat(leido.areaVerificada().valor()).isEqualByComparingTo("180.50");
        assertThat(leido.excesoVerificado()).isNotNull();
        assertThat(leido.excesoVerificado().valor()).isEqualByComparingTo("60.50");
        assertThat(leido.verificadoEn()).isEqualTo(LocalDate.of(2026, 5, 12));
        assertThat(leido.estado()).isEqualTo("FIRME");
    }

    @Test
    @DisplayName("y un omiso catastral llega sin predio y sin exceso: nulo, que no es cero")
    void unOmisoLlegaSinPredioYSinExceso() {
        Map<String, Object> hallazgo = new LinkedHashMap<>();
        hallazgo.put("id", 32);
        hallazgo.put("candidatoId", 78);
        hallazgo.put("clase", "OMISO");
        hallazgo.put("predioId", null);
        hallazgo.put("fichaId", null);
        hallazgo.put("areaDeLaFicha", null);
        hallazgo.put("areaVerificada", "240.00");
        hallazgo.put("excesoVerificado", null);
        hallazgo.put("inspector", "mlopez");
        hallazgo.put("verificadoEn", "2026-05-12");
        hallazgo.put("estado", "FIRME");

        JsonMapper json = new JsonMapper();
        ObjectNode cuerpo = json.createObjectNode();
        cuerpo.set("contenido", json.createArrayNode().add(json.valueToTree(hallazgo)));
        cuerpo.put("totalElementos", 1);

        HallazgoCatastral leido =
                new HallazgosDelPredioHttp(new CatastroQueNoContesta(ruta -> cuerpo))
                        .deLaCampania(3L, Paginacion.de(0, 20, "verificadoEn"))
                        .contenido()
                        .get(0);

        assertThat(leido.predioId())
                .as("un omiso catastral, por definicion, no esta en el padron")
                .isNull();
        assertThat(leido.areaDeLaFicha()).isNull();
        assertThat(leido.excesoVerificado())
                .as("cero significaria que lo inscrito y lo verificado coinciden")
                .isNull();
        assertThat(leido.areaVerificada().valor()).isEqualByComparingTo("240.00");
    }

    /** Un catastro que contesta siempre ese objeto, sea cual sea la ruta. */
    private static CatastroQueNoContesta doble(Map<String, Object> cuerpo) {
        JsonNode respuesta = new JsonMapper().valueToTree(cuerpo);
        return new CatastroQueNoContesta(ruta -> respuesta);
    }
}

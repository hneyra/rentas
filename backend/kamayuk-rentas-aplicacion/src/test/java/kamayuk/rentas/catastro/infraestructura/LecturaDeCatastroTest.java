package kamayuk.rentas.catastro.infraestructura;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import kamayuk.rentas.catastro.FichaDelPadron;
import kamayuk.rentas.verificaciones.ContratoQueConsumeDeCatastro;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

        JsonNode fila = new ObjectMapper().valueToTree(fabricada);
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
}

package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.FrenteInscrito;
import kamayuk.rentas.catastro.FrentesDelPredio;
import kamayuk.rentas.catastro.FrentesInscritos;
import kamayuk.rentas.dominio.Medida;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Los frentes de un predio, pedidos a {@code catastro} (`catastro`#7).
 *
 * <p>{@code GET /catastro/api/v1/catastro/predios/&#123;predioId&#125;/frentes}. Sin parametros de
 * consulta: un frente no se resuelve a una fecha.
 *
 * <h2>La longitud se parte en magnitud y unidad, y sin unidad NO se supone ninguna</h2>
 *
 * <p>{@code catastro} publica {@code "18.50 ML"}, con la unidad dentro del dato y a proposito (ver
 * {@code FrenteResource}). Este adaptador la conserva en una {@link Medida} en vez de quedarse la
 * cifra sola, porque el barrido se determina sobre metros LINEALES y el recojo sobre metros
 * CUADRADOS, y leer unos por otros <b>no falla</b>: cobra otra cosa.
 *
 * <p>Por eso una longitud que llegue sin unidad se rechaza en voz alta en vez de suponerle {@code
 * ML}: suponerla es escribir la confusion que este tipo existe para impedir, y la suposicion no
 * dejaria ni un rastro en la fila que despues se cobra.
 *
 * <h2>Y no se lee la geometria</h2>
 *
 * <p>El recurso trae el tramo en WKT para poder dibujarlo. Este lado no lo pide: {@code rentas} no
 * tiene visor de plano, y cada campo que el contrato declara es un campo que {@code catastro} no
 * puede retirar sin poner rojo su propio build. Se declara lo que se usa.
 */
@Component
public class FrentesDelPredioHttp implements FrentesDelPredio {

    private final ClienteHttpDeCatastro catastro;

    public FrentesDelPredioHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public FrentesInscritos delPredio(long predioId) {
        String que = "leer los frentes del predio " + predioId;
        JsonNode cuerpo =
                catastro.pedirHechoDelTerritorio("/catastro/predios/" + predioId + "/frentes", que);

        List<FrenteInscrito> frentes = new ArrayList<>();
        for (JsonNode frente : cuerpo.path("frentes")) {
            frentes.add(
                    new FrenteInscrito(
                            frente.path("id").asLong(),
                            frente.path("viaId").asLong(),
                            frente.path("viaCodigo").asString(""),
                            frente.path("viaNombre").asString(""),
                            medida(frente, "longitud", que),
                            frente.path("longitudEstado").asString(""),
                            frente.path("esPrincipal").asBoolean(),
                            ClienteHttpDeCatastro.texto(frente, "numeracion"),
                            medidaOpcional(frente, "retiro", que),
                            ClienteHttpDeCatastro.texto(frente, "confirmadoPor"),
                            ClienteHttpDeCatastro.texto(frente, "confirmadoEn")));
        }

        return new FrentesInscritos(
                cuerpo.path("predioId").asLong(),
                List.copyOf(frentes),
                ClienteHttpDeCatastro.texto(cuerpo, "derivadoEn"),
                entero(cuerpo, "frentesDerivados"),
                ClienteHttpDeCatastro.texto(cuerpo, "motivoDeLaDerivacion"));
    }

    // ------------------------------------------------------------------

    /** Una medida que tiene que estar, con su unidad dentro. */
    private static Medida medida(JsonNode fila, String campo, String que) {
        Medida medida = medidaOpcional(fila, campo, que);
        if (medida == null) {
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    que
                            + ": un frente llego sin «"
                            + campo
                            + "», y de esa cifra cuelga el arbitrio de barrido. Un cero se leeria"
                            + " como «este frente no da a la calle» (#48)",
                    null);
        }
        return medida;
    }

    /** Y la misma, cuando puede no estar: {@code retiro} no lo declara todo frente. */
    private static @Nullable Medida medidaOpcional(JsonNode fila, String campo, String que) {
        String texto = ClienteHttpDeCatastro.texto(fila, campo);
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String[] partes = texto.strip().split("\\s+");
        if (partes.length != 2) {
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    que
                            + ": «"
                            + campo
                            + "» llego como «"
                            + texto
                            + "», sin su unidad. Suponerle metros lineales seria escribir la"
                            + " confusion que separa el barrido del recojo, y no dejaria rastro en"
                            + " la fila que despues se cobra",
                    null);
        }
        return Medida.de(partes[0], partes[1]);
    }

    private static @Nullable Integer entero(JsonNode cuerpo, String campo) {
        JsonNode valor = cuerpo.path(campo);
        return valor.isNull() || valor.isMissingNode() ? null : valor.asInt();
    }
}

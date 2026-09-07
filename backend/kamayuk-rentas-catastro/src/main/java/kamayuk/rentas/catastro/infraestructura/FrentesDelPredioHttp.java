package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.EstadoDeLaLongitud;
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
 * <h2>Y {@code longitudEstado} tampoco se le supone</h2>
 *
 * <p>Llegaba con {@code asString("")}, o sea que un proveedor viejo o un despliegue a medias
 * producia una cadena vacia. Un consumidor escrito como {@code !"PROPUESTA".equals(estado)} daria
 * esa longitud por buena y la cobraria, sin un solo error por el camino. Ahora se exige uno de los
 * dos valores que {@link EstadoDeLaLongitud} admite y cualquier otra cosa —incluida la ausencia— se
 * rechaza en voz alta, igual que la unidad de la medida y por el mismo motivo (#15).
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

        // Se reparten al leer y no despues: el tipo no admite una lista que mezcle los dos
        // estados, asi que no hay ningun instante en que exista una lista con las dos cosas
        // dentro —que es el instante en que alguien la sumaria— (#15).
        List<FrenteInscrito> confirmados = new ArrayList<>();
        List<FrenteInscrito> propuestos = new ArrayList<>();
        for (JsonNode frente : cuerpo.path("frentes")) {
            FrenteInscrito leido =
                    new FrenteInscrito(
                            frente.path("id").asLong(),
                            frente.path("viaId").asLong(),
                            frente.path("viaCodigo").asString(""),
                            frente.path("viaNombre").asString(""),
                            medida(frente, "longitud", que),
                            estado(frente, que),
                            frente.path("esPrincipal").asBoolean(),
                            ClienteHttpDeCatastro.texto(frente, "numeracion"),
                            medidaOpcional(frente, "retiro", que),
                            ClienteHttpDeCatastro.texto(frente, "confirmadoPor"),
                            ClienteHttpDeCatastro.texto(frente, "confirmadoEn"));
            (leido.confirmada() ? confirmados : propuestos).add(leido);
        }

        return new FrentesInscritos(
                cuerpo.path("predioId").asLong(),
                List.copyOf(confirmados),
                List.copyOf(propuestos),
                ClienteHttpDeCatastro.texto(cuerpo, "derivadoEn"),
                entero(cuerpo, "frentesDerivados"),
                ClienteHttpDeCatastro.texto(cuerpo, "motivoDeLaDerivacion"));
    }

    // ------------------------------------------------------------------

    /**
     * Quien afirmo la longitud, y sin valor por omision.
     *
     * <p>La cadena vacia NO es un estado. Si el campo no llega, o llega con un valor que este lado
     * no reconoce, la lectura se niega: dar por buena una longitud cuyo autor no consta es
     * exactamente lo que la compuerta PROPUESTA/CONFIRMADA existe para impedir, y `catastro` la
     * construyo porque la decision de cobrar es de aqui (ADR-0024, ADR-0021).
     */
    private static EstadoDeLaLongitud estado(JsonNode frente, String que) {
        String texto = ClienteHttpDeCatastro.texto(frente, "longitudEstado");
        return EstadoDeLaLongitud.reconocer(texto)
                .orElseThrow(
                        () ->
                                new ClienteHttpDeCatastro.CatastroInalcanzable(
                                        que
                                                + ": un frente llego con «longitudEstado» = «"
                                                + texto
                                                + "», y este lado solo reconoce PROPUESTA y"
                                                + " CONFIRMADA. Una PROPUESTA la corto una maquina"
                                                + " contra el eje de la via y una CONFIRMADA la"
                                                + " firmo una persona (ADR-0021): sin ese campo las"
                                                + " dos llegan iguales, y quien determine un"
                                                + " arbitrio sobre metros que nadie confirmo no"
                                                + " tiene como saberlo",
                                        null));
    }

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

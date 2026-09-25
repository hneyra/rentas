package kamayuk.rentas.catastro.infraestructura;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * El cuadro de valores unitarios de edificacion, pedido a {@code catastro} (P5C).
 *
 * <p>`catastro` publica {@code GET /catastro/api/v1/catastro/tablas/valores-unitarios}. Lo consume
 * la valorizacion del FUE, en {@code licencias}. Era el segundo de los dos puertos que tenian quien
 * los contestara cuando P5C hizo la resta; C-5 conecto cinco mas.
 *
 * <h2>La respuesta es un ARRAY, no un sobre paginado (C-1, desajuste 6)</h2>
 *
 * <p>Hasta C-1 este adaptador iteraba {@code contenido} y {@code catastro} publica la lista pelada:
 * {@code path("contenido")} sobre un array devuelve un nodo ausente, asi que el cuadro salia
 * <b>vacio con un 200 delante</b> — que se lee como «este ejercicio no tiene cuadro publicado», que
 * es justo lo que el javadoc del puerto prohibe decir.
 *
 * <p><b>Paga el consumidor.</b> Un cuadro sellado se lee ENTERO: no tiene pagina, no tiene {@code
 * totalElementos} y envolverlo inventaria un sobre cuyo recuento nunca significaria nada. Ademas la
 * forma es la que {@code catastro} usa en sus tres lecturas de cuadro —aranceles, depreciacion y
 * valores unitarios—, asi que cambiarla por una de ellas las separaria. Quien supuso un sobre que
 * nunca estuvo fue este adaptador.
 *
 * <h2>Un ejercicio sin cuadro sellado es {@code EjercicioSinSellar}, no una averia (#350)</h2>
 *
 * <p>Un ejercicio sin conjunto sellado NO devuelve una lista vacia —una lista vacia se leeria como
 * «este ejercicio no tiene cuadro» y la obra saldria valorizada en 0,00 (#48)—: `catastro` contesta
 * <b>404 con su {@code codigo}</b>, y este adaptador lo traduce a {@link
 * LectorDeParametros.EjercicioSinSellar}, que es lo que el javadoc del puerto exige.
 *
 * <p>Hasta #350 lo dejaba salir como {@code CatastroInalcanzable}, y este mismo javadoc decia que
 * eso era lo que el puerto pedia. No lo era: {@code ValorizacionDelFue} solo sabe convertir en «—»
 * el {@code EjercicioSinSellar}, asi que en un anio sin cuadro la ficha del FUE, el reporte general
 * y la respuesta de «completar seccion» —una escritura que SI se habia confirmado— contestaban 500.
 * Las pruebas no lo veian porque el doble del puerto lanzaba {@code EjercicioSinSellar} por su
 * cuenta; desde #350 los dos pasan por la misma prueba de contrato ({@code
 * ContratoDelLectorDeValoresUnitarios}).
 *
 * <p>Es lo mismo que {@code ClienteHttpDeNormativa} ya hacia con el 404 de su vecino. Y solo el 404
 * <b>con codigo</b>: uno sin el —el HTML de un proxy— no es una respuesta de `catastro`, y un
 * codigo con otro estado tampoco dice «ese ejercicio no tiene cuadro». Los dos siguen siendo
 * averia; que cuenta como respuesta lo decide {@link ClienteHttpDeCatastro#hechoContestado}, y no
 * este adaptador.
 */
@Component
public class ValoresUnitariosHttp implements LectorDeValoresUnitarios {

    /** Lo que `catastro` contesta en esta ruta cuando el ejercicio no tiene conjunto sellado. */
    private static final int NO_HAY_CONJUNTO_SELLADO = 404;

    private final ClienteHttpDeCatastro catastro;

    public ValoresUnitariosHttp(ClienteHttpDeCatastro catastro) {
        this.catastro = catastro;
    }

    @Override
    public List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio) {
        String que = "leer el cuadro de valores unitarios de " + ejercicio;
        JsonNode cuerpo =
                catastro.pedirTraduciendoLosHechos(
                        "/catastro/tablas/valores-unitarios?ejercicio=" + ejercicio.valor(),
                        que,
                        hecho ->
                                hecho.estado() == NO_HAY_CONJUNTO_SELLADO
                                        ? new LectorDeParametros.EjercicioSinSellar(ejercicio)
                                        : hecho.comoAveria(que));
        List<ValorUnitarioPublicado> filas = new ArrayList<>();
        if (!cuerpo.isArray()) {
            // No es una comodidad: si `catastro` cambiara la forma, iterar un nodo que no es
            // un array devuelve CERO filas en silencio, y el cuadro vacio se lee como «este
            // ejercicio no tiene cuadro» — el defecto que este adaptador acaba de cerrar.
            throw new ClienteHttpDeCatastro.CatastroInalcanzable(
                    "leer el cuadro de valores unitarios de "
                            + ejercicio
                            + ": la respuesta no es la lista que publica esa ruta",
                    null);
        }
        for (JsonNode fila : cuerpo) {
            JsonNode hasta = fila.path("anioConstruccionHasta");
            filas.add(
                    new ValorUnitarioPublicado(
                            fila.path("partida").asString(""),
                            fila.path("categoria").asString(" ").charAt(0),
                            fila.path("anioConstruccionDesde").asInt(),
                            hasta.isNull() || hasta.isMissingNode() ? null : hasta.asInt(),
                            ValorNormativo.de(fila.path("valorM2").asString("0"))));
        }
        return List.copyOf(filas);
    }
}

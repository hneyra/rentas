package kamayuk.rentas.licencias.dobles;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.LectorDeParametros;

/**
 * El cuadro de valores unitarios de edificacion, con las celdas que la prueba le siembre (#48).
 *
 * <p>Es el <b>puerto publico de catastro</b>, no su tabla, y ese es el punto: si esta prueba
 * pudiera montar un doble del repositorio de valuacion en vez de este, seria porque {@code
 * licencias} conoce {@code catastro.dominio}, que es lo que Spring Modulith verifica.
 *
 * <p><b>Ninguna cifra viene compilada.</b> Las celdas entran por {@link #con}: un doble que trajera
 * el cuadro dentro seria una transcripcion normativa escondida en {@code src/test}, y las celdas
 * reales las espera #197.
 *
 * <p>Sin ninguna celda sembrada, {@link #vacio()} finge el ejercicio sin sellar, que es el estado
 * real de una instalacion recien implantada.
 */
public final class CuadroDeMentira implements LectorDeValoresUnitarios {

    private final List<ValorUnitarioPublicado> celdas = new ArrayList<>();
    private boolean sellado = true;

    public CuadroDeMentira con(String partida, char categoria, String valorM2) {
        celdas.add(
                new ValorUnitarioPublicado(
                        partida, categoria, 1990, null, ValorNormativo.de(valorM2)));
        return this;
    }

    /** El ejercicio sin ningun conjunto sellado: no hay cuadro que leer. */
    public CuadroDeMentira vacio() {
        sellado = false;
        return this;
    }

    @Override
    public List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio) {
        if (!sellado) {
            throw new LectorDeParametros.EjercicioSinSellar(ejercicio);
        }
        return List.copyOf(celdas);
    }
}

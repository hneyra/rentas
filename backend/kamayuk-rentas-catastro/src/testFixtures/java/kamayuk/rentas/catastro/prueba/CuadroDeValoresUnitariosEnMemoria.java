package kamayuk.rentas.catastro.prueba;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;

/**
 * <b>FIXTURE DE PRUEBA</b>: el cuadro de valores unitarios que publica {@code catastro} (P5C).
 *
 * <h2>Por que no lee una tabla</h2>
 *
 * <p>Porque ya no hay ninguna que leer en esta base: `V6` retiro las tablas de {@code catastro} y
 * el cuadro se pide por el puerto {@link LectorDeValoresUnitarios}. Lo que las pruebas de {@code
 * licencias} necesitaban no era la tabla sino la premisa: «este ejercicio tiene cuadro y estas son
 * sus celdas».
 *
 * <h2>Un ejercicio sin cuadro NO devuelve una lista vacia</h2>
 *
 * <p>Lanza {@code EjercicioSinSellar}, que es lo que el javadoc del puerto exige —«no devuelve
 * vacio y no devuelve ceros»— y lo que el cliente HTTP hara con el 404 de `catastro`. Una lista
 * vacia dejaria la obra valorizada en 0,00 y ese cero es indistinguible de uno correcto cuando
 * llega al papel que se exhibe en la obra (#48).
 */
public final class CuadroDeValoresUnitariosEnMemoria implements LectorDeValoresUnitarios {

    private final Map<String, List<ValorUnitarioPublicado>> porEjercicio = new LinkedHashMap<>();

    private long sembrandoEn;

    /**
     * Siembra el cuadro de UNA municipalidad.
     *
     * <p>Es por municipalidad y no global a proposito: un conjunto de parametros se sella por
     * municipalidad (ADR-0007), asi que una recien implantada NO tiene cuadro y su valorizacion
     * tiene que decirlo. Un fixture global dejaria esa prueba pasando por casualidad.
     */
    public CuadroDeValoresUnitariosEnMemoria en(long municipalidadId) {
        this.sembrandoEn = municipalidadId;
        return this;
    }

    public CuadroDeValoresUnitariosEnMemoria conCelda(
            Ejercicio ejercicio, String partida, char categoria, String valorM2) {
        porEjercicio
                .computeIfAbsent(sembrandoEn + ":" + ejercicio.valor(), anio -> new ArrayList<>())
                .add(
                        new ValorUnitarioPublicado(
                                partida, categoria, 1990, null, ValorNormativo.de(valorM2)));
        return this;
    }

    @Override
    public List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio) {
        List<ValorUnitarioPublicado> celdas =
                porEjercicio.get(
                        kamayuk.rentas.compartido.TenantContext.actual().valor()
                                + ":"
                                + ejercicio.valor());
        if (celdas == null) {
            throw new kamayuk.rentas.parametros.LectorDeParametros.EjercicioSinSellar(ejercicio);
        }
        return List.copyOf(celdas);
    }
}

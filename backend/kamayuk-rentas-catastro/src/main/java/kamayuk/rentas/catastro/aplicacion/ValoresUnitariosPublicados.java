package kamayuk.rentas.catastro.aplicacion;

import java.util.List;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.catastro.ValorUnitarioPublicado;
import kamayuk.rentas.catastro.dominio.ValorUnitarioEdificacion;
import kamayuk.rentas.dominio.Ejercicio;
import org.springframework.stereotype.Service;

/**
 * La mitad de {@code catastro} que responde a {@link LectorDeValoresUnitarios} (#17, #48).
 *
 * <p>No hace nada mas que traducir: la resolucion del ejercicio a un conjunto sellado ya vive en
 * {@link TablasDeValuacion}, y repetirla aqui seria tener dos sitios donde olvidarse del {@code AND
 * estado = 'SELLADO'}. Lo unico propio de esta clase es <b>no</b> publicar {@code
 * ValorUnitarioEdificacion}: ese tipo es el modelo interno de {@code catastro}, y quien lo
 * consumiera desde {@code licencias} estaria cruzando el limite que Spring Modulith vigila.
 */
@Service
public class ValoresUnitariosPublicados implements LectorDeValoresUnitarios {

    private final TablasDeValuacion tablas;

    public ValoresUnitariosPublicados(TablasDeValuacion tablas) {
        this.tablas = tablas;
    }

    @Override
    public List<ValorUnitarioPublicado> valoresUnitariosVigentesEn(Ejercicio ejercicio) {
        return tablas.valoresUnitarios(ejercicio).stream()
                .map(ValoresUnitariosPublicados::traducir)
                .toList();
    }

    private static ValorUnitarioPublicado traducir(ValorUnitarioEdificacion celda) {
        return new ValorUnitarioPublicado(
                celda.partida().name(),
                celda.categoria(),
                celda.anioConstruccionDesde(),
                celda.anioConstruccionHasta(),
                celda.valorM2());
    }
}

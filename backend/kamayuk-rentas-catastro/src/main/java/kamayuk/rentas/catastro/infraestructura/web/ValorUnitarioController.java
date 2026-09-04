package kamayuk.rentas.catastro.infraestructura.web;

import java.util.List;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.autorizacion.RequiereAcceso;
import kamayuk.rentas.catastro.aplicacion.TablasDeValuacion;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.FaltaPublicar;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.web.Api;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Valores unitarios de edificacion: {@code GET /api/v1/catastro/tablas/valores-unitarios?anio=2026}
 * (RF-009).
 *
 * <p>Igual que {@link ArancelController}: devuelve el conjunto sellado vigente del ejercicio, y
 * {@code NO_ENCONTRADO} si el ejercicio no tiene ninguno —con el discriminador dentro desde #723,
 * porque «no hay conjunto sellado» y «el cuadro de valores unitarios no existe» son dos cosas
 * distintas y solo una la arregla quien publica—. El motivo de que el codigo sea 404 y no 422 esta
 * escrito una sola vez, en {@link ArancelController}.
 */
@RestController
@RequestMapping(Api.RAIZ + "/catastro/tablas/valores-unitarios")
@RequiereAcceso(acceso = "valores_unitarios", privilegio = Privilegio.LECTURA)
public class ValorUnitarioController {

    private final TablasDeValuacion tablas;

    public ValorUnitarioController(TablasDeValuacion tablas) {
        this.tablas = tablas;
    }

    @GetMapping
    public List<ValorUnitarioResource> listar(@RequestParam int anio) {
        try {
            return tablas.valoresUnitarios(new Ejercicio(anio)).stream()
                    .map(ValorUnitarioResource::de)
                    .toList();
        } catch (LectorDeParametros.EjercicioSinSellar excepcion) {
            throw FaltaPublicar.noEncontrado(excepcion);
        }
    }
}

package kamayuk.rentas.catastro.prueba;

import kamayuk.rentas.catastro.ContratoDelLectorDeValoresUnitarios;
import kamayuk.rentas.catastro.LectorDeValoresUnitarios;
import kamayuk.rentas.compartido.TenantContext;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.MunicipalidadId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;

/**
 * #350 — el doble en memoria del cuadro cumple lo mismo que el adaptador de produccion.
 *
 * <p>Es la mitad que ya cumplia: el doble lanzaba {@code EjercicioSinSellar} desde P5C. Esta aqui
 * para que no pueda dejar de hacerlo sin que su gemela, {@code
 * ValoresUnitariosHttpCumpleElContratoTest}, siga midiendo lo mismo contra el adaptador.
 */
@DisplayName("#350 — el cuadro en memoria cumple el contrato del puerto")
class CuadroEnMemoriaCumpleElContratoTest extends ContratoDelLectorDeValoresUnitarios {

    private static final long MUNICIPALIDAD = 7L;

    @BeforeEach
    void fijarMunicipalidad() {
        TenantContext.fijar(new MunicipalidadId(MUNICIPALIDAD));
    }

    @AfterEach
    void limpiarMunicipalidad() {
        TenantContext.limpiar();
    }

    @Override
    protected LectorDeValoresUnitarios sinCuadroPara(Ejercicio ejercicio) {
        // Sembrado en OTRO ejercicio a proposito: un doble sin ninguna celda no distingue «este
        // ejercicio no tiene cuadro» de «este doble no tiene nada».
        return new CuadroDeValoresUnitariosEnMemoria()
                .en(MUNICIPALIDAD)
                .conCelda(SELLADO, "MUROS", 'A', "120.000000");
    }

    @Override
    protected LectorDeValoresUnitarios conUnaCelda(
            Ejercicio ejercicio, String partida, char categoria, String valorM2) {
        return new CuadroDeValoresUnitariosEnMemoria()
                .en(MUNICIPALIDAD)
                .conCelda(ejercicio, partida, categoria, valorM2);
    }
}

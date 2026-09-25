package kamayuk.rentas.catastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.ValorNormativo;
import kamayuk.rentas.parametros.LectorDeParametros;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #350 — lo que {@link LectorDeValoresUnitarios} promete, comprobado en CADA implementacion.
 *
 * <h2>Por que una clase abstracta y no una prueba por implementacion</h2>
 *
 * <p>Hasta #350 el puerto tenia dos implementaciones que ante el mismo hecho hacian cosas
 * distintas: el doble en memoria lanzaba {@code EjercicioSinSellar} para un ejercicio sin cuadro,
 * que es lo que el javadoc del puerto exige, y {@code ValoresUnitariosHttp} dejaba salir el 404 de
 * {@code catastro} como {@code CatastroInalcanzable}. Las pruebas de {@code licencias} corrian
 * contra el doble y veian la degradacion a «—»; en produccion la ficha del FUE contestaba 500. Dos
 * implementaciones del mismo puerto que no son intercambiables violan Liskov, y ninguna prueba
 * podia decirlo porque cada una media a la suya por su lado.
 *
 * <p>Aqui los casos se escriben <b>una vez</b> y cada implementacion los hereda: si una diverge,
 * sale roja la suya. Quien escriba un tercer doble del puerto lo hace pasar por aqui.
 */
public abstract class ContratoDelLectorDeValoresUnitarios {

    protected static final Ejercicio SIN_SELLAR = new Ejercicio(2025);
    protected static final Ejercicio SELLADO = new Ejercicio(2026);

    /** Una implementacion en la que {@link #SIN_SELLAR} no tiene ningun conjunto sellado. */
    protected abstract LectorDeValoresUnitarios sinCuadroPara(Ejercicio ejercicio);

    /** Una implementacion en la que {@link #SELLADO} tiene sellada exactamente esa celda. */
    protected abstract LectorDeValoresUnitarios conUnaCelda(
            Ejercicio ejercicio, String partida, char categoria, String valorM2);

    @Test
    @DisplayName(
            "un ejercicio sin cuadro sellado FALLA con EjercicioSinSellar, nombrando el ejercicio")
    void sinSellarFallaConEjercicioSinSellar() {
        Throwable fallo =
                catchThrowable(
                        () -> sinCuadroPara(SIN_SELLAR).valoresUnitariosVigentesEn(SIN_SELLAR));

        assertThat(fallo)
                .as(
                        "es lo unico que `ValorizacionDelFue` sabe convertir en «—». Cualquier otro"
                                + " tipo llega al manejador de errores como 500, y la ficha del FUE"
                                + " de ese ejercicio no se puede volver a abrir")
                .isInstanceOf(LectorDeParametros.EjercicioSinSellar.class);
        assertThat(((LectorDeParametros.EjercicioSinSellar) fallo).ejercicio())
                .isEqualTo(SIN_SELLAR);
    }

    @Test
    @DisplayName("un ejercicio con cuadro devuelve sus celdas, y no una lista vacia")
    void conCuadroDevuelveLasCeldas() {
        List<ValorUnitarioPublicado> celdas =
                conUnaCelda(SELLADO, "MUROS", 'A', "120.000000")
                        .valoresUnitariosVigentesEn(SELLADO);

        assertThat(celdas).hasSize(1);
        assertThat(celdas.getFirst().partida()).isEqualTo("MUROS");
        assertThat(celdas.getFirst().categoria()).isEqualTo('A');
        assertThat(celdas.getFirst().valorM2()).isEqualTo(ValorNormativo.de("120.000000"));
    }
}

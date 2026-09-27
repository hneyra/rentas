package kamayuk.rentas.verificaciones.muestras;

import java.util.Optional;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.CifraSinPublicar;
import kamayuk.rentas.parametros.ParametroSinPublicar;

/**
 * Las muestras de {@code LaFamiliaFaltaPublicarEsUnTipoTest} (#435): una cifra sin publicar que
 * declara la interfaz a mano, y otra que extiende la base.
 */
public final class MuestrasDeCifraFueraDeLaFamilia {

    private MuestrasDeCifraFueraDeLaFamilia() {}

    /**
     * MALA: publica ejercicio y llave, pero no es una {@link CifraSinPublicar}. Ningun {@code catch
     * (CifraSinPublicar …)} la ve, y el advice que traduce la familia tampoco: sale 500.
     */
    public static final class CifraQueDeclaraLaInterfazAMano extends RuntimeException
            implements ParametroSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public CifraQueDeclaraLaInterfazAMano() {
            super("El conjunto sellado del ejercicio 2027 no tiene el parametro TIM:MENSUAL");
        }

        @Override
        public Ejercicio ejercicio() {
            return new Ejercicio(2027);
        }

        @Override
        public Optional<String> llave() {
            return Optional.of("TIM:MENSUAL");
        }
    }

    /** BUENA: la misma cifra, dentro de la familia. */
    public static final class CifraQueExtiendeLaBase extends CifraSinPublicar {

        @java.io.Serial private static final long serialVersionUID = 1L;

        public CifraQueExtiendeLaBase() {
            super(
                    "El conjunto sellado del ejercicio 2027 no tiene el parametro TIM:MENSUAL",
                    new Ejercicio(2027),
                    "TIM:MENSUAL");
        }
    }
}

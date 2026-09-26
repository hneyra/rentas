package kamayuk.rentas.verificaciones.muestras.aplicacion;

import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.parametros.ConjuntoVigente;
import kamayuk.rentas.parametros.LectorDeParametros;
import kamayuk.rentas.parametros.ParametrosSellados;

/**
 * Muestras que violan —y que no violan— la regla de #361: ningun metodo pide al {@link
 * LectorDeParametros} los parametros y el identificador del conjunto por separado.
 *
 * <p>No las instancia nadie. Existen para que {@code ElConjuntoSeResuelveUnaVezTest} demuestre que
 * la regla muerde: una regla que no puede fallar no protege nada. No llevan {@code @Service}: la
 * regla no mira anotaciones, mira llamadas, y asi el escaner de componentes no las registra.
 */
public final class MuestrasDeDosResoluciones {

    private MuestrasDeDosResoluciones() {}

    /** MALO: la forma exacta de #361, el par suelto que once sitios repetian. */
    public static final class ElParSuelto {

        private final LectorDeParametros parametros;

        public ElParSuelto(LectorDeParametros parametros) {
            this.parametros = parametros;
        }

        public String aLaFechaDe(Ejercicio ejercicio) {
            ParametrosSellados sellados = parametros.vigenteEn(ejercicio);
            long conjuntoId = parametros.conjuntoVigenteEn(ejercicio).valor();
            return sellados.version() + "/" + conjuntoId;
        }
    }

    /** BUENO: una resolucion, y las dos cosas del mismo conjunto. */
    public static final class UnaSolaResolucion {

        private final LectorDeParametros parametros;

        public UnaSolaResolucion(LectorDeParametros parametros) {
            this.parametros = parametros;
        }

        public String aLaFechaDe(Ejercicio ejercicio) {
            ConjuntoVigente conjunto = parametros.vigenteConSuConjunto(ejercicio);
            return conjunto.parametros().version() + "/" + conjunto.id();
        }
    }

    /**
     * BUENO: solo el identificador, para leer una tabla que no cabe en los parametros —el catalogo
     * de valores referenciales—. Es un uso legitimo de {@code conjuntoVigenteEn}, y la regla no lo
     * toca.
     */
    public static final class SoloElIdentificador {

        private final LectorDeParametros parametros;

        public SoloElIdentificador(LectorDeParametros parametros) {
            this.parametros = parametros;
        }

        public long catalogoDe(Ejercicio ejercicio) {
            return parametros.conjuntoVigenteEn(ejercicio).valor();
        }
    }
}

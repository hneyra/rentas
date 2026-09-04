package kamayuk.rentas.parametros.aplicacion;

import kamayuk.rentas.parametros.infraestructura.CacheDelEscenario;
import kamayuk.rentas.parametros.infraestructura.NormativaDePrueba;
import kamayuk.rentas.parametros.infraestructura.ParametrosRepositoryJdbc;

/**
 * <b>FIXTURE DE PRUEBA</b>: el lector <b>de produccion</b>, sobre el escenario de la prueba.
 *
 * <p>No es un doble del lector: lo que hay debajo es {@link LectorDeParametrosCacheados}, la clase
 * que corre en ventanilla, con su resolucion de vigencias (#659) y su reparto entre «lo vigente» y
 * «el conjunto que la determinacion guardo». Lo unico sustituido es el <b>almacen</b>: las tablas
 * {@code _de_prueba} en vez de la cache de `V3`, porque veinte clases de prueba construyen su
 * lector a mano y ahi no hay gestor de transacciones que pueda abrir la que la descarga necesita.
 *
 * <p>Conserva el nombre que tenia el lector antes de P5B para no reescribir esas veinte clases, que
 * siguen diciendo lo mismo que decian: «dado un conjunto sellado con estos valores, calcula».
 */
public class LectorDeParametrosSellados extends LectorDeParametrosCacheados {

    public LectorDeParametrosSellados(ParametrosRepositoryJdbc repositorio) {
        super(
                new CacheDelEscenario(repositorio.jdbc()),
                new NormativaDePrueba(repositorio.jdbc()),
                new DescargaDeNormativa(
                        new CacheDelEscenario(repositorio.jdbc()),
                        new NormativaDePrueba(repositorio.jdbc())));
    }
}

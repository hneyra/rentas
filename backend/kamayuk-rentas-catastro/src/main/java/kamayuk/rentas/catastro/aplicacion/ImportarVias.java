package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.catastro.dominio.TipoVia;
import kamayuk.rentas.catastro.dominio.Via;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga inicial del catalogo vial desde un archivo (#121): una fila por via, columnas {@code
 * codigo,tipo,nombre,ubigeo} —las dos ultimas admiten quedar vacias—.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Cada fila se registra con su propia llamada a {@link RegistrarVia#registrar}, que es un
 * {@code @Service} <b>distinto</b> de este y con su propio {@code @Transactional}. Eso importa mas
 * de lo que parece: este metodo no lleva la anotacion, asi que cada llamada abre su propia
 * transaccion en vez de heredar una que ya existiera —lo mismo que {@code REQUIRES_NEW}, sin
 * necesitar la anotacion, porque nunca hay una transaccion ambiente de la que colgarse—. Si una
 * fila viola la restriccion de unicidad, PostgreSQL aborta <b>esa</b> transaccion y ninguna otra:
 * las filas siguientes abren la suya y entran con normalidad.
 *
 * <p>Llamar a {@code this.registrarVia.registrar(...)} en un bucle dentro de un metodo que si
 * llevara {@code @Transactional} deshace esta propiedad entera —todas las filas caerian en la misma
 * transaccion, y una que falle se lleva a las demas—. Es exactamente el defecto que el issue pide
 * demostrar capaz de ocurrir, y es la razon de que este metodo se quede sin la anotacion a
 * proposito.
 *
 * <h2>Ni por fila abierta el tiempo entero</h2>
 *
 * <p>La misma propiedad resuelve el otro criterio de aceptacion: con miles de filas, ninguna
 * transaccion queda abierta mas que lo que tarda una fila. Un archivo grande no mantiene un bloqueo
 * ni una conexion ocupada durante toda la carga.
 *
 * <h2>Reimportar no duplica</h2>
 *
 * <p>Una fila cuyo codigo ya existe —porque el archivo se volvio a cargar, o porque otra fila del
 * mismo archivo repite el codigo— revienta la misma restriccion de unicidad y se rechaza con su
 * motivo. No hay una comprobacion previa que lea antes de escribir: la unicidad la exige la base, y
 * preguntarle antes solo duplicaria el viaje sin cambiar el resultado.
 */
@Service
public class ImportarVias {

    private static final int COLUMNAS_MINIMAS = 3;

    private final RegistrarVia registrarVia;

    public ImportarVias(RegistrarVia registrarVia) {
        this.registrarVia = registrarVia;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            Via via;
            try {
                via = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrarVia.registrar(via, observacion);
                nuevas++;
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya existe una via con el codigo '" + via.codigo() + "'"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de vias", e);
        }
    }

    private static Via parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta al menos "
                            + COLUMNAS_MINIMAS
                            + ": codigo, tipo, nombre");
        }
        String codigo = campos.get(0);
        String tipoTexto = campos.get(1);
        String nombre = campos.get(2);
        String ubigeo = campos.size() > 3 && !campos.get(3).isBlank() ? campos.get(3) : null;

        TipoVia tipo;
        try {
            tipo = TipoVia.valueOf(tipoTexto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Tipo de via desconocido: '"
                            + tipoTexto
                            + "'. Los validos son "
                            + java.util.Arrays.toString(TipoVia.values()));
        }
        return Via.nueva(codigo, tipo, nombre, ubigeo);
    }
}

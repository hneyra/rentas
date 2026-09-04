package kamayuk.rentas.parametros.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.web.ProblemaDeNegocio;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * Compone el conjunto de un ejercicio desde un archivo que <b>nombra</b> parametros ya publicados,
 * uno por fila: columnas {@code tipo,clave,vigenciaDesde} —{@code clave} admite quedar vacia, para
 * el tipo que tiene un solo valor—.
 *
 * <p><b>Aqui no entra ninguna cifra</b>, y no por prudencia sino porque no puede: la aplicacion
 * solo tiene {@code SELECT} sobre {@code parametro_tributario} (V7). Publicar un valor normativo es
 * trabajo de {@code rol_carga_parametros} con su propia conexion —la separacion de funciones de
 * REQ-03—, y este archivo dice cuales de los ya publicados componen el ejercicio. Un archivo con
 * valores dentro seria un camino para meter una cifra sin la doble firma que ADR-0007 exige.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Cada fila pasa por {@link AdministrarParametros#agregarParametroPublicado}, un
 * {@code @Service} <b>distinto</b> de este con su propio {@code @Transactional}: este metodo no
 * lleva la anotacion, asi que cada llamada abre su propia transaccion en vez de heredar una que ya
 * existiera. Si una fila nombra un parametro que no esta publicado, o repite uno que ya entro, esa
 * transaccion se aborta y ninguna otra. Es la misma propiedad, y la misma razon, que documenta
 * {@code ImportarArancel} (#121, #328): con una transaccion envolvente, la fila que revienta se
 * lleva por delante a la valida que la seguia.
 */
@Service
public class ImportarParametrosDelConjunto {

    private static final int COLUMNAS = 3;

    private final AdministrarParametros administrar;

    public ImportarParametrosDelConjunto(AdministrarParametros administrar) {
        this.administrar = administrar;
    }

    public InformeDeImportacion importar(Reader archivo, long conjuntoId, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            LlaveDeParametro llave;
            try {
                llave = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                administrar.agregarParametroPublicado(conjuntoId, llave, observacion);
                nuevas++;
            } catch (ProblemaDeNegocio | IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DuplicateKeyException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "El parametro " + llave + " ya estaba en este conjunto"));
            } catch (DataAccessException e) {
                // La causa mas probable, sin repetir el mensaje crudo de la base (ARQ-04 §5): el
                // disparador de V9 rechazando la escritura porque el conjunto ya esta sellado.
                // La violacion de unicidad se distingue arriba.
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "No se pudo incorporar el parametro "
                                        + llave
                                        + ": el conjunto ya esta sellado, y corregirlo exige una"
                                        + " version nueva (ADR-0007)"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de parametros", e);
        }
    }

    private static LlaveDeParametro parsear(List<String> campos) {
        if (campos.size() < COLUMNAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS
                            + ": tipo, clave, vigenciaDesde");
        }
        String tipo = campos.get(0);
        if (tipo.isBlank()) {
            throw new IllegalArgumentException("La fila necesita el tipo del parametro");
        }
        String clave = campos.get(1).isBlank() ? null : campos.get(1);
        String desde = campos.get(2);
        try {
            return new LlaveDeParametro(tipo, clave, LocalDate.parse(desde));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "La fecha de vigencia no es una fecha aaaa-mm-dd: '" + desde + "'");
        }
    }
}

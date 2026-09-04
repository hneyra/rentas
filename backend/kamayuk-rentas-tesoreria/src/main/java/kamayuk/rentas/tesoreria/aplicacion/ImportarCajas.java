package kamayuk.rentas.tesoreria.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga inicial de las ventanillas de una municipalidad desde un archivo (#430): una fila por caja,
 * columnas {@code codigo,nombre,serie,codigoArea,nombreArea} —las dos últimas admiten quedar
 * vacías—.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Cada fila se registra con su propia llamada a {@link RegistrarCaja#registrar}, que es un
 * {@code @Service} <b>distinto</b> de este y con su propio {@code @Transactional}. Este método no
 * lleva la anotación a propósito: así cada llamada abre su propia transacción en vez de heredar una
 * ya abierta. Una fila que viola la unicidad del código o de la serie aborta <b>esa</b> transacción
 * y ninguna otra, y las siguientes entran con normalidad. Es la propiedad que {@code ImportarVias}
 * documenta y que #328 volvió a medir: envolver el bucle revienta la corrida entera con {@code
 * UnexpectedRollbackException}, no solo pierde la fila que seguía.
 *
 * <h2>Reimportar no duplica</h2>
 *
 * <p>Una caja cuyo código ya existe revienta {@code caja_codigo_uq} y se rechaza con su motivo. No
 * hay comprobación previa que lea antes de escribir: la unicidad la exige la base.
 *
 * <p>Con el <b>área</b> es al revés y también es deliberado: si ya está registrada se reutiliza, y
 * eso no es una excepción a lo anterior sino lo que hace que dos ventanillas del mismo archivo
 * puedan imputar a la misma área sin que la segunda se rechace.
 */
@Service
public class ImportarCajas {

    private static final int COLUMNAS_MINIMAS = 3;

    private final RegistrarCaja registrarCaja;

    public ImportarCajas(RegistrarCaja registrarCaja) {
        this.registrarCaja = registrarCaja;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            FilaDeCaja caja;
            try {
                caja = parsear(fila.campos());
            } catch (IllegalArgumentException invalida) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), invalida));
                continue;
            }
            try {
                registrarCaja.registrar(
                        caja.codigo(),
                        caja.nombre(),
                        caja.serie(),
                        caja.codigoDeArea(),
                        caja.nombreDelArea(),
                        observacion);
                nuevas++;
            } catch (IllegalArgumentException invalida) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), invalida));
            } catch (DataAccessException yaExiste) {
                // El mensaje crudo de PostgreSQL nombraria tabla y restriccion (ARQ-04 §5).
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya hay una caja con el codigo '"
                                        + caja.codigo()
                                        + "' o con la serie '"
                                        + caja.serie()
                                        + "'"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    // ------------------------------------------------------------------

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException noSePudo) {
            throw new IllegalStateException("No se pudo leer el archivo de cajas", noSePudo);
        }
    }

    private static FilaDeCaja parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta al menos "
                            + COLUMNAS_MINIMAS
                            + ": codigo, nombre, serie");
        }
        return new FilaDeCaja(
                campos.get(0),
                campos.get(1),
                campos.get(2),
                opcional(campos, 3),
                opcional(campos, 4));
    }

    private static @Nullable String opcional(List<String> campos, int posicion) {
        if (campos.size() <= posicion) {
            return null;
        }
        String valor = campos.get(posicion).strip();
        return valor.isEmpty() ? null : valor;
    }

    /** Una fila del archivo, ya separada en sus cinco columnas. */
    private record FilaDeCaja(
            String codigo,
            String nombre,
            String serie,
            @Nullable String codigoDeArea,
            @Nullable String nombreDelArea) {}
}

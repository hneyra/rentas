package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.catastro.dominio.Sector;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga inicial del catalogo de sectores desde un archivo (#121): una fila por sector, columnas
 * {@code codigo,nombre,zona} —la zona es opcional—.
 *
 * <p>Mismo patron que {@link ImportarVias}, con la misma razon de fondo: {@code importar} no lleva
 * {@code @Transactional} para que cada fila abra la suya propia al llamar a {@link
 * RegistrarSector#registrar}, que si la lleva. Ver el javadoc de {@link ImportarVias} para el
 * porque completo —rechazo por fila, ninguna transaccion abierta el tiempo entero, y reimportar sin
 * duplicar—.
 */
@Service
public class ImportarSectores {

    private static final int COLUMNAS_MINIMAS = 2;

    private final RegistrarSector registrarSector;

    public ImportarSectores(RegistrarSector registrarSector) {
        this.registrarSector = registrarSector;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            Sector sector;
            try {
                sector = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrarSector.registrar(sector, observacion);
                nuevas++;
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya existe un sector con el codigo '" + sector.codigo() + "'"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de sectores", e);
        }
    }

    private static Sector parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta al menos "
                            + COLUMNAS_MINIMAS
                            + ": codigo, nombre");
        }
        String codigo = campos.get(0);
        String nombre = campos.get(1);
        String zona = campos.size() > 2 && !campos.get(2).isBlank() ? campos.get(2) : null;
        return new Sector(null, codigo, nombre, zona, true);
    }
}

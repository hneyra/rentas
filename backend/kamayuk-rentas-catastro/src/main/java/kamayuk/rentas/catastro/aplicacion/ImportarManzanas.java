package kamayuk.rentas.catastro.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Observacion;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga inicial de manzanas desde un archivo (#121): una fila por manzana, columnas {@code
 * sectorCodigo,codigo}. El sector se referencia por su codigo —lo que trae el archivo—, no por su
 * identificador interno; {@link RegistrarManzana} lo resuelve dentro de la misma transaccion de la
 * fila.
 *
 * <p>Mismo patron que {@link ImportarVias}: {@code importar} no lleva {@code @Transactional} para
 * que cada fila abra la suya propia al llamar a {@link
 * RegistrarManzana#registrarPorCodigoDeSector}, que si la lleva. Ver el javadoc de {@link
 * ImportarVias} para el porque completo.
 *
 * <p>Una fila que referencia un sector que no existe se rechaza igual que una fila mal formada: el
 * sector tiene que haber entrado por {@link ImportarSectores} antes —o existir de antes—, y esa es
 * la razon de que la secuencia de carga sea vias y sectores primero, manzanas despues.
 */
@Service
public class ImportarManzanas {

    private static final int COLUMNAS_MINIMAS = 2;

    private final RegistrarManzana registrarManzana;

    public ImportarManzanas(RegistrarManzana registrarManzana) {
        this.registrarManzana = registrarManzana;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            String sectorCodigo;
            String codigo;
            try {
                List<String> campos = fila.campos();
                if (campos.size() < COLUMNAS_MINIMAS) {
                    throw new IllegalArgumentException(
                            "La fila trae "
                                    + campos.size()
                                    + " columna(s) y hacen falta "
                                    + COLUMNAS_MINIMAS
                                    + ": sectorCodigo, codigo");
                }
                sectorCodigo = campos.get(0);
                codigo = campos.get(1);
                if (sectorCodigo.isBlank()) {
                    throw new IllegalArgumentException("Falta el codigo del sector");
                }
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrarManzana.registrarPorCodigoDeSector(sectorCodigo, codigo, observacion);
                nuevas++;
            } catch (IllegalArgumentException e) {
                // Cubre tanto «no existe el sector» como una manzana mal formada: los dos son
                // mensajes propios del dominio, seguros de mostrar tal cual (ver
                // InformeDeImportacion.FilaRechazada).
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya existe una manzana con el codigo '"
                                        + codigo
                                        + "' en el sector '"
                                        + sectorCodigo
                                        + "'"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de manzanas", e);
        }
    }
}

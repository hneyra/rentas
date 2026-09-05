package kamayuk.rentas.nucleo.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga del padron vehicular desde un archivo: una fila por vehiculo, columnas {@code
 * placa,codigoContribuyente,marca,modelo,categoria,anioFabricacion,anioInscripcion} —{@code
 * categoria} admite quedar vacia—.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Mismo reparto transaccional que {@link ImportarVias}, y por el mismo motivo: este metodo
 * <b>no</b> lleva {@code @Transactional}, asi que cada fila abre la suya al llamar a {@link
 * RegistrarVehiculo}, que es un {@code @Service} distinto. Una placa repetida revienta {@code
 * vehiculo_placa_uq} y aborta <b>esa</b> transaccion; la fila siguiente entra con normalidad.
 * Envolver el bucle deshace la propiedad entera.
 *
 * <p>Ninguna cifra: el padron vehicular es registro puro. El impuesto al patrimonio necesita ademas
 * la tabla de valores referenciales y sus tramos, y eso no lo siembra nadie —es un cuadro normativo
 * nacional (D-13) que se publica con {@code PublicarCuadros}—.
 */
@Service
public class ImportarVehiculos {

    private static final int COLUMNAS_MINIMAS = 6;

    private final RegistrarVehiculo registrar;
    private final ReferenciasDeLaSiembra referencias;

    public ImportarVehiculos(RegistrarVehiculo registrar, ReferenciasDeLaSiembra referencias) {
        this.registrar = registrar;
        this.referencias = referencias;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            Vehiculo vehiculo;
            try {
                vehiculo = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrar.registrar(vehiculo, observacion);
                nuevas++;
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya hay un vehiculo con la placa '" + vehiculo.placa() + "'"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    // ------------------------------------------------------------------

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de vehiculos", e);
        }
    }

    private Vehiculo parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta al menos "
                            + COLUMNAS_MINIMAS
                            + ": placa, codigoContribuyente, marca, modelo, categoria,"
                            + " anioFabricacion, anioInscripcion");
        }
        Placa placa = Placa.de(campos.get(0));
        String codigoContribuyente = campos.get(1);
        long contribuyenteId =
                referencias
                        .contribuyenteDe(codigoContribuyente)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No hay ningun contribuyente con el codigo '"
                                                        + codigoContribuyente
                                                        + "'"));
        String marca = campos.get(2);
        String modelo = campos.get(3);
        String categoria = opcional(campos, 4);
        Ejercicio fabricacion = ejercicio(campos.get(5), "anio de fabricacion");
        Ejercicio inscripcion =
                campos.size() > 6 && !campos.get(6).isBlank()
                        ? ejercicio(campos.get(6), "anio de inscripcion")
                        : fabricacion;

        return Vehiculo.nuevo(
                placa, contribuyenteId, marca, modelo, categoria, fabricacion, inscripcion);
    }

    private static Ejercicio ejercicio(String texto, String queEs) {
        try {
            return new Ejercicio(Integer.parseInt(texto.strip()));
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "El " + queEs + " no es un anio: '" + texto + "'", noEsNumero);
        }
    }

    private static @Nullable String opcional(List<String> campos, int posicion) {
        String valor = campos.get(posicion).strip();
        return valor.isEmpty() ? null : valor;
    }
}

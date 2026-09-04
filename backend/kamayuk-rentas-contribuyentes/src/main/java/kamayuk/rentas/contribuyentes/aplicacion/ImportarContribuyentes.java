package kamayuk.rentas.contribuyentes.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.contribuyentes.dominio.CondicionEspecial;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.TipoPersona;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.TipoDocumento;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Carga del padron de contribuyentes desde un archivo: una fila por contribuyente, columnas {@code
 * codigo,tipoDocumento,numeroDocumento,tipoPersona,nombreRazonSocial,condicionEspecial,fechaNacimiento,estadoCivil}
 * —las tres ultimas admiten quedar vacias—.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Mismo patron que los tres importadores de catastro, y por la misma razon de fondo: {@code
 * importar} <b>no</b> lleva {@code @Transactional}, para que cada fila abra la suya al llamar a
 * {@link RegistrarContribuyente#registrar}, que si la lleva y es un {@code @Service} distinto de
 * este. Una fila que repite el codigo o el documento aborta esa transaccion y ninguna otra: la
 * siguiente entra con normalidad.
 *
 * <p>Envolver este bucle en una sola transaccion deshace la propiedad entera —la fila que revienta
 * se lleva a las validas que la seguian— y de paso mantiene una transaccion abierta lo que dure el
 * archivo. Es el defecto que la prueba de este importador demuestra capaz de ocurrir, y la razon de
 * que el metodo se quede sin la anotacion a proposito.
 *
 * <h2>Reimportar no duplica</h2>
 *
 * <p>{@link RegistrarContribuyente} ya comprueba el codigo y el documento repetidos antes de
 * escribir —y la tabla los exige de todos modos—, asi que volver a cargar el mismo archivo rechaza
 * todas sus filas y no inserta ninguna.
 */
@Service
public class ImportarContribuyentes {

    private static final int COLUMNAS_MINIMAS = 5;

    private final RegistrarContribuyente registrar;

    public ImportarContribuyentes(RegistrarContribuyente registrar) {
        this.registrar = registrar;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevos = 0;

        for (FilaCsv fila : filas) {
            Contribuyente contribuyente;
            try {
                contribuyente = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrar.registrar(contribuyente, observacion);
                nuevos++;
            } catch (IllegalArgumentException
                    | RegistrarContribuyente.CodigoRepetido
                    | RegistrarContribuyente.DocumentoRepetido e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                // La barrera de verdad es la unicidad de la tabla, y llega como un error de base
                // de datos que no se puede mostrar tal cual (ARQ-04 §5).
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "Ya hay un contribuyente con el codigo '"
                                        + contribuyente.codigo()
                                        + "' o con ese documento"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevos, rechazadas);
    }

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de contribuyentes", e);
        }
    }

    private static Contribuyente parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta al menos "
                            + COLUMNAS_MINIMAS
                            + ": codigo, tipoDocumento, numeroDocumento, tipoPersona,"
                            + " nombreRazonSocial");
        }
        CodigoContribuyente codigo = CodigoContribuyente.de(campos.get(0));
        DocumentoIdentidad documento =
                new DocumentoIdentidad(
                        enumerado(TipoDocumento.class, campos.get(1), "tipo de documento"),
                        campos.get(2));
        TipoPersona tipoPersona = enumerado(TipoPersona.class, campos.get(3), "tipo de persona");
        String nombre = campos.get(4);

        CondicionEspecial condicion =
                opcional(campos, 5) == null
                        ? null
                        : enumerado(CondicionEspecial.class, campos.get(5), "condicion especial");
        LocalDate nacimiento = fecha(opcional(campos, 6));
        String estadoCivil = opcional(campos, 7);

        return new Contribuyente(
                null,
                codigo,
                documento,
                tipoPersona,
                nombre,
                condicion,
                nacimiento,
                estadoCivil,
                null,
                true);
    }

    private static @Nullable String opcional(List<String> campos, int posicion) {
        if (campos.size() <= posicion || campos.get(posicion).isBlank()) {
            return null;
        }
        return campos.get(posicion).strip();
    }

    private static @Nullable LocalDate fecha(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "La fecha de nacimiento se escribe aaaa-mm-dd: '" + texto + "'");
        }
    }

    private static <E extends Enum<E>> E enumerado(Class<E> tipo, String texto, String que) {
        try {
            return Enum.valueOf(tipo, texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "El "
                            + que
                            + " '"
                            + texto
                            + "' no existe. Los validos son "
                            + java.util.Arrays.toString(tipo.getEnumConstants()));
        }
    }
}

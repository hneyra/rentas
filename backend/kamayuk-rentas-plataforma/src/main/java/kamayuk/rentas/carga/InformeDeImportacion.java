package kamayuk.rentas.carga;

import java.util.List;

/**
 * Lo que dejo una carga masiva desde archivo (#121): cuantas filas entraron, cuantas se rechazaron
 * y por que, con el numero de fila de cada una.
 *
 * <p>No hay un tercer contador para «ya existia»: una fila cuyo codigo ya esta en la base se
 * rechaza igual que una fila mal formada, con su propio motivo. Es la misma regla en los dos casos
 * que pide el issue —«un archivo con una fila que viola la unicidad: esa fila se rechaza»— y
 * «reimportar el mismo archivo no duplica» sale de ahi solo: si todo el archivo ya esta cargado,
 * todas sus filas se rechazan por existir y ninguna se inserta de nuevo.
 *
 * @param totalFilas filas de datos leidas del archivo, sin contar el encabezado ni las lineas en
 *     blanco
 * @param nuevas cuantas se registraron
 * @param rechazadas cuales no, y por que
 */
public record InformeDeImportacion(int totalFilas, int nuevas, List<FilaRechazada> rechazadas) {

    public InformeDeImportacion {
        rechazadas = List.copyOf(rechazadas);
    }

    /**
     * Una fila que no entro.
     *
     * @param fila numero de linea en el archivo, empezando en 1 con el encabezado incluido: la
     *     primera fila de datos es la 2
     * @param motivo en castellano, para mostrarse tal cual en el informe. Nunca el mensaje crudo de
     *     una excepcion de base de datos: eso filtraria tabla o restriccion (ARQ-04 §5)
     */
    public record FilaRechazada(int fila, String motivo) {

        /**
         * A partir de una excepcion de validacion del dominio —{@code IllegalArgumentException} de
         * un objeto de valor o de un caso de uso—, cuyo mensaje ya esta pensado para mostrarse tal
         * cual.
         *
         * <p>{@code getMessage()} es {@code @Nullable} para el verificador aunque en la practica
         * nunca lo sea aqui: los objetos de valor de este sistema siempre explican por que
         * rechazan. Decirlo con un texto de reserva cuesta menos que discutirlo.
         */
        public static FilaRechazada de(int fila, RuntimeException causaDeValidacion) {
            String mensaje = causaDeValidacion.getMessage();
            return new FilaRechazada(fila, mensaje == null ? "La fila no es valida" : mensaje);
        }
    }
}

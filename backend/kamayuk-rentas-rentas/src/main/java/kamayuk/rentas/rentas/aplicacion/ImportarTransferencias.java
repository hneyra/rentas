package kamayuk.rentas.rentas.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.dominio.Porcentaje;
import kamayuk.rentas.rentas.dominio.ObjetoDeTransferencia;
import kamayuk.rentas.rentas.dominio.TipoTransferencia;
import kamayuk.rentas.rentas.dominio.Vehiculo;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Carga de transferencias desde un archivo: una fila por acto, columnas {@code
 * objeto,codigoPredial,placa,codTransferente,codAdquiriente,tipoTransferencia,fechaTransferencia,
 * valorTransferencia,porcentajeTransferido,afectaAlcabala,documentoOrigen}.
 *
 * <h2>Es el unico modo de sembrar una copropiedad</h2>
 *
 * <p>{@code fichas.csv} inscribe un predio con <b>un</b> titular, y una segunda fila del mismo
 * predio se rechaza —ya tiene ficha vigente, y con razon—. La cuota compartida no se declara: se
 * <b>produce</b>, con una transferencia parcial, que es como ocurre en la realidad. {@link
 * RegistrarTransferencia#transferirPredio} cierra la titularidad del transferente y abre dos: la
 * del adquiriente por el porcentaje transferido y la del transferente por el remanente.
 *
 * <p>De ahi que este archivo se cargue <b>despues</b> de {@code fichas.csv} y de {@code
 * vehiculos.csv}: cada fila nombra por codigo algo que los otros dos tienen que haber escrito ya.
 *
 * <h2>El transferente de un vehiculo no se lee del archivo</h2>
 *
 * <p>La columna {@code codTransferente} solo se usa en las filas {@code PREDIO}, y ahi es
 * doblemente necesaria: identifica a quien transfiere <b>y</b> es por donde se busca el predio (ver
 * {@link ReferenciasDeLaSiembra#predioDe}). En las filas {@code VEHICULO} se deja vacia a
 * proposito, porque {@link RegistrarTransferencia#transferirVehiculo} toma como transferente a
 * quien figura hoy como titular y no a un dato que llegue en la peticion: un archivo que dijera
 * otra cosa firmaria una transferencia entre dos personas que no la hicieron.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Mismo reparto transaccional que {@link ImportarVias}: sin {@code @Transactional} aqui, con el
 * suyo en cada llamada a {@link RegistrarTransferencia}. Una fila cuyo transferente no tenga
 * titularidad vigente se rechaza sola y no arrastra a la siguiente.
 */
@Service
public class ImportarTransferencias {

    private static final int COLUMNAS_MINIMAS = 11;

    private final RegistrarTransferencia transferencias;
    private final ReferenciasDeLaSiembra referencias;

    public ImportarTransferencias(
            RegistrarTransferencia transferencias, ReferenciasDeLaSiembra referencias) {
        this.transferencias = transferencias;
        this.referencias = referencias;
    }

    public InformeDeImportacion importar(Reader archivo, Observacion observacion) {
        List<FilaCsv> filas = leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            Fila leida;
            try {
                leida = parsear(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            try {
                registrar(leida, observacion);
                nuevas++;
            } catch (IllegalArgumentException
                    | RegistrarTransferencia.TransferenteSinTitularidad
                    | RegistrarTransferencia.VehiculoInexistente e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    // ------------------------------------------------------------------

    private void registrar(Fila fila, Observacion observacion) {
        if (fila.objeto() == ObjetoDeTransferencia.PREDIO) {
            transferencias.transferirPredio(
                    fila.unidadId(),
                    java.util.Objects.requireNonNull(
                            fila.transferenteId(),
                            "Una transferencia de predio tiene transferente"),
                    fila.adquirienteId(),
                    fila.tipoTransferencia(),
                    fila.fecha(),
                    fila.valor(),
                    fila.porcentaje(),
                    fila.afectaAlcabala(),
                    fila.documentoOrigen(),
                    observacion);
            return;
        }
        transferencias.transferirVehiculo(
                fila.unidadId(),
                fila.adquirienteId(),
                fila.tipoTransferencia(),
                fila.fecha(),
                fila.valor(),
                fila.afectaAlcabala(),
                fila.documentoOrigen(),
                observacion);
    }

    /**
     * {@code transferenteId} es {@code null} en las filas {@code VEHICULO}, y no por comodidad: ahi
     * <b>no hay</b> transferente que el archivo pueda aportar. Dejarlo en el registro con el valor
     * que se leyo del padron invitaria a pasarselo a {@link
     * RegistrarTransferencia#transferirVehiculo}, que no lo admite justamente para que el
     * transferente sea siempre el titular vigente y no un dato de la peticion.
     */
    private record Fila(
            ObjetoDeTransferencia objeto,
            long unidadId,
            @Nullable Long transferenteId,
            long adquirienteId,
            TipoTransferencia tipoTransferencia,
            LocalDate fecha,
            Dinero valor,
            Porcentaje porcentaje,
            boolean afectaAlcabala,
            String documentoOrigen) {}

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de transferencias", e);
        }
    }

    private Fila parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS_MINIMAS
                            + ": objeto, codigoPredial, placa, codTransferente, codAdquiriente,"
                            + " tipoTransferencia, fechaTransferencia, valorTransferencia,"
                            + " porcentajeTransferido, afectaAlcabala, documentoOrigen");
        }
        ObjetoDeTransferencia objeto = objetoDe(campos.get(0));
        String codigoPredial = opcional(campos, 1);
        String placaTexto = opcional(campos, 2);
        String codTransferente = opcional(campos, 3);
        String codAdquiriente = exigir(campos, 4, "codAdquiriente");
        TipoTransferencia tipo = TipoTransferencia.de(exigir(campos, 5, "tipoTransferencia"));
        LocalDate fecha = fecha(campos.get(6));
        Dinero valor = dinero(campos.get(7));
        Porcentaje porcentaje = porcentaje(campos.get(8));
        boolean afectaAlcabala = Boolean.parseBoolean(campos.get(9).strip());
        String documentoOrigen = exigir(campos, 10, "documentoOrigen");

        long adquirienteId = contribuyente(codAdquiriente);

        if (objeto == ObjetoDeTransferencia.PREDIO) {
            if (codigoPredial == null || codTransferente == null) {
                throw new IllegalArgumentException(
                        "Una transferencia de predio necesita su codigoPredial y su"
                                + " codTransferente");
            }
            long transferenteId = contribuyente(codTransferente);
            long predioId =
                    referencias
                            .predioDe(transferenteId, codigoPredial, fecha)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "El contribuyente '"
                                                            + codTransferente
                                                            + "' no es titular del predio '"
                                                            + codigoPredial
                                                            + "' al "
                                                            + fecha));
            return new Fila(
                    objeto,
                    predioId,
                    transferenteId,
                    adquirienteId,
                    tipo,
                    fecha,
                    valor,
                    porcentaje,
                    afectaAlcabala,
                    documentoOrigen);
        }

        if (placaTexto == null) {
            throw new IllegalArgumentException("Una transferencia de vehiculo necesita su placa");
        }
        Placa placa = Placa.de(placaTexto);
        Vehiculo vehiculo =
                referencias
                        .vehiculoDe(placa)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No hay ningun vehiculo con la placa " + placa));
        Long vehiculoId = vehiculo.id();
        if (vehiculoId == null) {
            throw new IllegalArgumentException("El vehiculo " + placa + " no tiene identificador");
        }
        return new Fila(
                objeto,
                vehiculoId,
                null,
                adquirienteId,
                tipo,
                fecha,
                valor,
                porcentaje,
                afectaAlcabala,
                documentoOrigen);
    }

    private long contribuyente(String codigo) {
        return referencias
                .contribuyenteDe(codigo)
                .orElseThrow(
                        () ->
                                new IllegalArgumentException(
                                        "No hay ningun contribuyente con el codigo '"
                                                + codigo
                                                + "'"));
    }

    private static ObjetoDeTransferencia objetoDe(String texto) {
        try {
            return ObjetoDeTransferencia.valueOf(texto.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException desconocido) {
            throw new IllegalArgumentException(
                    "El objeto de la transferencia es PREDIO o VEHICULO, no '" + texto + "'",
                    desconocido);
        }
    }

    private static LocalDate fecha(String texto) {
        try {
            return LocalDate.parse(texto.strip());
        } catch (DateTimeParseException malFormada) {
            throw new IllegalArgumentException(
                    "La fecha va en formato AAAA-MM-DD: '" + texto + "'", malFormada);
        }
    }

    private static Dinero dinero(String texto) {
        try {
            return new Dinero(new BigDecimal(texto.strip()));
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "El valor de transferencia no es un importe valido: '" + texto + "'",
                    noEsNumero);
        }
    }

    private static Porcentaje porcentaje(String texto) {
        return Porcentaje.de(texto.strip());
    }

    private static String exigir(List<String> campos, int posicion, String campo) {
        String valor = campos.get(posicion).strip();
        if (valor.isEmpty()) {
            throw new IllegalArgumentException("Falta el campo obligatorio '" + campo + "'");
        }
        return valor;
    }

    private static @Nullable String opcional(List<String> campos, int posicion) {
        String valor = campos.get(posicion).strip();
        return valor.isEmpty() ? null : valor;
    }
}

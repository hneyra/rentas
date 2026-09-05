package kamayuk.rentas.nucleo.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.cuentacorriente.GeneradorDeCargos;
import kamayuk.rentas.cuentacorriente.TributoDelLibro;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.dominio.Placa;
import kamayuk.rentas.nucleo.dominio.Vehiculo;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Siembra el <b>saldo inicial</b> del libro de cuenta corriente desde un archivo: una fila por
 * obligacion, columnas {@code
 * codigoContribuyente,tributo,ejercicio,periodo,codigoPredial,placa,monto,fechaValor,
 * documentoOrigen,referenciaExterna}.
 *
 * <h2>Que cifra es esta, y por que se puede sembrar sin romper la regla 5</h2>
 *
 * <p>El monto de cada fila <b>no lo calcula nadie</b>: entra como dato, igual que entraria el saldo
 * de una base anterior el dia que se cierre D-04. No es una determinacion —no hay tramo, ni
 * alicuota, ni UIT de por medio— y por eso no depende de D-02a ni de D-11: es exactamente el mismo
 * acto que {@code MovimientosDeDeudaController} publica como alta de deuda, donde el importe lo
 * teclea quien atiende y el sistema no lo discute.
 *
 * <p>Lo que <b>no</b> hace, y no es una omision: no emite ninguna resolucion de determinacion, no
 * escribe una fila de {@code determinacion} y no reclama haber aplicado ninguna regla. Una deuda
 * sembrada asi se lee en la caja, en el estado de cuenta y en el panel; lo que no se puede es
 * pedirle al sistema que explique de donde sale, porque no sale de un calculo. En una instalacion
 * de demostracion eso es lo correcto: la alternativa —inventar los tramos para que «cuadre»—
 * produce cifras indistinguibles de las de una determinacion real.
 *
 * <h2>Por que pasa por {@link GeneradorDeCargos} y no por el libro</h2>
 *
 * <p>Porque «{@code cuentacorriente} no conoce a nadie» (ARQ-01 §4 regla 2): quien determina la
 * deuda se la <b>pide</b> al libro, y no escribe en {@code cuenta_corriente_asiento} por su cuenta.
 * Es el mismo puerto por el que entran los arbitrios, la papeleta y la tasa del anuncio. Que esta
 * carga viva en {@code rentas} sale de ahi: es el contexto que ya declara esa arista, y el unico
 * que puede resolver ademas el predio y el vehiculo de la obligacion.
 *
 * <h2>Rechazo por fila, no por archivo</h2>
 *
 * <p>Mismo reparto transaccional que {@link ImportarVias}: sin {@code @Transactional} aqui, con el
 * suyo en cada cargo. Una fila cuyo ejercicio no tenga particion declarada en el libro se rechaza
 * sola —«no partition of relation found»— y no se lleva por delante a las que la siguen.
 */
@Service
public class ImportarDeudaDeDemostracion {

    private static final int COLUMNAS_MINIMAS = 9;

    /** {@code periodo smallint}: 0 (anual) a 12 (mensual), igual que en el asiento. */
    private static final int PERIODO_MAXIMO = 12;

    private final GeneradorDeCargos cargos;
    private final ReferenciasDeLaSiembra referencias;

    public ImportarDeudaDeDemostracion(
            GeneradorDeCargos cargos, ReferenciasDeLaSiembra referencias) {
        this.cargos = cargos;
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
                cargos.generarCargo(
                        leida.ejercicio(),
                        leida.contribuyenteId(),
                        leida.tributo(),
                        leida.periodo(),
                        leida.predioId(),
                        leida.vehiculoId(),
                        leida.referenciaExterna(),
                        leida.monto(),
                        leida.fechaValor(),
                        leida.documentoOrigen(),
                        observacion);
                nuevas++;
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
            } catch (DataAccessException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "El libro no admitio el cargo de "
                                        + leida.tributo()
                                        + " "
                                        + leida.ejercicio()
                                        + ": puede que el ejercicio no tenga particion declarada"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }

    // ------------------------------------------------------------------

    private record Fila(
            long contribuyenteId,
            String tributo,
            Ejercicio ejercicio,
            @Nullable Integer periodo,
            @Nullable Long predioId,
            @Nullable Long vehiculoId,
            @Nullable String referenciaExterna,
            Dinero monto,
            LocalDate fechaValor,
            String documentoOrigen) {}

    private static List<FilaCsv> leer(Reader archivo) {
        try {
            return LectorDeFilasCsv.leer(archivo);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer el archivo de deuda", e);
        }
    }

    private Fila parsear(List<String> campos) {
        if (campos.size() < COLUMNAS_MINIMAS) {
            throw new IllegalArgumentException(
                    "La fila trae "
                            + campos.size()
                            + " columna(s) y hacen falta "
                            + COLUMNAS_MINIMAS
                            + ": codigoContribuyente, tributo, ejercicio, periodo, codigoPredial,"
                            + " placa, monto, fechaValor, documentoOrigen, referenciaExterna");
        }
        String codigoContribuyente = exigir(campos, 0, "codigoContribuyente");
        // #553: el vocabulario se comprueba AQUI y no al asentar, para que la fila se rechace
        // con su numero de linea. `toUpperCase` solo era una normalizacion, y con ella el
        // archivo sembraba `ARBITRIOS` donde el sistema escribe `ARBITRIO`: la deuda entraba,
        // se cobraba y caia al lado de la que deberia ser la misma obligacion.
        String tributo = TributoDelLibro.de(exigir(campos, 1, "tributo")).texto();
        Ejercicio ejercicio = ejercicio(campos.get(2));
        Integer periodo = periodo(opcional(campos, 3));
        String codigoPredial = opcional(campos, 4);
        String placaTexto = opcional(campos, 5);
        Dinero monto = dinero(campos.get(6));
        LocalDate fechaValor = fecha(campos.get(7));
        String documentoOrigen = exigir(campos, 8, "documentoOrigen");
        String referenciaExterna = campos.size() > 9 ? opcional(campos, 9) : null;

        long contribuyenteId =
                referencias
                        .contribuyenteDe(codigoContribuyente)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "No hay ningun contribuyente con el codigo '"
                                                        + codigoContribuyente
                                                        + "'"));

        Long predioId = null;
        if (codigoPredial != null) {
            predioId =
                    referencias
                            .predioDe(contribuyenteId, codigoPredial, fechaValor)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "El contribuyente '"
                                                            + codigoContribuyente
                                                            + "' no es titular del predio '"
                                                            + codigoPredial
                                                            + "' al "
                                                            + fechaValor));
        }

        Long vehiculoId = null;
        if (placaTexto != null) {
            Placa placa = Placa.de(placaTexto);
            Vehiculo vehiculo =
                    referencias
                            .vehiculoDe(placa)
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "No hay ningun vehiculo con la placa "
                                                            + placa));
            vehiculoId = vehiculo.id();
        }

        return new Fila(
                contribuyenteId,
                tributo,
                ejercicio,
                periodo,
                predioId,
                vehiculoId,
                referenciaExterna,
                monto,
                fechaValor,
                documentoOrigen);
    }

    private static Ejercicio ejercicio(String texto) {
        try {
            return new Ejercicio(Integer.parseInt(texto.strip()));
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "El ejercicio no es un anio: '" + texto + "'", noEsNumero);
        }
    }

    /** Vacio es {@code null}: una obligacion anual, sin cuota ni mes. */
    private static @Nullable Integer periodo(@Nullable String texto) {
        if (texto == null) {
            return null;
        }
        int valor;
        try {
            valor = Integer.parseInt(texto.strip());
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "El periodo no es un numero: '" + texto + "'", noEsNumero);
        }
        if (valor < 0 || valor > PERIODO_MAXIMO) {
            throw new IllegalArgumentException(
                    "Periodo fuera de rango: "
                            + valor
                            + ". Se admite de 0 (anual) a "
                            + PERIODO_MAXIMO);
        }
        return valor == 0 ? null : valor;
    }

    private static Dinero dinero(String texto) {
        try {
            return new Dinero(new BigDecimal(texto.strip()));
        } catch (NumberFormatException noEsNumero) {
            throw new IllegalArgumentException(
                    "El monto no es un importe valido: '" + texto + "'", noEsNumero);
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

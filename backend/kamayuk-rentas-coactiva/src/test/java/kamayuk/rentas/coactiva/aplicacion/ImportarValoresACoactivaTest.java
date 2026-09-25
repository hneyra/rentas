package kamayuk.rentas.coactiva.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dobles.ExpedientesEnMemoria;
import kamayuk.rentas.coactiva.dobles.FasesDeMentira;
import kamayuk.rentas.coactiva.dobles.LibroDeMentira;
import kamayuk.rentas.coactiva.dobles.MovimientosDelExpedienteEnMemoria;
import kamayuk.rentas.coactiva.dobles.ValoresDeMentira;
import kamayuk.rentas.coactiva.dominio.InformeDeImportacion;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.cuentacorriente.ObligacionPublica;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.ObligacionDelValor;
import kamayuk.rentas.valores.ValorParaCoactiva;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #407 — Importar un valor pasa su deuda de VALOR a COACTIVA, y cuanto pasa.
 *
 * <p>Contra PostgreSQL, {@code CostasYFraccionamientoJdbcTest} mide el camino entero —la OP, el
 * pago parcial, el pase, la importacion y el fraccionamiento—. Aqui, sin base, las dos decisiones
 * de la importacion que aquella no ejerce: una obligacion que formalizan dos valores se mueve una
 * sola vez, y una sin deuda a la fecha no se mueve.
 */
@DisplayName("#407 — La importacion pasa la deuda del valor a COACTIVA")
class ImportarValoresACoactivaTest {

    private static final Ejercicio EJERCICIO = new Ejercicio(2025);
    private static final LocalDate HOY = LocalDate.of(2026, 6, 15);
    private static final Clock RELOJ =
            Clock.fixed(HOY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
    private static final long TITULAR = 7L;

    private final MovimientosDelExpedienteEnMemoria movimientos =
            new MovimientosDelExpedienteEnMemoria();
    private final ExpedientesEnMemoria expedientes = new ExpedientesEnMemoria(movimientos);
    private final FasesDeMentira fases = new FasesDeMentira();

    @Test
    @DisplayName(
            "una obligacion que formalizan dos valores del expediente se mueve una vez, por lo"
                    + " pendiente y no por lo congelado")
    void dosValoresDeLaMismaObligacionLaMuevenUnaVez() {
        ValoresDeMentira valores =
                new ValoresDeMentira()
                        .con(valor(1L, "OP-2026-000001", predial()))
                        .con(valor(2L, "RD-2026-000001", predial()));
        LibroDeMentira libro = new LibroDeMentira().con(pendiente("PREDIAL", "300.00"));

        InformeDeImportacion informe = importar(valores, libro);

        assertThat(informe.importados()).hasSize(2);
        assertThat(fases.aCoactiva())
                .as(
                        "el par no cambia lo pendiente: moverla dos veces pondria en COACTIVA el"
                                + " doble de lo que se debe")
                .containsExactly(
                        new FasesDeMentira.Movido(
                                "PREDIAL",
                                EJERCICIO,
                                Dinero.de("300.00"),
                                informe.expedienteAbierto().numero()));
    }

    @Test
    @DisplayName(
            "una obligacion sin deuda a la fecha de la importacion no se mueve: un par por cero no"
                    + " mueve nada y deja un asiento que nadie puede explicar")
    void sinDeudaNoSeMueve() {
        ValoresDeMentira valores =
                new ValoresDeMentira()
                        .con(
                                valor(
                                        1L,
                                        "OP-2026-000001",
                                        predial(),
                                        new ObligacionDelValor("ARBITRIO", EJERCICIO, null, null)));
        LibroDeMentira libro =
                new LibroDeMentira()
                        .con(pendiente("PREDIAL", "120.00"))
                        .con(pendiente("ARBITRIO", "0.00"));

        importar(valores, libro);

        assertThat(fases.aCoactiva())
                .as("el arbitrio se pago entero antes del pase: no hay nada que cobrar en coactiva")
                .extracting(FasesDeMentira.Movido::tributo)
                .containsExactly("PREDIAL");
    }

    // ------------------------------------------------------------------

    private InformeDeImportacion importar(ValoresDeMentira valores, LibroDeMentira libro) {
        return new ImportarValoresACoactiva(
                        expedientes,
                        movimientos,
                        valores,
                        libro,
                        fases,
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ)
                .importar(
                        new ImportarValoresACoactiva.Peticion(
                                TITULAR, List.of(), "R. MENDOZA CRUZ", null, null, null),
                        HOY,
                        PlantillaDeNumeroDeExpediente.POR_OMISION,
                        Observacion.de("Se importa para la prueba de #407"));
    }

    private static ObligacionDelValor predial() {
        return new ObligacionDelValor("PREDIAL", EJERCICIO, null, null);
    }

    private static ObligacionPublica pendiente(String tributo, String total) {
        return new ObligacionPublica(
                tributo,
                EJERCICIO,
                null,
                null,
                HOY,
                Dinero.de(total),
                Dinero.CERO,
                Dinero.CERO,
                Dinero.CERO);
    }

    /** Un valor ya pasado a coactiva, congelado en 500 al emitirse. */
    private static ValorParaCoactiva valor(
            long id, String numero, ObligacionDelValor... obligaciones) {
        return new ValorParaCoactiva(
                id,
                numero.substring(0, 2),
                numero,
                new Ejercicio(2026),
                LocalDate.of(2026, 3, 2),
                TITULAR,
                "COACTIVA",
                HOY,
                LocalDate.of(2026, 5, 5),
                true,
                Dinero.de("500.00"),
                LocalDate.of(2026, 3, 2),
                List.of(obligaciones));
    }
}

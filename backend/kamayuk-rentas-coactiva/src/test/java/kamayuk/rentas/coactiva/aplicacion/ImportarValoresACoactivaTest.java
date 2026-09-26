package kamayuk.rentas.coactiva.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.coactiva.dobles.ExpedientesEnMemoria;
import kamayuk.rentas.coactiva.dobles.FasesDeMentira;
import kamayuk.rentas.coactiva.dobles.MovimientosDelExpedienteEnMemoria;
import kamayuk.rentas.coactiva.dobles.ValoresDeMentira;
import kamayuk.rentas.coactiva.dominio.InformeDeImportacion;
import kamayuk.rentas.coactiva.dominio.PlantillaDeNumeroDeExpediente;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.dominio.Observacion;
import kamayuk.rentas.valores.ObligacionDelValor;
import kamayuk.rentas.valores.ValorParaCoactiva;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * #407 — Importar un valor pide al libro que pase su deuda de VALOR a COACTIVA, una vez por
 * obligacion.
 *
 * <p>Cuanto se mueve lo decide {@code cuentacorriente} leyendo lo que la obligacion tiene en VALOR,
 * y contra PostgreSQL lo mide {@code CostasYFraccionamientoJdbcTest} —la OP, el pago parcial, el
 * cargo ordinario posterior, las dos importaciones—. Aqui, sin base, lo que si decide la
 * importacion: a que obligaciones se lo pide, y que no se lo pide dos veces para la misma aunque
 * los valores la escriban distinto.
 */
@DisplayName("#407 — La importacion pide el paso a COACTIVA una vez por obligacion")
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
            "una obligacion que formalizan dos valores de la importacion se pide una vez, aunque"
                    + " uno la escriba «predial» y el otro «PREDIAL»")
    void dosValoresDeLaMismaObligacionLaPidenUnaVez() {
        ValoresDeMentira valores =
                new ValoresDeMentira()
                        .con(valor(1L, "OP-2026-000001", obligacion("PREDIAL")))
                        .con(valor(2L, "RD-2026-000001", obligacion("predial")));

        InformeDeImportacion informe = importar(valores);

        assertThat(informe.importados()).hasSize(2);
        assertThat(fases.aCoactiva())
                .as(
                        "la deduplicacion usa la clave del libro, no el record crudo: el libro no"
                                + " distingue mayusculas, y la busqueda de la deuda tampoco")
                .containsExactly(
                        new FasesDeMentira.Movido(
                                "PREDIAL", EJERCICIO, informe.expedienteAbierto().numero()));
    }

    @Test
    @DisplayName("cada obligacion distinta del valor se pide, con el expediente como origen")
    void cadaObligacionDistintaSePide() {
        ValoresDeMentira valores =
                new ValoresDeMentira()
                        .con(
                                valor(
                                        1L,
                                        "OP-2026-000001",
                                        obligacion("PREDIAL"),
                                        obligacion("ARBITRIO")));

        InformeDeImportacion informe = importar(valores);

        assertThat(fases.aCoactiva())
                .as("una por obligacion: lo que cada una tenga en VALOR lo sabe el libro")
                .extracting(FasesDeMentira.Movido::tributo, FasesDeMentira.Movido::expediente)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "PREDIAL", informe.expedienteAbierto().numero()),
                        org.assertj.core.groups.Tuple.tuple(
                                "ARBITRIO", informe.expedienteAbierto().numero()));
    }

    /**
     * #426 — El mismo numero pedido dos veces es un valor, no dos. Hasta #426 {@code Peticion}
     * copiaba la lista tal cual: el segundo {@code importar} del mismo valor chocaba con el indice
     * unico, salia 409 «ya esta en un expediente» —falso— y se deshacia la importacion entera, la
     * del otro valor incluida. La siembra lleva el repetido Y un segundo valor distinto: con solo
     * el repetido, un arreglo que descartara la peticion entera tambien pasaria.
     */
    @Test
    @DisplayName("#426 — un numero repetido, y escrito de otra forma, se importa una sola vez")
    void unNumeroRepetidoSeImportaUnaVez() {
        ValoresDeMentira valores =
                new ValoresDeMentira()
                        .con(valor(1L, "OP-2026-000001", obligacion("PREDIAL")))
                        .con(valor(2L, "OP-2026-000002", obligacion("ARBITRIO")));

        InformeDeImportacion informe =
                importar(valores, List.of("OP-2026-000001", "op-2026-000001 ", "OP-2026-000002"));

        assertThat(informe.importados()).as("los dos valores distintos").hasSize(2);
        assertThat(expedientes.valoresDe(informe.expedienteAbierto().identificador()))
                .extracting(kamayuk.rentas.coactiva.dominio.ValorDelExpediente::valorId)
                .as("OP-2026-000001 una sola vez en el expediente")
                .containsExactly(1L, 2L);
    }

    // ------------------------------------------------------------------

    private InformeDeImportacion importar(ValoresDeMentira valores) {
        return importar(valores, List.of());
    }

    private InformeDeImportacion importar(ValoresDeMentira valores, List<String> numeros) {
        return new ImportarValoresACoactiva(
                        expedientes,
                        movimientos,
                        valores,
                        fases,
                        (RegistroDeAuditoria registro) -> {},
                        RELOJ)
                .importar(
                        new ImportarValoresACoactiva.Peticion(
                                TITULAR, numeros, "R. MENDOZA CRUZ", null, null, null),
                        HOY,
                        PlantillaDeNumeroDeExpediente.POR_OMISION,
                        Observacion.de("Se importa para la prueba de #407"));
    }

    private static ObligacionDelValor obligacion(String tributo) {
        return new ObligacionDelValor(tributo, EJERCICIO, null, null);
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

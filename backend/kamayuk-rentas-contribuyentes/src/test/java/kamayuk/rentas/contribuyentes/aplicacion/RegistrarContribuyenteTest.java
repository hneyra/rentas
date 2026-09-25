package kamayuk.rentas.contribuyentes.aplicacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.contribuyentes.dominio.CondicionEspecial;
import kamayuk.rentas.contribuyentes.dominio.Contribuyente;
import kamayuk.rentas.contribuyentes.dominio.TipoPersona;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import kamayuk.rentas.dominio.Observacion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El alta y la correccion son dos casos de uso, y la correccion no se puede auditar sin el antes
 * (#421).
 *
 * <p>Hasta #421 {@code registrar} hacia las dos cosas segun {@code esNuevo()}, y la que corregia no
 * recibia lo que habia. Lo que la separacion garantiza no es que el HTTP lo haga bien —eso lo mide
 * {@code EscrituraDelPadronControllerTest} contra PostgreSQL— sino que <b>el camino viejo ya no
 * exista</b>: una correccion que entre por el alta se rechaza en vez de auditarse sin antes.
 */
@DisplayName("#421 — Registrar da de alta; modificar corrige y audita el antes")
class RegistrarContribuyenteTest {

    private static final Observacion OBSERVACION = Observacion.de("Correccion segun DNI");

    private PadronEnMemoria padron;
    private List<RegistroDeAuditoria> asientos;
    private RegistrarContribuyente registrar;

    @BeforeEach
    void preparar() {
        padron = new PadronEnMemoria();
        asientos = new ArrayList<>();
        Auditoria auditoria = asientos::add;
        registrar = new RegistrarContribuyente(padron, auditoria);
    }

    @Test
    @DisplayName("una fila que ya esta en el padron no entra por el alta: se auditaria sin antes")
    void elAltaNoCorrige() {
        Contribuyente guardado = registrar.registrar(nuevo(), OBSERVACION);

        assertThatThrownBy(
                        () -> registrar.registrar(guardado.conNombre("OTRO, NOMBRE"), OBSERVACION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("modificar");
        assertThat(asientos).as("solo el alta").hasSize(1);
    }

    @Test
    @DisplayName("el antes y el despues tienen que ser la misma fila")
    void laCorreccionEsDeLaMismaFila() {
        Contribuyente uno = registrar.registrar(nuevo(), OBSERVACION);

        assertThatThrownBy(() -> registrar.modificar(nuevo(), uno, OBSERVACION))
                .as("un antes sin identificador no es una fila del padron")
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(asientos).hasSize(1);
    }

    @Test
    @DisplayName("la correccion audita MODIFICACION con el antes y el despues")
    void laCorreccionAuditaElAntes() {
        Contribuyente antes = registrar.registrar(nuevo(), OBSERVACION);

        registrar.modificar(antes, antes.conCondicion(CondicionEspecial.PENSIONISTA), OBSERVACION);

        RegistroDeAuditoria asiento = asientos.getLast();
        assertThat(asiento.operacion()).isEqualTo(Operacion.MODIFICACION);
        assertThat(asiento.datosAnteriores())
                .isEqualTo(antes.paraLaAuditoria())
                .contains("\"condicionEspecial\":null");
        assertThat(asiento.datosNuevos()).contains("\"condicionEspecial\":\"PENSIONISTA\"");
    }

    private static Contribuyente nuevo() {
        return Contribuyente.nuevo(
                CodigoContribuyente.de("C-0421"),
                DocumentoIdentidad.dni("40000421"),
                TipoPersona.NATURAL,
                "PEREZ GARCIA, JUAN");
    }
}

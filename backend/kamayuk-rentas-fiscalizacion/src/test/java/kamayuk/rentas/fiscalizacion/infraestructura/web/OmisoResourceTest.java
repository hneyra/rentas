package kamayuk.rentas.fiscalizacion.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import kamayuk.rentas.contribuyentes.ResumenDeContribuyente;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.fiscalizacion.dominio.CondicionFiscalizada;
import kamayuk.rentas.fiscalizacion.dominio.FilaDeOmisos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El titular de la fila de omisos: el nombre, y su codigo aparte (#545 defecto 2).
 *
 * <p>Hasta #545 la columna «Titular» de {@code fisc_omisos} ensenaba {@code C-000001}. Resolver el
 * nombre desde el cliente cuesta una peticion por fila, y esta lectura ya cruza catastro con
 * rentas: el nombre lo tiene delante.
 */
@DisplayName("#545 — El titular de la fila de omisos")
class OmisoResourceTest {

    private static final long UNO = 41L;
    private static final long OTRO = 42L;

    private static final Map<Long, ResumenDeContribuyente> PADRON =
            Map.of(
                    UNO,
                    new ResumenDeContribuyente(
                            UNO, "C-000001", "PEREZ GARCIA, JUAN", "DNI 70100001"),
                    OTRO,
                    new ResumenDeContribuyente(
                            OTRO, "C-000002", "LOPEZ DIAZ, MARIA", "DNI 70100002"));

    @Test
    @DisplayName("con un titular viajan el nombre y su codigo, cada uno en su campo")
    void conUnTitularViajanLosDos() {
        OmisoResource fila = OmisoResource.de(fila(List.of(UNO)), PADRON);

        assertThat(fila.titular())
                .as("la columna se llama «Titular» y ensenaba C-000001")
                .isEqualTo("PEREZ GARCIA, JUAN");
        assertThat(fila.codigoDelTitular()).isEqualTo("C-000001");
        assertThat(fila.titulares())
                .containsExactly(
                        new OmisoResource.TitularDelOmisoResource(
                                "C-000001", "PEREZ GARCIA, JUAN"));
    }

    @Test
    @DisplayName("con dos conyuges la columna ensena los dos nombres, y no hay UN codigo")
    void conDosTitularesNoHayUnCodigo() {
        OmisoResource fila = OmisoResource.de(fila(List.of(UNO, OTRO)), PADRON);

        assertThat(fila.titular()).isEqualTo("PEREZ GARCIA, JUAN y LOPEZ DIAZ, MARIA");
        assertThat(fila.codigoDelTitular())
                .as("elegir el de uno de los dos seria decir que el predio es suyo")
                .isNull();
        assertThat(fila.titulares()).hasSize(2);
    }

    @Test
    @DisplayName("sin titular vigente la fila sale igual, y los tres campos lo dicen")
    void sinTitularLaFilaSaleIgual() {
        OmisoResource fila = OmisoResource.de(fila(List.of()), PADRON);

        assertThat(fila.titular()).isNull();
        assertThat(fila.codigoDelTitular()).isNull();
        assertThat(fila.titulares()).isEmpty();
        assertThat(fila.condicion())
                .as("el predio que nadie reclama es el primero que hay que fiscalizar")
                .isEqualTo("OMISO");
    }

    @Test
    @DisplayName("un titular que ya no esta en el padron sale en la lista, sin nombre")
    void unTitularFueraDelPadronSaleSinNombre() {
        OmisoResource fila = OmisoResource.de(fila(List.of(999L)), PADRON);

        assertThat(fila.titulares())
                .containsExactly(new OmisoResource.TitularDelOmisoResource(null, null));
        assertThat(fila.titular())
                .as("ocultarlo escondería el predio que catastro tiene que revisar")
                .isNull();
        assertThat(fila.codigoDelTitular()).isNull();
    }

    @Test
    @DisplayName("las cuatro columnas de importe siguen sin cifra (D-02a, #198)")
    void lasCuatroColumnasDeImporteSinCifra() {
        OmisoResource fila = OmisoResource.de(fila(List.of(UNO)), PADRON);

        assertThat(fila.valorCatastralS()).isNull();
        assertThat(fila.valorDeclaradoS()).isNull();
        assertThat(fila.diferenciaS()).isNull();
        assertThat(fila.impuestoOmitidoS()).isNull();
    }

    private static FilaDeOmisos fila(List<Long> titulares) {
        return new FilaDeOmisos(
                7L,
                "000000000000000020",
                "01",
                titulares,
                new Ejercicio(2024),
                CondicionFiscalizada.OMISO,
                false,
                AreaM2.de("300.00"),
                null,
                null,
                null,
                null);
    }
}

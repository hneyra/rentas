package kamayuk.rentas.catastro.dominio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import kamayuk.rentas.esquema.BaseDeDatosDePrueba;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El cuadro tiene <b>tres</b> partidas y la ficha catastral <b>siete</b>, y no son la misma cosa
 * (issue #436, {@code V59}).
 *
 * <h2>Por que hace falta una prueba, y no basta el comentario</h2>
 *
 * <p>La confusion venia escrita en el propio esquema. {@code V1} declaro el vocabulario de siete en
 * dos sitios a la vez y afirmo que eran «las <b>dos mitades de la misma matriz</b>»; {@code V43} lo
 * repitio: {@code edificacion_estructura} «repite EXACTAMENTE el vocabulario y el dominio de {@code
 * valor_unitario_edificacion}». Con esa frase por delante, el arreglo «obvio» ante una discrepancia
 * futura es <b>volver a igualarlos</b> — y eso deshace la decision sin que nadie lo note.
 *
 * <p>Se comprobo, y por eso esto existe: devolver {@code PISOS} al {@code CHECK} de {@code V59}, o
 * al enum, dejaba <b>toda</b> la bateria en verde.
 *
 * <h2>Que decide, y con que evidencia</h2>
 *
 * <p>Las <b>tres</b> son las de la norma: el Cuadro de Valores Unitarios Oficiales de Edificacion
 * (R.M. 277-2025-VIVIENDA) lo dice en su nota al pie —«SUMANDO LOS VALORES SELECCIONADOS DE CADA
 * UNA DE LAS <b>3 COLUMNAS</b> DEL CUADRO»— y sus considerandos citan la metodologia de «tres
 * partidas de apreciacion exterior» de la R.D. 003-2022-VIVIENDA/VMVU-DGPRVU. Leer los cuatro
 * anexos regionales (#436) confirmo que ninguna region publica las otras cuatro.
 *
 * <p>Las <b>siete</b> son las del manual: el formulario de ficha catastral ({@code V1}: «manual,
 * cap. 2 §Caract. Construccion»), y NEG-05 §RT-002 las clasifica bajo «Confirmado por los
 * <b>manuales</b>» — no por ninguna resolucion.
 *
 * <p>Un catastro puede registrar mas caracteristicas de las que la valorizacion usa. Lo que no
 * puede es ponerle precio a una partida que la norma no publica.
 */
@DisplayName("Dos vocabularios de partida, y no son el mismo (#436, V59)")
class DosVocabulariosDePartidaTest {

    /** Las que la norma retiro del cuadro, y que la ficha del manual sigue teniendo. */
    private static final List<String> RETIRADAS_DEL_CUADRO =
            List.of("PISOS", "REVESTIMIENTOS", "BANIOS", "INSTALACIONES");

    private static BaseDeDatosDePrueba base;

    /**
     * La cabecera de una edicion abierta, sembrada para que el rechazo que se mide sea el del
     * vocabulario y no otro.
     *
     * <p>Hizo falta: {@code valuacion_de_publicacion_sellada_es_inmutable} (V55) es un disparador
     * {@code BEFORE} y corre <b>antes</b> que el {@code CHECK}, asi que sin una edicion valida las
     * dos pruebas de abajo fallaban con «La publicacion 1 no existe» — un rojo por un motivo que no
     * es el que se mide.
     */
    private static long edicion;

    @BeforeAll
    static void provisionar() throws SQLException, IOException {
        base = BaseDeDatosDePrueba.provisionar();
        try (Connection conexion = base.conexionAdmin();
                Statement sentencia = conexion.createStatement()) {
            try (var filas =
                    sentencia.executeQuery(
                            "INSERT INTO parametro_tributario (tipo, clave, valor_texto,"
                                    + " vigencia_desde, documento_fuente, usuario_carga,"
                                    + " usuario_aprueba) VALUES ('FICTICIO_EDICION_V59', 'CUADRO',"
                                    + " 'cabecera de la prueba de V59', DATE '2026-01-01', 'prueba de"
                                    + " V59', 'JNA', 'HNA') RETURNING id")) {
                filas.next();
                edicion = filas.getLong(1);
            }
        }
    }

    @AfterAll
    static void cerrar() {
        if (base != null) {
            base.close();
        }
    }

    @Test
    @DisplayName("el vocabulario del CUADRO son las tres partidas de apreciacion exterior")
    void elCuadroTieneTres() {
        assertThat(Arrays.stream(Partida.values()).map(Enum::name))
                .as(
                        "las tres del Cuadro de Valores Unitarios. Anadir una cuarta seria ponerle"
                                + " precio a una partida que la norma no publica, y la edicion quedaria"
                                + " con una fila que ningun anexo respalda")
                .containsExactly("MUROS", "TECHOS", "PUERTAS");
    }

    @Test
    @DisplayName("el vocabulario de la FICHA sigue teniendo siete, y eso no es una incoherencia")
    void laFichaTieneSiete() {
        assertThat(CategoriasConstructivas.ninguna().getClass().getRecordComponents())
                .as(
                        "las siete del formulario del manual. Recortarlas a tres para «igualarlas»"
                                + " con el cuadro perderia dato historico del catastro por una razon"
                                + " que no es suya (RNF-051, regla 4): la ficha describe una"
                                + " edificacion, no le pone precio")
                .hasSize(7);
    }

    @Test
    @DisplayName("y los dos vocabularios NO coinciden: es la decision, no un descuido")
    void losDosNoCoinciden() {
        assertThat(Partida.values().length)
                .as(
                        "si algun dia estos dos numeros vuelven a ser iguales, es que alguien"
                                + " deshizo V59 — probablemente creyendo que arreglaba una"
                                + " incoherencia, porque V1 y V43 decian que eran «las dos mitades de"
                                + " la misma matriz»")
                .isNotEqualTo(
                        CategoriasConstructivas.ninguna().getClass().getRecordComponents().length);
    }

    @Test
    @DisplayName("la base rechaza en el cuadro las cuatro partidas que la norma retiro")
    void laBaseRechazaLasCuatro() throws SQLException {
        for (String partida : RETIRADAS_DEL_CUADRO) {
            try (Connection conexion = base.conexionAdmin();
                    Statement sentencia = conexion.createStatement()) {
                assertThatThrownBy(
                                () ->
                                        sentencia.executeUpdate(
                                                "INSERT INTO valor_unitario_edificacion"
                                                        + " (partida, categoria, valor_m2,"
                                                        + " documento_fuente, publicacion_id,"
                                                        + " anio_construccion_desde) VALUES ('"
                                                        + partida
                                                        + "', 'C', 100, 'prueba de V59', "
                                                        + edicion
                                                        + ", 1990)"))
                        .as(
                                "%s salio del cuadro con V59. Sin el CHECK, publicar la edicion de"
                                        + " la norma dejaria esta partida sin ninguna fila, y una"
                                        + " edicion incompleta no se distingue de una completa hasta"
                                        + " que alguien valoriza un predio",
                                partida)
                        .isInstanceOf(SQLException.class)
                        .hasMessageContaining("valor_unitario_edificacion_partida_check");
            }
        }
    }

    @Test
    @DisplayName("y admite las tres, para que el rechazo de arriba no sea el de otra cosa")
    void laBaseAdmiteLasTres() throws SQLException {
        // Sin esto, las cuatro de arriba podrian estar fallando por el disparador de la edicion,
        // por el CHECK de categoria o por cualquier otra restriccion, y la prueba diria que el
        // vocabulario esta bien cuando no esta midiendo el vocabulario.
        for (Partida partida : Partida.values()) {
            try (Connection conexion = base.conexionAdmin();
                    Statement sentencia = conexion.createStatement()) {
                int filas =
                        sentencia.executeUpdate(
                                "INSERT INTO valor_unitario_edificacion (partida, categoria,"
                                        + " valor_m2, documento_fuente, publicacion_id,"
                                        + " anio_construccion_desde) VALUES ('"
                                        + partida.name()
                                        + "', 'C', 100, 'prueba de V59', "
                                        + edicion
                                        + ", 1990)");
                assertThat(filas).as("%s si esta en el cuadro y entra", partida).isEqualTo(1);
            }
        }
    }
}

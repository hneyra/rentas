package kamayuk.rentas.nucleo.infraestructura.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Ejercicio;
import kamayuk.rentas.nucleo.dominio.CorridaDeEmision;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Los dos agregados que la corrida sabe, publicados como campo</b> (#271).
 *
 * <h2>Que se mide y por que hacia falta</h2>
 *
 * <p>El panel del modulo —«Estado de la emision»— dibuja «Cuentas emitidas» y «Monto determinado»
 * arriba, como cifra suelta, y los dos estaban en {@code corrida_predial} desde #523 sin salir por
 * la API mas que <b>dentro</b> de una fila de {@link CorridaPredialResource.Etapa}. Mientras fuera
 * asi, la unica manera de pintarlos era leer la segunda fila de la tabla en el navegador, que es
 * exactamente lo que la regla de {@code conectores.ts} prohibe: el dia que la corrida tenga dos
 * etapas de determinacion, o que una se renombre, el campo seguiria ensenando una cifra plausible y
 * equivocada, en verde.
 *
 * <h2>La muestra NO es uniforme, a proposito</h2>
 *
 * <p>Los cuatro enteros de esta corrida son distintos —{@code id} 7, {@code leidos} 9 999, {@code
 * determinados} 9 417, {@code observados} 582— porque una muestra con cifras que coinciden no
 * distingue «publica los determinados» de «publica los leidos», ni de «publica los observados». Con
 * ellos iguales, tres implementaciones pasarian la misma prueba.
 *
 * <h2>Y el tercero desde #312: el derecho de emision que la corrida SELLO</h2>
 *
 * <p>Aqui la muestra tampoco puede ser una sola. Se ejercen <b>dos</b> corridas —una sellada por
 * {@code V23} y otra anterior a la migracion— porque con solo la primera la rama del nulo no
 * correria nunca, y una implementacion que devolviera {@code "0.00"} donde no hay nada sellado
 * saldria en verde. Nulo dice «esa corrida no lo guardo»; cero diria «no se cobro derecho de
 * emision», que es falso.
 */
@DisplayName("GET /rentas/predial/corridas/ultima — los agregados de la corrida (#271, #312)")
class CorridaGuardadaResourceTest {

    private static final int LEIDOS = 9_999;
    private static final int DETERMINADOS = 9_417;
    private static final int FUERA = LEIDOS - DETERMINADOS;
    private static final String EMITIDO = "9418204.60";
    private static final long CONJUNTO_ID = 31L;
    private static final String DERECHO = "4.50";

    /** Una corrida ya escrita, con las cuatro cifras separadas entre si, y con su sello (V23). */
    private static CorridaDeEmision corrida() {
        return corridaCon(CONJUNTO_ID, Dinero.de(DERECHO));
    }

    /**
     * <b>Una corrida anterior a {@code V23}</b>: las dos columnas del sello en nulo.
     *
     * <p>No es un caso inventado: es toda corrida escrita antes de #312, y es la unica muestra que
     * distingue «publica el derecho sellado» de «publica cero cuando no lo hay» — con solo la
     * corrida sellada, una implementacion que devolviera {@code "0.00"} pasaria en verde.
     */
    private static CorridaDeEmision corridaAnteriorALaMigracion() {
        return corridaCon(null, null);
    }

    private static CorridaDeEmision corridaCon(
            @Nullable Long conjuntoId, @Nullable Dinero derecho) {
        return new CorridaDeEmision(
                7L,
                new Ejercicio(2026),
                "TODOS",
                null,
                null,
                null,
                "TRIMESTRAL",
                false,
                "2026 v1",
                conjuntoId,
                derecho,
                LEIDOS,
                DETERMINADOS,
                Dinero.de(EMITIDO),
                LocalDate.of(2026, 1, 28),
                List.of());
    }

    @Test
    @DisplayName("las cuentas emitidas son los DETERMINADOS, no los leidos ni los observados")
    void lasCuentasEmitidasSonLosDeterminados() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());

        assertThat(resource.determinados())
                .as("es la columna `determinados` de la corrida, no una cuenta recompuesta")
                .isEqualTo(DETERMINADOS);
        assertThat(resource.determinados()).isNotEqualTo(LEIDOS).isNotEqualTo(FUERA);
        assertThat(resource.observados()).isEqualTo(FUERA);
    }

    @Test
    @DisplayName("el monto determinado sale TAL CUAL, sin redondear ni formatear")
    void elMontoSaleTalCual() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());

        // Texto y no un numero de coma flotante (regla 1, RNF-055): `9418204.60` en un `double`
        // vuelve como `9418204.6`, y el centimo perdido no da error en ningun sitio.
        assertThat(resource.montoEmitido()).isEqualTo(EMITIDO);
    }

    /**
     * <b>Un hecho, publicado una vez.</b>
     *
     * <p>La etapa «Determinados» y el campo de arriba dicen lo mismo de la misma corrida. Si
     * salieran de dos sitios distintos, la que se leyera al abrir la pantalla seria la que nadie
     * compara — que es el motivo por el que {@link CorridaGuardadaResource} compone las etapas de
     * los mismos campos y no al reves.
     */
    @Test
    @DisplayName("el campo y su etapa dicen lo mismo, porque salen del mismo campo de la corrida")
    void elCampoYSuEtapaDicenLoMismo() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());
        CorridaPredialResource.Etapa determinacion = resource.etapas().get(1);

        assertThat(determinacion.etapa()).isEqualTo("Determinados");
        assertThat(determinacion.registros()).isEqualTo(resource.determinados());
        assertThat(determinacion.monto()).isEqualTo(resource.montoEmitido());
    }

    /**
     * <b>El tercer campo del panel: el derecho que la corrida SELLO</b> (#312, V23, D-02b).
     *
     * <h2>Que decia antes esta prueba, y por que decia eso</h2>
     *
     * <p>Se llamaba {@code elDerechoDeEmisionNoSePublica} y afirmaba que <b>ningun componente de
     * este record se llama «derecho»</b>. No era una prohibicion caprichosa: mientras {@code
     * corrida_predial} no tuviera columna, el unico sitio de donde sacar la cifra era el conjunto
     * vigente <b>hoy</b>, que no tiene por que ser el que la corrida uso. Esta prueba existia para
     * ponerse roja exactamente el dia que alguien anadiera el campo por ese camino, y su segunda
     * asercion lo decia: lo que la corrida sella es el <b>nombre</b> del conjunto, no su
     * identificador.
     *
     * <h2>Que afirma ahora</h2>
     *
     * <p>{@code V23} arreglo la causa: la corrida sella {@code derecho_emision} y {@code
     * conjunto_id} el dia de la emision. Asi que el campo entra <b>acompanado de la columna</b>,
     * que es la condicion que la prueba anterior ponia. Lo que se afirma es lo que hace falta para
     * que el camino prohibido siga prohibido:
     *
     * <ul>
     *   <li>el campo sale <b>tal cual</b> de la corrida, sin redondear ni formatear;
     *   <li>viene <b>acompanado</b> de {@code conjuntoId}: una cifra sellada sin su conjunto vuelve
     *       a ser un numero sin fuente;
     *   <li>y una corrida que <b>no lo sello</b> publica {@code null} y no {@code "0.00"} — que son
     *       dos afirmaciones distintas, y la segunda es falsa.
     * </ul>
     */
    @Test
    @DisplayName("el derecho de emision es el que la corrida SELLO, con el conjunto del que salio")
    void elDerechoDeEmisionEsElQueLaCorridaSello() {
        CorridaGuardadaResource resource = CorridaGuardadaResource.de(corrida());

        assertThat(resource.derechoDeEmision())
                .as("es la columna `derecho_emision`, no una lectura del conjunto vigente hoy")
                .isEqualTo(DERECHO);
        assertThat(resource.conjuntoId())
                .as(
                        "la cifra sellada viaja con el conjunto del que salio, o no se puede contrastar")
                .isEqualTo(CONJUNTO_ID);
        assertThat(resource.conjunto())
                .as("y el nombre sigue saliendo, que es lo que una persona lee")
                .isEqualTo("2026 v1");
        // Texto y no coma flotante (regla 1, RNF-055): `4.50` en un `double` vuelve como `4.5`.
        assertThat(resource.derechoDeEmision()).isNotEqualTo("4.5");
    }

    /**
     * <b>Nulo no es cero, y esta es la muestra que lo separa.</b>
     *
     * <p>Una corrida anterior a {@code V23} no guardo el derecho, y no se puede inventar para ella:
     * el valor salio de un conjunto que su fila no nombra. Publicar {@code "0.00"} ahi diria «no se
     * cobro derecho de emision», que es <b>falso</b> — se cobro, y esta sumado dentro de {@code
     * montoEmitido}, de donde no se puede volver a separar.
     *
     * <p>Sin esta prueba la muestra seria uniforme —todas las corridas selladas— y la rama del nulo
     * no correria nunca: una implementacion que devolviera cero saldria verde.
     */
    @Test
    @DisplayName("una corrida anterior a V23 publica NULO, y jamas un cero")
    void laCorridaAnteriorALaMigracionNoDiceCero() {
        CorridaGuardadaResource resource =
                CorridaGuardadaResource.de(corridaAnteriorALaMigracion());

        assertThat(resource.derechoDeEmision())
                .as("«esa corrida no lo guardo» y «no se cobro derecho» son dos cosas distintas")
                .isNull();
        assertThat(resource.conjuntoId())
                .as("tampoco su conjunto: sin el no hay de donde volver a leer aquel valor")
                .isNull();
        // Y lo que si guardo sigue saliendo: el nulo es del sello, no de la corrida entera.
        assertThat(resource.determinados()).isEqualTo(DETERMINADOS);
        assertThat(resource.montoEmitido()).isEqualTo(EMITIDO);
    }

    /**
     * <b>El centinela de la prueba que esto sustituye.</b>
     *
     * <p>La anterior vigilaba por reflexion que ningun componente se llamara «derecho». Ese
     * criterio se retira porque su causa se arreglo, pero la mitad que sigue valiendo —<b>el campo
     * solo existe acompanado de la columna que lo sella</b>— no tiene por que perderse: aqui se
     * afirma que los dos componentes del sello estan, y que los dos son <b>anulables</b>. Un {@code
     * long conjuntoId} primitivo, por ejemplo, no podria distinguir «no lo sello» de «el conjunto
     * cero».
     */
    @Test
    @DisplayName("el sello son DOS componentes, y los dos admiten el nulo")
    void elSelloSonDosComponentesAnulables() {
        List<String> campos =
                Arrays.stream(CorridaGuardadaResource.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .toList();

        assertThat(campos).contains("derechoDeEmision", "conjuntoId");
        assertThat(
                        Arrays.stream(CorridaGuardadaResource.class.getRecordComponents())
                                .filter(uno -> uno.getName().equals("conjuntoId"))
                                .map(java.lang.reflect.RecordComponent::getType)
                                .toList())
                .as("un `long` primitivo leeria «no lo sello» como «el conjunto cero»")
                .containsExactly(Long.class);
    }
}

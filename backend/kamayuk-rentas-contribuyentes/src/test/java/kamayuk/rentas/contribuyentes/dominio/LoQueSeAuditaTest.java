package kamayuk.rentas.contribuyentes.dominio;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import kamayuk.rentas.dominio.CodigoContribuyente;
import kamayuk.rentas.dominio.DocumentoIdentidad;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que el padron deja en la auditoria es TODO lo que sus escrituras pueden cambiar (#421).
 *
 * <p>Hasta #421 la descripcion del contribuyente llevaba cuatro campos y la correccion cambiaba
 * ocho; la del contacto llevaba dos y la correccion cambiaba seis. Ninguna prueba lo decia, porque
 * ninguna miraba lo que quedaba en la auditoria.
 *
 * <p>Esta prueba no lista los campos que se auditan: lista los que <b>no</b>, con su motivo, y todo
 * componente del registro que no este ahi tiene que salir en {@code paraLaAuditoria()} con su
 * valor. Asi, un campo nuevo en {@link Contribuyente} o en {@link Contacto} sale rojo aqui hasta
 * que alguien decida si se audita — que es lo que el issue pedia de «una sola fuente de verdad».
 */
@DisplayName("#421 — Lo que se audita del padron es todo lo que la escritura puede cambiar")
class LoQueSeAuditaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Lo que el contribuyente NO lleva a la auditoria, y por que. */
    private static final Map<String, String> NO_SE_AUDITAN_DEL_CONTRIBUYENTE =
            Map.of(
                    "id", "es la clave de la fila de auditoria",
                    "documento", "es la identidad: ninguna escritura lo corrige");

    /** Lo que el contacto NO lleva a la auditoria, y por que. */
    private static final Map<String, String> NO_SE_AUDITAN_DEL_CONTACTO =
            Map.of(
                    "id", "es la clave de la fila de auditoria",
                    "contribuyenteId", "ninguna escritura mueve un contacto de contribuyente");

    /**
     * El componente {@code observacion} del contacto se audita como {@code nota}, que es su API.
     */
    private static final Map<String, String> CLAVE_DEL_CONTACTO = Map.of("observacion", "nota");

    @Test
    @DisplayName("el contribuyente lleva cada componente con su valor, salvo los excluidos")
    void elContribuyente() {
        Contribuyente todo =
                new Contribuyente(
                        7L,
                        CodigoContribuyente.de("C-0007"),
                        DocumentoIdentidad.dni("40000007"),
                        TipoPersona.NATURAL,
                        "PEREZ GARCIA, JUAN",
                        CondicionEspecial.PENSIONISTA,
                        LocalDate.of(1950, 2, 1),
                        "CASADO",
                        8L,
                        true);

        cadaComponenteSeAuditaODiceQueNo(
                todo, todo.paraLaAuditoria(), NO_SE_AUDITAN_DEL_CONTRIBUYENTE, Map.of());
    }

    @Test
    @DisplayName("el contacto lleva cada componente con su valor, salvo los excluidos")
    void elContacto() {
        Contacto todo =
                new Contacto(
                        11L,
                        7L,
                        TipoContacto.GESTOR,
                        "a@x.pe",
                        "GESTOR, EL",
                        "40999605",
                        "Llamar tras las 6",
                        true);

        cadaComponenteSeAuditaODiceQueNo(
                todo, todo.paraLaAuditoria(), NO_SE_AUDITAN_DEL_CONTACTO, CLAVE_DEL_CONTACTO);
    }

    @Test
    @DisplayName("un campo vacio sale como null, no desaparece: su ausencia es el dato")
    void loVacioSeNombra() {
        JsonNode contribuyente =
                leer(
                        Contribuyente.nuevo(
                                        CodigoContribuyente.de("C-0008"),
                                        DocumentoIdentidad.dni("40000008"),
                                        TipoPersona.NATURAL,
                                        "SIN NADA, ALGUIEN")
                                .paraLaAuditoria());
        JsonNode contacto =
                leer(Contacto.nuevo(7L, TipoContacto.CELULAR, "969000001").paraLaAuditoria());

        assertThat(esNullExplicito(contribuyente, "condicionEspecial"))
                .as("el antes de «se le concedio la condicion» es «no tenia ninguna»")
                .isTrue();
        assertThat(esNullExplicito(contribuyente, "conyugeId")).isTrue();
        assertThat(esNullExplicito(contacto, "nombre")).isTrue();
    }

    @Test
    @DisplayName(
            "el texto libre sale como JSON valido aunque traiga comillas, barras o tabuladores")
    void elTextoLibreSeEscapa() {
        String raro = "O'NEIL \"EL\" \\ SUR\tNORTE\nOTRA\u0001";
        Contacto contacto =
                new Contacto(11L, 7L, TipoContacto.GESTOR, "969000001", raro, null, raro, true);

        JsonNode leido = leer(contacto.paraLaAuditoria());

        assertThat(leido.get("nombre").asString())
                .as(
                        "#421 mete texto libre en la auditoria del contacto: sin escaparlo, cada"
                                + " comilla seria un 500 que hoy no existe")
                .isEqualTo(raro);
        assertThat(leido.get("nota").asString()).isEqualTo(raro);
    }

    // ------------------------------------------------------------------

    private static void cadaComponenteSeAuditaODiceQueNo(
            Record registro,
            String json,
            Map<String, String> excluidos,
            Map<String, String> clavePorComponente) {

        JsonNode auditado = leer(json);
        Set<String> esperadas = new HashSet<>();

        for (RecordComponent componente : registro.getClass().getRecordComponents()) {
            String nombre = componente.getName();
            String clave = clavePorComponente.getOrDefault(nombre, nombre);
            if (excluidos.containsKey(nombre)) {
                assertThat(auditado.has(clave))
                        .as(
                                "%s esta excluido (%s) y sin embargo se audita",
                                nombre, excluidos.get(nombre))
                        .isFalse();
                continue;
            }
            esperadas.add(clave);
            assertThat(auditado.has(clave))
                    .as(
                            "%s.%s no esta en paraLaAuditoria() ni en la lista de excluidos de esta"
                                    + " prueba: decide si se audita (#421)",
                            registro.getClass().getSimpleName(), nombre)
                    .isTrue();
            assertThat(auditado.get(clave).asString())
                    .as("%s se audita con su valor", nombre)
                    .isEqualTo(String.valueOf(valorDe(componente, registro)));
        }

        Set<String> claves = new HashSet<>(auditado.propertyNames());
        assertThat(claves)
                .as("la auditoria no inventa claves que no son de ningun componente")
                .isEqualTo(esperadas);
    }

    private static Object valorDe(RecordComponent componente, Record registro) {
        try {
            return componente.getAccessor().invoke(registro);
        } catch (ReflectiveOperationException fallo) {
            throw new IllegalStateException(fallo);
        }
    }

    /** La clave esta, y vale {@code null}: no es lo mismo que no estar. */
    private static boolean esNullExplicito(JsonNode objeto, String clave) {
        return objeto.has(clave) && objeto.get(clave).isNull();
    }

    private static JsonNode leer(String json) {
        return JSON.readTree(json);
    }
}

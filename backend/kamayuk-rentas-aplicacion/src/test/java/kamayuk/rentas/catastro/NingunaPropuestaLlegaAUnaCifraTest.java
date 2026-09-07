package kamayuk.rentas.catastro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.dominio.Medida;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Una longitud que nadie firmo no puede llegar a una cifra (#15).
 *
 * <h2>Por que la guarda mira la FORMA del tipo y no un camino de calculo</h2>
 *
 * <p>Porque hoy ese camino no existe: el frente no lo consume todavia ningun caso de uso —la tasa
 * del barrido la bloquea D-02b— y una prueba que ejercitara «el calculo del arbitrio» no tendria
 * que ejercitar. Escribirla igual seria una prueba que pasa por no haber sujeto, que es la trampa
 * que este repositorio lleva doscientos issues evitando.
 *
 * <p>Lo que si existe hoy, y es lo que #15 pide poner <b>mientras no hay consumidor</b>, es la
 * forma del tipo: si {@link FrentesInscritos} no ofrece ninguna lista que mezcle los dos estados ni
 * ningun total que no diga «confirmados», entonces la ruta corta —recorrer y sumar— no se puede
 * escribir sin nombrar lo que se esta haciendo. Es la misma leccion que C-7 dejo al retirar Jackson
 * 2 de los siete {@code build.gradle.kts}: la guarda mas fuerte es la que hace que el defecto no se
 * pueda escribir.
 *
 * <p>Y por eso la guarda vigila las <b>altas</b> y no solo el estado de hoy: el dia que alguien
 * anada un {@code todos()} o un {@code metrosLineales()} —que es exactamente lo que hara quien
 * escriba la pantalla o el arbitrio— esto se pone rojo antes de que exista el consumidor que lo
 * cobraria.
 */
@DisplayName("#15 — ninguna longitud PROPUESTA llega a una cifra")
class NingunaPropuestaLlegaAUnaCifraTest {

    /** Los dos unicos nombres por los que se puede llegar a una lista de frentes. */
    private static final List<String> LISTAS_QUE_DICEN_CUAL = List.of("confirmados", "propuestos");

    @Test
    @DisplayName("`FrentesInscritos` no publica ninguna lista que mezcle los dos estados")
    void ningunaListaMezclaLosDosEstados() {
        List<String> listas = new ArrayList<>();
        for (Method metodo : FrentesInscritos.class.getDeclaredMethods()) {
            if (!esPublicoDeInstancia(metodo)) {
                continue;
            }
            if (devuelveColeccionDe(metodo.getGenericReturnType(), FrenteInscrito.class)) {
                listas.add(metodo.getName());
            }
        }

        assertThat(listas)
                .as(
                        "una lista que mezcla PROPUESTA y CONFIRMADA es la ruta corta con otro"
                                + " nombre: recorrerla y sumar `longitud()` cobra metros que nadie"
                                + " firmo (ADR-0021)")
                .containsExactlyInAnyOrderElementsOf(LISTAS_QUE_DICEN_CUAL);
    }

    @Test
    @DisplayName("y el unico total que ofrece dice en su nombre que son los confirmados")
    void elUnicoTotalDiceQueSonLosConfirmados() {
        List<String> totales = new ArrayList<>();
        for (Method metodo : FrentesInscritos.class.getDeclaredMethods()) {
            if (!esPublicoDeInstancia(metodo)) {
                continue;
            }
            if (devuelveUnaMedida(metodo.getGenericReturnType())) {
                totales.add(metodo.getName());
            }
        }

        assertThat(totales)
                .as(
                        "sin este contraste la comprobacion de abajo se cumpliria sola sobre la"
                                + " lista vacia: un tipo sin ningun total la pasaria en verde")
                .isNotEmpty();
        assertThat(totales)
                .as(
                        "un `metrosLineales()` a secas volveria a sumar las propuestas, y el"
                                + " llamador no tendria como saberlo")
                .allSatisfy(
                        nombre ->
                                assertThat(nombre.toLowerCase(java.util.Locale.ROOT))
                                        .contains("confirmados"));
    }

    @Test
    @DisplayName("`longitudEstado` es un tipo cerrado y no una cadena suelta")
    void elEstadoEsUnTipoYNoUnaCadena() {
        Class<?> declarado =
                java.util.Arrays.stream(FrenteInscrito.class.getRecordComponents())
                        .filter(componente -> componente.getName().equals("longitudEstado"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "`FrenteInscrito` dejo de declarar"
                                                        + " «longitudEstado»: sin ese campo una"
                                                        + " PROPUESTA y una CONFIRMADA llegan"
                                                        + " iguales"))
                        .getType();

        assertThat(declarado)
                .as(
                        "con un `String`, «PROPUESTA» y «CONFIRMADA» son dos cadenas y nada impide"
                                + " compararlas al reves, escribirlas con otra caja o no mirarlas")
                .isEqualTo(EstadoDeLaLongitud.class);
        assertThat(EstadoDeLaLongitud.values())
                .as("los dos valores que existen, y ninguno mas")
                .containsExactly(EstadoDeLaLongitud.PROPUESTA, EstadoDeLaLongitud.CONFIRMADA);
    }

    @Test
    @DisplayName("un valor desconocido —o ausente— no se reconoce como estado")
    void unValorDesconocidoNoSeReconoce() {
        assertThat(EstadoDeLaLongitud.reconocer("CONFIRMADA"))
                .contains(EstadoDeLaLongitud.CONFIRMADA);
        assertThat(EstadoDeLaLongitud.reconocer(" propuesta "))
                .as("se normaliza la caja y los espacios, que es transporte y no significado")
                .contains(EstadoDeLaLongitud.PROPUESTA);
        assertThat(EstadoDeLaLongitud.reconocer(""))
                .as(
                        "la cadena vacia era el valor por omision de antes de #15, y un consumidor"
                                + " escrito como !\"PROPUESTA\".equals(estado) la habria cobrado")
                .isEmpty();
        assertThat(EstadoDeLaLongitud.reconocer(null)).isEmpty();
        assertThat(EstadoDeLaLongitud.reconocer("FIRMADA")).isEmpty();
    }

    @Test
    @DisplayName("el total suma los confirmados y deja fuera los propuestos")
    void elTotalDejaFueraLosPropuestos() {
        FrentesInscritos frentes =
                new FrentesInscritos(
                        11L,
                        List.of(
                                frente(1L, "18.50", EstadoDeLaLongitud.CONFIRMADA),
                                frente(2L, "6.50", EstadoDeLaLongitud.CONFIRMADA)),
                        List.of(frente(3L, "40.00", EstadoDeLaLongitud.PROPUESTA)),
                        "2026-02-01T08:00:00Z",
                        3,
                        null);

        assertThat(frentes.metrosLinealesConfirmados())
                .as(
                        "los 40,00 ML propuestos los corto una maquina contra el eje de la via, y"
                                + " nadie los firmo")
                .contains(Medida.enMetrosLineales("25.00"));
        assertThat(frentes.propuestos())
                .as("no se ocultan: son la lista de lo que falta por confirmar")
                .hasSize(1);
    }

    @Test
    @DisplayName("y sin ningun confirmado el total esta VACIO, que no es cero")
    void sinConfirmadosElTotalEstaVacio() {
        FrentesInscritos frentes =
                new FrentesInscritos(
                        11L,
                        List.of(),
                        List.of(frente(3L, "40.00", EstadoDeLaLongitud.PROPUESTA)),
                        "2026-02-01T08:00:00Z",
                        1,
                        null);

        assertThat(frentes.metrosLinealesConfirmados())
                .as(
                        "cero metros lineales es una cifra, y cobraria de menos a todo el padron"
                                + " sin que nada pareciera mal (#48)")
                .isEmpty();
    }

    @Test
    @DisplayName("y el tipo se niega a que una PROPUESTA viaje en la lista de confirmados")
    void unaPropuestaNoViajaComoConfirmada() {
        assertThatThrownBy(
                        () ->
                                new FrentesInscritos(
                                        11L,
                                        List.of(frente(3L, "40.00", EstadoDeLaLongitud.PROPUESTA)),
                                        List.of(),
                                        null,
                                        null,
                                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADR-0021");

        assertThatThrownBy(
                        () ->
                                new FrentesInscritos(
                                        11L,
                                        List.of(),
                                        List.of(frente(1L, "18.50", EstadoDeLaLongitud.CONFIRMADA)),
                                        null,
                                        null,
                                        null))
                .as("la otra direccion: quedaria fuera de la base sin que nadie lo decidiera")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONFIRMADA");
    }

    // ------------------------------------------------------------------

    private static FrenteInscrito frente(long id, String metros, EstadoDeLaLongitud estado) {
        return new FrenteInscrito(
                id,
                21L,
                "AV-0007",
                "AV. CAYETANO HEREDIA",
                Medida.enMetrosLineales(metros),
                estado,
                id == 1L,
                null,
                null,
                estado.confirmada() ? "jperez" : null,
                estado.confirmada() ? "2026-02-03T10:15:30Z" : null);
    }

    private static boolean esPublicoDeInstancia(Method metodo) {
        return java.lang.reflect.Modifier.isPublic(metodo.getModifiers())
                && !java.lang.reflect.Modifier.isStatic(metodo.getModifiers())
                && !metodo.isSynthetic();
    }

    /** Si el metodo devuelve una coleccion —o un {@code Optional}— de ese tipo. */
    private static boolean devuelveColeccionDe(Type tipo, Class<?> elemento) {
        if (!(tipo instanceof ParameterizedType parametrizado)) {
            return false;
        }
        if (!(parametrizado.getRawType() instanceof Class<?> crudo)) {
            return false;
        }
        if (!Collection.class.isAssignableFrom(crudo)) {
            return false;
        }
        Type[] argumentos = parametrizado.getActualTypeArguments();
        return argumentos.length == 1 && argumentos[0].equals(elemento);
    }

    /** Si el metodo devuelve una {@link Medida}, envuelta en un {@code Optional} o no. */
    private static boolean devuelveUnaMedida(Type tipo) {
        if (tipo.equals(Medida.class)) {
            return true;
        }
        if (tipo instanceof ParameterizedType parametrizado
                && Optional.class.equals(parametrizado.getRawType())) {
            return parametrizado.getActualTypeArguments()[0].equals(Medida.class);
        }
        return false;
    }
}

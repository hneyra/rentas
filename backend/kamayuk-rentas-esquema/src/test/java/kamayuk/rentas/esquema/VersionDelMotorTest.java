package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La version del motor de prueba se declara y se comprueba (C-4).
 *
 * <h2>Que defecto cierra</h2>
 *
 * <p>El esquema de este producto <b>no aplica en PostgreSQL 18</b>. PostgreSQL 17 restringe el
 * {@code search_path} de {@code CREATE INDEX} a {@code pg_catalog, pg_temp}, y {@code
 * nombre_normalizado} resuelve por {@code search_path} tanto la funcion {@code unaccent} como el
 * literal {@code 'unaccent'::regdictionary}, asi que la migracion muere con «text search dictionary
 * "unaccent" does not exist ... during inlining» — un mensaje que no se parece en nada a su causa.
 *
 * <p>Hasta C-4 nada lo impedia. El camino de Testcontainers fija {@code
 * postgis/postgis:16-3.4-alpine}, pero la salida de emergencia {@code kamayuk.pruebas.postgres.url}
 * —la que usa toda maquina sin Docker— apunta al motor que tenga quien construye. En la maquina
 * donde se escribio esto, {@code psql --version} devuelve 18.6.
 *
 * <h2>Por que esta prueba mira una funcion pura y no un motor</h2>
 *
 * <p>Por el mismo reparto que {@code CodificacionDeLaBaseDePruebaTest}: comprobarlo contra el motor
 * de verdad solo puede fallar en una maquina que tenga la version mala, asi que en CI —que corre
 * 16— pasaria en verde diga lo que diga la guarda. Lo que si se puede medir en cualquier maquina es
 * que la decision siga puesta y que los dos lados sigan diciendo cosas distintas.
 *
 * <p>La otra mitad —que la guarda muerda de verdad contra un PostgreSQL 18 real— se midio una vez y
 * esta escrita en {@code infrastructure/docs/00-gobierno/C-4-postgresql-18.md}.
 */
@DisplayName("C-4 — La version del motor de prueba")
class VersionDelMotorTest {

    @Test
    @DisplayName("la soportada es la 16, que es la que declaran los ambientes y CI")
    void laSoportadaEsLa16() {
        assertThat(MotorPostgres.MAJOR_SOPORTADA)
                .as(
                        "los dos Pulumi, los dos compose, el guion de respaldo y la imagen por"
                                + " omision de MotorPostgres dicen postgis/postgis:16-3.4-alpine. Subir"
                                + " este numero sin mover esos cinco sitios deja la guarda admitiendo"
                                + " una version que no se despliega en ninguna parte")
                .isEqualTo(16);
    }

    @Test
    @DisplayName("la 16 se admite: sin esto, la guarda podria estar diciendo que no a todo")
    void la16SeAdmite() {
        assertThat(MotorPostgres.motivoDeVersionNoSoportada(16))
                .as(
                        "es el contraste. Una guarda que rechazara tambien la version buena pondria"
                                + " en rojo el camino correcto, y el arreglo comodo seria quitarla")
                .isEmpty();
    }

    @Test
    @DisplayName("la 17 y la 18 se rechazan nombrando la version y el defecto medido")
    void las17YLa18SeRechazan() {
        for (int major : new int[] {17, 18}) {
            assertThat(MotorPostgres.motivoDeVersionNoSoportada(major))
                    .as("PostgreSQL %d tiene que rechazarse", major)
                    .isPresent()
                    .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                    .as(
                            "el mensaje tiene que nombrar la version que se encontro, el defecto"
                                    + " medido y donde esta escrito: sin eso, quien lo lea no puede"
                                    + " distinguir «no lo hemos probado» de «esta roto»")
                    .contains("PostgreSQL " + major)
                    .contains("search_path")
                    .contains("unaccent")
                    .contains("C-4-postgresql-18.md");
        }
    }

    @Test
    @DisplayName("una version mas antigua se rechaza, pero NO diciendo que este rota")
    void unaMasAntiguaSeRechazaPorOtroMotivo() {
        assertThat(MotorPostgres.motivoDeVersionNoSoportada(15))
                .isPresent()
                .get(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .as(
                        "en la 15 no hay ningun defecto medido: lo que no hay es una corrida que lo"
                                + " demuestre. Darle el mismo texto que a la 18 seria afirmar un defecto"
                                + " que nadie ha visto")
                .contains("PostgreSQL 15")
                .doesNotContain("search_path")
                .doesNotContain("unaccent");
    }
}

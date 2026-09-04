package kamayuk.rentas.esquema;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La base de prueba declara su codificacion en vez de heredarla (#706).
 *
 * <h2>Por que esta prueba mira la sentencia y no la base</h2>
 *
 * <p>El defecto solo se manifiesta contra un anfitrion que no sea UTF-8: {@code CREATE DATABASE} a
 * secas hereda la codificacion de {@code template1}, y en un cluster {@code SQL_ASCII} —tres de los
 * once locales lo estaban— no existe {@code chr(1114111)}, con el que {@code ViaRepositoryJdbc}
 * (#565) cierra el rango de prefijo. En un cluster UTF-8, que es el de CI, cualquier comprobacion
 * <i>sobre la base creada</i> pasa en verde diga lo que diga la sentencia.
 *
 * <p>De ahi el reparto: la base creada la comprueba {@code MotorPostgres.exigirCodificacionUtf8} en
 * cada arranque —y ahi si muerde, contra el anfitrion que no puede darla—, y lo que esta prueba
 * sujeta es lo unico que se puede medir en cualquier maquina: que la sentencia siga declarando las
 * tres cosas que #706 midio, para que quitar una sea una decision y no un descuido.
 */
@DisplayName("#706 — La base de prueba declara su codificacion")
class CodificacionDeLaBaseDePruebaTest {

    private final String sentencia = MotorPostgres.sentenciaDeCreacion("sgtm_prueba_abcd1234");

    @Test
    @DisplayName(
            "va por template0: desde template1 solo se puede copiar la codificacion que ya hay")
    void vaPorTemplate0() {
        assertThat(sentencia)
                .as(
                        "sin TEMPLATE template0 la base hereda la codificacion del anfitrion, que es"
                                + " justo lo que #706 arregla: PostgreSQL rechaza declarar otra"
                                + " codificacion sobre template1")
                .contains("TEMPLATE template0");
    }

    @Test
    @DisplayName("declara UTF8: en SQL_ASCII no existe el chr(1114111) del rango de prefijo")
    void declaraUtf8() {
        assertThat(sentencia)
                .as(
                        "sin ENCODING la base de prueba sale en la codificacion del cluster"
                                + " anfitrion, y contra uno SQL_ASCII las busquedas por prefijo de #565"
                                + " fallan con «requested character too large for encoding»")
                .contains("ENCODING 'UTF8'");
    }

    @Test
    @DisplayName("el tipo de caracter conoce el UTF-8: con «C», lower y upper solo saben ASCII")
    void elTipoDeCaracterConoceElUtf8() {
        assertThat(sentencia)
                .as(
                        "medido: con LC_CTYPE 'C' sobre una base UTF-8, lower('CAÑETE') devuelve"
                                + " 'caÑete' y upper('ñ') devuelve 'ñ', asi que el filtro por uso de"
                                + " FichaCatastralRepositoryJdbc —que compara upper(translate(...))"
                                + " contra lo que la pantalla manda en mayusculas— deja de encontrar un"
                                + " uso con «ñ» y devuelve cero filas, que se lee como «no hay ninguna"
                                + " ficha asi»")
                .contains("LC_CTYPE 'C.UTF-8'");
    }

    @Test
    @DisplayName("la intercalacion es la misma que el tipo de caracter, y es orden de byte")
    void laIntercalacionEsOrdenDeByte() {
        assertThat(sentencia)
                .as(
                        "medido: 'a' < 'B' da falso tanto en C como en C.UTF-8 —las dos ordenan por"
                                + " byte—, y los indices de prefijo se declaran text_pattern_ops (V14,"
                                + " V66), que ordena por byte pase lo que pase; asi que las pruebas de"
                                + " plan (#313, #536, #561, #565) no dependen de esta eleccion, y se"
                                + " declara la misma que el tipo de caracter para no tener dos cosas que"
                                + " ajustar")
                .contains("LC_COLLATE 'C.UTF-8'");
    }
}

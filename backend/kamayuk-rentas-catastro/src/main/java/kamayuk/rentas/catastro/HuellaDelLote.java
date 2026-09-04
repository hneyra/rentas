package kamayuk.rentas.catastro;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * La huella de un lote y la de un sector, para la anti-entropia entre catastro y rentas.
 *
 * <h2>Que problema resuelve</h2>
 *
 * <p>Este sistema mantiene una proyeccion del padron de {@code catastro} —{@code predio_ref},
 * {@code ficha_ref}— que se alimenta de eventos. Una proyeccion alimentada por eventos se
 * desincroniza: un evento que se perdio, uno que llego dos veces, uno que se aplico en el orden
 * equivocado. Y <b>no se nota</b>: la fila esta, tiene la forma correcta y dice otra cosa. La
 * pregunta que hay que poder contestar delante de un contribuyente —«¿por que esta ficha dice esta
 * direccion?»— se contesta comparando, y comparar el padron entero cada dia es leer dos veces todo
 * el catastro.
 *
 * <p>La anti-entropia compara <b>huellas por sector</b> —una cifra por sector, no una por lote— y
 * solo pide en detalle los sectores que no cuadran. En Catacaos son 14 422 predios repartidos en
 * unas decenas de sectores: la comparacion diaria son decenas de cifras, y el detalle solo del
 * sector que difiere.
 *
 * <h2>Por que esta funcion es, ella misma, un contrato entre dos repositorios</h2>
 *
 * <p>Los dos lados calculan la huella por su cuenta, sobre dos bases distintas. Si los dos calculos
 * no son <b>identicos hasta el byte</b>, la comparacion no falla ruidosamente: o todos los sectores
 * salen discrepantes —y entonces la anti-entropia deja de leerse en una semana—, o ninguno, y
 * entonces no protege nada y nadie lo sabe. Las dos son peores que no tenerla.
 *
 * <p>Por eso el algoritmo esta fijado con <b>vectores de oro comprometidos</b> —{@code
 * rentas/docs/50-api/anti-entropia/huella-del-lote.json}— que las dos implementaciones tienen que
 * reproducir, cada una en su propio CI. Cambiar el algoritmo de un lado sin cambiar el otro pone
 * rojo el build de quien lo cambio.
 *
 * <h2>Las tres decisiones del algoritmo, con su motivo</h2>
 *
 * <ol>
 *   <li><b>El separador es {@code U+001F} (separador de unidad)</b> y no una coma ni un guion:
 *       tiene que ser un caracter que <b>no pueda aparecer</b> en ningun campo. Con uno que pueda,
 *       dos lotes distintos producen la misma concatenacion —«AV. GRAU 100» en el sector «A» y «AV.
 *       GRAU» en el sector «100, A»— y sus huellas coinciden: la anti-entropia diria que cuadran.
 *   <li><b>Un campo nulo es la cadena vacia</b>, y por eso el separador importa doble: sin el, un
 *       sector nulo y un sector vacio serian indistinguibles de un desplazamiento de campos.
 *   <li><b>Las huellas de un sector se combinan EN ORDEN de {@code predioId} ascendente.</b> Sin un
 *       orden fijo, los dos lados suman lo mismo en distinto orden y obtienen huellas distintas —el
 *       modo de fallo mas caro, porque parece una discrepancia de datos y es una de codigo—.
 * </ol>
 *
 * <p>Lo que la huella <b>no</b> cubre queda dicho: solo los cinco campos que la proyeccion copia.
 * Un cambio en la geometria o en el ubigeo no la mueve, porque {@code predio_ref} no los lleva y
 * exigirle a {@code rentas} que los proyecte para poder compararlos seria proyectar datos que nadie
 * usa.
 */
public final class HuellaDelLote {

    /**
     * El separador de campos: {@code U+001F}, «unit separator» de ASCII.
     *
     * <p>No es una preferencia estetica: es la unica familia de caracteres que ninguna direccion,
     * ningun codigo catastral y ningun estado puede contener. Ver la decision (1) de la cabecera.
     */
    // `(char) 0x1F` y no el caracter literal: un caracter de control dentro de un
    // literal es INVISIBLE en un diff y cualquier formateador o editor puede comerselo
    // sin que nadie lo vea — y de las dos implementaciones, la que se lo comiera dejaria
    // de cuadrar con la otra en TODOS los sectores.
    public static final char SEPARADOR = (char) 0x1F;

    /** El separador entre huellas de lote al componer la de un sector. */
    public static final char SEPARADOR_DE_LOTES = '\n';

    private HuellaDelLote() {}

    /**
     * La huella de un lote: sha256 en hexadecimal minusculo de sus cinco campos proyectados.
     *
     * @param predioId el identificador del predio en {@code catastro}, que es la clave de la
     *     proyeccion
     * @param codigoRefCatastral el codigo de referencia catastral
     * @param direccion la direccion tal como el padron la tiene
     * @param sectorCodigo el codigo del sector, o {@code null} si el predio no esta sectorizado
     * @param estado ACTIVO o el que tenga
     */
    public static String deUnLote(
            long predioId,
            String codigoRefCatastral,
            String direccion,
            @Nullable String sectorCodigo,
            String estado) {

        String cuerpo =
                predioId
                        + separador()
                        + codigoRefCatastral
                        + separador()
                        + direccion
                        + separador()
                        + (sectorCodigo == null ? "" : sectorCodigo)
                        + separador()
                        + estado;
        return sha256(cuerpo);
    }

    /**
     * La huella de un sector: sha256 de las huellas de sus lotes, <b>en el orden en que vienen</b>.
     *
     * <p>Quien llama tiene que darlas ordenadas por {@code predioId} ascendente. No se ordenan aqui
     * a proposito: las dos implementaciones ordenan en su consulta —{@code ORDER BY predio_id}—, y
     * ordenar tambien aqui escondería que una de las dos no lo hiciera. Lo que sostiene el orden es
     * el vector de oro que lleva dos lotes.
     */
    public static String deUnSector(List<String> huellasDeSusLotes) {
        return sha256(String.join(String.valueOf(SEPARADOR_DE_LOTES), huellasDeSusLotes));
    }

    private static String separador() {
        return String.valueOf(SEPARADOR);
    }

    private static String sha256(String cuerpo) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(cuerpo.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException imposible) {
            // SHA-256 es obligatorio en toda implementacion de la plataforma Java.
            throw new IllegalStateException("SHA-256 no esta disponible", imposible);
        }
    }
}

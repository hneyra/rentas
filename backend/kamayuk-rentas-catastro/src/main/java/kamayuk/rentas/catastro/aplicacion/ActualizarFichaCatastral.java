package kamayuk.rentas.catastro.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import kamayuk.rentas.auditoria.Auditoria;
import kamayuk.rentas.auditoria.Operacion;
import kamayuk.rentas.auditoria.RegistroDeAuditoria;
import kamayuk.rentas.catastro.dominio.Construccion;
import kamayuk.rentas.catastro.dominio.DetalleDeLaFicha;
import kamayuk.rentas.catastro.dominio.FichaCatastral;
import kamayuk.rentas.catastro.dominio.FichaCatastralRepository;
import kamayuk.rentas.catastro.dominio.OrigenDeLaFicha;
import kamayuk.rentas.catastro.dominio.OtraInstalacion;
import kamayuk.rentas.catastro.dominio.TipoFicha;
import kamayuk.rentas.dominio.AreaM2;
import kamayuk.rentas.dominio.Observacion;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * La actualizacion del catastro, tal como la exige el manual (cap. 2 §Actualizacion del Catastro):
 * <b>modificar una ficha no sobrescribe</b>.
 *
 * <p>{@link #actualizar} hace las tres cosas en una sola transaccion:
 *
 * <ol>
 *   <li>copia la version vigente, con sus construcciones y sus instalaciones;
 *   <li>cierra la anterior el dia antes de que empiece la nueva;
 *   <li>abre la version siguiente con los datos modificados, su autor, su fecha y su observacion.
 * </ol>
 *
 * <p><b>El orden es cerrar y despues abrir</b>, y lo impone la base: {@code ficha_vigente_uq} es un
 * indice unico parcial y se comprueba en el acto, asi que con las dos abiertas a la vez el {@code
 * INSERT} falla. Es distinto del disparador de la titularidad, que si es diferido y por eso admite
 * el orden contrario.
 *
 * <p>Que el predio quede un instante sin ficha vigente no es un problema: ocurre dentro de la
 * transaccion, y ninguna otra sesion ve filas sin confirmar.
 *
 * <p>Que esto valga la pena se ve en 2029: una determinacion de 2027 se calculo sobre la ficha que
 * regia en 2027. Si esa ficha se hubiera editado en el sitio, la determinacion no se podria
 * reproducir ni defender ante una reclamacion.
 */
@Service
public class ActualizarFichaCatastral {

    private final FichaCatastralRepository repositorio;
    private final Auditoria auditoria;
    private final Clock reloj;

    public ActualizarFichaCatastral(
            FichaCatastralRepository repositorio, Auditoria auditoria, Clock reloj) {
        this.repositorio = repositorio;
        this.auditoria = auditoria;
        this.reloj = reloj;
    }

    /** Registra la primera version de la ficha de un predio. */
    @Transactional
    public FichaCatastral registrarPrimera(FichaCatastral ficha, Observacion observacion) {
        if (ficha.version() != 1) {
            throw new IllegalArgumentException(
                    "La primera version de una ficha es la 1, no la " + ficha.version());
        }
        Optional<FichaCatastral> yaHay = repositorio.ultimaVersion(ficha.predioId(), ficha.tipo());
        if (yaHay.isPresent()) {
            throw new YaTieneFicha(ficha.predioId(), ficha.tipo());
        }

        FichaCatastral guardada = repositorio.insertar(ficha);
        auditar(guardada, Operacion.ALTA, observacion, null);
        return guardada;
    }

    /**
     * Crea la version siguiente a partir de la vigente y cierra aquella.
     *
     * <p>{@code construcciones}, {@code instalaciones} y {@code detalle} nulos significan «lo mismo
     * que tenia»: la copia es el comportamiento por omision, porque es lo que casi siempre se
     * quiere y olvidarla borraria lo declarado sin que ningun {@code DELETE} apareciera en el diff.
     *
     * <p>Vale para los cuatro tipos. Lo unico que cambia entre ellos es {@code detalle}, y que sea
     * del tipo que la ficha declara lo comprueba el constructor de {@link FichaCatastral}.
     */
    @Transactional
    public FichaCatastral actualizar(
            long predioId,
            TipoFicha tipo,
            LocalDate desde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            @Nullable List<Construccion> construcciones,
            @Nullable List<OtraInstalacion> instalaciones,
            @Nullable DetalleDeLaFicha detalle,
            Observacion observacion) {
        return versionar(
                predioId,
                tipo,
                desde,
                origen,
                documentoOrigen,
                construcciones,
                instalaciones,
                detalle,
                null,
                null,
                observacion);
    }

    /**
     * La version que corrige la <b>estructura inscrita</b>: ademas del detalle, el area del terreno
     * y el uso.
     *
     * <p>Existe desde #52 y la usa un solo llamador: {@link
     * kamayuk.rentas.catastro.TransferenciaDeFiscalizacion}, el puerto por el que {@code
     * fiscalizacion} escribe en el padron. Es lo que RF-054 llama «lo hallado sobrescribe lo
     * declarado», con la salvedad de que aqui nada se sobrescribe: se versiona.
     *
     * <p><b>Es un metodo aparte y no dos parametros mas en {@link #actualizar}</b>, y conviene
     * saber por que. Hasta #52 <b>ninguna</b> ruta cambiaba el area ni el uso de una ficha: la
     * pantalla de actualizacion del catastro versiona construcciones, instalaciones y detalle, y
     * {@code siguienteVersion} copia el area y el uso tal cual. Anadirselos a la firma comun habria
     * abierto esa puerta para todos los llamadores presentes y futuros; asi la puerta tiene un
     * nombre, un usuario y una regla de arquitectura que la vigila.
     *
     * <p>{@code areaTerreno} y {@code uso} nulos significan «lo mismo que tenia», igual que las
     * listas.
     */
    @Transactional
    public FichaCatastral actualizarEstructura(
            long predioId,
            TipoFicha tipo,
            LocalDate desde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            @Nullable AreaM2 areaTerreno,
            @Nullable String uso,
            Observacion observacion) {
        return versionar(
                predioId,
                tipo,
                desde,
                origen,
                documentoOrigen,
                null,
                null,
                null,
                areaTerreno,
                uso,
                observacion);
    }

    /**
     * Copiar, cerrar y abrir: el unico sitio donde una ficha se versiona.
     *
     * <p>No es {@code @Transactional} porque los dos metodos publicos que lo llaman ya lo son, y
     * una llamada interna no pasaria por el proxy de Spring de todas formas. Que sean dos firmas y
     * un solo cuerpo es lo que impide que un dia diverjan en el orden de cerrar y abrir.
     */
    private FichaCatastral versionar(
            long predioId,
            TipoFicha tipo,
            LocalDate desde,
            OrigenDeLaFicha origen,
            String documentoOrigen,
            @Nullable List<Construccion> construcciones,
            @Nullable List<OtraInstalacion> instalaciones,
            @Nullable DetalleDeLaFicha detalle,
            @Nullable AreaM2 areaTerreno,
            @Nullable String uso,
            Observacion observacion) {

        FichaCatastral vigente =
                repositorio
                        .vigenteA(predioId, tipo, desde)
                        .orElseThrow(() -> new SinFichaVigente(predioId, tipo, desde));

        FichaCatastral siguiente =
                vigente.siguienteVersion(desde, origen, documentoOrigen, observacion);
        if (construcciones != null) {
            siguiente = siguiente.con(construcciones);
        }
        if (instalaciones != null) {
            siguiente = siguiente.conInstalaciones(instalaciones);
        }
        if (detalle != null) {
            siguiente = siguiente.conDetalle(detalle);
        }
        if (areaTerreno != null) {
            siguiente = siguiente.conArea(areaTerreno);
        }
        if (uso != null) {
            siguiente = siguiente.conUso(uso);
        }

        // Cerrar antes de abrir, y no al reves: ficha_vigente_uq es un indice unico parcial,
        // que se comprueba en el acto —a diferencia del disparador de la titularidad, que es
        // diferido—. Con las dos abiertas a la vez, el INSERT falla.
        //
        // Que el predio quede un instante sin ficha vigente no importa: pasa dentro de la
        // transaccion, y ninguna otra sesion ve filas sin confirmar.
        FichaCatastral cerrada = repositorio.cerrar(vigente.cerradaEl(desde.minusDays(1)));
        FichaCatastral abierta = repositorio.insertar(siguiente);

        auditar(cerrada, Operacion.MODIFICACION, observacion, descripcion(vigente));
        auditar(abierta, Operacion.ALTA, observacion, null);

        return abierta;
    }

    /** La ficha que regia en esa fecha. Es como se reconstruye el pasado (regla 9). */
    @Transactional(readOnly = true)
    public Optional<FichaCatastral> vigenteA(long predioId, TipoFicha tipo, LocalDate fecha) {
        return repositorio.vigenteA(predioId, tipo, fecha);
    }

    @Transactional(readOnly = true)
    public List<FichaCatastral> historial(long predioId, TipoFicha tipo) {
        return repositorio.historial(predioId, tipo);
    }

    private void auditar(
            FichaCatastral ficha,
            Operacion operacion,
            Observacion observacion,
            @Nullable String antes) {
        auditoria.registrar(
                RegistroDeAuditoria.enLaFechaDe(
                                LocalDate.now(reloj),
                                "ficha_catastral",
                                String.valueOf(ficha.id()),
                                operacion,
                                observacion)
                        .con(antes, descripcion(ficha)));
    }

    private static String descripcion(FichaCatastral ficha) {
        return "{\"version\":"
                + ficha.version()
                + ",\"areaTerreno\":\""
                // Sin la unidad dentro (#607): la concatenacion llama a AreaM2.toString(),
                // que anade « m2», y esta cadena se publica VERBATIM por
                // GET /seguridad/auditoria. Era la tercera convencion, y el escaner de
                // fuentes no la ve porque aqui no hay ningun `.toString()` escrito.
                + ficha.areaTerreno().valor().toPlainString()
                + "\",\"uso\":\""
                + ficha.uso().replace("\"", "\\\"")
                + "\",\"construcciones\":"
                + ficha.construcciones().size()
                + ",\"vigenciaHasta\":"
                + (ficha.vigenciaHasta() == null ? "null" : "\"" + ficha.vigenciaHasta() + "\"")
                + "}";
    }

    /** El predio ya tiene ficha de ese tipo: lo que toca es actualizarla, no crear otra primera. */
    public static final class YaTieneFicha extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        YaTieneFicha(long predioId, TipoFicha tipo) {
            super(
                    "El predio "
                            + predioId
                            + " ya tiene ficha de tipo "
                            + tipo
                            + "; modificarla es crear la version siguiente, no otra primera");
        }
    }

    /** No hay ficha vigente a esa fecha: no se puede versionar lo que no existe. */
    public static final class SinFichaVigente extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        SinFichaVigente(long predioId, TipoFicha tipo, LocalDate fecha) {
            super(
                    "El predio "
                            + predioId
                            + " no tiene ficha "
                            + tipo
                            + " vigente al "
                            + fecha
                            + "; la primera version se registra, no se actualiza");
        }
    }
}

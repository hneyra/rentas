package kamayuk.rentas.seguridad.aplicacion;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import kamayuk.rentas.autorizacion.Privilegio;
import kamayuk.rentas.persistencia.RepositorioJdbc;
import kamayuk.rentas.seguridad.dominio.EventoDeIdentidadRecibido;
import kamayuk.rentas.seguridad.dominio.TipoDeEventoDeIdentidad;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Aplica UN evento del buzon de {@code identidad} a la copia local, en su propia transaccion (etapa
 * 4 de ADR-0039, ADR-0028 §3).
 *
 * <h2>Una transaccion por evento, y {@code REQUIRES_NEW} a proposito</h2>
 *
 * <p>Cada llamada abre su transaccion con el gestor de tenant, que hace el {@code SET LOCAL} con el
 * contexto del momento. Con {@code REQUIRED}, una vuelta que cambiara de municipalidad a mitad se
 * uniria a la transaccion de la anterior y la fila de B caeria en A (AC-7 #2). Y como el acuse
 * local —{@code identidad_evento_aplicado}— se escribe en la MISMA transaccion que la fila, un
 * evento a medias no existe: o entro entero, o no entro.
 *
 * <h2>Los tres desenlaces que no son «aplicado»</h2>
 *
 * <ul>
 *   <li>{@link Aplicacion#YA_APLICADO}: la entrega es al menos una vez, y deduplica el receptor por
 *       {@code evento_id}. Se acusa sin volver a escribir.
 *   <li>{@link Aplicacion#IGNORADO_AJENO}: un {@code PERMISO_FIJADO} de otro sistema. {@code
 *       identidad} publica los cinco catalogos por un solo buzon y {@code permisos} es una opcion
 *       de {@code identidad} <b>y</b> de {@code rentas}: aplicarlo «por el codigo» seria conceder
 *       aqui lo que se concedio en otro sistema (AC-7 #4). Se acusa y no se toca la copia.
 *   <li>{@link NoSePuedeAplicar}: no se podra aplicar NUNCA —tipo desconocido, cuerpo ilegible, sin
 *       la clave con la que se casa—. Quien llama lo aparta, lo acusa y avisa.
 *   <li>{@link TodaviaNo}: no se puede aplicar AHORA —la afiliacion cuyo grupo no ha llegado—. No
 *       se acusa: el buzon lo vuelve a servir. Apartarlo seria matar por un motivo que se arregla
 *       solo (AC-7 #3).
 * </ul>
 *
 * <p>Una baja se aplica como baja —la fila se queda, inhabilitada o inactiva— y un usuario que
 * llega y no existe se crea: el cuerpo es la fila entera, asi que alta y modificacion son la misma
 * escritura ({@code INSERT ... ON CONFLICT DO UPDATE}).
 */
@Service
public class AplicarUnEventoDeIdentidad extends RepositorioJdbc {

    /** El sistema cuyos permisos rigen aqui: los de los otros cuatro se acusan y se ignoran. */
    public static final String ESTE_SISTEMA = "rentas";

    private static final String SIN_QUIEN_LO_DIO_DE_ALTA = "identidad";
    private static final int LARGO_DEL_TIPO = 40;
    private static final int LARGO_DE_LA_HUELLA = 64;
    private static final int LARGO_DEL_MOTIVO = 400;

    private final JsonMapper json;
    private final Clock reloj;

    public AplicarUnEventoDeIdentidad(JdbcClient jdbc, JsonMapper json, Clock reloj) {
        super(jdbc);
        this.json = json;
        this.reloj = reloj;
    }

    public enum Aplicacion {
        APLICADO,
        YA_APLICADO,
        IGNORADO_AJENO
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Aplicacion aplicar(EventoDeIdentidadRecibido evento) {
        TipoDeEventoDeIdentidad tipo = evento.tipo();
        if (tipo == null) {
            throw new NoSePuedeAplicar(
                    "`identidad` publica un evento de tipo «"
                            + evento.tipoPublicado()
                            + "» y esta copia no lo conoce. Los que conoce son los siete de"
                            + " TipoDeEventoDeIdentidad; un octavo es un cambio del contrato del"
                            + " buzon, y esta copia no lo aplica a ciegas");
        }
        JsonNode cuerpo = leer(evento);

        if (!marcarComoAplicado(evento)) {
            return Aplicacion.YA_APLICADO;
        }
        return switch (tipo) {
            case USUARIO_DADO_DE_ALTA, USUARIO_MODIFICADO -> usuario(cuerpo);
            case GRUPO_DADO_DE_ALTA, GRUPO_MODIFICADO -> grupo(cuerpo);
            case MIEMBRO_AFILIADO, MIEMBRO_DESAFILIADO -> miembro(cuerpo);
            case PERMISO_FIJADO -> permiso(cuerpo);
        };
    }

    /**
     * Aparta el evento a la cola de muertos con su motivo. Idempotente: apartar dos veces es una.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apartar(EventoDeIdentidadRecibido evento, String motivo) {
        jdbc().sql(
                        "INSERT INTO identidad_evento_muerto (municipalidad_id, evento_id,"
                                + " secuencia, tipo, sujeto_id, cuerpo, huella, motivo, apartado_en)"
                                + " VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :eventoId, :secuencia, :tipo, :sujetoId, :cuerpo, :huella,"
                                + " :motivo, :cuando)"
                                + " ON CONFLICT (municipalidad_id, evento_id) DO NOTHING")
                .param("eventoId", evento.eventoId())
                .param("secuencia", evento.secuencia())
                .param("tipo", recortar(evento.tipoPublicado(), LARGO_DEL_TIPO))
                .param("sujetoId", evento.sujetoId())
                .param("cuerpo", evento.cuerpo())
                .param("huella", recortar(evento.huella(), LARGO_DE_LA_HUELLA))
                .param("motivo", recortar(motivo, LARGO_DEL_MOTIVO))
                .param("cuando", reloj.instant().atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Cuantos eventos hay apartados en esta municipalidad: es la cifra del aviso. */
    @Transactional(readOnly = true)
    public long apartados() {
        return jdbc().sql("SELECT count(*) FROM identidad_evento_muerto")
                .query(Long.class)
                .single();
    }

    // ------------------------------------------------------------------

    private boolean marcarComoAplicado(EventoDeIdentidadRecibido evento) {
        int escritas =
                jdbc().sql(
                                "INSERT INTO identidad_evento_aplicado (municipalidad_id,"
                                        + " evento_id, secuencia, tipo, sujeto_id, huella,"
                                        + " aplicado_en) VALUES ("
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", :eventoId, :secuencia, :tipo, :sujetoId, :huella,"
                                        + " :cuando)"
                                        + " ON CONFLICT (municipalidad_id, evento_id) DO NOTHING")
                        .param("eventoId", evento.eventoId())
                        .param("secuencia", evento.secuencia())
                        .param("tipo", recortar(evento.tipoPublicado(), LARGO_DEL_TIPO))
                        .param("sujetoId", evento.sujetoId())
                        .param("huella", recortar(evento.huella(), LARGO_DE_LA_HUELLA))
                        .param("cuando", reloj.instant().atOffset(ZoneOffset.UTC))
                        .update();
        return escritas == 1;
    }

    private Aplicacion usuario(JsonNode cuerpo) {
        jdbc().sql(
                        "INSERT INTO usuario (municipalidad_id, cuenta, nombre, correo, habilitado,"
                                + " vigencia_desde, vigencia_hasta) VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :cuenta, :nombre, :correo, :habilitado, :desde, :hasta)"
                                + " ON CONFLICT (municipalidad_id, cuenta) DO UPDATE SET"
                                + " nombre = EXCLUDED.nombre, correo = EXCLUDED.correo,"
                                + " habilitado = EXCLUDED.habilitado,"
                                + " vigencia_desde = EXCLUDED.vigencia_desde,"
                                + " vigencia_hasta = EXCLUDED.vigencia_hasta")
                .param("cuenta", exigir(cuerpo, "cuenta"))
                .param("nombre", exigir(cuerpo, "nombre"))
                .param("correo", textoONulo(cuerpo, "correo"))
                .param("habilitado", cuerpo.path("habilitado").asBoolean(true))
                .param("desde", fechaONula(cuerpo, "vigenciaDesde"))
                .param("hasta", fechaONula(cuerpo, "vigenciaHasta"))
                .update();
        return Aplicacion.APLICADO;
    }

    private Aplicacion grupo(JsonNode cuerpo) {
        jdbc().sql(
                        "INSERT INTO grupo (municipalidad_id, nombre, descripcion, habilitado,"
                                + " vigencia_desde, vigencia_hasta) VALUES ("
                                + MUNICIPALIDAD_ACTUAL
                                + ", :nombre, :descripcion, :habilitado, :desde, :hasta)"
                                + " ON CONFLICT (municipalidad_id, nombre) DO UPDATE SET"
                                + " descripcion = EXCLUDED.descripcion,"
                                + " habilitado = EXCLUDED.habilitado,"
                                + " vigencia_desde = EXCLUDED.vigencia_desde,"
                                + " vigencia_hasta = EXCLUDED.vigencia_hasta")
                .param("nombre", exigir(cuerpo, "nombre"))
                .param("descripcion", textoONulo(cuerpo, "descripcion"))
                .param("habilitado", cuerpo.path("habilitado").asBoolean(true))
                .param("desde", fechaONula(cuerpo, "vigenciaDesde"))
                .param("hasta", fechaONula(cuerpo, "vigenciaHasta"))
                .update();
        return Aplicacion.APLICADO;
    }

    private Aplicacion miembro(JsonNode cuerpo) {
        String grupo = exigir(cuerpo, "grupoNombre");
        String cuenta = exigir(cuerpo, "usuarioCuenta");
        boolean activo = cuerpo.path("activo").asBoolean(true);
        String usuarioAlta = textoONulo(cuerpo, "usuarioAlta");
        // Se casa por el NOMBRE del grupo y la CUENTA del usuario, no por sus identificadores:
        // los ids del cuerpo son los de `identidad`, y aqui cada fila tiene el suyo.
        int escritas =
                jdbc().sql(
                                "INSERT INTO miembro (municipalidad_id, grupo_id, usuario_id,"
                                        + " usuario_alta, activo, fecha_baja, usuario_baja)"
                                        + " SELECT "
                                        + MUNICIPALIDAD_ACTUAL
                                        + ", g.id, u.id, :usuarioAlta, :activo, :fechaBaja,"
                                        + " :usuarioBaja FROM grupo g, usuario u"
                                        + " WHERE g.nombre = :grupo AND u.cuenta = :cuenta"
                                        + " ON CONFLICT (municipalidad_id, grupo_id, usuario_id)"
                                        + " DO UPDATE SET activo = EXCLUDED.activo,"
                                        + " fecha_baja = EXCLUDED.fecha_baja,"
                                        + " usuario_baja = EXCLUDED.usuario_baja")
                        .param(
                                "usuarioAlta",
                                usuarioAlta == null ? SIN_QUIEN_LO_DIO_DE_ALTA : usuarioAlta)
                        .param("activo", activo)
                        .param(
                                "fechaBaja",
                                activo ? null : reloj.instant().atOffset(ZoneOffset.UTC))
                        .param("usuarioBaja", activo ? null : textoONulo(cuerpo, "usuarioBaja"))
                        .param("grupo", grupo)
                        .param("cuenta", cuenta)
                        .update();
        exigirQueEscribiera(
                escritas,
                "El evento de `miembro` nombra el grupo «"
                        + grupo
                        + "» y la cuenta «"
                        + cuenta
                        + "», y esta copia no conoce a los dos todavia");
        return Aplicacion.APLICADO;
    }

    private Aplicacion permiso(JsonNode cuerpo) {
        String sistema = exigir(cuerpo, "sistema");
        if (!ESTE_SISTEMA.equals(sistema)) {
            return Aplicacion.IGNORADO_AJENO;
        }
        String codigo = exigir(cuerpo, "codigo");
        String sujetoNombre = exigir(cuerpo, "sujetoNombre");
        boolean deGrupo = "GRUPO".equals(exigir(cuerpo, "sujeto"));
        JsonNode privilegios = cuerpo.path("privilegios");
        StringBuilder columnas = new StringBuilder();
        StringBuilder valores = new StringBuilder();
        StringBuilder actualizacion = new StringBuilder();
        for (Privilegio privilegio : Privilegio.values()) {
            String columna = privilegio.columna();
            columnas.append(", ").append(columna);
            valores.append(", :").append(columna);
            actualizacion.append(", ").append(columna).append(" = EXCLUDED.").append(columna);
        }
        // `acceso` de este esquema no lleva `sistema`: aqui todo acceso es de `rentas`, y por eso
        // el `sistema` del cuerpo se comprueba ARRIBA y no en el WHERE.
        String sql =
                "INSERT INTO permiso (municipalidad_id, acceso_id, grupo_id, usuario_id,"
                        + " usuario_registro"
                        + columnas
                        + ") SELECT "
                        + MUNICIPALIDAD_ACTUAL
                        + ", a.id, "
                        + (deGrupo ? "s.id, NULL" : "NULL, s.id")
                        + ", :quien"
                        + valores
                        + " FROM acceso a, "
                        + (deGrupo ? "grupo" : "usuario")
                        + " s WHERE a.codigo = :codigo AND s."
                        + (deGrupo ? "nombre" : "cuenta")
                        + " = :sujeto"
                        + " ON CONFLICT (municipalidad_id, acceso_id, "
                        + (deGrupo ? "grupo_id) WHERE grupo_id" : "usuario_id) WHERE usuario_id")
                        + " IS NOT NULL DO UPDATE SET usuario_registro = EXCLUDED.usuario_registro"
                        + actualizacion;
        JdbcClient.StatementSpec sentencia =
                jdbc().sql(sql)
                        .param("quien", exigir(cuerpo, "usuarioRegistro"))
                        .param("codigo", codigo)
                        .param("sujeto", sujetoNombre);
        for (Privilegio privilegio : Privilegio.values()) {
            sentencia =
                    sentencia.param(
                            privilegio.columna(),
                            privilegios.path(privilegio.columna()).asBoolean(false));
        }
        exigirQueEscribiera(
                sentencia.update(),
                "El evento de `permiso` nombra el acceso «"
                        + codigo
                        + "» de `rentas` y el "
                        + (deGrupo ? "grupo" : "usuario")
                        + " «"
                        + sujetoNombre
                        + "», y esta copia no conoce a los dos todavia");
        return Aplicacion.APLICADO;
    }

    // ------------------------------------------------------------------

    private JsonNode leer(EventoDeIdentidadRecibido evento) {
        try {
            JsonNode cuerpo = json.readTree(evento.cuerpo());
            if (!cuerpo.isObject()) {
                throw new NoSePuedeAplicar(
                        "El cuerpo del evento " + evento.eventoId() + " no es un objeto JSON");
            }
            return cuerpo;
        } catch (JacksonException ilegible) {
            throw new NoSePuedeAplicar(
                    "El cuerpo del evento "
                            + evento.eventoId()
                            + " no es JSON: "
                            + ilegible.getOriginalMessage(),
                    ilegible);
        }
    }

    /**
     * Un {@code INSERT ... SELECT} que no encuentra a quien nombra <b>no falla: escribe cero
     * filas</b>. Descartarlo en silencio dejaria la copia desatrasada sin que nada lo diga (es
     * `rentas`#54 y el defecto que `identidad` encontro en su propio instrumento de referencia).
     */
    private static void exigirQueEscribiera(int escritas, String queFalta) {
        if (escritas == 0) {
            throw new TodaviaNo(
                    queFalta
                            + ": la sentencia escribio 0 filas. Un evento que llega antes que aquel"
                            + " del que depende no se puede aplicar, y descartarlo en silencio"
                            + " dejaria la copia desatrasada sin que nada lo diga; se deja"
                            + " pendiente y se vuelve a intentar");
        }
    }

    private static String exigir(JsonNode cuerpo, String campo) {
        JsonNode valor = cuerpo.path(campo);
        if (valor.isMissingNode() || valor.isNull() || valor.asString("").isBlank()) {
            throw new NoSePuedeAplicar(
                    "El cuerpo del evento no trae «"
                            + campo
                            + "», que es con lo que esta copia lo casa. Sin el no hay forma de"
                            + " saber de quien habla");
        }
        return valor.asString();
    }

    private static @Nullable String textoONulo(JsonNode cuerpo, String campo) {
        JsonNode valor = cuerpo.path(campo);
        return valor.isMissingNode() || valor.isNull() ? null : valor.asString();
    }

    private static @Nullable LocalDate fechaONula(JsonNode cuerpo, String campo) {
        String texto = textoONulo(cuerpo, campo);
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(texto);
        } catch (DateTimeParseException noEsFecha) {
            throw new NoSePuedeAplicar(
                    "El cuerpo trae «" + campo + "» = «" + texto + "», que no es una fecha",
                    noEsFecha);
        }
    }

    private static String recortar(String texto, int largo) {
        return texto.length() <= largo ? texto : texto.substring(0, largo);
    }

    /** No se podra aplicar NUNCA: se aparta, se acusa y se avisa. */
    public static final class NoSePuedeAplicar extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NoSePuedeAplicar(String mensaje) {
            super(mensaje);
        }

        public NoSePuedeAplicar(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** No se puede aplicar AHORA: no se acusa, y el buzon lo vuelve a servir. */
    public static final class TodaviaNo extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public TodaviaNo(String mensaje) {
            super(mensaje);
        }
    }
}

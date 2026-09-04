package kamayuk.rentas.parametros.aplicacion;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import kamayuk.rentas.carga.InformeDeImportacion;
import kamayuk.rentas.carga.InformeDeImportacion.FilaRechazada;
import kamayuk.rentas.carga.LectorDeFilasCsv;
import kamayuk.rentas.carga.LectorDeFilasCsv.FilaCsv;
import kamayuk.rentas.parametros.dominio.LlaveDeParametro;
import kamayuk.rentas.parametros.dominio.PublicacionDeParametros;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * Publica valores normativos en {@code parametro_tributario} desde el derivado del corpus (#188,
 * #247 §4).
 *
 * <p>Es el eslabon que le faltaba a la cadena de un ejercicio. {@code AbrirConjuntoDeParametros}
 * (#247 §2) sabe abrir, componer y sellar, pero componer nombra parametros <b>ya publicados</b> y
 * <b>nada los publicaba</b>: por eso {@code sellar} —que exige al menos una fila en {@code
 * conjunto_parametro_detalle}— no tenia forma de pasar en un ambiente real. La secuencia completa
 * queda: abrir → <b>publicar</b> → cargar el arancel → componer y sellar.
 *
 * <h2>De donde salen las cifras, y por que de ahi y de ningun otro sitio</h2>
 *
 * <p>De un CSV derivado de {@code docs/10-negocio/valores-normativos/}, y solo de archivos en
 * estado {@code VERIFICADO}. Este proceso <b>no lleva ninguna cifra dentro</b> (regla 5) y tampoco
 * la valida contra la norma: eso lo hace {@code docs/10-negocio/verificar-publicacion.mjs} en cada
 * PR, comparando fila a fila el CSV contra el archivo del corpus que nombra —la cifra, el texto, el
 * documento fuente, el articulo y las dos firmas—. Aqui la decision de diseno de #188 se convierte
 * en dos columnas: <b>la doble firma de ADR-0007 ya ocurrio en el corpus</b>, y lo que el proceso
 * hace es transportarla a {@code usuario_carga} y {@code usuario_aprueba}, donde {@code
 * parametro_doble_verificacion_ck} exige que sean distintas.
 *
 * <p>Desde #192 el CSV lleva una columna mas, {@code valor_maquina}, y lo unico que este proceso
 * hace con ella es preferirla: si la fila la trae, es <b>ella</b> la que va a {@code
 * parametro_tributario.valor_texto}, y si no, el texto verbatim de la norma. Existe porque hay
 * valores cuya forma en la norma no es la que el codigo consume —«cuatro (4) anios» frente a «4
 * ANIOS», que es lo unico que {@code Plazo.de} acepta—, y las dos formas no caben en una columna
 * sin que una de las dos deje de ser comprobable. Cual de ellas se exige, y a que filas, lo decide
 * {@code verificar-publicacion.mjs}; aqui no hay ninguna regla sobre tipos concretos de parametro.
 *
 * <h2>La credencial</h2>
 *
 * <p>Corre como {@code rol_carga_parametros} —no como {@code sgtm_app}, que solo tiene {@code
 * SELECT} sobre esta tabla, y no como {@code sgtm_owner}, que tampoco puede escribirla: {@code
 * parametro_tributario} lleva {@code FORCE ROW LEVEL SECURITY} y la unica politica de escritura de
 * V6 nombra a {@code rol_carga_parametros}—. Ese rol <b>solo</b> alcanza esta tabla: ni el
 * conjunto, ni su detalle, ni la auditoria (V7). Por eso este proceso publica y no compone:
 * componer es el otro acto, del otro rol.
 *
 * <h2>Una transaccion por fila, sin escribir ninguna</h2>
 *
 * <p>Cada fila es un solo {@code INSERT} y no hay ningun {@code @Transactional} en este camino: en
 * autocommit, cada sentencia <b>ya es</b> su propia transaccion. Envolver el bucle es exactamente
 * el defecto de #328 —la fila que revienta se lleva por delante a la valida que la seguia— y
 * envolver cada fila seria escribir una transaccion para no usarla. Lo que si hay es un informe:
 * publicadas, rechazadas, y por que cada una.
 *
 * <p><b>Volver a correr el mismo archivo no duplica.</b> {@code parametro_tributario} no tiene
 * ninguna restriccion de unicidad sobre {@code (tipo, clave, vigencia_desde)} —V1 no la puso, y
 * ponerla hoy retiraria la guarda de homonimos que {@code
 * AdministrarParametros.agregarParametroPublicado} tiene probada—, asi que la comprueba este
 * proceso antes de insertar. Si un dia la base la tiene, el {@code DuplicateKeyException} cae en el
 * mismo sitio y se informa igual.
 *
 * <p>Perfil {@code batch} por lo mismo que los demas procesos de arranque (#202): sin el, el
 * contenedor que atiende peticiones tendria dentro el camino mas corto entre una peticion HTTP y la
 * publicacion de una cifra normativa.
 */
@Component
@Profile("batch")
@ConditionalOnProperty("sgtm.publicacion-parametros.archivo")
@EnableConfigurationProperties(DatosDeLaPublicacion.class)
public class PublicarParametros implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PublicarParametros.class);

    private final PublicacionDeParametros publicacion;
    private final DatosDeLaPublicacion datos;

    public PublicarParametros(PublicacionDeParametros publicacion, DatosDeLaPublicacion datos) {
        this.publicacion = publicacion;
        this.datos = datos;
    }

    @Override
    public void run(ApplicationArguments argumentos) throws IOException {
        // Sin TenantContext, y a proposito: lo que se publica es de ambito nacional y va con
        // municipalidad_id nulo. TenantTransactionManager admite transacciones sin contexto
        // justamente para esto —leer y cargar el catalogo nacional—, y quien decide si el acceso es
        // legitimo es la politica RLS de la tabla, que para este rol dice que si.
        try (Reader lectura =
                Files.newBufferedReader(Path.of(datos.archivo()), StandardCharsets.UTF_8)) {
            InformeDeImportacion informe = publicar(lectura);

            for (FilaRechazada rechazada : informe.rechazadas()) {
                log.warn("Fila {} no publicada: {}", rechazada.fila(), rechazada.motivo());
            }
            log.info(
                    "Publicacion desde {} por {}: {} fila(s) leidas, {} publicada(s), {}"
                            + " rechazada(s)",
                    datos.archivo(),
                    datos.usuarioDelProceso(),
                    informe.totalFilas(),
                    informe.nuevas(),
                    informe.rechazadas().size());

            // La linea que espera quien corre esto, en una forma que se puede extraer del registro
            // sin leerlo entero: si no es cero, el conjunto no se puede componer entero todavia.
            log.info("PUBLICADAS={} RECHAZADAS={}", informe.nuevas(), informe.rechazadas().size());
        }
    }

    InformeDeImportacion publicar(Reader archivo) throws IOException {
        List<FilaCsv> filas = LectorDeFilasCsv.leer(archivo);
        List<FilaRechazada> rechazadas = new ArrayList<>();
        int nuevas = 0;

        for (FilaCsv fila : filas) {
            FilaPublicable publicable;
            try {
                publicable = FilaPublicable.de(fila.campos());
            } catch (IllegalArgumentException e) {
                rechazadas.add(FilaRechazada.de(fila.numeroDeLinea(), e));
                continue;
            }
            LlaveDeParametro llave = publicable.llave();
            try {
                if (!publicacion.publicados(llave).isEmpty()) {
                    rechazadas.add(
                            new FilaRechazada(
                                    fila.numeroDeLinea(),
                                    "El parametro "
                                            + llave
                                            + " ya estaba"
                                            + " publicado; volver a publicarlo dejaria dos filas"
                                            + " homonimas y el conjunto sin poder decir cual sello"));
                    continue;
                }
                long id =
                        publicacion.publicar(
                                publicable.parametro(),
                                publicable.transcribio(),
                                publicable.verifico());
                nuevas++;
                log.info(
                        "Publicado {} como parametro {}, firmado por {} y verificado por {}",
                        llave,
                        id,
                        publicable.transcribio(),
                        publicable.verifico());
            } catch (DuplicateKeyException e) {
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "El parametro " + llave + " ya estaba publicado"));
            } catch (DataAccessResourceFailureException e) {
                // NO es una fila rechazada: es que no hay con que hablarle a la base. Se corta
                // aqui, en la primera, en vez de contarlo N veces (issue #435).
                //
                // Pasó de verdad contra `stg` el 2026-08-29. `rol_carga_parametros` seguia
                // NOLOGIN —`20-asignar-claves.sh` solo corre al inicializar el motor, y ese
                // cluster se habia creado antes del issue #387—, y como
                // `CannotGetJdbcConnectionException` es una `DataAccessException`, el `catch` de
                // abajo la trataba como un rechazo de fila: la corrida termino con
                // `PUBLICADAS=0 RECHAZADAS=22`, un aviso por fila que culpaba a las firmas, y
                // codigo de salida 0. La causa real —«role "rol_carga_parametros" is not
                // permitted to log in»— no aparecia en ninguna linea. Veintidos reintentos de
                // pool, veintidos diagnosticos equivocados.
                throw new IllegalStateException(
                        "No se pudo abrir una conexion para publicar "
                                + llave
                                + ". Esto NO es una fila rechazada: sin conexion no se publica"
                                + " ninguna. Comprobar que la credencial de rol_carga_parametros"
                                + " sirve de verdad contra este ambiente —el secreto puede existir"
                                + " y el rol seguir sin LOGIN— con"
                                + " infra/secretos/asignar-claves.sh --ambiente <amb> --comprobar",
                        e);
            } catch (DataAccessException e) {
                // Sin repetir el mensaje crudo de la base (ARQ-04 §5). La causa mas probable es
                // parametro_doble_verificacion_ck, o que la credencial no sea la que puede escribir
                // esta tabla.
                rechazadas.add(
                        new FilaRechazada(
                                fila.numeroDeLinea(),
                                "La base rechazo el parametro "
                                        + llave
                                        + ": revise que las dos firmas sean distintas y que este"
                                        + " proceso corra como rol_carga_parametros"));
            }
        }

        return new InformeDeImportacion(filas.size(), nuevas, rechazadas);
    }
}

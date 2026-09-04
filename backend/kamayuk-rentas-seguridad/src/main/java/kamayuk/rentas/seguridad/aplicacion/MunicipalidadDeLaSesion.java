package kamayuk.rentas.seguridad.aplicacion;

import kamayuk.rentas.seguridad.dominio.Municipalidad;
import kamayuk.rentas.seguridad.dominio.MunicipalidadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A quien pertenecen las cifras de la pantalla: el rotulo de la entidad (#555).
 *
 * <p>Lectura pura y sin ningun argumento. La {@code @Transactional(readOnly = true)} no es
 * cosmetica y aqui menos que en ninguna otra parte: la consulta compara {@code
 * current_setting('app.municipalidad_id')}, que solo existe dentro de la transaccion que lo fijo
 * con {@code SET LOCAL}. Sin ella no hay una respuesta equivocada, hay un {@code 500} —el defecto
 * de clase de #486—.
 *
 * <p>Que no reciba nada es lo que hace que esta operacion no pueda convertirse en un directorio de
 * municipalidades: no hay donde poner el identificador de otra.
 */
@Service
public class MunicipalidadDeLaSesion {

    private final MunicipalidadRepository municipalidades;

    public MunicipalidadDeLaSesion(MunicipalidadRepository municipalidades) {
        this.municipalidades = municipalidades;
    }

    /**
     * La municipalidad de la sesion en curso.
     *
     * @throws IllegalStateException si el token trae una municipalidad que no esta en el registro.
     *     Es una instalacion rota y no una respuesta de negocio, asi que sale ruidosa: la
     *     alternativa comoda —devolver un nombre vacio— acabaria impresa en la cabecera de un
     *     documento, que es exactamente lo que este issue existe para impedir
     */
    @Transactional(readOnly = true)
    public Municipalidad actual() {
        return municipalidades
                .deLaSesion()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "La municipalidad de la sesion no esta en el registro de"
                                                + " municipalidades. Sin su nombre no se puede decir de"
                                                + " quien son las cifras de la pantalla ni encabezar"
                                                + " ningun documento."));
    }
}

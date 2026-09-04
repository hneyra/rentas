package kamayuk.rentas.seguridad.dominio;

import java.util.Optional;

/**
 * Lo unico que se puede preguntar del registro de tenants desde una peticion: <b>cual es la
 * mia</b>.
 *
 * <h2>Un metodo, y sin argumento</h2>
 *
 * <p>No hay {@code porId}, ni {@code porUbigeo}, ni {@code todas}. No es economia: un puerto con
 * cualquiera de los tres seria un <b>directorio de municipalidades</b> —quien pregunta elige de
 * quien pregunta— y la operacion que lo publicara dejaria de respetar el aislamiento por el simple
 * expediente de cambiar un numero. Lo que no se necesita no se pone, igual que en {@link
 * RegistroDeMunicipalidades}, que es su gemelo del lado que escribe.
 *
 * <p>Que el metodo no reciba nada es lo que obliga a que el identificador salga del token: entra
 * una sola vez en el borde, viaja en {@code TenantContext} y llega a la base con el {@code SET
 * LOCAL} que emite el gestor de transacciones (regla 2, ARQ-03 §3.1).
 *
 * <h2>Y el {@code Optional} significa una cosa sola</h2>
 *
 * <p>Vacio es «el token trae una municipalidad que no esta en el registro», que es una instalacion
 * rota y no una respuesta de negocio. No es «no tiene»: toda sesion pertenece a una.
 */
public interface MunicipalidadRepository {

    /**
     * La municipalidad de la sesion en curso.
     *
     * <p><b>Se lee dentro de una transaccion o no se lee.</b> {@code municipalidad} no es una tabla
     * de tenant —su politica es {@code FOR SELECT USING (true)}, porque los procesos masivos la
     * recorren entera (V6)—, asi que aqui el aislamiento no lo pone RLS: lo pone el {@code WHERE},
     * y lo que ese {@code WHERE} compara es {@code current_setting('app.municipalidad_id')}. Fuera
     * de una transaccion ese parametro no existe y la consulta <b>revienta</b> en vez de responder
     * por la municipalidad equivocada, que es el defecto de clase de #486.
     */
    Optional<Municipalidad> deLaSesion();
}

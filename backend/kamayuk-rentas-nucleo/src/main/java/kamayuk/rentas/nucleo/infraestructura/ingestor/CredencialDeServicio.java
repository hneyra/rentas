package kamayuk.rentas.nucleo.infraestructura.ingestor;

/**
 * De donde sale el {@code Authorization} con el que el ingestor llama a {@code catastro} (#21
 * AC-2).
 *
 * <p>Existe para que {@link ClienteHttpDelBuzonDeCatastro} no sepa <b>como</b> se consigue. Hasta
 * #21 lo que mandaba era una cadena configurada —lo que {@code bootstrap-secretos.sh} genera: un
 * valor aleatorio que ningun emisor firmo— y {@code catastro} la rechazaba con 401. Ahora lo que se
 * configura es la <b>clave con la que se pide el token</b>, y quien lo pide es {@link
 * TokenDeServicioDeKeycloak}.
 *
 * <p><b>Devuelve la cabecera entera, con su esquema.</b> Devolver solo el token dejaria el {@code
 * "Bearer "} escrito en el cliente HTTP.
 *
 * <p>Puede lanzar {@link
 * kamayuk.rentas.nucleo.dominio.proyeccion.FuenteDeHechosDeCatastro.CatastroNoContesta}: no poder
 * pedir el token es un fallo de despliegue, y en este camino <b>todo fallo es transitorio</b> a
 * proposito —lo que no puede pasar es que un fallo de transporte mate un hecho—.
 */
@FunctionalInterface
public interface CredencialDeServicio {

    /** La cabecera {@code Authorization}, o cadena vacia si este despliegue no tiene ninguna. */
    String cabecera();

    /** Una credencial fija, para las pruebas y para el compose sin identidad. */
    static CredencialDeServicio fija(String cabecera) {
        return () -> cabecera;
    }
}

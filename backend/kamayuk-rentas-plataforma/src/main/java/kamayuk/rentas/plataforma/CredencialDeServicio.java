package kamayuk.rentas.plataforma;

/**
 * De donde sale el {@code Authorization} con el que un proceso de este sistema llama a otro sistema
 * con su cuenta de servicio (#21 AC-2; etapa 4 de ADR-0039).
 *
 * <p>Existe para que el cliente HTTP de cada buzon —el de {@code catastro} en {@code nucleo}, el de
 * {@code identidad} en {@code seguridad}— no sepa <b>como</b> se consigue. Hasta #21 lo que mandaba
 * el ingestor era una cadena configurada —lo que {@code bootstrap-secretos.sh} genera: un valor
 * aleatorio que ningun emisor firmo— y {@code catastro} la rechazaba con 401. Ahora lo que se
 * configura es la <b>clave con la que se pide el token</b>, y quien lo pide es {@link
 * TokenDeServicioDeKeycloak}.
 *
 * <p><b>Vive en {@code plataforma} y no en el ingestor de {@code catastro}</b>, desde la etapa 4 de
 * ADR-0039: lo consumen dos contextos —{@code nucleo} para el buzon de {@code catastro} y {@code
 * seguridad} para el de {@code identidad}— y Spring Modulith no expone los sub-paquetes de un
 * modulo, asi que dejarlo en {@code nucleo.infraestructura.ingestor} obligaba a copiarlo. Es
 * infraestructura tecnica sin una regla de negocio dentro, que es lo que este modulo aloja.
 *
 * <p><b>Devuelve la cabecera entera, con su esquema.</b> Devolver solo el token dejaria el {@code
 * "Bearer "} escrito en el cliente HTTP.
 *
 * <p>Puede lanzar {@link NoSePudoObtener}: no poder pedir el token es un fallo de despliegue, y en
 * los dos caminos que la usan <b>todo fallo es transitorio</b> a proposito —lo que no puede pasar
 * es que un fallo de transporte mate un hecho—. Cada cliente la traduce a la excepcion transitoria
 * de SU buzon, para que quien atiende lea el aviso de esa vuelta y no uno generico.
 */
@FunctionalInterface
public interface CredencialDeServicio {

    /** La cabecera {@code Authorization}, o cadena vacia si este despliegue no tiene ninguna. */
    String cabecera();

    /** Una credencial fija, para las pruebas y para el compose sin identidad. */
    static CredencialDeServicio fija(String cabecera) {
        return () -> cabecera;
    }

    /**
     * No se pudo conseguir la cabecera: el emisor caido, la clave equivocada, el cliente sin crear.
     *
     * <p>Es transitoria por definicion —se arregla en el despliegue y cambia sola—, y es de {@code
     * plataforma} y no de ningun buzon: quien la atrapa es el cliente HTTP de cada uno, que la
     * vuelve a lanzar como la excepcion transitoria de su camino.
     */
    class NoSePudoObtener extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public NoSePudoObtener(String mensaje) {
            super(mensaje);
        }

        public NoSePudoObtener(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }
}

package kamayuk.rentas.seguridad.dominio;

/**
 * La municipalidad a la que pertenece la sesion, tal como esta en el registro de tenants.
 *
 * <h2>Por que existe, y que defecto cierra</h2>
 *
 * <p>Ninguna operacion del contrato publicaba el nombre de la municipalidad en curso (#555), asi
 * que la interfaz lo llevaba <b>compilado</b>: dibujaba «Municipalidad Distrital de Catacaos» en la
 * cabecera de los doce modulos y en cinco hojas imprimibles, con el token de otra municipalidad y
 * sus datos debajo. Un rotulo de entidad equivocado no es una cifra mal dibujada: es lo que dice de
 * quien son todas las demas, y sale impreso.
 *
 * <h2>{@code nombre} es el nombre entero, y no se compone</h2>
 *
 * <p>La columna {@code municipalidad.nombre} guarda el nombre <b>como sale en los documentos</b>,
 * con su tipo delante —«Municipalidad Distrital de Catacaos»—; es lo que declara {@code
 * kamayuk.implantacion.nombre} y lo que escribe la implantacion. {@link #tipo()} es el mismo dato
 * clasificado —{@code DISTRITAL} o {@code PROVINCIAL}, lo que admite el {@code CHECK} de {@code
 * V1}—, y esta para quien necesite distinguir las dos, <b>no</b> para anteponerlo: componer
 * «Municipalidad » + tipo + « de » + nombre produce «Municipalidad Distrital de Municipalidad
 * Distrital de Catacaos», y quien lo compone no lo ve hasta que se imprime.
 *
 * <p>No lleva {@code MunicipalidadId} sino un {@code long}: el tipo del dominio no aparece en
 * ninguna firma (regla 2, ARQ-03 §3.1), y un componente de {@code record} es un parametro del
 * constructor.
 *
 * @param id el identificador de la municipalidad, el mismo que trae el claim del token
 * @param ubigeo los seis digitos del distrito. Lo pide un segundo consumidor: el alta de predio de
 *     Catastro prefijaba el distrito con un ubigeo compilado, y el del padron real es otro
 * @param nombre el nombre completo, verbatim de la columna
 * @param tipo {@code DISTRITAL} o {@code PROVINCIAL}
 */
public record Municipalidad(long id, String ubigeo, String nombre, String tipo) {

    public Municipalidad {
        if (id <= 0) {
            throw new IllegalArgumentException(
                    "El identificador de municipalidad debe ser positivo");
        }
        ubigeo = exigir(ubigeo, "ubigeo");
        nombre = exigir(nombre, "nombre");
        tipo = exigir(tipo, "tipo");
    }

    private static String exigir(String valor, String campo) {
        if (valor == null || valor.isBlank()) {
            throw new IllegalArgumentException(
                    "La municipalidad no puede quedarse sin " + campo + ": es lo que sale impreso");
        }
        return valor.strip();
    }
}

package kamayuk.rentas.contribuyentes;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Lo que este contexto publica a los demas para responder por un contribuyente <b>identificado por
 * quien atiende</b>: su codigo, su nombre, sus domicilios.
 *
 * <p>Existe porque {@code catastro} tiene que responder dos preguntas que no puede responder solo:
 * «que predios tiene este contribuyente, escrito su nombre como se escriba» y «que pongo en la
 * cabecera del reporte de ficha». La titularidad guarda un {@code contribuyente_id} y nada mas; el
 * nombre, el documento y el domicilio viven aqui.
 *
 * <p><b>Esta en el paquete raiz a proposito.</b> Spring Modulith trata {@code
 * contribuyentes.dominio} y {@code contribuyentes.aplicacion} como internos, asi que un {@code
 * import} desde otro contexto no compila la verificacion. Publicar exactamente esta interfaz —y no
 * el repositorio— es lo que impide que manana alguien llame a {@code guardar} desde fuera: es el
 * mismo patron que {@code parametros.LectorDeParametros} (ARQ-01 §4, regla 1).
 *
 * <p>Devuelve <b>resumenes</b>, no entidades. Quien consulta desde otro contexto necesita mostrar
 * un nombre y un codigo, no editar un contribuyente; entregarle el agregado entero seria invitar a
 * que lo modificara.
 *
 * <p><b>No es lo unico que este contexto publica</b>, y la otra puerta esta separada a proposito:
 * {@link AcreditacionEnElPadron} responde por un <b>documento acreditado</b> y su interlocutor no
 * es un funcionario sino el ciudadano (ADR-0020). Son dos preguntas distintas —«¿quien es el
 * titular de este predio?» y «¿figura esta persona aqui?»—, con dos respuestas distintas y dos
 * poblaciones distintas preguntando; juntarlas en esta interfaz habria obligado a que todo
 * consumidor de ventanilla tuviera algo que contestar sobre el portal.
 */
public interface DirectorioDeContribuyentes {

    /**
     * Los contribuyentes cuyo nombre o codigo se parece a lo escrito.
     *
     * <p>Por aproximacion, como {@code RF-011}: el nombre llega mal escrito desde ventanilla mas a
     * menudo que bien, y una consulta por igualdad exacta no encuentra a nadie.
     *
     * @param maximo cuantos como mucho; un filtro de una grilla no necesita el padron entero
     */
    List<ResumenDeContribuyente> buscar(String texto, int maximo);

    Optional<ResumenDeContribuyente> porCodigo(String codigo);

    /**
     * Varios de golpe, indexados por identificador.
     *
     * <p>Existe para que una grilla de fichas resuelva sus titulares en <b>una</b> consulta. Con
     * {@code porId} en un bucle, una pagina de veinte fichas serian veintiuna consultas, y eso no
     * se nota en la prueba y si en el padron de una provincia.
     *
     * <p>Los identificadores que no existen simplemente no aparecen en el mapa. Un predio cuyo
     * titular se dio de baja sigue apareciendo en la grilla, sin nombre; ocultarlo escondería
     * justamente el caso que hay que revisar.
     */
    Map<Long, ResumenDeContribuyente> porIds(Set<Long> ids);

    /**
     * El domicilio fiscal <b>vigente a esa fecha</b>, no el ultimo.
     *
     * <p>Quien mudo en setiembre no cambia la direccion a la que se notifico en marzo (regla 9), y
     * el reporte de una ficha de marzo tiene que poder imprimirse como se imprimio.
     */
    Optional<String> domicilioFiscalDe(long contribuyenteId, LocalDate fecha);
}

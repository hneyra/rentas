package kamayuk.rentas.nucleo.dominio.predial;

import java.util.Objects;
import java.util.Optional;
import kamayuk.rentas.dominio.Dinero;
import kamayuk.rentas.dominio.Porcentaje;
import org.jspecify.annotations.Nullable;

/**
 * El aporte de un predio a la base de una {@link Determinacion} predial (#30, tabla {@code
 * determinacion_predio_detalle} de V20).
 *
 * <p>Sin esto, un contribuyente con tres predios no se puede explicar de donde sale su base: {@link
 * Determinacion#baseImponible} es la suma de estos detalles (RT-011, {@code
 * RT011BaseImponibleDelContribuyente}), y cada fila es la que responde «¿cuanto puso este predio?»
 * ante una impugnacion.
 *
 * <p>No lleva el identificador de la determinacion a la que pertenece: lo asigna el repositorio al
 * insertar la cabecera y el detalle en la misma transaccion ({@code
 * DeterminacionRepository#insertar}), igual que una fila nueva no trae la clave que todavia no
 * existe.
 *
 * @param id nulo mientras no se ha guardado; lo asigna la base
 * @param predioId el predio que aporta
 * @param autovaluo el autovaluo del predio (RT-010: terreno + construccion + obras)
 * @param valuoExonerado la parte del autovaluo que no esta afecta (V56); cero si no hay ninguna
 * @param porcentajePropiedad el % de propiedad del contribuyente sobre este predio al 1 de enero
 *     del ejercicio, antes de las transferencias de ese dia ({@code
 *     Ejercicio.fechaDeLaTitularidad()}, #328)
 * @param baseImponiblePredio el aporte de este predio a la base del contribuyente, ya ponderado
 * @param origen de donde salio el autovaluo: declarado, o sellado por {@code catastro} (#38)
 * @param valuacionConjuntoId el conjunto de parametros que fijo la corrida de valuacion; {@code
 *     null} cuando el autovaluo es declarado
 * @param valuacionHuella la huella con que {@code catastro} sello esa valuacion; {@code null}
 *     cuando el autovaluo es declarado
 * @param autovaluoDeclarado el autovaluo que declaro el contribuyente cuando <b>mando la
 *     sellada</b> (#362, V33). Solo puede tener valor en {@link OrigenDelAutovaluo#SELLADO}: en
 *     {@link OrigenDelAutovaluo#DECLARADO} la declarada <b>es</b> {@code autovaluo}, y guardarla
 *     dos veces invitaria a que difieran. {@code null} cuando no hay dos cifras que comparar. Para
 *     preguntar «que declaro el contribuyente» no se lee este campo sino {@link
 *     #autovaluoDeclaradoSegunElOrigen()}
 */
public record DetalleDeterminacionPredio(
        @Nullable Long id,
        long predioId,
        Dinero autovaluo,
        Dinero valuoExonerado,
        Porcentaje porcentajePropiedad,
        Dinero baseImponiblePredio,
        OrigenDelAutovaluo origen,
        @Nullable Long valuacionConjuntoId,
        @Nullable String valuacionHuella,
        @Nullable Dinero autovaluoDeclarado) {

    public DetalleDeterminacionPredio {
        if (predioId <= 0) {
            throw new IllegalArgumentException(
                    "El detalle de determinacion tiene un predio: el identificador debe ser"
                            + " positivo");
        }
        Objects.requireNonNull(autovaluo, "El detalle necesita el autovaluo del predio");
        if (autovaluo.esNegativo()) {
            throw new IllegalArgumentException("El autovaluo no puede ser negativo");
        }
        Objects.requireNonNull(
                valuoExonerado, "El detalle necesita la parte exonerada, aunque sea cero");
        if (valuoExonerado.esNegativo()) {
            throw new IllegalArgumentException("El valuo exonerado no puede ser negativo");
        }
        if (valuoExonerado.esMayorQue(autovaluo)) {
            throw new IllegalArgumentException(
                    "La parte exonerada es una parte del autovaluo, no otra cifra: "
                            + valuoExonerado
                            + " sobre "
                            + autovaluo);
        }
        Objects.requireNonNull(
                porcentajePropiedad, "El detalle necesita el % de propiedad del contribuyente");
        Objects.requireNonNull(baseImponiblePredio, "El detalle necesita la base que aporta");
        Objects.requireNonNull(origen, "El detalle dice de donde salio su autovaluo (#38)");
        // La misma guarda que `determinacion_detalle_procedencia_ck` en la base. Un autovaluo que
        // dice venir de una valuacion sellada y no trae con cual no deja decir de cual vino, que
        // es exactamente el rastro que hace falta dentro de un ano.
        boolean traeProcedencia = valuacionConjuntoId != null && valuacionHuella != null;
        if ((origen == OrigenDelAutovaluo.SELLADO) != traeProcedencia) {
            throw new IllegalArgumentException(
                    "El autovaluo del predio "
                            + predioId
                            + " dice ser "
                            + origen
                            + " y "
                            + (traeProcedencia ? "trae" : "no trae")
                            + " el conjunto y la huella con que se sello (ADR-0027)");
        }
        if (baseImponiblePredio.esNegativo()) {
            throw new IllegalArgumentException(
                    "La base imponible del predio no puede ser negativa");
        }
        // La misma guarda que `determinacion_detalle_declarado_ck` (V33). Una declarada al lado de
        // un autovaluo que ya ES el declarado seria la misma cifra dos veces, y el dia que
        // difirieran nadie sabria cual de las dos firmo el contribuyente (#362).
        if (autovaluoDeclarado != null) {
            if (origen != OrigenDelAutovaluo.SELLADO) {
                throw new IllegalArgumentException(
                        "El predio "
                                + predioId
                                + " es DECLARADO y trae aparte un autovaluo declarado: en"
                                + " DECLARADO la declarada es el autovaluo, y solo se guarda al"
                                + " lado cuando manda la sellada (#362)");
            }
            if (autovaluoDeclarado.esNegativo()) {
                throw new IllegalArgumentException("El autovaluo declarado no puede ser negativo");
            }
        }
    }

    /**
     * Lo que declaro el contribuyente para este predio, <b>segun el origen</b> (#362): el propio
     * {@code autovaluo} si es {@link OrigenDelAutovaluo#DECLARADO}, y el que se guardo al lado si
     * es {@link OrigenDelAutovaluo#SELLADO}. Vacio si mando la sellada y nadie habia declarado —o
     * si la fila es anterior a V33, que no lo guardaba—.
     *
     * <p>Es la unica regla para esa pregunta, y la usan el recalculo individual y la corrida
     * masiva. Hasta #362 las dos leian {@code autovaluo} sin mirar el origen: tras una
     * determinacion SELLADO, el «declarado» del recalculo era la cifra sellada, y la discrepancia
     * con lo que el contribuyente firmo se borraba en la primera corrida.
     *
     * <p>Vacio no se rellena con {@code autovaluo}: eso seria volver a dar la sellada por
     * declarada. Si la sellada sigue estando, manda ella y no hace falta otra cifra; si ya no esta,
     * el predio se queda sin autovaluo y se dice, en vez de determinarse con una cifra que nadie
     * declaro.
     */
    public Optional<Dinero> autovaluoDeclaradoSegunElOrigen() {
        return origen == OrigenDelAutovaluo.DECLARADO
                ? Optional.of(autovaluo)
                : Optional.ofNullable(autovaluoDeclarado);
    }

    /** Un detalle nuevo, todavia sin guardar, de un predio sin ninguna parte exonerada. */
    public static DetalleDeterminacionPredio nuevo(
            long predioId,
            Dinero autovaluo,
            Porcentaje porcentajePropiedad,
            Dinero baseImponiblePredio) {
        return nuevo(predioId, autovaluo, Dinero.CERO, porcentajePropiedad, baseImponiblePredio);
    }

    /** Un detalle nuevo, todavia sin guardar, con la parte del autovaluo que no esta afecta. */
    public static DetalleDeterminacionPredio nuevo(
            long predioId,
            Dinero autovaluo,
            Dinero valuoExonerado,
            Porcentaje porcentajePropiedad,
            Dinero baseImponiblePredio) {
        return new DetalleDeterminacionPredio(
                null,
                predioId,
                autovaluo,
                valuoExonerado,
                porcentajePropiedad,
                baseImponiblePredio,
                OrigenDelAutovaluo.DECLARADO,
                null,
                null,
                null);
    }

    /**
     * Un detalle nuevo cuyo autovaluo salio de la valuacion que {@code catastro} sello.
     *
     * <p>Exige el conjunto y la huella en la firma, no los admite despues: si se pudieran anadir a
     * posteriori, existiria un instante en que un detalle SELLADO no dice de donde salio, y ese es
     * el instante en que alguien lo guarda.
     *
     * <p>La declarada va en la firma por lo mismo (#362): si llegara despues, el detalle que se
     * guarda podria salir sin ella, que es exactamente lo que pasaba.
     *
     * @param autovaluoDeclarado lo que declaro el contribuyente; {@code null} si no declaro
     */
    public static DetalleDeterminacionPredio sellado(
            long predioId,
            Dinero autovaluo,
            Dinero valuoExonerado,
            Porcentaje porcentajePropiedad,
            Dinero baseImponiblePredio,
            long valuacionConjuntoId,
            String valuacionHuella,
            @Nullable Dinero autovaluoDeclarado) {
        return new DetalleDeterminacionPredio(
                null,
                predioId,
                autovaluo,
                valuoExonerado,
                porcentajePropiedad,
                baseImponiblePredio,
                OrigenDelAutovaluo.SELLADO,
                valuacionConjuntoId,
                valuacionHuella,
                autovaluoDeclarado);
    }

    /** La parte del autovaluo que si esta afecta, antes de ponderar por el % de propiedad. */
    public Dinero valuoAfecto() {
        return autovaluo.menos(valuoExonerado);
    }
}

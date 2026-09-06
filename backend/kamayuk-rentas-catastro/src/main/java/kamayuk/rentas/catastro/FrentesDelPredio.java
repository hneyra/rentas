package kamayuk.rentas.catastro;

/**
 * A que vias da un predio y cuantos metros lineales tiene en cada una (`catastro`#7).
 *
 * <p>Es el <b>insumo</b> de los arbitrios de barrido de calles, y nada mas que el insumo: aqui
 * llegan los metros y la via; el arbitrio lo determina {@code rentas} con su tarifa y su ordenanza
 * (ADR-0024). <b>Ni un importe, ni un factor de barrido, ni el nombre de un servicio</b>: si uno de
 * los dos apareciera en este puerto, la frontera estaria movida.
 *
 * <p><b>Sin geometria.</b> {@code catastro} publica el tramo en WKT para poder dibujarlo, y este
 * lado no lo lee: {@code rentas} no tiene visor de plano, y un campo que se declara en el contrato
 * es un campo que el proveedor no puede retirar sin poner rojo su build. Se pide lo que se usa.
 */
public interface FrentesDelPredio {

    /**
     * Los frentes inscritos de un predio, con la constancia de cuando se derivaron.
     *
     * <p>Sin fecha: un frente no se resuelve a una fecha —lo que tiene fecha es su
     * <b>confirmacion</b>, y viaja dentro de cada frente—.
     *
     * @throws kamayuk.rentas.catastro.infraestructura.ClienteHttpDeCatastro.NoConstaEnCatastro si
     *     el predio no esta en el padron de esta municipalidad. «No tiene frentes» NO es eso: es
     *     una lista vacia con su motivo, y las dos se arreglan de maneras distintas
     */
    FrentesInscritos delPredio(long predioId);
}

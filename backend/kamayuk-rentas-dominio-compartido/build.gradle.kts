// Objetos de valor y contexto de tenant. No depende de ningun contexto acotado,
// ni de Spring: lo usan todos, incluida la capa `dominio`, que debe poder
// probarse sin levantar el contexto (ARQ-04 §1).

plugins {
    id("sgtm.pruebas")
}

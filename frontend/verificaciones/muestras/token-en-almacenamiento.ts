// Viola: el token vive en memoria, nunca en localStorage ni sessionStorage.
//
// En una municipalidad la PC de ventanilla se comparte entre turnos. Un token persistido
// sobrevive al cierre del navegador, asi que el turno de la tarde entra como el de la
// manana — y eso no es una comodidad, es un problema de control de acceso.

export function guardar(token: string) {
  localStorage.setItem('token', token);
}

export function leer() {
  return sessionStorage.getItem('token_de_acceso');
}

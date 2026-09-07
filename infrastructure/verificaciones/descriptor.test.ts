import { describe, expect, it } from "vitest";
import type { Contenedor, EntornoDelDescriptor, Manifiesto } from "@kamayuk/infra-contrato";
import { rentas } from "../src/descriptor";

/**
 * El descriptor de `rentas`, verificado sobre lo que devuelve.
 *
 * Esto es lo que corre en la maquina de quien lo escribe y en el CI de este repositorio: **sin
 * Pulumi, sin token y sin cluster**. La auditoria completa —las convenciones de `INF-01` §4 y las
 * cinco prohibiciones— la hace `infrastructure` al componer; aqui se comprueba lo que este
 * repositorio decide y solo el.
 */

const ENTORNO: EntornoDelDescriptor = {
  ambiente: "stg",
  namespace: "kamayuk-rentas-stg",
  dominio: "stg.kamayuk.example",
  etiquetas: { "app.kubernetes.io/part-of": "kamayuk", ambiente: "stg" },
  imagenDe: (c) => `ghcr.io/hneyra/kamayuk-${c}:0eee58e43e04b1c2d3f4a5b6c7d8e9f0a1b2c3d4`,
  secretoDe: (c) => `kamayuk-rentas-stg-${c}`,
  prioridadDe: (clase) => `kamayuk-stg-prioridad-${clase}`,
  // Del AMBIENTE, no de este sistema (C-7): quien recibe el aviso cuando algo
  // se rompe aqui. `checkInvariants` de `infrastructure` rechaza el relleno.
  operacion: { responsable: "Guardia de plataforma", canal: "guardia@example.pe" },
  // La municipalidad que el AMBIENTE implanta (C-14, punto 4). Los cuatro sistemas implantan la
  // misma, cada uno en su base.
  implantacion: {
    ubigeo: "200105",
    nombre: "Municipalidad Distrital de Catacaos",
    tipo: "DISTRITAL",
    administrador: "administrador",
    nombreDelAdministrador: "Administrador del sistema",
    esDemostracion: true,
    // El `id` de la fila que crea el Job de implantacion. En una base recien creada vale 1.
    municipalidadId: 1,
  },
  namespaceDe: (otro) => `kamayuk-${otro}-stg`,
  // El nombre de un `Job` lleva la version: un `Job` de Kubernetes es INMUTABLE.
  nombreConVersion: (base) => `${base}-0eee58e43e04`,
  plataforma: {
    namespace: "kamayuk-stg",
    // El anfitrion del motor, ya cruzando el namespace (C-17, punto 1). Los cuatro descriptores
    // escribian `postgres:5432` a mano, que es el nombre del `compose.yaml` local: en Kubernetes
    // no existe ningun `Service` que se llame asi.
    motor: "kamayuk-stg-postgres.kamayuk-stg:5432",
    emisor: "https://stg.kamayuk.example/keycloak/realms/sgtm",
    jwks: "http://kamayuk-stg-identidad.kamayuk-stg:8080/keycloak/realms/sgtm/protocol/openid-connect/certs",
  },
};

describe("el descriptor de rentas", () => {
  it("declara su base, y SOLO la suya", () => {
    const base = rentas.baseDeDatos(ENTORNO);
    expect(base.nombre).toBe("rentas");
    for (const rol of base.roles) {
      expect(rol.sobre).toEqual(["rentas"]);
      // Un superusuario OMITE RLS aunque haya FORCE (DAT-01 §0, hallazgo 1).
      expect(rol.superusuario).toBe(false);
    }
  });

  it("no fija la etiqueta de ninguna imagen: la pide", () => {
    // La prohibicion (b) de `infrastructure`, comprobada aqui tambien porque es la que sostiene
    // que una liberacion normal NO sea un `pulumi up` (ADR-0011 §5).
    const admisibles = rentas.imagenes.map((n) => ENTORNO.imagenDe(n));
    const imagenes = [...rentas.despliegue(ENTORNO), ...rentas.migracion(ENTORNO)]
      .flatMap((m) =>
        m.kind === "Deployment"
          ? m.spec.template.spec.containers
          : m.kind === "Job"
            ? m.spec.template.spec.containers
            : [],
      )
      .map((c) => c.image);
    expect(imagenes.length).toBeGreaterThan(0);
    for (const i of imagenes) expect(admisibles).toContain(i);
  });

  it("todas sus rutas van bajo su prefijo", () => {
    for (const m of rentas.ingreso(ENTORNO)) {
      if (m.kind !== "IngressRoute") continue;
      for (const r of m.spec.routes) {
        for (const encaje of r.match.matchAll(/PathPrefix\(`([^`]*)`\)/g)) {
          expect(encaje[1]).toMatch(/^\/rentas(\/|$)/);
        }
      }
    }
  });

  it("no emite ningun Secret, y su inventario no trae valores", () => {
    const todos = [
      ...rentas.despliegue(ENTORNO),
      ...rentas.migracion(ENTORNO),
      ...rentas.ingreso(ENTORNO),
    ];
    expect(todos.some((m) => (m as { kind: string }).kind === "Secret")).toBe(false);
    for (const c of rentas.claves(ENTORNO)) {
      for (const campo of ["valor", "value", "data", "stringData", "password"]) {
        expect((c as unknown as Record<string, unknown>)[campo]).toBeUndefined();
      }
    }
  });

  it("todo contenedor declara limites de recursos", () => {
    const contenedores = [...rentas.despliegue(ENTORNO), ...rentas.migracion(ENTORNO)].flatMap((m) =>
      m.kind === "Deployment"
        ? m.spec.template.spec.containers
        : m.kind === "Job"
          ? m.spec.template.spec.containers
          : [],
    );
    for (const c of contenedores) {
      expect(c.resources.requests.cpu).toBeTruthy();
      expect(c.resources.limits.memory).toBeTruthy();
    }
  });

  it("su egreso es catastro, normativa y caja: el grafo de ADR-0029", () => {
    expect(destinosDeEgreso()).toEqual(["caja", "catastro", "normativa"]);
  });

  /**
   * **Un solo `Deployment`, y el perfil `batch` no es uno** (C-17, punto 5).
   *
   * Hasta aqui eran dos, y el segundo —`kamayuk-rentas-batch`— arrancaba, no encontraba nada que
   * planificar, **salia con codigo 0** a los once segundos y Kubernetes lo volvia a crear:
   * `CrashLoopBackOff` permanente con la aplicacion sana. `ADR-0003` sigue siendo cierto —un
   * artefacto, dos perfiles—, y lo que cambia es la FORMA en que el segundo perfil se invoca: el
   * `Job` de implantacion y el `CronJob` del ingestor, que crean su pod cuando hay trabajo. El
   * propio codigo lo dice: `CorrerElIngestor` y `CorrerLaAntiEntropia` son `ApplicationRunner`,
   * «y el perfil `batch` TERMINA el proceso con `web-application-type: none`».
   *
   * Un `Deployment` solo admite `restartPolicy: Always`, asi que la forma miente en las dos
   * direcciones: afirma que algo corre siempre cuando no corre nada, y reporta como fallo una
   * salida con exito.
   */
  /**
   * Los DOS `Deployment`, y **ninguno de ellos en perfil `batch`**.
   *
   * Desde #44 son dos y no uno: el backend en perfil `web` y la interfaz, que es un nginx y no
   * tiene perfil de Spring ninguno. Lo que esta prueba vigila no ha cambiado —que no exista un
   * `kamayuk-rentas-batch`, que arranca, sale con codigo 0 a los once segundos y Kubernetes lo
   * vuelve a crear en `CrashLoopBackOff`— y se comprueba sobre el unico contenedor que declara
   * `SPRING_PROFILES_ACTIVE`: si alguien anadiera el `Deployment` de `batch`, esa lista tendria
   * dos entradas y esto saldria rojo igual que antes.
   */
  it("produce DOS Deployment —el backend y la interfaz— y ninguno en perfil `batch`", () => {
    const deployments = rentas.despliegue(ENTORNO).filter((m) => m.kind === "Deployment");
    expect(deployments.map((m) => m.metadata.name).sort()).toEqual([
      "kamayuk-rentas-interfaz",
      "kamayuk-rentas-web",
    ]);

    const perfiles = deployments
      .flatMap((m) => (m.kind === "Deployment" ? m.spec.template.spec.containers : []))
      .flatMap((c) => c.env ?? [])
      .filter((v) => v.name === "SPRING_PROFILES_ACTIVE")
      .map((v) => v.value);
    expect(perfiles, "un `Deployment` en perfil `batch` es un CrashLoopBackOff garantizado").toEqual([
      "web",
    ]);
  });

  /**
   * La interfaz no hereda NADA de la configuracion del backend (AC-6 de #44).
   *
   * Es la mitad que no se ve mirando lo que si declara: un nginx que sirve archivos no necesita
   * la URL de la base, ni el rol con que conectarse, ni la clave de `kamayuk_app`. Copiar el
   * bloque de variables del backend «por si acaso» pondria una credencial dentro de un pod que
   * no la usa, y ahi se quedaria hasta que alguien la leyera.
   */
  it("la interfaz no declara ni una variable de entorno ni un solo secreto", () => {
    const interfaz = rentas
      .despliegue(ENTORNO)
      .filter((m) => m.kind === "Deployment")
      .filter((m) => m.metadata.name === "kamayuk-rentas-interfaz")
      .flatMap((m) => (m.kind === "Deployment" ? m.spec.template.spec.containers : []));
    expect(interfaz).toHaveLength(1);
    expect(interfaz[0]?.env ?? []).toEqual([]);
    expect(JSON.stringify(interfaz[0])).not.toContain("secretKeyRef");
  });

  /** Y el perfil `batch` sigue existiendo donde le toca: en un Job y en un CronJob. */
  it("el perfil `batch` corre donde hay trabajo: la implantacion y el ingestor", () => {
    const enPerfilBatch = [...rentas.implantacion(ENTORNO), ...rentas.lotes(ENTORNO)];
    const perfiles = contenedoresDe(enPerfilBatch).map(
      (c) => (c.env ?? []).find((v) => v.name === "SPRING_PROFILES_ACTIVE")?.value,
    );
    expect(perfiles).toEqual(["batch", "batch"]);
  });
});

/** Los SISTEMAS a los que este descriptor declara egreso. El motor y la identidad no cuentan. */
function destinosDeEgreso(): string[] {
  const infra = ["postgres", "identidad"];
  return rentas
    .egreso(ENTORNO)
    .flatMap((p) => p.spec.egress ?? [])
    .flatMap((r) => r.to ?? [])
    .map((s) => s.podSelector?.matchLabels?.["componente"])
    .filter((c): c is string => c !== undefined && !infra.includes(c))
    .sort();
}

describe("C-14 — que esto se pueda desplegar", () => {
  /**
   * El Job de migracion corre la imagen del MIGRADOR, no la de la aplicacion.
   *
   * Hasta C-14 corria la misma que el `Deployment` con `KAMAYUK_DB_USUARIO=kamayuk_owner` y sin perfil:
   * arrancaba el proceso web con las credenciales del unico rol con DDL, y la aplicacion tiene
   * `spring.flyway.enabled: false` a proposito (ARQ-03 §4). O sea que ese Job **no migraba**.
   */
  it("el Job de migracion corre el migrador, con las variables que el migrador lee", () => {
    const contenedores = contenedoresDe(rentas.migracion(ENTORNO));
    expect(contenedores).toHaveLength(1);
    const c = contenedores[0]!;
    expect(c.image).toBe(ENTORNO.imagenDe(`${"rentas"}-migrador`));
    expect(valorDe(c, "KAMAYUK_DB_OWNER_USUARIO")).toBe("kamayuk_owner");
    expect(declara(c, "KAMAYUK_DB_OWNER_CLAVE")).toBe(true);
    // La de la APLICACION. El migrador no la lee, y ponerla es lo que hacia que este Job
    // pareciera correcto sin migrar nada.
    expect(declara(c, "KAMAYUK_DB_USUARIO")).toBe(false);
  });

  /**
   * TRES imagenes y DOS `Dockerfile` (#44).
   *
   * `rentas` y `rentas-migrador` son dos objetivos del mismo `backend/Dockerfile`, con el contexto
   * en la raiz del repositorio. `rentas-interfaz` sale de `frontend/Dockerfile`, con el contexto
   * en `frontend/` — y esas dos parejas son las que `publicar-imagenes.yml` tiene que declarar en
   * su matriz, porque un `.dockerignore` solo cuenta desde la raiz de SU contexto.
   *
   * **Y no se llama `rentas-web`**: ese nombre es el del `Deployment` y el `Service` del backend
   * en perfil `web`, que este mismo archivo produce.
   */
  it("y las tres imagenes son los objetivos de los dos Dockerfile", () => {
    expect(rentas.imagenes).toEqual(["rentas", "rentas-migrador", "rentas-interfaz"]);
    expect(rentas.imagenes, "«rentas-web» ya es el Deployment del backend").not.toContain(
      "rentas-web",
    );
  });

  /**
   * El Job de implantacion (C-7 §2.3): la fila de `municipalidad` en SU base.
   *
   * Con el migrador de contenedor de inicializacion: un `Deployment` no sabe esperar a un `Job`,
   * y la salida del monolito —un contenedor con `psql`— no vale aqui, porque un descriptor solo
   * puede nombrar SUS imagenes (prohibicion (b)).
   */
  it("implanta la municipalidad del ambiente, detras del esquema", () => {
    const jobs = rentas.implantacion(ENTORNO).filter((m) => m.kind === "Job");
    expect(jobs).toHaveLength(1);
    const job = jobs[0]!;
    expect(job.metadata.name).toContain("0eee58e43e04");
    const pod = job.spec.template.spec;
    expect((pod.initContainers ?? []).map((c) => c.image)).toEqual([
      ENTORNO.imagenDe(`${"rentas"}-migrador`),
    ]);
    const c = pod.containers[0]!;
    expect(c.image).toBe(ENTORNO.imagenDe("rentas"));
    expect(valorDe(c, "SPRING_PROFILES_ACTIVE")).toBe("batch");
    // EL PREFIJO DE `rentas` ES `KAMAYUK_IMPLANTACION_`, y esta linea ya dijo dos nombres.
    //
    // Hasta C-18 decia `KAMAYUK_IMPLANTACION_` mientras el Java leia `sgtm.implantacion`, o sea
    // que la comprobacion exigia el nombre roto —el mismo modo de fallo que C-17 §1—. C-18 lo
    // arreglo poniendo aqui `SGTM_IMPLANTACION_`, y R-A/B lo deshizo por el otro lado:
    // `DatosDeImplantacion` pasa a declarar `@ConfigurationProperties("kamayuk.implantacion")` y
    // sus dos `@Value` piden `${kamayuk.implantacion.*}`, como los de sus tres hermanos.
    //
    // El defecto es MUDO: `ImplantarMunicipalidad` esta condicionado a
    // `@ConditionalOnProperty("kamayuk.implantacion.ubigeo")`, asi que con el prefijo ajeno el runner
    // **no se registra**, el proceso arranca, no hace nada y sale con codigo 0 — el Job queda
    // `Complete`. Medido levantando el compose de C-18: 13 migraciones aplicadas y `municipalidad`
    // VACIA, o sea `rentas` sin ninguna municipalidad y sin nadie que pueda entrar.
    //
    // Aqui va el literal y no una lectura del Java, y conviene decir por que: este paquete no
    // tiene `@types/node`, asi que no puede leer un archivo sin estrenar una dependencia. Lo que
    // ata este literal a su Java es `infrastructure/infra/verificaciones/prefijo-de-la-implantacion.test.ts`,
    // que lee el `@ConfigurationProperties` de los cuatro y lo compara con lo que su descriptor
    // pone — el mismo reparto que `checkout-en-el-espacio-de-trabajo`: la comparacion vive donde
    // estan los dos clones.
    expect(valorDe(c, "KAMAYUK_IMPLANTACION_UBIGEO")).toBe("200105");
    expect(valorDe(c, "KAMAYUK_IMPLANTACION_ESDEMOSTRACION")).toBe("true");
  });

  /**
   * Un `podSelector` sin `namespaceSelector` selecciona pods **del mismo namespace**, y desde
   * ADR-0031 cada sistema tiene el suyo. Una regla escrita asi no abre nada: el sintoma es
   * trafico denegado con una politica que dice permitirlo.
   */
  it("toda regla de egreso nombra el namespace de su destino", () => {
    const destinos = rentas.egreso(ENTORNO)
      .flatMap((p) => p.spec.egress ?? [])
      .flatMap((r) => r.to ?? []);
    expect(destinos.length).toBeGreaterThan(0);
    for (const destino of destinos) {
      expect(destino.namespaceSelector, JSON.stringify(destino)).toBeDefined();
    }
  });
});

/** Los contenedores de una lista de manifiestos, los de inicializacion aparte. */
function contenedoresDe(manifiestos: readonly Manifiesto[]) {
  return manifiestos.flatMap((m) =>
    m.kind === "Deployment"
      ? m.spec.template.spec.containers
      : m.kind === "Job"
        ? m.spec.template.spec.containers
        : m.kind === "CronJob"
          ? m.spec.jobTemplate.spec.template.spec.containers
          : [],
  );
}

function valorDe(c: Contenedor, nombre: string): string | undefined {
  return (c.env ?? []).find((e) => e.name === nombre)?.value;
}

function declara(c: Contenedor, nombre: string): boolean {
  return (c.env ?? []).some((e) => e.name === nombre);
}

describe("C-14 §3 — el ingestor de catastro, declarado entero y CORRIENDO (#21)", () => {
  /**
   * C-8 lo construyo y lo midio, y su hueco 2 decia: «mientras el descriptor no tenga campo, el
   * ingestor no se puede desplegar». Ahora lo tiene.
   *
   * **Nacio SUSPENDIDO y esta prueba exigia que lo siguiera estando** —`toBe(true)`—, que es la
   * forma en que una guarda fosiliza el defecto que vigila: mientras nadie la tocara, arreglar la
   * identidad de servicio ponia el descriptor en ROJO. Es el mismo patron que C-17 §1, C-18 §5 y
   * R-AB encontraron tres veces.
   *
   * Desde #21 el `suspend` se va y lo que sostiene la seguridad es otra cosa, y mas fuerte: la
   * credencial declara `emisor: "keycloak"`, y la guarda `identidad-de-servicio` de
   * `infrastructure` **no deja pasar el build** si alguna municipalidad no declara su cliente de
   * servicio. Un CronJob activo contra un emisor que no emitio nada ya no se puede desplegar,
   * porque el build se para antes.
   */
  it("declara su configuracion entera, y CORRE", () => {
    const crones = rentas.lotes(ENTORNO).filter((m) => m.kind === "CronJob");
    expect(crones).toHaveLength(1);
    const cron = crones[0]!;
    // `undefined` es lo que Kubernetes lee como «no suspendido». Se afirma que NO es `true` y no
    // que sea `false`: declarar `suspend: false` seria ruido en el manifiesto.
    expect(cron.spec.suspend, "el ingestor volvio a nacer suspendido (#21 AC-4)").not.toBe(true);
    const c = cron.spec.jobTemplate.spec.template.spec.containers[0]!;
    // `@ConditionalOnProperty("kamayuk.rentas.ingestor.usuario")`: sin ella el cableado del
    // ingestor no existe y el proceso arranca sin ingestar nada.
    expect(valorDe(c, "KAMAYUK_RENTAS_INGESTOR_USUARIO")).toBe("rol_ingestor_catastro");
    expect(declara(c, "KAMAYUK_RENTAS_INGESTOR_CLAVE")).toBe(true);
    expect(valorDe(c, "KAMAYUK_RENTAS_INGESTOR_MUNICIPALIDAD")).toBe("1");
    // `ResponsableDeLaProyeccion` exige las dos: un hecho apartado bloquea la cola detras de el,
    // y avisar a nadie es no avisar (C-8 §4.2).
    expect(valorDe(c, "KAMAYUK_RENTAS_INGESTOR_RESPONSABLE")).toBe("Guardia de plataforma");
    expect(valorDe(c, "KAMAYUK_RENTAS_INGESTOR_CANAL")).toBe("guardia@example.pe");
    // El buzon vive en el namespace de `catastro`: sin el sufijo el nombre no resuelve.
    expect(valorDe(c, "KAMAYUK_CATASTRO_URL")).toBe(
      "http://kamayuk-catastro-web.kamayuk-catastro-stg",
    );
  });
});

describe("C-17 — que el despliegue pase de verdad", () => {
  /**
   * El anfitrion del motor **se pide**, y este descriptor no escribe ninguno.
   *
   * Es la mutacion que este criterio existe para cazar: hasta C-17 la constante decia
   * `jdbc:postgresql://postgres:5432/...`, y en Kubernetes no hay ningun `Service` llamado
   * `postgres` —ese nombre viene del `compose.yaml` local—. Medido en el clúster:
   * `UnknownHostException` en los ocho Jobs de los cuatro sistemas y en sus `Deployment`.
   */
  it("toda URL de base sale del anfitrion que entrega el entorno", () => {
    const urls = contenedoresDe([
      ...rentas.despliegue(ENTORNO),
      ...rentas.migracion(ENTORNO),
      ...rentas.implantacion(ENTORNO),
      ...rentas.lotes(ENTORNO),
    ]).flatMap((c) => (c.env ?? []).map((v) => v.value ?? ""))
      .filter((v) => v.startsWith("jdbc:"));

    expect(urls.length, "ninguna variable lleva una URL de base: ¿se dejo de leer?").toBeGreaterThan(0);
    for (const url of urls) {
      expect(url).toBe(`jdbc:postgresql://${ENTORNO.plataforma.motor}/rentas`);
    }
  });

  /**
   * DNS, sin el cual las demas reglas de egreso no sirven de nada.
   *
   * Una politica de egreso convierte a los pods que selecciona en «solo lo declarado», y todo lo
   * que estas reglas nombran —el motor, la identidad, los sistemas hermanos— se alcanza por el
   * nombre de un `Service`. Resolverlo es una consulta a CoreDNS, en `kube-system`. Con la regla
   * anadida a mano sobre el clúster, las ocho tareas de los cuatro sistemas pasaron de `Failed` a
   * `Complete` (C-17, punto 3).
   */
  it("TODA politica de egreso abre DNS hacia kube-system, en UDP y en TCP", () => {
    // Desde #44 hay DOS politicas de egreso —la del backend y la de la interfaz— y las dos
    // necesitan la regla: la propiedad se comprueba **por politica** y no sobre el total, o
    // anadir una tercera sin DNS pasaria en verde escondida detras de las otras dos.
    const conEgreso = rentas.egreso(ENTORNO).filter((p) => (p.spec.egress ?? []).length > 0);
    expect(conEgreso.length).toBeGreaterThanOrEqual(2);

    for (const politica of conEgreso) {
      const dns = (politica.spec.egress ?? []).filter((r) =>
        (r.to ?? []).some(
          (d) => d.namespaceSelector?.matchLabels?.["kubernetes.io/metadata.name"] === "kube-system",
        ),
      );
      expect(
        dns,
        `«${politica.metadata.name}» no abre DNS: ninguna de sus demas reglas puede resolver un nombre`,
      ).toHaveLength(1);
      expect(
        (dns[0]?.ports ?? []).map((p) => `${p.protocol}/${p.port}`).sort(),
        "TCP tambien: una respuesta que no cabe en un datagrama se reintenta por TCP",
      ).toEqual(["TCP/53", "UDP/53"]);
    }
  });
});

/**
 * #44 — que `rentas-web` se pueda desplegar, y que llegue a alguien.
 *
 * Lo que estas pruebas vigilan no es que los manifiestos existan —eso se ve leyendolos— sino las
 * decisiones cuyo fallo NO GRITA: la precedencia del ingreso, el prefijo, el puerto de la
 * politica de red, de donde sale el emisor OIDC y sobre que archivo cae el montaje.
 */
describe("#44 — la interfaz desplegada", () => {
  const manifiestos = rentas.ingreso(ENTORNO);
  const rutas = manifiestos.flatMap((m) => (m.kind === "IngressRoute" ? m.spec.routes : []));
  const deLaApi = rutas.find((r) => r.match.includes("/rentas/api/v1"));
  const deLaInterfaz = rutas.find((r) => !r.match.includes("/rentas/api/v1"));

  const interfazDe = (m: Manifiesto[]) =>
    m
      .filter((x) => x.kind === "Deployment" && x.metadata.name === "kamayuk-rentas-interfaz")
      .flatMap((x) => (x.kind === "Deployment" ? x.spec.template.spec.containers : []));

  /**
   * AC-6. **La prioridad se escribe, no se hereda de la longitud de la regla.**
   *
   * Traefik v3 ordena por longitud del `match` cuando nadie declara `priority`, y
   * `PathPrefix(/rentas/api/v1)` es mas larga que `PathPrefix(/rentas)` — o sea que hoy saldria
   * bien **por accidente**. Un `undefined` aqui no es «el valor por omision»: es que la
   * precedencia la decide una propiedad del texto de la regla, y el dia que alguien reescriba la
   * de la interfaz para que sea mas larga, la API se la queda el nginx y contesta 200 con HTML.
   */
  it("las dos rutas declaran su prioridad, y la de la API es la mayor", () => {
    expect(rutas, "la ruta va partida en dos: la API y la interfaz").toHaveLength(2);
    expect(
      deLaApi?.priority,
      "sin prioridad explicita la precedencia sale bien por accidente",
    ).toBeTypeOf("number");
    expect(deLaInterfaz?.priority).toBeTypeOf("number");
    expect(deLaApi!.priority!).toBeGreaterThan(deLaInterfaz!.priority!);
  });

  /**
   * AC-6, y es la mitad que produce un **200** cuando se hace al reves.
   *
   * `Api.RAIZ` del backend es `/rentas/api/v1` entera: quitarle el prefijo deja a Spring buscando
   * `/api/v1/...` y contestando 404 a todo. Y a la interfaz hay que quitarselo porque su nginx
   * sirve en la raiz del contenedor.
   */
  it("el prefijo se quita SOLO en la ruta de la interfaz", () => {
    expect(deLaApi?.middlewares ?? [], "el backend espera la ruta entera").toEqual([]);
    expect((deLaInterfaz?.middlewares ?? []).map((m) => m.name)).toEqual([
      "kamayuk-rentas-quitar-prefijo",
    ]);

    const middleware = manifiestos.find((m) => m.kind === "Middleware");
    expect(middleware?.metadata.name).toBe("kamayuk-rentas-quitar-prefijo");
    expect(middleware?.kind === "Middleware" ? middleware.spec : {}).toEqual({
      stripPrefix: { prefixes: ["/rentas"] },
    });
  });

  /** Cada ruta a SU servicio, y el de la interfaz no es el del backend. */
  it("la API va al backend y la interfaz a la interfaz", () => {
    expect(deLaApi?.services.map((s) => s.name)).toEqual(["kamayuk-rentas-web"]);
    expect(deLaInterfaz?.services.map((s) => s.name)).toEqual(["kamayuk-rentas-interfaz"]);
  });

  /**
   * AC-7. La interfaz **no hereda** las aristas del backend, y el vehiculo es la etiqueta.
   *
   * Si su `componente` fuera `rentas`, el `podSelector` de la politica del backend la
   * seleccionaria y un nginx de archivos estaticos tendria salida a PostgreSQL.
   */
  it("la interfaz no sale a PostgreSQL ni a ningun otro sistema: solo DNS", () => {
    const suyas = rentas
      .egreso(ENTORNO)
      .filter((p) => p.spec.podSelector.matchLabels?.["componente"] === "rentas-interfaz");
    expect(suyas.map((p) => p.metadata.name).sort()).toEqual([
      "kamayuk-rentas-interfaz-egreso",
      "kamayuk-rentas-interfaz-ingreso",
    ]);

    const salidas = suyas.flatMap((p) => p.spec.egress ?? []);
    expect(salidas, "solo DNS, y nada mas").toHaveLength(1);
    expect(
      salidas.flatMap((r) => (r.ports ?? []).map((p) => p.port)),
      "un 5432 aqui seria salida a la base desde un servidor de archivos",
    ).toEqual([53, 53]);
  });

  /**
   * AC-7, la trampa del puerto. Una `NetworkPolicy` filtra sobre el puerto del **contenedor**; el
   * mapeo 80 -> 8080 lo deshace el `Service` antes de que la politica mire nada. Con 80 escrito
   * aqui la politica no admite absolutamente nada, y el sintoma es el navegador esperando con la
   * ruta creada y el pod sano.
   */
  it("la entrada se abre al puerto del CONTENEDOR y no al del Service", () => {
    const entrada = rentas
      .egreso(ENTORNO)
      .find((p) => p.metadata.name === "kamayuk-rentas-interfaz-ingreso");
    const puertos = (entrada?.spec.ingress ?? []).flatMap((r) =>
      (r.ports ?? []).map((p) => p.port),
    );
    expect(puertos).toEqual([8080]);

    const servicio = rentas
      .despliegue(ENTORNO)
      .find((m) => m.kind === "Service" && m.metadata.name === "kamayuk-rentas-interfaz");
    expect(servicio?.kind === "Service" ? servicio.spec.ports : []).toEqual([
      { name: "http", port: 80, targetPort: 8080 },
    ]);
  });

  /**
   * El emisor sale del ambiente, y es el PUBLICO.
   *
   * `plataforma.jwks` es la otra direccion del mismo Keycloak y NO vale aqui: es un nombre de la
   * red interna del cluster, que el navegador no puede alcanzar. Confundirlas daria una interfaz
   * que no deja entrar a nadie, con un error que habla de un anfitrion desconocido.
   */
  it("el ConfigMap sirve el emisor publico, y no el JWKS interno", () => {
    const mapa = rentas
      .despliegue(ENTORNO)
      .find((m) => m.kind === "ConfigMap" && m.metadata.name.includes("interfaz"));
    const guion = mapa?.kind === "ConfigMap" ? (mapa.data["configuracion.js"] ?? "") : "";

    expect(guion).toContain("window.__KAMAYUK_RENTAS__");
    expect(guion).toContain(ENTORNO.plataforma.emisor);
    expect(guion, "el JWKS es una direccion interna: el navegador no la alcanza").not.toContain(
      ENTORNO.plataforma.jwks,
    );
    // Y no queda horneado ningun `localhost`, que es el valor por omision del paquete.
    expect(guion, "el valor por omision del paquete no puede llegar al cluster").not.toContain(
      "localhost",
    );
  });

  /**
   * El montaje tiene que caer sobre el archivo que `nginx` sirve, y con `subPath`.
   *
   * Sin `subPath` el montaje tapa el directorio entero: la imagen serviria un `html/` con un solo
   * archivo dentro, sin `index.html` y sin `assets/`. Y la ruta tiene que ser exactamente la del
   * archivo que viaja vacio en `frontend/public/`; si no, el `ConfigMap` no reemplaza nada y la
   * interfaz entra por el emisor por omision —`localhost`— desde la municipalidad.
   */
  it("el guion se monta con subPath sobre el que la imagen ya trae", () => {
    const contenedor = interfazDe(rentas.despliegue(ENTORNO))[0];
    expect(contenedor?.volumeMounts).toEqual([
      {
        name: "configuracion",
        mountPath: "/usr/share/nginx/html/configuracion.js",
        subPath: "configuracion.js",
        readOnly: true,
      },
    ]);
  });

  /**
   * AC-2, la mitad que este paquete SI puede afirmar.
   *
   * `SEGURIDAD` fija `runAsNonRoot: true` y **no** fija `runAsUser`, y eso solo es correcto
   * porque la imagen declara su uid en numero. La otra mitad —que `frontend/Dockerfile` diga
   * `USER 101` y no `USER nginx`— la comprueba `frontend/verificaciones/imagen-y-despliegue.test.ts`,
   * que es donde se puede leer un archivo: **este paquete no declara `@types/node` a proposito**,
   * porque un descriptor es una funcion pura que no lee ni el disco ni el entorno (ADR-0031 §2) y
   * la forma mas barata de que siga siendolo es que ni siquiera pueda.
   */
  it("no fija runAsUser, porque quien declara el uid es la imagen", () => {
    const contenedor = interfazDe(rentas.despliegue(ENTORNO))[0];
    expect(contenedor?.securityContext?.runAsNonRoot).toBe(true);
    expect(JSON.stringify(contenedor?.securityContext)).not.toContain("runAsUser");
  });
});

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
    namespace: "sgtm-stg",
    // El anfitrion del motor, ya cruzando el namespace (C-17, punto 1). Los cuatro descriptores
    // escribian `postgres:5432` a mano, que es el nombre del `compose.yaml` local: en Kubernetes
    // no existe ningun `Service` que se llame asi.
    motor: "sgtm-stg-postgres.sgtm-stg:5432",
    emisor: "https://stg.kamayuk.example/keycloak/realms/sgtm",
    jwks: "http://sgtm-stg-identidad.sgtm-stg:8080/keycloak/realms/sgtm/protocol/openid-connect/certs",
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
  it("produce UN Deployment, el del perfil web: `batch` no es un proceso que este siempre", () => {
    const deployments = rentas.despliegue(ENTORNO).filter((m) => m.kind === "Deployment");
    expect(deployments.map((m) => m.metadata.name)).toEqual(["kamayuk-rentas-web"]);

    const perfiles = deployments.map(
      (m) =>
        m.kind === "Deployment"
          ? (m.spec.template.spec.containers[0]?.env ?? []).find(
              (v) => v.name === "SPRING_PROFILES_ACTIVE",
            )?.value
          : undefined,
    );
    expect(perfiles, "un `Deployment` en perfil `batch` es un CrashLoopBackOff garantizado").toEqual([
      "web",
    ]);
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
   * Hasta C-14 corria la misma que el `Deployment` con `KAMAYUK_DB_USUARIO=sgtm_owner` y sin perfil:
   * arrancaba el proceso web con las credenciales del unico rol con DDL, y la aplicacion tiene
   * `spring.flyway.enabled: false` a proposito (ARQ-03 §4). O sea que ese Job **no migraba**.
   */
  it("el Job de migracion corre el migrador, con las variables que el migrador lee", () => {
    const contenedores = contenedoresDe(rentas.migracion(ENTORNO));
    expect(contenedores).toHaveLength(1);
    const c = contenedores[0]!;
    expect(c.image).toBe(ENTORNO.imagenDe(`${"rentas"}-migrador`));
    expect(valorDe(c, "KAMAYUK_DB_OWNER_USUARIO")).toBe("sgtm_owner");
    expect(declara(c, "KAMAYUK_DB_OWNER_CLAVE")).toBe(true);
    // La de la APLICACION. El migrador no la lee, y ponerla es lo que hacia que este Job
    // pareciera correcto sin migrar nada.
    expect(declara(c, "KAMAYUK_DB_USUARIO")).toBe(false);
  });

  it("y las dos imagenes son los dos objetivos del Dockerfile", () => {
    expect(rentas.imagenes).toEqual(["rentas", `${"rentas"}-migrador`]);
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

describe("C-14 §3 — el ingestor de catastro, declarado entero y suspendido", () => {
  /**
   * C-8 lo construyo y lo midio, y su hueco 2 decia: «mientras el descriptor no tenga campo, el
   * ingestor no se puede desplegar». Ahora lo tiene, y nace SUSPENDIDO porque el feed de
   * `catastro` esta detras de `@RequiereAcceso` y no hay identidad de servicio (ADR-0028 §2):
   * sin credencial la llamada sale sin `Authorization` y `catastro` la rechaza con 401, asi que
   * un CronJob activo fallaria cada noche y su alerta seria ruido.
   */
  it("declara su configuracion entera, y no corre todavia", () => {
    const crones = rentas.lotes(ENTORNO).filter((m) => m.kind === "CronJob");
    expect(crones).toHaveLength(1);
    const cron = crones[0]!;
    expect(cron.spec.suspend).toBe(true);
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
  it("abre DNS hacia kube-system, en UDP y en TCP", () => {
    const reglas = rentas.egreso(ENTORNO).flatMap((p) => p.spec.egress ?? []);
    const dns = reglas.filter((r) =>
      (r.to ?? []).some(
        (d) => d.namespaceSelector?.matchLabels?.["kubernetes.io/metadata.name"] === "kube-system",
      ),
    );

    expect(dns, "sin DNS ninguna de las demas reglas de egreso puede resolver un nombre").toHaveLength(1);
    expect(
      (dns[0]?.ports ?? []).map((p) => `${p.protocol}/${p.port}`).sort(),
      "TCP tambien: una respuesta que no cabe en un datagrama se reintenta por TCP",
    ).toEqual(["TCP/53", "UDP/53"]);
  });
});

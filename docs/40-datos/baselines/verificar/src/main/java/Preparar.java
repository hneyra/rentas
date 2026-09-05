import java.nio.file.*;
import java.sql.*;
import org.flywaydb.core.Flyway;

/** Crea una base, la provisiona como el ambiente real y le aplica un directorio de migraciones. */
public final class Preparar {

    static final String[] ROLES = {"sgtm_owner", "sgtm_app", "sgtm_readonly", "rol_carga_parametros"};

    public static void main(String[] a) throws Exception {
        String base = a[0], migraciones = a[1];
        try (Connection c = DriverManager.getConnection(admin(), usuario(), clave());
                Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + base + " WITH (FORCE)");
            s.execute("CREATE DATABASE " + base + " ENCODING 'UTF8' LC_CTYPE 'C.UTF-8'"
                    + " LC_COLLATE 'C.UTF-8' TEMPLATE template0");
            for (String r : ROLES) {
                s.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='"
                        + r + "') THEN CREATE ROLE " + r + " NOLOGIN; END IF; END $$");
                s.execute("ALTER ROLE " + r
                        + " NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE NOREPLICATION"
                        + " LOGIN PASSWORD 'clave_" + r + "'");
            }
        }
        String url = aBase(base);
        // El provisionamiento de la base, tal como crear-roles.sql lo hace, menos postgis.
        try (Connection c = DriverManager.getConnection(url, usuario(), clave());
                Statement s = c.createStatement()) {
            s.execute("GRANT USAGE, CREATE ON SCHEMA public TO sgtm_owner");
            s.execute("GRANT USAGE ON SCHEMA public TO sgtm_app, sgtm_readonly, rol_carga_parametros");
            s.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
            s.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
            s.execute("CREATE EXTENSION IF NOT EXISTS btree_gist");
        }
        Flyway.configure()
                .dataSource(url, "sgtm_owner", "clave_sgtm_owner")
                .locations("filesystem:" + Paths.get(migraciones).toAbsolutePath())
                .load()
                .migrate();
        System.out.println("OK " + base);
    }
    /** La URL del motor, del entorno: el arnes no fija ningun puerto (INF-01 §4.1). */
    static String base() {
        String u = System.getenv("KAMAYUK_BASELINE_URL");
        if (u == null || u.isBlank()) {
            throw new IllegalStateException(
                    "Falta KAMAYUK_BASELINE_URL, p.ej. jdbc:postgresql://localhost:5432/postgres");
        }
        return u;
    }

    static String usuario() {
        return System.getenv().getOrDefault("KAMAYUK_BASELINE_USUARIO", "postgres");
    }

    static String clave() {
        return System.getenv().getOrDefault("KAMAYUK_BASELINE_CLAVE", "postgres");
    }

    static String admin() { return base(); }

    /** La misma URL, apuntando a otra base del mismo cluster. */
    static String aBase(String nombre) {
        return base().replaceFirst("/[^/?]+(\\?.*)?$", "/" + nombre);
    }

}

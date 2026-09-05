import java.sql.*;

/** Rompe el esquema de una base a proposito, para demostrar que las guardas muerden. */
public final class Mutar {
    public static void main(String[] a) throws Exception {
        String sql = System.getProperty("sql");
        try (Connection c = DriverManager.getConnection(
                aBase(a[0]), usuario(), clave());
                Statement s = c.createStatement()) {
            s.execute(sql);
        }
        System.out.println("mutado [" + a[0] + "]: " + sql);
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

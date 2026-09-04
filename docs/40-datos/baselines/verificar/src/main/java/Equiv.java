import java.sql.*;

/**
 * Demuestra EJECUTANDO que las dos formas del `CHECK ... IN (...)` que PostgreSQL produce al
 * reparsear son la misma restriccion: no se comparan textos, se comparan comportamientos.
 */
public final class Equiv {
    public static void main(String[] a) throws Exception {
        // acta_fiscalizacion.hallazgo: 5 valores validos, y uno que no lo es.
        String[] validos = {"CONFORME", "OMISO", "SUBVALUADOR", "USO_DISTINTO", "NO_UBICADO"};
        String[] invalidos = {"XXXX", "conforme", "USO DISTINTO", ""};
        for (String base : new String[] {"ref", "t_rentas"}) {
            StringBuilder r = new StringBuilder();
            try (Connection c = DriverManager.getConnection(
                    aBase(base), usuario(), clave());
                    Statement s = c.createStatement()) {
                for (String v : validos) r.append(prueba(s, v) ? '.' : '!');
                r.append(' ');
                for (String v : invalidos) r.append(prueba(s, v) ? '!' : '.');
            }
            System.out.println(base + ": " + r + "   (. = como debe, ! = NO)");
        }
    }

    /** Evalua la expresion del CHECK tal como el catalogo la tiene, contra un valor. */
    static boolean prueba(Statement s, String valor) throws SQLException {
        String def;
        try (ResultSet r = s.executeQuery(
                "SELECT pg_get_constraintdef(k.oid) FROM pg_constraint k"
                + " WHERE k.conname = 'acta_fiscalizacion_hallazgo_check'")) {
            r.next();
            def = r.getString(1);
        }
        String expr = def.substring(def.indexOf('(') + 1, def.lastIndexOf(')'));
        expr = expr.replace("hallazgo", "$$" + valor + "$$::character varying");
        try (ResultSet r = s.executeQuery("SELECT COALESCE((" + expr + "), false)")) {
            r.next();
            return r.getBoolean(1);
        }
    }
    /** La URL del motor, del entorno: el arnes no fija ningun puerto (INF-01 §4.1). */
    static String base() {
        String u = System.getenv("SGTM_BASELINE_URL");
        if (u == null || u.isBlank()) {
            throw new IllegalStateException(
                    "Falta SGTM_BASELINE_URL, p.ej. jdbc:postgresql://localhost:5432/postgres");
        }
        return u;
    }

    static String usuario() {
        return System.getenv().getOrDefault("SGTM_BASELINE_USUARIO", "postgres");
    }

    static String clave() {
        return System.getenv().getOrDefault("SGTM_BASELINE_CLAVE", "postgres");
    }

    static String admin() { return base(); }

    /** La misma URL, apuntando a otra base del mismo cluster. */
    static String aBase(String nombre) {
        return base().replaceFirst("/[^/?]+(\\?.*)?$", "/" + nombre);
    }

}

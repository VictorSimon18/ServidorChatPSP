package seguridad;

public class ValidadorEntrada {

    private static final int MAX_NOMBRE = 20;
    private static final int MIN_NOMBRE = 3;
    private static final int MAX_PASSWORD = 50;
    private static final int MIN_PASSWORD = 4;
    private static final int MAX_MENSAJE = 500;
    private static final String NOMBRE_PATTERN = "^[a-zA-Z0-9_]+$";

    public static String validarNombre(String nombre) {
        if (nombre == null || nombre.isBlank()) {
            return "El nombre no puede estar vacío.";
        }
        nombre = nombre.trim();
        if (nombre.length() < MIN_NOMBRE || nombre.length() > MAX_NOMBRE) {
            return "El nombre debe tener entre " + MIN_NOMBRE + " y " + MAX_NOMBRE + " caracteres.";
        }
        if (!nombre.matches(NOMBRE_PATTERN)) {
            return "El nombre solo puede contener letras, números y guiones bajos.";
        }
        return null;
    }

    public static String validarPassword(String password) {
        if (password == null || password.isBlank()) {
            return "La contraseña no puede estar vacía.";
        }
        if (password.length() < MIN_PASSWORD || password.length() > MAX_PASSWORD) {
            return "La contraseña debe tener entre " + MIN_PASSWORD + " y " + MAX_PASSWORD + " caracteres.";
        }
        return null;
    }

    public static String sanitizarMensaje(String mensaje) {
        if (mensaje == null) return "";
        mensaje = mensaje.trim();
        if (mensaje.length() > MAX_MENSAJE) {
            mensaje = mensaje.substring(0, MAX_MENSAJE);
        }
        return mensaje;
    }
}

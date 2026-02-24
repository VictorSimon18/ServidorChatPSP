package comun;

import java.io.Serializable;

public class Usuario implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String nombre;
    private final String salt;
    private final String passwordHash;

    public Usuario(String nombre, String salt, String passwordHash) {
        this.nombre = nombre;
        this.salt = salt;
        this.passwordHash = passwordHash;
    }

    public String getNombre() { return nombre; }
    public String getSalt() { return salt; }
    public String getPasswordHash() { return passwordHash; }

    public String toFileString() {
        return nombre + ":" + salt + ":" + passwordHash;
    }

    public static Usuario fromFileString(String linea) {
        String[] partes = linea.split(":");
        if (partes.length != 3) return null;
        return new Usuario(partes[0], partes[1], partes[2]);
    }
}

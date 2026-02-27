package servidor;

import comun.Mensaje;
import comun.TipoMensaje;
import comun.Usuario;
import seguridad.HashUtil;
import seguridad.ValidadorEntrada;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Gestor central de mensajes, usuarios y conexiones TCP. Patrón Singleton.
 *
 * <p>En la arquitectura híbrida HTTP + TCP:
 * <ul>
 *   <li>HTTP gestiona login, registro, envío de mensajes y desconexión.</li>
 *   <li>TCP gestiona la entrega de mensajes en tiempo real (push del servidor).</li>
 * </ul>
 *
 * <p>Los mensajes se distribuyen directamente a través de los {@link PrintWriter}
 * de cada socket TCP, sustituyendo las colas {@code BlockingQueue} del diseño anterior.
 */
public class GestorMensajes {

    private static GestorMensajes instancia;

    // Usuarios autenticados vía HTTP (pueden enviar mensajes y ejecutar comandos)
    private final ConcurrentHashMap<String, Boolean> usuariosAutenticados = new ConcurrentHashMap<>();

    // Streams de escritura TCP por usuario (para recibir mensajes en push)
    // Clave: nombre de usuario  |  Valor: PrintWriter del socket TCP asociado
    private final ConcurrentHashMap<String, PrintWriter> escritoresTCP = new ConcurrentHashMap<>();

    private final Object lockUsuarios = new Object(); // Protege el acceso al fichero de usuarios
    private final File   archivoUsuarios;
    private OutputStream pipeHistorial;               // Pipe al proceso hijo ProcesoHistorial

    private GestorMensajes() {
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdirs();
        archivoUsuarios = new File("data/usuarios.txt");
    }

    public static synchronized GestorMensajes getInstancia() {
        if (instancia == null) instancia = new GestorMensajes();
        return instancia;
    }

    public void setPipeHistorial(OutputStream pipe) {
        this.pipeHistorial = pipe;
    }

    // ── Gestión de clientes ──────────────────────────────────────────────────

    /**
     * Marca al usuario como autenticado tras un login HTTP exitoso.
     * El socket TCP se registra después, cuando el cliente se conecta al puerto TCP.
     */
    public void registrarCliente(String nombre) {
        usuariosAutenticados.put(nombre, Boolean.TRUE);
    }

    /**
     * Registra el stream TCP del cliente ya identificado.
     * Llamado desde {@link ManejadorClienteTCP} tras leer el nombre de usuario.
     * En este momento se anuncia la entrada al chat y se envía la lista actualizada.
     *
     * @param nombre   Nombre de usuario autenticado.
     * @param escritor PrintWriter del socket TCP asociado a ese usuario.
     */
    public void registrarSocketTCP(String nombre, PrintWriter escritor) {
        escritoresTCP.put(nombre, escritor);
        // Ahora que el cliente puede recibir mensajes push, notificamos su llegada
        broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " se unió al chat.", "Servidor"));
        enviarListaUsuarios();
    }

    /**
     * Desconecta completamente al cliente (HTTP + TCP).
     * Invocado desde el handler HTTP {@code /desconectar} o por el comando {@code /quit}.
     * Usa la atomicidad de {@link ConcurrentHashMap#remove} para evitar mensajes
     * duplicados si ambos mecanismos de desconexión se disparan simultáneamente.
     */
    public void desconectarCliente(String nombre) {
        boolean eraConectado = usuariosAutenticados.remove(nombre) != null;
        PrintWriter escritor  = escritoresTCP.remove(nombre);

        // Cerrar el PrintWriter cierra el OutputStream del socket TCP de ese cliente
        if (escritor != null) escritor.close();

        if (eraConectado && nombre != null && !nombre.isEmpty()) {
            broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " ha salido del chat.", "Servidor"));
            enviarListaUsuarios();
        }
    }

    /**
     * Cierra solo la parte TCP cuando el socket del cliente se pierde inesperadamente.
     * Llamado desde {@link ManejadorClienteTCP} al detectar EOF o excepción de E/S.
     */
    public void desconectarSocketTCP(String nombre) {
        boolean eraConectado = usuariosAutenticados.remove(nombre) != null;
        escritoresTCP.remove(nombre);

        if (eraConectado && nombre != null && !nombre.isEmpty()) {
            broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " ha salido del chat.", "Servidor"));
            enviarListaUsuarios();
        }
    }

    /** Devuelve {@code true} si el usuario completó el login HTTP y está activo. */
    public boolean estaConectado(String nombre) {
        return usuariosAutenticados.containsKey(nombre);
    }

    /** Lista de usuarios con socket TCP activo (visibles para el comando /users). */
    public List<String> getUsuariosConectados() {
        return new ArrayList<>(escritoresTCP.keySet());
    }

    // ── Mensajes ─────────────────────────────────────────────────────────────

    /**
     * Envía el mensaje a todos los clientes con socket TCP activo.
     * Se sincroniza individualmente sobre cada {@link PrintWriter} para evitar
     * escrituras concurrentes en el mismo stream desde distintos hilos.
     */
    public void broadcast(Mensaje mensaje) {
        String linea = mensaje.toHttpString();
        for (PrintWriter escritor : escritoresTCP.values()) {
            synchronized (escritor) {
                escritor.println(linea);
            }
        }
        escribirHistorial(mensaje.toString());
    }

    /** Envía el mensaje únicamente al cliente indicado. */
    public void enviarA(String nombre, Mensaje mensaje) {
        PrintWriter escritor = escritoresTCP.get(nombre);
        if (escritor != null) {
            synchronized (escritor) {
                escritor.println(mensaje.toHttpString());
            }
        }
    }

    /**
     * Envía un mensaje privado al destinatario y una copia al remitente.
     *
     * @return {@code true} si el destinatario existe y tiene socket TCP activo.
     */
    public boolean enviarPrivado(Mensaje mensaje) {
        PrintWriter escritorDestino = escritoresTCP.get(mensaje.getDestinatario());
        if (escritorDestino == null) return false;

        String linea = mensaje.toHttpString();
        synchronized (escritorDestino) {
            escritorDestino.println(linea);
        }

        // Copia al remitente si es distinto del destinatario
        if (!mensaje.getRemitente().equals(mensaje.getDestinatario())) {
            PrintWriter escritorRemitente = escritoresTCP.get(mensaje.getRemitente());
            if (escritorRemitente != null) {
                synchronized (escritorRemitente) {
                    escritorRemitente.println(linea);
                }
            }
        }

        escribirHistorial("[PRIVADO] " + mensaje);
        return true;
    }

    /** Envía la lista actualizada de usuarios con TCP activo a todos los clientes. */
    private void enviarListaUsuarios() {
        String lista = String.join(",", escritoresTCP.keySet());
        Mensaje msg  = new Mensaje(TipoMensaje.USER_LIST, lista, "Servidor");
        String  linea = msg.toHttpString();
        for (PrintWriter escritor : escritoresTCP.values()) {
            synchronized (escritor) {
                escritor.println(linea);
            }
        }
    }

    // ── Historial ────────────────────────────────────────────────────────────

    /** Escribe una línea en el proceso hijo de historial a través del pipe. */
    private void escribirHistorial(String linea) {
        if (pipeHistorial != null) {
            try {
                pipeHistorial.write((linea + "\n").getBytes(StandardCharsets.UTF_8));
                pipeHistorial.flush();
            } catch (IOException e) {
                System.err.println("Error escribiendo al historial: " + e.getMessage());
            }
        }
    }

    // ── Gestión de usuarios (fichero) ─────────────────────────────────────────

    /** Registra un nuevo usuario en el fichero. Devuelve el mensaje de error o {@code null} si ok. */
    public String registrarUsuario(String nombre, String password) {
        String errorNombre = ValidadorEntrada.validarNombre(nombre);
        if (errorNombre != null) return errorNombre;
        String errorPass = ValidadorEntrada.validarPassword(password);
        if (errorPass != null) return errorPass;

        synchronized (lockUsuarios) {
            if (buscarUsuario(nombre) != null) return "El usuario '" + nombre + "' ya existe.";
            String salt  = HashUtil.generarSalt();
            String hash  = HashUtil.hashPassword(password, salt);
            Usuario nuevo = new Usuario(nombre, salt, hash);
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(archivoUsuarios, true))) {
                bw.write(nuevo.toFileString());
                bw.newLine();
            } catch (IOException e) {
                return "Error al guardar usuario: " + e.getMessage();
            }
            return null;
        }
    }

    /** Autentica al usuario. Devuelve el mensaje de error o {@code null} si las credenciales son válidas. */
    public String autenticarUsuario(String nombre, String password) {
        String errorNombre = ValidadorEntrada.validarNombre(nombre);
        if (errorNombre != null) return errorNombre;

        synchronized (lockUsuarios) {
            Usuario usuario = buscarUsuario(nombre);
            if (usuario == null) return "Usuario no encontrado.";
            if (!HashUtil.verificarPassword(password, usuario.getSalt(), usuario.getPasswordHash())) {
                return "Contraseña incorrecta.";
            }
            return null;
        }
    }

    /** Busca un usuario en el fichero por nombre. Devuelve {@code null} si no existe. */
    private Usuario buscarUsuario(String nombre) {
        if (!archivoUsuarios.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(archivoUsuarios))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                Usuario u = Usuario.fromFileString(linea);
                if (u != null && u.getNombre().equals(nombre)) return u;
            }
        } catch (IOException e) {
            System.err.println("Error leyendo usuarios: " + e.getMessage());
        }
        return null;
    }
}

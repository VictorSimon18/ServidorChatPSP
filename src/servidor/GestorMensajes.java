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
import java.util.concurrent.locks.ReentrantLock;

public class GestorMensajes {

    private static GestorMensajes instancia;

    private final ConcurrentHashMap<String, ManejadorCliente> clientesConectados = new ConcurrentHashMap<>();
    private final ReentrantLock lockBroadcast = new ReentrantLock();
    private final Object lockUsuarios = new Object();
    private final File archivoUsuarios;
    private OutputStream pipeHistorial;

    private GestorMensajes() {
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdirs();
        }
        archivoUsuarios = new File("data/usuarios.txt");
    }

    public static synchronized GestorMensajes getInstancia() {
        if (instancia == null) {
            instancia = new GestorMensajes();
        }
        return instancia;
    }

    public void setPipeHistorial(OutputStream pipe) {
        this.pipeHistorial = pipe;
    }

    // --- Gestión de clientes ---

    public void registrarCliente(String nombre, ManejadorCliente manejador) {
        clientesConectados.put(nombre, manejador);
        enviarListaUsuarios();
    }

    public void desconectarCliente(String nombre) {
        clientesConectados.remove(nombre);
        if (nombre != null) {
            broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " ha salido del chat.", "Servidor"));
            enviarListaUsuarios();
        }
    }

    public boolean estaConectado(String nombre) {
        return clientesConectados.containsKey(nombre);
    }

    public List<String> getUsuariosConectados() {
        return new ArrayList<>(clientesConectados.keySet());
    }

    // --- Broadcast y mensajes privados ---

    public void broadcast(Mensaje mensaje) {
        lockBroadcast.lock();
        try {
            for (ManejadorCliente cliente : clientesConectados.values()) {
                cliente.enviarMensaje(mensaje);
            }
            escribirHistorial(mensaje.toString());
        } finally {
            lockBroadcast.unlock();
        }
    }

    public boolean enviarPrivado(Mensaje mensaje) {
        ManejadorCliente destinatario = clientesConectados.get(mensaje.getDestinatario());
        if (destinatario == null) return false;

        destinatario.enviarMensaje(mensaje);
        // También enviar copia al remitente
        ManejadorCliente remitente = clientesConectados.get(mensaje.getRemitente());
        if (remitente != null && !mensaje.getRemitente().equals(mensaje.getDestinatario())) {
            remitente.enviarMensaje(mensaje);
        }
        escribirHistorial("[PRIVADO] " + mensaje);
        return true;
    }

    private void enviarListaUsuarios() {
        String lista = String.join(",", clientesConectados.keySet());
        Mensaje msg = new Mensaje(TipoMensaje.USER_LIST, lista, "Servidor");
        lockBroadcast.lock();
        try {
            for (ManejadorCliente cliente : clientesConectados.values()) {
                cliente.enviarMensaje(msg);
            }
        } finally {
            lockBroadcast.unlock();
        }
    }

    // --- Historial (pipe al proceso hijo) ---

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

    // --- Gestión de usuarios (fichero) ---

    public String registrarUsuario(String nombre, String password) {
        String errorNombre = ValidadorEntrada.validarNombre(nombre);
        if (errorNombre != null) return errorNombre;
        String errorPass = ValidadorEntrada.validarPassword(password);
        if (errorPass != null) return errorPass;

        synchronized (lockUsuarios) {
            if (buscarUsuario(nombre) != null) {
                return "El usuario '" + nombre + "' ya existe.";
            }
            String salt = HashUtil.generarSalt();
            String hash = HashUtil.hashPassword(password, salt);
            Usuario nuevo = new Usuario(nombre, salt, hash);
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(archivoUsuarios, true))) {
                bw.write(nuevo.toFileString());
                bw.newLine();
            } catch (IOException e) {
                return "Error al guardar usuario: " + e.getMessage();
            }
            return null; // éxito
        }
    }

    public String autenticarUsuario(String nombre, String password) {
        String errorNombre = ValidadorEntrada.validarNombre(nombre);
        if (errorNombre != null) return errorNombre;

        synchronized (lockUsuarios) {
            Usuario usuario = buscarUsuario(nombre);
            if (usuario == null) {
                return "Usuario no encontrado.";
            }
            if (!HashUtil.verificarPassword(password, usuario.getSalt(), usuario.getPasswordHash())) {
                return "Contraseña incorrecta.";
            }
            return null; // éxito
        }
    }

    private Usuario buscarUsuario(String nombre) {
        if (!archivoUsuarios.exists()) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(archivoUsuarios))) {
            String linea;
            while ((linea = br.readLine()) != null) {
                Usuario u = Usuario.fromFileString(linea);
                if (u != null && u.getNombre().equals(nombre)) {
                    return u;
                }
            }
        } catch (IOException e) {
            System.err.println("Error leyendo usuarios: " + e.getMessage());
        }
        return null;
    }
}

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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class GestorMensajes {

    private static GestorMensajes instancia;

    private final ConcurrentHashMap<String, BlockingQueue<Mensaje>> colasMensajes = new ConcurrentHashMap<>();
    private final Object lockUsuarios = new Object();
    private final File archivoUsuarios;
    private OutputStream pipeHistorial;

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

    // ── Gestión de clientes ─────────────────────────────────────────────────

    public void registrarCliente(String nombre) {
        colasMensajes.put(nombre, new LinkedBlockingQueue<>());
        enviarListaUsuarios();
    }

    public void desconectarCliente(String nombre) {
        colasMensajes.remove(nombre);
        if (nombre != null && !nombre.isEmpty()) {
            broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " ha salido del chat.", "Servidor"));
            enviarListaUsuarios();
        }
    }

    public boolean estaConectado(String nombre) {
        return colasMensajes.containsKey(nombre);
    }

    public List<String> getUsuariosConectados() {
        return new ArrayList<>(colasMensajes.keySet());
    }

    // ── Mensajes ────────────────────────────────────────────────────────────

    public void broadcast(Mensaje mensaje) {
        for (BlockingQueue<Mensaje> cola : colasMensajes.values()) {
            cola.offer(mensaje);
        }
        escribirHistorial(mensaje.toString());
    }

    public void enviarA(String nombre, Mensaje mensaje) {
        BlockingQueue<Mensaje> cola = colasMensajes.get(nombre);
        if (cola != null) cola.offer(mensaje);
    }

    public boolean enviarPrivado(Mensaje mensaje) {
        BlockingQueue<Mensaje> colaDestino = colasMensajes.get(mensaje.getDestinatario());
        if (colaDestino == null) return false;
        colaDestino.offer(mensaje);
        BlockingQueue<Mensaje> colaRemitente = colasMensajes.get(mensaje.getRemitente());
        if (colaRemitente != null && !mensaje.getRemitente().equals(mensaje.getDestinatario())) {
            colaRemitente.offer(mensaje);
        }
        escribirHistorial("[PRIVADO] " + mensaje);
        return true;
    }

    /** Long polling: bloquea hasta que hay un mensaje o expira el timeout. */
    public Mensaje esperarMensaje(String nombre, long timeoutMs) throws InterruptedException {
        BlockingQueue<Mensaje> cola = colasMensajes.get(nombre);
        if (cola == null) return null;
        return cola.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void enviarListaUsuarios() {
        String lista = String.join(",", colasMensajes.keySet());
        Mensaje msg = new Mensaje(TipoMensaje.USER_LIST, lista, "Servidor");
        for (BlockingQueue<Mensaje> cola : colasMensajes.values()) {
            cola.offer(msg);
        }
    }

    // ── Historial ───────────────────────────────────────────────────────────

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

    // ── Gestión de usuarios (fichero) ───────────────────────────────────────

    public String registrarUsuario(String nombre, String password) {
        String errorNombre = ValidadorEntrada.validarNombre(nombre);
        if (errorNombre != null) return errorNombre;
        String errorPass = ValidadorEntrada.validarPassword(password);
        if (errorPass != null) return errorPass;

        synchronized (lockUsuarios) {
            if (buscarUsuario(nombre) != null) return "El usuario '" + nombre + "' ya existe.";
            String salt = HashUtil.generarSalt();
            String hash = HashUtil.hashPassword(password, salt);
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

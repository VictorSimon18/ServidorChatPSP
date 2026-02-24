package servidor;

import comun.Mensaje;
import comun.TipoMensaje;
import seguridad.ValidadorEntrada;

import java.io.*;
import java.net.Socket;

public class ManejadorCliente extends Thread {

    private final Socket socket;
    private final GestorMensajes gestor;
    private ObjectInputStream entrada;
    private ObjectOutputStream salida;
    private String nombreUsuario;
    private volatile boolean activo = true;

    public ManejadorCliente(Socket socket) {
        this.socket = socket;
        this.gestor = GestorMensajes.getInstancia();
    }

    @Override
    public void run() {
        try {
            salida = new ObjectOutputStream(socket.getOutputStream());
            salida.flush();
            entrada = new ObjectInputStream(socket.getInputStream());

            while (activo) {
                Mensaje mensaje = (Mensaje) entrada.readObject();
                procesarMensaje(mensaje);
            }
        } catch (EOFException | java.net.SocketException e) {
            // Cliente desconectado
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("Error con cliente " + nombreUsuario + ": " + e.getMessage());
        } finally {
            desconectar();
        }
    }

    private void procesarMensaje(Mensaje mensaje) {
        switch (mensaje.getTipo()) {
            case LOGIN -> procesarLogin(mensaje);
            case REGISTER -> procesarRegistro(mensaje);
            case MESSAGE -> procesarMensajeChat(mensaje);
            case DISCONNECT -> {
                activo = false;
            }
            default -> enviarMensaje(new Mensaje(TipoMensaje.ERROR, "Tipo de mensaje no reconocido.", "Servidor"));
        }
    }

    private void procesarLogin(Mensaje mensaje) {
        String nombre = mensaje.getRemitente();
        String password = mensaje.getContenido();

        if (gestor.estaConectado(nombre)) {
            enviarMensaje(new Mensaje(TipoMensaje.ERROR, "El usuario ya está conectado.", "Servidor"));
            return;
        }

        String error = gestor.autenticarUsuario(nombre, password);
        if (error != null) {
            enviarMensaje(new Mensaje(TipoMensaje.ERROR, error, "Servidor"));
        } else {
            this.nombreUsuario = nombre;
            enviarMensaje(new Mensaje(TipoMensaje.OK, "Login correcto. Bienvenido " + nombre + "!", "Servidor"));
            gestor.registrarCliente(nombre, this);
            gestor.broadcast(new Mensaje(TipoMensaje.MESSAGE, nombre + " se ha unido al chat.", "Servidor"));
        }
    }

    private void procesarRegistro(Mensaje mensaje) {
        String nombre = mensaje.getRemitente();
        String password = mensaje.getContenido();

        String error = gestor.registrarUsuario(nombre, password);
        if (error != null) {
            enviarMensaje(new Mensaje(TipoMensaje.ERROR, error, "Servidor"));
        } else {
            enviarMensaje(new Mensaje(TipoMensaje.OK, "Registro exitoso. Ahora puedes iniciar sesión.", "Servidor"));
        }
    }

    private void procesarMensajeChat(Mensaje mensaje) {
        if (nombreUsuario == null) {
            enviarMensaje(new Mensaje(TipoMensaje.ERROR, "Debes iniciar sesión primero.", "Servidor"));
            return;
        }

        String contenido = ValidadorEntrada.sanitizarMensaje(mensaje.getContenido());
        if (contenido.isEmpty()) return;

        // Comandos especiales
        if (contenido.startsWith("/")) {
            procesarComando(contenido);
            return;
        }

        Mensaje msgBroadcast = new Mensaje(TipoMensaje.MESSAGE, contenido, nombreUsuario);
        gestor.broadcast(msgBroadcast);
    }

    private void procesarComando(String contenido) {
        String[] partes = contenido.split("\\s+", 3);
        String comando = partes[0].toLowerCase();

        switch (comando) {
            case "/users" -> {
                String lista = String.join(", ", gestor.getUsuariosConectados());
                enviarMensaje(new Mensaje(TipoMensaje.MESSAGE, "Usuarios conectados: " + lista, "Servidor"));
            }
            case "/private" -> {
                if (partes.length < 3) {
                    enviarMensaje(new Mensaje(TipoMensaje.ERROR, "Uso: /private <usuario> <mensaje>", "Servidor"));
                    return;
                }
                String destino = partes[1];
                String msgPrivado = partes[2];
                Mensaje privado = new Mensaje(TipoMensaje.PRIVATE, msgPrivado, nombreUsuario, destino);
                if (!gestor.enviarPrivado(privado)) {
                    enviarMensaje(new Mensaje(TipoMensaje.ERROR, "Usuario '" + destino + "' no encontrado.", "Servidor"));
                }
            }
            case "/help" -> {
                String ayuda = """
                        Comandos disponibles:
                        /users - Lista de usuarios conectados
                        /private <usuario> <mensaje> - Mensaje privado
                        /help - Mostrar esta ayuda
                        /quit - Desconectarse""";
                enviarMensaje(new Mensaje(TipoMensaje.HELP, ayuda, "Servidor"));
            }
            case "/quit" -> {
                activo = false;
            }
            default -> enviarMensaje(new Mensaje(TipoMensaje.ERROR, "Comando desconocido. Usa /help.", "Servidor"));
        }
    }

    public void enviarMensaje(Mensaje mensaje) {
        try {
            if (salida != null) {
                synchronized (salida) {
                    salida.writeObject(mensaje);
                    salida.flush();
                    salida.reset();
                }
            }
        } catch (IOException e) {
            System.err.println("Error enviando mensaje a " + nombreUsuario + ": " + e.getMessage());
        }
    }

    private void desconectar() {
        activo = false;
        gestor.desconectarCliente(nombreUsuario);
        try {
            if (entrada != null) entrada.close();
            if (salida != null) salida.close();
            if (!socket.isClosed()) socket.close();
        } catch (IOException e) {
            // Ignorar errores al cerrar
        }
        System.out.println("Cliente desconectado: " + (nombreUsuario != null ? nombreUsuario : socket.getInetAddress()));
    }
}

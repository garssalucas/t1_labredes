package labredes;

import java.net.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

public class UDPNode {
    private static final int PORT = 8080;
    private static String deviceName;
    private static final DeviceManager deviceManager = new DeviceManager();
    private static final AtomicInteger messageId = new AtomicInteger(1); // ID global sequencial

    // Controle de mensagens já recebidas para evitar processamento duplicado
    private static final Map<Integer, Long> idsRecebidos = Collections.synchronizedMap(new HashMap<>());
    private static final long TEMPO_EXPIRACAO_IDS_MS = 5 * 60 * 1000; // 5 minutos

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: java UDPNode <nome_dispositivo>");
            return;
        }
        deviceName = args[0];

        DatagramSocket socket = new DatagramSocket(PORT);
        System.out.println("[" + deviceName + "] escutando na porta " + PORT);

        // Thread que escuta mensagens
        new Thread(() -> listen(socket)).start();

        // Thread que envia heartbeats
        new Thread(() -> heartbeat(socket)).start();

        // Thread que remove dispositivos inativos
        new Thread(() -> {
            while (true) {
                try {
                    deviceManager.removeInactiveDevices();
                    Thread.sleep(1000); // verifica a cada 1 segundo
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

        // Interface de comandos
        Scanner scanner = new Scanner(System.in);
        while (true) {
            System.out.print("> ");
            String linha = scanner.nextLine();
            tratarComando(linha, socket);
        }
    }

    private static void listen(DatagramSocket socket) {
        byte[] buffer = new byte[4096];
        while (true) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);

                String recebido = new String(packet.getData(), 0, packet.getLength());
                processarMensagem(recebido, packet.getAddress(), packet.getPort(), socket);

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void heartbeat(DatagramSocket socket) {
        while (true) {
            try {
                String mensagem = "HEARTBEAT:" + deviceName;
                byte[] data = mensagem.getBytes();
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"), PORT);
                socket.send(packet);
                Thread.sleep(5000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void processarMensagem(String mensagem, InetAddress remetente, int porta, DatagramSocket socket) {
        if (mensagem.startsWith("HEARTBEAT:")) {
            String nome = mensagem.substring(10);
            deviceManager.addOrUpdateDevice(nome, new Device(nome, remetente, porta));
        } else if (mensagem.startsWith("TALK:")) {
            String[] parts = mensagem.split(":", 4);
            if (parts.length >= 4) {
                int id = Integer.parseInt(parts[1]);
                String senderName = parts[2];
                String realMessage = parts[3];

                if (mensagemDuplicada(id)) {
                    System.out.println("[FALHA] Mensagem duplicada detectada (id:" + id + ")");
                    return;
                }

                System.out.println("[Recebido (id:" + id + ")] de " + senderName + " (" + remetente.getHostAddress() + "): " + realMessage);

                // enviar ACK
                sendAck(id, remetente, porta, socket);
            }
        } else if (mensagem.startsWith("ACK:")) {
            String[] parts = mensagem.split(":", 3);
            if (parts.length >= 3) {
                int id = Integer.parseInt(parts[1]);
                String senderName = parts[2];
                System.out.println("[Recebido ACK (id:" + id + ")] de " + senderName);
            }
        } else {
            System.out.println("[Mensagem desconhecida] " + mensagem);
        }
    }

    private static void sendAck(int id, InetAddress ipDestino, int portaDestino, DatagramSocket socket) {
        try {
            String ack = "ACK:" + id + ":" + deviceName;
            byte[] data = ack.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, ipDestino, portaDestino);
            socket.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void tratarComando(String linha, DatagramSocket socket) {
        String[] partes = linha.split(" ", 3);
        if (partes[0].equalsIgnoreCase("devices")) {
            deviceManager.listDevices(deviceName);
        } else if (partes[0].equalsIgnoreCase("talk") && partes.length >= 3) {
            String destino = partes[1];
            String mensagem = partes[2];
            enviarMensagem(destino, mensagem, socket);
        } else {
            System.out.println("Comandos disponíveis:");
            System.out.println("  devices                    (listar dispositivos)");
            System.out.println("  talk <destino> <mensagem>   (enviar mensagem)");
        }
    }

    private static void enviarMensagem(String destino, String mensagem, DatagramSocket socket) {
        try {
            Device device = deviceManager.getDevice(destino);
            if (device == null) {
                System.out.println("Destino não encontrado.");
                return;
            }
            int id = messageId.getAndIncrement(); // pega o próximo id e incrementa
            String mensagemCompleta = "TALK:" + id + ":" + deviceName + ":" + mensagem;
            byte[] data = mensagemCompleta.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, device.getIpAddress(), device.getPort());
            socket.send(packet);
            System.out.println("[Enviado (id:" + id + ")] para " + device.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean mensagemDuplicada(int id) {
        long agora = System.currentTimeMillis();
        
        // Limpa IDs expirados antes de checar
        idsRecebidos.entrySet().removeIf(entry -> (agora - entry.getValue()) > TEMPO_EXPIRACAO_IDS_MS);
    
        if (idsRecebidos.containsKey(id)) {
            // ID já recebido, é duplicado
            return true;
        } else {
            // Novo ID, registra
            idsRecebidos.put(id, agora);
            return false;
        }
    }
}
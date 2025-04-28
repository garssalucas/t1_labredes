package labredes;

import java.net.*;
import java.util.Scanner;

public class UDPNode {
    private static final int PORT = 8080; // porta fixa dentro dos containers
    private static String deviceName;
    private static DeviceManager deviceManager = new DeviceManager();

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
                processarMensagem(recebido, packet.getAddress(), packet.getPort());

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
                DatagramPacket packet = new DatagramPacket(data, data.length, InetAddress.getByName("255.255.255.255"),
                        PORT);
                socket.send(packet);
                Thread.sleep(5000);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void processarMensagem(String mensagem, InetAddress remetente, int porta) {
        if (mensagem.startsWith("HEARTBEAT:")) {
            String nome = mensagem.substring(10);
            deviceManager.addOrUpdateDevice(nome, new Device(nome, remetente, porta));
        } else {
            // Tratamento de mensagem normal: separar remetente e conteúdo
            String[] parts = mensagem.split(":", 2);
            String senderName = parts.length > 1 ? parts[0] : "Desconhecido";
            String realMessage = parts.length > 1 ? parts[1].trim() : mensagem;

            System.out.println("[Recebido] de " + senderName + " (" + remetente.getHostAddress() + "): " + realMessage);
        }
    }

    private static void tratarComando(String linha, DatagramSocket socket) {
        String[] partes = linha.split(" ", 3);
        if (partes[0].equals("devices")) {
            deviceManager.listDevices(deviceName);
        } else if (partes[0].equals("talk") && partes.length >= 3) {
            String destino = partes[1];
            String mensagem = partes[2];
            enviarMensagem(destino, mensagem, socket);
        } else {
            System.out.println("Comandos:");
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
            // Agora inclui o nome do dispositivo no início da mensagem
            String mensagemCompleta = deviceName + ": " + mensagem;
            byte[] data = mensagemCompleta.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, device.getIpAddress(), device.getPort());
            socket.send(packet);
            System.out.println("[Enviado] para " + device.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
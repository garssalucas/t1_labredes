package labredes;

import java.net.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Base64;
import java.io.*;

public class UDPNode {
    private static final int PORT = 8080;
    private static String deviceName;
    private static final DeviceManager deviceManager = new DeviceManager();
    private static final AtomicInteger messageId = new AtomicInteger(1); // ID global sequencial
    private static final Map<Integer, String> nomesArquivosRecebidos = new HashMap<>();

    // Controle de mensagens já recebidas para evitar processamento duplicado
    private static final Map<String, Long> idsRecebidos = Collections.synchronizedMap(new HashMap<>());
    private static final long TEMPO_EXPIRACAO_IDS_MS = 5 * 60 * 1000; // 5 minutos

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Uso: java UDPNode <nome_dispositivo>");
            return;
        }
        deviceName = args[0];

        DatagramSocket socket = new DatagramSocket(PORT);
        System.out.println("[" + deviceName + "] escutando na porta " + PORT);

        new Thread(() -> listen(socket)).start();
        new Thread(() -> heartbeat(socket)).start();
        new Thread(() -> {
            while (true) {
                try {
                    deviceManager.removeInactiveDevices();
                    Thread.sleep(1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();

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

                if (mensagemDuplicada("TALK-" + id)) {
                    System.out.println("[FALHA] Mensagem duplicada detectada (id:" + id + ")");
                    return;
                }

                System.out.println("[Recebido (id:" + id + ")] de " + senderName + " (" + remetente.getHostAddress() + "): " + realMessage);
                sendAck(id, remetente, porta, socket);
            }
        } else if (mensagem.startsWith("ACK:")) {
            String[] parts = mensagem.split(":", 3);
            if (parts.length >= 3) {
                int id = Integer.parseInt(parts[1]);
                String senderName = parts[2];
                System.out.println("[Recebido ACK (id:" + id + ")] de " + senderName);
            }
        } else if (mensagem.startsWith("FILE:")) {
            String[] parts = mensagem.split(":", 4);
            if (parts.length >= 4) {
                int id = Integer.parseInt(parts[1]);
                String nomeArquivo = parts[2];
                long tamanho = Long.parseLong(parts[3]);

                if (mensagemDuplicada("FILE-" + id)) {
                    System.out.println("[FALHA] FILE duplicado (id:" + id + ")");
                    return;
                }

                System.out.println("[FILE recebido (id:" + id + ")] Arquivo: " + nomeArquivo + ", Tamanho: " + tamanho + " bytes");
                nomesArquivosRecebidos.put(id, nomeArquivo);
                sendAck(id, remetente, porta, socket);
            }
        } else if (mensagem.startsWith("CHUNK:")) {
            String[] parts = mensagem.split(":", 4);
            if (parts.length >= 4) {
                int id = Integer.parseInt(parts[1]);
                int seq = Integer.parseInt(parts[2]);
                String dadosBase64 = parts[3];

                String chave = "CHUNK-" + id + "-" + seq;
                if (mensagemDuplicada(chave)) {
                    System.out.println("[FALHA] CHUNK duplicado (id:" + id + ", seq:" + seq + ")");
                    return;
                }

                File pasta = new File("arquivos_recebidos");
                if (!pasta.exists()) pasta.mkdirs();

                try {
                    byte[] dadosBytes = Base64.getDecoder().decode(dadosBase64);
                    String nomeOriginal = nomesArquivosRecebidos.getOrDefault(id, "temp_" + id + ".part");
                    FileOutputStream out = new FileOutputStream("arquivos_recebidos/" + nomeOriginal, true);
                    out.write(dadosBytes);
                    out.close();

                    System.out.println("[CHUNK recebido] id=" + id + " seq=" + seq + " (" + dadosBytes.length + " bytes)");
                    sendAck(id, remetente, porta, socket); 
                } catch (IOException e) {
                    e.printStackTrace();
                }
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
        } else if (partes[0].equalsIgnoreCase("sendfile") && partes.length >= 3) {
            String destino = partes[1];
            String nomeArquivo = partes[2];
            iniciarEnvioArquivo(destino, nomeArquivo, socket);
        } else {
            System.out.println("Comandos disponíveis:");
            System.out.println("  devices                    (listar dispositivos)");
            System.out.println("  talk <destino> <mensagem>   (enviar mensagem)");
            System.out.println("  sendfile <destino> <arquivo> (enviar arquivo)");
        }
    }

    private static void enviarMensagem(String destino, String mensagem, DatagramSocket socket) {
        try {
            Device device = deviceManager.getDevice(destino);
            if (device == null) {
                System.out.println("Destino não encontrado.");
                return;
            }
            int id = messageId.getAndIncrement();
            String mensagemCompleta = "TALK:" + id + ":" + deviceName + ":" + mensagem;
            byte[] data = mensagemCompleta.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, device.getIpAddress(), device.getPort());
            socket.send(packet);
            System.out.println("[Enviado (id:" + id + ")] para " + device.getName());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void iniciarEnvioArquivo(String destino, String nomeArquivo, DatagramSocket socket) {
        try {
            File file = new File("arquivos/" + nomeArquivo);
            if (!file.exists()) {
                System.out.println("[ERRO] Arquivo não encontrado: " + nomeArquivo);
                return;
            }

            Device device = deviceManager.getDevice(destino);
            if (device == null) {
                System.out.println("[ERRO] Destino não encontrado.");
                return;
            }

            int id = messageId.getAndIncrement();
            long tamanho = file.length();
            String mensagemFile = "FILE:" + id + ":" + nomeArquivo + ":" + tamanho;

            byte[] data = mensagemFile.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, device.getIpAddress(), device.getPort());
            socket.send(packet);

            System.out.println("[FILE enviado (id:" + id + ")] " + nomeArquivo + " (" + tamanho + " bytes)");

            int seq = 0;
            int tamBloco = 1024;
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[tamBloco];
                int lido;
                while ((lido = in.read(buffer)) != -1) {
                    byte[] chunkData = (lido == tamBloco) ? buffer : Arrays.copyOf(buffer, lido);
                    String dadosBase64 = Base64.getEncoder().encodeToString(chunkData);
                    String mensagemChunk = "CHUNK:" + id + ":" + seq + ":" + dadosBase64;

                    byte[] dados = mensagemChunk.getBytes();
                    DatagramPacket packetChunk = new DatagramPacket(dados, dados.length, device.getIpAddress(), device.getPort());
                    socket.send(packetChunk);

                    System.out.println("[CHUNK enviado] id=" + id + " seq=" + seq);
                    seq++;
                    Thread.sleep(50);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static boolean mensagemDuplicada(String chave) {
        long agora = System.currentTimeMillis();
        idsRecebidos.entrySet().removeIf(entry -> (agora - entry.getValue()) > TEMPO_EXPIRACAO_IDS_MS);

        if (idsRecebidos.containsKey(chave)) {
            return true;
        } else {
            idsRecebidos.put(chave, agora);
            return false;
        }
    }
}

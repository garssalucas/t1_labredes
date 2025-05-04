package labredes;

import java.net.*;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Arrays;
import java.util.Base64;
import java.io.*;

public class UDPNode {
    private static final int PORT = 8080;
    private static final int MAX_TENTATIVAS = 5;
    private static String deviceName;
    private static final DeviceManager deviceManager = new DeviceManager();
    private static final AtomicInteger messageId = new AtomicInteger(1);
    private static final Map<Integer, String> nomesArquivosRecebidos = new HashMap<>();
    private static final Map<String, Long> idsRecebidos = Collections.synchronizedMap(new HashMap<>());
    private static final long TEMPO_EXPIRACAO_IDS_MS = 5 * 60 * 1000;
    private static final Map<String, Boolean> acksRecebidos = new ConcurrentHashMap<>();
    private static final Map<String, byte[]> chunksPendentes = new ConcurrentHashMap<>();
    private static final Map<String, Long> tempoEnvioChunk = new ConcurrentHashMap<>();
    private static final Map<String, String> tipoMensagemEnviada = new ConcurrentHashMap<>();
    private static final Map<String, Integer> tentativasEnvioChunk = new ConcurrentHashMap<>();
    private static final java.time.format.DateTimeFormatter FORMATTER = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            log("Uso: java UDPNode <nome_dispositivo>");
            return;
        }
        deviceName = args[0];

        DatagramSocket socket = new DatagramSocket(PORT);
        log("[" + deviceName + "](" + socket.getLocalAddress().getHostAddress() + ") escutando na porta " + PORT);

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

        new Thread(() -> monitorarAcks(socket)).start();

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

    private static void monitorarAcks(DatagramSocket socket) {
        while (true) {
            try {
                for (Map.Entry<String, byte[]> entry : chunksPendentes.entrySet()) {
                    String chave = entry.getKey(); // CHUNK-<id>-<seq>-<destino>
                    byte[] dados = entry.getValue();
                    String[] partes = chave.split("-");
    
                    int id = Integer.parseInt(partes[1]);
                    int seq = Integer.parseInt(partes[2]);
                    String destino = partes[3];
    
                    String referencia = tipoMensagemEnviada.getOrDefault(id + ":" + seq, "DESCONHECIDO");
                    String chaveAck = "ACK-" + referencia + "-" + id + "-" + seq;
                    long tempoEnviado = tempoEnvioChunk.getOrDefault(chave, 0L);
                    int tentativas = tentativasEnvioChunk.getOrDefault(chave, 0);

                    if (!acksRecebidos.getOrDefault(chaveAck, false)) {
                        if (tentativas >= MAX_TENTATIVAS) {
                            log("[ERRO] Falha ao enviar CHUNK id=" + id + " seq=" + seq + " após " + MAX_TENTATIVAS + " tentativas para " + destino + "(" + deviceManager.getDevice(destino).getIpAddress() + ")");
                            chunksPendentes.remove(chave);
                            tempoEnvioChunk.remove(chave);
                            tentativasEnvioChunk.remove(chave);
                            continue;
                        }

                        if ((System.currentTimeMillis() - tempoEnviado) >= 1000) {
                            tempoEnvioChunk.put(chave, System.currentTimeMillis());
                            tentativasEnvioChunk.put(chave, tentativas + 1);
                            Device device = deviceManager.getDevice(destino);
                            if (device != null) {
                                DatagramPacket packet = new DatagramPacket(dados, dados.length, device.getIpAddress(), device.getPort());
                                socket.send(packet);
                                log("[RETRANSMISSÃO] CHUNK id=" + id + " seq=" + seq + " (tentativa " + (tentativas + 1) + ") para " + device.getName() + "(" + device.getIpAddress() + ")");
                            }
                        }
                    } else {
                        chunksPendentes.remove(chave);
                        tempoEnvioChunk.remove(chave);
                        tentativasEnvioChunk.remove(chave);
                    }
                }
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
                    log("[FALHA] TALK Mensagem duplicada detectada (id:" + id + ") de " + senderName + " (" + remetente.getHostAddress() + ")");
                    return;
                }

                log("[TALK Recebido] id=" + id + " de " + senderName + " (" + remetente.getHostAddress() + "): " + realMessage);
                sendAck(id, -1, remetente, porta, socket);
            }
        } else if (mensagem.startsWith("ACK:")) {
            String[] parts = mensagem.split(":", 4);
            if (parts.length >= 4) {
                int id = Integer.parseInt(parts[1]);
                int seq = Integer.parseInt(parts[2]);
                String senderName = parts[3];
                String referencia = tipoMensagemEnviada.getOrDefault(id + ":" + seq, "DESCONHECIDO");
                String chaveAck = "ACK-" + referencia + "-" + id + "-" + seq;
                acksRecebidos.put(chaveAck, true);
                log("[ACK Recebido] " + referencia + " id=" + id + " seq=" + seq + " de " + senderName + " (" + remetente.getHostAddress() + ")");
            }
        } else if (mensagem.startsWith("FILE:")) {
            String[] parts = mensagem.split(":", 5);
            if (parts.length >= 5) {
                int id = Integer.parseInt(parts[1]);
                String nomeArquivo = parts[2];
                long tamanho = Long.parseLong(parts[3]);
                String nomeRemetente = parts[4];
                if (mensagemDuplicada("FILE-" + id)) {
                    log("[FALHA] FILE duplicado (id:" + id + ") de " + nomeRemetente + " (" + remetente.getHostAddress() + ")");
                    return;
                }

                log("[FILE recebido] id=" + id + " Arquivo: " + nomeArquivo + ", Tamanho: " + tamanho + " bytes de " + nomeRemetente + " (" + remetente.getHostAddress() + ")");
                nomesArquivosRecebidos.put(id, nomeArquivo);
                sendAck(id, -1, remetente, porta, socket);
            }
        } else if (mensagem.startsWith("CHUNK:")) {
            String[] parts = mensagem.split(":", 5);
            if (parts.length >= 5) {
                int id = Integer.parseInt(parts[1]);
                int seq = Integer.parseInt(parts[2]);
                String dadosBase64 = parts[3];
                String nomeRemetente = parts[4];

                String chave = "CHUNK-" + id + "-" + seq;
                if (mensagemDuplicada(chave)) {
                    log("[FALHA] CHUNK duplicado (id:" + id + ", seq:" + seq + ") de " + nomeRemetente + " (" + remetente.getHostAddress() + ")");
                    return;
                }

                File pasta = new File("arquivos_recebidos");
                if (!pasta.exists()) pasta.mkdirs();

                byte[] dadosBytes;
                try {
                    dadosBytes = Base64.getDecoder().decode(dadosBase64);
                } catch (IllegalArgumentException e) {
                    log("[ERRO] Falha ao decodificar CHUNK id=" + id + " seq=" + seq + " de " + nomeRemetente + " (" + remetente.getHostAddress() + ")");
                    sendNack(id, "CHUNK inválido (base64)", remetente, porta, socket);
                    return;
                }

                String nomeOriginal = nomesArquivosRecebidos.getOrDefault(id, "temp_" + id + ".part");
                File arquivoDestino = new File("arquivos_recebidos/" + nomeOriginal);

                try (RandomAccessFile raf = new RandomAccessFile(arquivoDestino, "rw")) {
                    raf.seek(seq * 1024L);
                    raf.write(dadosBytes);
                } catch (IOException e) {
                    log("[ERRO] Falha ao gravar CHUNK id=" + id + " seq=" + seq + ": " + " de " + nomeRemetente + " (" + remetente.getHostAddress() + ")" + e.getMessage());
                    sendNack(id, "Falha ao gravar CHUNK seq=" + seq, remetente, porta, socket);
                    return;
                }

                log("[CHUNK recebido] id=" + id + " seq=" + seq + " (" + dadosBytes.length + " bytes) de " + nomeRemetente + " (" + remetente.getHostAddress() + ")");
                sendAck(id, seq, remetente, porta, socket);
            }
        } else if (mensagem.startsWith("END:")) {
            String[] parts = mensagem.split(":", 4);
            if (parts.length >= 4) {
                int id = Integer.parseInt(parts[1]);
                String hashRecebido = parts[2];
                String nomeRemetente = parts[3];
        
                String nomeArquivo = nomesArquivosRecebidos.get(id);
                if (nomeArquivo == null) {
                    log("[ERRO] Arquivo para id=" + id + " não encontrado. Enviando NACK.");
                    sendNack(id, "Arquivo não encontrado", remetente, porta, socket);
                    return;
                }
        
                File arquivo = new File("arquivos_recebidos/" + nomeArquivo);
                if (!arquivo.exists()) {
                    log("[ERRO] Arquivo físico não encontrado: " + nomeArquivo + ". Enviando NACK.");
                    sendNack(id, "Arquivo não existe no disco", remetente, porta, socket);
                    return;
                }
        
                String hashCalculado = calcularHashArquivo(arquivo);
                if (hashCalculado.equals(hashRecebido)) {
                    log("[END recebido] id=" + id + " hash verificado com sucesso de " + nomeRemetente + "("+ remetente.getHostAddress() + ")");
                    sendAck(id, -1, remetente, porta, socket); // ACK do END
                } else {
                    log("[ERRO] Hash divergente para id=" + id + ". Esperado: " + hashRecebido + " / Calculado: " + hashCalculado );
                    arquivo.delete(); // remove arquivo corrompido
                    sendNack(id, "Hash inválido. Arquivo corrompido", remetente, porta, socket);
                }
            }
        } else if (mensagem.startsWith("NACK:")) {
            String[] parts = mensagem.split(":", 4);
            if (parts.length >= 4) {
                int id = Integer.parseInt(parts[1]);
                String motivo = parts[2];
                String nomeRemetente = parts[3];
                log("[NACK Recebido] END id=" + id + " motivo=" + motivo + " de " + nomeRemetente + "("+ remetente.getHostAddress() + ")");
            }
        } else {
            log("[Mensagem desconhecida] " + mensagem);
        }
    }

    private static void sendAck(int id, int seq, InetAddress ipDestino, int portaDestino, DatagramSocket socket) {
        try {
            String ack = "ACK:" + id + ":" + seq + ":" + deviceName;
            byte[] data = ack.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, ipDestino, portaDestino);
            socket.send(packet);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendNack(int id, String motivo, InetAddress destino, int porta, DatagramSocket socket) {
        try {
            String nack = "NACK:" + id + ":" + motivo + ":" + deviceName;
            byte[] data = nack.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, destino, porta);
            socket.send(packet);
            //log("[NACK enviado] id=" + id + " motivo=" + motivo + " para " + " (" + destino.getHostAddress() + ")");
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
                log("Destino não encontrado.");
                return;
            }
            int id = messageId.getAndIncrement();
            String mensagemCompleta = "TALK:" + id + ":" + deviceName + ":" + mensagem;
            tipoMensagemEnviada.put(id + ":-1", "TALK");
            byte[] data = mensagemCompleta.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, device.getIpAddress(), device.getPort());
            socket.send(packet);
            log("[TALK Enviado] id=" + id + " para " + device.getName() + " (" + device.getIpAddress() + ")");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void iniciarEnvioArquivo(String destino, String nomeArquivo, DatagramSocket socket) {
        try {
            File file = new File("arquivos/" + nomeArquivo);
            if (!file.exists()) {
                log("[ERRO] Arquivo não encontrado: " + nomeArquivo);
                return;
            }

            Device device = deviceManager.getDevice(destino);
            if (device == null) {
                log("[ERRO] Destino não encontrado.");
                return;
            }

            int id = messageId.getAndIncrement();
            long tamanho = file.length();
            String mensagemFile = "FILE:" + id + ":" + nomeArquivo + ":" + tamanho + ":" + deviceName;
            tipoMensagemEnviada.put(id + ":-1", "FILE");
            byte[] data = mensagemFile.getBytes();
            DatagramPacket packet = new DatagramPacket(data, data.length, device.getIpAddress(), device.getPort());
            socket.send(packet);

            log("[FILE enviado] id=" + id + " -> " + nomeArquivo + " (" + tamanho + " bytes) para " + device.getName() + "(" + device.getIpAddress() + ")");
            
            int tentativas = 0;
            while (!acksRecebidos.getOrDefault("ACK-FILE-" + id + "-" + "-1", false) && tentativas < 35) {
                Thread.sleep(100); 
                tentativas++;
            }
            if (!acksRecebidos.getOrDefault("ACK-FILE-" + id + "-" + "-1", false)) {
                log("[ERRO] ACK do FILE id=" + id + " não recebido de " + device.getName() + "(" + device.getIpAddress() + "). Abortando envio.");
                return;
            }

            int seq = 0;
            int tamBloco = 1024;
            try (InputStream in = new FileInputStream(file)) {
                byte[] buffer = new byte[tamBloco];
                int lido;
                long totalLido = 0;
                while ((lido = in.read(buffer)) != -1) {
                    byte[] chunkData = (lido == tamBloco) ? buffer : Arrays.copyOf(buffer, lido);
                    String dadosBase64 = Base64.getEncoder().encodeToString(chunkData);
                    String mensagemChunk = "CHUNK:" + id + ":" + seq + ":" + dadosBase64 + ":" + deviceName;

                    byte[] dados = mensagemChunk.getBytes();
                    DatagramPacket packetChunk = new DatagramPacket(dados, dados.length, device.getIpAddress(), device.getPort());
                    tipoMensagemEnviada.put(id + ":" + seq, "CHUNK");
                    String referencia = tipoMensagemEnviada.getOrDefault(id + ":" + seq, "DESCONHECIDO");
                    String chaveAck = "ACK-" + referencia + "-" + id + "-" + seq;
                    chunksPendentes.put("CHUNK-" + id + "-" + seq + "-" + destino, dados);
                    tentativasEnvioChunk.put("CHUNK-" + id + "-" + seq + "-" + destino, 0);
                    acksRecebidos.put(chaveAck, false);
                    tempoEnvioChunk.put("CHUNK-" + id + "-" + seq + "-" + destino, System.currentTimeMillis());
                    socket.send(packetChunk);
                    totalLido += lido;
                    int percentual = (int) ((100.0 * totalLido) / tamanho);
                    log("[CHUNK enviado] id=" + id + " seq=" + seq + " (" + percentual + "% enviado) para " + device.getName() + "(" + device.getIpAddress() + ")");
                    seq++;
                    Thread.sleep(50);
                }
                
                while (true) {
                    // Verifica se ainda há CHUNKs pendentes desse ID que estão ativos
                    boolean aindaTemChunkAtivo = chunksPendentes.keySet().stream()
                        .filter(k -> k.startsWith("CHUNK-" + id + "-"))
                        .anyMatch(k -> {
                            int tent = tentativasEnvioChunk.getOrDefault(k, 0);
                            return tent < MAX_TENTATIVAS;
                        });
                
                    if (!aindaTemChunkAtivo) break;
                
                    Thread.sleep(100); // espera antes de checar novamente
                }
                boolean falhou = chunksPendentes.keySet().stream().anyMatch(k -> k.startsWith("CHUNK-" + id + "-"));
                if (falhou) log("[ERRO] Não foi possível enviar todos os CHUNKs (id=" + id + ") ");
        

                String hash = calcularHashArquivo(file); 
                String mensagemEnd = "END:" + id + ":" + hash + ":" + deviceName;
                tipoMensagemEnviada.put(id + ":-1", "END");
                byte[] dadosEnd = mensagemEnd.getBytes();
                DatagramPacket packetEnd = new DatagramPacket(dadosEnd, dadosEnd.length, device.getIpAddress(), device.getPort());
                socket.send(packetEnd);
                log("[END enviado] id=" + id + " hash=" + hash + " para " + device.getName() + "(" + device.getIpAddress() + ")");

                // Aguarda ACK do END
                int tentativa = 0;
                while (!acksRecebidos.getOrDefault("ACK-END-" + id + "-" + "-1", false) && tentativa < 35) {
                    Thread.sleep(100); 
                    tentativa++;
                }
                if (!acksRecebidos.getOrDefault("ACK-END-" + id + "-" + "-1", false)) {
                    log("[AVISO] Não foi possível confirmar se " + destino + "(" + deviceManager.getDevice(destino).getIpAddress() + ") validou o arquivo (ACK de END não recebido)");
                    return;
                }
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static String calcularHashArquivo(File file) {
        try (InputStream in = new FileInputStream(file)) {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            byte[] hashBytes = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return "erro";
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

    private static void log(String mensagem) {
        String timestamp = java.time.LocalTime.now().format(FORMATTER);
        System.out.println("[" + timestamp + "] " + mensagem);
    }
}

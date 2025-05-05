package labredes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceManager {
    private Map<String, Device> devices = new ConcurrentHashMap<>();

    public boolean addOrUpdateDevice(String name, Device device) {
        if (devices.containsKey(name)) {
            devices.get(name).updateLastSeen();
            return false; // já existia
        } else {
            devices.put(name, device);
            return true; // é novo!
        }
    }

    public Device getDevice(String name) {
        return devices.get(name);
    }

    public void listDevices(String deviceLocal) {
        System.out.println("Dispositivos ativos:");
        long agora = System.currentTimeMillis();
        for (Device device : devices.values()) {
            long seconds = (agora - device.getLastSeen()) / 1000;
            String obs = "";
            if(deviceLocal.equals(device.getName()))
                obs = "(Este Dispositivo)"; 
            System.out.println("- " + device.getName() + " (" + device.getIpAddress().getHostAddress() + ":" + device.getPort() + ") " + seconds + "s atrás" + obs );
        }
    }

    public void removeInactiveDevices() {
        long agora = System.currentTimeMillis();
        Iterator<Map.Entry<String, Device>> it = devices.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Device> entry = it.next();
            Device device = entry.getValue();
            if (agora - device.getLastSeen() > 10_000) { // 10 segundos
                System.out.println("[Dispositivo desconectado] " + entry.getKey() + " (" + device.getIpAddress().getHostAddress() + ")");
                it.remove();
            }
        }
    }
}
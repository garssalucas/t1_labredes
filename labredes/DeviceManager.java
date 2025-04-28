package labredes;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class DeviceManager {
    private Map<String, Device> devices = new ConcurrentHashMap<>();

    public void addOrUpdateDevice(String name, Device device) {
        if (devices.containsKey(name)) {
            devices.get(name).updateLastSeen();
        } else {
            devices.put(name, device);
            System.out.println("[Novo dispositivo] " + name + " (" + device.getIpAddress().getHostAddress() + ")");
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
        devices.values().removeIf(device -> (agora - device.getLastSeen()) > 10000); // mais de 10s inativo
    }
}
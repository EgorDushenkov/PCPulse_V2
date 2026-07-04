package pcpulse.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.*;

public class SystemMonitor {
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final double GB = 1073741824.0;

    private final SystemInfo si = new SystemInfo();
    private final HardwareAbstractionLayer hal = si.getHardware();
    private final OperatingSystem os = si.getOperatingSystem();

    private long[] prevTicks;
    private long prevTime = System.currentTimeMillis();
    private Map<String, Long> prevRx = new HashMap<>();
    private Map<String, Long> prevTx = new HashMap<>();

    public SystemMonitor() {
        prevTicks = hal.getProcessor().getSystemCpuLoadTicks();
        for (NetworkIF net : hal.getNetworkIFs()) {
            prevRx.put(net.getName(), net.getBytesRecv());
            prevTx.put(net.getName(), net.getBytesSent());
        }
    }

    public String getLocalIp() {
        try {
            String best = null;
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                NetworkInterface iface = ifaces.nextElement();
                String name = iface.getDisplayName().toLowerCase();
                // отсеиваем всякие виртуалки и WSL — они только мешают
                if (iface.isLoopback() || !iface.isUp()
                    || name.contains("virtual") || name.contains("vmware")
                    || name.contains("wsl") || name.contains("host-only")
                    || name.contains("hyper-v")) continue;

                Enumeration<InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String ip = addr.getHostAddress();
                        // 192.168.— самый частый домашний диапазон, приоритет ему
                        if (ip.startsWith("192.168.")) {
                            best = ip;
                        } else if (best == null && (ip.startsWith("10.") || ip.startsWith("172."))) {
                            best = ip;
                        } else if (best == null) {
                            best = ip;
                        }
                    }
                }
            }
            return best != null ? best : InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public ObjectNode buildFullState(ObjectNode workerState) {
        ObjectNode state = mapper.createObjectNode();

        try {
            state.put("pc_name", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            state.put("pc_name", "Unknown");
        }

        state.put("local_ip", getLocalIp());
        state.put("status", "online");

        Calendar cal = Calendar.getInstance();
        state.put("time", String.format("%02d:%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)));
        state.put("uptime", (System.currentTimeMillis() - os.getSystemBootTime() * 1000L) / 3600000.0);

        CentralProcessor cpu = hal.getProcessor();
        ObjectNode cpuNode = state.putObject("cpu");
        try {
            cpuNode.put("name", cpu.getProcessorIdentifier().getName());
            cpuNode.put("usage", Math.round(cpu.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0));
            prevTicks = cpu.getSystemCpuLoadTicks();
            cpuNode.put("cores", cpu.getPhysicalProcessorCount());
        } catch (Throwable t) {
            cpuNode.put("usage", 0);
        }

        ObjectNode ramNode = state.putObject("ram");
        try {
            GlobalMemory mem = hal.getMemory();
            long totalRam = mem.getTotal();
            long freeRam = mem.getAvailable();
            long usedRam = totalRam - freeRam;
            ramNode.put("usage", Math.round(((double) usedRam / totalRam) * 100));
            ramNode.put("total", roundGb(totalRam));
            ramNode.put("used", roundGb(usedRam));
            ramNode.put("free", roundGb(freeRam));
        } catch (Throwable t) {
            ramNode.put("usage", 0);
        }

        ObjectNode netNode = state.putObject("network");
        try {
            long now = System.currentTimeMillis();
            long dt = now - prevTime;
            long totRx = 0, totTx = 0;
            for (NetworkIF net : hal.getNetworkIFs()) {
                net.updateAttributes();
                long rx = net.getBytesRecv();
                long tx = net.getBytesSent();
                totRx += rx - prevRx.getOrDefault(net.getName(), rx);
                totTx += tx - prevTx.getOrDefault(net.getName(), tx);
                prevRx.put(net.getName(), rx);
                prevTx.put(net.getName(), tx);
            }
            prevTime = now;

            if (dt > 0) {
                netNode.put("down_kbps", Math.round((totRx * 8.0 / 1024.0) / (dt / 1000.0) * 10.0) / 10.0);
                netNode.put("up_kbps", Math.round((totTx * 8.0 / 1024.0) / (dt / 1000.0) * 10.0) / 10.0);
            } else {
                netNode.put("down_kbps", 0);
                netNode.put("up_kbps", 0);
            }
        } catch (Throwable t) {
            netNode.put("down_kbps", 0);
            netNode.put("up_kbps", 0);
        }

        ArrayNode procs = state.putArray("procs");
        try {
            List<OSProcess> pList = os.getProcesses(null, OperatingSystem.ProcessSorting.CPU_DESC, 20);
            int count = 0;
            int logicalCores = cpu.getLogicalProcessorCount();
            for (OSProcess p : pList) {
                String pName = p.getName().toLowerCase();
                if (p.getProcessID() == 0 || pName.contains("idle") || pName.contains("бездействие")) continue;
                if (pName.contains("pcpulseserver") || pName.contains("worker.exe")
                    || pName.contains("javaw.exe") || pName.contains("java.exe")) continue;

                ObjectNode pNode = mapper.createObjectNode();
                pNode.put("pid", p.getProcessID());
                pNode.put("name", p.getName());
                long cpuVal = Math.round(100d * (p.getKernelTime() + p.getUserTime()) / Math.max(1, p.getUpTime()) / logicalCores);
                pNode.put("cpu", Math.min(100, Math.max(0, cpuVal)));
                procs.add(pNode);
                if (++count >= 5) break;
            }
        } catch (Throwable t) {}

        ArrayNode disks = state.putArray("disks");
        try {
            for (OSFileStore fs : os.getFileSystem().getFileStores()) {
                long total = fs.getTotalSpace();
                if (total <= 0 || fs.getMount().isEmpty()) continue;
                long used = total - fs.getUsableSpace();
                ObjectNode disk = mapper.createObjectNode();
                disk.put("dev", fs.getMount());
                disk.put("total", roundGb(total));
                disk.put("percent", Math.round((double) used / total * 1000.0) / 10.0);
                disks.add(disk);
            }
        } catch (Throwable t) {}

        if (workerState != null) {
            // мерджим то, что пришло от python worker'а
            for (String key : List.of("cpu_temp", "cpu_freq", "gpu", "fans",
                    "volume", "mic_muted", "audio_sessions", "media",
                    "active_app", "running_apps")) {
                if (workerState.has(key)) {
                    if (key.equals("cpu_temp")) cpuNode.set("temp", workerState.get(key));
                    else if (key.equals("cpu_freq")) cpuNode.set("freq", workerState.get(key));
                    else state.set(key, workerState.get(key));
                }
            }
        }

        return state;
    }

    private double roundGb(long bytes) {
        return Math.round(bytes / GB * 10.0) / 10.0;
    }
}

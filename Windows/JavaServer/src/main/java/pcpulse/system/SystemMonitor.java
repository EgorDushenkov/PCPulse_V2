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
    private SystemInfo si = new SystemInfo();
    private HardwareAbstractionLayer hal = si.getHardware();
    private OperatingSystem os = si.getOperatingSystem();

    private long[] oldTicks;
    private long oldTime = System.currentTimeMillis();
    private Map<String, Long> oldRx = new HashMap<>();
    private Map<String, Long> oldTx = new HashMap<>();

    public SystemMonitor() {
        oldTicks = hal.getProcessor().getSystemCpuLoadTicks();
        for (NetworkIF net : hal.getNetworkIFs()) {
            oldRx.put(net.getName(), net.getBytesRecv());
            oldTx.put(net.getName(), net.getBytesSent());
        }
    }

    public String getLocalIp() {
        try {
            String bestIp = null;
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                String dName = iface.getDisplayName().toLowerCase();
                if (iface.isLoopback() || !iface.isUp() || dName.contains("virtual") || dName.contains("vmware") || dName.contains("wsl") || dName.contains("host-only") || dName.contains("hyper-v")) continue;
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while(addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof java.net.Inet4Address) {
                        String a = addr.getHostAddress();
                        if (a.startsWith("192.168.")) {
                            bestIp = a;
                        } else if (bestIp == null && (a.startsWith("10.") || a.startsWith("172."))) {
                            bestIp = a;
                        } else if (bestIp == null) {
                            bestIp = a;
                        }
                    }
                }
            }
            if (bestIp != null) return bestIp;
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public ObjectNode buildFullState(ObjectNode latestWorkerState) {
        ObjectNode state = mapper.createObjectNode();
        
        try { state.put("pc_name", InetAddress.getLocalHost().getHostName()); } 
        catch (Exception e) { state.put("pc_name", "Unknown"); }
        
        state.put("local_ip", getLocalIp());
        state.put("status", "online");
        
        Calendar cal = Calendar.getInstance();
        state.put("time", String.format("%02d:%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), cal.get(Calendar.SECOND)));
        state.put("uptime", (System.currentTimeMillis() - os.getSystemBootTime() * 1000L) / 3600000.0);

        // CPU
        ObjectNode cpu = state.putObject("cpu");
        CentralProcessor processor = hal.getProcessor();
        cpu.put("name", processor.getProcessorIdentifier().getName());
        cpu.put("usage", Math.round(processor.getSystemCpuLoadBetweenTicks(oldTicks) * 100.0));
        oldTicks = processor.getSystemCpuLoadTicks();
        cpu.put("cores", processor.getPhysicalProcessorCount());
        
        // RAM
        ObjectNode ram = state.putObject("ram");
        GlobalMemory memory = hal.getMemory();
        long totalRam = memory.getTotal();
        long freeRam = memory.getAvailable();
        long usedRam = totalRam - freeRam;
        ram.put("usage", Math.round(((double)usedRam / totalRam) * 100));
        ram.put("total", Math.round(totalRam / 1073741824.0 * 10.0) / 10.0);
        ram.put("used", Math.round(usedRam / 1073741824.0 * 10.0) / 10.0);
        ram.put("free", Math.round(freeRam / 1073741824.0 * 10.0) / 10.0);

        // Network
        long currTime = System.currentTimeMillis();
        long dt = currTime - oldTime;
        long totRx = 0, totTx = 0;
        for (NetworkIF net : hal.getNetworkIFs()) {
            net.updateAttributes();
            long crx = net.getBytesRecv();
            long ctx = net.getBytesSent();
            Long orx = oldRx.getOrDefault(net.getName(), crx);
            Long otx = oldTx.getOrDefault(net.getName(), ctx);
            totRx += (crx - orx);
            totTx += (ctx - otx);
            oldRx.put(net.getName(), crx);
            oldTx.put(net.getName(), ctx);
        }
        oldTime = currTime;
        
        ObjectNode netNode = state.putObject("network");
        if (dt > 0) {
            netNode.put("down_kbps", Math.round((totRx * 8.0 / 1024.0) / (dt / 1000.0) * 10.0) / 10.0);
            netNode.put("up_kbps", Math.round((totTx * 8.0 / 1024.0) / (dt / 1000.0) * 10.0) / 10.0);
        } else {
            netNode.put("down_kbps", 0);
            netNode.put("up_kbps", 0);
        }

        ArrayNode procs = state.putArray("procs");
        List<OSProcess> pList = os.getProcesses(null, OperatingSystem.ProcessSorting.CPU_DESC, 20);
        int procCount = 0;
        int logicalCores = hal.getProcessor().getLogicalProcessorCount();
        for (OSProcess p : pList) {
            String pName = p.getName().toLowerCase();
            if (p.getProcessID() == 0 || pName.contains("idle") || pName.contains("бездействие")) continue;
            if (pName.contains("pcpulseserver") || pName.contains("worker.exe") || pName.contains("javaw.exe") || pName.contains("java.exe")) continue;

            ObjectNode pNode = mapper.createObjectNode();
            pNode.put("pid", p.getProcessID());
            pNode.put("name", p.getName());
            
            long cpuVal = Math.round(100d * (p.getKernelTime() + p.getUserTime()) / Math.max(1, p.getUpTime()) / logicalCores);
            pNode.put("cpu", Math.min(100, Math.max(0, cpuVal)));
            
            procs.add(pNode);
            procCount++;
            if (procCount >= 5) break;
        }

        ArrayNode disks = state.putArray("disks");
        for (OSFileStore fs : os.getFileSystem().getFileStores()) {
            long total = fs.getTotalSpace();
            if (total > 0 && fs.getMount().length() > 0) {
                long usable = fs.getUsableSpace();
                long used = total - usable;
                double percent = (double) used / total * 100.0;
                
                ObjectNode diskNode = mapper.createObjectNode();
                diskNode.put("dev", fs.getMount());
                diskNode.put("total", Math.round(total / 1073741824.0 * 10.0) / 10.0);
                diskNode.put("percent", Math.round(percent * 10.0) / 10.0);
                disks.add(diskNode);
            }
        }
        
        if (latestWorkerState != null) {
            if (latestWorkerState.has("cpu_temp")) cpu.set("temp", latestWorkerState.get("cpu_temp"));
            if (latestWorkerState.has("cpu_freq")) cpu.set("freq", latestWorkerState.get("cpu_freq"));
            if (latestWorkerState.has("gpu")) state.set("gpu", latestWorkerState.get("gpu"));
            if (latestWorkerState.has("fans")) state.set("fans", latestWorkerState.get("fans"));
            if (latestWorkerState.has("volume")) state.set("volume", latestWorkerState.get("volume"));
            if (latestWorkerState.has("mic_muted")) state.set("mic_muted", latestWorkerState.get("mic_muted"));
            if (latestWorkerState.has("audio_sessions")) state.set("audio_sessions", latestWorkerState.get("audio_sessions"));
            if (latestWorkerState.has("media")) state.set("media", latestWorkerState.get("media"));
            if (latestWorkerState.has("active_app")) state.set("active_app", latestWorkerState.get("active_app"));
            if (latestWorkerState.has("running_apps")) state.set("running_apps", latestWorkerState.get("running_apps"));
        }

        return state;
    }
}

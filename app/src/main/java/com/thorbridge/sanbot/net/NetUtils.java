package com.thorbridge.sanbot.net;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class NetUtils {

    public static final class Iface {
        public final String name;
        public final String ip;
        public final InetAddress broadcast;

        Iface(String name, String ip, InetAddress broadcast) {
            this.name = name;
            this.ip = ip;
            this.broadcast = broadcast;
        }

        /** eth0 = Ethernet adapter, wlan0 = Wi-Fi, rndis0/usb0 = USB tethering. */
        public String kind() {
            if (name.startsWith("eth")) return "Ethernet";
            if (name.startsWith("wlan")) return "Wi-Fi";
            if (name.startsWith("rndis") || name.startsWith("usb")) return "USB tethering";
            if (name.startsWith("ap") || name.startsWith("softap")) return "Hotspot";
            return "other";
        }
    }

    private NetUtils() {}

    /** TCP ports in LISTEN state on this tablet with the owning app, e.g. "127.0.0.1:12000 com.sunbo.main". */
    public static List<String> listeningPorts(Context ctx) {
        List<String> out = new ArrayList<>();
        PackageManager pm = ctx.getPackageManager();
        for (String file : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try (BufferedReader r = new BufferedReader(new FileReader(file))) {
                r.readLine();
                String line;
                while ((line = r.readLine()) != null) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 8 || !"0A".equals(f[3])) continue;
                    String[] local = f[1].split(":");
                    int port = Integer.parseInt(local[1], 16);
                    String ip = local[0].matches("0+") ? "*" : local[0].endsWith("0100007F") ? "127.0.0.1" : local[0];
                    String owner = pm.getNameForUid(Integer.parseInt(f[7]));
                    String entry = ip + ":" + port + " " + (owner != null ? owner : "uid " + f[7]);
                    if (!out.contains(entry)) out.add(entry);
                }
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** IPv4 addresses of all interfaces that are up (loopback excluded). */
    public static List<Iface> interfaces() {
        List<Iface> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getAddress() instanceof Inet4Address) {
                        out.add(new Iface(ni.getName(), ia.getAddress().getHostAddress(), ia.getBroadcast()));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}

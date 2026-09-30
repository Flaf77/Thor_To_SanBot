package com.thorbridge.sanbot.net;

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

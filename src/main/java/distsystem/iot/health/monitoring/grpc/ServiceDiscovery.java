/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */
package distsystem.iot.health.monitoring.grpc;

import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.jmdns.JmDNS;
import javax.jmdns.ServiceEvent;
import javax.jmdns.ServiceInfo;
import javax.jmdns.ServiceListener;

/**
 *
 * @author opeyemiadeniji
 */


/**
 * Discovers gRPC services advertised via mDNS (JmDNS).
 * Uses the lecturer's original approach, with a cross-platform address
 * fix so it works on both Windows and macOS.
 */
public class ServiceDiscovery {

    private String requiredServiceType;
    private String requiredServiceName;
    private JmDNS jmdns;
    private int discoveredPort = -1;

    public ServiceDiscovery(String inServiceType, String inServiceName) {
        requiredServiceType = inServiceType;
        requiredServiceName = inServiceName;
    }

    public int discoverService(long timeoutMilliseconds) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);

        try {
            // --- The only change from the lecturer's version ---
            // InetAddress.getLocalHost() returns 127.0.0.1 on macOS, which blocks
            // mDNS multicast loopback. We find the real LAN address instead.
            // On Windows, getLocalHost() already returns the LAN address, so this
            // helper also works there.
            InetAddress bindAddress = getRealLocalAddress();
            System.out.println("Client: binding JmDNS to: " + bindAddress);

            jmdns = JmDNS.create(bindAddress);

            // Everything below is identical to the lecturer's approach
            jmdns.addServiceListener(requiredServiceType, new ServiceListener() {

                @Override
                public void serviceAdded(ServiceEvent event) {
                    System.out.println("Service added: " + event.getName());
                    jmdns.requestServiceInfo(event.getType(), event.getName(), 1000);
                }

                @Override
                public void serviceRemoved(ServiceEvent event) {
                    System.out.println("Service removed: " + event.getInfo());
                }

                @Override
                public void serviceResolved(ServiceEvent event) {
                    System.out.println("Service resolved: " + event.getInfo());
                    ServiceInfo info = event.getInfo();
                    String resolvedServiceName = info.getName();
                    System.out.println("Resolved service name: " + resolvedServiceName);
                    System.out.println("Resolved service port: " + info.getPort());

                    if (resolvedServiceName.contains(requiredServiceName)) {
                        discoveredPort = info.getPort();
                        System.out.println("Discovered " + resolvedServiceName
                                + " on port " + discoveredPort);
                        latch.countDown();
                    }
                }
            });

        } catch (UnknownHostException e) {
            System.out.println("UnknownHostException: " + e.getMessage());
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }

        latch.await(timeoutMilliseconds, TimeUnit.MILLISECONDS);
        System.out.println("discoverService returning port: " + discoveredPort);
        return discoveredPort;
    }

    public void close() throws IOException {
        if (jmdns != null) {
            jmdns.close();
        }
    }

    /**
     * Returns the machine's real LAN IP address (e.g. 192.168.x.x or 10.x.x.x).
     * Falls back to getLocalHost() if no suitable address is found, which
     * preserves the original behaviour on Windows where getLocalHost() already
     * returns the LAN address.
     */
    private InetAddress getRealLocalAddress() throws UnknownHostException {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                // Skip loopback, inactive, and virtual interfaces
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) {
                    continue;
                }

                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();

                    // Only IPv4, and not loopback
                    if (!addr.isLoopbackAddress()
                            && addr.getHostAddress().contains(".")) {
                        return addr;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Could not enumerate network interfaces: " + e.getMessage());
        }

        // Fallback — works on Windows where getLocalHost() gives the LAN address
        return InetAddress.getLocalHost();
    }
}